package me.iacn.biliroaming.hook

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.text.SpannableStringBuilder
import android.text.style.ClickableSpan
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.PopupWindow
import android.widget.TextView
import me.iacn.biliroaming.BiliBiliPackage.Companion.instance
import me.iacn.biliroaming.utils.*
import org.json.JSONObject

class CopyHook(classLoader: ClassLoader) : BaseHook(classLoader) {
    companion object {
        private val DYNAMIC_COPYABLE_IDS = arrayOf(
            "dy_card_text",
            "dy_opus_paragraph_desc",
            "dy_opus_paragraph_title",
            "dy_opus_copy_right_id",
            "dy_opus_paragraph_text",
        )

        // 简介视图 -> 完整简介文本
        private val descFullText = java.util.WeakHashMap<View, String>()

        /** Activity 层长按识别阈值 */
        private const val LONG_PRESS_MS = 600L
        private const val LONG_PRESS_SLOP = 24f

        /**
         * 启用长按兜底的页面类名关键字。
         * 9.8.0 的动态/opus 详情页类名不一定包含 "detail"，因此放宽为一组关键字；
         * 非目标页面只记录一次日志，便于后续定位新页面。
         */
        private val TARGET_PAGE_KEYWORDS = arrayOf(
            "detail", "opus", "dynamic", "followinglist", "intro", "bangumi"
        )
    }

    private val enhanceLongClickCopy = sPrefs.getBoolean("comment_copy_enhance", false)

    // Activity 层长按兜底：9.8.0 的简介控件只在带 span 的小区域回调 performLongClick，
    // 而且它的继承方法无法用 findAndHookMethod 挂载，没法靠给视图装监听覆盖整块文字。
    // 因此在 dispatchTouchEvent 里自行识别长按，再按坐标命中真正显示文本的视图。
    @Volatile
    private var pressDownAt = 0L

    @Volatile
    private var pressDownX = 0f

    @Volatile
    private var pressDownY = 0f

    // 已装过长按监听的 RichTextView（弱引用），用于只记录"首次覆盖 App 监听"的诊断
    private val longClickViews =
        java.util.Collections.newSetFromMap(java.util.WeakHashMap<View, Boolean>())

    // 已给哪些简介视图装过长按监听（弱引用），避免 setText/setter 反复安装
    private val descLongClickViews =
        java.util.Collections.newSetFromMap(java.util.WeakHashMap<View, Boolean>())

    // 已经深度安装过长按监听的 View（弱引用）：简介/评论的文字常由子 View 绘制，
    // 只给容器装监听会出现"只有容器空白处（左侧一小块）能长按"的问题。
    private val deepLongClickViews =
        java.util.Collections.newSetFromMap(java.util.WeakHashMap<View, Boolean>())

    // 长按兜底"跳过"过的页面类名（只记一次，避免刷屏）
    private val loggedSkipPages =
        java.util.Collections.synchronizedSet(java.util.LinkedHashSet<String>())

