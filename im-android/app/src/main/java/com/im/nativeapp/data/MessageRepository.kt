package com.im.nativeapp.data

import android.content.Context
import android.net.Uri
import android.webkit.MimeTypeMap
import com.im.nativeapp.core.network.ApiClient
import com.im.nativeapp.core.network.ApiError
import com.im.nativeapp.core.network.ConvReadRequest
import com.im.nativeapp.core.network.DeliveredRequest
import com.im.nativeapp.core.network.FileApi
import com.im.nativeapp.core.network.FileVO
import com.im.nativeapp.core.network.MessageApi
import com.im.nativeapp.core.network.MessageExtra
import com.im.nativeapp.core.network.MessageVO
import com.im.nativeapp.core.network.SendMessageRequest
import com.im.nativeapp.core.network.unwrap
import com.im.nativeapp.core.network.unwrapUnit
import com.im.nativeapp.core.storage.ImDatabase
import com.im.nativeapp.core.storage.MessageEntity
import com.im.nativeapp.core.storage.PendingMessageEntity
import com.im.nativeapp.core.ws.ImWebSocket
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 聊天消息仓库（对齐 im-ui/src/stores/chat.js 的语义）：
 * 消息按会话分桶；本地 Room 缓存优先渲染、服务端历史回写；
 * 发送走「本地占位(status=0) → HTTP → 回包/WS ack 去重合并」；断网进 pending 队列重连补发。
 */
