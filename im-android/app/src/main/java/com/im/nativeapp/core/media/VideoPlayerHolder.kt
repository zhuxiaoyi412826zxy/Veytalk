package com.im.nativeapp.core.media

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.im.nativeapp.core.config.SessionStore
import com.im.nativeapp.core.log.AppLog
import com.im.nativeapp.core.network.ApiClient
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 视频播放器持有者（单例）：点视频卡片时预建 ExoPlayer 并开始缓冲，
 * 导航转场期间完成首帧，进入全屏播放页直接 attach Surface 出声画 —— 无缝切入。
 * 退出播放页时 release。
 */
@Singleton
class VideoPlayerHolder @Inject constructor(
    @ApplicationContext private val context: Context,
    private val apiClient: ApiClient,
    private val session: SessionStore
) {
    var player: ExoPlayer? = null
        private set

    /**
     * 预建并缓冲（鉴权数据源：受控接口需 satoken 头）。
     * autoPlay=false 时只 prepare 首帧不自动播，由播放页等用户点播放（设置项「视频自动播放」）。
     */
    fun prepare(fileUrl: String?, autoPlay: Boolean = true) {
        release()
        val url = session.resolveUrl(fileUrl) ?: ""
        if (url.isEmpty()) {
            AppLog.w("PLAYER", "prepare 跳过：url 为空")
            return
        }
        AppLog.i("PLAYER", "prepare autoPlay=$autoPlay url=$url")
        val dsFactory = OkHttpDataSource.Factory(apiClient.mediaHttpClient)
        // 缓冲区调大一些：弱网/自签证书握手抖动时不容易一卡一顿
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                15_000, // 最小缓冲
                60_000, // 最大缓冲
                1_000,  // 起播所需
                2_000   // 重缓冲所需
            )
            .build()
        player = ExoPlayer.Builder(context)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(DefaultMediaSourceFactory(context).setDataSourceFactory(dsFactory))
            .build().apply {
                playWhenReady = autoPlay
                setMediaItem(MediaItem.fromUri(url))
                prepare()
            }
    }

    fun release() {
        player?.let {
            AppLog.i("PLAYER", "release")
            it.release()
        }
        player = null
    }
}
