package me.iacn.biliroaming.hook

import android.net.Uri
import me.iacn.biliroaming.BiliBiliPackage.Companion.instance
import me.iacn.biliroaming.BuildConfig
import me.iacn.biliroaming.utils.*
import org.json.JSONArray
import org.json.JSONObject

class LiveQualityHook(classLoader: ClassLoader) : BaseHook(classLoader) {

    companion object {
        private const val TAG = "LiveQualityHook"
    }

    @Volatile
    private var newQn: String = ""

    private fun debug(msg: () -> String) {
        if (BuildConfig.DEBUG) {
            Log.d("[$TAG] - ${msg()}")
        }
    }

    override fun startHook() {
        val liveQuality = sPrefs.getString("live_quality", "0")?.toIntOrNull() ?: 0
        if (liveQuality <= 0) {
            return
        }

        val canSwitchLiveRoom = !sPrefs.getBoolean("forbid_switch_live_room", false)
        Log.d("startHook: LiveQuality qn=$liveQuality canSwitch=$canSwitchLiveRoom")
        Log.d(
            "LiveQuality: 锚点 sourceService=${instance.liveRTCSourceServiceImplClass?.name} " +
                    "selectUtil=${instance.livePlayUrlSelectUtilClass?.name}"
        )

        instance.defaultRequestInterceptClass?.hookBeforeAllMethods(instance.interceptMethod()) { param ->
            val request = param.args[0]
            val httpUrl = request.getObjectField(instance.urlField())
            val url = httpUrl.toString()
            if (!url.startsWith("https://api.live.bilibili.com/xlive/app-room/v2/index/getRoomPlayInfo?")) {
                return@hookBeforeAllMethods
            }

            debug { "oldHttpUrl: $url" }

            val uri = Uri.parse(httpUrl.toString())
            val qn = uri.getQueryParameter("qn")
            if (qn.isNullOrEmpty() || qn == "0") {
                val builder = uri.buildUpon().clearQuery()
                for (name in uri.queryParameterNames) {
                    if (name == "qn") {
                        builder.appendQueryParameter(name, newQn.ifEmpty { liveQuality.toString() })
                    } else {
                        builder.appendQueryParameter(name, uri.getQueryParameter(name))
                    }
                }
                val newHttpUrl = instance.httpUrlClass?.callStaticMethod(
                    instance.httpUrlParseMethod(),
                    builder.build().toString()
                )
                debug { "newHttpUrl: $newHttpUrl" }
                request.setObjectField(instance.urlField(), newHttpUrl)
                Log.d(
                    "LiveQuality: 已注入 qn=" +
                            newQn.ifEmpty { liveQuality.toString() }
                )
            }
        }

        instance.retrofitResponseClass?.hookBeforeAllConstructors { param ->
            // 9.8.0 的直播间列表数据里存在缺少 accept_quality 的条目，
            // 旧实现直接 getJSONArray 会抛 JSONException 并中断整个清晰度处理，
            // 这里整体兜住异常，避免一次脏数据让功能彻底失效。
            runCatching {
                val url = getRetrofitUrl(param.args[0]) ?: return@runCatching
                val body = param.args[1] ?: return@runCatching

                when {
                    instance.generalResponseClass?.isInstance(body) != true -> Unit
                    // 处理上下滑动切换直播间
                    url.startsWith("https://api.live.bilibili.com/xlive/app-interface/v2/room/recList?") && canSwitchLiveRoom -> {
                        val data = body.getObjectField("data") ?: return@runCatching
                        val info = JSONObject(
                            instance.fastJsonClass?.callStaticMethod("toJSONString", data).toString()
                        )
                        if (fixLiveRoomFeedInfo(info, liveQuality)) {
                            body.setObjectField(
                                "data",
                                instance.fastJsonClass?.callStaticMethod(
                                    instance.fastJsonParse(),
                                    info.toString(),
                                    data.javaClass
                                )
                            )
                            Log.d("LiveQuality: 已修正直播间切换列表清晰度")
                        }
                    }

                    url.startsWith("https://api.live.bilibili.com/xlive/app-room/v2/index/getRoomPlayInfo?") -> {
                        val data = body.getObjectField("data") ?: return@runCatching
                        val info = JSONObject(
                            instance.fastJsonClass?.callStaticMethod("toJSONString", data).toString()
                        )
                        if (BuildConfig.DEBUG) printCodec(info)
                        logServerQuality(info)
                    }
                }
            }.onFailure { Log.e("LiveQuality: 处理响应失败 $it") }
        }

        // Mode 枚举（解包 classes7.dex 确认）：
        //   0 UN_SET / 1 USER_SELECT / 2 AUTO / 3 SELECT_TO_AUTO
        //   4 BIZ_AUTO / 5 QOE_AUTO / 6 ONLY_AUDIO / 7 BROADCAST_AUTO
        // 旧实现只是跳过 AUTO 切换；9.8.0 上播放器内部状态仍停留在自动，
        // 因此这里把 AUTO 改写成 USER_SELECT，让播放器按用户选择的清晰度工作。
        val userSelectMode = runCatchingOrNull {
            "com.bilibili.bililive.source.Mode".findClassOrNull(mClassLoader)
                ?.getStaticObjectField("USER_SELECT")
        }
        Log.d("LiveQuality: USER_SELECT 模式=${userSelectMode?.toString() ?: "null"}")
        instance.liveRTCSourceServiceImplClass?.hookBeforeAllMethods(instance.switchAutoMethod()) { param ->
            val mode = param.args[0] as? Enum<*> ?: return@hookBeforeAllMethods
            if (mode.ordinal == 2) { // AUTO
                if (userSelectMode != null) {
                    param.args[0] = userSelectMode
                    Log.d("LiveQuality: 直播模式 AUTO -> USER_SELECT")
                } else {
                    param.result = null
                    Log.d("LiveQuality: 已取消直播 AUTO 清晰度")
                }
            }
        }
        // 记录播放器当前清晰度模式，确认修正是否生效
        var modeLogs = 0
        instance.liveRTCSourceServiceImplClass?.hookAfterMethod("getMode") { param ->
            if (modeLogs < 10) {
                modeLogs++
                Log.d("LiveQuality: 当前播放模式=${param.result}")
            }
        }

        // 9.8.0 直播改用 sistersplayer/HLS 决策器（StreamDecider）：
        // 即使把请求 qn 改对，决策器仍会按自己的策略选流，导致实际画质与设置不符。
        // 这里在决策器选出流之后强制替换为目标清晰度的流。
        runCatchingOrNull {
            "com.bilibili.bililive.player.rtc.decider.StreamDecider"
                .findClassOrNull(mClassLoader)
        }?.let { deciderClass ->
            val selectMethod = deciderClass.declaredMethods.firstOrNull { method ->
                method.parameterTypes.firstOrNull()?.let {
                    java.util.List::class.java.isAssignableFrom(it)
                } == true && method.returnType.name.contains("HLSStreamInf")
            }
            var streamSelectLogs = 0
            selectMethod?.hookAfterMethod { param ->
                runCatching {
                    @Suppress("UNCHECKED_CAST")
                    val streams = param.args[0] as? List<Any> ?: run {
                        Log.d("LiveQuality: 选流调用但首参不是 List")
                        return@runCatching
                    }
                    if (streamSelectLogs < 6) {
                        streamSelectLogs++
                        val qns = streams.take(8).mapNotNull { stream ->
                            runCatchingOrNull {
                                (stream.callMethodAs<Any?>("getQn") as? Number)?.toInt()
                            }
                        }
                        Log.d(
                            "LiveQuality: 选流调用 size=${streams.size} qns=$qns " +
                                    "当前结果qn=" +
                                    runCatchingOrNull {
                                        (param.result?.callMethodAs<Any?>("getQn") as? Number)
                                            ?.toInt()
                                    }
                        )
                    }
                    val target = findStreamOfQuality(streams, liveQuality) ?: run {
                        Log.d("LiveQuality: 未在流列表中找到目标 qn=$liveQuality")
                        return@runCatching
                    }
                    if (param.result !== target) {
                        param.result = target
                        Log.d(
                            "LiveQuality: 强制选流 qn=" +
                                    (target.callMethodAs<Any?>("getQn") ?: "?")
                        )
                    }
                }.onFailure { Log.e("LiveQuality: 强制选流失败 $it") }
            }
            Log.d("LiveQuality: 选流钩子=${selectMethod?.name ?: "null"}")
        }

        instance.livePlayUrlSelectUtilClass?.hookBeforeMethod(
            instance.buildSelectorDataMethod(),
            Uri::class.java
        ) { param ->
            val originalUri = param.args[0] as Uri
            if (!originalUri.isLive()) {
                return@hookBeforeMethod
            }

            debug { "originalLiveUrl: $originalUri" }

            if (originalUri.getQueryParameter("no_playurl") == "1") {
                newQn = liveQuality.toString()
            } else {
                newQn = findQuality(
                    JSONArray(originalUri.getQueryParameter("accept_quality")),
                    liveQuality
                ).toString()

                param.args[0] = originalUri.modified(
                    removeIf = { name ->
                        name.startsWith("playurl")
                    },
                    append = mapOf("no_playurl" to "1")
                ).also {
                    debug { "newLiveUrl: $it" }
                }
            }

            debug { "newQn: $newQn" }
            Log.d("LiveQuality: 直播播放地址选择 qn=$newQn")
        }

        // 诊断 + 修正：tianma / Bundle 两条解析路径最终交给播放器的数据
        //（LiveUrlSelectorData，含 getCurrentQn()/getMasterUrl()/getMediaInfoStr()）。
        runCatchingOrNull {
            "com.bilibili.bililive.liveplayer.params.LiveFastPlayUrlSelector"
                .findClassOrNull(mClassLoader)
        }?.let { selectorClass ->
            var selectorLogs = 0
            selectorClass.declaredMethods
                .firstOrNull {
                    it.name == "parsePlayUrlFromTianma" && it.parameterTypes.size == 1
                }
                ?.hookAfterMethod { param ->
                    runCatching {
                        val data = param.result ?: return@runCatching
                        val qnInt = (data.callMethodAs<Any?>("getCurrentQn") as? Number)
                            ?.toInt() ?: -1
                        val masterUrl = data.callMethodAs<Any?>("getMasterUrl") as? String
                        if (selectorLogs < 8) {
                            selectorLogs++
                            Log.d(
                                "LiveQuality: tianma 结果 qn=$qnInt masterHost=" +
                                        masterUrl?.let { android.net.Uri.parse(it).host }
                            )
                        }
                        if (liveQuality > 0 && qnInt != liveQuality) {
                            val forced = forceDataQn(data, liveQuality)
                            if (forced != null) {
                                param.result = forced
                                Log.d("LiveQuality: tianma 结果已强制改为 qn=$liveQuality")
                            } else {
                                Log.d(
                                    "LiveQuality: tianma 清晰度不符 当前=$qnInt 期望=$liveQuality"
                                )
                            }
                        }
                    }.onFailure { Log.e("LiveQuality: 解析 tianma 结果失败 $it") }
                }

            // 播放器实际走的 Bundle 路径
            selectorClass.declaredMethods
                .firstOrNull {
                    it.name == "parsePlayUrlInBundle" && it.parameterTypes.size == 1
                }
                ?.let { method ->
                    var bundleLogs = 0
                    method.hookBeforeMethod { param ->
                        runCatching {
                            val bundle = param.args[0] as? android.os.Bundle
                                ?: return@hookBeforeMethod
                            if (bundleLogs < 3) {
                                bundleLogs++
                                Log.d("LiveQuality: bundle keys=${bundle.keySet()}")
                            }
                            if (liveQuality > 0 && bundle.containsKey("qn")) {
                                bundle.putInt("qn", liveQuality)
                                Log.d("LiveQuality: bundle qn 已改写为 $liveQuality")
                            }
                        }
                    }
                    method.hookAfterMethod { param ->
                        runCatching {
                            val data = param.result ?: return@hookAfterMethod
                            val qnInt = (data.callMethodAs<Any?>("getCurrentQn") as? Number)
                                ?.toInt() ?: -1
                            val masterUrl = data.callMethodAs<Any?>("getMasterUrl") as? String
                            if (bundleLogs < 6) {
                                bundleLogs++
                                Log.d(
                                    "LiveQuality: bundle 结果 qn=$qnInt masterHost=" +
                                            masterUrl?.let { android.net.Uri.parse(it).host }
                                )
                            }
                            if (liveQuality > 0 && qnInt != liveQuality) {
                                forceDataQn(data, liveQuality)?.let { forced ->
                                    param.result = forced
                                    Log.d("LiveQuality: bundle 结果已强制改为 qn=$liveQuality")
                                }
                            }
                        }
                    }
                }
            Log.d("LiveQuality: 直播数据诊断钩子已安装")
        }
    }

