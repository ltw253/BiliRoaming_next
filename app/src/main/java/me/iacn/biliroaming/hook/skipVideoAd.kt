package me.iacn.biliroaming.hook

import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import me.iacn.biliroaming.BiliBiliPackage.Companion.instance
import me.iacn.biliroaming.utils.BilibiliSponsorBlock
import me.iacn.biliroaming.utils.Log
import me.iacn.biliroaming.utils.av2bv
import me.iacn.biliroaming.utils.callMethod
import me.iacn.biliroaming.utils.callMethodAs
import me.iacn.biliroaming.utils.currentContext
import me.iacn.biliroaming.utils.hookAfterMethod
import me.iacn.biliroaming.utils.hookBeforeMethod
import me.iacn.biliroaming.utils.mossResponseHandlerReplaceProxy
import me.iacn.biliroaming.utils.sPrefs
import java.lang.ref.WeakReference
import java.util.Locale

/**
 * SponsorBlock 空降助手（BiliRoaming 播放器侧移植）。
 *
 * 数据来自 [BilibiliSponsorBlock]，在播放时跳过/静音命中的片段。
 * 支持：自动跳过、跳过倒计时、片段静音、最小片段时长、按分类开关、跳过提示与统计。
 */
class SkipVideoAd(classLoader: ClassLoader) : BaseHook(classLoader) {

    companion object {
        private const val LOOKAHEAD_MS = 250L

        /** 静音兜底恢复的宽限时间：片段结束后再多等这么久仍无回调就强制解除静音。 */
        private const val MUTE_WATCHDOG_GRACE_MS = 5_000L
    }

    private var lastSeekTime = 0L
    private var playerRef: WeakReference<Any>? = null
    private val player get() = playerRef?.get()
    private var duration = -1L

    @Volatile
    private var segments: List<BilibiliSponsorBlock.Segment> = emptyList()
    @Volatile
    private var bvid = ""
    @Volatile
    private var cid = ""

    private val skipped = HashSet<String>()
    @Volatile
    private var muted = false

    // 当前拉取片段的任务：切换视频时取消，避免旧视频的结果覆盖新视频。
    private var fetchJob: Job? = null

    // 静音兜底：ADJUST_MUTE 是系统级媒体流标志，若回调停止（暂停/退出/被杀前），
    // 必须保证还能恢复，否则设备会一直没声音。
    private val muteWatchdog = Runnable {
        if (muted) {
            Log.d("mute watchdog fired, restore audio")
            setMuted(false)
        }
    }

    // 设置快照：进入新视频时刷新，避免每帧读 MODE_MULTI_PROCESS 的 SharedPreferences。
    @Volatile private var server = BilibiliSponsorBlock.DEFAULT_SERVER
    @Volatile private var autoSkip = true
    @Volatile private var muteEnabled = false
    @Volatile private var showToast = true
    @Volatile private var statsEnabled = true
    @Volatile private var minDurationMs = 0L
    @Volatile private var countdownMs = 0L
    @Volatile private var enabledCategories: Set<String> = BilibiliSponsorBlock.defaultCategories

    private val handler = Handler(Looper.getMainLooper())
    private var countdownSegment: BilibiliSponsorBlock.Segment? = null
    private var countdownRunnable: Runnable? = null

