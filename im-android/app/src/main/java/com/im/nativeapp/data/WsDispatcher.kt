package com.im.nativeapp.data

import android.util.Log
import com.im.nativeapp.core.network.ApiClient
import com.im.nativeapp.core.network.MessageVO
import com.im.nativeapp.core.push.MessageNotifier
import com.im.nativeapp.core.ws.ImWebSocket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton

/**
 * WebSocket 下行报文到各仓库的分发（对齐 im-ui/src/ws/dispatch.js）。
 *
 * 事件名与后端 WsMessageType 完全一致，中间不做翻译层。
 * 协议知识（每种 type 的 data 长什么样、该更新哪几个仓库）集中在这一处。
 */
@Singleton
class WsDispatcher @Inject constructor(
    private val ws: ImWebSocket,
    private val apiClient: ApiClient,
    private val conversations: ConversationRepository,
    private val messages: MessageRepository,
    private val friends: FriendRepository,
    private val groups: GroupRepository,
    private val auth: AuthRepository,
    private val calls: com.im.nativeapp.core.call.CallManager,
    private val notifier: MessageNotifier
) {
    companion object { private const val TAG = "WsDispatcher" }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** 被顶下线：UI 收集后弹提示并回登录页 */
    private val _kickout = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val kickout: SharedFlow<String> = _kickout

    /** 首次连接成功后补一次离线消息；之后每次重连成功也补（对齐 dispatch.js 的 open 处理） */
    @Volatile private var everOpened = false

    private var installed = false

    fun install() {
        if (installed) return
        installed = true

        // 连接状态：OPEN 时补发离线队列；重连后补拉离线消息 + 刷会话列表
        scope.launch {
            ws.status.collect { status ->
                if (status == ImWebSocket.Status.OPEN) {
                    messages.flushPending()
                    if (everOpened) {
                        // 重连：后端返回离线消息的同时推进位点但不清未读，拉完要刷会话列表对角标
                        runCatching {
                            val list = messages.pullOffline()
                            if (list.isNotEmpty()) conversations.fetchList() else conversations.refreshTotalUnreadFromList()
                        }
                    } else {
                        everOpened = true
                    }
                }
            }
        }

        scope.launch {
            ws.events.collect { ev -> handle(ev.type, ev.packet) }
        }
    }

    private suspend fun handle(type: String, packet: JsonObject) {
        val data = packet["data"]
        when (type) {
            "message" -> {
                val dto = decode<MessageVO>(data) ?: return
                messages.applyIncoming(dto)
                // 正打开着这个会话时不计未读（否则角标先 +1 再被 markRead 清零，闪一下）
                val viewing = conversations.activeId.value != null && conversations.activeId.value == dto.conversationId
                // @ 了我：与后端 MessageExtra.hasMention 同口径（@全体，或 atUserIds 里有我）
                val me = auth.currentUser.value.id
                val mentioned = dto.extra?.atAll == true || dto.extra?.atUserIds?.any { it == me } == true
                conversations.applyIncoming(dto, viewing, mentioned)
                // 系统通知：前台/正在看/免打扰未@我时内部静默（对齐 Web 端 utils/notify）
                notifier.onIncoming(dto, viewing, mentioned)
                // 送达回执与是否在看无关：消息已经到端了
                dto.messageId?.let { messages.reportDelivered(listOf(it)) }
                if (viewing) {
                    messages.reportRead(dto.conversationId ?: return, dto.seq)
                    conversations.markRead(dto.conversationId ?: return, dto.seq)
                }
            }
            "ack" -> {
                val dto = decode<MessageVO>(data) ?: return
                // clientMsgId 在报文外层而不在 data 里（对齐 dispatch.js 的注释）
                val clientMsgId = packet["clientMsgId"]?.jsonPrimitive?.content
                messages.applyAck(clientMsgId, dto)
                conversations.applyAck(dto)
            }
            "recall-notify" -> {
                val obj = data as? JsonObject ?: return
                val cid = obj["conversationId"]?.jsonPrimitive?.content ?: return
                val mid = obj["messageId"]?.jsonPrimitive?.content ?: return
                val summary = obj["summary"]?.jsonPrimitive?.content ?: ""
                messages.applyRecall(cid, mid, summary)
                conversations.applyRecall(cid, mid, summary)
            }
            "delivered-notify" -> {
                val ids = (data as? JsonObject)?.get("messageIds")
                    ?.let { it as? kotlinx.serialization.json.JsonArray }
                    ?.mapNotNull { it.jsonPrimitive.content }
                    ?: return
                messages.applyReceipt(ids, 2)
            }
            "read-notify" -> {
                val ids = (data as? JsonObject)?.get("messageIds")
                    ?.let { it as? kotlinx.serialization.json.JsonArray }
                    ?.mapNotNull { it.jsonPrimitive.content }
                    ?: return
                messages.applyReceipt(ids, 3)
            }
            "unread" -> {
                val obj = data as? JsonObject ?: return
                val cid = obj["conversationId"]?.jsonPrimitive?.content ?: return
                val count = obj["unreadCount"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
                val total = obj["total"]?.jsonPrimitive?.content?.toIntOrNull()
                conversations.applyUnread(cid, count, total)
            }
            "online-state" -> {
                val obj = data as? JsonObject ?: return
                val userId = obj["userId"]?.jsonPrimitive?.content ?: return
                val online = obj["online"]?.jsonPrimitive?.content == "true"
                conversations.applyOnlineState(userId, online)
                friends.applyOnlineState(userId, online)
            }
            "notify" -> handleNotify(data as? JsonObject)
            "call-signal" -> calls.onSignal(data as? JsonObject)
            "kickout" -> {
                val reason = (data as? JsonObject)?.get("reason")?.jsonPrimitive?.content ?: "账号已在其他设备登录"
                // socket 已置 manualClose 不再重连，这里只负责清态收尾
                auth.teardownLocal()
                resetAll()
                _kickout.tryEmit(reason)
            }
            "error" -> Log.w(TAG, "ws error frame: ${packet["message"]?.jsonPrimitive?.content}")
        }
    }

    /**
     * notify 帧有三种形态（对齐 dispatch.js handleNotify 的现状描述）：
     * 握手欢迎用 action，好友相关用 bizType，群组相关用 biz，必须几种都认。
     */
    private suspend fun handleNotify(data: JsonObject?) {
        if (data == null) return
        if (data["action"]?.jsonPrimitive?.content == "connected") return // 心跳间隔已由 socket 自己消费
        when (data["bizType"]?.jsonPrimitive?.content) {
            "friend-request" -> friends.onFriendRequestNotify()
            "friend-accepted" -> {
                // 对方同意后后端已建好单聊会话，本地列表跟着补上
                friends.refreshPendingCount()
                conversations.fetchList()
            }
        }
        if (data["biz"]?.jsonPrimitive?.content == "group") {
            // 群成员变动/群名修改等：会话列表与群列表一起刷
            conversations.fetchList()
            runCatching { groups.fetchMyGroups() }
        }
    }

    private inline fun <reified T> decode(element: kotlinx.serialization.json.JsonElement?): T? =
        element?.let { runCatching { apiClient.json.decodeFromJsonElement<T>(it) }.getOrNull() }

    private fun resetAll() {
        friends.reset()
        groups.reset()
    }
}
