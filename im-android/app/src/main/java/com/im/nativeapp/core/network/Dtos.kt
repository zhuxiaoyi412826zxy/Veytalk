package com.im.nativeapp.core.network

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * ID 兼容序列化器。
 *
 * 后端 JacksonConfig$SafeLongSerializer 的行为：雪花 ID（19 位，超 JS 安全整数）
 * 序列化成字符串，而 seq / fileSize 这类小 Long 仍是数字。也就是说同一个 Long 字段，
 * JSON 里既可能是 "123" 也可能是 123。这里统一按字符串收（序列化方向后端两者都认，
 * Jackson 对 Long 字段同时接受字符串与数字），与 Web 端 asId/sameId 的口径一致。
 */
object IdAsString : KSerializer<String> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("IdAsString", PrimitiveKind.STRING)
    override fun deserialize(decoder: Decoder): String {
        val el = (decoder as JsonDecoder).decodeJsonElement()
        return if (el is JsonPrimitive) el.content else el.toString()
    }
    override fun serialize(encoder: Encoder, value: String) = encoder.encodeString(value)
}

/** 可空 ID 版本：null 不经过本序列化器，只处理 number/string 两种形态 */
object IdAsStringNullable : KSerializer<String?> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("IdAsStringNullable", PrimitiveKind.STRING)
    override fun deserialize(decoder: Decoder): String? {
        val el = (decoder as JsonDecoder).decodeJsonElement()
        return if (el is JsonPrimitive) el.content else el.toString()
    }
    override fun serialize(encoder: Encoder, value: String?) {
        if (value == null) encoder.encodeNull() else encoder.encodeString(value)
    }
}

// ============================== 会话（conversation.js 事实源） ==============================

/** 对齐 ConversationVO.java；时间字段是 ISO_LOCAL_DATE_TIME 字符串，不解析直接展示时格式化 */
@Serializable
data class ConversationVO(
    @Serializable(IdAsStringNullable::class) val conversationId: String? = null,
    val type: Int = 1,                       // 1 单聊 2 群聊
    val typeDesc: String? = null,
    @Serializable(IdAsStringNullable::class) val targetId: String? = null,
    val name: String = "",
    val avatar: String? = null,
    val online: Boolean? = null,
    val remark: String? = null,
    val unreadCount: Int = 0,
    val atFlag: Boolean? = null,
    @Serializable(IdAsStringNullable::class) val lastMsgId: String? = null,
    val lastMsgContent: String? = null,
    val lastMsgType: Int? = null,
    val lastMsgTime: String? = null,
    val top: Boolean? = null,
    val topTime: String? = null,
    val muted: Boolean? = null,
    val hidden: Boolean? = null,
    @Serializable(IdAsStringNullable::class) val lastAckSeq: String? = null,
    val memberCount: Int? = null,
    val myRole: Int? = null
)

@Serializable
data class SingleConversationRequest(val targetUserId: String)

@Serializable
data class ConvReadRequest(val conversationId: String, val maxSeq: String? = null)

@Serializable
data class FlagRequest(val enabled: Boolean)

// ============================== 消息（message.js + MessageVO/MessageDTO 事实源） ==============================

/** MessageExtra 只声明聊天主链路用到的字段，其余（投票/接龙/红包…）由 Json 的 ignoreUnknownKeys 放过 */
@Serializable
data class MessageExtra(
    @Serializable(IdAsStringNullable::class) val fileId: String? = null,
    val fileName: String? = null,
    val fileUrl: String? = null,
    val fileSize: Long? = null,
    val contentType: String? = null,
    val ext: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val duration: Int? = null,
    val atAll: Boolean? = null,
    val atUserIds: List<String>? = null,
    val action: String? = null,
    val edited: Boolean? = null,
    val flash: Boolean? = null,
    val flashViewed: Boolean? = null,
    // 通话记录消息（msgType 6）专用：audio / video
    val callMediaType: String? = null,
    val callScreenRecorded: Boolean? = null,
    /** 发送端本地 OCR 提取的图内文字（服务端过敏感词后存储），仅图片消息可能有 */
    val ocrText: String? = null
)

/**
 * 消息视图：HTTP 的 MessageVO 与 WS 的 MessageDTO 归一成同一形状（对齐 chat.js normalizeMessage）。
 * DTO 比 VO 少 self / readCount / statusDesc / msgTypeDesc / reactions 等，一律可空兜底。
 */
