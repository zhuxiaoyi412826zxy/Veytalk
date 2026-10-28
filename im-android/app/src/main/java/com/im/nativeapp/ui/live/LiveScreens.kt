package com.im.nativeapp.ui.live

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import androidx.media3.common.MediaItem
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.im.nativeapp.core.network.LiveRoomVO
import com.im.nativeapp.core.network.VideoVO
import com.im.nativeapp.ui.components.Avatar

/**
 * 直播大厅：直播|视频 双板块（对齐 Web 端 /live）。
 * 手机端只观看不开播（定案「本地软件开播、浏览器与 App 观看」），因此没有开播按钮；
 * 视频板块与 Web 一致：网格 + 内联播放 + 上传 + 删自己的视频。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveTab(
    onOpenRoom: (String) -> Unit,
    onOpenVideoPlayer: (VideoVO) -> Unit = {},
    vm: LiveViewModel = hiltViewModel()
) {
    val list by vm.list.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val toast by vm.toast.collectAsStateWithLifecycle()
    val videos by vm.videos.collectAsStateWithLifecycle()
    val videoLoading by vm.videoLoading.collectAsStateWithLifecycle()
    val videoTotal by vm.videoTotal.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var tab by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(0) }
    // 视频列表滚动位置持久化：导航去播放页再返回时回到原小窗位置
    val videoListState = androidx.compose.foundation.lazy.rememberLazyListState()
    var uploadDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { vm.refreshList() }
    LaunchedEffect(toast) {
        toast?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            vm.consumeToast()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("直播") })
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            // 顶层双板块切换：直播 | 视频（对齐 live-lobby__tabs）
            androidx.compose.material3.TabRow(selectedTabIndex = tab) {
                androidx.compose.material3.Tab(
                    selected = tab == 0,
                    onClick = { tab = 0 },
                    text = { Text("直播") }
                )
                androidx.compose.material3.Tab(
                    selected = tab == 1,
                    onClick = { tab = 1; vm.loadVideosIfNeeded() },
                    text = { Text("视频") }
                )
            }

            if (tab == 0) {
                // ============================== 直播板块 ==============================
                if (loading && list.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                } else if (list.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("暂无直播，去 PC 端开一场吧", color = MaterialTheme.colorScheme.secondary)
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp)
                    ) {
                        items(list, key = { it.id ?: it.hashCode().toString() }) { room ->
                            val roomId = room.id
                            if (roomId != null) {
                                LiveRoomCard(room = room, onClick = { onOpenRoom(roomId) })
                            }
                        }
                    }
                }
            } else {
                // ============================== 视频板块 ==============================
                // 头行：总数 + 上传按钮（对齐 live-video__head）
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "共 $videoTotal 个视频",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.weight(1f)
                    )
                    Button(onClick = { uploadDialog = true }) { Text("上传视频") }
                }

                // 点卡片跳全屏播放页（横版横屏/竖版竖屏沉浸播放）
                if (videoLoading && videos.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                } else if (videos.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("还没有视频，点右上角上传", color = MaterialTheme.colorScheme.secondary)
                    }
                } else {
                    LazyColumn(
                        state = videoListState,
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp)
                    ) {
                        items(videos, key = { it.id ?: it.hashCode().toString() }) { video ->
                            VideoCard(
                                video = video,
                                coverUrl = vm.mediaUrl(video.coverUrl),
                                onClick = { vm.reportPlay(video); onOpenVideoPlayer(video) },
                                onDelete = if (video.mine == true) ({ vm.deleteVideo(video) }) else null
                            )
                        }
                    }
                }
            }
        }
    }

    if (uploadDialog) {
        VideoUploadDialog(
            onDismiss = { uploadDialog = false },
            onConfirm = { vUri, cUri, title, dur, w, h ->
                vm.uploadVideo(vUri, cUri, title, dur, w, h) { ok -> if (ok) uploadDialog = false }
            }
        )
    }
}

@Composable
private fun VideoCard(
    video: VideoVO,
    coverUrl: String,
    onClick: () -> Unit,
    onDelete: (() -> Unit)?
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
    ) {
        Column {
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color(0xFF1B1B1F))) {
                if (coverUrl.isNotEmpty()) {
                    AsyncImage(
                        model = coverUrl,
                        contentDescription = video.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Icon(
                        Icons.Default.Videocam,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.4f),
                        modifier = Modifier.align(Alignment.Center).size(34.dp)
                    )
                }
                Text(
                    "▶ ${video.playCount ?: 0}",
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(8.dp)
                        .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 4.dp)
                )
                video.duration?.let { dur ->
                    val m = dur / 60
                    val s = dur % 60
                    Text(
                        "%d:%02d".format(m, s),
                        color = Color.White,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(8.dp)
                            .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(4.dp))
                            .padding(horizontal = 4.dp)
                    )
                }
                if (onDelete != null) {
                    Text(
                        "删除",
                        color = Color.White,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(8.dp)
                            .background(Color(0xFFE5484D), RoundedCornerShape(4.dp))
                            .clickable(onClick = onDelete)
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }
            Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        video.title ?: "未命名视频",
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        video.uploaderName ?: "",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}


@Composable
private fun VideoUploadDialog(
    onDismiss: () -> Unit,
    onConfirm: (android.net.Uri, android.net.Uri?, String, Int?, Int?, Int?) -> Unit
) {
    val context = LocalContext.current
    var videoUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var coverUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var title by remember { mutableStateOf("") }
    var uploading by remember { mutableStateOf(false) }

    val videoPicker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            videoUri = uri
            if (title.isBlank()) {
                // 默认标题取文件名去扩展名（对齐 Web 端 onVideoPicked 的行为）
                val name = runCatching {
                    context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                        val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                        if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
                    }
                }.getOrNull()
                title = (name ?: "video").replace(Regex("\\.[^.]+$"), "").take(60)
            }
        }
    }
    val coverPicker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetContent()
    ) { uri -> if (uri != null) coverUri = uri }

    AlertDialog(
        onDismissRequest = { if (!uploading) onDismiss() },
        title = { Text("上传视频") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = { videoPicker.launch("video/*") }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (videoUri != null) "已选视频 ✓" else "选择视频文件 *")
                }
                OutlinedButton(onClick = { coverPicker.launch("image/*") }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (coverUri != null) "已选封面 ✓" else "选择封面（可选，默认自动抽帧）")
                }
                OutlinedTextField(
                    value = title,
                    onValueChange = { if (it.length <= 60) title = it },
                    label = { Text("视频标题") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = videoUri != null && title.isNotBlank() && !uploading,
                onClick = {
                    uploading = true
                    onConfirm(videoUri!!, coverUri, title, null, null, null)
                }
            ) { Text(if (uploading) "上传中…" else "发布") }
        },
        dismissButton = {
            TextButton(onClick = { if (!uploading) onDismiss() }) { Text("取消") }
        }
    )
}

@Composable
private fun LiveRoomCard(room: LiveRoomVO, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
    ) {
        Column {
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
                AsyncImage(
                    model = room.cover,
                    contentDescription = room.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
                Surface(
                    color = if ((room.status ?: 0) == 1) Color(0xFFE5484D) else MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = Color.White,
                    shape = RoundedCornerShape(4.dp),
                    modifier = Modifier.align(Alignment.TopStart).padding(8.dp)
                ) {
                    Text(
                        if ((room.status ?: 0) == 1) "直播中" else "已结束",
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall
                    )
                }
                Text(
                    "${room.onlineCount ?: 0} 人在看",
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp)
                )
            }
            Row(
                modifier = Modifier.padding(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Avatar(url = room.anchorAvatar, name = room.anchorName ?: "主", size = 36.dp)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        room.title ?: "未命名直播",
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        "${room.anchorName ?: ""} · 累计 ${room.viewerTotal ?: 0} 次观看",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

/**
 * 直播间：Media3 播放器 + 弹幕收发 + 密码/付费解锁。
 * playUrl 是已签名 m3u8，进房现取现用；弹幕走独立 danmakuWs。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveRoomScreen(
    roomId: String,
    onBack: () -> Unit,
    vm: LiveViewModel = hiltViewModel()
) {
    val room by vm.room.collectAsStateWithLifecycle()
    val danmaku by vm.danmaku.collectAsStateWithLifecycle()
    val online by vm.online.collectAsStateWithLifecycle()
    val ended by vm.ended.collectAsStateWithLifecycle()
    val toast by vm.toast.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var input by remember { mutableStateOf("") }

    LaunchedEffect(roomId) { vm.enterRoom(roomId) }
    LaunchedEffect(toast) {
        toast?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            vm.consumeToast()
        }
    }

    // 播放器生命周期与界面绑定；playUrl 就位才建 MediaItem（签名地址不能缓存复用）
    // 鉴权数据源 + HLS 支持：直播/回放是 m3u8，回放地址同样需要 satoken 头
    val player = remember {
        val dsFactory = OkHttpDataSource.Factory(vm.authedHttpClient())
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(context).setDataSourceFactory(dsFactory))
            .build().apply { playWhenReady = true }
    }
    val playUrl = room?.playUrl
    LaunchedEffect(playUrl) {
        if (!playUrl.isNullOrEmpty()) {
            player.setMediaItem(MediaItem.fromUri(playUrl))
            player.prepare()
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            player.release()
            vm.leaveRoom()
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Column(Modifier.fillMaxSize()) {
            // 顶部信息条
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xEE141414))
                    .padding(horizontal = 4.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = Color.White)
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        room?.title ?: "直播间",
                        color = Color.White,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        "${room?.anchorName ?: ""} · $online 人在看",
                        color = Color.White.copy(alpha = 0.7f),
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }

            // 视频区 16:9
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color.Black)) {
                if (!playUrl.isNullOrEmpty()) {
                    AndroidView(
                        factory = { ctx ->
                            android.view.SurfaceView(ctx).also { player.setVideoSurfaceView(it) }
                        },
                        modifier = Modifier.fillMaxSize(),
                        update = { view -> player.setVideoSurfaceView(view as android.view.SurfaceView) }
                    )
                } else {
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            when {
                                ended -> "直播已结束"
                                room?.gate?.type == "password" -> "密码直播，解锁后观看"
                                room?.gate?.type == "paid" -> "付费直播，解锁后观看"
                                room == null -> "加载中…"
                                else -> "主播离线"
                            },
                            color = Color.White.copy(alpha = 0.85f)
                        )
                        if (room?.gate?.type == "paid") {
                            Spacer(Modifier.height(8.dp))
                            Button(onClick = { vm.pay() }) {
                                Text("支付 ${room?.gate?.price ?: 0} 积分解锁")
                            }
                        }
                    }
                }
            }

            // 弹幕列表（最新在底部）
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(danmaku, key = { it.key }) { item ->
                    DanmakuRow(item)
                }
            }

            // 弹幕输入 + 点赞
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xEE141414))
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { if (it.length <= 50) input = it },
                    placeholder = { Text("发个弹幕…", color = Color.White.copy(alpha = 0.4f)) },
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = Color.White),
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(20.dp),
                    singleLine = true
                )
                IconButton(onClick = {
                    vm.sendDanmaku(input)
                    input = ""
                }) {
                    Icon(Icons.AutoMirrored.Filled.Send, "发送", tint = Color.White)
                }
                IconButton(onClick = { vm.sendLike() }) {
                    Icon(Icons.Default.Favorite, "点赞", tint = Color(0xFFE5484D))
                }
            }
        }
    }

    // 密码解锁弹窗
    if (room?.gate?.type == "password" && room?.unlocked != true && room?.mine != true) {
        PasswordUnlockDialog(
            error = room?.playUrl.isNullOrEmpty(),
            onConfirm = { vm.unlock(it) },
            onDismiss = onBack
        )
    }
}

@Composable
private fun PasswordUnlockDialog(onConfirm: (String) -> Unit, onDismiss: () -> Unit, error: Boolean) {
    var pwd by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("密码直播") },
        text = {
            Column {
                Text("请输入房间密码后观看")
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = pwd,
                    onValueChange = { pwd = it },
                    placeholder = { Text("房间密码") },
                    singleLine = true
                )
                if (error && pwd.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text("密码不正确或已失效", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { if (pwd.isNotEmpty()) onConfirm(pwd) }) { Text("解锁") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("离开") }
        }
    )
}

@Composable
private fun DanmakuRow(item: LiveViewModel.DanmakuItem) {
    when (item.type) {
        "danmaku" -> Row(verticalAlignment = Alignment.CenterVertically) {
            Avatar(url = item.avatar, name = item.nickname ?: "观", size = 22.dp)
            Spacer(Modifier.width(6.dp))
            Text(
                "${item.nickname ?: "观众"}：",
                color = Color(0xFF9FD5FF),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium
            )
            Text(item.content ?: "", color = Color.White, style = MaterialTheme.typography.bodySmall)
        }
        "ended" -> Text(
            item.content ?: "直播已结束",
            color = Color(0xFFFFC66D),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(vertical = 4.dp)
        )
        else -> Text(
            item.content ?: "",
            color = Color.White.copy(alpha = 0.55f),
            style = MaterialTheme.typography.labelSmall
        )
    }
}
