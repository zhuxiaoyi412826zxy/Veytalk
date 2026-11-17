import Taro from '@tarojs/taro'
import { create } from 'zustand'
import { authApi, conversationApi, friendApi, groupApi } from '@/services/api'
import { setUnauthorizedHandler } from '@/services/http'
import { connect, disconnect, type ConnectionState } from '@/services/websocket'
import type { Conversation, Friend, GroupInfo, LoginResult, Message, UserInfo } from '@/types/im'
import { sortConversations } from '@/utils/format'
import { clearSession, getCachedUser, getToken, saveLogin, saveUser } from '@/utils/session'

interface AppState {
  initialized: boolean
  user: UserInfo | null
  conversations: Conversation[]
  friends: Friend[]
  groups: GroupInfo[]
  messages: Record<string, Message[]>
  totalUnread: number
  friendPending: number
  activeConversationId: string
  connection: ConnectionState
  bootstrap: () => Promise<UserInfo | null>
  acceptLogin: (result: LoginResult) => void
  logout: (requestServer?: boolean) => Promise<void>
  expireSession: (message?: string) => void
  setUser: (user: UserInfo) => void
  setConnection: (connection: ConnectionState) => void
  setActiveConversation: (id: string) => void
  loadConversations: () => Promise<void>
  loadFriends: () => Promise<void>
  loadGroups: () => Promise<void>
  loadFriendPending: () => Promise<void>
  setMessages: (conversationId: string, messages: Message[]) => void
  prependMessages: (conversationId: string, messages: Message[]) => void
  upsertMessage: (message: Message) => void
  patchMessage: (conversationId: string, messageId: string, patch: Partial<Message>) => void
  patchMessageByClientId: (clientMsgId: string, patch: Partial<Message>) => void
  removeMessage: (conversationId: string, messageId: string) => void
  updateConversation: (conversation: Conversation) => void
  patchConversation: (id: string, patch: Partial<Conversation>) => void
  removeConversation: (id: string) => void
  setTotalUnread: (value: number) => void
}

function normalizeMessage(message: Message, selfId?: string): Message {
  return {
    ...message,
    messageId: String(message.messageId),
    conversationId: String(message.conversationId),
    fromUserId: String(message.fromUserId),
    self: message.self ?? (selfId ? String(message.fromUserId) === selfId : false)
  }
}

function uniqueMessages(messages: Message[]): Message[] {
  const map = new Map<string, Message>()
  messages.forEach((message) => {
    const key = message.clientMsgId || message.messageId
    const previous = map.get(key)
    map.set(key, previous ? { ...previous, ...message, sending: false } : message)
  })
  return Array.from(map.values()).sort((a, b) => {
    const aSeq = Number(a.seq || 0)
    const bSeq = Number(b.seq || 0)
    if (aSeq && bSeq && aSeq !== bSeq) return aSeq - bSeq
    return new Date(a.sendTime || 0).getTime() - new Date(b.sendTime || 0).getTime()
  })
}

