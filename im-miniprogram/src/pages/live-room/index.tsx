import { useEffect, useRef, useState } from 'react'
import { Button, Input, ScrollView, Text, Video, View } from '@tarojs/components'
import Taro, { useRouter } from '@tarojs/taro'
import Avatar from '@/components/Avatar'
import RemoteImage from '@/components/RemoteImage'
import { getWsBase, resolveUrl } from '@/config/runtime'
import { liveApi } from '@/services/api'
import type { LiveRoom } from '@/types/im'
import { getToken, getTokenName } from '@/utils/session'
import { confirm, showError } from '@/utils/ui'
import styles from './index.module.scss'

interface DanmakuItem { key: number; type: string; nickname?: string; content?: string }
interface LiveFrame { type?: string; nickname?: string; content?: string; count?: number; message?: string }

export default function LiveRoomPage() {
  const router = useRouter()
  const roomId = String(router.params.id || '')
  const [room, setRoom] = useState<LiveRoom | null>(null)
  const [password, setPassword] = useState('')
  const [replayUrl, setReplayUrl] = useState('')
  const [danmaku, setDanmaku] = useState<DanmakuItem[]>([])
  const [online, setOnline] = useState(0)
  const [input, setInput] = useState('')
  const [connected, setConnected] = useState(false)
  const [ended, setEnded] = useState(false)
  const [anchor, setAnchor] = useState('')
  const socketRef = useRef<Taro.SocketTask | null>(null)
  const heartbeatRef = useRef<ReturnType<typeof setInterval> | null>(null)
  const sequenceRef = useRef(0)

  const closeSocket = (): void => {
    if (heartbeatRef.current) clearInterval(heartbeatRef.current)
    heartbeatRef.current = null
    socketRef.current?.close({})
    socketRef.current = null
    setConnected(false)
  }

  const sendFrame = (data: Record<string, string | number>): void => {
    if (!socketRef.current || !connected) return Taro.showToast({ title: '互动连接尚未就绪', icon: 'none' })
    socketRef.current.send({ data: JSON.stringify({ ...data, timestamp: Date.now() }) })
  }

  const append = (item: Omit<DanmakuItem, 'key'>): void => {
    sequenceRef.current += 1
    const key = sequenceRef.current
    setDanmaku((items) => [...items, { ...item, key }].slice(-100))
    setAnchor(`danmaku-${key}`)
  }

  const openSocket = (path?: string): void => {
    closeSocket()
    if (!path) return
    let url = path
    if (!/^wss?:\/\//.test(path)) {
      const normalized = path.startsWith('/') ? path : `/${path}`
      const separator = normalized.includes('?') ? '&' : '?'
      url = `${getWsBase()}${normalized}${separator}${encodeURIComponent(getTokenName())}=${encodeURIComponent(getToken())}`
    }
    const task = Taro.connectSocket({ url })
    socketRef.current = task
    task.onOpen(() => {
      setConnected(true)
      append({ type: 'system', content: '已连接直播间互动服务' })
      heartbeatRef.current = setInterval(() => {
        task.send({ data: JSON.stringify({ type: 'ping', timestamp: Date.now() }) })
      }, 30000)
    })
    task.onMessage((result) => {
      if (typeof result.data !== 'string') return
      try {
        const frame = JSON.parse(result.data) as LiveFrame
        if (frame.type === 'online') setOnline(frame.count || 0)
        else if (frame.type === 'ended') { setEnded(true); append({ type: 'system', content: frame.content || '直播已结束' }) }
        else if (frame.type === 'danmaku' || frame.type === 'system' || frame.type === 'notice' || frame.type === 'error') append({ type: frame.type, nickname: frame.nickname, content: frame.content || frame.message })
      } catch (error) { console.error('[Live] invalid danmaku frame', error) }
    })
    task.onClose(() => setConnected(false))
    task.onError((error) => { console.error('[Live] danmaku socket failed', error); setConnected(false) })
  }

  const loadRoom = async (): Promise<void> => {
    try {
      const detail = await liveApi.room(roomId)
      setRoom(detail)
      setOnline(detail.onlineCount || detail.viewerCount || 0)
      setEnded(detail.status === 0)
      setDanmaku([])
      setReplayUrl('')
      Taro.setNavigationBarTitle({ title: detail.title || '直播间' })
      openSocket(detail.danmakuWs)
    } catch (error) {
      console.error('[Live] room failed', error)
      showError(error, '直播间加载失败')
    }
  }

  useEffect(() => {
    void loadRoom()
    return () => closeSocket()
  }, [roomId])

  const unlock = async (): Promise<void> => {
    if (!password.trim()) return Taro.showToast({ title: '请输入房间密码', icon: 'none' })
    try {
      const detail = await liveApi.unlock(roomId, password)
      setRoom(detail)
      openSocket(detail.danmakuWs)
    } catch (error) { showError(error, '密码解锁失败') }
  }

  const pay = async (): Promise<void> => {
    if (!await confirm(`确认支付 ${room?.gate?.price || 0} 积分解锁本场直播吗？`, '付费直播')) return
    try {
      const detail = await liveApi.pay(roomId)
      setRoom(detail)
      openSocket(detail.danmakuWs)
      Taro.showToast({ title: '解锁成功', icon: 'success' })
    } catch (error) { showError(error, '支付解锁失败') }
  }

  const loadReplay = async (): Promise<void> => {
    try {
      const result = await liveApi.replay(roomId)
      if (!result.playUrl) return Taro.showToast({ title: '回放不存在或已过期', icon: 'none' })
      setReplayUrl(resolveUrl(result.playUrl))
    } catch (error) { showError(error, '回放加载失败') }
  }

  const send = (): void => {
    const value = input.trim()
    if (!value) return
    sendFrame({ type: 'danmaku', content: value })
    setInput('')
  }

  const locked = Boolean(room && room.status === 1 && !room.mine && room.unlocked !== true && room.gate?.type && room.gate.type !== 'none')
  const playUrl = replayUrl || resolveUrl(room?.playUrl)
  return <View className={styles.page}>
    <View className={styles.videoArea}>
      {playUrl && !locked ? <Video className={styles.video} src={playUrl} autoplay controls objectFit='contain' onError={(error) => { console.error('[Live] playback failed', error); Taro.showToast({ title: '播放失败，请稍后重试', icon: 'none' }) }} /> : <><RemoteImage className={styles.cover} src={room?.cover} mode='aspectFill' /><View className={styles.overlay}><Text className={styles.roomTitle}>{room?.title || '正在进入直播间'}</Text><Text className={styles.state}>{ended ? '直播已结束' : locked ? room?.gate?.type === 'paid' ? '付费直播，解锁后观看' : '密码直播，解锁后观看' : '等待主播信号'}</Text>{locked && room?.gate?.type === 'password' && <><Input className={styles.password} password value={password} onInput={(event) => setPassword(event.detail.value)} placeholder='请输入房间密码' placeholderClass='placeholder' /><Button className={styles.unlock} onClick={() => void unlock()}>解锁观看</Button></>}{locked && room?.gate?.type === 'paid' && <Button className={styles.unlock} onClick={() => void pay()}>支付 {room.gate.price || 0} 积分解锁</Button>}{ended && room?.hasReplay && !replayUrl && <Button className={styles.unlock} onClick={() => void loadReplay()}>观看回放</Button>}</View></>}
    </View>
    <View className={styles.liveInfo}><Avatar name={room?.anchorName} url={room?.anchorAvatar} size={64} /><View className={styles.anchor}><Text className={styles.anchorName}>{room?.anchorName || '主播'}</Text><Text className={styles.online}>{online} 人在线 · {connected ? '互动已连接' : '互动未连接'}</Text></View>{room?.status === 1 && !ended && <Text className={styles.liveTag}>直播中</Text>}{!connected && room?.danmakuWs && <Text className={styles.socketState} onClick={() => openSocket(room.danmakuWs)}>重新连接</Text>}</View>
    <ScrollView className={styles.chat} scrollY scrollIntoView={anchor}>{danmaku.length ? danmaku.map((item) => <View id={`danmaku-${item.key}`} key={item.key}><Text className={item.type === 'danmaku' ? styles.danmaku : styles.system}>{item.type === 'danmaku' && <Text className={styles.nickname}>{item.nickname || '观众'}：</Text>}{item.content || ''}</Text></View>) : <Text className={styles.emptyChat}>{room?.notice || '来发送第一条弹幕吧'}</Text>}</ScrollView>
    <View className={styles.composer}><Input className={styles.input} maxlength={100} value={input} onInput={(event) => setInput(event.detail.value)} onConfirm={send} confirmType='send' placeholder={connected ? '发送弹幕…' : '互动服务连接后可发言'} placeholderClass='placeholder' disabled={!connected || ended} /><Button className={styles.like} disabled={!connected || ended} onClick={() => sendFrame({ type: 'like', count: 1 })}>点赞</Button><Button className={styles.send} disabled={!connected || ended} onClick={send}>发送</Button></View>
  </View>
}
