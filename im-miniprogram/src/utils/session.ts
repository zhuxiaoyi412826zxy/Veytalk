import Taro from '@tarojs/taro'
import type { LoginResult, UserInfo } from '@/types/im'

const TOKEN_KEY = 'veytalk.token'
const TOKEN_NAME_KEY = 'veytalk.tokenName'
const USER_KEY = 'veytalk.user'
const DEFAULT_TOKEN_NAME = 'satoken'

export function getToken(): string {
  return (Taro.getStorageSync(TOKEN_KEY) as string) || ''
}

export function getTokenName(): string {
  return (Taro.getStorageSync(TOKEN_NAME_KEY) as string) || DEFAULT_TOKEN_NAME
}

export function getCachedUser(): UserInfo | null {
  const user = Taro.getStorageSync(USER_KEY) as UserInfo | ''
  return user && typeof user === 'object' ? user : null
}

export function saveLogin(result: LoginResult): void {
  Taro.setStorageSync(TOKEN_KEY, result.token)
  Taro.setStorageSync(TOKEN_NAME_KEY, result.tokenName || DEFAULT_TOKEN_NAME)
  Taro.setStorageSync(USER_KEY, result.userInfo)
}

export function saveUser(user: UserInfo): void {
  Taro.setStorageSync(USER_KEY, user)
}

export function clearSession(): void {
  Taro.removeStorageSync(TOKEN_KEY)
  Taro.removeStorageSync(TOKEN_NAME_KEY)
  Taro.removeStorageSync(USER_KEY)
}
