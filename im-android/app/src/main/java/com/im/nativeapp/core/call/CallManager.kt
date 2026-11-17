package com.im.nativeapp.core.call

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioManager
import android.util.Log
import com.im.nativeapp.core.log.AppLog
import com.im.nativeapp.core.network.ApiClient
import com.im.nativeapp.core.network.ApiError
import com.im.nativeapp.core.network.CallApi
import com.im.nativeapp.core.network.MessageExtra
import com.im.nativeapp.core.network.unwrap
import com.im.nativeapp.core.ws.ImWebSocket
import com.im.nativeapp.data.FriendRepository
import com.im.nativeapp.data.MessageRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 一对一语音视频通话状态机（对齐 im-ui/src/stores/call.js）。
 *
 * - 信令走已认证的 WS 长连接：上行 type=call，下行 call-signal（由 WsDispatcher 转交 onSignal）
 * - 状态机：idle → dialing(已发 invite)/ringing(收到 invite) → active → ended → idle
 * - 主叫收到 accept 后才发 offer；被叫收到 offer 自动 answer；ice 双向 trickle
 * - 通话结束只有主叫、且真正接通过才落一条 msgType=6 通话记录消息
 * - ICE failed：给 5s 重连窗口，救不回来主动挂断（Web 端同款兜底）
 */
