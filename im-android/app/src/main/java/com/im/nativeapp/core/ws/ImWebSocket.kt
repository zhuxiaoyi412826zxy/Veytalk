package com.im.nativeapp.core.ws

import android.util.Log
import com.im.nativeapp.core.config.SessionStore
import com.im.nativeapp.core.log.AppLog
import com.im.nativeapp.core.network.ApiClient
import com.im.nativeapp.core.network.ApiError
import com.im.nativeapp.core.network.TlsCompat
import com.im.nativeapp.core.network.WsTicketVO
import com.im.nativeapp.core.network.unwrap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min
import kotlin.random.Random

/**
 * /ws 长连客户端（对齐 im-ui/src/ws/socket.js 的协议与节奏）。
 *
 * 协议事实（读 socket.js 得出，勿凭直觉改）：
 * - 握手：先 POST /api/ws/ticket?deviceId=android 拿一次性短票，
 *   再连 {wsBase}{endpoint}?ticket=xxx（浏览器不能自定义头，票据只能挂 query，后端因此签短票）
 * - 心跳：每 heartbeatSeconds 秒发 {"type":"ping"}，服务端回 {"type":"pong"}；
 *   默认 30s，服务端在连接后的 notify(connected) 帧里下发 heartbeatSeconds 覆盖默认值
 * - 死亡判定：两个心跳周期内没等到 pong 就认为链路已死，主动 close 触发重连
 *   （必须短于服务端 heartbeat-timeout-seconds 90s，否则出现「客户端以为连着、服务端已清会话」的空窗）
 * - 顶号：收到 {"type":"kickout"} 后服务端随即关连接，先置 manualClose 避免触发重连
 * - 重连：指数退避 1s,2s,4s… 封顶 30s + 0~500ms 抖动（防后端重启时全客户端同节奏打尖峰）
 */
