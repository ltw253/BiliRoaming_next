package me.iacn.biliroaming.utils

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * BiliSponsorBlock 数据层。
 *
 * 参考 hanydd/BilibiliSponsorBlock 与 BitStandByYou/BiliSponsorBlock 的协议实现：
 * 请求 `{server}/api/skipSegments/{sha256(bvid)[0..4)}`，服务端返回该 hash 前缀下
 * 所有视频的桶，客户端再按完整 videoID 匹配。
 */
object BilibiliSponsorBlock {
    const val DEFAULT_SERVER = "https://bsbsb.top"

    const val CATEGORY_HIGHLIGHT = "poi_highlight"
    const val ACTION_SKIP = "skip"
    const val ACTION_MUTE = "mute"
    const val ACTION_POI = "poi"

    /** 分类的中文显示名，顺序即 UI 展示顺序。 */
    val categoryNames: Map<String, String> = linkedMapOf(
        "sponsor" to "赞助/恰饭",
        "selfpromo" to "自我推广",
        "interaction" to "互动提醒",
        "intro" to "开场动画",
        "outro" to "结束画面",
        "preview" to "回顾/概要",
        "music_offtopic" to "非音乐片段",
        "filler" to "填充内容",
        CATEGORY_HIGHLIGHT to "精彩时刻",
    )

    /** 默认启用的分类（不含只做标记的精彩时刻）。 */
    val defaultCategories: Set<String> = setOf(
        "sponsor", "selfpromo", "interaction", "intro",
        "outro", "preview", "music_offtopic", "filler",
    )

    fun displayName(category: String): String = categoryNames[category] ?: category

    data class Segment(
        val category: String,
        val actionType: String,
        val startMs: Long,
        val endMs: Long,
        val uuid: String,
        val description: String = "",
    ) {
        val durationMs: Long get() = (endMs - startMs).coerceAtLeast(0L)
    }

    private class CacheEntry(val timeMs: Long, val segments: List<Segment>)

    private val cache = ConcurrentHashMap<String, CacheEntry>()

    private const val CACHE_TTL_MS = 30 * 60 * 1000L

    /** 缓存对应的设置签名（服务器 + 启用分类），签名变化时整体失效。 */
    private var cacheSignature: String? = null

    /**
     * 同步当前设置：服务器地址或启用分类变化时清空进程内缓存。
     * 否则用户改了分类/服务器后，30 分钟内仍会命中按旧规则生成的结果（表现为设置不生效）。
     */
    fun syncSettings(server: String, enabledCategories: Set<String>) {
        val signature =
            server.trimEnd('/') + "|" + enabledCategories.sorted().joinToString(",")
        if (signature != cacheSignature) {
            cacheSignature = signature
            cache.clear()
        }
    }
    private const val MIN_SEGMENT_MS = 250L
    private const val MAX_SEGMENT_SECONDS = 86_400.0
    private const val CONNECT_TIMEOUT_MS = 5_000
    private const val READ_TIMEOUT_MS = 5_000

    /** 命中未过期的进程内缓存时返回，否则返回 null。 */
    fun cached(bvid: String, cid: String): List<Segment>? {
        val entry = cache["$bvid:$cid"] ?: return null
        if (System.currentTimeMillis() - entry.timeMs > CACHE_TTL_MS) return null
        return entry.segments
    }

    fun hasCached(bvid: String, cid: String): Boolean = cached(bvid, cid) != null

    fun clear(bvid: String, cid: String) {
        cache.remove("$bvid:$cid")
    }

