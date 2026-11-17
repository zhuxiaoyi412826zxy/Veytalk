import { useEffect, useRef, useState } from 'react'
import { Button, ScrollView, Text, Textarea, View } from '@tarojs/components'
import Taro from '@tarojs/taro'
import classNames from 'classnames'
import { streamAiChat, type AiStream } from '@/services/ai'
import { aiApi } from '@/services/api'
import type { AiChatMessage, AiStatus } from '@/types/im'
import { confirm } from '@/utils/ui'
import styles from './index.module.scss'

interface DisplayMessage extends AiChatMessage { id: number }

export default function AiPage() {
  const [status, setStatus] = useState<AiStatus | null>(null)
  const [messages, setMessages] = useState<DisplayMessage[]>([])
  const [input, setInput] = useState('')
  const [streaming, setStreaming] = useState(false)
  const [error, setError] = useState('')
  const [anchor, setAnchor] = useState('')
  const idRef = useRef(0)
  const streamRef = useRef<AiStream | null>(null)

  const loadStatus = async (): Promise<void> => {
    try { setStatus(await aiApi.status()) } catch (loadError) { console.error('[AI] status failed', loadError); setStatus({ enabled: false, message: '状态获取失败' }) }
  }
  useEffect(() => {
    void loadStatus()
    return () => streamRef.current?.cancel()
  }, [])

  const append = (role: 'user' | 'assistant', content: string): number => {
    idRef.current += 1
    const id = idRef.current
    setMessages((items) => [...items, { id, role, content }])
    setAnchor(`ai-${id}`)
    return id
  }

  const run = (history: AiChatMessage[]): void => {
    if (streaming || status?.enabled === false) return
    setError('')
    setStreaming(true)
    const assistantId = append('assistant', '')
    const stream = streamAiChat(history, (delta) => {
      setMessages((items) => items.map((item) => item.id === assistantId ? { ...item, content: item.content + delta } : item))
    })
    streamRef.current = stream
    void stream.done.then(() => {
      setMessages((items) => items.map((item) => item.id === assistantId && !item.content ? { ...item, content: '本轮没有生成内容，请重试。' } : item))
    }).catch((streamError: unknown) => {
      console.error('[AI] stream failed', streamError)
      const message = streamError instanceof Error ? streamError.message : 'AI 服务异常'
      setError(message)
      setMessages((items) => items.filter((item) => item.id !== assistantId || Boolean(item.content)))
    }).finally(() => {
      setStreaming(false)
      streamRef.current = null
    })
  }

  const send = (): void => {
    const value = input.trim()
    if (!value || streaming) return
    if (status?.enabled === false) return Taro.showToast({ title: status.message || 'AI 面试官未启用', icon: 'none' })
    const history: AiChatMessage[] = [...messages.filter((item) => item.content).map(({ role, content }) => ({ role, content })), { role: 'user', content: value }]
    append('user', value)
    setInput('')
    run(history)
  }

  const prompt = (content: string): void => {
    if (streaming) return
    const history: AiChatMessage[] = [...messages.filter((item) => item.content).map(({ role, content: text }) => ({ role, content: text })), { role: 'user', content }]
    append('user', content)
    run(history)
  }

  const clear = async (): Promise<void> => {
    if (!messages.length || !await confirm('清空当前面试对话？')) return
    streamRef.current?.cancel()
    setMessages([])
    setError('')
  }

  return <View className={styles.page}>
    <View className={styles.header}><View className={styles.logo}>AI</View><View className={styles.heading}><Text className={styles.title}>AI 面试官</Text><View className={styles.status}><View className={classNames(styles.dot, status?.enabled === false && styles.offline)} /><Text>{status?.enabled === false ? status.message || '服务未启用' : `${status?.model || '面试助手'}${status?.knowledgeBaseReady ? ' · 知识库已就绪' : ''}`}</Text></View></View><Text className={styles.clear} onClick={() => void clear()}>清空</Text></View>
    <ScrollView className={styles.messages} scrollY scrollIntoView={anchor} scrollWithAnimation>
      {!messages.length && <View className={styles.welcome}><Text className={styles.welcomeTitle}>准备好下一次面试了吗？</Text><Text className={styles.welcomeText}>我会结合岗位知识库与你进行真实问答，并逐题深入追问。可以从一场新面试开始，也可以先试试下面的问题。</Text><Button className={styles.start} disabled={streaming || status?.enabled === false} onClick={() => run([])}>开始新面试</Button><View className={styles.suggestions}><Text className={styles.suggestion} onClick={() => prompt('请帮我准备前端开发岗位面试')}>前端面试</Text><Text className={styles.suggestion} onClick={() => prompt('请帮我准备 Java 后端岗位面试')}>Java 面试</Text><Text className={styles.suggestion} onClick={() => prompt('请模拟一次自我介绍环节')}>自我介绍</Text></View></View>}
      {messages.map((message) => <View id={`ai-${message.id}`} className={classNames(styles.message, message.role === 'user' && styles.userMessage)} key={message.id}><View className={classNames(styles.bubble, message.role === 'user' && styles.userBubble)}>{message.content || <Text className={styles.thinking}>面试官正在思考…</Text>}</View></View>)}
      {error && <View className={styles.error}>{error}，请检查网络后重试。</View>}
    </ScrollView>
    <Text className={styles.notice}>AI 内容仅供面试练习参考；当前小程序提供文本对话</Text>
    <View className={styles.composer}><View className={styles.inputRow}><Textarea className={styles.input} autoHeight maxlength={4000} value={input} onInput={(event) => setInput(event.detail.value)} placeholder='输入你的回答或想练习的问题…' placeholderClass='placeholder' disabled={streaming || status?.enabled === false} />{streaming ? <Button className={classNames(styles.send, styles.stop)} onClick={() => streamRef.current?.cancel()}>停止</Button> : <Button className={classNames(styles.send, status?.enabled === false && styles.disabled)} disabled={status?.enabled === false} onClick={send}>发送</Button>}</View></View>
  </View>
}
