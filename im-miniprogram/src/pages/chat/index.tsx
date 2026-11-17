import { useEffect, useMemo, useRef, useState, type ReactNode } from 'react'
import { Button, Input, ScrollView, Text, View } from '@tarojs/components'
import Taro, { useRouter } from '@tarojs/taro'
import classNames from 'classnames'
import Avatar from '@/components/Avatar'
import RemoteImage from '@/components/RemoteImage'
import { callApi, conversationApi, messageApi } from '@/services/api'
import { send as sendSignal } from '@/services/websocket'
import { uploadFile } from '@/services/upload'
import { useAppStore } from '@/store/useAppStore'
import type { FileInfo, Message, MessageExtra } from '@/types/im'
import { createClientMsgId, formatFileSize, formatTime, messageSummary, parseExtra } from '@/utils/format'
import { confirm, openRemoteFile, previewRemoteImage, showError, signedFileUrl } from '@/utils/ui'
import styles from './index.module.scss'

export default function ChatPage() {
  const router = useRouter()
  const conversationId = String(router.params.id || '')
  const title = String(router.params.title || '聊天')
  const user = useAppStore((state) => state.user)
  const connection = useAppStore((state) => state.connection)
  const conversation = useAppStore((state) => state.conversations.find((item) => item.conversationId === conversationId))
  const messages = useAppStore((state) => state.messages[conversationId] || [])
  const setMessages = useAppStore((state) => state.setMessages)
  const prependMessages = useAppStore((state) => state.prependMessages)
  const upsertMessage = useAppStore((state) => state.upsertMessage)
  const patchMessage = useAppStore((state) => state.patchMessage)
  const patchMessageByClientId = useAppStore((state) => state.patchMessageByClientId)
  const removeMessage = useAppStore((state) => state.removeMessage)
  const patchConversation = useAppStore((state) => state.patchConversation)
  const setActiveConversation = useAppStore((state) => state.setActiveConversation)
  const [text, setText] = useState('')
  const [loading, setLoading] = useState(true)
  const [hasMore, setHasMore] = useState(true)
  const [loadingOlder, setLoadingOlder] = useState(false)
  const [anchor, setAnchor] = useState('')
  const [showTools, setShowTools] = useState(false)
  const [voiceMode, setVoiceMode] = useState(false)
  const [recording, setRecording] = useState(false)
  const [playingId, setPlayingId] = useState('')
  const [calling, setCalling] = useState(false)
  const audioRef = useRef<ReturnType<typeof Taro.createInnerAudioContext> | null>(null)
  const recordingRef = useRef(false)
  const recorder = useMemo(() => Taro.getRecorderManager(), [])
  const lastMessageId = messages.length ? messages[messages.length - 1].messageId : ''

  const markRead = (): void => {
    const maxSeq = messages.length ? messages[messages.length - 1].seq : undefined
    patchConversation(conversationId, { unreadCount: 0, atFlag: false })
    void conversationApi.read(conversationId, maxSeq ? String(maxSeq) : undefined).catch((error: unknown) => console.error('[Chat] read failed', error))
  }

  useEffect(() => {
    if (!conversationId) return
    setActiveConversation(conversationId)
    Taro.setNavigationBarTitle({ title })
    void messageApi.history(conversationId, undefined, 30)
      .then((items) => {
        setMessages(conversationId, items)
        setHasMore(items.length >= 30)
      })
      .catch((error: unknown) => {
        console.error('[Chat] history failed', error)
        showError(error, '消息加载失败')
      })
      .finally(() => setLoading(false))
    return () => {
      setActiveConversation('')
      audioRef.current?.stop()
      audioRef.current?.destroy()
    }
  }, [conversationId])

  useEffect(() => {
    if (!loading && lastMessageId) {
      setAnchor(`msg-${lastMessageId}`)
      markRead()
    }
  }, [lastMessageId, loading])

  useEffect(() => {
    recorder.onStop((result) => {
      setRecording(false)
      recordingRef.current = false
      if (!result.tempFilePath || result.duration < 800) {
        Taro.showToast({ title: '说话时间太短', icon: 'none' })
        return
      }
      void sendAttachment(4, result.tempFilePath, 'chat_voice', { duration: Math.round(result.duration / 1000), fileName: '语音消息' }, Math.round(result.duration / 1000))
    })
    recorder.onError((error) => {
      console.error('[Chat] recorder failed', error)
      setRecording(false)
      recordingRef.current = false
      Taro.showToast({ title: '录音失败，请检查麦克风权限', icon: 'none' })
    })
  }, [recorder])

  const loadOlder = async (): Promise<void> => {
    if (loadingOlder || !hasMore || !messages.length) return
    setLoadingOlder(true)
    try {
      const beforeSeq = messages[0].seq ? String(messages[0].seq) : undefined
      const items = await messageApi.history(conversationId, beforeSeq, 30)
      prependMessages(conversationId, items)
      setHasMore(items.length >= 30)
    } catch (error) {
      console.error('[Chat] older history failed', error)
      showError(error, '历史消息加载失败')
    } finally {
      setLoadingOlder(false)
    }
  }

  const sendPayload = async (msgType: number, content: string, extra?: MessageExtra, retry?: Message): Promise<void> => {
    if (!user || !conversationId) return
    const clientMsgId = retry?.clientMsgId || createClientMsgId()
    const optimistic: Message = retry ? { ...retry, sending: true, failed: false } : {
      messageId: clientMsgId,
      clientMsgId,
      conversationId,
      fromUserId: user.id,
      msgType,
      content,
      extra,
      sendTime: Date.now(),
      self: true,
      sending: true
    }
    if (retry) patchMessage(conversationId, retry.messageId, optimistic)
    else upsertMessage(optimistic)
    patchConversation(conversationId, { lastMsgContent: messageSummary(optimistic), lastMsgType: msgType, lastMsgTime: Date.now(), unreadCount: 0 })
    try {
      const sent = await messageApi.send({ clientMsgId, msgType, content, conversationId, extra })
      patchMessageByClientId(clientMsgId, { ...sent, clientMsgId, sending: false, failed: false, self: true })
    } catch (error) {
      console.error('[Chat] send failed', error)
      patchMessageByClientId(clientMsgId, { sending: false, failed: true })
      showError(error, '消息发送失败')
    }
  }

  const sendText = (): void => {
    const value = text.trim()
    if (!value) return
    setText('')
    setShowTools(false)
    void sendPayload(1, value)
  }

  async function sendAttachment(
    msgType: number,
    filePath: string,
    bizType: 'chat_image' | 'chat_file' | 'chat_voice',
    extra: MessageExtra,
    duration?: number
  ): Promise<void> {
    Taro.showLoading({ title: '正在上传', mask: true })
    try {
      const file: FileInfo = await uploadFile(filePath, bizType, duration)
      const metadata: MessageExtra = {
        ...extra,
        fileId: file.fileId,
        fileName: extra.fileName || file.originalName || file.fileName,
        fileUrl: file.url,
        fileSize: file.size,
        contentType: file.contentType,
        ext: file.ext,
        duration: extra.duration || file.duration
      }
      await sendPayload(msgType, file.fileId, metadata)
    } catch (error) {
      console.error('[Chat] attachment failed', error)
      showError(error, '附件发送失败')
    } finally {
      Taro.hideLoading()
    }
  }

  const chooseImage = async (): Promise<void> => {
    try {
      const result = await Taro.chooseImage({ count: 1, sizeType: ['compressed'], sourceType: ['album', 'camera'] })
      const file = result.tempFiles[0]
      if (!file) return
      await sendAttachment(2, file.path, 'chat_image', { fileName: '图片', fileSize: file.size })
      setShowTools(false)
    } catch (error) {
      if (error instanceof Error && error.message.includes('cancel')) return
      console.error('[Chat] choose image failed', error)
      showError(error, '图片选择失败')
    }
  }

  const chooseFile = async (): Promise<void> => {
    try {
      const result = await Taro.chooseMessageFile({ count: 1, type: 'all' })
      const file = result.tempFiles[0]
      if (!file) return
      await sendAttachment(3, file.path, 'chat_file', { fileName: file.name, fileSize: file.size })
      setShowTools(false)
    } catch (error) {
      if (error instanceof Error && error.message.includes('cancel')) return
      console.error('[Chat] choose file failed', error)
      showError(error, '文件选择失败')
    }
  }

  const startRecord = (): void => {
    if (recordingRef.current) return
    recordingRef.current = true
    setRecording(true)
    recorder.start({ duration: 60000, sampleRate: 16000, numberOfChannels: 1, encodeBitRate: 48000, format: 'mp3' })
  }

  const stopRecord = (): void => {
    if (!recordingRef.current) return
    recorder.stop()
  }

  const playVoice = async (message: Message): Promise<void> => {
    try {
      audioRef.current?.stop()
      const extra = parseExtra(message.extra)
      const url = await signedFileUrl(extra.fileId, extra.fileUrl || message.content)
      const audio = Taro.createInnerAudioContext()
      audioRef.current = audio
      audio.src = url
      audio.onEnded(() => setPlayingId(''))
      audio.onError((error) => {
        console.error('[Chat] voice playback failed', error)
        setPlayingId('')
        Taro.showToast({ title: '语音播放失败', icon: 'none' })
      })
      setPlayingId(message.messageId)
      audio.play()
    } catch (error) {
      showError(error, '语音地址不可用')
    }
  }

  const showMessageActions = async (message: Message): Promise<void> => {
    if (message.sending || message.recalled || message.msgType === 5) return
    const actions = message.failed ? ['重新发送', '删除消息'] : message.self ? ['撤回消息', '删除消息'] : ['删除消息']
    const result = await Taro.showActionSheet({ itemList: actions })
    try {
      if (message.failed && result.tapIndex === 0) {
        await sendPayload(message.msgType, message.content, parseExtra(message.extra), message)
      } else if (!message.failed && message.self && result.tapIndex === 0) {
        if (!await confirm('撤回后，会话成员将看到撤回提示。', '撤回消息')) return
        await messageApi.recall(message.messageId)
        patchMessage(conversationId, message.messageId, { recalled: true, content: '' })
      } else {
        if (!await confirm('仅删除当前设备上的这条消息。', '删除消息')) return
        await messageApi.remove(message.messageId)
        removeMessage(conversationId, message.messageId)
      }
    } catch (error) {
      console.error('[Chat] message action failed', error)
      showError(error)
    }
  }

  const openGroup = (): void => {
    if (conversation?.type === 2 && conversation.targetId) Taro.navigateTo({ url: `/pages/group-detail/index?id=${conversation.targetId}` })
  }

  /**
   * 发起呼叫：通过主 WebSocket 的 call invite 信令把呼叫送达对端。
   * 微信小程序没有 RTCPeerConnection / getUserMedia，无法承载实时音视频，
   * 因此实际接听由手机 App 或桌面端完成，这里只负责发起与提示。
   */
  const startCall = async (mediaType: 'audio' | 'video'): Promise<void> => {
    if (!conversation) return Taro.showToast({ title: '会话信息加载中', icon: 'none' })
    if (conversation.type === 2) return Taro.showToast({ title: '群通话暂未开放', icon: 'none' })
    const calleeUserId = conversation.targetId
    if (!calleeUserId) return Taro.showToast({ title: '无法确定对端账号', icon: 'none' })
    if (calling) return
    setCalling(true)
    try {
      const config = await callApi.config()
      if (!config || config.enabled === false) {
        return Taro.showToast({ title: '通话服务未开启', icon: 'none' })
      }
      const sent = sendSignal({
        type: 'call',
        action: 'invite',
        callId: createClientMsgId(),
        calleeUserId: String(calleeUserId),
        mediaType
      })
      if (!sent) return Taro.showToast({ title: '实时连接未就绪，请稍后重试', icon: 'none' })
      await Taro.showModal({
        title: mediaType === 'video' ? '视频通话' : '语音通话',
        content: '已向对方发起呼叫。小程序端无法承载实时音视频，请让对方在手机 App 或桌面端接听。',
        showCancel: false,
        confirmText: '知道了'
      })
    } catch (error) {
      console.error('[Call] invite failed', error)
      showError(error, '呼叫失败')
    } finally {
      setCalling(false)
    }
  }

  const renderContent = (message: Message): ReactNode => {
    if (message.recalled) return <Text className={styles.recalled}>消息已撤回</Text>
    const extra = parseExtra(message.extra)
    if (message.msgType === 2) {
      return <RemoteImage className={styles.image} src={extra.fileId ? `/api/file/download/${extra.fileId}` : extra.fileUrl || message.content} mode='aspectFill' onClick={() => void previewRemoteImage(extra.fileId, extra.fileUrl || message.content).catch(showError)} />
    }
    if (message.msgType === 3) {
      return <View className={styles.file} onClick={() => void openRemoteFile(extra).catch(showError)}><View className={styles.fileIcon}>{(extra.ext || 'FILE').slice(0, 4).toUpperCase()}</View><View className={styles.fileCopy}><Text className={styles.fileName}>{extra.fileName || '聊天文件'}</Text><Text className={styles.fileSize}>{formatFileSize(extra.fileSize) || '点击打开'}</Text></View></View>
    }
    if (message.msgType === 4) {
      return <View className={styles.voice} onClick={() => void playVoice(message)}><Text className={styles.voiceIcon}>{playingId === message.messageId ? ')))' : '))'}</Text><Text className={styles.voiceTime}>{extra.duration || 0}″</Text></View>
    }
    if (message.msgType === 6) return <Text>{message.content || '通话记录'}</Text>
    return <Text>{message.content}</Text>
  }

  return (
    <View className={styles.page}>
      {connection !== 'connected' && <View className={styles.status}>实时连接中，消息仍可发送</View>}
      <ScrollView className={styles.messages} scrollY scrollIntoView={anchor} scrollWithAnimation upperThreshold={80} onScrollToUpper={() => void loadOlder()}>
        <View className={styles.history} onClick={() => void loadOlder()}>{loading ? '正在加载消息…' : loadingOlder ? '正在加载更早消息…' : hasMore && messages.length ? '上拉或点击查看更早消息' : '已经到最早的消息啦'}</View>
        {messages.map((message) => {
          if (message.msgType === 5) return <View className={styles.system} key={message.messageId}><Text className={styles.systemText}>{message.content}</Text></View>
          const self = Boolean(message.self)
          return (
            <View className={styles.message} id={`msg-${message.messageId}`} key={message.messageId}>
              <View className={self ? styles.selfRow : styles.peerRow}>
                <Avatar name={self ? user?.nickname : message.fromNickname || message.senderName || title} url={self ? user?.avatar : message.fromAvatar || message.senderAvatar} size={72} />
                <View className={styles.body}>
                  {!self && conversation?.type === 2 && <Text className={styles.sender}>{message.fromNickname || message.senderName || '群成员'}</Text>}
                  <View className={classNames(styles.bubble, self && styles.selfBubble, (message.msgType === 2 || message.msgType === 3) && styles.mediaBubble)} onLongPress={() => void showMessageActions(message)}>{renderContent(message)}</View>
                  <View className={styles.messageMeta}><Text>{formatTime(message.sendTime)}</Text>{self && <Text className={message.failed ? styles.failed : ''}>{message.sending ? '发送中' : message.failed ? '发送失败，长按重发' : message.status === 3 ? '已读' : message.status === 2 ? '已送达' : ''}</Text>}</View>
                </View>
              </View>
            </View>
          )
        })}
      </ScrollView>
      <View className={styles.composer}>
        {conversation?.type === 2 && <View className={styles.notice} onClick={openGroup}>查看群资料与成员</View>}
        <View className={styles.callBar}>
          <Button className={styles.callButton} disabled={calling} onClick={() => void startCall('audio')}>语音通话</Button>
          <Button className={styles.callButton} disabled={calling} onClick={() => void startCall('video')}>视频通话</Button>
        </View>
        <View className={styles.inputRow}>
          <Button className={classNames(styles.toolButton, styles.voiceToggle)} onClick={() => setVoiceMode(!voiceMode)}>{voiceMode ? '键盘' : '语音'}</Button>
          {voiceMode ? <View className={classNames(styles.record, recording && styles.recording)} onLongPress={startRecord} onTouchEnd={stopRecord} onTouchCancel={stopRecord}>{recording ? '松开发送' : '按住说话'}</View> : <Input className={styles.input} value={text} onInput={(event) => setText(event.detail.value)} confirmType='send' onConfirm={sendText} placeholder='说点什么…' placeholderClass='placeholder' />}
          {text.trim() && !voiceMode ? <Button className={styles.send} onClick={sendText}>发送</Button> : <Button className={styles.toolButton} onClick={() => setShowTools(!showTools)}>+</Button>}
        </View>
        {showTools && <View className={styles.tools}><View className={styles.tool} onClick={() => void chooseImage()}><View className={styles.toolIcon}>图</View><Text>图片</Text></View><View className={styles.tool} onClick={() => void chooseFile()}><View className={styles.toolIcon}>文</View><Text>文件</Text></View></View>}
      </View>
    </View>
  )
}
