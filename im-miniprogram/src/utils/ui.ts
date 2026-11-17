import Taro from '@tarojs/taro'
import { resolveUrl } from '@/config/runtime'
import { fileApi } from '@/services/api'
import type { MessageExtra } from '@/types/im'
import { errorMessage } from './format'

export function showError(error: unknown, fallback = '操作失败'): void {
  Taro.showToast({ title: errorMessage(error, fallback), icon: 'none', duration: 2500 })
}

export async function confirm(content: string, title = '确认操作'): Promise<boolean> {
  const result = await Taro.showModal({ title, content })
  return result.confirm
}

export function openChat(conversationId: string, title = '聊天'): void {
  Taro.navigateTo({ url: `/pages/chat/index?id=${encodeURIComponent(conversationId)}&title=${encodeURIComponent(title)}` })
}

export async function signedFileUrl(fileId?: string, path?: string): Promise<string> {
  if (fileId) return resolveUrl(await fileApi.signedUrl(fileId))
  return resolveUrl(path)
}

/**
 * 把受控下载地址 /api/file/download/{fileId} 换成带票据的直链。
 * Video / Image 这类组件无法携带 satoken 请求头，直接喂鉴权地址只会拿到 403 响应，
 * 播放器表现为 MEDIA_ERR_DECODE 一类的解码失败。
 */
export async function playableMediaUrl(path?: string): Promise<string> {
  if (!path) return ''
  const match = path.match(/\/api\/file\/download\/(\d+)/)
  if (!match) return resolveUrl(path)
  try {
    return resolveUrl(await fileApi.signedUrl(match[1]))
  } catch (error) {
    console.error('[Media] signed url failed', error)
    return resolveUrl(path)
  }
}

export async function previewRemoteImage(fileId?: string, path?: string): Promise<void> {
  const url = await signedFileUrl(fileId, path)
  if (!url) throw new Error('图片地址不可用')
  await Taro.previewImage({ urls: [url] })
}

export async function openRemoteFile(extra: MessageExtra): Promise<void> {
  const url = await signedFileUrl(extra.fileId, extra.fileUrl)
  if (!url) throw new Error('文件地址不可用')
  const result = await Taro.downloadFile({ url })
  if (result.statusCode < 200 || result.statusCode >= 300) throw new Error('文件下载失败')
  await Taro.openDocument({ filePath: result.tempFilePath, showMenu: true })
}
