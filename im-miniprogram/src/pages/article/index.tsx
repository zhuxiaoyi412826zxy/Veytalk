import { useEffect, useState } from 'react'
import { Text, View } from '@tarojs/components'
import Taro, { useRouter } from '@tarojs/taro'
import EmptyState from '@/components/EmptyState'
import Loading from '@/components/Loading'
import PageHeader from '@/components/PageHeader'
import RemoteImage from '@/components/RemoteImage'
import { articleApi } from '@/services/api'
import type { Article } from '@/types/im'
import { formatTime } from '@/utils/format'
import { showError } from '@/utils/ui'
import styles from './index.module.scss'

type Block =
  | { kind: 'h1' | 'h2' | 'h3' | 'quote' | 'li' | 'p'; text: string }
  | { kind: 'img'; src: string }

/** 轻量 Markdown 解析：只覆盖标题、引用、列表、图片与段落，避免引入额外依赖 */
function parseMarkdown(source: string): Block[] {
  return source.split(/\r?\n/).reduce<Block[]>((blocks, raw) => {
    const line = raw.trim()
    if (!line) return blocks
    const image = line.match(/^!\[[^\]]*\]\(([^)]+)\)$/)
    if (image) { blocks.push({ kind: 'img', src: image[1] }); return blocks }
    if (line.startsWith('### ')) { blocks.push({ kind: 'h3', text: line.slice(4) }); return blocks }
    if (line.startsWith('## ')) { blocks.push({ kind: 'h2', text: line.slice(3) }); return blocks }
    if (line.startsWith('# ')) { blocks.push({ kind: 'h1', text: line.slice(2) }); return blocks }
    if (line.startsWith('> ')) { blocks.push({ kind: 'quote', text: line.slice(2) }); return blocks }
    if (/^[-*] /.test(line)) { blocks.push({ kind: 'li', text: line.slice(2) }); return blocks }
    blocks.push({ kind: 'p', text: line })
    return blocks
  }, [])
}

export default function ArticlePage() {
  const router = useRouter()
  const id = router.params.id || ''
  const [article, setArticle] = useState<Article | null>(null)
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    const load = async (): Promise<void> => {
      if (!id) { setLoading(false); return }
      try {
        setArticle(await articleApi.detail(id))
      } catch (error) {
        console.error('[Article] detail failed', error)
        showError(error, '文章加载失败')
      } finally { setLoading(false) }
    }
    void load()
  }, [id])

  const copy = (): void => {
    if (!article) return
    void Taro.setClipboardData({ data: article.title }).catch((error: unknown) => console.error('[Article] copy failed', error))
  }

  const blocks = article?.content ? parseMarkdown(article.content) : []

  return <View className={styles.page}>
    <PageHeader title='文章' subtitle='阅读与分享' />
    {loading ? <Loading text='正在加载文章…' /> : !article ? <EmptyState title='文章不存在' description='它可能已被删除或尚未发布' /> : <View className={styles.body}>
      <Text className={styles.title}>{article.title}</Text>
      <View className={styles.meta}>
        <Text>{article.authorName || '作者'}</Text>
        <Text>{formatTime(article.publishTime || article.createTime)}</Text>
        <Text>{article.viewCount || 0} 次阅读</Text>
      </View>
      {article.rejectReason ? <Text className={styles.reject}>审核未通过：{article.rejectReason}</Text> : null}
      {article.coverUrl ? <RemoteImage className={styles.cover} src={article.coverUrl} mode='aspectFill' /> : null}
      {article.summary ? <Text className={styles.summary}>{article.summary}</Text> : null}
      <View className={styles.content}>
        {blocks.length ? blocks.map((block, index) => {
          const key = `${block.kind}-${index}`
          if (block.kind === 'img') return <RemoteImage className={styles.image} key={key} src={block.src} mode='widthFix' />
          if (block.kind === 'h1') return <Text className={styles.h1} key={key}>{block.text}</Text>
          if (block.kind === 'h2') return <Text className={styles.h2} key={key}>{block.text}</Text>
          if (block.kind === 'h3') return <Text className={styles.h3} key={key}>{block.text}</Text>
          if (block.kind === 'quote') return <Text className={styles.quote} key={key}>{block.text}</Text>
          if (block.kind === 'li') return <Text className={styles.li} key={key}>· {block.text}</Text>
          return <Text className={styles.paragraph} key={key}>{block.text}</Text>
        }) : <Text className={styles.paragraph}>这篇文章暂无正文。</Text>}
      </View>
      <View className={styles.actions}><Text className={styles.action} onClick={copy}>复制标题</Text></View>
    </View>}
  </View>
}