@Singleton
class CallManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val ws: ImWebSocket,
    private val apiClient: ApiClient,
    private val messages: MessageRepository,
    private val friends: FriendRepository
) {
    companion object {
        private const val TAG = "CallManager"
        private val FAIL_GRACE_MS = 5000L
    }

    enum class Status { IDLE, DIALING, RINGING, ACTIVE, ENDED }

    data class CallUiState(
        val status: Status = Status.IDLE,
        val role: String = "",              // caller | callee
        val callId: String = "",
        val mediaType: String = "audio",    // audio | video
        val peerUserId: String? = null,
        val peerName: String = "",
        val conversationId: String? = null,
        val seconds: Int = 0,
        val hint: String = "",
        val muted: Boolean = false,
        val cameraOff: Boolean = false,
        val speaker: Boolean = true,
        val enabled: Boolean = false,
        val iceServers: List<IceServerCfg> = emptyList(),
        val localStream: org.webrtc.MediaStream? = null,
        val remoteStream: org.webrtc.MediaStream? = null,
        val remoteSharing: Boolean = false
    ) {
        val visible: Boolean get() = status != Status.IDLE && status != Status.ENDED
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(CallUiState())
    val state: StateFlow<CallUiState> = _state

    private var engine: CallEngine? = null
    private var config: Pair<Boolean, List<IceServerCfg>>? = null
    private var configLoading: Job? = null
    private var configMutex = Mutex()
    private var tickJob: Job? = null
    private var failJob: Job? = null
    private var startedAtTs = 0L
    private var wasConnected = false
    private var finalizedCall = "" // 已落过记录的 callId，防重复
    private val audioManager by lazy {
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }

    /** 拉一次 ICE 配置并缓存；请求失败不能缓存成「关闭」，否则入口永久消失（Web 端踩过的坑） */
    suspend fun ensureConfig(force: Boolean = false): Pair<Boolean, List<IceServerCfg>>? {
        configMutex.withLock {
            if (config != null && !force) return config
        }
        if (configLoading?.isActive == true) return config
        configLoading = scope.launch {
            try {
                val vo: CallConfigNet = apiClient.service(CallApi::class.java).config().unwrap(apiClient.json)
                val servers = vo.iceServers.orEmpty().mapNotNull { item ->
                    // urls 支持 string 或数组两种形态（RTCIceServer.urls 的合法形状）
                    val urls: List<String> = when (val u = item["urls"]) {
                        is JsonPrimitive -> listOf(u.content)
                        is JsonArray -> u.mapNotNull { (it as? JsonPrimitive)?.content }
                        else -> emptyList()
                    }
                    if (urls.isEmpty()) null
                    else IceServerCfg(
                        urls,
                        (item["username"] as? JsonPrimitive)?.content,
                        (item["credential"] as? JsonPrimitive)?.content
                    )
                }
                val enabled = vo.enabled
                configMutex.withLock { config = enabled to servers }
                _state.value = _state.value.copy(enabled = enabled, iceServers = servers)
            } catch (e: Exception) {
                Log.w(TAG, "call config 拉取失败: ${e.message}") // 留空，下次重试
            }
        }
        configLoading?.join()
        return config
    }

    /* ------------------------------ 主叫：拨出 ------------------------------ */

    /**
     * 发起通话。mediaPermissionGranted 由 UI 在请求 RECORD_AUDIO/CAMERA 后传入，
     * 这里只管状态机与信令（对齐 dialCall）。
     */
    fun dial(userId: String, mediaType: String, conversationId: String?, peerName: String) {
        if (_state.value.status != Status.IDLE) return
        val type = if (mediaType == "video") "video" else "audio"
        val id = UUID.randomUUID().toString()
        AppLog.i("CALL", "拨号 userId=$userId media=$type callId=$id")
        startedAtTs = 0
        wasConnected = false
        _state.value = CallUiState(
            status = Status.DIALING,
            role = "caller",
            callId = id,
            mediaType = type,
            peerUserId = userId,
            peerName = peerName,
            conversationId = conversationId,
            hint = "正在呼叫…",
            enabled = _state.value.enabled,
            iceServers = _state.value.iceServers
        )
        scope.launch {
            val cfg = ensureConfig()
            if (cfg == null || !cfg.first) {
                endLocally("通话功能已关闭")
                return@launch
            }
            val ok = runCatching { buildEngine(cfg.second, type) }
            if (ok.isFailure) {
                endLocally("无法获取麦克风/摄像头")
                return@launch
            }
            setSpeaker(if (type == "video") true else false)
            val sent = ws.send(
                "call",
                buildJsonObject {
                    put("action", "invite")
                    put("callId", id)
                    put("calleeUserId", userId)
                    put("mediaType", type)
                }
            )
            if (!sent) endLocally("连接未就绪，稍后再试")
        }
    }

    /* ------------------------------ 被叫：接听/拒绝 ------------------------------ */

    fun accept() {
        val s = _state.value
        if (s.status != Status.RINGING) return
        scope.launch {
            // 与 dial 同口径走 ensureConfig：invite 到达时那次拉取若恰逢网络抖动失败，
            // 直接读缓存会把「可重试的null」当成「已关闭」，点接听永远变成拒接
            val cfg = ensureConfig()
            if (cfg == null || !cfg.first) {
                signal("reject")
                teardown(finalize = false)
                return@launch
            }
            val ok = runCatching { buildEngine(cfg.second, s.mediaType) }
            if (ok.isFailure) {
                hint("无法获取麦克风/摄像头")
                signal("reject")
                teardown(finalize = false)
                return@launch
            }
            wasConnected = true
            startedAtTs = System.currentTimeMillis()
            setSpeaker(if (s.mediaType == "video") true else false)
            _state.value = _state.value.copy(status = Status.ACTIVE, hint = "已接听，等待对方连接…")
            signal("accept")
        }
    }

    fun reject() {
        if (_state.value.status != Status.RINGING) return
        signal("reject")
        teardown(finalize = false)
    }

    /** 主叫在对方接听前取消 */
    fun cancel() {
        if (_state.value.status == Status.DIALING) {
            signal("cancel", buildJsonObject { put("reason", "caller-cancel") })
        }
        teardown(finalize = wasConnected)
    }

    /** 挂断（通话中） */
    fun hangup() {
        signal("hangup", buildJsonObject { put("reason", "user-hangup") })
        teardown(finalize = wasConnected)
    }

    /* ------------------------------ 控制动作 ------------------------------ */

    fun toggleMute() {
        val next = !_state.value.muted
        engine?.setMuted(next)
        _state.value = _state.value.copy(muted = next)
    }

    fun toggleCamera() {
        if (_state.value.mediaType != "video") return
        val next = !_state.value.cameraOff
        engine?.setCameraEnabled(!next)
        _state.value = _state.value.copy(cameraOff = next)
    }

    fun toggleSpeaker() {
        setSpeaker(!_state.value.speaker)
    }

    @SuppressLint("Deprecated")
    private fun setSpeaker(on: Boolean) {
        runCatching {
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            @Suppress("DEPRECATION")
            audioManager.isSpeakerphoneOn = on
        }
        _state.value = _state.value.copy(speaker = on)
    }

    private fun restoreAudio() {
        runCatching {
            @Suppress("DEPRECATION")
            audioManager.isSpeakerphoneOn = false
            audioManager.mode = AudioManager.MODE_NORMAL
        }
    }

    /* ------------------------------ 引擎与回调 ------------------------------ */

    private suspend fun buildEngine(iceServers: List<IceServerCfg>, mediaType: String) {
        engine = CallEngine(
            context,
            iceServers,
            mediaType,
            onLocalSignal = { action, data ->
                ws.send("call", buildJsonObject {
                    put("action", action)
                    put("callId", _state.value.callId)
                    data.forEach { (k, v) -> put(k, v) }
                })
            },
            onLocalStream = { stream -> _state.value = _state.value.copy(localStream = stream) },
            onRemoteStream = { stream -> _state.value = _state.value.copy(remoteStream = stream) },
            onConnectionState = { st ->
                Log.d(TAG, "pc connectionState $st")
                when (st) {
                    "connected" -> {
                        failJob?.cancel()
                        hint("")
                        startTimer()
                    }
                    "failed" -> reportIceFailureAndArmHangup()
                }
            }
        ).also { it.initMedia() }
    }

    private fun reportIceFailureAndArmHangup() {
        hint("P2P 连接失败，正在尝试恢复…")
        failJob?.cancel()
        failJob = scope.launch {
            delay(FAIL_GRACE_MS)
            // 5 秒内没救回来（connectionState 未回到 connected）就主动挂断并通知对端
            hint("通话无法建立，已挂断")
            signal("hangup", buildJsonObject { put("reason", "user-hangup") })
            teardown(finalize = wasConnected)
        }
    }

    /* ------------------------------ 计时 ------------------------------ */

    private fun startTimer() {
        if (tickJob?.isActive == true || startedAtTs == 0L) return
        _state.value = _state.value.copy(seconds = ((System.currentTimeMillis() - startedAtTs) / 1000).toInt())
        tickJob = scope.launch {
            while (true) {
                delay(1000)
                _state.value = _state.value.copy(seconds = ((System.currentTimeMillis() - startedAtTs) / 1000).toInt())
            }
        }
    }

    private fun hint(text: String) {
        _state.value = _state.value.copy(hint = text)
    }

    /* ------------------------------ 下行信令（WsDispatcher 转交） ------------------------------ */

    fun onSignal(data: JsonObject?) {
        if (data == null) return
        val action = data["action"]?.jsonPrimitive?.content ?: return
        val s = _state.value
        if (action != "ice") AppLog.i("CALL", "信令 action=$action callId=${data["callId"]?.jsonPrimitive?.content}")

        // invite 是唯一「本机空闲时到达」的信令：置为被叫振铃
        if (action == "invite") {
            if (s.status != Status.IDLE) {
                AppLog.w("CALL", "收到来电但本机非空闲(${s.status})，已忽略")
                return
            }
            val fromId = data["fromUserId"]?.jsonPrimitive?.content
            val friend = friends.friends.value.firstOrNull { it.friendId == fromId }
            val name = when {
                friend == null -> "对方"
                friend.displayName.isNotEmpty() -> friend.displayName
                friend.nickname.isNotEmpty() -> friend.nickname
                else -> "对方"
            }
            val callId = data["callId"]?.jsonPrimitive?.content ?: ""
            val media = if (data["mediaType"]?.jsonPrimitive?.content == "video") "video" else "audio"
            AppLog.i("CALL", "收到来电 callId=$callId from=$fromId media=$media")
            // 先振铃再拉 ICE 配置：配置请求慢/失败都不能拖住来电界面
            _state.value = CallUiState(
                status = Status.RINGING,
                role = "callee",
                callId = callId,
                mediaType = media,
                peerUserId = fromId,
                peerName = name,
                hint = "邀请你通话",
                enabled = s.enabled,
                iceServers = s.iceServers
            )
            scope.launch { ensureConfig() }
            return
        }

        // 其余信令必须属于当前这通电话，否则丢弃（迟到的/别的通话的）
        val packetCallId = data["callId"]?.jsonPrimitive?.content
        if (!packetCallId.isNullOrEmpty() && s.callId.isNotEmpty() && packetCallId != s.callId) return

        when (action) {
            "accept" -> scope.launch {
                if (s.role == "caller") {
                    wasConnected = true
                    startedAtTs = System.currentTimeMillis()
                    _state.value = _state.value.copy(status = Status.ACTIVE, hint = "对方已接听，正在连接…")
                    runCatching { engine?.createOffer() }
                        .onFailure { hint("协商失败") }
                }
            }
            "offer" -> scope.launch { runCatching { engine?.handleDescription("offer", data["sdp"]?.jsonPrimitive?.content ?: "") } }
            "answer" -> scope.launch { runCatching { engine?.handleDescription("answer", data["sdp"]?.jsonPrimitive?.content ?: "") } }
            "ice" -> {
                val cand = data["candidate"] as? JsonObject ?: return
                engine?.handleCandidate(
                    cand["candidate"]?.jsonPrimitive?.content ?: return,
                    cand["sdpMid"]?.jsonPrimitive?.content,
                    cand["sdpMLineIndex"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
                )
            }
            "screen" -> _state.value = _state.value.copy(remoteSharing = data["sharing"]?.jsonPrimitive?.content == "true")
            "reject" -> if (s.role == "caller") {
                hint("对方拒绝了通话")
                teardown(finalize = false)
            }
            "cancel" -> if (s.role == "callee") {
                hint("对方已取消")
                teardown(finalize = false)
            }
            "hangup" -> {
                hint(if (data["reason"]?.jsonPrimitive?.content == "max-duration") "通话已达时长上限" else "")
                teardown(finalize = wasConnected)
            }
            "canceled" -> if (s.role == "callee" && s.status == Status.RINGING) {
                hint(if (data["reason"]?.jsonPrimitive?.content == "timeout") "对方无应答" else "已在他端接听")
                teardown(finalize = false)
            }
            "failed" -> {
                hint(failureText(data["reason"]?.jsonPrimitive?.content))
                teardown(finalize = false)
            }
        }
    }

    private fun failureText(reason: String?) = when (reason) {
        "offline" -> "对方不在线"
        "busy" -> "对方正忙"
        "self-busy" -> "你已在通话中"
        "not-friend" -> "需为好友才能通话"
        "timeout" -> "对方无应答"
        "gone" -> "通话已结束"
        "forbidden" -> "无权操作该通话"
        else -> "呼叫失败"
    }

    /* ------------------------------ 收尾 ------------------------------ */

    private fun signal(action: String, extra: JsonObject? = null) {
        val callId = _state.value.callId
        if (callId.isEmpty()) return
        val data = buildJsonObject {
            put("action", action)
            put("callId", callId)
            extra?.forEach { (k, v) -> put(k, v) }
        }
        if (!ws.send("call", data)) AppLog.w("CALL", "上行失败 action=$action（连接不可用）")
    }

    /** 本地异常收尾（不发信令） */
    private fun endLocally(hintText: String) {
        hint(hintText)
        teardown(finalize = false)
    }

    /** 结束通话，清理资源；主叫且接通过才落 msgType=6 通话记录（对齐 teardown） */
    fun teardown(finalize: Boolean) {
        val s = _state.value
        val duration = s.seconds
        val recordRole = s.role
        val recordConv = s.conversationId
        val recordMediaType = s.mediaType
        val recordCallId = s.callId

        tickJob?.cancel(); tickJob = null
        failJob?.cancel(); failJob = null
        engine?.close()
        engine = null
        restoreAudio()

        _state.value = s.copy(
            localStream = null,
            remoteStream = null,
            muted = false,
            cameraOff = false,
            status = Status.ENDED
        )
        scope.launch {
            if (finalize && wasConnected && recordRole == "caller" && recordConv != null && recordCallId != finalizedCall) {
                finalizedCall = recordCallId
                runCatching {
                    messages.send(
                        recordConv,
                        6,
                        "",
                        MessageExtra(
                            callMediaType = recordMediaType,
                            duration = duration
                        )
                    )
                }
            }
            delay(800) // 停留 ended 让浮层收尾动画播完
            if (_state.value.status == Status.ENDED) {
                _state.value = CallUiState(enabled = _state.value.enabled, iceServers = _state.value.iceServers)
            }
        }
    }
}

/** /call/config 响应体（CallConfigVO） */
@kotlinx.serialization.Serializable
data class CallConfigNet(
    val enabled: Boolean = false,
    val iceServers: List<JsonObject>? = null,
    val groupEnabled: Boolean = false,
    val groupMaxSlots: Int = 0
)