@Singleton
class ImWebSocket @Inject constructor(
    private val session: SessionStore,
    private val apiClient: ApiClient
) {
    companion object {
        private const val TAG = "ImWebSocket"
        private const val DEFAULT_HEARTBEAT_SECONDS = 30L
        private const val MAX_BACKOFF_MS = 30_000L
    }

    enum class Status { CLOSED, CONNECTING, RECONNECTING, OPEN }

    /** 业务帧回调：type 与完整 JSON 对象（message/notify/receipt 等，M1 起由会话层订阅） */
    data class WsEvent(val type: String, val packet: JsonObject)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val wsClient = TlsCompat.install(
        OkHttpClient.Builder()
            .pingInterval(0, TimeUnit.SECONDS) // 关掉 OkHttp 协议层 ping，用心跳帧与应用层对齐
    ).build()

    private val _status = MutableStateFlow(Status.CLOSED)
    val status: StateFlow<Status> = _status

    private val _events = MutableSharedFlow<WsEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<WsEvent> = _events

    @Volatile private var ws: WebSocket? = null
    @Volatile private var manualClose = false
    @Volatile private var heartbeatSeconds = DEFAULT_HEARTBEAT_SECONDS
    private var heartbeatJob: Job? = null
    private var pongTimeoutJob: Job? = null
    private var reconnectJob: Job? = null
    private var attempts = 0

    /** 建立连接。已连接或正在连接时是空操作，可以安全地在多处调用 */
    fun connect() {
        if (!manualClose && (_status.value == Status.OPEN || _status.value == Status.CONNECTING)) return
        if (!session.isLoggedIn()) return
        manualClose = false
        attempts = 0
        reconnectJob?.cancel()
        reconnectJob = null
        openOnce()
    }

    /** 登出/被顶号时调用：不再重连 */
    fun disconnect() {
        manualClose = true
        reconnectJob?.cancel()
        heartbeatJob?.cancel()
        pongTimeoutJob?.cancel()
        ws?.close(1000, "logout")
        ws = null
        _status.value = Status.CLOSED
    }

    private fun openOnce() {
        if (!session.isLoggedIn()) {
            _status.value = Status.CLOSED
            return
        }
        _status.value = if (attempts == 0) Status.CONNECTING else Status.RECONNECTING

        scope.launch {
            val ticketVo: WsTicketVO = try {
                apiClient.api().wsTicket(SessionStore.DEVICE_ID).unwrap(apiClient.json)
            } catch (e: ApiError) {
                if (ApiError.isAuthError(e.code)) {
                    // 登录态已经没了，重连只是反复撞 401；清凭证回登录页由上层 me() 失败触发
                    manualClose = true
                    _status.value = Status.CLOSED
                    return@launch
                }
                scheduleReconnect()
                return@launch
            } catch (e: Exception) {
                scheduleReconnect()
                return@launch
            }

            if (manualClose || !session.isLoggedIn()) {
                _status.value = Status.CLOSED
                return@launch
            }

            val endpoint = if (ticketVo.endpoint.startsWith("/")) ticketVo.endpoint else "/ws"
            val url = "${session.wsBase()}$endpoint?ticket=${URLEncoder.encode(ticketVo.ticket, "UTF-8")}"
            Log.d(TAG, "ws connect → $url")
            ws = wsClient.newWebSocket(Request.Builder().url(url).build(), listener)
        }
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            attempts = 0
            heartbeatSeconds = DEFAULT_HEARTBEAT_SECONDS
            _status.value = Status.OPEN
            startHeartbeat()
            AppLog.i("WS", "已连接")
            Log.d(TAG, "ws open")
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val packet = try {
                apiClient.json.parseToJsonElement(text).jsonObject
            } catch (e: Exception) {
                return // 非 JSON 帧（代理探活等）直接忽略，对齐 Web 端处理
            }
            val type = packet["type"]?.jsonPrimitive?.content ?: return
            if (type != "pong") {
                // error 帧必须带原文：服务端拒绝上行时的 code/message 全在里面
                if (type == "error") AppLog.w("WS", "↓ error ${text.take(300)}")
                else AppLog.i("WS", "↓ $type")
            }
            when (type) {
                "pong" -> {
                    pongTimeoutJob?.cancel()
                    pongTimeoutJob = null
                }
                "notify" -> {
                    val data = packet["data"] as? JsonObject
                    if (data?.get("action")?.jsonPrimitive?.content == "connected") {
                        // 用心跳帧里的服务端配置覆盖本地默认值，两端节奏始终一致
                        data["heartbeatSeconds"]?.jsonPrimitive?.content?.toLongOrNull()
                            ?.takeIf { it > 0 }
                            ?.let {
                                heartbeatSeconds = it
                                startHeartbeat()
                            }
                    }
                }
                "kickout" -> manualClose = true // 服务端推完这帧就会关连接，避免 onClosed 触发重连
            }
            _events.tryEmit(WsEvent(type, packet))
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(code, reason)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            AppLog.w("WS", "已断开 code=$code reason=$reason")
            Log.d(TAG, "ws closed code=$code reason=$reason manual=$manualClose")
            onSocketDown()
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            AppLog.w("WS", "连接失败 ${t.message}")
            Log.w(TAG, "ws failure: ${t.message}")
            onSocketDown()
        }
    }

    private fun onSocketDown() {
        heartbeatJob?.cancel()
        pongTimeoutJob?.cancel()
        ws = null
        if (manualClose || !session.isLoggedIn()) {
            _status.value = Status.CLOSED
            return
        }
        scheduleReconnect()
    }

    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (true) {
                delay(heartbeatSeconds * 1000)
                val socket = ws ?: break
                if (_status.value != Status.OPEN) break
                // 必须带 timestamp：服务端开了 FAIL_ON_NULL_FOR_PRIMITIVES，
                // WsPacket.timestamp 是 long，缺字段会被判成 null 直接拒帧（1001 报文格式错误）
                send(socket, """{"type":"ping","timestamp":${System.currentTimeMillis()}}""")
                // 两个心跳周期内没等到 pong 就判定链路已死
                pongTimeoutJob?.cancel()
                pongTimeoutJob = scope.launch {
                    delay(heartbeatSeconds * 2000)
                    ws?.close(1000, "pong timeout")
                }
            }
        }
    }

    /** 发送业务帧。返回 false 表示当前不在连接状态 */
    fun send(type: String, data: JsonObject? = null, clientMsgId: String? = null): Boolean {
        val socket = ws ?: return false
        if (_status.value != Status.OPEN) return false
        // 整帧交给 kotlinx.serialization 组装：字符串拼 JSON 时原始串无法以引号结尾
        // （"""...":""" 的尾引号会被并入闭合分隔符），曾产出 {"type":call" 这种非法帧，
        // 后端整条解析失败、只回 error，导致带 data 的上行（call/read/delivered）全部静默丢失
        val packet = buildJsonObject {
            put("type", type)
            put("timestamp", System.currentTimeMillis())
            if (clientMsgId != null) put("clientMsgId", clientMsgId)
            if (data != null) put("data", data)
        }
        // ICE 候选项量太大，不进调试缓冲，其余上行都记一条，便于和服务端 error 帧对齐
        if (data?.get("action")?.jsonPrimitive?.content != "ice") {
            AppLog.i("WS", "↑ ${packet.toString().take(300)}")
        }
        return send(socket, packet.toString())
    }

    private fun send(socket: WebSocket, text: String): Boolean {
        return try {
            socket.send(text)
        } catch (e: Exception) {
            Log.w(TAG, "ws send failed", e)
            false
        }
    }

    private fun scheduleReconnect() {
        if (reconnectJob?.isActive == true) return
        attempts += 1
        // 位移位数钳制在 15（8192s），防止长时间挂机重连时 shl 溢出成负值
        val base = min(MAX_BACKOFF_MS, (1000L shl min(attempts - 1, 15)))
        val jitter = Random.nextLong(0, 500)
        _status.value = Status.RECONNECTING
        reconnectJob = scope.launch {
            delay(base + jitter)
            openOnce()
        }
    }
}
