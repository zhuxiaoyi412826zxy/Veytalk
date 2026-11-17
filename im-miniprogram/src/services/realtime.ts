import Taro from '@tarojs/taro'
import { messageApi } from '@/services/api'
import { subscribe, subscribeState } from '@/services/websocket'
import { useAppStore } from '@/store/useAppStore'
import type { Message, NotifyData, OnlineStateData, UnreadData, WsEvent } from '@/types/im'
import { messageSummary } from '@/utils/format'

let installed = false

function eventData<T>(event: WsEvent): T | null {
  return event.data && typeof event.data === 'object' ? event.data as T : null
}

function handleMessage(raw: Message): void {
  const store = useAppStore.getState()
  if (!raw.conversationId) return
  const message: Message = {
    ...raw,
    messageId: String(raw.messageId),
    conversationId: String(raw.conversationId),
    fromUserId: String(raw.fromUserId),
    self: raw.fromUserId === store.user?.id
  }
  store.upsertMessage(message)
  const current = store.conversations.find((item) => item.conversationId === message.conversationId)
  const active = store.activeConversationId === message.conversationId
  store.patchConversation(message.conversationId, {
    lastMsgContent: messageSummary(message),
    lastMsgType: message.msgType,
    lastMsgTime: message.sendTime || Date.now(),
    unreadCount: active || message.self ? 0 : (current?.unreadCount || 0) + 1,
    atFlag: false
  })
  if (!current) void store.loadConversations().catch(() => undefined)
  if (active) {
    if (!message.self) void messageApi.delivered([message.messageId]).catch(() => undefined)
    void messageApi.read(message.conversationId, message.seq ? String(message.seq) : undefined).catch(() => undefined)
  } else if (!message.self) {
    store.setTotalUnread(store.totalUnread + 1)
  }
}

function handleEvent(event: WsEvent): void {
  const store = useAppStore.getState()
  if (event.type === 'message') {
    const message = eventData<Message>(event)
    if (message) handleMessage(message)
    return
  }
  if (event.type === 'ack') {
    const data = eventData<Partial<Message>>(event)
    if (event.clientMsgId) {
      store.patchMessageByClientId(event.clientMsgId, {
        ...data,
        clientMsgId: event.clientMsgId,
        sending: false,
        failed: false
      })
    }
    return
  }
  if (event.type === 'recall-notify') {
    const data = eventData<{ messageId: string; conversationId: string }>(event)
    if (data) store.patchMessage(data.conversationId, data.messageId, { recalled: true, content: '' })
    return
  }
  if (event.type === 'unread') {
    const data = eventData<UnreadData>(event)
    if (data?.conversationId) {
      store.patchConversation(data.conversationId, { unreadCount: data.unreadCount || 0 })
    }
    if (data?.total !== undefined || data?.totalUnread !== undefined) {
      store.setTotalUnread(Number(data.total ?? data.totalUnread) || 0)
    } else {
      void conversationUnread()
    }
    return
  }
  if (event.type === 'online-state') {
    const data = eventData<OnlineStateData>(event)
    if (!data) return
    useAppStore.setState((state) => ({
      friends: state.friends.map((friend) => friend.friendId === data.userId ? { ...friend, online: data.online } : friend),
      conversations: state.conversations.map((conversation) =>
        conversation.targetId === data.userId ? { ...conversation, online: data.online } : conversation
      )
    }))
    return
  }
  if (event.type === 'notify') {
    const data = eventData<NotifyData>(event)
    if (data?.bizType === 'friend-request') void store.loadFriendPending().catch(() => undefined)
    if (data?.bizType === 'friend-request' || data?.bizType === 'friend-accepted') {
      void store.loadFriends().catch(() => undefined)
      void store.loadConversations().catch(() => undefined)
    }
    if (data?.biz === 'group') {
      void store.loadGroups().catch(() => undefined)
      void store.loadConversations().catch(() => undefined)
    }
    return
  }
  if (event.type === 'kickout') {
    store.expireSession(event.message || '账号已在其他设备登录')
    return
  }
  if (event.type === 'error' && event.message) Taro.showToast({ title: event.message, icon: 'none' })
}

async function conversationUnread(): Promise<void> {
  const { conversationApi } = await import('./api')
  const total = await conversationApi.unreadTotal().catch(() => 0)
  useAppStore.getState().setTotalUnread(Number(total) || 0)
}

export function installRealtime(): () => void {
  if (installed) return () => undefined
  installed = true
  const unsubscribe = subscribe(handleEvent)
  const unsubscribeState = subscribeState((connection) => useAppStore.getState().setConnection(connection))
  return () => {
    unsubscribe()
    unsubscribeState()
    installed = false
  }
}
