package me.iacn.biliroaming.hook

import android.content.SharedPreferences
import me.iacn.biliroaming.BiliBiliPackage.Companion.instance
import me.iacn.biliroaming.utils.*
import java.lang.reflect.Proxy
import java.util.regex.Pattern

class EnvHook(classLoader: ClassLoader) : BaseHook(classLoader) {
    override fun startHook() {
        Log.d("startHook: Env")

        // 兼容两种历史签名：旧版无参实例方法、新版 static(clazz) 方法。
        // 两者都不匹配时明确记日志，避免"功能静默失效、无从排查"。
        fun hookSignatureCompat(clazz: Class<*>, methodName: String, tag: String, hooker: Hooker) {
            val target = clazz.declaredMethods.firstOrNull {
                it.name == methodName && it.parameterTypes.isEmpty()
            } ?: clazz.declaredMethods.firstOrNull {
                it.name == methodName && it.parameterTypes.size == 1 &&
                        it.parameterTypes[0] == clazz
            }
            if (target == null) {
                Log.d(
                    "EnvHook: $tag 在 ${clazz.name} 上未找到 $methodName 的兼容签名，" +
                            "相关功能在 9.8.0 上可能不生效"
                )
                return
            }
            Log.d("EnvHook: $tag 挂钩 ${clazz.name}#${methodName}（${target.parameterTypes.size} 参）")
            runCatching { target.hookAfterMethod(hooker) }
                .onFailure { Log.e("EnvHook: $tag 挂钩失败: $it") }
        }

        // EnvContext
        instance.preBuiltConfigClass?.let { clazz ->
            val hooker: Hooker = hooker@ { param ->
                @Suppress("UNCHECKED_CAST")
                val result = param.result as? MutableMap<String, String?> ?: return@hooker
                for (config in configSet) {
                    config.getEncryptedValue()?.let { result[config.key] = it }
                        ?: result.remove(config.key)
                }
            }
            // v8.28.0 - ? 实例无参方法
            // ? - v8.48.0 .. 静态 (clazz) -> Map 方法（9.8.0 为 t(EnvContext)）
            instance.getPreBuiltConfigMethod()?.let { methodName ->
                hookSignatureCompat(clazz, methodName, "preBuiltConfig", hooker)
            }
        }

        // TypedContext
        instance.dataSPClass?.let { clazz ->
            val hooker: Hooker = hooker@ { param ->
                val result = param.result as? SharedPreferences ?: return@hooker
                if (!result.contains("bv.enable_bv")) return@hooker
                for (config in configSet) {
                    config.getEncryptedValue()?.let {
                        result.edit().putString(config.key, it).apply()
                    } ?: result.edit().remove(config.key).apply()
                }
            }
            // v8.28.0 - ? 实例无参方法
            // ? - v8.48.0 .. 静态 (clazz) -> SharedPreferences 方法（9.8.0 为 f(TypedContext)）
            instance.getDataSPMethod()?.let { methodName ->
                hookSignatureCompat(clazz, methodName, "dataSP", hooker)
            }
        }

        "com.bilibili.lib.blconfig.internal.OverrideConfig".findClassOrNull(mClassLoader)
            ?.hookBeforeAllConstructors { param ->
                val delegate = param.args.getOrNull(0) ?: return@hookBeforeAllConstructors
                val realConfig = param.args.getOrNull(1) // may be null on 8.97.0+ (z12=true)
                val delegateClass = delegate.javaClass
                param.args[0] = Proxy.newProxyInstance(
                    delegateClass.classLoader,
                    delegateClass.interfaces
                ) { _, m, a ->
                    val args = a ?: emptyArray()
                    if (m.name == "getConfig") {
                        var result: Any? = null
                        val key = args[0]
                        for (config in configSet) {
                            if (config.key == key) {
                                result = if (realConfig != null) {
                                    realConfig.callMethodOrNull("get", *args)
                                } else config.getPlainValue()
                            }
                        }
                        result ?: m(delegate, *args)
                    } else {
                        m(delegate, *args)
                    }
                }
            }

//        // Disable tinker
//        "com.tencent.tinker.loader.app.TinkerApplication".findClass(mClassLoader)?.hookBeforeAllConstructors { param ->
//            param.args[0] = 0
//        }
    }

    override fun lateInitHook() {
        Log.d("lateHook: Env")
        if (sPrefs.getBoolean("enable_av", false)) {
            val compatClass = "com.bilibili.droid.BVCompat".findClassOrNull(mClassLoader)
            compatClass?.declaredFields?.forEach { f ->
                runCatchingOrNull {
                    val field = compatClass.getStaticObjectField(f.name)
                    if (field is Pattern && field.pattern() == "av[1-9]\\d*") {
                        compatClass.setStaticObjectField(
                            f.name,
                            Pattern.compile("(av[1-9]\\d*)|(BV1[1-9A-NP-Za-km-z]{9})", field.flags())
                        )
                    }
                }
                if (f.type == Boolean::class.javaPrimitiveType) {
                    runCatching {
                        f.isAccessible = true
                        f.setBoolean(null, false)
                    }.onFailure { Log.e(it) }
                }
            }
        }
    }

    companion object {

        private val encryptedValueMap = hashMapOf(
            "0" to "Irb5O7Q8Ka0ojD4qqScgqg==",
            "1" to "Y260Cyvp6HZEboaGO+YGMw=="
        )

        class ConfigTuple(
            val key: String,
            val config: String,
            val trueValue: String?,
            val falseValue: String?,
            val plainTrueValue: String? = null,
            val plainFalseValue: String? = null
        ) {
            fun getEncryptedValue(): String? =
                if (sPrefs.getBoolean(config, false)) trueValue else falseValue

            fun getPlainValue(): String? =
                if (sPrefs.getBoolean(config, false)) plainTrueValue else plainFalseValue
        }

        val configSet = listOf(
            ConfigTuple(
                "bv.enable_bv",
                "enable_av",
                encryptedValueMap["0"],
                encryptedValueMap["1"],
                "0",
                "1"
            ),
        )
    }
}
