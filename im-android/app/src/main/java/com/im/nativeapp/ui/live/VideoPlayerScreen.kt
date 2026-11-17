package com.im.nativeapp.ui.live

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.view.SurfaceView
import android.view.WindowManager
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.im.nativeapp.core.config.SessionStore
import com.im.nativeapp.core.config.UiPrefs
import com.im.nativeapp.core.log.AppLog
import com.im.nativeapp.core.media.VideoPlayerHolder
import com.im.nativeapp.core.network.ApiClient
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import javax.inject.Inject

@HiltViewModel
class VideoPlayerViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val session: SessionStore,
    private val apiClient: ApiClient,
    val holder: VideoPlayerHolder
) : ViewModel() {
    // 路由 query 参数，Navigation 已做过 URL decode
    val fileUrl: String = savedStateHandle.get<String>("url") ?: ""
    val title: String = savedStateHandle.get<String>("title") ?: ""
    val coverUrl: String = savedStateHandle.get<String>("cover") ?: ""
    val width: Int = savedStateHandle.get<Int>("w") ?: 16
    val height: Int = savedStateHandle.get<Int>("h") ?: 9

    /** 列表页点击时已预建缓冲；兜底（深链接/异常路径）现场建 */
    fun player(): ExoPlayer? {
        holder.player?.let { return it }
        holder.prepare(fileUrl, UiPrefs.autoPlayVideo)
        return holder.player
    }

    fun coverFull(): String = session.resolveUrl(coverUrl) ?: ""
}

/**
 * 全屏视频播放页：
 * - 横版（宽≥高）真横屏全屏；竖版竖屏全屏
 * - ROTATION_ANIMATION_JUMPCUT：进/出的屏幕旋转直接跳切，无旋转动画
 * - 隐藏系统栏沉浸播放，退出恢复竖屏并回到视频列表原位置
 * - 点按画面显隐控制层：播放/暂停 + 可拖动进度条 + 时间
 */
