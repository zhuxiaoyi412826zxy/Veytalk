export type Id = string
export type Query = Record<string, string | number | boolean | undefined>

export interface ApiEnvelope<T> {
  code: number
  success?: boolean
  message?: string
  msg?: string
  data: T
  traceId?: string
}

export interface PageResult<T> {
  records: T[]
  total?: number
  current?: number
  size?: number
  pages?: number
}

export interface UserInfo {
  id: Id
  userId?: Id
  username: string
  nickname: string
  avatar?: string
  phone?: string
  email?: string
  signature?: string
  gender?: number
  status?: number
  online?: boolean
}

export interface LoginResult {
  token: string
  tokenName: string
  userInfo: UserInfo
}

export interface CaptchaInfo {
  captchaKey: string
  image: string
}

export interface Conversation {
  conversationId: Id
  type?: number
  targetId?: Id
  name?: string
  title?: string
  avatar?: string
  lastMsgContent?: string
  lastMessage?: Message
  lastMsgType?: number
  lastMsgTime?: string | number
  lastMsgSenderName?: string
  unreadCount?: number
  top?: boolean
  isTop?: boolean
  muted?: boolean
  isMuted?: boolean
  topTime?: string | number
  atFlag?: boolean
  online?: boolean
  memberCount?: number
  userId?: Id
  groupId?: Id
}

export interface MessageExtra {
  fileId?: Id
  fileName?: string
  fileUrl?: string
  fileSize?: number
  contentType?: string
  ext?: string
  width?: number
  height?: number
  duration?: number
}

export interface Message {
  messageId: Id
  clientMsgId?: string
  conversationId: Id
  fromUserId: Id
  msgType: number
  content: string
  extra?: MessageExtra | string
  seq?: number | string
  status?: number
  sendTime?: string | number
  self?: boolean
  recalled?: boolean
  senderName?: string
  senderAvatar?: string
  fromNickname?: string
  fromAvatar?: string
  sending?: boolean
  failed?: boolean
}

export interface Friend {
  userId?: Id
  friendId: Id
  username?: string
  nickname: string
  displayName?: string
  avatar?: string
  signature?: string
  remark?: string
  groupName?: string
  online?: boolean
}

export interface FriendRequest {
  id: Id
  requestId?: Id
  fromUserId?: Id
  toUserId?: Id
  peerUserId?: Id
  peerUsername?: string
  peerNickname?: string
  peerAvatar?: string
  direction?: string
  verifyMessage?: string
  actionable?: boolean
  status?: number
  statusDesc?: string
  createTime?: string | number
}

export interface FriendGroup {
  groupId?: Id
  name: string
  count?: number
}

export interface GroupMember {
  userId: Id
  username?: string
  nickname?: string
  displayName?: string
  nicknameInGroup?: string
  groupNickname?: string
  avatar?: string
  role?: number
  online?: boolean
}

export interface GroupInfo {
  groupId: Id
  name: string
  avatar?: string
  announcement?: string
  notice?: string
  description?: string
  ownerId?: Id
  inviteCode?: string
  memberCount?: number
  myRole?: number
  myGroupNickname?: string
  myNickname?: string
  conversationId?: Id
  members?: GroupMember[]
}

export interface FileInfo {
  fileId: Id
  originalName?: string
  fileName?: string
  url?: string
  size?: number
  contentType?: string
  ext?: string
  duration?: number
}

export interface LiveRoom {
  id: Id
  roomId?: Id
  title: string
  cover?: string
  anchorName?: string
  anchorAvatar?: string
  status?: number
  viewerCount?: number
  onlineCount?: number
  likeCount?: number
  playUrl?: string
  danmakuWs?: string
  hasReplay?: boolean
  unlocked?: boolean
  mine?: boolean
  notice?: string
  gate?: { type?: string; price?: number }
  locked?: boolean
  needPassword?: boolean
  needPay?: boolean
  price?: number
  replayUrl?: string
}

export interface VideoItem {
  id: Id
  videoId?: Id
  title: string
  cover?: string
  coverUrl?: string
  videoUrl?: string
  fileUrl?: string
  url?: string
  authorName?: string
  uploaderName?: string
  uploaderAvatar?: string
  authorAvatar?: string
  playCount?: number
  likeCount?: number
  duration?: number
  mine?: boolean
  createTime?: string | number
}

export interface Article {
  id: Id
  authorId?: Id
  authorName?: string
  authorAvatar?: string
  title: string
  summary?: string
  content?: string
  coverUrl?: string
  coverFileId?: Id
  status?: number
  rejectReason?: string
  viewCount?: number
  publishTime?: string | number
  createTime?: string | number
  mine?: boolean
}

export interface CallConfig {
  enabled?: boolean
  iceServers?: Array<{ urls?: string | string[]; username?: string; credential?: string }>
  groupEnabled?: boolean
  groupMaxSlots?: number
}

export interface AiStatus {
  enabled?: boolean
  model?: string
  knowledgeBaseReady?: boolean
  asrEnabled?: boolean
  ttsEnabled?: boolean
  message?: string
}

export interface AiChatMessage {
  role: 'user' | 'assistant' | 'system'
  content: string
}

export interface AiChatRequest {
  messages: AiChatMessage[]
}

export interface WsEvent<T = unknown> {
  type: string
  action?: string
  clientMsgId?: string
  data?: T
  timestamp?: number
  code?: number
  message?: string
}

export interface UnreadData {
  total?: number
  totalUnread?: number
  conversationId?: Id
  unreadCount?: number
}

export interface OnlineStateData {
  userId: Id
  online: boolean
}

export interface NotifyData {
  bizType?: string
  biz?: string
  action?: string
  heartbeatSeconds?: number
  content?: string
  [key: string]: unknown
}
