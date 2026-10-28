package com.im.nativeapp.core.network

import okhttp3.MultipartBody
import okhttp3.ResponseBody
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Part
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * 业务 REST 接口清单（事实源：im-ui/src/api/{conversation,message,friend,group,file}.js）。
 * 相对路径不带前导斜杠，baseUrl 固定以 /api/ 结尾。
 */

interface UserApi {
    /** 更新本人资料（对齐 im-ui/src/api/user.js updateProfile）；只传要改的字段 */
    @PUT("user/profile")
    suspend fun updateProfile(@Body body: Map<String, String>): ApiEnvelope
}

interface ConversationApi {
    @GET("conversation/list")
    suspend fun list(): ApiEnvelope

    @GET("conversation/{id}")
    suspend fun get(@Path("id") id: String): ApiEnvelope

    /** 打开与某人的单聊，幂等：已存在则返回原会话 ID */
    @POST("conversation/single")
    suspend fun createSingle(@Body body: SingleConversationRequest): ApiEnvelope

    @PUT("conversation/{id}/read")
    suspend fun markRead(@Path("id") id: String, @Body body: Map<String, String>): ApiEnvelope

    @PUT("conversation/{id}/top")
    suspend fun setTop(@Path("id") id: String, @Body body: FlagRequest): ApiEnvelope

    @PUT("conversation/{id}/mute")
    suspend fun setMute(@Path("id") id: String, @Body body: FlagRequest): ApiEnvelope

    @DELETE("conversation/{id}")
    suspend fun remove(@Path("id") id: String): ApiEnvelope

    @GET("conversation/unread/total")
    suspend fun unreadTotal(): ApiEnvelope
}

interface MessageApi {
    @POST("message/send")
    suspend fun send(@Body body: SendMessageRequest): ApiEnvelope

    /** 历史消息按 seq 升序；beforeSeq 为空取最新一页，返回少于 size 说明到顶 */
    @GET("message/history")
    suspend fun history(
        @Query("conversationId") conversationId: String,
        @Query("beforeSeq") beforeSeq: String? = null,
        @Query("size") size: Int = 20
    ): ApiEnvelope

    /** 离线消息：拉取后后端自动推进确认位点，但不清未读（未读等打开会话才清） */
    @GET("message/offline")
    suspend fun offline(): ApiEnvelope

    @PUT("message/read")
    suspend fun reportRead(@Body body: ConvReadRequest): ApiEnvelope

    @POST("message/delivered")
    suspend fun reportDelivered(@Body body: DeliveredRequest): ApiEnvelope

    @PUT("message/{id}/recall")
    suspend fun recall(@Path("id") id: String): ApiEnvelope

    /** 单端删除：只对自己不可见 */
    @DELETE("message/{id}")
    suspend fun delete(@Path("id") id: String): ApiEnvelope
}

interface FriendApi {
    @GET("friend/list")
    suspend fun list(@Query("keyword") keyword: String? = null): ApiEnvelope

    @GET("friend/groups")
    suspend fun groups(): ApiEnvelope

    @GET("friend/{friendId}/card")
    suspend fun card(@Path("friendId") friendId: String): ApiEnvelope

    @POST("friend/request/apply")
    suspend fun apply(@Body body: FriendApplyRequest): ApiEnvelope

    @GET("friend/request/received")
    suspend fun received(): ApiEnvelope

    @GET("friend/request/sent")
    suspend fun sent(): ApiEnvelope

    @GET("friend/request/pending-count")
    suspend fun pendingCount(): ApiEnvelope

    /** 同意申请，返回新建的单聊会话 ID */
    @PUT("friend/request/{id}/accept")
    suspend fun accept(@Path("id") id: String): ApiEnvelope

    @PUT("friend/request/{id}/reject")
    suspend fun reject(@Path("id") id: String): ApiEnvelope

    @DELETE("friend/{friendId}")
    suspend fun removeFriend(@Path("friendId") friendId: String): ApiEnvelope
}

interface GroupApi {
    @GET("group/my")
    suspend fun my(): ApiEnvelope

    @GET("group/{id}")
    suspend fun detail(@Path("id") id: String): ApiEnvelope

    /** 创建群：创建者自动成为群主，返回含 conversationId 可直接跳聊天 */
    @POST("group/create")
    suspend fun create(@Body body: GroupCreateRequest): ApiEnvelope

    @GET("group/{id}/members")
    suspend fun members(@Path("id") id: String, @Query("current") current: Int = 1, @Query("size") size: Int = 50): ApiEnvelope

    @POST("group/join")
    suspend fun join(@Body body: GroupJoinRequest): ApiEnvelope

    @POST("group/{id}/quit")
    suspend fun quit(@Path("id") id: String): ApiEnvelope

    /** 修改群资料（群名/公告/头像），群主与管理员可操作 */
    @PUT("group/{id}")
    suspend fun update(@Path("id") id: String, @Body body: GroupUpdateRequest): ApiEnvelope