@Serializable
data class MessageVO(
    @Serializable(IdAsStringNullable::class) val messageId: String? = null,
    val clientMsgId: String? = null,
    @Serializable(IdAsStringNullable::class) val conversationId: String? = null,
    val conversationType: Int? = null,
    @Serializable(IdAsStringNullable::class) val fromUserId: String? = null,
    val fromNickname: String? = null,
    val fromAvatar: String? = null,
    val msgType: Int = 1,
    val msgTypeDesc: String? = null,
    val content: String? = null,
    val extra: MessageExtra? = null,
    val quote: JsonObject? = null,
    @Serializable(IdAsStringNullable::class) val seq: String? = null,
    val status: Int? = null,
    val statusDesc: String? = null,
    val recalled: Boolean? = null,
    val recallTime: String? = null,
    val sendTime: String? = null,
    val self: Boolean? = null,
    val readCount: Int? = null,
    val reactions: List<ReactionItem>? = null,
    val essence: Boolean? = null,
    val favorite: Boolean? = null
)

@Serializable
data class ReactionItem(
    val emoji: String = "",
    val count: Int = 0,
    val self: Boolean = false
)

/** 发送消息请求体（对齐 message.js sendMessage 的 data） */
@Serializable
data class SendMessageRequest(
    val clientMsgId: String,
    val msgType: Int,
    val content: String,
    val conversationId: String? = null,
    val toUserId: String? = null,
    val toGroupId: String? = null,
    val extra: MessageExtra? = null,
    val quoteMsgId: String? = null,
    val atAll: Boolean? = null,
    val atUserIds: List<String>? = null
)

@Serializable
data class DeliveredRequest(val messageIds: List<String>)

// ============================== 好友（friend.js 事实源） ==============================

@Serializable
data class FriendVO(
    @Serializable(IdAsStringNullable::class) val friendId: String? = null,
    val username: String = "",
    val nickname: String = "",
    val displayName: String = "",
    val avatar: String? = null,
    val gender: Int? = null,
    val signature: String? = null,
    val online: Boolean? = null,
    val remark: String? = null,
    val groupName: String? = null,
    val status: Int? = null,
    val blocked: Boolean? = null
)

@Serializable
data class FriendRequestVO(
    @Serializable(IdAsStringNullable::class) val id: String? = null,
    @Serializable(IdAsStringNullable::class) val fromUserId: String? = null,
    @Serializable(IdAsStringNullable::class) val toUserId: String? = null,
    val direction: String? = null,
    @Serializable(IdAsStringNullable::class) val peerUserId: String? = null,
    val peerUsername: String? = null,
    val peerNickname: String? = null,
    val peerAvatar: String? = null,
    val peerOnline: Boolean? = null,
    val verifyMessage: String? = null,
    val source: String? = null,
    val status: Int = 0,                     // 0 待处理 1 已同意 2 已拒绝 3 已过期
    val statusDesc: String? = null,
    val actionable: Boolean? = null,
    val createTime: String? = null
)

@Serializable
data class FriendApplyRequest(
    val targetUserId: String? = null,
    val targetAccount: String? = null,
    val verifyMessage: String? = null,
    val source: String? = null
)

// ============================== 群组（group.js 事实源） ==============================

@Serializable
data class GroupVO(
    @Serializable(IdAsStringNullable::class) val groupId: String? = null,
    val name: String = "",
    val avatar: String? = null,
    val notice: String? = null,
    @Serializable(IdAsStringNullable::class) val ownerId: String? = null,
    val ownerNickname: String? = null,
    val maxMember: Int? = null,
    val memberCount: Int? = null,
    val muteAll: Boolean? = null,
    val myRole: Int? = null,                 // 1 群主 2 管理员 3 成员
    val myRoleDesc: String? = null,
    val myNickname: String? = null,
    val myMuted: Boolean? = null,
    val myManager: Boolean? = null,
    val joined: Boolean? = null,
    @Serializable(IdAsStringNullable::class) val conversationId: String? = null,
    val joinApproval: Boolean? = null,
    val inviteCode: String? = null
)

@Serializable
data class GroupCreateRequest(
    val name: String,
    val notice: String? = null,
    val memberIds: List<String>? = null
)

@Serializable
data class GroupJoinRequest(val inviteCode: String, val verifyMessage: String? = null)

