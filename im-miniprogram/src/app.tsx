import type { PropsWithChildren } from 'react'
import { useEffect } from 'react'
import Taro, { useDidHide, useDidShow } from '@tarojs/taro'
import { installRealtime } from '@/services/realtime'
import { connect, disconnect } from '@/services/websocket'
import { getToken } from '@/utils/session'
import './app.scss'

function App({ children }: PropsWithChildren) {
  useEffect(() => installRealtime(), [])

  useDidShow(() => {
    if (getToken()) void connect()
  })

  useDidHide(() => disconnect())

  return children
}

export default App
