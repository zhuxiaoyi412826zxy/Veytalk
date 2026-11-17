import { useEffect, useState } from 'react'
import { Image, View, type ImageProps } from '@tarojs/components'
import classNames from 'classnames'
import { resolveUrl } from '@/config/runtime'
import { fileApi } from '@/services/api'
import styles from './index.module.scss'

interface RemoteImageProps extends Omit<ImageProps, 'src' | 'onError'> {
  src?: string
  onError?: () => void
}

export default function RemoteImage({ src, onError, ...props }: RemoteImageProps) {
  const [url, setUrl] = useState('')
  const [failed, setFailed] = useState(false)

  useEffect(() => {
    let active = true
    setFailed(false)
    setUrl('')
    if (!src) return () => { active = false }
    const match = src.match(/\/api\/file\/download\/(\d+)/)
    if (!match) {
      setUrl(resolveUrl(src))
      return () => { active = false }
    }
    fileApi.signedUrl(match[1])
      .then((signed) => {
        if (active) setUrl(resolveUrl(signed))
      })
      .catch(() => {
        if (active) {
          setFailed(true)
          onError?.()
        }
      })
    return () => { active = false }
  }, [src])

  const className = classNames(styles.remoteImage, props.className)
  if (!src || failed || !url) return <View className={className} style={props.style} />
  return (
    <Image
      {...props}
      className={className}
      src={url}
      onError={() => {
        setFailed(true)
        onError?.()
      }}
    />
  )
}
