import Taro from '@tarojs/taro'
import { getApiBase } from '@/config/runtime'
import type { AiChatMessage, ApiEnvelope } from '@/types/im'
import { createTraceId } from '@/utils/format'
import { getToken, getTokenName } from '@/utils/session'

export interface AiStream {
  done: Promise<void>
  cancel: () => void
}

function createUtf8Decoder(): (buffer: ArrayBuffer) => string {
  let pending: number[] = []
  return (buffer) => {
    const bytes = pending.concat(Array.from(new Uint8Array(buffer)))
    pending = []
    let index = 0
    let output = ''
    while (index < bytes.length) {
      const first = bytes[index]
      let length = 1
      let code = first
      if (first >= 0xf0) { length = 4; code = first & 0x07 }
      else if (first >= 0xe0) { length = 3; code = first & 0x0f }
      else if (first >= 0xc0) { length = 2; code = first & 0x1f }
      else if (first >= 0x80) { index += 1; continue }
      if (index + length > bytes.length) break
      let valid = true
      for (let offset = 1; offset < length; offset += 1) {
        const next = bytes[index + offset]
        if ((next & 0xc0) !== 0x80) { valid = false; break }
        code = (code << 6) | (next & 0x3f)
      }
      if (valid) {
        if (code <= 0xffff) output += String.fromCharCode(code)
        else {
          const value = code - 0x10000
          output += String.fromCharCode(0xd800 + (value >> 10), 0xdc00 + (value & 0x3ff))
        }
        index += length
      } else index += 1
    }
    pending = bytes.slice(index)
    return output
  }
}

export function streamAiChat(messages: AiChatMessage[], onDelta: (content: string) => void): AiStream {
  let task: Taro.RequestTask | null = null
  let settled = false
  let buffer = ''
  const decode = createUtf8Decoder()
  let resolveDone: () => void = () => undefined
  let rejectDone: (error: Error) => void = () => undefined
  const done = new Promise<void>((resolve, reject) => { resolveDone = resolve; rejectDone = reject })

  const finish = (error?: Error): void => {
    if (settled) return
    settled = true
    if (error) rejectDone(error)
    else resolveDone()
  }

  const handleEvent = (raw: string): boolean => {
    let event = 'message'
    const dataLines: string[] = []
    raw.split('\n').forEach((line) => {
      if (line.startsWith('event:')) event = line.slice(6).trim()
      if (line.startsWith('data:')) dataLines.push(line.slice(5).trim())
    })
    const data = dataLines.join('\n')
    if (!data) return false
    if (event === 'delta') {
      const payload = JSON.parse(data) as { content?: string }
      if (payload.content) onDelta(payload.content)
    } else if (event === 'error') {
      const payload = JSON.parse(data) as { message?: string }
      throw new Error(payload.message || 'AI 服务异常')
    }
    return event === 'done'
  }

  const consume = (chunk: string): void => {
    buffer += chunk.replace(/\r\n/g, '\n')
    let index = buffer.indexOf('\n\n')
    while (index >= 0) {
      const raw = buffer.slice(0, index)
      buffer = buffer.slice(index + 2)
      if (raw.trim() && handleEvent(raw)) {
        finish()
        task?.abort()
        return
      }
      index = buffer.indexOf('\n\n')
    }
  }

  const header: Record<string, string> = {
    'Content-Type': 'application/json',
    'X-Trace-Id': createTraceId(),
    [getTokenName()]: getToken()
  }

  task = Taro.request({
    url: `${getApiBase()}/ai/interview/chat`,
    method: 'POST',
    data: { messages },
    header,
    enableChunked: true,
    timeout: 300000,
    success: (response) => {
      if (settled) return
      const contentType = Object.keys(response.header).find((key) => key.toLowerCase() === 'content-type')
      const contentTypeValue = contentType ? String(response.header[contentType]) : ''
      if (response.statusCode < 200 || response.statusCode >= 300 || contentTypeValue.includes('application/json')) {
        const body = response.data as ApiEnvelope<unknown>
        finish(new Error(body?.message || body?.msg || `AI 服务异常（HTTP ${response.statusCode}）`))
        return
      }
      if (typeof response.data === 'string' && response.data) {
        try { consume(response.data); if (!settled && buffer.trim()) handleEvent(buffer); finish() } catch (error) { finish(error instanceof Error ? error : new Error('AI 响应解析失败')) }
      } else if (!settled) finish()
    },
    fail: (error) => {
      if (!settled) finish(new Error(error.errMsg || 'AI 连接已中断'))
    }
  })

  task.onChunkReceived((response) => {
    if (settled) return
    try { consume(decode(response.data)) } catch (error) {
      finish(error instanceof Error ? error : new Error('AI 响应解析失败'))
      task?.abort()
    }
  })

  return {
    done,
    cancel: () => {
      task?.abort()
      finish()
    }
  }
}
