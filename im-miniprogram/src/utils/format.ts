import dayjs from 'dayjs'
import type { Conversation, Message, MessageExtra } from '@/types/im'

export function toId(value: string | number | null | undefined): string {
  return value === null || value === undefined ? '' : String(value)
}

export function createClientMsgId(): string {
  return `mini-${Date.now()}-${Math.random().toString(36).slice(2, 10)}`
}

export function createTraceId(): string {
  return `mini-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 12)}`
}

export function parseExtra(extra?: MessageExtra | string): MessageExtra {
  if (!extra) return {}
  if (typeof extra !== 'string') return extra
  try {
    return JSON.parse(extra) as MessageExtra
  } catch {
    return {}
  }
}

export function messageSummary(message?: Partial<Message>, msgType?: number, content?: string): string {
  if (message?.recalled) return '[消息已撤回]'
  const type = message?.msgType || msgType || 1
  const text = message?.content ?? content ?? ''
  const extra = parseExtra(message?.extra)
  if (type === 2) return '[图片]'
  if (type === 3) return `[文件] ${extra.fileName || ''}`.trim()
  if (type === 4) return '[语音]'
  if (type === 6) return '[通话记录]'
  return text
}

export function conversationTitle(item: Conversation): string {
  return item.name || item.title || '会话'
}

export function formatTime(value?: string | number): string {
  if (!value) return ''
  const date = dayjs(value)
  if (!date.isValid()) return ''
  const now = dayjs()
  if (date.isSame(now, 'day')) return date.format('HH:mm')
  if (date.isSame(now.subtract(1, 'day'), 'day')) return '昨天'
  if (date.isSame(now, 'year')) return date.format('MM-DD')
  return date.format('YYYY-MM-DD')
}

export function formatFullTime(value?: string | number): string {
  if (!value) return ''
  const date = dayjs(value)
  return date.isValid() ? date.format('YYYY-MM-DD HH:mm:ss') : ''
}

export function formatFileSize(value?: number): string {
  if (!value || value <= 0) return ''
  if (value < 1024) return `${value} B`
  if (value < 1024 * 1024) return `${(value / 1024).toFixed(1)} KB`
  if (value < 1024 * 1024 * 1024) return `${(value / 1024 / 1024).toFixed(1)} MB`
  return `${(value / 1024 / 1024 / 1024).toFixed(1)} GB`
}

export function sortConversations(items: Conversation[]): Conversation[] {
  return [...items].sort((a, b) => {
    const topDiff = Number(Boolean(b.top || b.isTop)) - Number(Boolean(a.top || a.isTop))
    if (topDiff) return topDiff
    const aTime = dayjs(a.top || a.isTop ? a.topTime || a.lastMsgTime : a.lastMsgTime).valueOf() || 0
    const bTime = dayjs(b.top || b.isTop ? b.topTime || b.lastMsgTime : b.lastMsgTime).valueOf() || 0
    if (aTime !== bTime) return bTime - aTime
    return b.conversationId.localeCompare(a.conversationId)
  })
}

export function errorMessage(error: unknown, fallback = '操作失败'): string {
  // 不依赖 instanceof：降级编译后 Error 子类可能丢失原型链，按结构取 message 更稳
  if (error instanceof Error && error.message) return error.message
  if (error && typeof error === 'object') {
    const message = (error as { message?: unknown }).message
    if (typeof message === 'string' && message) return message
  }
  return fallback
}