    /**
     * 拉取并缓存该视频的片段。
     *
     * 返回 null 表示本次拉取失败（网络异常 / 非 2xx / 解析异常），失败结果**不会**写入缓存，
     * 调用方可自行重试；返回空列表表示服务端明确没有该视频的片段，属于有效结果，可以缓存。
     */
    fun fetch(
        bvid: String,
        cid: String,
        server: String,
        enabledCategories: Set<String>,
    ): List<Segment>? {
        val segments = try {
            val prefix = sha256(bvid).take(4)
            val url = URL("${server.trimEnd('/')}/api/skipSegments/$prefix")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty("Origin", "BiliRoaming")
                setRequestProperty("X-EXT-VERSION", "1.7.0")
            }
            try {
                val status = conn.responseCode
                if (status !in 200..299) {
                    Log.e("SponsorBlock fetch HTTP $status for $bvid")
                    return null
                }
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                parse(body, bvid, cid, enabledCategories)
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            Log.e(e)
            return null
        }
        cache["$bvid:$cid"] = CacheEntry(System.currentTimeMillis(), segments)
        return segments
    }

    private fun parse(
        raw: String,
        bvid: String,
        cid: String,
        enabledCategories: Set<String>,
    ): List<Segment> {
        if (raw.isBlank()) return emptyList()
        val result = mutableListOf<Segment>()
        val buckets = JSONArray(raw)
        for (i in 0 until buckets.length()) {
            val bucket = buckets.optJSONObject(i) ?: continue
            if (bucket.optString("videoID") != bvid) continue
            val array = bucket.optJSONArray("segments") ?: continue
            for (j in 0 until array.length()) {
                val obj = array.optJSONObject(j) ?: continue
                parseSegment(obj, cid, enabledCategories)?.let { result += it }
            }
            break
        }
        return result.sortedBy { it.startMs }
    }

    private fun parseSegment(
        json: JSONObject,
        cid: String,
        enabledCategories: Set<String>,
    ): Segment? {
        val segmentArray = json.optJSONArray("segment") ?: return null
        // 同一 bvid 的多个分P 会一起返回，必须按 cid 过滤，
        // 否则会拿别的分P 的时间轴去跳当前分P（多分P 串味）。
        // 服务端未提供 cid 时不过滤，保持向后兼容，避免把片段全部丢弃。
        val segmentCid = optStringOrNull(json, "cid")?.takeIf { it.isNotBlank() }
        if (segmentCid != null && cid.isNotBlank() && segmentCid != cid) return null
        val category = optStringOrNull(json, "category")?.takeIf { it.isNotBlank() } ?: "sponsor"
        val actionType = optStringOrNull(json, "actionType")?.takeIf { it.isNotBlank() } ?: ACTION_SKIP
        if (category !in enabledCategories) return null
        if (actionType != ACTION_SKIP && actionType != ACTION_MUTE && actionType != ACTION_POI) return null

        // UUID 是去重 key，缺失直接丢弃该片段。
        val uuid = optStringOrNull(json, "UUID")?.takeIf { it.isNotBlank() }
            ?: optStringOrNull(json, "uuid")?.takeIf { it.isNotBlank() }
            ?: return null

        val isFullOrPoi = actionType == "full" || actionType == ACTION_POI
        if (segmentArray.length() < if (isFullOrPoi) 1 else 2) return null

        val startSec = segmentArray.optDouble(0, Double.NaN)
        val endSec = if (segmentArray.length() > 1) segmentArray.optDouble(1, Double.NaN) else startSec
        if (!startSec.isFinite()) return null
        if (startSec > MAX_SEGMENT_SECONDS || endSec > MAX_SEGMENT_SECONDS) return null

        val startMs = (startSec * 1000.0).toLong()
        val endMs = (endSec * 1000.0).toLong()
        if (startMs < 0L) return null
        if (endMs > startMs && endMs - startMs < MIN_SEGMENT_MS) return null
        if (endMs < startMs) return null

        return Segment(
            category = category,
            actionType = actionType,
            startMs = startMs,
            endMs = endMs,
            uuid = uuid,
            description = optStringOrNull(json, "description") ?: "",
        )
    }

    private fun optStringOrNull(json: JSONObject, key: String): String? =
        if (json.isNull(key)) null else json.optString(key)

    // MessageDigest 不是线程安全的，每次 fetch 都可能在不同 IO 协程上并行调用，
    // 共享单例会导致 hash 前缀算错（进而拿到空结果）。这里每次新建实例。
    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray())
            .joinToString("") { "%02x".format(it) }
}
