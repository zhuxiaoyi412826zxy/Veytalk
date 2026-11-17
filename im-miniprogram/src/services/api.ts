import { DEVICE_ID } from '@/config/runtime'
import type {
  AiChatMessage,
  AiStatus,
  Article,
  CallConfig,
  CaptchaInfo,
  Conversation,
  FileInfo,
  Friend,
  FriendGroup,
  FriendRequest,
  GroupInfo,
  GroupMember,
  LiveRoom,
  LoginResult,
  Message,
  PageResult,
  UserInfo,
  VideoItem
} from '@/types/im'
import { del, get, post, put } from './http'

export const authApi = {
  login(account: string, password: string) {
    return post<LoginResult>('/auth/login', { loginType: 'username', account, password, deviceId: DEVICE_ID }, undefined, false)
  },
  loginBySms(phone: string, smsCode: string) {
    return post<LoginResult>('/auth/login/sms', { phone, smsCode, deviceId: DEVICE_ID }, undefined, false)
  },
  register(data: { username: string; password: string; nickname?: string; phone?: string; email?: string }) {
    return post<LoginResult>('/auth/register', { ...data, deviceId: DEVICE_ID }, undefined, false)
  },
  me: () => get<UserInfo>('/auth/me'),
  logout: () => post<void>('/auth/logout'),
  refresh: () => post<LoginResult>('/auth/refresh'),
  captcha: () => get<CaptchaInfo>('/captcha/image', undefined, false),
  sendSms(data: { phone: string; scene: string; captchaKey: string; captchaCode: string }) {
    return post<{ debugCode?: string; retryAfter?: number; expiresIn?: number }>('/captcha/sms', data, undefined, false)
  },
  wsTicket: () => post<{ endpoint: string; ticket: string }>('/ws/ticket', undefined, { deviceId: DEVICE_ID }),
  qrScanned: (scene: string) => post<void>('/auth/qr-login/scanned', { scene }),
  qrAuthorize: (scene: string) => post<void>('/auth/qr-login/authorize', { scene })
}

export const conversationApi = {
  list: () => get<Conversation[]>('/conversation/list'),
  detail: (id: string) => get<Conversation>(`/conversation/${id}`),
  single: (targetUserId: string) => post<Conversation>('/conversation/single', { targetUserId }),
  assistant: () => post<string>('/conversation/assistant'),
  read: (id: string, lastAckSeq?: string) => put<void>(`/conversation/${id}/read`, { lastAckSeq }),
  top: (id: string, enabled: boolean) => put<void>(`/conversation/${id}/top`, { enabled }),
  mute: (id: string, enabled: boolean) => put<void>(`/conversation/${id}/mute`, { enabled }),
  remove: (id: string) => del<void>(`/conversation/${id}`),
  unreadTotal: () => get<number>('/conversation/unread/total')
}

export const messageApi = {
  send(data: {
    clientMsgId: string
    msgType: number
    content: string
    conversationId: string
    extra?: unknown
  }) {
    return post<Message>('/message/send', data)
  },
  history: (conversationId: string, beforeSeq?: string, size = 30) =>
    get<Message[]>('/message/history', { conversationId, beforeSeq, size }),
  offline: () => get<Message[]>('/message/offline'),
  export: (conversationId: string) => get<Message[]>('/message/export', { conversationId }),
  read: (conversationId: string, maxSeq?: string) => put<void>('/message/read', { conversationId, maxSeq }),
  delivered: (messageIds: string[]) => post<void>('/message/delivered', { messageIds }),
  recall: (id: string) => put<void>(`/message/${id}/recall`),
  remove: (id: string) => del<void>(`/message/${id}`)
}

export const friendApi = {
  list: (keyword?: string) => get<Friend[]>('/friend/list', { keyword }),
  groups: () => get<FriendGroup[]>('/friend/groups'),
  card: (friendId: string) => get<Friend>(`/friend/${friendId}/card`),
  apply: (data: { targetUserId?: string; targetAccount?: string; verifyMessage?: string }) =>
    post<void>('/friend/request/apply', { ...data, source: 'mini' }),
  received: () => get<FriendRequest[]>('/friend/request/received'),
  sent: () => get<FriendRequest[]>('/friend/request/sent'),
  pendingCount: () => get<number>('/friend/request/pending-count'),
  accept: (id: string) => put<string>(`/friend/request/${id}/accept`),
  reject: (id: string) => put<void>(`/friend/request/${id}/reject`),
  remove: (friendId: string) => del<void>(`/friend/${friendId}`)
}

export const groupApi = {
  my: () => get<GroupInfo[]>('/group/my'),
  detail: (id: string) => get<GroupInfo>(`/group/${id}`),
  create: (data: { name: string; notice?: string; memberIds?: string[] }) => post<GroupInfo>('/group/create', data),
  members: (id: string, current = 1, size = 100) => get<PageResult<GroupMember>>(`/group/${id}/members`, { current, size }),
  join: (inviteCode: string, verifyMessage?: string) => post<GroupInfo>('/group/join', { inviteCode, verifyMessage }),
  quit: (id: string) => post<void>(`/group/${id}/quit`),
  update: (id: string, data: { name?: string; notice?: string; avatar?: string }) => put<void>(`/group/${id}`, data),
  addMembers: (id: string, userIds: string[]) => post<string[]>(`/group/${id}/members`, { userIds }),
  removeMember: (id: string, userId: string) => del<void>(`/group/${id}/members/${userId}`),
  updateMyNickname: (id: string, nicknameInGroup: string) => put<void>(`/group/${id}/my-nickname`, { nicknameInGroup })
}

export const fileApi = {
  signedUrl: (id: string) => get<string>(`/file/${id}/url`)
}

export const liveApi = {
  page: (current = 1, size = 20, status?: number) => get<PageResult<LiveRoom>>('/live/page', { current, size, status }),
  room: (id: string) => get<LiveRoom>(`/live/${id}`),
  unlock: (id: string, password: string) => post<LiveRoom>(`/live/${id}/unlock`, { password }),
  pay: (id: string) => post<LiveRoom>(`/live/${id}/pay`),
  replay: (id: string) => get<{ playUrl?: string }>(`/live/${id}/replay`),
  videoPage: (current = 1, size = 10) => get<PageResult<VideoItem>>('/live/video/page', { current, size }),
  createVideo: (data: {
    title: string
    fileId: string
    coverFileId?: string
    duration?: number
    width?: number
    height?: number
    size?: number
    ext?: string
    contentType?: string
  }) => post<VideoItem>('/live/video', data),
  deleteVideo: (id: string) => del<void>(`/live/video/${id}`),
  reportVideoPlay: (id: string) => post<void>(`/live/video/${id}/play`)
}

export const aiApi = {
  status: () => get<AiStatus>('/ai/interview/status')
}

export const articleApi = {
  page: (current = 1, size = 12) => get<PageResult<Article>>('/article/page', { current, size }),
  mine: (current = 1, size = 12) => get<PageResult<Article>>('/article/mine', { current, size }),
  detail: (id: string) => get<Article>(`/article/${id}`)
}

export const callApi = {
  config: () => get<CallConfig>('/call/config')
}

export const userApi = {
  updateProfile: (data: { nickname?: string; avatar?: string }) => put<void>('/user/profile', data),
  changePassword: (oldPassword: string, newPassword: string, logoutAll = true) =>
    put<void>('/user/password', { oldPassword, newPassword, logoutAll }),
  cancelAccount: (password: string) => post<void>('/user/cancel', { password })
}

export type { AiChatMessage }
