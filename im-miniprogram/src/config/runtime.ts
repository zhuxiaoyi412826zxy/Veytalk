import Taro from '@tarojs/taro'

const SERVER_KEY = 'veytalk.server'
export const DEFAULT_SERVER = 'https://im.dsaol.asia'
export const DEVICE_ID = 'mini'

export function normalizeServer(value: string): string {
  return value.trim().replace(/\/+$/, '')
}

/**
 * 是否为纯 IP 地址形式的服务器。
 * 线上证书为 CN=im.dsaol.asia，未把 IP 写进 SAN，用 IP 直连一律
 * ERR_CERT_COMMON_NAME_INVALID；微信小程序本身也不接受 IP 作为合法域名。
 */
export function isIpServer(value: string): boolean {
  return /^https?:\/\/\d{1,3}(\.\d{1,3}){3}(:\d+)?$/i.test(normalizeServer(value))
}

export function getServer(): string {
  const saved = Taro.getStorageSync(SERVER_KEY) as string
  const stored = saved ? normalizeServer(saved) : ''
  // 历史脏配置可能存了 IP，就地清掉并回退域名，否则每次请求都会卡在证书校验
  if (stored && isIpServer(stored)) {
    Taro.removeStorageSync(SERVER_KEY)
    return DEFAULT_SERVER
  }
  return stored || DEFAULT_SERVER
}

export function setServer(value: string): string {
  const server = normalizeServer(value)
  if (isIpServer(server)) return getServer()
  Taro.setStorageSync(SERVER_KEY, server)
  return server
}

export function resetServer(): void {
  Taro.removeStorageSync(SERVER_KEY)
}

export function getApiBase(): string {
  return `${getServer()}/api`
}

export function getWsBase(): string {
  return getServer().replace(/^http:/, 'ws:').replace(/^https:/, 'wss:')
}

export function resolveUrl(path?: string): string {
  if (!path) return ''
  if (/^(https?:|wss?:|data:|blob:|wxfile:|file:|\/\/)/.test(path)) return path
  return `${getServer()}${path.startsWith('/') ? path : `/${path}`}`
}