    override fun startHook() {
        if (!sPrefs.getBoolean("comment_copy", false)) return

        // 记录当前 Activity，供需要窗口 token 的对话框使用（不再记录 URL / 界面类名，避免隐私外泄）
        CurrentActivity.install()
        instance.descCopyView().zip(instance.descCopy()).forEach { p ->
            val clazz = p.first ?: return@forEach
            val method = p.second ?: return@forEach
            clazz.replaceMethod(
                method,
                View::class.java,
                ClickableSpan::class.java
            ) { param ->
                if (!enhanceLongClickCopy) return@replaceMethod Unit

                param.thisObject.getFirstFieldByExactTypeOrNull<SpannableStringBuilder>()?.let {
                    val view = param.args[0] as View
                    showCopyDialog(view.context, it, param)
                } ?: (param.args[0] as? TextView)?.let { tv ->
                    showCopyDialog(tv.context, tv.text, param)
                }
            }
        }

        // 9.8.0+: 视频简介视图为 tv.danmaku.bili.videopage.common.widget.view.ExpandableTextView，
        // 不再自带复制监听器，且原 (View, ClickableSpan) 渲染方法已不存在。
        // 在简介文本 setter 触发时捕获全文，并拦截 performLongClick 弹复制对话框
        if (enhanceLongClickCopy) {
            installDetailLongPressFallback()
            "tv.danmaku.bili.videopage.common.widget.view.ExpandableTextView"
                .findClassOrNull(mClassLoader)?.let { clazz ->
                    Log.d("hooked desc copy view: ${clazz.name}")
                    // 诊断（有界）：记录实际收到长按的 View 类名，用于定位 9.8.0 简介控件真实类型。
                    // 每个类只记一次，最多 40 个，避免刷屏。
                    val loggedLongClickClasses =
                        java.util.Collections.synchronizedSet(java.util.LinkedHashSet<String>())
                    View::class.java.hookAfterMethod("performLongClick") { param ->
                        val cls = param.thisObject.javaClass.name
                        if (cls.startsWith("android.") || cls.startsWith("androidx.") ||
                            cls.startsWith("kotlin.")
                        ) {
                            return@hookAfterMethod
                        }
                        if (loggedLongClickClasses.size < 40 && loggedLongClickClasses.add(cls)) {
                            Log.d("CopyHook: longClick view class=$cls")
                        }
                    }
                    fun installDescLongClick(view: View) {
                        if (view.javaClass.name.endsWith("ExpandableTextView") &&
                            descLongClickViews.add(view)
                        ) {
                            Log.d(
                                "CopyHook: 简介视图状态 class=${view.javaClass.name} " +
                                        "attached=${view.isAttachedToWindow} shown=${view.isShown} " +
                                        "longClickable=${view.isLongClickable} " +
                                        "hasLongClick=${view.hasOnLongClickListeners()}"
                            )
                        }
                        installLongClickDeep(view, { pressed ->
                            val full = descFullText[pressed]?.takeIf { it.isNotEmpty() }
                                ?: descFullText[view]?.takeIf { it.isNotEmpty() }
                            if (full.isNullOrEmpty()) return@installLongClickDeep false
                            Log.d(
                                "CopyHook: 简介长按触发 class=${pressed.javaClass.name} " +
                                        "textLen=${full.length}"
                            )
                            showLongPressCopyDialog(pressed.context, full)
                            true
                        }, "简介")
                    }
                    listOf("T", "setOriginText", "setExpandedDesc", "setRetractedDesc").forEach { name ->
                        var loggedOnce = false
                        clazz.hookAfterAllMethods(name) { param ->
                            if (!loggedOnce) {
                                loggedOnce = true
                                Log.d("CopyHook: 简介 setter 命中 $name")
                            }
                            val view = param.thisObject as? View ?: return@hookAfterAllMethods
                            val text = extractDescText(param.args.getOrNull(0))?.toString()
                            if (text != null) {
                                // 展开态/收起态的 setter 调用顺序不固定，后写入的可能反而是
                                // 更短的收起态文本。这里只保留最长的一份，保证"复制全部"拿到全文。
                                val old = descFullText[view]
                                if (old == null || text.length > old.length) {
                                    descFullText[view] = text
                                }
                            }
                            installDescLongClick(view)
                        }
                    }
                    // 部分路径直接用 setText 设置简介文本，同样要捕获全文并安装长按监听
                    clazz.hookAfterMethod(
                        "setText", CharSequence::class.java, TextView.BufferType::class.java
                    ) { param ->
                        val view = param.thisObject as? View ?: return@hookAfterMethod
                        val text = (param.args.getOrNull(0) as? CharSequence)?.toString()
                        if (!text.isNullOrEmpty()) {
                            val old = descFullText[view]
                            if (old == null || text.length > old.length) descFullText[view] = text
                        }
                        installDescLongClick(view)
                    }
                    // 诊断：触摸是否真的到达简介视图（用于判断长按是否被上层拦截）
                    var touchLogged = false
                    clazz.hookAfterMethod("onTouchEvent", MotionEvent::class.java) { _ ->
                        if (!touchLogged) {
                            touchLogged = true
                            Log.d("CopyHook: 简介视图收到触摸事件")
                        }
                    }
                    // 9.8.0 的简介控件在 setter 触发时尚未挂载/布局（日志显示 attached=false、
                    // 遍历只有 1 个 View），真正的文字控件是之后才添加的子 View。
                    // 因此在挂载与每次布局时重新递归安装监听，覆盖后来加入的子 View。
                    clazz.hookAfterMethod("onAttachedToWindow") { param ->
                        (param.thisObject as? View)?.let { installDescLongClick(it) }
                    }
                    clazz.hookAfterMethod(
                        "onLayout",
                        Boolean::class.javaPrimitiveType,
                        Int::class.javaPrimitiveType,
                        Int::class.javaPrimitiveType,
                        Int::class.javaPrimitiveType,
                        Int::class.javaPrimitiveType
                    ) { param ->
                        (param.thisObject as? View)?.let { installDescLongClick(it) }
                    }
                    val longClickHooker: Hooker = hooker@{ param ->
                        val view = param.thisObject as? View ?: return@hooker
                        val text = descFullText[view]?.takeIf { it.isNotEmpty() }
                            ?: runCatchingOrNull {
                                (view.callMethodOrNull("getTextBuilder")?.callMethodOrNull("a")) as? CharSequence
                            }?.takeIf { !it.isNullOrEmpty() }
                            ?: runCatchingOrNull {
                                view.getFirstFieldByExactTypeOrNull<CharSequence>() as? CharSequence
                            }?.takeIf { !it.isNullOrEmpty() }
                            ?: (view as? TextView)?.text?.takeIf { !it.isNullOrEmpty() }
                        Log.d("desc performLongClick fired: textLen=${text?.length ?: -1}")
                        if (text.isNullOrEmpty()) return@hooker
                        showLongPressCopyDialog(view.context, text)
                        param.result = true
                    }
                    clazz.hookBeforeMethod("performLongClick", hooker = longClickHooker)
                    if (runCatchingOrNull {
                            clazz.getDeclaredMethod(
                                "performLongClick",
                                Float::class.javaPrimitiveType,
                                Float::class.javaPrimitiveType
                            )
                        } != null
                    ) {
                        clazz.hookBeforeMethod(
                            "performLongClick",
                            Float::class.javaPrimitiveType,
                            Float::class.javaPrimitiveType,
                            hooker = longClickHooker
                        )
                    }
                }
        }

        val dynamicHolders = instance.dynamicDescHolderListeners()
        Log.d(
            "CopyHook: dynamicDesc holders=${dynamicHolders.size}：" +
                    dynamicHolders.joinToString { it?.name ?: "null" }
        )
        dynamicHolders.forEach { c ->
            c?.replaceMethod("onLongClick", View::class.java) { param ->
                if (!enhanceLongClickCopy)
                    return@replaceMethod true
                Log.d("CopyHook: dynamicDesc onLongClick holder=${c?.name}")
                val itemView = param.args[0] as? View
                DYNAMIC_COPYABLE_IDS.asSequence().firstNotNullOfOrNull { n ->
                    getId(n).takeIf { it != 0 }?.let { itemView?.findViewById<TextView>(it) }
                }?.let { v ->
                    (if (instance.ellipsizingTextViewClass?.isInstance(v) == true) {
                        v.getFirstFieldByExactTypeOrNull()
                    } else v.text)?.also { text ->
                        showCopyDialog(v.context, text, param)
                    }
                } ?: Log.toast("找不到动态内容", true)
                true
            }
        }

        val commentCopyHook = fun(param: MethodHookParam, idName: String): Any? {
            if (!enhanceLongClickCopy) return true
            if (param.args[0] is FrameLayout) return param.invokeOriginalMethod()
            (param.args[0] as? View)?.findViewById<View>(getId(idName))?.let {
                if (instance.commentSpanTextViewClass?.isInstance(it) == true ||
                    instance.commentSpanEllipsisTextViewClass?.isInstance(it) == true
                ) it else null
            }?.let { view ->
                view.getFirstFieldByExactTypeOrNull<CharSequence>()?.also { text ->
                    showCopyDialog(view.context, text, param)
                }
            } ?: Log.toast("找不到评论内容", true)
            return true
        }
        instance.commentCopyClass?.replaceMethod("onLongClick", View::class.java) {
            commentCopyHook(it, "message")
        }
        instance.commentCopyNewClass?.replaceMethod("onLongClick", View::class.java) {
            commentCopyHook(it, "comment_message")
        }

        instance.comment3CopyClass?.let { c ->
            instance.comment3Copy()?.let { m ->
                instance.comment3ViewIndex().let { i ->
                    c.replaceAllMethods(m) { param ->
                        if (!enhanceLongClickCopy) return@replaceAllMethods true
                        val view = param.args[i] as View
                        view.getFirstFieldByExactTypeOrNull<CharSequence>()?.also { text ->
                            showCopyDialog(view.context, text, param)
                        }
                        return@replaceAllMethods true
                    }
                }
            }
        }

        // 9.8.0+: 评论内容渲染改为 Compose/新 holder，内容视图为 RichTextView，
        // 原 CommentContentRichTextHandler 已不存在，长按复制增强直接挂载在 setText 上
        if (enhanceLongClickCopy) {
            // 9.8.0 的评论/动态评论存在多个渲染控件，逐个挂载以覆盖不同页面的评论
            listOf(
                "com.bilibili.app.comment3.ui.widget.RichTextView",
                "com.bilibili.app.comment3.ui.nextwidget.NextExpandableTextView",
                "com.bilibili.app.comment3.ui.nextwidget.NextExperiment3ExpandableTextView",
                "com.bilibili.app.comment3.ui.widget.ExpandableTextView",
                "com.bilibili.app.comm.comment2.widget.CommentExpandableTextView",
                "com.bilibili.app.comment3.ui.widget.imagecardviewer.ui.widget.CardExpandableTextView"
            ).forEach { className ->
                val commentViewClass = className.findClassOrNull(mClassLoader) ?: return@forEach
                Log.d("CopyHook: 评论长按 hook 已挂载 ${commentViewClass.name}")
                commentViewClass.hookAfterMethod(
                    "setText", CharSequence::class.java, TextView.BufferType::class.java
                ) { param ->
                    val view = param.thisObject as? View ?: return@hookAfterMethod
                    // 首次安装监听时，若 App 已自行注册长按监听，说明模块会覆盖它
                    if (longClickViews.add(view) && view.hasOnLongClickListeners()) {
                        Log.d("CopyHook: 评论控件已有长按监听，将被模块覆盖 class=${view.javaClass.name}")
                    }
                    installLongClickDeep(view, { pressed ->
                        val tv = pressed as? TextView
                        Log.d(
                            "CopyHook: 评论长按 class=${pressed.javaClass.name} " +
                                    "textLen=${tv?.text?.length ?: -1}"
                        )
                        tv?.text?.let { text -> showLongPressCopyDialog(pressed.context, text) }
                        true
                    }, "评论")
                }
            }
        }

        if (!enhanceLongClickCopy) return
        val onClickName = instance.onOperateClick() ?: return
        val contentStringName = instance.getContentString() ?: return
        val hostClassName = instance.operateClickHostClass() ?: return
        val hostClass = hostClassName.from(mClassLoader) ?: return
        val hookMethod = hostClass.declaredMethods.find { it.name == onClickName } ?: return

        fun parseContentText(json: String): String? = runCatchingOrNull { json.toJSONObject() }?.run {
            optString("content").ifEmpty {
                buildString {
                    appendLine(optString("title").trim())
                    appendLine(optString("text").trim())
                    optJSONArray("modules")?.run {
                        asSequence<JSONObject>().map {
                            it.optString("title") + "：" + it.optString("detail")
                        }.joinToString("\n").run { append(this) }
                    }
                }.run { removeSuffix("\n") }
            }.ifEmpty { null }
        }

        hookMethod.hookBeforeMethod { param ->
            // Repost guard: last arg == first arg
            if (param.args.size >= 2 && param.args.last() != param.args.first()) return@hookBeforeMethod

            val hostClass = param.thisObject.javaClass

            // Activity: try this first, then search for captured field
            val activity = (param.thisObject as? Activity)
                ?: hostClass.declaredFields.find {
                    Activity::class.java.isAssignableFrom(it.type)
                }?.apply { isAccessible = true }?.get(param.thisObject) as? Activity
                ?: return@hookBeforeMethod

            // Typed message: try args[1] first, then search for field with getContentString
            val typedMsg = param.args.getOrNull(1)
                ?: hostClass.declaredFields.find {
                    runCatching { it.type.getMethod(contentStringName) }.isSuccess
                }?.apply { isAccessible = true }?.get(param.thisObject)
                ?: return@hookBeforeMethod

            val json = typedMsg.callMethodOrNullAs<String>(contentStringName) ?: return@hookBeforeMethod
            val text = parseContentText(json) ?: return@hookBeforeMethod
            showCopyDialog(activity, text, param)

            // Dismiss popup: try args[6] first, then search for PopupWindow field
            (param.args.getOrNull(6)
                ?: hostClass.declaredFields.find {
                    PopupWindow::class.java.isAssignableFrom(it.type)
                }?.apply { isAccessible = true }?.get(param.thisObject))
                ?.callMethodOrNull("dismiss")

            param.result = null
        }
    }

