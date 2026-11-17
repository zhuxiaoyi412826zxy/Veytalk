package com.im.nativeapp.core.live

import android.util.Log
import com.im.nativeapp.core.network.TlsCompat
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
import java.util.concurrent.TimeUnit

/**
 * 直播弹幕 WebSocket 客户端（对齐 im-live LiveDanmakuHandler 的轻量文本帧协议）。
 *
 * - 地址来自 LiveRoomVO.danmakuWs（已带票据/签名，连接即进房，无需额外握手）
 * - 下行帧形如 {type, ...}：danmaku(nickname/avatar/content)、online(count)、
 *   like(count)、system/notice/ended/error(content)、pong(ts)、gift、linkmic、pk
 * - 上行：danmaku(content)、like(count)、ping
 * - 心跳 25s 一次 ping（远小于服务端断连阈值），断开不自动重连——回放/关播由业务层感知 ended 后收口
 */
class LiveDanmakuSocket {

    companion object {
        private const val TAG = "LiveDanmaku"
        private const val HEARTBEAT_MS = 25_000L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = TlsCompat.install(
        OkHttpClient.Builder()
            .pingInterval(0, TimeUnit.SECONDS)
    ).build()

    private val _frames = MutableSharedFlow<JsonObject>(extraBufferCapacity = 64)
    val frames: SharedFlow<JsonObject> = _frames

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected

    @Volatile private var ws: WebSocket? = null
    private var heartbeatJob: Job? = null

    /** 建立连接。地址来自服务端下发的 danmakuWs，直接可用 */
    fun connect(url: String) {
        disconnect()
        val request = Request.Builder().url(url).build()
        ws = client.newWebSocket(request, listener)
    }

    fun sendDanmaku(content: String): Boolean =
        send(buildJsonObject { put("type", "danmaku"); put("content", content) })

    fun sendLike(count: Int = 1): Boolean =
        send(buildJsonObject { put("type", "like"); put("count", count) })

    fun disconnect() {
        heartbeatJob?.cancel()
        heartbeatJob = null
        runCatching { ws?.close(1000, "leave") }
        ws = null
        _connected.value = false
    }

    private fun send(obj: JsonObject): Boolean = try {
        // 补 timestamp：服务端 WsPacket.timestamp 是 long 且开了 FAIL_ON_NULL_FOR_PRIMITIVES，
        // 缺字段会被判成 null → 整帧 1001 拒绝
        val frame = buildJsonObject {
            obj.forEach { (k, v) -> put(k, v) }
            put("timestamp", System.currentTimeMillis())
        }
        ws?.send(frame.toString()) ?: false
    } catch (e: Exception) {
        Log.w(TAG, "danmaku send failed", e)
        false
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            _connected.value = true
            heartbeatJob?.cancel()
            heartbeatJob = scope.launch {
                while (true) {
                    delay(HEARTBEAT_MS)
                    send(buildJsonObject { put("type", "ping") })
                }
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val frame = apiParse(text) ?: return
            _frames.tryEmit(frame)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            _connected.value = false
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.w(TAG, "danmaku ws failure: ${t.message}")
            _connected.value = false
        }
    }

    private fun apiParse(text: String): JsonObject? = try {
        kotlinx.serialization.json.Json.parseToJsonElement(text).jsonObject
    } catch (e: Exception) {
        null
    }
}