export const useAppStore = create<AppState>()((set, get) => {
  const clearLocal = (): void => {
    disconnect()
    clearSession()
    set({
      user: null,
      conversations: [],
      friends: [],
      groups: [],
      messages: {},
      totalUnread: 0,
      friendPending: 0,
      activeConversationId: '',
      initialized: true
    })
  }

  return {
    initialized: false,
    user: null,
    conversations: [],
    friends: [],
    groups: [],
    messages: {},
    totalUnread: 0,
    friendPending: 0,
    activeConversationId: '',
    connection: 'idle',

    bootstrap: async () => {
      const cached = getCachedUser()
      if (cached) set({ user: cached })
      if (!getToken()) {
        set({ initialized: true })
        return null
      }
      try {
        const user = await authApi.me()
        saveUser(user)
        set({ user, initialized: true })
        void connect()
        return user
      } catch {
        clearLocal()
        return null
      }
    },

    acceptLogin: (result) => {
      saveLogin(result)
      set({ user: result.userInfo, initialized: true })
      void connect()
    },

    logout: async (requestServer = true) => {
      if (requestServer && getToken()) {
        try {
          await authApi.logout()
        } catch {
          // 本地退出不依赖网络结果。
        }
      }
      clearLocal()
    },

    expireSession: (message) => {
      const hadSession = Boolean(getToken())
      clearLocal()
      if (hadSession) {
        if (message) Taro.showToast({ title: message, icon: 'none' })
        Taro.reLaunch({ url: '/pages/login/index' })
      }
    },

    setUser: (user) => {
      saveUser(user)
      set({ user })
    },
    setConnection: (connection) => set({ connection }),
    setActiveConversation: (activeConversationId) => set({ activeConversationId }),

    loadConversations: async () => {
      const [conversations, unread] = await Promise.all([
        conversationApi.list(),
        conversationApi.unreadTotal().catch(() => get().totalUnread)
      ])
      set({ conversations: sortConversations(conversations), totalUnread: Number(unread) || 0 })
    },
    loadFriends: async () => set({ friends: await friendApi.list() }),
    loadGroups: async () => set({ groups: await groupApi.my() }),
    loadFriendPending: async () => set({ friendPending: Number(await friendApi.pendingCount()) || 0 }),

    setMessages: (conversationId, messages) => {
      const selfId = get().user?.id
      set((state) => ({
        messages: { ...state.messages, [conversationId]: uniqueMessages(messages.map((item) => normalizeMessage(item, selfId))) }
      }))
    },
    prependMessages: (conversationId, messages) => {
      const selfId = get().user?.id
      set((state) => ({
        messages: {
          ...state.messages,
          [conversationId]: uniqueMessages([
            ...messages.map((item) => normalizeMessage(item, selfId)),
            ...(state.messages[conversationId] || [])
          ])
        }
      }))
    },
    upsertMessage: (rawMessage) => {
      const message = normalizeMessage(rawMessage, get().user?.id)
      set((state) => {
        const oldMessages = state.messages[message.conversationId] || []
        const withoutDuplicate = oldMessages.filter((item) => {
          if (message.clientMsgId && item.clientMsgId === message.clientMsgId) return false
          return item.messageId !== message.messageId
        })
        return {
          messages: {
            ...state.messages,
            [message.conversationId]: uniqueMessages([...withoutDuplicate, message])
          }
        }
      })
    },
    patchMessage: (conversationId, messageId, patch) => {
      set((state) => ({
        messages: {
          ...state.messages,
          [conversationId]: (state.messages[conversationId] || []).map((item) =>
            item.messageId === messageId ? { ...item, ...patch } : item
          )
        }
      }))
    },
    patchMessageByClientId: (clientMsgId, patch) => {
      set((state) => {
        const next = { ...state.messages }
        Object.keys(next).forEach((conversationId) => {
          next[conversationId] = next[conversationId].map((item) =>
            item.clientMsgId === clientMsgId ? { ...item, ...patch } : item
          )
        })
        return { messages: next }
      })
    },
    removeMessage: (conversationId, messageId) => {
      set((state) => ({
        messages: {
          ...state.messages,
          [conversationId]: (state.messages[conversationId] || []).filter((item) => item.messageId !== messageId)
        }
      }))
    },
    updateConversation: (conversation) => {
      const list = get().conversations.filter((item) => item.conversationId !== conversation.conversationId)
      set({ conversations: sortConversations([conversation, ...list]) })
    },
    patchConversation: (id, patch) => {
      set((state) => ({
        conversations: sortConversations(state.conversations.map((item) =>
          item.conversationId === id ? { ...item, ...patch } : item
        ))
      }))
    },
    removeConversation: (id) => set((state) => ({ conversations: state.conversations.filter((item) => item.conversationId !== id) })),
    setTotalUnread: (totalUnread) => set({ totalUnread })
  }
})

setUnauthorizedHandler((_code, message) => {
  useAppStore.getState().expireSession(message)
})