    private fun extractDescText(arg: Any?): CharSequence? {
        arg ?: return null
        runCatchingOrNull {
            arg.getFirstFieldByExactTypeOrNull<CharSequence>() as? CharSequence
        }?.takeIf { it.isNotEmpty() }?.let { return it }
        return runCatchingOrNull {
            arg.callMethodOrNull("a") as? CharSequence
        }?.takeIf { !it.isNullOrEmpty() }
    }

    /**
     * Activity 层长按兜底。
     *
     * 9.8.0 的简介控件只在带 span 的小区域回调 `performLongClick(x, y)`，正文空白处
     * 完全不回调；同时它的继承方法（onTouchEvent/onAttachedToWindow 等）无法用
     * findAndHookMethod 挂载。因此只能在 Activity.dispatchTouchEvent 里识别长按，
     * 再按坐标命中当时真正承载文本的视图（优先取捕获到的简介全文）。
     */
    private fun installDetailLongPressFallback() {
        runCatching {
            Activity::class.java.hookBeforeMethod(
                "dispatchTouchEvent", MotionEvent::class.java
            ) { param ->
                val activity = param.thisObject as? Activity ?: return@hookBeforeMethod
                val event = param.args[0] as? MotionEvent ?: return@hookBeforeMethod
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        pressDownAt = SystemClock.uptimeMillis()
                        pressDownX = event.rawX
                        pressDownY = event.rawY
                    }

                    MotionEvent.ACTION_MOVE -> {
                        if (pressDownAt > 0L &&
                            (Math.abs(event.rawX - pressDownX) > LONG_PRESS_SLOP ||
                                Math.abs(event.rawY - pressDownY) > LONG_PRESS_SLOP)
                        ) {
                            pressDownAt = 0L
                        }
                    }

                    MotionEvent.ACTION_UP -> {
                        val downAt = pressDownAt
                        pressDownAt = 0L
                        if (downAt <= 0L) return@hookBeforeMethod
                        if (SystemClock.uptimeMillis() - downAt < LONG_PRESS_MS) {
                            return@hookBeforeMethod
                        }
                        // 只在详情类页面启用，避免影响首页/播放器等处的长按。
                        // 9.8.0 的动态/opus 页类名不一定含 detail，这里放宽为一组关键字。
                        val pageName = activity.javaClass.name
                        val isTargetPage = TARGET_PAGE_KEYWORDS.any { pageName.contains(it, true) }
                        if (!isTargetPage) {
                            if (loggedSkipPages.size < 30 && loggedSkipPages.add(pageName)) {
                                Log.d("CopyHook: 长按兜底跳过 class=$pageName（非目标页面）")
                            }
                            return@hookBeforeMethod
                        }
                        val text = runCatchingOrNull {
                            findTextAtPoint(
                                activity.window?.decorView, event.rawX, event.rawY
                            )
                        }
                        if (text.isNullOrEmpty() || text.length < 5) {
                            Log.d(
                                "CopyHook: 长按兜底未取到文本 class=$pageName " +
                                        "textLen=${text?.length ?: -1}"
                            )
                            return@hookBeforeMethod
                        }
                        Log.d(
                            "CopyHook: 长按兜底命中 class=$pageName textLen=${text.length}"
                        )
                        showLongPressCopyDialog(activity, text)
                        // 吞掉事件，避免 App 自己也弹一次菜单
                        param.result = true
                    }
                }
            }
            Log.d("CopyHook: 长按兜底已安装（Activity.dispatchTouchEvent）")
        }.onFailure { Log.e("CopyHook: 长按兜底安装失败: $it") }
    }

    /** 在 [root] 中按屏幕坐标找到最深处的文本视图，优先返回简介全文缓存。 */
    private fun findTextAtPoint(root: View?, x: Float, y: Float): CharSequence? {
        if (root == null) return null
        val hits = ArrayList<View>()
        val location = IntArray(2)

        fun walk(v: View) {
            if (!v.isShown) return
            v.getLocationOnScreen(location)
            val vx = location[0]
            val vy = location[1]
            if (x < vx || y < vy || x >= vx + v.width || y >= vy + v.height) return
            hits.add(v)
            if (v is ViewGroup) {
                for (i in v.childCount - 1 downTo 0) walk(v.getChildAt(i))
            }
        }

        walk(root)
        // 由深到浅：先看有没有捕获到的简介全文，再看普通 TextView 文本
        for (v in hits.asReversed()) {
            descFullText[v]?.takeIf { it.isNotEmpty() }?.let { return it }
            (v as? TextView)?.text?.takeIf { it.isNotEmpty() }?.let { return it }
        }
        return null
    }

    private fun showLongPressCopyDialog(context: Context, text: CharSequence) {
        val appDialogTheme = getResId("AppTheme.Dialog.Alert", "style")
        AlertDialog.Builder(context, appDialogTheme).run {
            setTitle("自由复制内容")
            setMessage(text)
            setPositiveButton("分享") { _, _ ->
                context.startActivity(
                    Intent.createChooser(
                        Intent().apply {
                            action = Intent.ACTION_SEND
                            putExtra(Intent.EXTRA_TEXT, text)
                            type = "text/plain"
                        }, "分享评论内容"
                    )
                )
            }
            setNeutralButton("复制全部") { _, _ ->
                val clipboard =
                    context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("comment", text))
            }
            setNegativeButton(android.R.string.cancel, null)
            show()
        }.apply {
            findViewById<TextView>(android.R.id.message)?.setTextIsSelectable(true)
        }
    }

    /**
     * 递归给 [root] 及其所有子 View 安装长按监听。
     *
     * 9.8.0 的简介/评论文字常由子 View 绘制并消费触摸，只给容器装监听会出现
     * "只有容器空白处（左侧一小块区域）能触发长按"的问题。
     */
    private fun installLongClickDeep(root: View, onLongClick: (View) -> Boolean, tag: String) {
        val stack = ArrayDeque<View>()
        stack.add(root)
        var visited = 0
        var added = 0
        while (stack.isNotEmpty() && visited < 200) {
            val v = stack.removeFirst()
            visited++
            if (v is ViewGroup) {
                for (i in 0 until v.childCount) stack.add(v.getChildAt(i))
            }
            if (!deepLongClickViews.add(v)) continue
            v.isLongClickable = true
            v.setOnLongClickListener { pressed -> onLongClick(pressed) }
            added++
        }
        // 只在真正新增了监听时记录，避免每次 setter/layout 都刷屏
        if (added > 0) {
            Log.d("CopyHook: $tag 深度长按监听 root=${root.javaClass.name} 新增=$added 遍历=$visited")
        }
    }

    private fun showCopyDialog(context: Context, text: CharSequence, param: MethodHookParam) {
        val appDialogTheme = getResId("AppTheme.Dialog.Alert", "style")
        AlertDialog.Builder(context, appDialogTheme).run {
            setTitle("自由复制内容")
            setMessage(text)
            setPositiveButton("分享") { _, _ ->
                context.startActivity(
                    Intent.createChooser(
                        Intent().apply {
                            action = Intent.ACTION_SEND
                            putExtra(Intent.EXTRA_TEXT, text)
                            type = "text/plain"
                        }, "分享评论内容"
                    )
                )
            }
            setNeutralButton("复制全部") { _, _ ->
                param.invokeOriginalMethod()
            }
            setNegativeButton(android.R.string.cancel, null)
            show()
        }.apply {
            findViewById<TextView>(android.R.id.message)?.setTextIsSelectable(true)
        }
    }
}
