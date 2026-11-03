package me.iacn.biliroaming.hook

import android.app.Activity
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import me.iacn.biliroaming.BiliBiliPackage.Companion.instance
import me.iacn.biliroaming.SettingDialog
import me.iacn.biliroaming.utils.*
import java.lang.reflect.Constructor
import java.lang.reflect.Proxy


class SettingHook(classLoader: ClassLoader) : BaseHook(classLoader) {
    private var startSetting = false

    /** 已确认含菜单数据的适配器类（弱引用，避免泄漏）。 */
    private val knownMenuAdapterClasses: MutableSet<Class<*>> =
        java.util.Collections.newSetFromMap(java.util.WeakHashMap<Class<*>, Boolean>())

    /** 已挂过长按入口的底部「我的」标签视图（弱引用）。 */
    private var mineTabViewRef: java.lang.ref.WeakReference<View>? = null

    /** 底部标签长按入口是否已提示过失败（避免刷屏）。 */
    @Volatile
    private var bottomTabEntryReported = false

    override fun startHook() {
        Log.d("startHook: Setting")

        instance.splashActivityClass?.hookBeforeMethod("onCreate", Bundle::class.java) { param ->
            val self = param.thisObject as Activity
            startSetting = self.intent.hasExtra(START_SETTING_KEY)
        }

        instance.mainActivityClass?.hookAfterMethod("onResume") { param ->
            if (startSetting) {
                startSetting = false
                SettingDialog.show(param.thisObject as Activity)
            }
        }

        instance.mainActivityClass?.hookBeforeMethod(
            "onCreate",
            Bundle::class.java
        ) { param ->
            val bundle = param.args[0] as? Bundle
            bundle?.remove("android:fragments")
        }

        instance.drawerClass?.hookAfterMethod(
            "onCreateView",
            LayoutInflater::class.java,
            ViewGroup::class.java,
            Bundle::class.java
        ) { param ->
            val navSettingId = getId("nav_settings")
            val nav =
                param.thisObject.javaClass.declaredFields.first { it.type.name == "android.support.design.widget.NavigationView" }.name
            (param.thisObject.getObjectField(nav)
                ?: param.result).callMethodAs<View>("findViewById", navSettingId)
                .setOnLongClickListener {
                    SettingDialog.show(param.thisObject.callMethodAs<Activity>("getActivity"))
                    true
                }
        }

        instance.homeCenters().forEach { (c, m) ->
            c?.hookBeforeAllMethods(m) { param ->
                @Suppress("UNCHECKED_CAST")
                val list = param.args[1] as? MutableList<Any>
                    ?: param.args[1]?.getObjectFieldOrNullAs<MutableList<Any>>("moreSectionList")
                    ?: return@hookBeforeAllMethods

                val itemList = list.lastOrNull()?.let {
                    if (it.javaClass != instance.menuGroupItemClass) it.getObjectFieldOrNullAs<MutableList<Any>>(
                        "itemList"
                    ) else list
                } ?: list

                val item = makeSettingItem() ?: return@hookBeforeAllMethods
                itemList.forEach {
                    if (try {
                            it.getIntField("id") == SETTING_ID
                        } catch (t: Throwable) {
                            it.getLongField("id") == SETTING_ID.toLong()
                        }
                    ) return@hookBeforeAllMethods
                }
                itemList.add(item)
            }
        }

        instance.settingRouterClass?.hookBeforeAllConstructors { param ->
            if (param.args[1] != SETTING_URI) return@hookBeforeAllConstructors
            val routerType = (param.method as Constructor<*>).parameterTypes[3]
            param.args[3] = Proxy.newProxyInstance(
                routerType.classLoader,
                arrayOf(routerType)
            ) { _, method, _ ->
                val returnType = method.returnType
                Proxy.newProxyInstance(
                    returnType.classLoader,
                    arrayOf(returnType)
                ) { _, method2, args ->
                    when (method2.returnType) {
                        Boolean::class.javaPrimitiveType -> false
                        else -> {
                            if (method2.parameterTypes.isNotEmpty() &&
                                method2.parameterTypes[0].name == "android.app.Activity"
                            ) {
                                val currentActivity = args[0] as Activity
                                SettingDialog.show(currentActivity)
                            }
                            null
                        }
                    }
                }
            }
        }

        // 8.97.0+: hook 菜单适配器 notify* 方法注入设置项
        instance.mineAdapterClass?.let { adapterClass ->
            val dataField = hookAdapterFallback(adapterClass)
            if (dataField == null) {
                Log.e(
                    "SettingHook: 我的页适配器数据字段未找到，入口注入不可用" +
                            "（${adapterClass.name}）"
                )
            } else {
                hookAdapterAttach(adapterClass, dataField)
            }
        }

        installGenericAdapterInjection()
        installBottomTabEntry()
    }