    /** 邀请成员入群：已在群内的跳过，返回实际新增的 ID 列表 */
    @POST("group/{id}/members")
    suspend fun addMembers(@Path("id") id: String, @Body body: GroupAddMembersRequest): ApiEnvelope

    /** 移除成员：群主可移除任何人，管理员只能移除普通成员 */
    @DELETE("group/{id}/members/{userId}")
    suspend fun removeMember(@Path("id") id: String, @Path("userId") userId: String): ApiEnvelope

    /** 修改我的群昵称：空串清除，回退展示账号昵称 */
    @PUT("group/{id}/my-nickname")
    suspend fun updateMyNickname(@Path("id") id: String, @Body body: GroupMyNicknameRequest): ApiEnvelope
}

interface FileApi {
    /**
     * 上传文件（multipart，字段名必须是 file）。
     * bizType：avatar / chat_image / chat_file / chat_voice；duration 仅语音需要。
     */
    @Multipart
    @POST("file/upload")
    suspend fun upload(
        @Part file: MultipartBody.Part,
        @Query("bizType") bizType: String,
        @Query("duration") duration: Int? = null
    ): ApiEnvelope

    /** 换取带短时票据的直链（DownloadManager 等无法自定义头的下载器用），data 为完整 URL 字符串 */
    @GET("file/{id}/url")
    suspend fun signedUrl(@Path("id") id: String): ApiEnvelope
}

// ============================== 通话（api/call.js + CallConfigVO 事实源） ==============================

interface CallApi {
    /** 通话开关 + ICE 服务器清单（STUN/TURN，带临时凭证）；enabled=false 时整体禁用入口 */
    @GET("call/config")
    suspend fun config(): ApiEnvelope
}

// ============================== 直播（api/live.js + LiveRoomVO 事实源） ==============================

interface LiveApi {
    /** 房间分页：直播中恒排最前，其次按开播时间倒序；status 留空为全部 */
    @GET("live/page")
    suspend fun page(
        @Query("current") current: Int = 1,
        @Query("size") size: Int = 20,
        @Query("status") status: Int? = null
    ): ApiEnvelope

    /** 房间详情（含签名播放地址与弹幕端点，仅解锁且直播中返回） */
    @GET("live/{roomId}")
    suspend fun room(@Path("roomId") roomId: String): ApiEnvelope

    /** 解锁密码直播，返回解锁后的房间详情（带 playUrl） */
    @POST("live/{roomId}/unlock")
    suspend fun unlock(@Path("roomId") roomId: String, @Body body: Map<String, String>): ApiEnvelope

    /** 付费解锁直播；幂等，主播本人恒免 */
    @POST("live/{roomId}/pay")
    suspend fun pay(@Path("roomId") roomId: String): ApiEnvelope

    /** 回放现签地址；null 为没有入口或已过期 */
    @GET("live/{roomId}/replay")
    suspend fun replay(@Path("roomId") roomId: String): ApiEnvelope

    /** 短视频分页（对齐 live.js fetchVideoPage） */
    @GET("live/video/page")
    suspend fun videoPage(
        @Query("current") current: Int = 1,
        @Query("size") size: Int = 24
    ): ApiEnvelope

    /** 发布视频（对齐 live.js createVideo；fileId/coverFileId 来自 file/upload 的 bizType=video / video_cover） */
    @POST("live/video")
    suspend fun createVideo(@Body body: VideoCreateRequest): ApiEnvelope

    /** 删除视频：仅上传者本人可删；记录不存在后端幂等返回成功 */
    @DELETE("live/video/{id}")
    suspend fun deleteVideo(@Path("id") id: String): ApiEnvelope

    /** 播放上报：点开视频时 +1，静默失败不打扰观看 */
    @POST("live/video/{id}/play")
    suspend fun reportVideoPlay(@Path("id") id: String): ApiEnvelope
}

// ============================== AI 面试助手（api/ai.js + InterviewController 事实源） ==============================

interface AiApi {
    /** 面试官状态：知识库目录/文件数/片段数、模型名、Key 是否已配置（前端状态条展示用） */
    @GET("ai/interview/status")
    suspend fun status(): ApiEnvelope

    /** 语音转文字：multipart 上传 16k 单声道 WAV（字段名 file），data={text} */
    @Multipart
    @POST("ai/interview/asr")
    suspend fun asr(@Part file: MultipartBody.Part): ApiEnvelope

    /**
     * 文字合成朗读：成功体是 audio/wav 字节流、失败体是 Result JSON（双形态，按 Content-Type 分流）。
     * 注意不能用 ApiEnvelope 接收——成功时根本不是 JSON。
     */
    @POST("ai/interview/tts")
    suspend fun tts(@Body body: Map<String, String>): ResponseBody
}
