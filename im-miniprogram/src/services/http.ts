import Taro from '@tarojs/taro'
import { getApiBase } from '@/config/runtime'
import type { ApiEnvelope, Query } from '@/types/im'
import { createTraceId } from '@/utils/format'
import { getToken, getTokenName } from '@/utils/session'

export class ApiError extends Error {
  code: number
  traceId: string

  constructor(code: number, message: string, traceId = '') {
    super(message)
    // H5/小程序编译降级后 extends Error 的原型链会断，导致 instanceof 与 message 一起丢失
    Object.setPrototypeOf(this, ApiError.prototype)
    this.name = 'ApiError'
    this.code = code
    this.traceId = traceId
  }
}

export interface RequestOptions {
  method?: 'GET' | 'POST' | 'PUT' | 'DELETE' | 'PATCH'
  data?: unknown
  query?: Query
  auth?: boolean
}

type UnauthorizedHandler = (code: number, message: string) => void
let unauthorizedHandler: UnauthorizedHandler | null = null

export function setUnauthorizedHandler(handler: UnauthorizedHandler | null): void {
  unauthorizedHandler = handler
}

function isEnvelope<T>(value: unknown): value is ApiEnvelope<T> {
  return Boolean(value) && typeof value === 'object' && typeof (value as ApiEnvelope<T>).code === 'number'
}

function responseMessage(value: unknown): string {
  if (isEnvelope<unknown>(value)) return value.message || value.msg || ''
  return ''
}

/**
 * 提取请求失败的真实原因。
 * 小程序端 Taro.request 失败时给的是 { errMsg: 'request:fail ...' }，
 * 直接套用兜底文案会把「url not in domain list」「ssl hand shake error」这类关键信息吞掉。
 */
function requestFailureMessage(error: unknown): string {
  if (error instanceof Error && error.message) return error.message
  if (error && typeof error === 'object') {
    const detail = (error as { errMsg?: unknown }).errMsg
    if (typeof detail === 'string' && detail) return detail
  }
  return '网络连接失败，请检查服务器地址'
}

export async function request<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const token = getToken()
  const header: Record<string, string> = {
    'Content-Type': 'application/json',
    'X-Trace-Id': createTraceId()
  }
  if (options.auth !== false && token) header[getTokenName()] = token
  const query = options.query || {}
  const url = `${getApiBase()}${path.startsWith('/') ? path : `/${path}`}`

  let response: Taro.request.ApiResponse<unknown>
  try {
    response = await Taro.request({
      url,
      method: options.method || 'GET',
      data: (options.method === 'GET' || !options.data ? query : options.data) as object,
      header,
      timeout: 20000
    })
  } catch (error) {
    // Taro 的失败对象是 { errMsg }，不是 Error 实例；保留原文才能区分「域名未配置」与「网络不通」
    console.error('[HTTP] request failed', options.method || 'GET', url, error)
    const message = requestFailureMessage(error)
    throw new ApiError(-1, message)
  }

  const body = response.data
  if (response.statusCode < 200 || response.statusCode >= 300) {
    throw new ApiError(response.statusCode, responseMessage(body) || `请求失败（HTTP ${response.statusCode}）`)
  }
  if (!isEnvelope<T>(body)) throw new ApiError(-1, '服务器返回了无法识别的响应')
  if (body.code !== 200) {
    const message = body.message || body.msg || '请求失败'
    if (body.code === 1002 || body.code === 2010) unauthorizedHandler?.(body.code, message)
    throw new ApiError(body.code, message, body.traceId || '')
  }
  return body.data
}

export function get<T>(path: string, query?: Query, auth = true): Promise<T> {
  return request<T>(path, { query, auth })
}

export function post<T>(path: string, data?: unknown, query?: Query, auth = true): Promise<T> {
  return request<T>(path, { method: 'POST', data, query, auth })
}

export function put<T>(path: string, data?: unknown, auth = true): Promise<T> {
  return request<T>(path, { method: 'PUT', data, auth })
}

export function del<T>(path: string, auth = true): Promise<T> {
  return request<T>(path, { method: 'DELETE', auth })
}