    /**
     * 通用注入：不依赖具体适配器类名，按"内容形态"识别「我的」页适配器。
     *
     * 部分设备/改版包上，[instance.mineAdapterClass] 记录的类名可能与实际运行的类不一致，
     * 导致针对该类的 notify / setAdapter 钩子永不触发。这里改为全局 hook：
     *  - RecyclerView.setAdapter（任意适配器）
     *  - RecyclerView.Adapter 基类的 notify* 全量重载
     * 对类名带 "mine" 的适配器，扫描字段找 "MenuGroup 列表"，找到就注入。
     */
    private fun installGenericAdapterInjection() {
        val recyclerViewClass =
            "androidx.recyclerview.widget.RecyclerView".findClassOrNull(mClassLoader) ?: return
        var setAdapterHooks = 0
        recyclerViewClass.declaredMethods
            .filter { it.name == "setAdapter" }
            .forEach { method ->
                runCatching {
                    method.hookAfterMethod { param ->
                        val adapter = param.args[0] ?: return@hookAfterMethod
                        val view = param.thisObject as? View ?: return@hookAfterMethod
                        view.postDelayed({
                            runCatching {
                                val groups = scanMenuGroups(adapter)
                                if (groups != null) {
                                    knownMenuAdapterClasses.add(adapter.javaClass)
                                    Log.d(
                                        "SettingHook: 通用注入 setAdapter adapter=" +
                                                adapter.javaClass.name
                                    )
                                    injectSettingItem(groups)
                                    runCatchingOrNull {
                                        adapter.callMethodAs<Any?>("notifyDataSetChanged")
                                    }
                                }
                            }
                        }, 1200)
                    }
                    setAdapterHooks++
                }.onFailure { Log.d("SettingHook: 通用 setAdapter hook 失败 $it") }
            }
        Log.d("SettingHook: 通用 setAdapter 钩子=$setAdapterHooks")

        val adapterBase = "androidx.recyclerview.widget.RecyclerView\$Adapter"
            .findClassOrNull(mClassLoader) ?: return
        var notifyHooks = 0
        listOf(
            "notifyDataSetChanged", "notifyItemRangeChanged",
            "notifyItemRangeInserted", "notifyItemRangeRemoved", "notifyItemChanged"
        ).forEach { methodName ->
            adapterBase.methods.filter { it.name == methodName }.forEach { method ->
                runCatching {
                    method.hookBeforeMethod { param ->
                        val adapter = param.thisObject ?: return@hookBeforeMethod
                        if (!adapter.javaClass.name.contains("mine", true) &&
                            !knownMenuAdapterClasses.contains(adapter.javaClass)
                        ) return@hookBeforeMethod
                        val groups = scanMenuGroups(adapter) ?: return@hookBeforeMethod
                        injectSettingItem(groups)
                    }
                    notifyHooks++
                }
            }
        }
        Log.d("SettingHook: 通用 notify 钩子=$notifyHooks")
    }

    /** 按内容形态从适配器字段里找出「MenuGroup 列表」（元素有 itemList 且含 MenuGroup.Item）。 */
    private fun scanMenuGroups(adapter: Any): MutableList<Any>? {
        val menuGroupItemClass = instance.menuGroupItemClass ?: return null
        for (field in adapter.javaClass.declaredFields) {
            if (!List::class.java.isAssignableFrom(field.type)) continue
            field.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            val list = runCatchingOrNull { field.get(adapter) as? MutableList<Any> }
                ?: continue
            if (list.isEmpty()) continue
            val first = list.firstOrNull() ?: continue
            val itemList = first.getObjectFieldOrNullAs<MutableList<Any>>("itemList")
                ?: continue
            if (itemList.any { it != null && it.javaClass == menuGroupItemClass }) {
                return list
            }
        }
        return null
    }

