package me.iacn.biliroaming.hook

import me.iacn.biliroaming.BiliBiliPackage.Companion.instance
import me.iacn.biliroaming.utils.*

class VideoQualityHook(classLoader: ClassLoader) : BaseHook(classLoader) {
    override fun startHook() {
        if (!sPrefs.getBoolean("main_func", false)) return

        val halfScreenQuality = sPrefs.getString("half_screen_quality", "0")?.toInt() ?: 0
        val fullScreenQuality = sPrefs.getString("full_screen_quality", "0")?.toInt() ?: 0
        // half_screen_quality == 1 是"跟随全屏/默认"的哨兵值，不是真实 qn。
        // 直接当 qn=1 用会把半屏缩略图和自动画质压到最低画质。
        val effectiveHalfQuality =
            if (halfScreenQuality == 1) fullScreenQuality else halfScreenQuality
        Log.d(
            "startHook: VideoQuality half=$halfScreenQuality full=$fullScreenQuality " +
                    "effectiveHalf=$effectiveHalfQuality"
        )
        if (effectiveHalfQuality != 0) {
            instance.playerPreloadHolderClass?.replaceAllMethods(instance.getPreload()) { null }
            instance.playerQualityServices().forEach { (clazz, getDefaultQnThumb) ->
                clazz?.replaceAllMethods(getDefaultQnThumb) { effectiveHalfQuality }
            }
        }
        if (fullScreenQuality != 0) {
            instance.playerSettingHelperClass?.replaceMethod(instance.getDefaultQn()) { fullScreenQuality }
        }

        if (effectiveHalfQuality != 0 || fullScreenQuality != 0) {
            val desiredQn = if (fullScreenQuality != 0) fullScreenQuality else effectiveHalfQuality
            // 9.8.0+: 播放器画质为“自动”时会调用 setUserQn(0)，这里强制成设定值；
            // 同时关闭自动画质切换，避免随后又被 ABR 改回
            val abrClass = "tv.danmaku.videoplayer.coreV2.ABRManagerKt"
                .findClassOrNull(mClassLoader)
            Log.d("VideoQuality ABRManagerKt=${abrClass != null}")
            abrClass?.hookBeforeMethod("setUserQn", Int::class.java) { param ->
                if (param.args[0] == 0) param.args[0] = desiredQn
            }
            val settingCompanion = "tv.danmaku.biliplayerv2.service.setting.IPlayerSettingService\$Companion"
                .findClassOrNull(mClassLoader)
            Log.d("VideoQuality settingCompanion=${settingCompanion != null}")
            settingCompanion?.hookBeforeMethod(
                "putBoolean", String::class.java, Boolean::class.java
            ) { param ->
                if (param.args[0] == "pref_player_mediaSource_quality_auto_switch") {
                    param.args[1] = false
                }
            }

            // 9.8.0：旧版 AutoSupremumQuality 已从 APK 移除，"默认/自动画质的最高值"
            // 改由 bp.video.default_quality_max_qn(_small) 控制。
            // 这里不持久化改写 B 站设置，只覆盖播放器设置服务的读取结果，
            // 禁用模块后立即恢复原值。
            val qualityMaxQn = mapOf(
                "bp.video.default_quality_max_qn" to fullScreenQuality,
                "bp.video.default_quality_max_qn_small" to effectiveHalfQuality,
            )
            val overriddenMaxQnKeys = HashSet<String>()
            if (settingCompanion == null) {
                Log.d("VideoQuality: IPlayerSettingService Companion 缺失，跳过 default_quality_max_qn 覆盖")
            } else {
                runCatching {
                    settingCompanion.hookAfterMethod(
                        "getInt", String::class.java, Int::class.javaPrimitiveType
                    ) { param ->
                        val key = param.args[0] as? String ?: return@hookAfterMethod
                        val qn = qualityMaxQn[key]?.takeIf { it > 0 } ?: return@hookAfterMethod
                        param.result = qn
                        if (overriddenMaxQnKeys.add(key)) {
                            Log.d("VideoQuality: 已覆盖 $key 的读取值 = $qn")
                        }
                    }
                    settingCompanion.hookAfterMethod(
                        "getLong", String::class.java, Long::class.javaPrimitiveType
                    ) { param ->
                        val key = param.args[0] as? String ?: return@hookAfterMethod
                        val qn = qualityMaxQn[key]?.takeIf { it > 0 } ?: return@hookAfterMethod
                        param.result = qn.toLong()
                        if (overriddenMaxQnKeys.add(key)) {
                            Log.d("VideoQuality: 已覆盖 $key 的读取值 = $qn(long)")
                        }
                    }
                    settingCompanion.hookAfterMethod(
                        "getString", String::class.java, String::class.java
                    ) { param ->
                        val key = param.args[0] as? String ?: return@hookAfterMethod
                        val qn = qualityMaxQn[key]?.takeIf { it > 0 } ?: return@hookAfterMethod
                        param.result = qn.toString()
                        if (overriddenMaxQnKeys.add(key)) {
                            Log.d("VideoQuality: 已覆盖 $key 的读取值 = $qn(string)")
                        }
                    }
                    Log.d(
                        "VideoQuality: default_quality_max_qn 覆盖已安装" +
                                " (full=$fullScreenQuality half=$effectiveHalfQuality)"
                    )
                }.onFailure { Log.e("VideoQuality: default_quality_max_qn 覆盖安装失败: $it") }
            }
        }

        if (effectiveHalfQuality != 0 || fullScreenQuality != 0) {
            instance.autoSupremumQualityClass?.hookBeforeConstructor(
                *Array(6) { Int::class.javaPrimitiveType }
            ) { param ->
                if (effectiveHalfQuality != 0) {
                    param.args[0] = effectiveHalfQuality    // loginHalf

                    param.args[3] = effectiveHalfQuality    // unloginHalf
                    param.args[4] = effectiveHalfQuality    // unloginFull
                    param.args[5] = effectiveHalfQuality    // unloginMobileFull
                }
                if (fullScreenQuality != 0) {
                    param.args[1] = fullScreenQuality       // loginFull
                    param.args[2] = fullScreenQuality       // loginMobileFull
                }
            }
            instance.qualityStrategyProviderClass?.hookBeforeMethod(
                instance.selectQuality(),
                instance.autoSupremumQualityClass,
                Boolean::class.javaPrimitiveType,           // isFullscreen
                Boolean::class.javaPrimitiveType            // isVideoPortrait
            ) { param ->
                // videoQuality = when {
                //     isVideoPortrait && isFullscreen -> loginFull
                //     isVideoPortrait && !isFullscreen -> unloginFull
                //     isFullscreen -> loginHalf
                //     else -> unloginHalf
                // }
                param.args[2] = true
            }
        }
    }
}
