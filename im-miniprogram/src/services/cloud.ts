import Taro from '@tarojs/taro'

export async function callFunction<T = unknown>(
  name: string,
  data?: Record<string, unknown>
): Promise<T> {
  if (process.env.TARO_ENV !== 'weapp') {
    const mockModule = await import(`../data/${name}`)
    return mockModule.default(data) as T
  }
  const res = await Taro.cloud.callFunction({ name, data })
  const result = res.result as { code: number; message: string; data: T }
  if (result.code !== 0) {
    console.error(`[Cloud] ${name} failed:`, result.message)
    throw new Error(result.message || '请求失败')
  }
  return result.data
}

export function getDatabase() {
  if (process.env.TARO_ENV !== 'weapp') {
    return null
  }
  return Taro.cloud.database()
}