@Composable
fun VideoPlayerScreen(onBack: () -> Unit, vm: VideoPlayerViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val landscape = vm.width >= vm.height

    DisposableEffect(Unit) {
        val window = activity?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        controller?.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        // 关键：先给窗口设 JUMPCUT 再切方向 —— 旋转瞬间生效，无系统旋转动画
        window?.attributes = window?.attributes?.apply {
            rotationAnimation = WindowManager.LayoutParams.ROTATION_ANIMATION_JUMPCUT
        }
        activity?.requestedOrientation =
            if (landscape) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        onDispose {
            // 退出同样 JUMPCUT 跳切回竖屏，再恢复窗口默认旋转行为
            activity?.window?.attributes = activity?.window?.attributes?.apply {
                rotationAnimation = WindowManager.LayoutParams.ROTATION_ANIMATION_JUMPCUT
            }
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            controller?.show(WindowInsetsCompat.Type.systemBars())
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    val player = remember { runCatching { vm.player() }.getOrNull() }
    if (player == null) {
        Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            Text("视频地址无效", color = Color.White)
        }
        return
    }

    // 「视频自动播放」关闭时只 prepare 不 auto start：保留封面首帧，等用户点播放
    LaunchedEffect(player) {
        if (!UiPrefs.autoPlayVideo) player.playWhenReady = false
    }

    // 首帧渲染前用封面占位（Coil 已缓存秒出），消除黑屏空窗
    var firstFrame by remember { mutableStateOf(false) }
    // 控制层显隐：点按画面切换
    var controlsVisible by remember { mutableStateOf(true) }
    var playWhenReady by remember { mutableStateOf(player.playWhenReady) }
    var playbackState by remember { mutableStateOf(player.playbackState) }
    var durationMs by remember { mutableStateOf(player.duration.coerceAtLeast(0)) }
    var positionMs by remember { mutableStateOf(player.currentPosition.coerceAtLeast(0)) }
    var bufferedMs by remember { mutableStateOf(player.bufferedPosition.coerceAtLeast(0)) }
    var playerError by remember { mutableStateOf<PlaybackException?>(null) }
    // 拖动中的进度（松手才 seek，避免拖动过程被轮询回写顶掉）
    var dragMs by remember { mutableStateOf<Float?>(null) }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onRenderedFirstFrame() { firstFrame = true }
            override fun onPlayWhenReadyChanged(ready: Boolean, reason: Int) { playWhenReady = ready }
            override fun onPlaybackStateChanged(state: Int) {
                playbackState = state
                durationMs = player.duration.coerceAtLeast(0)
                positionMs = player.currentPosition.coerceAtLeast(0)
                bufferedMs = player.bufferedPosition.coerceAtLeast(0)
                if (state == Player.STATE_ENDED) controlsVisible = true
            }
            override fun onPlayerError(error: PlaybackException) {
                playerError = error
                playbackState = player.playbackState
                AppLog.w("PLAY", "播放失败 ${error.errorCodeName}: ${error.message}")
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }
    // 轮询进度：300ms 刷新；顺带自愈 playWhenReady / playbackState，
    // 避免个别机型回调顺序异常导致按钮图标与实际状态不一致（需点两三次）
    LaunchedEffect(player) {
        while (true) {
            if (dragMs == null) {
                positionMs = player.currentPosition.coerceAtLeast(0)
                bufferedMs = player.bufferedPosition.coerceAtLeast(0)
                playWhenReady = player.playWhenReady
                playbackState = player.playbackState
                if (durationMs <= 0) durationMs = player.duration.coerceAtLeast(0)
            }
            delay(300)
        }
    }
    DisposableEffect(Unit) { onDispose { vm.holder.release() } }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) { detectTapGestures { controlsVisible = !controlsVisible } }
    ) {
        // Surface 只在 factory 里绑定一次。
        // 绝不能在 update 里重绑：进度轮询会触发重组，反复 setVideoSurfaceView 会让
        // 视频画面被频繁重新挂载，表现为「播两秒就卡住」。
        AndroidView(
            factory = { ctx ->
                SurfaceView(ctx).also { player.setVideoSurfaceView(it) }
            },
            modifier = Modifier.fillMaxSize()
        )
        // 首帧前的封面占位
        if (!firstFrame) {
            val cover = vm.coverFull()
            if (cover.isNotEmpty()) {
                coil.compose.AsyncImage(
                    model = cover,
                    contentDescription = null,
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
        // 缓冲中转圈
        if (playerError == null && playWhenReady && playbackState == Player.STATE_BUFFERING) {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center).size(44.dp),
                color = Color.White,
                strokeWidth = 3.dp
            )
        }
        // 播放失败浮层
        playerError?.let { err ->
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(horizontal = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("播放失败", color = Color.White, style = MaterialTheme.typography.titleMedium)
                Text(
                    err.message ?: "网络或格式异常",
                    color = Color.White.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 6.dp)
                )
                TextButton(onClick = {
                    playerError = null
                    if (player.playbackState == Player.STATE_IDLE) player.prepare()
                    player.seekTo(0)
                    player.play()
                    playWhenReady = true
                }) { Text("重试", color = Color.White) }
            }
        }

        if (controlsVisible) {
            // 顶部返回条（渐变遮罩，白字）
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopStart)
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Black.copy(alpha = 0.65f), Color.Transparent)
                        )
                    )
                    .padding(bottom = 12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = Color.White)
                    }
                    Text(
                        vm.title.ifEmpty { "视频" },
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            // 底部控制条：播放/暂停 + 当前时间 + 进度条 + 总时长
            val shownMs = dragMs?.toLong() ?: positionMs
            val dur = durationMs.takeIf { it > 0 } ?: 1L
            // 整条吞掉点击，避免操作控制条时误触发「点画面显隐」
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomStart)
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Transparent, Color.Black.copy(alpha = 0.75f))
                        )
                    )
                    .padding(top = 32.dp)
                    .pointerInput(Unit) { detectTapGestures { } }
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = {
                        val shouldPlay = !(playWhenReady && playbackState != Player.STATE_ENDED)
                        if (shouldPlay) {
                            playerError = null
                            // 兜底：未 prepare 就 prepare，播完了就回到开头
                            if (player.playbackState == Player.STATE_IDLE) player.prepare()
                            if (player.playbackState == Player.STATE_ENDED) player.seekTo(0)
                            player.play()
                        } else {
                            player.pause()
                        }
                        // 立即回写 UI，不等回调 —— 连续点击时图标与动作始终一致
                        playWhenReady = shouldPlay
                        playbackState = player.playbackState
                    }) {
                        val playing = playWhenReady && playbackState != Player.STATE_ENDED
                        Icon(
                            if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (playing) "暂停" else "播放",
                            tint = Color.White,
                            modifier = Modifier.size(30.dp)
                        )
                    }
                    Text(formatMs(shownMs), color = Color.White, style = MaterialTheme.typography.labelSmall)
                    // 自定义进度条：底层缓冲 + 已播 + 可拖 thumb
                    VideoProgressBar(
                        position = shownMs.toFloat(),
                        buffered = bufferedMs.toFloat(),
                        duration = dur.toFloat(),
                        onSeek = { dragMs = it },
                        onSeekFinished = {
                            dragMs?.let { player.seekTo(it.toLong()) }
                            dragMs = null
                        },
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 10.dp)
                    )
                    Text(formatMs(durationMs), color = Color.White, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

private fun formatMs(ms: Long): String {
    val totalSec = (ms / 1000).coerceAtLeast(0)
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/**
 * 自定义视频进度条：
 * - 三层轨道：背景（半透明白）/ 缓冲（更透明白）/ 已播（纯白）
 * - 圆形 thumb，拖动时放大（带过渡动画）
 * - 点击任意位置跳转，拖动实时预览，松手才 seek
 */
@Composable
private fun VideoProgressBar(
    position: Float,
    buffered: Float,
    duration: Float,
    onSeek: (Float) -> Unit,
    onSeekFinished: () -> Unit,
    modifier: Modifier = Modifier
) {
    val fraction = (position / duration).coerceIn(0f, 1f)
    val bufferedFraction = (buffered / duration).coerceIn(0f, 1f)
    var dragging by remember { mutableStateOf(false) }
    val thumbRadius by animateFloatAsState(if (dragging) 9f else 6f, label = "thumbRadius")

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(30.dp)
            // 点击任意位置跳转
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    val f = (offset.x / size.width).coerceIn(0f, 1f)
                    onSeek(f * duration)
                    onSeekFinished()
                }
            }
            // 拖动 scrub
            .pointerInput(Unit) {
                var curFrac = 0f
                detectDragGestures(
                    onDragStart = { offset ->
                        dragging = true
                        curFrac = (offset.x / size.width).coerceIn(0f, 1f)
                        onSeek(curFrac * duration)
                    },
                    onDragEnd = { dragging = false; onSeekFinished() },
                    onDragCancel = { dragging = false; onSeekFinished() }
                ) { _, dragAmount ->
                    curFrac = (curFrac + dragAmount.x / size.width).coerceIn(0f, 1f)
                    onSeek(curFrac * duration)
                }
            },
        contentAlignment = Alignment.CenterStart
    ) {
        Canvas(modifier = Modifier.fillMaxWidth()) {
            val trackH = 4.dp.toPx()
            val y = size.height / 2
            // 背景
            drawRoundRect(
                color = Color.White.copy(alpha = 0.22f),
                topLeft = Offset(0f, y - trackH / 2),
                size = Size(size.width, trackH),
                cornerRadius = CornerRadius(trackH / 2)
            )
            // 缓冲
            if (bufferedFraction > 0f) {
                drawRoundRect(
                    color = Color.White.copy(alpha = 0.4f),
                    topLeft = Offset(0f, y - trackH / 2),
                    size = Size(size.width * bufferedFraction, trackH),
                    cornerRadius = CornerRadius(trackH / 2)
                )
            }
            // 已播
            drawRoundRect(
                color = Color.White,
                topLeft = Offset(0f, y - trackH / 2),
                size = Size(size.width * fraction, trackH),
                cornerRadius = CornerRadius(trackH / 2)
            )
            // 圆点 thumb
            drawCircle(
                color = Color.White,
                radius = thumbRadius.dp.toPx(),
                center = Offset(size.width * fraction, y)
            )
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