    /**
     * 通用入口：底部「我的」标签长按打开模块设置。
     *
     * 9.8.0 的「我的」页存在三套实现（V1 RecyclerView / V2、V3 Compose），
     * 不同账号/设备可能命中不同实现，菜单适配器注入在 Compose 实现上无法生效。
     * 这条入口不依赖「我的」页内部结构，任何实现下都能打开设置。
     */
    private fun installBottomTabEntry() {
        instance.mainActivityClass?.hookAfterMethod("onResume") { param ->
            val activity = param.thisObject as? Activity ?: return@hookAfterMethod
            activity.window?.decorView?.postDelayed({
                runCatching {
                    installMineTabLongPress(activity)
                }.onFailure { Log.d("SettingHook: 底部标签入口安装失败 $it") }
            }, 1200L)
        }
        Log.d("SettingHook: 底部「我的」标签长按入口已安装")
    }

    private fun installMineTabLongPress(activity: Activity) {
        val decor = activity.window?.decorView ?: return
        if (decor.width <= 0 || decor.height <= 0) return
        val target = findBottomMineTab(decor, decor.height)
        if (target == null) {
            if (!bottomTabEntryReported) {
                bottomTabEntryReported = true
                Log.d("SettingHook: 未找到底部「我的」标签（稍后重试）")
            }
            return
        }
        if (mineTabViewRef?.get() === target) return
        mineTabViewRef = java.lang.ref.WeakReference(target)
        target.isLongClickable = true
        target.setOnLongClickListener {
            Log.d("SettingHook: 通过底部「我的」标签长按打开设置")
            SettingDialog.show(activity)
            true
        }
        Log.d("SettingHook: 已为底部「我的」标签安装长按设置入口")
    }

    /** 在视图树底部区域按文字「我的」定位底部标签，并向上取可点击容器。 */
    private fun findBottomMineTab(root: View, screenHeight: Int): View? {
        var found: View? = null
        fun walk(view: View, depth: Int) {
            if (found != null || depth > 30 || !view.isShown) return
            val location = IntArray(2)
            view.getLocationOnScreen(location)
            if (location[1] > screenHeight * 0.85) {
                val label = when (view) {
                    is android.widget.TextView -> view.text?.toString()
                    else -> view.contentDescription?.toString()
                }
                if (!label.isNullOrEmpty() && label.contains("我的")) {
                    var target: View = view
                    var parent: Any? = view.parent
                    var guard = 0
                    while (parent is View && guard++ < 3) {
                        if (parent.isClickable) {
                            target = parent
                            break
                        }
                        parent = parent.parent
                    }
                    found = target
                    return
                }
            }
            if (view is ViewGroup) {
                for (i in 0 until view.childCount) walk(view.getChildAt(i), depth + 1)
            }
        }
        walk(root, 0)
        return found
    }