/** 群成员列表项（事实源：im-group GroupMemberVO.java） */
@Serializable
data class GroupMemberVO(
    @Serializable(IdAsStringNullable::class) val userId: String? = null,
    val username: String? = null,
    val nickname: String? = null,
    /** 展示名：群内昵称 > 账号昵称 > 账号 > 兜底文案（后端已拼好，直接用） */
    val displayName: String? = null,
    val avatar: String? = null,
    val online: Boolean? = null,
    /** 1 群主 2 管理员 3 成员 */
    val role: Int? = null,
    val roleDesc: String? = null,
    val nicknameInGroup: String? = null,
    val muted: Boolean? = null
)

/** 修改群资料：传 null 不改，传空串清空（对齐 group.js updateGroup） */
@Serializable
data class GroupUpdateRequest(
    val name: String? = null,
    val notice: String? = null,
    val avatar: String? = null
)

@Serializable
data class GroupAddMembersRequest(val userIds: List<String>)

@Serializable
data class GroupMyNicknameRequest(val nicknameInGroup: String)

// ============================== 文件（file.js + FileVO 事实源） ==============================

@Serializable
data class FileVO(
    @Serializable(IdAsStringNullable::class) val fileId: String? = null,
    val bizType: String? = null,
    val originalName: String? = null,
    /** 长期受控地址 /api/file/download/{fileId}，渲染需带登录头 */
    val url: String? = null,
    val signedUrl: String? = null,
    val size: Long? = null,
    val sizeText: String? = null,
    val contentType: String? = null,
    val ext: String? = null,
    val duration: Int? = null,
    val image: Boolean? = null
)

// ============================== 直播（live.js + LiveRoomVO 事实源） ==============================

/** 门禁信息：none 公开 / password 密码房 / paid 付费房 */
@Serializable
data class LiveGateVO(
    val type: String? = null,
    val price: Long? = null
)

/** 直播间视图。playUrl/danmakuWs 是已签名地址，进房现取现用，不能长期缓存 */
@Serializable
data class LiveRoomVO(
    @Serializable(IdAsStringNullable::class) val id: String? = null,
    @Serializable(IdAsStringNullable::class) val anchorId: String? = null,
    val anchorName: String? = null,
    val anchorAvatar: String? = null,
    val title: String? = null,
    val cover: String? = null,
    val notice: String? = null,
    val sourceType: String? = null,
    val resolution: String? = null,
    val bitrateKbps: Int? = null,
    val status: Int? = null,
    val onlineCount: Int? = null,
    val peakOnline: Int? = null,
    val viewerTotal: Int? = null,
    val startTime: String? = null,
    val endTime: String? = null,
    val playUrl: String? = null,
    val danmakuWs: String? = null,
    val hasReplay: Boolean? = null,
    val mine: Boolean? = null,
    val gate: LiveGateVO? = null,
    val unlocked: Boolean? = null
)

/** 回放现签地址返回体 */
@Serializable
data class LiveReplayVO(
    val playUrl: String? = null,
    val durationSeconds: Long? = null,
    val segmentCount: Int? = null,
    val bytes: Long? = null,
    val reportAt: Long? = null
)

// ============================== 短视频信息流（live.js fetchVideoPage/createVideo 事实源） ==============================

/** 短视频信息流卡片（对齐 Live.vue 视频板块渲染字段：title/coverUrl/fileUrl/duration/playCount/uploaderName/mine） */
@Serializable
data class VideoVO(
    @Serializable(IdAsStringNullable::class) val id: String? = null,
    @Serializable(IdAsStringNullable::class) val uploaderId: String? = null,
    val uploaderName: String? = null,
    val uploaderAvatar: String? = null,
    val title: String? = null,
    val coverUrl: String? = null,
    val fileUrl: String? = null,
    val duration: Int? = null,
    val width: Int? = null,
    val height: Int? = null,
    val size: Long? = null,
    val ext: String? = null,
    val playCount: Int? = null,
    val mine: Boolean? = null,
    val createTime: String? = null
)

/** 发布视频请求（对齐 live.js createVideo 的 body：先传文件拿 fileId，再建信息流记录） */
@Serializable
data class VideoCreateRequest(
    val title: String,
    val fileId: String,
    val coverFileId: String? = null,
    val duration: Int? = null,
    val width: Int? = null,
    val height: Int? = null,
    val size: Long? = null,
    val ext: String? = null,
    val contentType: String? = null
)

/**
 * 手机端只观看不开播（定案「本地软件开播、浏览器与 App 观看」），
 * LiveStartRequest/LiveStartVO 已随开播链路一并移除。
 */
