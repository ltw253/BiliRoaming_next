package me.iacn.biliroaming.hook

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.text.SpannableStringBuilder
import android.text.style.ClickableSpan
import android.view.View
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
    }

    private val enhanceLongClickCopy = sPrefs.getBoolean("comment_copy_enhance", false)

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
            "tv.danmaku.bili.videopage.common.widget.view.ExpandableTextView"
                .findClassOrNull(mClassLoader)?.let { clazz ->
                    Log.d("hooked desc copy view: ${clazz.name}")
                    listOf("T", "setOriginText", "setExpandedDesc", "setRetractedDesc").forEach { name ->
                        clazz.hookAfterAllMethods(name) { param ->
                            val view = param.thisObject as? View ?: return@hookAfterAllMethods
                            val text = extractDescText(param.args.getOrNull(0))
                            if (text != null) descFullText[view] = text.toString()
                        }
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

        instance.dynamicDescHolderListeners().forEach { c ->
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
            "com.bilibili.app.comment3.ui.widget.RichTextView".findClassOrNull(mClassLoader)
                ?.hookAfterMethod(
                    "setText",
                    CharSequence::class.java,
                    TextView.BufferType::class.java
                ) { param ->
                    (param.thisObject as? View)?.setOnLongClickListener { v ->
                        Log.d("CopyHook: RichTextView longClick class=${v.javaClass.name} textLen=${(v as? TextView)?.text?.length ?: -1}")
                        (v as? TextView)?.text?.let { text ->
                            showLongPressCopyDialog(v.context, text)
                        }
                        true
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
