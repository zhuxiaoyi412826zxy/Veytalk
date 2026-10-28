package com.im.nativeapp.ui.chat

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.im.nativeapp.core.call.CallManager
import com.im.nativeapp.core.config.SessionStore
import com.im.nativeapp.core.media.ImageOcr
import com.im.nativeapp.core.media.VoicePlayer
import com.im.nativeapp.core.media.VoiceRecorder
import com.im.nativeapp.core.network.ApiClient
import com.im.nativeapp.core.network.ApiError
import com.im.nativeapp.core.network.FileApi
import com.im.nativeapp.core.network.MessageVO
import com.im.nativeapp.core.network.unwrap
import com.im.nativeapp.data.AuthRepository
import com.im.nativeapp.data.ConversationRepository
import com.im.nativeapp.data.MessageRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.decodeFromJsonElement
import javax.inject.Inject

@HiltViewModel
class ChatViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    savedStateHandle: SavedStateHandle,
    private val conversations: ConversationRepository,
    private val messages: MessageRepository,
    private val session: SessionStore,
    private val apiClient: ApiClient,
    private val callManager: CallManager,
    private val imageOcr: ImageOcr,
    private val auth: AuthRepository,
    val recorder: VoiceRecorder,
    private val voicePlayer: VoicePlayer
) : ViewModel() {

    val conversationId: String = checkNotNull(savedStateHandle["conversationId"])

    /** 正在播放的语音消息 ID，气泡据此高亮播放按钮 */
    val voicePlayingId = voicePlayer.playingId

    fun playVoice(messageId: String, url: String) = voicePlayer.play(messageId, url)

    /** 发起 1:1 通话（单聊才有入口）：对端用户 ID 取会话的 targetId */
    fun dialCall(mediaType: String) {
        val conv = conversation.value ?: return
        val peer = conv.targetId ?: return
        callManager.dial(peer, mediaType, conversationId, conv.name ?: "对方")
    }

    /** 会话资料跟随仓库列表实时更新（在线状态/未读变化会反映到顶栏） */
    val conversation: StateFlow<com.im.nativeapp.core.network.ConversationVO?> =
        conversations.list
            .map { list -> list.find { it.conversationId == conversationId } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val messageList: StateFlow<List<MessageVO>> = messages.messagesOf(conversationId)
    val hasMore: StateFlow<Boolean> = messages.hasMoreOf(conversationId)

    private val _toast = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val toast: SharedFlow<String> = _toast

    /** 上传中的附件占位提示（简单 toast 即可，上传链路失败由气泡标红呈现） */
    private val _sendingAttachment = MutableStateFlow(false)
    val sendingAttachment: StateFlow<Boolean> = _sendingAttachment

    init {
        conversations.setActive(conversationId)
        viewModelScope.launch {
            // 本地优先渲染，再拉服务端最新一页合并（对齐 chat.js loadHistory(reset)）
            runCatching { messages.loadHistory(conversationId, reset = true) }
            // 进会话即已读：用当前最大 seq 上报（对齐 ChatWindow 的 markRead 时机）
            val maxSeq = messageList.value.mapNotNull { it.seq?.toLongOrNull() }.maxOrNull()?.toString()
            conversations.markRead(conversationId, maxSeq)
            if (maxSeq != null) runCatching { messages.reportRead(conversationId, maxSeq) }
        }
    }

    override fun onCleared() {
        conversations.setActive(null)
        voicePlayer.stop()
        recorder.cancel()
    }

    fun resolveUrl(path: String?): String? = session.resolveUrl(path)

    /**
     * 下载文件消息：换带短时票据的直链交给系统下载管理器。
     * DownloadManager 不能自定义鉴权头，走 /file/{id}/url 票据直链（对齐 Web 端 resolveDirectDownloadUrl）。
     */
    fun downloadFile(msg: MessageVO) {
        val fileId = msg.extra?.fileId ?: msg.content
        if (fileId.isNullOrBlank()) return
        val fileName = msg.extra?.fileName ?: "file-${fileId}"
        viewModelScope.launch {
            try {
                val url: String = apiClient.service(FileApi::class.java)
                    .signedUrl(fileId)
                    .unwrap(apiClient.json)
                val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as android.app.DownloadManager
                val request = android.app.DownloadManager.Request(Uri.parse(url))
                    .setTitle(fileName)
                    .setDescription("IM 文件下载")
                    .setNotificationVisibility(android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    .setDestinationInExternalPublicDir(
                        android.os.Environment.DIRECTORY_DOWNLOADS,
                        fileName
                    )
                dm.enqueue(request)
                _toast.tryEmit("已开始下载：$fileName")
            } catch (e: Exception) {
                _toast.tryEmit(if (e is ApiError) e.message ?: "下载失败" else "下载失败：${e.message ?: ""}")
            }
        }
    }

    fun loadMore() {
        viewModelScope.launch { runCatching { messages.loadHistory(conversationId, reset = false) } }
    }

    // ------------------------------ 引用回复（对齐 ChatWindow replyTarget） ------------------------------

    /** 正在回复的消息：不为空时输入框上方展示回复横幅，发送后清空 */
    private val _replyTarget = MutableStateFlow<MessageVO?>(null)
    val replyTarget: StateFlow<MessageVO?> = _replyTarget

    fun setReply(msg: MessageVO) { _replyTarget.value = msg }
    fun cancelReply() { _replyTarget.value = null }

    /** 引用块摘要的唯一口径（对齐 ChatWindow.quoteSummaryText）：附件 content 是文件 ID，必须转占位文案 */
    fun quoteSummaryText(msg: MessageVO?): String {
        if (msg == null) return ""
        if (msg.recalled == true) return "[引用内容已撤回]"
        return when (msg.msgType) {
            2 -> if (msg.extra?.flash == true) "[闪照]" else "[图片]"
            3 -> "[文件]"
            4 -> "[语音]"
            6 -> "[通话]"
            else -> msg.content ?: ""
        }
    }

    /** 本地即时拼一份 QuotePreview（与服务端同形），回包来了服务端权威内容覆盖（对齐 buildQuotePreview） */
    private fun buildQuotePreview(msg: MessageVO): kotlinx.serialization.json.JsonObject =
        kotlinx.serialization.json.buildJsonObject {
            msg.messageId?.let { put("messageId", kotlinx.serialization.json.JsonPrimitive(it)) }
            msg.fromUserId?.let { put("fromUserId", kotlinx.serialization.json.JsonPrimitive(it)) }
            msg.fromNickname?.let { put("fromNickname", kotlinx.serialization.json.JsonPrimitive(it)) }
            put("msgType", kotlinx.serialization.json.JsonPrimitive(msg.msgType))
            put("content", kotlinx.serialization.json.JsonPrimitive(quoteSummaryText(msg)))
            put("recalled", kotlinx.serialization.json.JsonPrimitive(msg.recalled == true))
        }

    // ------------------------------ 群成员与 @ 提及（对齐 parseMentions） ------------------------------

    /** 群成员列表：仅群聊加载，@ 选人与提及解析共用 */
    private val _members = MutableStateFlow<List<com.im.nativeapp.core.network.GroupMemberVO>>(emptyList())
    val members: StateFlow<List<com.im.nativeapp.core.network.GroupMemberVO>> = _members

    private var membersLoaded = false

    /** 本人用户 ID：@ 选人面板与提及解析都要排除自己（对齐 mentionMembers 的 sameId 过滤） */
    val myUserId: String get() = auth.currentUser.value.id

    /** 群聊才拉成员（type=2 时 targetId 是 groupId） */
    fun loadMembersIfGroup() {
        val conv = conversation.value ?: return
        if (conv.type != 2 || membersLoaded) return
        val gid = conv.targetId ?: return
        membersLoaded = true
        viewModelScope.launch {
            runCatching {
                val envelope = apiClient.service(com.im.nativeapp.core.network.GroupApi::class.java)
                    .members(gid, current = 1, size = 200)
                if (envelope.code == 200) {
                    val obj = envelope.data as? kotlinx.serialization.json.JsonObject
                    val records = obj?.get("records") as? kotlinx.serialization.json.JsonArray
                    _members.value = records?.mapNotNull {
                        runCatching {
                            apiClient.json.decodeFromJsonElement<com.im.nativeapp.core.network.GroupMemberVO>(it)
                        }.getOrNull()
                    } ?: emptyList()
                }
            }
        }
    }

    /** 解析草稿中的 @提及（对齐 parseMentions）：@所有人→atAll；匹配成员展示名→atUserIds；匹配不到保留原文 */
    private fun parseMentions(text: String): Pair<Boolean, List<String>> {
        var atAll = false
        val atUserIds = mutableListOf<String>()
        val myId = auth.currentUser.value.id
        for (match in Regex("@(\\S+)").findAll(text)) {
            val name = match.groupValues[1]
            if (name == "所有人") { atAll = true; continue }
            val found = _members.value.find { m ->
                (m.nicknameInGroup ?: m.displayName ?: m.nickname ?: "") == name
            }
            if (found?.userId != null && found.userId != myId) atUserIds.add(found.userId)
        }
        return atAll to atUserIds
    }

    fun sendText(text: String) {
        if (text.isBlank()) return
        val reply = _replyTarget.value
        _replyTarget.value = null
        // 群聊解析 @；单聊没有提及语义
        val (atAll, atUserIds) = if (conversation.value?.type == 2) parseMentions(text) else (false to emptyList())
        viewModelScope.launch {
            messages.sendText(
                conversationId, text.trim(),
                quoteMsgId = reply?.messageId,
                quote = reply?.let { buildQuotePreview(it) },
                atAll = if (atAll) true else null,
                atUserIds = atUserIds.ifEmpty { null }
            )
                .onFailure { e -> _toast.tryEmit(e.message ?: "发送失败") }
        }
    }

    fun sendImage(uri: Uri) {
        viewModelScope.launch {
            _sendingAttachment.value = true
            try {
                // width/height 服务端不回填，客户端自己读（对齐 message.js 的注释，否则气泡缩略图跳动）
                val size = readImageSize(uri)
                // 发送端本地 OCR（对齐 Web 端 ChatWindow：识别结果随消息上传，服务端过敏感词后存 extra.ocrText）。
                // 任何异常返回 null 照常发送，不阻断主流程。
                val ocrText = imageOcr.recognize(uri)
                messages.uploadFile(uri, "chat_image")
                    .onSuccess { file ->
                        messages.sendAttachment(conversationId, 2, file, width = size?.first, height = size?.second, ocrText = ocrText)
                            .onFailure { e -> _toast.tryEmit(e.message ?: "发送失败") }
                    }
                    .onFailure { e -> _toast.tryEmit("图片上传失败：${e.message ?: ""}") }
            } finally {
                _sendingAttachment.value = false
            }
        }
    }

    fun sendFile(uri: Uri) {
        viewModelScope.launch {
            _sendingAttachment.value = true
            try {
                messages.uploadFile(uri, "chat_file")
                    .onSuccess { file ->
                        messages.sendAttachment(conversationId, 3, file)
                            .onFailure { e -> _toast.tryEmit(e.message ?: "发送失败") }
                    }
                    .onFailure { e -> _toast.tryEmit("文件上传失败：${e.message ?: ""}") }
            } finally {
                _sendingAttachment.value = false
            }
        }
    }

    fun sendVoice(path: String, duration: Int) {
        viewModelScope.launch {
            _sendingAttachment.value = true
            try {
                messages.uploadVoiceFile(path, duration)
                    .onSuccess { file ->
                        messages.sendAttachment(conversationId, 4, file, duration = duration)
                            .onFailure { e -> _toast.tryEmit(e.message ?: "发送失败") }
                        runCatching { java.io.File(path).delete() }
                    }
                    .onFailure { e -> _toast.tryEmit("语音上传失败：${e.message ?: ""}") }
            } finally {
                _sendingAttachment.value = false
            }
        }
    }

    private fun readImageSize(uri: Uri): Pair<Int, Int>? = runCatching {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        if (opts.outWidth > 0 && opts.outHeight > 0) opts.outWidth to opts.outHeight else null
    }.getOrNull()

    fun recall(message: MessageVO) {
        val mid = message.messageId ?: return
        viewModelScope.launch {
            messages.recall(conversationId, mid)
                .onFailure { e -> _toast.tryEmit(e.message ?: "撤回失败") }
        }
    }

    fun resend(message: MessageVO) {
        viewModelScope.launch {
            messages.resend(conversationId, message)
                .onFailure { e -> _toast.tryEmit(e.message ?: "重发失败") }
        }
    }

    fun discard(message: MessageVO) {
        message.clientMsgId?.let { messages.discard(conversationId, it) }
    }

    fun deleteMessage(message: MessageVO) {
        val mid = message.messageId ?: return
        viewModelScope.launch {
            messages.deleteLocal(conversationId, mid)
                .onFailure { e -> _toast.tryEmit(e.message ?: "删除失败") }
        }
    }
}
