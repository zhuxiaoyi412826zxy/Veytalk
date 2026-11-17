import { useState } from 'react'
import { Button, Input, Text, Video, View } from '@tarojs/components'
import Taro, { useDidShow, usePullDownRefresh, useReachBottom } from '@tarojs/taro'
import classNames from 'classnames'
import Avatar from '@/components/Avatar'
import EmptyState from '@/components/EmptyState'
import PageHeader from '@/components/PageHeader'
import RemoteImage from '@/components/RemoteImage'
import { articleApi, liveApi } from '@/services/api'
import { uploadFile } from '@/services/upload'
import type { Article, LiveRoom, VideoItem } from '@/types/im'
import { confirm, playableMediaUrl, showError } from '@/utils/ui'
import styles from './index.module.scss'

interface PendingVideo { path: string; cover: string; duration: number; width: number; height: number; size: number }

type TabKey = 'videos' | 'live' | 'articles'

function durationText(value?: number): string {
  const seconds = value || 0
  return `${Math.floor(seconds / 60).toString().padStart(2, '0')}:${Math.floor(seconds % 60).toString().padStart(2, '0')}`
}

export default function VideosPage() {
  const [tab, setTab] = useState<TabKey>('videos')

  const [items, setItems] = useState<VideoItem[]>([])
  const [current, setCurrent] = useState(1)
  const [hasMore, setHasMore] = useState(true)
  const [loading, setLoading] = useState(false)

  const [rooms, setRooms] = useState<LiveRoom[]>([])
  const [roomPage, setRoomPage] = useState(1)
  const [roomHasMore, setRoomHasMore] = useState(true)
  const [roomLoading, setRoomLoading] = useState(false)

  const [articles, setArticles] = useState<Article[]>([])
  const [articlePage, setArticlePage] = useState(1)
  const [articleHasMore, setArticleHasMore] = useState(true)
  const [articleLoading, setArticleLoading] = useState(false)

  const [publishing, setPublishing] = useState(false)
  const [showPublish, setShowPublish] = useState(false)
  const [publishTitle, setPublishTitle] = useState('')
  const [pending, setPending] = useState<PendingVideo | null>(null)
  const [playing, setPlaying] = useState<VideoItem | null>(null)
  const [videoSrc, setVideoSrc] = useState('')
  const [poster, setPoster] = useState('')

  const load = async (page = 1): Promise<void> => {
    if (loading) return
    setLoading(true)
    try {
      const result = await liveApi.videoPage(page, 10)
      setItems((old) => page === 1 ? result.records || [] : [...old, ...(result.records || [])])
      setCurrent(page)
      setHasMore((result.records || []).length >= 10)
    } catch (error) {
      console.error('[Video] list failed', error)
      showError(error, '视频加载失败')
    } finally { setLoading(false) }
  }

  const loadRooms = async (page = 1): Promise<void> => {
    if (roomLoading) return
    setRoomLoading(true)
    try {
      const result = await liveApi.page(page, 10)
      setRooms((old) => page === 1 ? result.records || [] : [...old, ...(result.records || [])])
      setRoomPage(page)
      setRoomHasMore((result.records || []).length >= 10)
    } catch (error) {
      console.error('[Live] list failed', error)
      showError(error, '直播加载失败')
    } finally { setRoomLoading(false) }
  }

  const loadArticles = async (page = 1): Promise<void> => {
    if (articleLoading) return
    setArticleLoading(true)
    try {
      const result = await articleApi.page(page, 10)
      setArticles((old) => page === 1 ? result.records || [] : [...old, ...(result.records || [])])
      setArticlePage(page)
      setArticleHasMore((result.records || []).length >= 10)
    } catch (error) {
      console.error('[Article] list failed', error)
      showError(error, '文章加载失败')
    } finally { setArticleLoading(false) }
  }

  const switchTab = (next: TabKey): void => {
    setTab(next)
    if (next === 'videos' && !items.length) void load(1)
    if (next === 'live' && !rooms.length) void loadRooms(1)
    if (next === 'articles' && !articles.length) void loadArticles(1)
  }

  useDidShow(() => { if (tab === 'videos' && !items.length) void load(1) })

  usePullDownRefresh(() => {
    const task = tab === 'videos' ? load(1) : tab === 'live' ? loadRooms(1) : loadArticles(1)
    void task.finally(() => Taro.stopPullDownRefresh())
  })

  useReachBottom(() => {
    if (tab === 'videos' && hasMore) void load(current + 1)
    else if (tab === 'live' && roomHasMore) void loadRooms(roomPage + 1)
    else if (tab === 'articles' && articleHasMore) void loadArticles(articlePage + 1)
  })

  const choose = async (): Promise<void> => {
    try {
      const result = await Taro.chooseVideo({ sourceType: ['album', 'camera'], compressed: true, maxDuration: 60 })
      setPending({ path: result.tempFilePath, cover: result.thumbTempFilePath, duration: Math.round(result.duration), width: result.width, height: result.height, size: result.size })
      if (!publishTitle) setPublishTitle(`我的视频 ${new Date().toLocaleDateString()}`)
    } catch (error) {
      if (error instanceof Error && error.message.includes('cancel')) return
      showError(error, '视频选择失败')
    }
  }

  const publish = async (): Promise<void> => {
    if (!pending || !publishTitle.trim()) return Taro.showToast({ title: '请选择视频并填写标题', icon: 'none' })
    setPublishing(true)
    Taro.showLoading({ title: '正在发布', mask: true })
    try {
      const video = await uploadFile(pending.path, 'video')
      let coverFileId: string | undefined
      if (pending.cover) coverFileId = (await uploadFile(pending.cover, 'video_cover')).fileId
      await liveApi.createVideo({ title: publishTitle.trim(), fileId: video.fileId, coverFileId, duration: pending.duration, width: pending.width, height: pending.height, size: pending.size, ext: video.ext, contentType: video.contentType })
      Taro.showToast({ title: '发布成功', icon: 'success' })
      setShowPublish(false)
      setPending(null)
      setPublishTitle('')
      await load(1)
    } catch (error) {
      console.error('[Video] publish failed', error)
      showError(error, '视频发布失败')
    } finally { setPublishing(false); Taro.hideLoading() }
  }

  const play = (item: VideoItem): void => {
    const raw = item.fileUrl || item.videoUrl || item.url
    if (!raw) {
      Taro.showToast({ title: '该视频暂无可播放地址', icon: 'none' })
      return
    }
    setPlaying(item)
    setVideoSrc('')
    setPoster('')
    // 先换带票据的直链：Video 组件带不了 satoken，直接播鉴权地址会解码失败
    void playableMediaUrl(raw)
      .then((url) => setVideoSrc(url))
      .catch((error: unknown) => {
        console.error('[Video] resolve playable url failed', error)
        showError(error, '视频地址获取失败')
      })
    // 封面同样需要签名，先展示出来，避免点开后长时间黑屏
    void playableMediaUrl(item.coverUrl || item.cover)
      .then((url) => setPoster(url))
      .catch(() => setPoster(''))
    void liveApi.reportVideoPlay(item.id || item.videoId || '').catch((error: unknown) => console.error('[Video] play report failed', error))
  }

  const remove = async (): Promise<void> => {
    if (!playing || !await confirm('删除后无法恢复，确定删除这条视频吗？', '删除视频')) return
    try {
      await liveApi.deleteVideo(playing.id || playing.videoId || '')
      setPlaying(null)
      await load(1)
    } catch (error) { showError(error, '删除失败') }
  }

  return <View className={styles.page}>
    <PageHeader title='视频' subtitle='短视频 · 直播 · 文章' right={tab === 'videos' ? <View className={styles.add} onClick={() => setShowPublish(!showPublish)}>发布</View> : undefined} />
    <View className={styles.tabs}>
      <View className={classNames(styles.tab, tab === 'videos' && styles.active)} onClick={() => switchTab('videos')}>视频</View>
      <View className={classNames(styles.tab, tab === 'live' && styles.active)} onClick={() => switchTab('live')}>直播</View>
      <View className={classNames(styles.tab, tab === 'articles' && styles.active)} onClick={() => switchTab('articles')}>文章</View>
    </View>

    {tab === 'videos' && <>
      {showPublish && <View className={styles.publish}><Text className={styles.publishTitle}>发布短视频</Text><Input className={styles.input} maxlength={60} value={publishTitle} onInput={(event) => setPublishTitle(event.detail.value)} placeholder='填写视频标题' placeholderClass='placeholder' />{pending && <Text className={styles.fileName}>已选择 {durationText(pending.duration)} 的视频，封面将自动生成</Text>}<View className={styles.publishActions}><Button className={styles.button} onClick={() => void choose()}>{pending ? '重新选择' : '选择视频'}</Button><Button className={classNames(styles.button, styles.primary)} loading={publishing} disabled={publishing} onClick={() => void publish()}>确认发布</Button></View></View>}
      {items.length ? <View className={styles.grid}>{items.map((item) => { const id = item.id || item.videoId || ''; return <View className={styles.card} key={id} onClick={() => play(item)}><View className={styles.coverWrap}><RemoteImage className={styles.cover} src={item.coverUrl || item.cover} mode='aspectFill' /><View className={styles.play}>▶</View><Text className={styles.duration}>{durationText(item.duration)}</Text></View><View className={styles.copy}><Text className={styles.title}>{item.title}</Text><View className={styles.meta}><Avatar name={item.uploaderName || item.authorName} url={item.uploaderAvatar || item.authorAvatar} size={40} /><Text className={styles.author}>{item.uploaderName || item.authorName || '用户'}</Text><Text>{item.playCount || 0} 次播放</Text></View></View></View>})}</View> : <View className={styles.empty}><EmptyState title='还没有短视频' description='发布第一条视频，与大家分享此刻' actionText='发布视频' onAction={() => setShowPublish(true)} /></View>}
      {hasMore && items.length > 0 && <Button className={classNames(styles.button, styles.loadMore)} loading={loading} onClick={() => void load(current + 1)}>加载更多</Button>}
    </>}

    {tab === 'live' && <>
      {rooms.length ? <View className={styles.grid}>{rooms.map((room) => { const id = room.id || room.roomId || ''; return <View className={styles.card} key={id} onClick={() => Taro.navigateTo({ url: `/pages/live-room/index?id=${id}` })}><View className={styles.coverWrap}><RemoteImage className={styles.cover} src={room.cover} mode='aspectFill' /><Text className={styles.duration}>{room.status === 1 ? '直播中' : '已结束'}</Text>{room.gate?.type && room.gate.type !== 'none' && <Text className={styles.gate}>{room.gate.type === 'paid' ? '付费' : '密码'}</Text>}</View><View className={styles.copy}><Text className={styles.title}>{room.title}</Text><View className={styles.meta}><Avatar name={room.anchorName} url={room.anchorAvatar} size={40} /><Text className={styles.author}>{room.anchorName || '主播'}</Text><Text>{room.onlineCount || room.viewerCount || 0} 人</Text></View></View></View>})}</View> : <View className={styles.empty}><EmptyState title='暂无直播房间' description='主播开播后，房间会出现在这里' /></View>}
      {roomHasMore && rooms.length > 0 && <Button className={classNames(styles.button, styles.loadMore)} loading={roomLoading} onClick={() => void loadRooms(roomPage + 1)}>加载更多</Button>}
    </>}

    {tab === 'articles' && <>
      {articles.length ? <View className={styles.articleList}>{articles.map((article) => <View className={styles.articleCard} key={article.id} onClick={() => Taro.navigateTo({ url: `/pages/article/index?id=${article.id}` })}><View className={styles.articleCopy}><Text className={styles.articleTitle}>{article.title}</Text>{article.summary ? <Text className={styles.articleSummary}>{article.summary}</Text> : null}<View className={styles.meta}><Avatar name={article.authorName} url={article.authorAvatar} size={40} /><Text className={styles.author}>{article.authorName || '作者'}</Text><Text>{article.viewCount || 0} 次阅读</Text></View></View>{article.coverUrl ? <RemoteImage className={styles.articleCover} src={article.coverUrl} mode='aspectFill' /> : null}</View>)}</View> : <View className={styles.empty}><EmptyState title='还没有文章' description='文章发布后会出现在这里' /></View>}
      {articleHasMore && articles.length > 0 && <Button className={classNames(styles.button, styles.loadMore)} loading={articleLoading} onClick={() => void loadArticles(articlePage + 1)}>加载更多</Button>}
    </>}

    {playing && <View className={styles.playerMask}><Video className={styles.player} src={videoSrc} poster={poster} autoplay controls showFullscreenBtn objectFit='contain' onError={(error) => { console.error('[Video] playback failed', error); Taro.showToast({ title: '视频播放失败', icon: 'none' }) }} /><View className={styles.playerInfo}><Text className={styles.playerTitle}>{playing.title}</Text><Text className={styles.playerMeta}>{playing.uploaderName || playing.authorName} · {playing.playCount || 0} 次播放</Text></View><View className={styles.playerActions}>{playing.mine && <Button className={classNames(styles.playerButton, styles.danger)} onClick={() => void remove()}>删除视频</Button>}<Button className={styles.playerButton} onClick={() => { setPlaying(null); setVideoSrc(''); setPoster('') }}>关闭播放</Button></View></View>}
  </View>
}
