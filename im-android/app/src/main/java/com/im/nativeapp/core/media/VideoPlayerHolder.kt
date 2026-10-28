package com.im.nativeapp.core.media

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.im.nativeapp.core.config.SessionStore
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

    /** 预建并缓冲（鉴权数据源：受控接口需 satoken 头） */
    fun prepare(fileUrl: String?) {
        release()
        val url = session.resolveUrl(fileUrl) ?: ""
        if (url.isEmpty()) return
        val dsFactory = OkHttpDataSource.Factory(apiClient.mediaHttpClient)
        player = ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(context).setDataSourceFactory(dsFactory))
            .build().apply {
                playWhenReady = true
                setMediaItem(MediaItem.fromUri(url))
                prepare()
            }
    }

    fun release() {
        player?.release()
        player = null
    }
}