@Singleton
class MessageRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val apiClient: ApiClient,
    private val ws: ImWebSocket
) {
    companion object {
        const val PAGE_SIZE = 20
        const val LOCAL_LIMIT = 300
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val db by lazy { ImDatabase.get(context) }
    private val json get() = apiClient.json

    /** { convId: 消息列表 }，切换会话不清空，来回切不用重拉（对齐 chat.js 的分桶） */
    private val buckets = mutableMapOf<String, MutableStateFlow<List<MessageVO>>>()
    private val hasMoreMap = mutableMapOf<String, MutableStateFlow<Boolean>>()
    private val loadingMutex = mutableMapOf<String, Mutex>()

    fun messagesOf(cid: String): StateFlow<List<MessageVO>> =
        buckets.getOrPut(cid) { MutableStateFlow(emptyList()) }

    fun hasMoreOf(cid: String): StateFlow<Boolean> =
        hasMoreMap.getOrPut(cid) { MutableStateFlow(true) }

    // ------------------------------ 本地 <-> 网络 模型转换 ------------------------------

    private fun toEntity(m: MessageVO): MessageEntity = MessageEntity(
        messageId = m.messageId,
        clientMsgId = m.clientMsgId,
        conversationId = m.conversationId ?: "",
        fromUserId = m.fromUserId,
        fromNickname = m.fromNickname,
        fromAvatar = m.fromAvatar,
        msgType = m.msgType,
        content = m.content,
        extraJson = m.extra?.let { json.encodeToString(MessageExtra.serializer(), it) },
        seqSort = m.seq?.toLongOrNull() ?: Long.MAX_VALUE,
        status = m.status ?: 1,
        recalled = m.recalled == true || m.status == 4,
        sendTime = m.sendTime,
        self = m.self == true,
        readCount = m.readCount,
        quoteJson = m.quote?.toString()
    )

    private fun fromEntity(e: MessageEntity): MessageVO = MessageVO(
        messageId = e.messageId,
        clientMsgId = e.clientMsgId,
        conversationId = e.conversationId,
        fromUserId = e.fromUserId,
        fromNickname = e.fromNickname,
        fromAvatar = e.fromAvatar,
        msgType = e.msgType,
        content = e.content,
        extra = e.extraJson?.let { runCatching { json.decodeFromString(MessageExtra.serializer(), it) }.getOrNull() },
        quote = e.quoteJson?.let { runCatching { json.parseToJsonElement(it).jsonObject }.getOrNull() },
        seq = if (e.seqSort == Long.MAX_VALUE) null else e.seqSort.toString(),
        status = e.status,
        recalled = e.recalled,
        sendTime = e.sendTime,
        self = e.self,
        readCount = e.readCount
    )

    private fun cache(cid: String, list: List<MessageVO>) {
        val items = list.mapNotNull { m -> if (m.conversationId != null) toEntity(m) else null }
        if (items.isEmpty()) return
        scope.launch { runCatching { db.messageDao().upsertAll(items) } }
    }

    // ------------------------------ 去重 / 排序（对齐 chat.js） ------------------------------

    private fun seqOf(m: MessageVO): Long? = m.seq?.toLongOrNull()

    private fun compareMessages(a: MessageVO, b: MessageVO): Int {
        val l = seqOf(a); val r = seqOf(b)
        if (l != null && r != null) return l.compareTo(r)
        if (l == null && r == null) return (a.sendTime ?: "").compareTo(b.sendTime ?: "")
        return if (l == null) 1 else -1
    }

    private fun findIndex(list: List<MessageVO>, m: MessageVO): Int {
        val serverId = m.messageId
        val clientId = m.clientMsgId
        return list.indexOfFirst { item ->
            if (!serverId.isNullOrEmpty() && item.messageId == serverId) return@indexOfFirst true
            // 只在对方也还没有 messageId 时用 clientMsgId 匹配，否则已落库消息会被后到的占位覆盖回「发送中」
            item.messageId == null && !clientId.isNullOrEmpty() && item.clientMsgId == clientId
        }
    }

    /**
     * 追加/合并一条消息。HTTP 回包与 WS ack 是同一条消息的两个来源，谁先到不确定；
     * 离线补拉也会重复给，去重是必需的。
     */
    fun appendMessage(cid: String, raw: MessageVO, fallbackSelf: Boolean = false): MessageVO? {
        if (raw.conversationId == null) return null
        val m = raw.copy(
            self = raw.self ?: fallbackSelf,
            status = raw.status ?: 1,
            recalled = raw.recalled == true || raw.status == 4
        )
        val flow = buckets.getOrPut(cid) { MutableStateFlow(emptyList()) }
        val list = flow.value.toMutableList()
        val idx = findIndex(list, m)
        if (idx >= 0) {
            // 已有本地占位：就地补全为服务端权威数据
            list[idx] = m
        } else {
            list.add(m)
        }
        list.sortWith(::compareMessages)
        flow.value = list
        cache(cid, listOf(m))
        return m
    }

    // ------------------------------ 历史分页 ------------------------------

    /** reset=true 进会话：先渲染本地最近一屏，再拉服务端最新一页合并回写 */
    suspend fun loadHistory(cid: String, reset: Boolean) {
        val mutex = loadingMutex.getOrPut(cid) { Mutex() }
        if (!mutex.tryLock()) return
        try {
            val flow = buckets.getOrPut(cid) { MutableStateFlow(emptyList()) }
            if (reset) {
                if (flow.value.isEmpty()) {
                    val local = db.messageDao().loadRecentDesc(cid, LOCAL_LIMIT).map(::fromEntity).sortedWith(::compareMessages)
                    if (local.isNotEmpty()) flow.value = local
                }
                val page: List<MessageVO> = apiClient.service(MessageApi::class.java)
                    .history(cid, null, PAGE_SIZE).unwrap(json)
                mergePage(cid, page)
                hasMoreOf(cid).let { (hasMoreMap.getOrPut(cid) { MutableStateFlow(true) }).value = page.size >= PAGE_SIZE }
            } else {
                val current = flow.value
                val beforeSeq = current.firstOrNull()?.let { seqOf(it) } ?: run {
                    hasMoreMap.getOrPut(cid) { MutableStateFlow(true) }.value = false
                    return
                }
                val localPage = db.messageDao().loadBeforeDesc(cid, beforeSeq, PAGE_SIZE)
                    .map(::fromEntity).sortedWith(::compareMessages)
                if (localPage.size >= PAGE_SIZE) {
                    flow.value = (localPage + current).distinctByKey().sortedWith(::compareMessages)
                } else {
                    val page: List<MessageVO> = apiClient.service(MessageApi::class.java)
                        .history(cid, beforeSeq.toString(), PAGE_SIZE).unwrap(json)
                    mergePage(cid, page, prepend = true)
                    hasMoreMap.getOrPut(cid) { MutableStateFlow(true) }.value = page.size >= PAGE_SIZE
                }
            }
        } finally {
            mutex.unlock()
        }
    }

    private fun List<MessageVO>.distinctByKey(): List<MessageVO> {
        val seenServer = mutableSetOf<String>()
        val seenClient = mutableSetOf<String>()
        return filter { m ->
            val sid = m.messageId
            val cidOnly = m.clientMsgId
            when {
                !sid.isNullOrEmpty() -> seenServer.add(sid)
                !cidOnly.isNullOrEmpty() -> seenClient.add(cidOnly)
                else -> true
            }
        }
    }

    private fun mergePage(cid: String, page: List<MessageVO>, prepend: Boolean = false) {
        val flow = buckets.getOrPut(cid) { MutableStateFlow(emptyList()) }
        val merged = if (prepend) page + flow.value else flow.value + page
        flow.value = merged.distinctByKey().sortedWith(::compareMessages)
        cache(cid, page)
    }

    // ------------------------------ 发送 ------------------------------

    fun newClientMsgId(): String =
        "c-${System.currentTimeMillis().toString(36)}-${(1..8).map { "0123456789abcdefghijklmnopqrstuvwxyz".random() }.joinToString("")}"

    /**
     * 发送一条消息（HTTP 通道）：先占位(status=0)再发，失败标 status=5 可重发；
     * 网络层失败（非业务拒绝）进 pending 队列，恢复后 flushPending 幂等重提交。
     * quote/quoteMsgId/atAll/atUserIds 对齐 chat.js send：占位阶段就带上 quote，
     * 气泡上的引用块只认 message.quote，不等回包。
     */
    suspend fun send(
        cid: String,
        msgType: Int,
        content: String,
        extra: MessageExtra? = null,
        clientMsgId: String? = null,
        quoteMsgId: String? = null,
        quote: kotlinx.serialization.json.JsonObject? = null,
        atAll: Boolean? = null,
        atUserIds: List<String>? = null
    ): Result<MessageVO> {
        val id = clientMsgId ?: newClientMsgId()
        appendMessage(
            cid,
            MessageVO(
                clientMsgId = id,
                conversationId = cid,
                msgType = msgType,
                content = content,
                extra = extra,
                quote = quote,
                seq = null,
                status = 0,
                recalled = false,
                sendTime = nowIso()
            ),
            fallbackSelf = true
        )
        return try {
            val vo: MessageVO = apiClient.service(MessageApi::class.java)
                .send(SendMessageRequest(
                    clientMsgId = id, msgType = msgType, content = content, conversationId = cid,
                    extra = extra, quoteMsgId = quoteMsgId, atAll = atAll, atUserIds = atUserIds
                ))
                .unwrap(json)
            appendMessage(cid, vo, fallbackSelf = true)
            Result.success(vo)
        } catch (e: Exception) {
            markFailed(cid, id)
            if (e !is ApiError) {
                // 传输层失败才有重试价值：敏感词/限流这类业务拒绝重试一万次也不会成功（对齐 chat.js isNetworkError）
                runCatching {
                    db.pendingDao().enqueue(
                        PendingMessageEntity(
                            clientMsgId = id,
                            conversationId = cid,
                            msgType = msgType,
                            content = content,
                            extraJson = extra?.let { json.encodeToString(MessageExtra.serializer(), it) },
                            quoteMsgId = quoteMsgId,
                            atAll = atAll == true,
                            atUserIdsJson = atUserIds?.let { ids -> ids.joinToString(",", "[", "]") { "\"$it\"" } }
                        )
                    )
                }
            }
            Result.failure(e)
        }
    }

    suspend fun sendText(
        cid: String,
        text: String,
        quoteMsgId: String? = null,
        quote: kotlinx.serialization.json.JsonObject? = null,
        atAll: Boolean? = null,
        atUserIds: List<String>? = null
    ): Result<MessageVO> = send(cid, 1, text, quoteMsgId = quoteMsgId, quote = quote, atAll = atAll, atUserIds = atUserIds)

    /** 附件消息：content 放文件 ID，服务端按文件记录回填元数据；width/height 服务端不回填要自己传 */
    suspend fun sendAttachment(cid: String, msgType: Int, file: FileVO, width: Int? = null, height: Int? = null, duration: Int? = null, ocrText: String? = null): Result<MessageVO> {
        val fileId = file.fileId ?: return Result.failure(ApiError(-1, "上传未返回文件 ID"))
        val extra = MessageExtra(
            fileId = fileId,
            fileName = file.originalName,
            fileUrl = file.url,
            fileSize = file.size,
            contentType = file.contentType,
            ext = file.ext,
            width = width,
            height = height,
            duration = duration ?: file.duration,
            ocrText = ocrText
        )
        return send(cid, msgType, fileId, extra)
    }

    /** 上传文件（multipart 字段名必须是 file，对齐 file.js 的教训） */
    suspend fun uploadFile(uri: Uri, bizType: String, duration: Int? = null): Result<FileVO> {
        return try {
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: return Result.failure(ApiError(-1, "无法读取所选文件"))
            val name = queryDisplayName(uri) ?: "file"
            val mime = context.contentResolver.getType(uri)
                ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', ""))
                ?: "application/octet-stream"
            val part = MultipartBody.Part.createFormData("file", name, bytes.toRequestBody(mime.toMediaTypeOrNull()))
            val vo: FileVO = apiClient.service(FileApi::class.java).upload(part, bizType, duration).unwrap(json)
            Result.success(vo)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** 上传本地录音文件（MediaRecorder 产物在 app 私有目录，无 Uri 权限问题） */
    suspend fun uploadVoiceFile(path: String, durationSeconds: Int): Result<FileVO> {
        return try {
            val f = java.io.File(path)
            val bytes = f.readBytes()
            val part = MultipartBody.Part.createFormData("file", f.name, bytes.toRequestBody("audio/mp4".toMediaTypeOrNull()))
            val vo: FileVO = apiClient.service(FileApi::class.java).upload(part, "chat_voice", durationSeconds).unwrap(json)
            Result.success(vo)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun queryDisplayName(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
        }
    }.getOrNull()

    private fun markFailed(cid: String, clientMsgId: String) {
        val flow = buckets[cid] ?: return
        flow.value = flow.value.map { m ->
            if (m.clientMsgId == clientMsgId && m.messageId == null) m.copy(status = 5) else m
        }
        flow.value.find { it.clientMsgId == clientMsgId }?.let { cache(cid, listOf(it)) }
    }

    /** 重发失败消息：复用原 clientMsgId，后端幂等直接返回首次结果；引用关系从占位 quote 还原 */
    suspend fun resend(cid: String, message: MessageVO): Result<MessageVO> {
        val id = message.clientMsgId ?: return Result.failure(ApiError(-1, "本地消息缺少 clientMsgId"))
        val flow = buckets[cid] ?: return Result.failure(ApiError(-1, "会话不存在"))
        flow.value = flow.value.map { m -> if (m.clientMsgId == id) m.copy(status = 0) else m }
        val quoteMsgId = message.quote?.get("messageId")?.jsonPrimitive?.contentOrNull
        return try {
            val vo: MessageVO = apiClient.service(MessageApi::class.java)
                .send(SendMessageRequest(
                    clientMsgId = id, msgType = message.msgType, content = message.content ?: "",
                    conversationId = cid, extra = message.extra, quoteMsgId = quoteMsgId,
                    atAll = message.extra?.atAll, atUserIds = message.extra?.atUserIds
                ))
                .unwrap(json)
            appendMessage(cid, vo, fallbackSelf = true)
            runCatching { db.pendingDao().remove(id) }
            Result.success(vo)
        } catch (e: Exception) {
            markFailed(cid, id)
            Result.failure(e)
        }
    }

    /** 丢弃发送失败的本地占位 */
    fun discard(cid: String, clientMsgId: String) {
        val flow = buckets[cid] ?: return
        flow.value = flow.value.filter { it.clientMsgId != clientMsgId }
        scope.launch { runCatching { db.messageDao().byClientMsgId(cid, clientMsgId)?.let { db.messageDao().deleteByRowid(it.rowid) } } }
    }

    /** 重连/上线后重提交离线队列（按入队顺序；网络错中断，业务错移出队列） */
    suspend fun flushPending() {
        val list = db.pendingDao().list()
        for (p in list) {
            try {
                val extra = p.extraJson?.let { runCatching { json.decodeFromString(MessageExtra.serializer(), it) }.getOrNull() }
                val atUserIds = p.atUserIdsJson?.let {
                    runCatching {
                        (json.parseToJsonElement(it) as? kotlinx.serialization.json.JsonArray)
                            ?.map { e -> e.jsonPrimitive.content }
                    }.getOrNull()
                }
                val vo: MessageVO = apiClient.service(MessageApi::class.java)
                    .send(SendMessageRequest(
                        clientMsgId = p.clientMsgId, msgType = p.msgType, content = p.content,
                        conversationId = p.conversationId, extra = extra, quoteMsgId = p.quoteMsgId,
                        atAll = if (p.atAll) true else null, atUserIds = atUserIds
                    ))
                    .unwrap(json)
                appendMessage(p.conversationId, vo, fallbackSelf = true)
                db.pendingDao().remove(p.clientMsgId)
            } catch (e: ApiError) {
                db.pendingDao().remove(p.clientMsgId)
            } catch (e: Exception) {
                break // 网络还没好，下一跳再说
            }
        }
    }

    /** 撤回（HTTP 通道，对齐 chat.js：失败原因需要明确回给调用方，不走 WS 上行） */
    suspend fun recall(cid: String, messageId: String): Result<Unit> {
        return try {
            apiClient.service(MessageApi::class.java).recall(messageId).unwrapUnit()
            applyRecall(cid, messageId, "")
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** 单端删除：只对自己不可见 */
    suspend fun deleteLocal(cid: String, messageId: String): Result<Unit> {
        return try {
            apiClient.service(MessageApi::class.java).delete(messageId).unwrapUnit()
            buckets[cid]?.let { flow -> flow.value = flow.value.filter { it.messageId != messageId } }
            runCatching { db.messageDao().deleteByMessageId(messageId) }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ------------------------------ WS 下行应用 ------------------------------

    fun applyIncoming(dto: MessageVO) {
        dto.conversationId?.let { appendMessage(it, dto, fallbackSelf = false) }
    }

    /** ack 推给发送者全部设备；本地没有该 clientMsgId 说明是另一台设备发的，同样当新消息追加 */
    fun applyAck(clientMsgId: String?, dto: MessageVO) {
        val cid = dto.conversationId ?: return
        appendMessage(cid, dto.copy(clientMsgId = dto.clientMsgId ?: clientMsgId), fallbackSelf = true)
    }

    fun applyRecall(cid: String, messageId: String, summary: String) {
        val flow = buckets[cid] ?: return
        flow.value = flow.value.map { m ->
            if (m.messageId == messageId) m.copy(recalled = true, status = 4) else m
        }
        flow.value.find { it.messageId == messageId }?.let { cache(cid, listOf(it)) }
    }

    /** 送达/已读回执：只在自己发出的消息上升级状态，且状态只能往前走（回执顺序不保证） */
    fun applyReceipt(messageIds: List<String>, level: Int) {
        buckets.forEach { (cid, flow) ->
            val changed = mutableListOf<MessageVO>()
            flow.value = flow.value.map { m ->
                if (m.self == true && m.recalled != true && m.messageId != null && messageIds.contains(m.messageId) && (m.status ?: 1) < level) {
                    m.copy(status = level).also { changed.add(it) }
                } else m
            }
            if (changed.isNotEmpty()) cache(cid, changed)
        }
    }

    /** 离线消息：登录与重连后各拉一次；后端返回同时推进位点但不清未读 */
    suspend fun pullOffline(): List<MessageVO> {
        val list: List<MessageVO> = apiClient.service(MessageApi::class.java).offline().unwrap(json)
        list.forEach { dto -> dto.conversationId?.let { appendMessage(it, dto, fallbackSelf = false) } }
        return list
    }

    // ------------------------------ 回执上报（WS 优先，HTTP 兜底，对齐 chat.js） ------------------------------

    suspend fun reportRead(cid: String, maxSeq: String?) {
        val data = kotlinx.serialization.json.buildJsonObject {
            put("conversationId", kotlinx.serialization.json.JsonPrimitive(cid))
            maxSeq?.let { put("maxSeq", kotlinx.serialization.json.JsonPrimitive(it)) }
        }
        if (!ws.send("read", data)) {
            runCatching { apiClient.service(MessageApi::class.java).reportRead(ConvReadRequest(cid, maxSeq)).unwrapUnit() }
        }
    }

    suspend fun reportDelivered(messageIds: List<String>) {
        if (messageIds.isEmpty()) return
        val data = kotlinx.serialization.json.buildJsonObject {
            put("messageIds", kotlinx.serialization.json.JsonArray(messageIds.map { kotlinx.serialization.json.JsonPrimitive(it) }))
        }
        if (!ws.send("delivered", data)) {
            runCatching { apiClient.service(MessageApi::class.java).reportDelivered(DeliveredRequest(messageIds)).unwrapUnit() }
        }
    }

    private fun nowIso(): String {
        val f = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
        return f.format(java.util.Date())
    }
}
