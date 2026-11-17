import Taro from '@tarojs/taro'
import { getWsBase } from '@/config/runtime'
import type { NotifyData, WsEvent } from '@/types/im'
import { getToken } from '@/utils/session'
import { authApi } from './api'

export type ConnectionState = 'idle' | 'connecting' | 'connected' | 'reconnecting' | 'closed'
type Listener = (event: WsEvent) => void
type StateListener = (state: ConnectionState) => void

let socket: Taro.SocketTask | null = null
let heartbeatTimer: ReturnType<typeof setInterval> | null = null
let reconnectTimer: ReturnType<typeof setTimeout> | null = null
let reconnectAttempt = 0
let heartbeatSeconds = 30
let lastPong = 0
let generation = 0
let manualClose = true
let state: ConnectionState = 'idle'
const listeners = new Set<Listener>()
const stateListeners = new Set<StateListener>()

function setState(next: ConnectionState): void {
  state = next
  stateListeners.forEach((listener) => listener(next))
}

function clearHeartbeat(): void {
  if (heartbeatTimer) clearInterval(heartbeatTimer)
  heartbeatTimer = null
}

function clearReconnect(): void {
  if (reconnectTimer) clearTimeout(reconnectTimer)
  reconnectTimer = null
}

function sendFrame(data: unknown): void {
  if (!socket || state !== 'connected') return
  socket.send({ data: JSON.stringify(data) })
}

/** 上行信令（如通话 invite / cancel）；返回 false 表示连接未就绪，调用方需提示用户 */
export function send(payload: Record<string, unknown>): boolean {
  if (!socket || state !== 'connected') return false
  socket.send({ data: JSON.stringify(payload) })
  return true
}

function startHeartbeat(): void {
  clearHeartbeat()
  lastPong = Date.now()
  heartbeatTimer = setInterval(() => {
    if (Date.now() - lastPong > heartbeatSeconds * 2000) {
      socket?.close({})
      return
    }
    sendFrame({ type: 'ping', timestamp: Date.now() })
  }, heartbeatSeconds * 1000)
}

function dispatch(event: WsEvent): void {
  listeners.forEach((listener) => listener(event))
}

function scheduleReconnect(currentGeneration: number): void {
  if (manualClose || !getToken() || currentGeneration !== generation || reconnectTimer) return
  const delay = Math.min(30000, 1000 * 2 ** reconnectAttempt) + Math.floor(Math.random() * 500)
  reconnectAttempt += 1
  setState('reconnecting')
  reconnectTimer = setTimeout(() => {
    reconnectTimer = null
    void connect()
  }, delay)
}

export async function connect(): Promise<void> {
  if (!getToken()) return
  manualClose = false
  const currentGeneration = ++generation
  clearReconnect()
  clearHeartbeat()
  if (socket) {
    const previous = socket
    socket = null
    previous.close({})
  }
  setState(reconnectAttempt ? 'reconnecting' : 'connecting')

  try {
    const ticket = await authApi.wsTicket()
    if (currentGeneration !== generation || manualClose) return
    const endpoint = ticket.endpoint.startsWith('/') ? ticket.endpoint : `/${ticket.endpoint}`
    const url = `${getWsBase()}${endpoint}?ticket=${encodeURIComponent(ticket.ticket)}`
    const task = Taro.connectSocket({ url })
    socket = task
    task.onOpen(() => {
      if (currentGeneration !== generation) return
      reconnectAttempt = 0
      heartbeatSeconds = 30
      setState('connected')
      startHeartbeat()
    })
    task.onMessage((result) => {
      if (currentGeneration !== generation || typeof result.data !== 'string') return
      try {
        const event = JSON.parse(result.data) as WsEvent
        if (event.type === 'pong') {
          lastPong = Date.now()
          return
        }
        if (event.type === 'notify') {
          const data = event.data as NotifyData | undefined
          if (data?.action === 'connected' && data.heartbeatSeconds) {
            heartbeatSeconds = data.heartbeatSeconds
            startHeartbeat()
          }
        }
        if (event.type === 'kickout') manualClose = true
        lastPong = Date.now()
        dispatch(event)
      } catch {
        // 非 JSON 保活帧不影响主连接。
      }
    })
    task.onClose(() => {
      if (currentGeneration !== generation) return
      clearHeartbeat()
      socket = null
      setState('closed')
      scheduleReconnect(currentGeneration)
    })
    task.onError(() => {
      if (currentGeneration !== generation) return
      clearHeartbeat()
      setState('closed')
    })
  } catch {
    if (currentGeneration === generation) scheduleReconnect(currentGeneration)
  }
}

export function disconnect(): void {
  manualClose = true
  generation += 1
  reconnectAttempt = 0
  clearHeartbeat()
  clearReconnect()
  const current = socket
  socket = null
  current?.close({})
  setState('idle')
}

export function reconnect(): void {
  disconnect()
  void connect()
}

export function subscribe(listener: Listener): () => void {
  listeners.add(listener)
  return () => listeners.delete(listener)
}

export function subscribeState(listener: StateListener): () => void {
  stateListeners.add(listener)
  listener(state)
  return () => stateListeners.delete(listener)
}