    /** 用 Kotlin data class 的 copy() 把 currentQn 改成目标清晰度。 */
    private fun forceDataQn(data: Any, targetQn: Int): Any? {
        val currentQn = (data.callMethodAs<Any?>("getCurrentQn") as? Number)?.toInt()
            ?: return null
        if (currentQn == targetQn) return null
        val components = (1..7).map { index -> data.callMethodAs<Any?>("component$index") }
        var replaced = false
        val newComponents = components.map { component ->
            if (!replaced && (component as? Number)?.toInt() == currentQn) {
                replaced = true
                targetQn
            } else {
                component
            }
        }.toTypedArray()
        if (!replaced) return null
        return runCatchingOrNull { data.callMethodAs<Any?>("copy", *newComponents) }
    }

    private fun Uri.isLive(): Boolean {
        return scheme in arrayOf("http", "https")
                && host == "live.bilibili.com"
                && pathSegments.firstOrNull()?.all { it.isDigit() } == true
    }

    /**
     * 从 HLS 流列表里挑出目标清晰度的流。
     *
     * 依据 9.8.0 解包：`com.bilibili.sistersplayer.hls.model.HLSStreamInf` 提供
     * `getQn(): Integer`；优先精确命中，其次取不超过目标的最高档，最后退回最高档。
     */
    private fun findStreamOfQuality(streams: List<Any>, expectedQuality: Int): Any? {
        if (expectedQuality <= 0) return null
        fun qnOf(stream: Any): Int = runCatchingOrNull {
            (stream.callMethodAs<Any?>("getQn") as? Number)?.toInt()
        } ?: -1
        val candidates = streams.filter { qnOf(it) > 0 }
        if (candidates.isEmpty()) return null
        return candidates.firstOrNull { qnOf(it) == expectedQuality }
            ?: candidates.filter { qnOf(it) <= expectedQuality }
                .maxByOrNull { qnOf(it) }
            ?: candidates.maxByOrNull { qnOf(it) }
    }

