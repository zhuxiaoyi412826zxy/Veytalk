package com.im.nativeapp.data

import android.content.Context
import com.im.nativeapp.core.network.ApiClient
import com.im.nativeapp.core.network.ConversationApi
import com.im.nativeapp.core.network.ConversationVO
import com.im.nativeapp.core.network.FlagRequest
import com.im.nativeapp.core.network.MessageVO
import com.im.nativeapp.core.network.SingleConversationRequest
import com.im.nativeapp.core.network.unwrap
import com.im.nativeapp.core.network.unwrapUnit
import com.im.nativeapp.core.storage.ConversationEntity
import com.im.nativeapp.core.storage.ImDatabase
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 会话列表与未读数（对齐 im-ui/src/stores/conversation.js）。
 *
 * 本地排序必须与后端 selectViewsByUserId 完全一致：
 * is_top DESC, top_time DESC, last_msg_time DESC, id DESC ——
 * 否则 WS 逐条到达的更新与整表刷新的顺序对不上，用户会觉得会话「乱跳」。
 * ISO 时间字符串字典序即时间序，可直接比较。
 */
@Singleton
class ConversationRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val apiClient: ApiClient
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val db by lazy { ImDatabase.get(context) }

    private val _list = MutableStateFlow<List<ConversationVO>>(emptyList())
    val list: StateFlow<List<ConversationVO>> = _list

    /** 全部会话未读之和：取自列表求和（对齐 Web 端「以列表为准」，不另调 unread/total） */
    private val _totalUnread = MutableStateFlow(0)
    val totalUnread: StateFlow<Int> = _totalUnread

    /** 当前打开的会话 ID：WS message 帧据此判定是否「正在看」而不计未读 */
    private val _activeId = MutableStateFlow<String?>(null)
    val activeId: StateFlow<String?> = _activeId

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading

    fun setActive(cid: String?) {
        _activeId.value = cid
    }

    fun find(cid: String?): ConversationVO? =
        _list.value.find { it.conversationId == cid }

    /** 会话列表：先渲染 Room 快照（杀进程重进秒开），再拉服务端整表覆盖并回写 */
    suspend fun fetchList() {
        if (_list.value.isEmpty()) {
            val cached = db.conversationDao().all().map(::fromEntity)
            if (cached.isNotEmpty()) _list.value = sortConversations(cached)
        }
        _loading.value = true
        try {
            val remote: List<ConversationVO> = apiClient.service(ConversationApi::class.java).list().unwrap(apiClient.json)
            _list.value = sortConversations(remote)
            applyTotalUnread(remote.sumOf { it.unreadCount })
            scope.launch { runCatching { db.conversationDao().reset(remote.map(::toEntity)) } }
        } finally {
            _loading.value = false
        }
    }

    fun applyTotalUnread(count: Int) {
        _totalUnread.value = count.coerceAtLeast(0)
    }

    fun refreshTotalUnreadFromList() {
        applyTotalUnread(_list.value.sumOf { it.unreadCount })
    }

    /** 打开与某人的单聊（后端幂等），返回会话 ID */
    suspend fun openWith(targetUserId: String): String {
        val cid: String = apiClient.service(ConversationApi::class.java)
            .createSingle(SingleConversationRequest(targetUserId))
            .unwrap(apiClient.json)
        if (find(cid) == null) fetchList()
        return cid
    }

    /**
     * 新消息到达：后端推送排除发送者本人，所以这里递增未读总是对的。
     * suppressUnread = 正打开着该会话（不计未读，否则角标先 +1 再被清零闪一下）。
     * mentioned = 消息 @ 了我（与后端 MessageExtra.hasMention 同口径）：本地即时亮「有人@我」，
     * 服务端 DB 的 at_flag 是同一时刻置位的，下次拉列表口径一致。
     */
    fun applyIncoming(dto: MessageVO, suppressUnread: Boolean, mentioned: Boolean = false) {
        val cid = dto.conversationId ?: return
        val target = find(cid)
        if (target == null) {
            // 可能是对方刚通过好友申请新建的单聊：拉一次列表比凭空拼可靠
            scope.launch { runCatching { fetchList() } }
            return
        }
        _list.value = sortConversations(
            _list.value.map {
                if (it.conversationId == cid) it.copy(
                    lastMsgId = dto.messageId,
                    lastMsgContent = summarize(dto),
                    lastMsgType = dto.msgType,
                    lastMsgTime = dto.sendTime,
                    unreadCount = if (suppressUnread) it.unreadCount else it.unreadCount + 1,
                    atFlag = it.atFlag == true || mentioned
                ) else it
            }
        )
        refreshTotalUnreadFromList()
    }

    /** 发送回执（ack 推给发送者全部设备）：更新摘要但不加未读 */
    fun applyAck(dto: MessageVO) {
        val cid = dto.conversationId ?: return
        if (find(cid) == null) return
        _list.value = sortConversations(
            _list.value.map {
                if (it.conversationId == cid) it.copy(
                    lastMsgId = dto.messageId,
                    lastMsgContent = summarize(dto),
                    lastMsgType = dto.msgType,
                    lastMsgTime = dto.sendTime
                ) else it
            }
        )
    }

    /** 撤回通知：只有被撤回的正好是最后一条时才改摘要，且不把会话顶到最前 */
    fun applyRecall(cid: String, messageId: String, summary: String) {
        val target = find(cid) ?: return
        if (target.lastMsgId != messageId) return
        _list.value = _list.value.map {
            if (it.conversationId == cid) it.copy(lastMsgContent = summary.ifEmpty { "对方撤回了一条消息" }) else it
        }
    }

    /** 未读数变更推送：data = { conversationId, unreadCount, total } */
    fun applyUnread(cid: String, unreadCount: Int, total: Int?) {
        _list.value = _list.value.map {
            if (it.conversationId == cid) it.copy(unreadCount = unreadCount) else it
        }
        if (total != null) applyTotalUnread(total) else refreshTotalUnreadFromList()
    }

    /** 好友在线状态变更：单聊会话的 targetId 就是对方用户 ID */
    fun applyOnlineState(userId: String, online: Boolean) {
        _list.value = _list.value.map {
            if (it.type == 1 && it.targetId == userId) it.copy(online = online) else it
        }
    }

    /** 标记已读：乐观清零再发请求（对齐 Web 端，失败不回滚，等下次推送纠正） */
    suspend fun markRead(cid: String, lastAckSeq: String?) {
        val target = find(cid) ?: return
        if (target.unreadCount > 0) {
            _list.value = _list.value.map {
                if (it.conversationId == cid) it.copy(unreadCount = 0, atFlag = false) else it
            }
            refreshTotalUnreadFromList()
        }
        if (!lastAckSeq.isNullOrEmpty()) {
            runCatching {
                apiClient.service(ConversationApi::class.java)
                    .markRead(cid, mapOf("lastAckSeq" to lastAckSeq)).unwrapUnit()
            }
        }
    }

    suspend fun toggleTop(cid: String, enabled: Boolean) {
        apiClient.service(ConversationApi::class.java).setTop(cid, FlagRequest(enabled)).unwrapUnit()
        _list.value = sortConversations(
            _list.value.map {
                if (it.conversationId == cid) it.copy(top = enabled, topTime = if (enabled) nowIso() else null) else it
            }
        )
    }

    suspend fun toggleMute(cid: String, enabled: Boolean) {
        apiClient.service(ConversationApi::class.java).setMute(cid, FlagRequest(enabled)).unwrapUnit()
        _list.value = _list.value.map { if (it.conversationId == cid) it.copy(muted = enabled) else it }
    }

    suspend fun remove(cid: String) {
        apiClient.service(ConversationApi::class.java).remove(cid).unwrapUnit()
        _list.value = _list.value.filter { it.conversationId != cid }
        if (_activeId.value == cid) _activeId.value = null
        refreshTotalUnreadFromList()
    }

    // ------------------------------ 排序 / 摘要 / 转换 ------------------------------

    private fun sortConversations(list: List<ConversationVO>): List<ConversationVO> =
        list.sortedWith { a, b ->
            val ta = a.top == true; val tb = b.top == true
            if (ta != tb) return@sortedWith if (ta) -1 else 1
            val topDiff = (b.topTime ?: "").compareTo(a.topTime ?: "")
            if (topDiff != 0) return@sortedWith topDiff
            val msgDiff = (b.lastMsgTime ?: "").compareTo(a.lastMsgTime ?: "")
            if (msgDiff != 0) return@sortedWith msgDiff
            (b.conversationId ?: "").compareTo(a.conversationId ?: "")
        }

    companion object {
        /** 会话列表摘要（对齐 conversation.js summarize）：附件消息 content 是文件 ID，必须转占位文案 */
        fun summarize(dto: MessageVO): String = when (dto.msgType) {
            2 -> "[图片]"
            4 -> "[语音]"
            3 -> dto.extra?.fileName?.let { "[文件] $it" } ?: "[文件]"
            5 -> dto.content ?: "[系统通知]"
            else -> dto.content ?: ""
        }
    }

    private fun toEntity(c: ConversationVO): ConversationEntity {
        val cid = c.conversationId ?: return ConversationEntity(conversationId = "")
        return ConversationEntity(
            conversationId = cid,
            type = c.type,
            targetId = c.targetId,
            name = c.name,
            avatar = c.avatar,
            online = c.online == true,
            unreadCount = c.unreadCount,
            atFlag = c.atFlag == true,
            lastMsgId = c.lastMsgId,
            lastMsgContent = c.lastMsgContent,
            lastMsgType = c.lastMsgType,
            lastMsgTime = c.lastMsgTime,
            top = c.top == true,
            topTime = c.topTime,
            muted = c.muted == true,
            lastAckSeq = c.lastAckSeq,
            memberCount = c.memberCount,
            myRole = c.myRole
        )
    }

    private fun fromEntity(e: ConversationEntity): ConversationVO = ConversationVO(
        conversationId = e.conversationId,
        type = e.type,
        targetId = e.targetId,
        name = e.name,
        avatar = e.avatar,
        online = e.online,
        unreadCount = e.unreadCount,
        atFlag = e.atFlag,
        lastMsgId = e.lastMsgId,
        lastMsgContent = e.lastMsgContent,
        lastMsgType = e.lastMsgType,
        lastMsgTime = e.lastMsgTime,
        top = e.top,
        topTime = e.topTime,
        muted = e.muted,
        lastAckSeq = e.lastAckSeq,
        memberCount = e.memberCount,
        myRole = e.myRole
    )

    private fun nowIso(): String {
        val f = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
        return f.format(java.util.Date())
    }
}
