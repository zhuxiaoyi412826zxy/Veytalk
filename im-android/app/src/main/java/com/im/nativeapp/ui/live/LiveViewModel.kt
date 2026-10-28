package com.im.nativeapp.ui.live

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.im.nativeapp.core.config.SessionStore
import com.im.nativeapp.core.live.LiveDanmakuSocket
import com.im.nativeapp.core.network.ApiClient
import com.im.nativeapp.core.network.ApiError
import com.im.nativeapp.core.network.LiveRoomVO
import com.im.nativeapp.core.network.unwrap
import com.im.nativeapp.core.network.LiveApi
import com.im.nativeapp.core.network.LiveReplayVO
import com.im.nativeapp.core.network.VideoVO
import com.im.nativeapp.core.network.VideoCreateRequest
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import javax.inject.Inject

/**
 * 直播观看（对齐 im-ui/src/api/live.js + Live.vue 的观众链路）。
 * M2 只做「看」：列表、进房、HLS 播放、弹幕收发、点赞、密码/付费解锁；
 * 开播推流/连麦/PK/礼物/回放管理属 M3。
 */
@HiltViewModel
class LiveViewModel @Inject constructor(
    private val apiClient: ApiClient,
    private val session: SessionStore,
    @ApplicationContext private val appContext: android.content.Context
) : ViewModel() {

    /** 弹幕流列表项：统一渲染形状 */
    data class DanmakuItem(
        val key: Long,
        val type: String,           // danmaku/system/notice/ended/error/online/like
        val nickname: String? = null,
        val avatar: String? = null,
        val content: String? = null,
        val count: Int? = null
    )

    private val api get() = apiClient.service(LiveApi::class.java)

    /** 带鉴权拦截器的 OkHttpClient：给 ExoPlayer 数据源用（视频文件是受控接口，无 satoken 头会 403） */
    fun authedHttpClient(): okhttp3.OkHttpClient = apiClient.mediaHttpClient

    private val _list = MutableStateFlow<List<LiveRoomVO>>(emptyList())
    val list: StateFlow<List<LiveRoomVO>> = _list

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading

    /** 当前房间（进房后填充；解锁动作成功后整只替换，playUrl 随之就位） */
    private val _room = MutableStateFlow<LiveRoomVO?>(null)
    val room: StateFlow<LiveRoomVO?> = _room

    private val _danmaku = MutableStateFlow<List<DanmakuItem>>(emptyList())
    val danmaku: StateFlow<List<DanmakuItem>> = _danmaku

    private val _online = MutableStateFlow(0)
    val online: StateFlow<Int> = _online

    private val _ended = MutableStateFlow(false)
    val ended: StateFlow<Boolean> = _ended

    private val _toast = MutableStateFlow<String?>(null)
    val toast: StateFlow<String?> = _toast

    private val danmakuSocket = LiveDanmakuSocket()
    private var seq = 0L
    private var currentRoomId: String? = null

    fun mediaUrl(path: String?): String = session.resolveUrl(path) ?: ""

    /* ------------------------------ 大厅 ------------------------------ */

    fun refreshList() {
        _loading.value = true
        viewModelScope.launch {
            try {
                // PageResult 信封：data.records 才是房间数组（PageResult{records,total,current,size,pages}）
                val envelope = api.page(current = 1, size = 30)
                if (envelope.code != 200) throw ApiError(envelope.code, envelope.message ?: "请求失败")
                val records = (envelope.data as? kotlinx.serialization.json.JsonObject)
                    ?.get("records") as? kotlinx.serialization.json.JsonArray
                val rooms: List<LiveRoomVO> = records
                    ?.mapNotNull { runCatching { apiClient.json.decodeFromJsonElement<LiveRoomVO>(it) }.getOrNull() }
                    ?: emptyList()
                _list.value = rooms
            } catch (e: Exception) {
                _toast.value = if (e is ApiError) e.message else "直播列表加载失败"
            } finally {
                _loading.value = false
            }
        }
    }

    /* ------------------------------ 视频板块（对齐 Live.vue 视频信息流） ------------------------------ */

    private val _videos = MutableStateFlow<List<VideoVO>>(emptyList())
    val videos: StateFlow<List<VideoVO>> = _videos

    private val _videoLoading = MutableStateFlow(false)
    val videoLoading: StateFlow<Boolean> = _videoLoading

    private val _videoTotal = MutableStateFlow(0)
    val videoTotal: StateFlow<Int> = _videoTotal

    private var videoLoaded = false

    /** 首次切到视频板块才拉列表，避免进大厅就多打一次请求 */
    fun loadVideosIfNeeded() {
        if (videoLoaded) return
        refreshVideos()
    }

    fun refreshVideos() {
        _videoLoading.value = true
        viewModelScope.launch {
            try {
                val envelope = api.videoPage(current = 1, size = 24)
                if (envelope.code != 200) throw ApiError(envelope.code, envelope.message ?: "请求失败")
                val obj = envelope.data as? kotlinx.serialization.json.JsonObject
                val records = obj?.get("records") as? kotlinx.serialization.json.JsonArray
                _videos.value = records
                    ?.mapNotNull { runCatching { apiClient.json.decodeFromJsonElement<VideoVO>(it) }.getOrNull() }
                    ?: emptyList()
                _videoTotal.value = obj?.get("total")?.jsonPrimitive?.content?.toIntOrNull() ?: 0
                videoLoaded = true
            } catch (e: Exception) {
                _toast.value = if (e is ApiError) e.message else "视频列表加载失败"
            } finally {
                _videoLoading.value = false
            }
        }
    }

    /** 点开卡片进全屏播放页时上报播放量（静默失败不打扰观看） */
    fun reportPlay(video: VideoVO) {
        val id = video.id ?: return
        _videos.value = _videos.value.map {
            if (it.id == id) it.copy(playCount = (it.playCount ?: 0) + 1) else it
        }
        viewModelScope.launch { runCatching { api.reportVideoPlay(id) } }
    }

    /** 删除自己的视频（mine=true 才有入口），记录不存在后端幂等成功 */
    fun deleteVideo(video: VideoVO) {
        val id = video.id ?: return
        viewModelScope.launch {
            try {
                val envelope = api.deleteVideo(id)
                if (envelope.code != 200) throw ApiError(envelope.code, envelope.message ?: "删除失败")
                _videos.value = _videos.value.filter { it.id != id }
                _videoTotal.value = (_videoTotal.value - 1).coerceAtLeast(0)
                _toast.value = "已删除"
            } catch (e: Exception) {
                _toast.value = if (e is ApiError) e.message else "删除失败"
            }
        }
    }

    /** 上传视频：先传文件拿 fileId（bizType=video / video_cover），再建信息流记录 */
    fun uploadVideo(
        videoUri: android.net.Uri,
        coverUri: android.net.Uri?,
        title: String,
        duration: Int?,
        width: Int?,
        height: Int?,
        onDone: (Boolean) -> Unit
    ) {
        viewModelScope.launch {
            try {
                val context = appContext
                // 元数据 + 自动封面抽帧：不选封面时用第 1 秒帧（OPT CLOSEST_SYNC 取关键帧，速度快不花屏）
                var dur = duration
                var w = width
                var h = height
                var autoCoverBytes: ByteArray? = null
                val retriever = android.media.MediaMetadataRetriever()
                runCatching {
                    retriever.setDataSource(context, videoUri)
                    if (dur == null) {
                        dur = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
                            ?.toLongOrNull()?.let { (it / 1000).toInt() }
                    }
                    var vw = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
                    var vh = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
                    // 竖拍视频元数据宽高未含旋转，90/270 度要交换，否则播放页横竖判断会反
                    val rot = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                    if (rot == 90 || rot == 270) { val t = vw; vw = vh; vh = t }
                    if (w == null) w = vw
                    if (h == null) h = vh
                    if (coverUri == null) {
                        val frame = retriever.getFrameAtTime(1_000_000, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                            ?: retriever.getFrameAtTime(0)
                        if (frame != null) {
                            val baos = java.io.ByteArrayOutputStream()
                            frame.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, baos)
                            autoCoverBytes = baos.toByteArray()
                        }
                    }
                }
                runCatching { retriever.release() }

                // 视频本体
                val vBytes = context.contentResolver.openInputStream(videoUri)?.use { it.readBytes() }
                    ?: throw ApiError(-1, "无法读取所选视频")
                val vName = queryName(videoUri) ?: "video.mp4"
                val vMime = context.contentResolver.getType(videoUri) ?: "video/mp4"
                val vPart = okhttp3.MultipartBody.Part.createFormData(
                    "file", vName,
                    vBytes.toRequestBody(vMime.toMediaTypeOrNull())
                )
                val fileVo: com.im.nativeapp.core.network.FileVO =
                    apiClient.service(com.im.nativeapp.core.network.FileApi::class.java)
                        .upload(vPart, "video", duration).unwrap(apiClient.json)

                // 封面：手动选的优先；没选用自动抽帧（封面失败不阻断发布）
                var coverFileId: String? = null
                if (coverUri != null) {
                    runCatching {
                        val cBytes = context.contentResolver.openInputStream(coverUri)?.use { it.readBytes() }
                        if (cBytes != null) {
                            val cName = queryName(coverUri) ?: "cover.jpg"
                            val cMime = context.contentResolver.getType(coverUri) ?: "image/jpeg"
                            val cPart = okhttp3.MultipartBody.Part.createFormData(
                                "file", cName,
                                cBytes.toRequestBody(cMime.toMediaTypeOrNull())
                            )
                            coverFileId = apiClient.service(com.im.nativeapp.core.network.FileApi::class.java)
                                .upload(cPart, "video_cover", null).unwrap<com.im.nativeapp.core.network.FileVO>(apiClient.json).fileId
                        }
                    }
                } else if (autoCoverBytes != null) {
                    runCatching {
                        val cPart = okhttp3.MultipartBody.Part.createFormData(
                            "file", "cover.jpg",
                            autoCoverBytes!!.toRequestBody("image/jpeg".toMediaTypeOrNull())
                        )
                        coverFileId = apiClient.service(com.im.nativeapp.core.network.FileApi::class.java)
                            .upload(cPart, "video_cover", null).unwrap<com.im.nativeapp.core.network.FileVO>(apiClient.json).fileId
                    }
                }

                val body = VideoCreateRequest(
                    title = title.trim(),
                    fileId = fileVo.fileId ?: throw ApiError(-1, "上传未返回 fileId"),
                    coverFileId = coverFileId,
                    duration = dur,
                    width = w,
                    height = h,
                    size = vBytes.size.toLong(),
                    ext = vName.substringAfterLast('.', ""),
                    contentType = vMime
                )
                val envelope = api.createVideo(body)
                if (envelope.code != 200) throw ApiError(envelope.code, envelope.message ?: "发布失败")
                _toast.value = "已发布"
                videoLoaded = false
                refreshVideos()
                onDone(true)
            } catch (e: Exception) {
                _toast.value = if (e is ApiError) e.message else "上传失败"
                onDone(false)
            }
        }
    }

    private fun queryName(uri: android.net.Uri): String? = runCatching {
        appContext.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
        }
    }.getOrNull()

    /* ------------------------------ 房间 ------------------------------ */

    /** 进房：详情未解锁时不带 playUrl，UI 据此弹解锁框 */
    fun enterRoom(roomId: String) {
        currentRoomId = roomId
        viewModelScope.launch {
            try {
                val vo: LiveRoomVO = api.room(roomId).unwrap(apiClient.json)
                _room.value = vo
                _ended.value = false
                _danmaku.value = emptyList()
                _online.value = vo.onlineCount ?: 0
                vo.danmakuWs?.let { connectDanmaku(it) }
            } catch (e: Exception) {
                _toast.value = if (e is ApiError) e.message else "直播间加载失败"
                _room.value = null
            }
        }
    }

    fun unlock(password: String) {
        val roomId = currentRoomId ?: return
        viewModelScope.launch {
            try {
                val vo: LiveRoomVO = api.unlock(roomId, mapOf("password" to password)).unwrap(apiClient.json)
                _room.value = vo
                vo.danmakuWs?.let { connectDanmaku(it) }
            } catch (e: Exception) {
                _toast.value = if (e is ApiError) e.message else "解锁失败"
            }
        }
    }

    fun pay() {
        val roomId = currentRoomId ?: return
        viewModelScope.launch {
            try {
                val vo: LiveRoomVO = api.pay(roomId).unwrap(apiClient.json)
                _room.value = vo
                vo.danmakuWs?.let { connectDanmaku(it) }
            } catch (e: Exception) {
                _toast.value = if (e is ApiError) e.message else "解锁失败"
            }
        }
    }

    /** 回放现签地址（hasReplay=true 时入口可见） */
    fun fetchReplay(roomId: String, onReady: (String) -> Unit) {
        viewModelScope.launch {
            try {
                val vo: LiveReplayVO = api.replay(roomId).unwrap(apiClient.json)
                vo.playUrl?.let(onReady) ?: run { _toast.value = "回放已过期" }
            } catch (e: Exception) {
                _toast.value = if (e is ApiError) e.message else "回放加载失败"
            }
        }
    }

    fun sendDanmaku(content: String) {
        if (content.isBlank()) return
        danmakuSocket.sendDanmaku(content.trim())
    }

    fun sendLike() {
        danmakuSocket.sendLike(1)
    }

    fun leaveRoom() {
        danmakuSocket.disconnect()
        currentRoomId = null
        _room.value = null
        _danmaku.value = emptyList()
        _online.value = 0
        _ended.value = false
    }

    private fun connectDanmaku(url: String) {
        danmakuSocket.connect(url)
        viewModelScope.launch {
            danmakuSocket.frames.collect { frame -> consumeFrame(frame) }
        }
    }

    private fun consumeFrame(frame: kotlinx.serialization.json.JsonObject) {
        val type = frame["type"]?.jsonPrimitive?.content ?: return
        val content = frame["content"]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
        val count = frame["count"]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull() }
        when (type) {
            "danmaku" -> append(
                DanmakuItem(
                    key = ++seq,
                    type = type,
                    nickname = frame["nickname"]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content },
                    avatar = frame["avatar"]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content },
                    content = content
                )
            )
            "online" -> _online.value = count ?: 0
            "like" -> { /* 点赞广播只更新热度计数，M2 观众侧不展示点赞流 */ }
            "ended" -> {
                _ended.value = true
                append(DanmakuItem(key = ++seq, type = "ended", content = content ?: "直播已结束"))
            }
            "system", "notice", "error" -> append(DanmakuItem(key = ++seq, type = type, content = content))
            else -> {}
        }
    }

    private fun append(item: DanmakuItem) {
        // 只保留最近 100 条，防止长时间停留内存与渲染持续膨胀
        _danmaku.value = (_danmaku.value + item).takeLast(100)
    }

    fun consumeToast() {
        _toast.value = null
    }

    override fun onCleared() {
        danmakuSocket.disconnect()
        super.onCleared()
    }
}