    private fun findQuality(acceptQuality: JSONArray, expectQuality: Int): Int {
        val acceptQnList = acceptQuality.asSequence<Int>().sorted().toList()
        val max = acceptQnList.max()
        val min = acceptQnList.min()
        return when {
            expectQuality > max -> max
            expectQuality < min -> min
            else -> acceptQnList.first { it >= expectQuality }
        }
    }

    private fun Uri.modified(removeIf: (String) -> Boolean, append: Map<String, Any>): Uri {
        val newBuilder = buildUpon().clearQuery()
        for (name in queryParameterNames) {
            val value = getQueryParameter(name) ?: ""
            if (!removeIf(name)) {
                newBuilder.appendQueryParameter(name, value)
            }
        }
        append.forEach { (k, v) ->
            newBuilder.appendQueryParameter(k, v.toString())
        }
        return newBuilder.build()
    }

    /**
     * 修正直播间上下滑动列表里的清晰度。
     *
     * 9.8.0 起该接口的部分条目不再携带 `accept_quality`（实测会抛
     * `JSONException: No value for accept_quality`），因此这里改为逐条容错：
     * 缺少字段的条目直接跳过，不再让整个处理中断。
     */
    private fun fixLiveRoomFeedInfo(info: JSONObject, expectQuality: Int): Boolean {
        val feedList = info.optJSONArray("list") ?: return false
        var changed = false
        for (index in 0 until feedList.length()) {
            val feedData = feedList.optJSONObject(index) ?: continue
            val acceptQuality = feedData.optJSONArray("accept_quality") ?: continue
            if (acceptQuality.length() == 0) continue
            val newQuality = runCatching {
                findQuality(acceptQuality, expectQuality)
            }.getOrNull() ?: continue
            feedData.put("current_qn", newQuality)
            feedData.put("current_quality", newQuality)
            feedData.put("play_url", "")
            feedData.put("play_url_h265", "")
            feedData.put("playurl_infos", JSONArray())
            changed = true
        }
        return changed
    }