    private fun hookAdapterFallback(adapterClass: Class<*>): java.lang.reflect.Field? {
        val menuGroupItemClass = instance.menuGroupItemClass ?: return null
        val dataField = adapterClass.declaredFields.firstOrNull {
            List::class.java.isAssignableFrom(it.type)
        } ?: return null
        dataField.isAccessible = true
        Log.d(
            "SettingHook: 我的页适配器=${adapterClass.name} " +
                    "数据字段=${dataField.name}:${dataField.type.simpleName}"
        )

        // hook RecyclerView.Adapter 多个通知方法代替单一 notifyDataSetChanged
        // 8.97.0 的适配器使用 notifyItemRangeChanged 刷新数据
        var notifyHooks = 0
        listOf(
            "notifyDataSetChanged", "notifyItemRangeChanged",
            "notifyItemRangeInserted", "notifyItemRangeRemoved", "notifyItemChanged"
        ).forEach { methodName ->
            // 不能用 getMethod(name)：它是按"零参数"查找，带参重载
            // notifyItemRangeChanged(int,int) 等会查不到，注入点会被静默漏装。
            adapterClass.methods.filter { it.name == methodName }.forEach { method ->
                runCatching {
                    method.hookBeforeMethod { param ->
                        if (!adapterClass.isInstance(param.thisObject)) {
                            return@hookBeforeMethod
                        }
                        val data = dataField.get(param.thisObject) as? MutableList<*>
                            ?: return@hookBeforeMethod
                        injectSettingItem(data)
                    }
                    notifyHooks++
                }.onFailure {
                    Log.d(
                        "SettingHook: notify hook 安装失败 " +
                                "${method.name}/${method.parameterTypes.size}: $it"
                    )
                }
            }
        }

        // 兜底 2：部分版本会直接整体替换 data 列表（不走 notify），或者 notify 时数据仍为空
        // 导致上面的注入被跳过。这里再 hook 适配器上所有"单个 List 参数"的方法，数据一到就注入。
        var listHooks = 0
        adapterClass.declaredMethods.filter { m ->
            !java.lang.reflect.Modifier.isStatic(m.modifiers) &&
                m.parameterTypes.size == 1 &&
                List::class.java.isAssignableFrom(m.parameterTypes[0])
        }.forEach { method ->
            runCatching {
                method.hookAfterMethod { param ->
                    if (!adapterClass.isInstance(param.thisObject)) return@hookAfterMethod
                    @Suppress("UNCHECKED_CAST")
                    val list = param.args[0] as? MutableList<Any> ?: return@hookAfterMethod
                    injectSettingItem(list)
                }
                listHooks++
            }.onFailure { Log.d("SettingHook: 列表注入 hook 安装失败 ${method.name}: $it") }
        }
        Log.d("SettingHook: 注入点已安装 notify=$notifyHooks list=$listHooks")

        // 为注入的设置项绑定点击监听
        // 8.97.0+ 为双层 RecyclerView：外层分组适配器 → 内层条目适配器
        // 从外层 data 定位包含注入项的 MenuGroup，post 后遍历 View 树找到内层 RecyclerView
        fun bindSettingClick(holder: Any, position: Int, adapter: Any) {
            val data = dataField.get(adapter) as? List<*> ?: return
            val group = data.getOrNull(position) ?: return
            val itemList = group.getObjectFieldOrNullAs<List<*>>("itemList") ?: return
            itemList.find {
                it?.javaClass == menuGroupItemClass && it.getObjectField("uri") == SETTING_URI
            } ?: return
            val itemView = holder.getObjectFieldOrNullAs<View>("itemView") ?: return
            itemView.post {
                val rv = findFirstRecyclerView(itemView) ?: return@post
                val innerAdapter = rv.callMethodAs<Any?>("getAdapter") ?: return@post

                @Suppress("UNCHECKED_CAST")
                val innerData = innerAdapter.javaClass.declaredFields
                    .firstOrNull { List::class.java.isAssignableFrom(it.type) }
                    ?.also { it.isAccessible = true }
                    ?.get(innerAdapter) as? List<Any> ?: return@post
                val idx = innerData.indexOfFirst {
                    it.javaClass == menuGroupItemClass &&
                            it.getObjectField("uri") == SETTING_URI
                }
                if (idx < 0) return@post
                val lm = rv.callMethodAs<Any?>("getLayoutManager") ?: return@post
                val child = lm.callMethodAs<View?>("findViewByPosition", idx) ?: return@post
                child.setOnClickListener {
                    val ctx = it.context
                    if (ctx is Activity) SettingDialog.show(ctx)
                }
            }
        }

        // 2-param onBindViewHolder (首次 bind、全量刷新)
        adapterClass.methods.firstOrNull { m ->
            m.name == "onBindViewHolder" && m.parameterTypes.size == 2
        }?.hookAfterMethod { param ->
            bindSettingClick(param.args[0], param.args[1] as Int, param.thisObject)
        }
        return dataField
    }