    override fun startHook() {
        if (!sPrefs.getBoolean("skip_video_ad", false)) return

        Log.d("startHook: SkipVideoAd")

        instance.playerMossClass?.apply {
            hookBeforeMethod(
                "executePlayViewUnite",
                instance.playViewUniteReqClass
            ) { param ->
                val req = param.args[0]
                bvid = req.callMethodAs("getBvid")
                val vod = req.callMethod("getVod") ?: return@hookBeforeMethod
                if (bvid.isEmpty()) {
                    val aid = vod.callMethodAs<Long>("getAid")
                    if (aid == -1L) {
                        return@hookBeforeMethod
                    }
                    bvid = av2bv(aid)
                }
                cid = vod.callMethodAs<Long>("getCid").toString()
            }

            hookBeforeMethod(
                "playViewUnite",
                instance.playViewUniteReqClass,
                instance.mossResponseHandlerClass
            ) { param ->
                param.args[1] = param.args[1].mossResponseHandlerReplaceProxy { reply ->
                    reply ?: return@mossResponseHandlerReplaceProxy null
                    val playArc = reply.callMethod("getPlayArc")
                        ?: return@mossResponseHandlerReplaceProxy null
                    cid = playArc.callMethodAs<Long>("getCid").toString()
                    val aid = playArc.callMethodAs<Long>("getAid") ?: -1L
                    if (aid == -1L) {
                        return@mossResponseHandlerReplaceProxy null
                    }
                    bvid = av2bv(aid)
                    null
                }
            }
        }

        instance.playerCoreServiceV2Class?.apply {
            // 播放器状态回调：9.8.0 是 r(I)V，旧版是 G1(I)V。
            // 额外要求返回 void，避免同类里的同名重载被误选；
            // 找不到时明确记日志，而不是静默地"开了但什么都不跳"。
            val stateCandidates = declaredMethods.filter {
                (it.name == "G1" || it.name == "r") && it.parameterTypes.size == 1 &&
                    it.parameterTypes[0] == Int::class.javaPrimitiveType &&
                    it.returnType == java.lang.Void.TYPE
            }
            if (stateCandidates.size > 1) {
                Log.d(
                    "SkipVideoAd: 状态回调有多个候选，取第一个：" +
                            stateCandidates.joinToString { m ->
                                m.name + m.parameterTypes.contentToString()
                            }
                )
            }
            val stateMethod = stateCandidates.firstOrNull()
            if (stateMethod == null) {
                Log.d("SkipVideoAd: 未找到播放器状态回调 G1/r(I)V，跳过与静音可能不工作")
            } else {
                Log.d("SkipVideoAd: 状态回调 = ${stateMethod.name}(I)V")
                // 用精确 Member 挂钩，避免 findAndHookMethod 按名字+参数重新查找时选到同签名重载
                stateMethod.hookAfterMethod { param ->
                    playerRef = WeakReference(param.thisObject)
                    val state = param.args[0] as Int
                    if (state in 3..5 && duration <= 0) {
                        duration = (player?.callMethodAs<Int>("getDuration") ?: -1).toLong()
                    }
                    if (state == 2) {
                        onVideoStart()
                    }
                }
            }

            hookAfterMethod("getCurrentPosition") { param ->
                val position = (param.result as? Number)?.toLong() ?: return@hookAfterMethod
                onProgress(position)
            }
        }
    }

    private fun onVideoStart() {
        duration = -1
        segments = emptyList()
        skipped.clear()
        setMuted(false)
        cancelCountdown()
        reloadSettings()
        val currentBvid = bvid
        val currentCid = cid
        if (currentBvid.isEmpty() || currentCid.isEmpty()) return
        if (BilibiliSponsorBlock.hasCached(currentBvid, currentCid)) {
            segments = BilibiliSponsorBlock.cached(currentBvid, currentCid).orEmpty()
            return
        }
        // 取消上一轮的拉取，避免旧视频的结果在新视频播放期间覆盖 segments。
        fetchJob?.cancel()
        fetchJob = CoroutineScope(Dispatchers.IO).launch {
            var retryCount = 0
            val maxRetries = 3
            while (isActive && retryCount < maxRetries) {
                val result = BilibiliSponsorBlock.fetch(
                    currentBvid, currentCid, server, enabledCategories
                )
                // fetch 返回 null 表示本次失败（不写缓存），可以重试；
                // 空列表表示服务端确实没有片段，属于有效结果。
                if (result != null) {
                    // 写入前确认仍然是同一个视频，否则丢弃这一批过期结果。
                    if (bvid == currentBvid && cid == currentCid) {
                        segments = result
                    }
                    break
                }
                retryCount++
                if (retryCount < maxRetries) delay(1000)
            }
        }
    }

    private fun reloadSettings() {
        server = sPrefs.getString("skip_video_ad_server", null)
            ?.takeIf { it.isNotBlank() } ?: BilibiliSponsorBlock.DEFAULT_SERVER
        autoSkip = sPrefs.getBoolean("skip_video_ad_auto", true)
        muteEnabled = sPrefs.getBoolean("skip_video_ad_mute", false)
        showToast = sPrefs.getBoolean("skip_video_ad_toast", true)
        statsEnabled = sPrefs.getBoolean("skip_video_ad_stats", true)
        enabledCategories = sPrefs.getStringSet("skip_video_ad_categories", null)
            ?: BilibiliSponsorBlock.defaultCategories
        minDurationMs = (sPrefs.getString("skip_video_ad_min_duration", "0")
            ?.toFloatOrNull()?.times(1000f) ?: 0f).toLong()
        countdownMs = (sPrefs.getString("skip_video_ad_countdown", "0")
            ?.toFloatOrNull()?.times(1000f) ?: 0f).toLong()
        // 服务器地址或启用分类变化时让进程内缓存失效，否则改完设置仍会用旧结果
        BilibiliSponsorBlock.syncSettings(server, enabledCategories)
    }

