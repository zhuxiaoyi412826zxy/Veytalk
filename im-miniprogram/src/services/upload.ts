import Taro from '@tarojs/taro'
import { getApiBase } from '@/config/runtime'
import type { ApiEnvelope, FileInfo } from '@/types/im'
import { createTraceId } from '@/utils/format'
import { getToken, getTokenName } from '@/utils/session'
import { ApiError } from './http'

export async function uploadFile(
  filePath: string,
  bizType: 'avatar' | 'chat_image' | 'chat_file' | 'chat_voice' | 'video' | 'video_cover' | 'article_cover' | 'article_img',
  duration?: number
): Promise<FileInfo> {
  const header: Record<string, string> = { 'X-Trace-Id': createTraceId() }
  const token = getToken()
  if (token) header[getTokenName()] = token
  const query = [`bizType=${encodeURIComponent(bizType)}`]
  if (duration !== undefined) query.push(`duration=${duration}`)

  let response: Taro.uploadFile.ApiResponse
  try {
    response = await Taro.uploadFile({
      url: `${getApiBase()}/file/upload?${query.join('&')}`,
      filePath,
      name: 'file',
      header,
      timeout: 60000
    })
  } catch (error) {
    throw new ApiError(-1, error instanceof Error ? error.message : '文件上传失败')
  }

  let body: ApiEnvelope<FileInfo>
  try {
    body = JSON.parse(response.data) as ApiEnvelope<FileInfo>
  } catch {
    throw new ApiError(response.statusCode, '上传响应无法解析')
  }
  if (response.statusCode < 200 || response.statusCode >= 300 || body.code !== 200) {
    throw new ApiError(body.code || response.statusCode, body.message || body.msg || '文件上传失败', body.traceId || '')
  }
  if (!body.data?.fileId) throw new ApiError(-1, '上传成功但未返回文件 ID')
  return body.data
}