    /**
     * 适配器被设置到 RecyclerView 后，主动注入并刷新一次。
     *
     * 部分设备/版本上，进入「我的」页时数据已就绪但不会再触发 notify*，
     * 只依赖 notify 注入会导致入口永远不出现（表现为"第一次有、之后消失"）。
     */
    private fun hookAdapterAttach(
        adapterClass: Class<*>,
        dataField: java.lang.reflect.Field
    ) {
        val recyclerViewClass =
            "androidx.recyclerview.widget.RecyclerView".findClassOrNull(mClassLoader) ?: return
        recyclerViewClass.declaredMethods.firstOrNull {
            it.name == "setAdapter" && it.parameterTypes.size == 1
        }?.hookAfterMethod { param ->
            val adapter = param.args[0] ?: return@hookAfterMethod
            if (!adapterClass.isInstance(adapter)) return@hookAfterMethod
            val view = param.thisObject as? View ?: return@hookAfterMethod
            Log.d("SettingHook: 我的页适配器已挂载，延迟主动注入")
            view.postDelayed({
                runCatching {
                    val data = dataField.get(adapter) as? MutableList<*>
                    if (data == null) {
                        Log.d("SettingHook: 适配器数据字段为 null，放弃主动注入")
                        return@runCatching
                    }
                    injectSettingItem(data)
                    adapter.callMethodAs<Any?>("notifyDataSetChanged")
                    Log.d("SettingHook: 已主动刷新我的页（组数=${data.size}）")
                }.onFailure { Log.e("SettingHook: 主动注入失败 $it") }
            }, 800)
        }
        Log.d("SettingHook: 已安装 setAdapter 主动注入钩子")
    }

    /** 把「哔哩漫游设置」项注入到菜单数据里（去重，可重复调用）。 */
    private fun injectSettingItem(data: MutableList<*>) {
        val menuGroupItemClass = instance.menuGroupItemClass ?: return
        if (data.isEmpty()) {
            Log.d("SettingHook: 适配器数据为空，跳过注入")
            return
        }

        // 去重：检查 data 中所有 MenuGroup 的 itemList 是否已含设置项
        for (group in data) {
            group?.getObjectFieldOrNullAs<MutableList<Any>>("itemList")?.forEach { item ->
                if (item.javaClass == menuGroupItemClass &&
                    (try {
                        item.getIntField("id") == SETTING_ID
                    } catch (_: Throwable) {
                        item.getLongField("id") == SETTING_ID.toLong()
                    })
                ) return
            }
        }

        val targetList = data.lastOrNull()
            ?.getObjectFieldOrNullAs<MutableList<Any>>("itemList")
            ?: run {
                Log.d("SettingHook: 最后一组没有 itemList，跳过注入（data=${data.size}）")
                return
            }

        // 同一个列表实例内去重（App 可能复用 group 对象反复 notify）
        val alreadyInTarget = targetList.any { item ->
            item?.javaClass == menuGroupItemClass &&
                (try {
                    item.getIntField("id") == SETTING_ID
                } catch (_: Throwable) {
                    item.getLongField("id") == SETTING_ID.toLong()
                })
        }
        if (alreadyInTarget) return

        val item = makeSettingItem() ?: run {
            Log.d("SettingHook: makeSettingItem 返回 null，跳过注入")
            return
        }
        targetList.add(if (targetList.isEmpty()) 0 else targetList.lastIndex, item)
        Log.d("SettingHook: 已注入设置项（组数=${data.size}，项数=${targetList.size}）")
    }

    /**
     * 递归遍历 View 树，查找第一个 RecyclerView 实例。
     */
    private fun findFirstRecyclerView(root: View): View? {
        if (rvClass?.isInstance(root) == true) return root
        if (root !is ViewGroup) return null
        for (i in 0 until root.childCount) {
            findFirstRecyclerView(root.getChildAt(i))?.let { return it }
        }
        return null
    }

    private val rvClass: Class<*>? by lazy {
        "androidx.recyclerview.widget.RecyclerView".findClassOrNull(mClassLoader)
            ?: "android.support.v7.widget.RecyclerView".findClassOrNull(mClassLoader)
    }

    companion object {
        const val START_SETTING_KEY = "biliroaming_start_setting"
        const val SETTING_URI = "bilibili://biliroaming"
        const val SETTING_ID = 114514

        fun makeSettingItem(): Any? {
            val item = instance.menuGroupItemClass?.new() ?: return null
            try {
                item.setIntField("id", SETTING_ID)
            } catch (_: Throwable) {
                item.setLongField("id", SETTING_ID.toLong())
            }
            item.setObjectField("title", "哔哩漫游设置")
                .setObjectField(
                    "icon",
                    "https://i0.hdslb.com/bfs/album/276769577d2a5db1d9f914364abad7c5253086f6.png"
                )
                .setObjectField("uri", SETTING_URI)
            item.setIntField("visible", 1)
            return item
        }
    }
}
