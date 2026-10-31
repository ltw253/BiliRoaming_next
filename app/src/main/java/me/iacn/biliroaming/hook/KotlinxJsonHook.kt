package me.iacn.biliroaming.hook

import me.iacn.biliroaming.BiliBiliPackage.Companion.instance
import me.iacn.biliroaming.hook.kotlinx.KotlinxProcessor
import me.iacn.biliroaming.hook.kotlinx.KotlinxSplashListProcessor
import me.iacn.biliroaming.hook.kotlinx.KotlinxSplashShowProcessor
import me.iacn.biliroaming.utils.Log
import me.iacn.biliroaming.utils.callMethodOrNull
import me.iacn.biliroaming.utils.getObjectFieldAs
import me.iacn.biliroaming.utils.hookAfterAllMethods
import me.iacn.biliroaming.utils.runCatchingOrNull

class KotlinxJsonHook(classLoader: ClassLoader) : BaseHook(classLoader) {
    private val allProcessors = listOf(
        KotlinxSplashListProcessor(),
        KotlinxSplashShowProcessor(),
    )

    private val enabledProcessors: Map<String, List<KotlinxProcessor>> by lazy {
        allProcessors
            .filter { it.shouldEnable() }
            .groupBy { it.targetSerialName }
    }

    override fun startHook() {
        if (enabledProcessors.isEmpty()) return
        Log.d("startHook: KotlinxJson")

        val jsonClass = instance.kotlinJsonClass ?: return

        jsonClass.hookAfterAllMethods("decodeFromString") { param ->
            dispatchResult(param.args.getOrNull(0), param.result)
        }
    }

    private fun dispatchResult(deserializer: Any?, result: Any?) {
        if (deserializer == null || result == null) return

        val descriptor = runCatchingOrNull { deserializer.callMethodOrNull("getDescriptor") }
            ?: return
        // 旧写法直接取描述符的 "serialName" 字段，但 ArrayListClassDesc 这类描述符
        // 只在 SerialDescriptor 接口上提供 getSerialName() 方法、没有该字段，
        // 会抛 NoSuchFieldError（见用户日志 kotlinx.serialization.internal.ArrayListClassDesc#serialName）。
        // 这里优先调用接口方法，失败再回退字段读取。
        val serialName = runCatchingOrNull {
            (descriptor.callMethodOrNull("getSerialName") as? String)?.takeIf { it.isNotEmpty() }
        } ?: runCatchingOrNull {
            descriptor.getObjectFieldAs<String>("serialName")
        } ?: return

        enabledProcessors[serialName]?.forEach { processor ->
            try {
                processor.process(result)
            } catch (e: Throwable) {
                Log.e("KotlinxJsonHook processor ${processor.targetSerialName} error: $e")
            }
        }
    }
}
