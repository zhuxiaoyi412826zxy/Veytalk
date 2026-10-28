package com.im.nativeapp.ui.live

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.view.SurfaceView
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.im.nativeapp.core.config.SessionStore
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
    fun player(): ExoPlayer = holder.player ?: run {
        holder.prepare(fileUrl)
        holder.player!!
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

    val player = remember { vm.player() }
    // 首帧渲染前用封面占位（Coil 已缓存秒出），消除黑屏空窗
    var firstFrame by remember { mutableStateOf(false) }
    // 控制层显隐：点按画面切换
    var controlsVisible by remember { mutableStateOf(true) }
    var isPlaying by remember { mutableStateOf(player.isPlaying) }
    var durationMs by remember { mutableStateOf(player.duration.coerceAtLeast(0)) }
    var positionMs by remember { mutableStateOf(player.currentPosition.coerceAtLeast(0)) }
    // 拖动中的进度（松手才 seek，避免拖动过程被轮询回写顶掉）
    var dragMs by remember { mutableStateOf<Float?>(null) }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onRenderedFirstFrame() { firstFrame = true }
            override fun onIsPlayingChanged(playing: Boolean) { isPlaying = playing }
            override fun onPlaybackStateChanged(playbackState: Int) {
                durationMs = player.duration.coerceAtLeast(0)
                positionMs = player.currentPosition.coerceAtLeast(0)
                if (playbackState == Player.STATE_ENDED) controlsVisible = true
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }
    // 播放中轮询进度（500ms 足够跟手，Slider 数值变化会重组）
    LaunchedEffect(player) {
        while (true) {
            if (dragMs == null) positionMs = player.currentPosition.coerceAtLeast(0)
            delay(500)
        }
    }
    DisposableEffect(Unit) { onDispose { vm.holder.release() } }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) { detectTapGestures { controlsVisible = !controlsVisible } }
    ) {
        AndroidView(
            factory = { ctx -> SurfaceView(ctx).also(player::setVideoSurfaceView) },
            update = { view -> player.setVideoSurfaceView(view as SurfaceView) },
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
        if (controlsVisible) {
            // 顶部返回条（半透明黑底，白字）
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.4f))
                    .padding(horizontal = 4.dp, vertical = 2.dp)
                    .align(Alignment.TopStart),
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
            // 底部控制条：播放/暂停 + 当前时间 + 进度条 + 总时长
            val shownMs = dragMs?.toLong() ?: positionMs
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.4f))
                    .padding(horizontal = 8.dp, vertical = 2.dp)
                    .align(Alignment.BottomStart),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { if (isPlaying) player.pause() else player.play() }) {
                    Icon(
                        if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (isPlaying) "暂停" else "播放",
                        tint = Color.White
                    )
                }
                Text(formatMs(shownMs), color = Color.White, style = MaterialTheme.typography.labelSmall)
                Slider(
                    value = shownMs.toFloat().coerceIn(0f, (durationMs.takeIf { it > 0 } ?: 1).toFloat()),
                    onValueChange = { dragMs = it },
                    onValueChangeFinished = {
                        dragMs?.let { player.seekTo(it.toLong()) }
                        dragMs = null
                    },
                    valueRange = 0f..(durationMs.takeIf { it > 0 } ?: 1).toFloat(),
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 8.dp),
                    colors = SliderDefaults.colors(
                        thumbColor = Color.White,
                        activeTrackColor = Color.White,
                        inactiveTrackColor = Color.White.copy(alpha = 0.3f)
                    )
                )
                Text(formatMs(durationMs), color = Color.White, style = MaterialTheme.typography.labelSmall)
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

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