    private fun onProgress(positionMs: Long) {
        if (duration <= 0L) return
        val position = positionMs.coerceIn(0L, duration)

        // 静音状态机必须放在倒计时提前返回之前：否则倒计时期间进入的静音片段不会静音，
        // 离开片段时也不会恢复音频，而且会掩盖 setMuted 的兜底解除。
        updateMuteState(position)

        // 倒计时进行中：离开片段则取消，否则等待回调触发跳过。
        countdownSegment?.let { seg ->
            if (position < seg.startMs - LOOKAHEAD_MS || position >= seg.endMs) {
                cancelCountdown()
            } else {
                return
            }
        }

        if (!autoSkip) return

        val segment = segments.firstOrNull {
            it.actionType == BilibiliSponsorBlock.ACTION_SKIP &&
                it.category != BilibiliSponsorBlock.CATEGORY_HIGHLIGHT &&
                it.durationMs >= minDurationMs &&
                it.uuid !in skipped &&
                position >= it.startMs - LOOKAHEAD_MS &&
                position < it.endMs
        } ?: return

        if (countdownMs > 0L) {
            startCountdown(segment)
        } else {
            skipped.add(segment.uuid)
            if (performSkip(segment)) {
                onSkipped(segment)
            } else {
                skipped.remove(segment.uuid)
            }
        }
    }

    private fun startCountdown(segment: BilibiliSponsorBlock.Segment) {
        skipped.add(segment.uuid)
        countdownSegment = segment
        val totalMs = minOf(countdownMs, segment.durationMs / 2).coerceAtLeast(1L)
        if (showToast) {
            Log.toast("${BilibiliSponsorBlock.displayName(segment.category)} ${totalMs / 1000}秒后跳过")
        }
        val runnable = Runnable {
            countdownSegment = null
            countdownRunnable = null
            val current = player?.callMethodAs<Int>("getCurrentPosition")?.toLong() ?: -1L
            if (current in 0 until segment.endMs && performSkip(segment)) {
                onSkipped(segment)
            }
        }
        countdownRunnable = runnable
        handler.postDelayed(runnable, totalMs)
    }

    private fun cancelCountdown() {
        countdownRunnable?.let { handler.removeCallbacks(it) }
        countdownRunnable = null
        countdownSegment?.let { skipped.remove(it.uuid) }
        countdownSegment = null
    }

    private fun performSkip(segment: BilibiliSponsorBlock.Segment): Boolean {
        val position = player?.callMethodAs<Int>("getCurrentPosition")?.toLong() ?: return false
        // 已经越过片段尾：seek(endMs) 是往回跳且没有省下时长，放弃。
        if (position >= segment.endMs) return false
        val target = if (duration in 1 until segment.endMs) duration else segment.endMs
        if (System.currentTimeMillis() - lastSeekTime < 200) return false
        lastSeekTime = System.currentTimeMillis()
        player?.callMethod("seekTo", target.toInt())
        return true
    }

    private fun onSkipped(segment: BilibiliSponsorBlock.Segment) {
        if (statsEnabled) {
            val count = sPrefs.getLong("skip_video_ad_count", 0L) + 1
            val saved = sPrefs.getLong("skip_video_ad_saved_ms", 0L) + segment.durationMs
            sPrefs.edit()
                .putLong("skip_video_ad_count", count)
                .putLong("skip_video_ad_saved_ms", saved)
                .apply()
        }
        if (showToast) {
            val name = BilibiliSponsorBlock.displayName(segment.category)
            val seconds = String.format(Locale.US, "%.1f", segment.durationMs / 1000.0)
            Log.toast("已跳过 $name (${seconds}秒)")
        }
    }

    private fun updateMuteState(position: Long) {
        if (!muteEnabled) {
            if (muted) setMuted(false)
            return
        }
        val muteSegment = segments.firstOrNull {
            it.actionType == BilibiliSponsorBlock.ACTION_MUTE &&
                it.durationMs >= minDurationMs &&
                position >= it.startMs && position < it.endMs
        }
        setMuted(muteSegment != null)
        if (muteSegment != null) {
            // 以"片段剩余时长 + 宽限"布防：即使之后进度回调完全停止（暂停/退出播放页），
            // 也能保证音频最终被恢复。
            val remaining = (muteSegment.endMs - position).coerceAtLeast(1_000L)
            armMuteWatchdog(remaining + MUTE_WATCHDOG_GRACE_MS)
        }
    }

    private fun armMuteWatchdog(delayMs: Long) {
        handler.removeCallbacks(muteWatchdog)
        handler.postDelayed(muteWatchdog, delayMs)
    }

    private fun setMuted(mute: Boolean) {
        if (mute == muted) return
        val audioManager = try {
            currentContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        } catch (e: Exception) {
            null
        } ?: return
        try {
            if (mute) {
                audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0)
            } else {
                audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)
                handler.removeCallbacks(muteWatchdog)
            }
            muted = mute
        } catch (e: Exception) {
            Log.e(e)
        }
    }
}