    /** 记录服务端返回的直播清晰度，用于判断 qn 注入是否真的生效。 */
    private fun logServerQuality(data: JSONObject) {
        runCatching {
            val playurlInfo = data.optJSONObject("playurl_info") ?: return@runCatching
            val playurl = playurlInfo.optJSONObject("playurl") ?: return@runCatching
            val streams = playurl.optJSONArray("stream") ?: return@runCatching
            for (i in 0 until streams.length()) {
                val stream = streams.optJSONObject(i) ?: continue
                val formats = stream.optJSONArray("format") ?: continue
                for (j in 0 until formats.length()) {
                    val format = formats.optJSONObject(j) ?: continue
                    val codecs = format.optJSONArray("codec") ?: continue
                    for (k in 0 until codecs.length()) {
                        val codec = codecs.optJSONObject(k) ?: continue
                        Log.d(
                            "LiveQuality: 服务端返回 qn=${codec.optInt("current_qn", -1)} " +
                                    "accept=${codec.optJSONArray("accept_qn")} " +
                                    "format=${format.optString("format_name")}"
                        )
                    }
                }
            }
        }.onFailure { Log.e("LiveQuality: 解析服务端清晰度失败 $it") }
    }

    private fun printCodec(info: JSONObject) {
        val playUrlInfo = info.optJSONObject("playurl_info") ?: return
        val playUrlObj = playUrlInfo.getJSONObject("playurl")
        debug { "printCodec >>>>>>>>>>>>>>" }
        playUrlObj.getJSONArray("stream").iterator().forEach { stream ->
            stream.getJSONArray("format").iterator().forEach { format ->
                format.getJSONArray("codec").iterator().forEach {
                    debug { "codec: ${it.toString(2)}" }
                }
            }
        }
        debug { "printCodec <<<<<<<<<<<<<<" }
    }
}