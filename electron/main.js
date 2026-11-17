const { app, BrowserWindow, Menu, session, dialog, ipcMain, Notification, Tray, nativeImage, globalShortcut } = require('electron')
const path = require('path')
const os = require('os')
const fs = require('fs')
const { spawn, execFile } = require('child_process')
const { setupLocalDb } = require('./localdb')
const { setupDirectBridge, closeDirectLink } = require('./direct')
const { setupRecorder, closeRecording } = require('./recorder')
const { setupLiveBridge, closeLivePush } = require('./live')
const { setupServerConfig, resolve: resolveServerBase, resolveWithProbe, BUILT_IN_BASE, BUILT_IN_FALLBACK_BASE } = require('./server-config')

/**
 * 远程后端根地址（协议 + host + 端口，不含 /api、不含 /ws）。
 *
 * Electron 桌面端不再像浏览器那样与后端同源（没有 vite proxy / Nginx 反代），
 * 前端必须拿到后端的绝对地址才能发请求。这个值经 preload 用 additionalArguments
 * 注入到渲染进程的 window.__IM_SERVER__.baseUrl，前端 utils/env.js 据此拼 /api 与 ws。
 *
 * ★ 地址现在不再烤死在本文件里，而是运行期三层回落（见 server-config.js）：
 *   userData/server-config.json → 环境变量 IM_SERVER_BASE → 内置默认 https://im.dsaol.asia。
 *   登录页的「开发调试」入口与设置页的「后端地址」行改的就是第一层，
 *   所以分发给别人之后换后端不必重打包；内置默认值保留是为了开发机本机起后端时零配置可用。
 */

/**
 * 主窗口引用。托盘、通知点击、任务栏闪烁都要拿到它，
 * 而 BrowserWindow.getAllWindows() 在「关到托盘」之后会空掉（窗口只是 hide 不是 close），
 * 靠它去找会把新消息提醒发给一个不存在的目标。
 */
let mainWindow = null
/** 只有真正要退出进程时才让 close 放行（托盘菜单的「退出」与 app.quit()） */
let quitting = false
/** 托盘实例：创建失败（无图标可用）时为 null，那时关窗口就按老行为直接退进程 */
let tray = null

/* ------------------------------ 证书定向白名单 ------------------------------ */

/**
 * 自签证书 + IP 回落的定向证书白名单：线上证书是自签的 CN=im.dsaol.asia（SAN 只含域名、不含 IP），
 * 没装根 CA 的公网机器连域名必报 authority-invalid，连 IP 再叠一层主机名不匹配。
 * 这里只放行「目标 host ∈ {域名, 回落 IP} 且证书 CN 正是域名」：等于把自家证书身份钉在客户端，
 * 别的身份的 MITM 证书仍走默认校验被拒，不是全局关证书校验。
 * WebSocket（wss）握手的证书错也走这个事件，一处注册 HTTP 与 WS 都覆盖。
 */
function setupCertWhitelist() {
  let domainHost = ''
  let fallbackHost = ''
  try {
    domainHost = new URL(BUILT_IN_BASE).hostname
  } catch { /* 内置值写坏时白名单不生效，忽略 */ }
  try {
    fallbackHost = new URL(BUILT_IN_FALLBACK_BASE).hostname
  } catch { /* 同上 */ }
  app.on('certificate-error', (event, webContents, url, error, certificate, callback) => {
    let host = ''
    try {
      host = new URL(url).hostname
    } catch {
      return callback(false)
    }
    const isOurHost = host === domainHost || host === fallbackHost
    // 只认两种可修复错：自签无 CA（authority）与连 IP 的主机名不匹配（common name）；
    // 过期、用途不对等错误照旧拒绝，白名单不能变成一律放行
    const fixable = error === 'net::ERR_CERT_AUTHORITY_INVALID' || error === 'net::ERR_CERT_COMMON_NAME_INVALID'
    const certIsOurs = domainHost !== '' && String((certificate && certificate.subjectName) || '').includes(domainHost)
    if (isOurHost && fixable && certIsOurs) {
      event.preventDefault()
      return callback(true)
    }
    return callback(false)
  })
}

function createWindow() {
  // 启动时解析一次，作为 additionalArguments 的兜底值；页面加载时的实时值由 preload
  // 走 im:server-resolve 同步通道现取（additionalArguments 是建窗口时定死的，reload 不会刷新它）
  const server = resolveServerBase()
  const win = new BrowserWindow({
    width: 1200,
    height: 800,
    minWidth: 900,
    minHeight: 600,
    title: 'IM 即时通讯',
    backgroundColor: '#ffffff',
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      contextIsolation: true,
      nodeIntegration: false,
      /**
       * ★ 关掉后台节流。默认 true 时，窗口一隐藏/最小化，Chromium 就把页面里的定时器
       * 压到约 1 次/分钟——心跳发不出去，服务端 90 秒没收到就踢会话，前端那两周期后
       * 也自己判死，于是「切后台就收不到消息」。IM 长连接必须常年醒着，这一行是根因修复。
       * 代价是后台 CPU 占用略增（一个 WS 客户端的量级，可以忽略）。
       */
      backgroundThrottling: false,
      // 桌面端加载的是本地可信 dist，但要从 file:// 跨源访问远程后端（含带自定义头
      // satoken/X-Trace-Id 的预检请求）。关掉同源策略可免去后端 CORS 配置与预检失败问题；
      // 若后端已正确配置 CORS，可改回 true 获得更严格的安全边界。
      webSecurity: false,
      // 把后端地址透传给 preload，再由 contextBridge 暴露给渲染进程
      additionalArguments: [`--server-base=${server.baseUrl}`]
    }
  })

  Menu.setApplicationMenu(null)
  win.loadFile(path.join(__dirname, 'dist', 'index.html'))

  mainWindow = win
  // 点回窗口就停掉任务栏闪烁：闪烁只是「有条消息没看」的引子，人来了就该结束
  win.on('focus', () => {
    try {
      win.flashFrame(false)
    } catch { /* flashFrame 在部分 Linux 桌面环境上不可用 */ }
  })
  /**
   * 关窗口 = 收进托盘，进程与长连接继续跑。
   * 不给用户一个「关了窗口是不是就收不到消息了」的悬念——现在确实收得到，
   * 因为窗口只是 hide，WS 还开着（backgroundThrottling 已关）。
   * quitting 为真（托盘菜单退出 / 系统登出）或托盘没建起来时不拦——后者得让窗口真关掉，
   * 否则进程会无窗口、无托盘地挂在那里，只能从任务管理器杀。
   */
  win.on('close', (event) => {
    if (quitting || !tray) {
      return
    }
    event.preventDefault()
    win.hide()
  })
  win.on('closed', () => {
    if (mainWindow === win) {
      mainWindow = null
    }
  })
}

/* ------------------------------ 托盘 ------------------------------ */

/**
 * 自己画一枚 32x32 的托盘/角标图（聊天气泡 + 右上红点）。
 *
 * 为什么不放一张 png：本仓的 electron/ 目录里一张图标资源都没有（见 build.files 与
 * buildResources 目录），托盘图标又要跟着打包走；用 nativeImage.createFromBitmap 自己填
 * BGRA 像素阵只需十几行，换来的是「不必维护一个二进制资源、也不会因为文件丢了整个托盘建不起来」。
 * 真的需要品牌图标时，在 electron/tray.png 放一张就行，下面会优先用它。
 */
function trayIcon() {
  const file = path.join(__dirname, 'tray.png')
  if (fs.existsSync(file)) {
    const image = nativeImage.createFromPath(file)
    if (!image.isEmpty()) {
      return image.resize({ width: 32, height: 32 })
    }
  }
  const size = 32
  const buf = Buffer.alloc(size * size * 4)
  const inCircle = (x, y, cx, cy, r) => (x - cx) ** 2 + (y - cy) ** 2 <= r * r
  for (let y = 0; y < size; y++) {
    for (let x = 0; x < size; x++) {
      const i = (y * size + x) * 4
      let color = null
      if (inCircle(x, y, 24, 24, 7)) {
        color = [0x4a, 0x5c, 0xf5, 0xff] // 红点（BGRA）
      } else if (x >= 5 && x < 26 && y >= 7 && y < 22) {
        color = [0xff, 0xff, 0xff, 0xff] // 气泡主体
      } else if (x >= 9 && x < 16 && y >= 21 && y < 26) {
        color = [0xff, 0xff, 0xff, 0xff] // 气泡左下的小尾巴
      } else if (x >= 1 && x < 31 && y >= 1 && y < 31 && !inCircle(x, y, 3, 3, 3) && !inCircle(x, y, 29, 3, 3) &&
        !inCircle(x, y, 3, 29, 3) && !inCircle(x, y, 29, 29, 3)) {
        color = [0xff, 0x9e, 0x40, 0xff] // 底色 #409eff
      }
      if (color) {
        buf[i] = color[0]
        buf[i + 1] = color[1]
        buf[i + 2] = color[2]
        buf[i + 3] = color[3]
      }
    }
  }
  return nativeImage.createFromBitmap(buf, { width: size, height: size })
}

/** 把窗口唤到前台：托盘单击、通知点击、二次启动都走这里 */
function revealWindow() {
  const win = mainWindow
  if (!win || win.isDestroyed()) {
    createWindow()
    return
  }
  win.show()
  if (win.isMinimized()) {
    win.restore()
  }
  win.focus()
}

function setupTray() {
  if (tray) {
    return
  }
  const image = trayIcon()
  if (!image || image.isEmpty()) {
    // 画不出图标就整体不做托盘：宁可保持「关窗口 = 退进程」的旧行为，也不挂一个看不见的常驻进程
    console.warn('[tray] 图标不可用，不启用托盘常驻')
    return
  }
  try {
    tray = new Tray(image)
    tray.setToolTip('IM 即时通讯')
    tray.setContextMenu(
      Menu.buildFromTemplate([
        { label: '显示主窗口', click: revealWindow },
        { type: 'separator' },
        {
          label: '退出 IM',
          click: () => {
            quitting = true
            app.quit()
          }
        }
      ])
    )
    // 单击唤回窗口，双击也唤（有些用户习惯双击图标）
    tray.on('click', revealWindow)
    tray.on('double-click', revealWindow)
  } catch (error) {
    console.warn('[tray] 托盘创建失败，关窗口将直接退出进程', error)
    tray = null
  }
}

/* ------------------------------ 系统通知与任务栏角标 ------------------------------ */

/**
 * 提醒出口的主进程侧。
 *
 * 为什么通知要下沉到主进程而不是让渲染进程用 Web Notification：桌面壳加载的是 file:// 页面，
 * 非安全上下文下 Notification 拿不到权限；而主进程的 electron#Notification 是真正的系统通知，
 * 窗口隐藏时也能弹、点击能把窗口唤回来。渲染侧只负责判断该不该提醒（见 im-ui/src/utils/notify.js）。
 *
 * 上行都用 send 而不是 invoke：提醒是「发完就不管」的事件，
 * 拿不到回执也不该让渲染侧因一个 rejected Promise 而卡住或报错。
 */
function setupNotify() {
  ipcMain.on('im:notify', (event, payload) => {
    const title = (payload && payload.title) || '新消息'
    const body = (payload && payload.body) || ''
    const tag = (payload && payload.tag) || ''
    if (!Notification.isSupported()) {
      // Windows 上服务没起来、Linux 上没有 libnotify 时会走到这：
      // 没系统通知不等于什幺提醒都没有，让任务栏闪起来兼作兜底
      const win = mainWindow
      if (win && !win.isDestroyed() && !win.isFocused()) {
        try {
          win.flashFrame(true)
        } catch { /* 同上 */ }
      }
      return
    }
    try {
      const notification = new Notification({
        title,
        body,
        icon: trayIcon(),
        silent: !!(payload && payload.silent)
        // 去重与节流不做在这里：同一个会话 4 秒内只提醒一次是渲染侧的职责（utils/notify.js 的 buckets），
        // 主进程只负责「把这一条变成系统里看得见的东西」，不知道也不该知道业务上该不该响
      })
      notification.on('click', () => {
        revealWindow()
        const win = mainWindow
        if (win && !win.isDestroyed() && tag) {
          win.webContents.send('im:notify-click', tag)
        }
      })
      notification.show()
    } catch (error) {
      console.warn('[notify] 系统通知弹出失败', error)
    }
  })

  ipcMain.on('im:badge', (event, payload) => {
    const count = Math.max(0, Number(payload && payload.count) || 0)
    const win = mainWindow
    if (!win || win.isDestroyed()) {
      return
    }
    if (process.platform === 'darwin') {
      // macOS 有真正的 Dock 角标数字
      try {
        app.setBadgeCount(count)
      } catch { /* 部分系统版本不支持 */ }
    }
    if (process.platform === 'win32') {
      // Windows 任务栏没有数字角标 API，能做的只有叠加一个小图标 + 未读时闪烁；
      // 数字已经在 tooltip 里了，不把「有角标」当成「有数字」吹
      try {
        win.setOverlayIcon(count > 0 ? trayIcon() : null, count > 0 ? `${count} 条未读` : '')
        win.flashFrame(count > 0 && !win.isFocused())
      } catch { /* setOverlayIcon 在部分环境下不可用 */ }
    }
    if (count === 0) {
      try {
        win.flashFrame(false)
      } catch { /* 同上 */ }
    }
  })
}

/**
 * 文件下载处理。
 *
 * Electron 的渲染进程不会像浏览器那样自动接管 <a download> / 直链下载，
 * 不监听 will-download 的话，点下载会「没反应」（和手机端那个大文件下载 bug 同源）。
 * 这里弹系统保存框让用户选路径，交给 Chromium 下载器流式写盘，1GB 大文件也不占内存。
 */
function setupDownload() {
  session.defaultSession.on('will-download', (event, item) => {
    // 如果用户配置了固定下载目录，静默下载到那里（跳过对话框）
    if (desktopSettings.downloadDir) {
      const fileName = item.getFilename() || 'download'
      const savePath = path.join(desktopSettings.downloadDir, fileName)
      item.setSavePath(savePath)
      return
    }
    const savePath = dialog.showSaveDialogSync({
      title: '保存文件',
      defaultPath: item.getFilename() || 'download'
    })
    if (!savePath) {
      item.cancel()
      return
    }
    item.setSavePath(savePath)
  })
  // IPC：渲染进程选择下载目录
  ipcMain.handle('im:pick-download-dir', () => {
    const dir = dialog.showOpenDialogSync({ properties: ['openDirectory'] })
    return dir && dir[0] ? dir[0] : null
  })
}

/* ======================= 桌面端统一配置应用（代理 + 快捷键） =======================
 * 渲染进程在启动和设置变更时通过 im:apply-desktop-settings 把当前设置推送过来，
 * 主进程据此调整代理、注册/注销快捷键。
 */
const desktopSettings = {
  downloadDir: '',
  proxyEnabled: false,
  proxyUrl: '',
  shortcutMinimize: '',
  shortcutAlwaysOnTop: '',
  shortcutToggleVisible: ''
}

let shortcutsRegistered = false

function applyDesktopSettings(payload) {
  if (!payload) return
  Object.assign(desktopSettings, payload)
  // 代理
  applyProxy()
  // 快捷键
  registerShortcuts()
}

function applyProxy() {
  const ses = session.defaultSession
  if (desktopSettings.proxyEnabled && desktopSettings.proxyUrl) {
    ses.setProxy({ mode: 'fixed_servers', rules: desktopSettings.proxyUrl })
      .then(() => ses.resolveProxy('http://connectivitycheck.gstatic.com'))
      .then((result) => console.log('[proxy] active:', result))
      .catch((e) => console.warn('[proxy] error:', e))
  } else {
    ses.setProxy({ mode: 'system' })
      .then(() => console.log('[proxy] system default'))
      .catch(() => {})
  }
}

function registerShortcuts() {
  // 先全部注销再重新注册（幂等）
  globalShortcut.unregisterAll()
  shortcutsRegistered = false
  const win = mainWindow
  if (!win || win.isDestroyed()) return

  const map = [
    { key: desktopSettings.shortcutMinimize, action: () => win.minimize() },
    { key: desktopSettings.shortcutAlwaysOnTop, action: () => win.setAlwaysOnTop(!win.isAlwaysOnTop()) },
    { key: desktopSettings.shortcutToggleVisible, action: () => {
      if (win.isVisible() && !win.isMinimized()) { win.hide() } else { win.show(); win.focus() }
    }}
  ]
  for (const { key, action } of map) {
    if (!key) continue
    try {
      globalShortcut.register(key, action)
      shortcutsRegistered = true
    } catch { /* 被其它应用占用时忽略 */ }
  }
}

function setupDesktopConfig() {
  ipcMain.handle('im:apply-desktop-settings', (event, payload) => {
    applyDesktopSettings(payload)
    return { ok: true }
  })
}

/* ======================= 视频硬件压缩（调用本机原生 ffmpeg） =======================
 * 桌面端专属：渲染进程受 contextIsolation:true / nodeIntegration:false 限制，不能直接起子进程，
 * 所以压缩全在主进程做——渲染进程经 preload 把视频的真实磁盘路径发过来，这里 spawn 原生 ffmpeg，
 * 用 GPU 硬件编码器（NVENC/QuickSync/AMF）转成 720p MP4，缺对应 GPU/驱动时自动回落 libx264 软编。
 * ffmpeg 直接读写磁盘、流式处理，2GB/4K 也不占内存，这是浏览器端 ffmpeg.wasm 做不到的。
 * 找不到 ffmpeg 时压缩自动禁用，视频原片直传（和 Web 端一致），不影响其它功能。
 */

/**
 * 定位 ffmpeg 可执行文件，按优先级：
 *   1) 打包内置的 resources/ffmpeg/ffmpeg.exe（electron-builder 的 extraResources 拷进去的）
 *   2) 开发期 electron/bin/ffmpeg.exe
 *   3) 环境变量 IM_FFMPEG_PATH 显式指定
 *   4) 系统 PATH 里的 ffmpeg（用户自己装过）
 */
function resolveFfmpegPath() {
  const candidates = []
  if (process.resourcesPath) {
    candidates.push(path.join(process.resourcesPath, 'ffmpeg', 'ffmpeg.exe'))
  }
  candidates.push(path.join(__dirname, 'bin', 'ffmpeg.exe'))
  if (process.env.IM_FFMPEG_PATH) {
    candidates.push(process.env.IM_FFMPEG_PATH)
  }
  for (const c of candidates) {
    try {
      if (fs.existsSync(c)) return c
    } catch { /* 忽略单个候选探测失败 */ }
  }
  return 'ffmpeg' // 交给系统 PATH；若确实没有，spawn 会失败并被兜底成「压缩不可用」
}

/** libx264 软件编码参数，作为硬件编码不可用/失败时的兜底 */
const SOFTWARE_ENCODER = { name: 'libx264', hw: false, args: ['-c:v', 'libx264', '-preset', 'veryfast', '-crf', '28'] }

/** 选定编码器的缓存，避免每次压缩都重新探测 */
let encoderCache = null

/**
 * 探测可用编码器：优先 GPU 硬件（nvenc > qsv > amf），都没有则 libx264。
 * 注意 -encoders 只反映「编译时是否包含」，不代表本机一定装了对应 GPU/驱动，
 * 所以硬件编码在 runFfmpeg 运行失败时还会再回落一次软编（见 handler）。
 */
function detectEncoder(ffmpegPath) {
  if (encoderCache) return Promise.resolve(encoderCache)
  return new Promise((resolve) => {
    let settled = false
    const done = (v) => { if (!settled) { settled = true; encoderCache = v; resolve(v) } }
    try {
      const proc = execFile(ffmpegPath, ['-hide_banner', '-encoders'], { timeout: 8000 }, (err, stdout) => {
        if (err) return done(SOFTWARE_ENCODER)
        const out = String(stdout || '')
        if (/\bh264_nvenc\b/.test(out)) return done({ name: 'h264_nvenc', hw: true, args: ['-c:v', 'h264_nvenc', '-preset', 'medium', '-rc', 'vbr', '-cq', '28', '-b:v', '0'] }) // 旧式 preset+显式 CQ：Maxwell(GTX900)/Pascal 老卡也吃（新式 p1-p7 部分老驱动不认）
        if (/\bh264_qsv\b/.test(out)) return done({ name: 'h264_qsv', hw: true, args: ['-c:v', 'h264_qsv', '-global_quality', '28'] })
        if (/\bh264_amf\b/.test(out)) return done({ name: 'h264_amf', hw: true, args: ['-c:v', 'h264_amf', '-quality', 'balanced'] })
        return done(SOFTWARE_ENCODER)
      })
      proc.on('error', () => done(SOFTWARE_ENCODER))
    } catch { done(SOFTWARE_ENCODER) }
  })
}

/** 探测视频时长（秒），把 ffmpeg 的 out_time_ms 换算成百分比进度用。读不到返回 0（进度不精确，不影响结果）。 */
function probeDuration(ffmpegPath, inputPath) {
  return new Promise((resolve) => {
    let settled = false
    const done = (v) => { if (!settled) { settled = true; resolve(v) } }
    try {
      // 只给 -i 不给输出，ffmpeg 会以非 0 退出，但已把 Duration 打进 stderr
      const proc = execFile(ffmpegPath, ['-hide_banner', '-i', inputPath], { timeout: 15000 }, (err, stdout, stderr) => {
        const text = String(stderr || '') + String(stdout || '')
        const m = text.match(/Duration:\s*(\d+):(\d+):(\d+(?:\.\d+)?)/)
        done(m ? (+m[1] * 3600 + +m[2] * 60 + parseFloat(m[3])) : 0)
      })
      proc.on('error', () => done(0))
    } catch { done(0) }
  })
}

/**
 * 执行一次 ffmpeg 转码，resolve { code, stderrTail }；期间用 onProgress(percent) 回报进度。
 * 靠 -progress pipe:1 让 ffmpeg 把进度以 key=value 写进 stdout，逐行取 out_time_ms 换算百分比。
 */
function runFfmpeg(ffmpegPath, args, durationSec, onProgress) {
  return new Promise((resolve) => {
    let proc
    try {
      proc = spawn(ffmpegPath, args, { windowsHide: true })
    } catch (e) {
      return resolve({ code: -1, stderrTail: String((e && e.message) || e) })
    }
    let stderrTail = ''
    proc.stdout.on('data', (buf) => {
      const matches = buf.toString().match(/out_time_ms=(\d+)/g)
      if (matches && durationSec > 0) {
        const us = parseInt(matches[matches.length - 1].split('=')[1], 10)
        const pct = Math.max(0, Math.min(99, Math.round((us / 1e6 / durationSec) * 100)))
        if (onProgress) onProgress(pct)
      }
    })
    proc.stderr.on('data', (buf) => { stderrTail = (stderrTail + buf.toString()).slice(-2000) })
    proc.on('error', (e) => resolve({ code: -1, stderrTail: String((e && e.message) || e) }))
    proc.on('close', (code) => resolve({ code, stderrTail }))
  })
}

/** 注册视频压缩 IPC：渲染进程 invoke('im:compress-video')，进度经 'im:compress-progress' 事件回推。 */
function setupVideoCompress() {
  ipcMain.handle('im:compress-video', async (event, payload) => {
    const { inputPath, requestId, duration } = payload || {}
    const send = (percent) => {
      try { event.sender.send('im:compress-progress', { requestId, percent }) } catch { /* 窗口已关闭 */ }
    }
    let outputPath = null
    try {
      if (!inputPath || !fs.existsSync(inputPath)) {
        return { ok: false, reason: 'input-not-found' }
      }
      const ffmpegPath = resolveFfmpegPath()
      const enc = await detectEncoder(ffmpegPath)
      const inputSize = fs.statSync(inputPath).size
      const baseName = path.basename(inputPath).replace(/\.[^.]+$/, '') || 'video'
      outputPath = path.join(os.tmpdir(), `im-compress-${Date.now()}-${Math.round(Math.random() * 1e6)}.mp4`)

      const durSec = duration > 0 ? duration : await probeDuration(ffmpegPath, inputPath)
      const tail = ['-vf', 'scale=-2:720', '-c:a', 'aac', '-b:a', '128k', '-movflags', '+faststart', '-progress', 'pipe:1', '-y', outputPath]

      let res = await runFfmpeg(ffmpegPath, ['-hide_banner', '-i', inputPath, ...enc.args, ...tail], durSec, send)
      // 硬件编码器编译进来了但本机没对应 GPU/驱动时会失败，回落软编再试一次
      if (res.code !== 0 && enc.hw) {
        encoderCache = SOFTWARE_ENCODER
        res = await runFfmpeg(ffmpegPath, ['-hide_banner', '-i', inputPath, ...SOFTWARE_ENCODER.args, ...tail], durSec, send)
      }

      if (res.code !== 0 || !fs.existsSync(outputPath)) {
        return { ok: false, reason: (res.stderrTail || 'ffmpeg-failed').slice(-500) }
      }

      const outSize = fs.statSync(outputPath).size
      // 压完反而没变小（源本就是高压缩视频）就不用压缩结果，让前端传原片
      if (outSize <= 0 || outSize >= inputSize) {
        return { ok: true, compressed: false, name: `${baseName}.mp4`, size: outSize }
      }

      const data = fs.readFileSync(outputPath) // 压缩产物通常只有几 MB~几十 MB，读进内存回传无压力
      send(100)
      return { ok: true, compressed: true, name: `${baseName}.mp4`, size: outSize, data }
    } catch (e) {
      return { ok: false, reason: String((e && e.message) || e) }
    } finally {
      if (outputPath) { try { fs.unlinkSync(outputPath) } catch { /* 已删或不存在 */ } }
    }
  })
}

/* ======================= 窗口内容防截图（拆闪照期间） =======================
 * BrowserWindow.setContentProtection(true) 在 Windows 底层调 SetWindowDisplayAffinity
 * (WDA_EXCLUDEFROMCAPTURE)，窗口在系统截屏 / 录屏 / 镜像输出里变成黑块，
 * 肉眼正常可见。只在渲染进程打开闪照预览时开启、关闭预览时撤销，
 * 避免整个应用长期不进截图工具。浏览器 Web 端没有对应 API，防截图仅桌面端生效。
 */
function setupContentProtection() {
  ipcMain.handle('im:set-content-protection', (event, payload) => {
    try {
      const win = BrowserWindow.getAllWindows()[0]
      if (!win || win.isDestroyed()) return { ok: false, error: '窗口不存在' }
      win.setContentProtection(!!(payload && payload.enabled))
      return { ok: true, data: true }
    } catch (e) {
      return { ok: false, error: String((e && e.message) || e) }
    }
  })
}

/* ======================= 应用调试日志落盘 (app.log) =======================
 * 渲染进程通过 IPC 把日志条目发过来，主进程追加写入 userData/app.log。
 * 关闭时由渲染进程发 im:log-clear 清空文件（而非删除），保证下次开启是干净的。
 */
let appLogFd = null

function setupAppLog() {
  const logPath = path.join(app.getPath('userData'), 'app.log')
  // 启动时以追加模式打开（文件不存在则创建）
  try {
    appLogFd = fs.openSync(logPath, 'a')
  } catch { /* 无写入权限时静默跳过 */ }

  ipcMain.handle('im:log-write', (event, entry) => {
    if (appLogFd === null) return
    try {
      const line = `${new Date(entry.timestamp).toISOString()} [${(entry.level || 'debug').toUpperCase()}] ${entry.message}\n`
      fs.writeSync(appLogFd, line)
    } catch { /* 忽略单次写入失败 */ }
  })

  ipcMain.handle('im:log-clear', () => {
    if (appLogFd === null) return
    try {
      fs.ftruncateSync(appLogFd, 0)
    } catch { /* ignore */ }
  })
}

function closeAppLog() {
  if (appLogFd !== null) {
    try { fs.closeSync(appLogFd) } catch { /* ignore */ }
    appLogFd = null
  }
}

/* ======================= 被控端 Agent 随桌面端自启 =======================
 * 打包安装包经 extraResources 内置了被控端 Agent（resources/agent/im-remote-agent.jar）
 * 与裁剪 JRE（resources/jre）。桌面端启动时后台拉起 Agent，被控机装完包无需再手动
 * 双击 bat：Agent 起身后按识别码模式自动连中继，网页绿色「本机识别码」面板随即
 * 经回环接口读到识别码。
 *
 * 防重复启动：先探测 Agent 回环接口（127.0.0.1:18923/local-info），有响应即认为
 * 已在运行（自启过 / bat 拉过 / 手动跑的 jar 都算）直接跳过，避免第二个 JVM 去争
 * WS 连接与回环端口。Agent 生命周期独立于 IM 窗口：关窗口不杀它，被控机才能保持
 * 可远程；要停请在 Agent 窗口退出或任务管理器结束 javaw.exe。
 * 开发模式下 electron/ 目录只有 jre 没有 agent/ jar，探测自然跳过，不影响调试。
 */
const AGENT_LOCAL_INFO_URL = 'http://127.0.0.1:18923/local-info'

/** 定位内置 JRE 与 Agent jar：优先安装包 resources 目录，回退主脚本同目录 */
function resolveAgentPaths() {
  const roots = []
  if (process.resourcesPath) roots.push(process.resourcesPath)
  roots.push(__dirname)
  for (const root of roots) {
    const javaw = path.join(root, 'jre', 'bin', 'javaw.exe')
    const jar = path.join(root, 'agent', 'im-remote-agent.jar')
    try {
      if (fs.existsSync(javaw) && fs.existsSync(jar)) return { javaw, jar }
    } catch { /* 忽略单个候选探测失败 */ }
  }
  return null
}

/** 探测本机 Agent 是否已在运行：回环接口有响应即在运行 */
function probeLocalAgent() {
  return fetch(AGENT_LOCAL_INFO_URL, { signal: AbortSignal.timeout(800) })
    .then((r) => r.ok)
    .catch(() => false)
}

/** 后台拉起 Agent：工作目录固定 %USERPROFILE%\im-remote-agent（与 bat 一致，配置和日志同落一处） */
async function setupAgentAutostart() {
  const paths = resolveAgentPaths()
  if (!paths) return
  if (await probeLocalAgent()) return
  const workDir = path.join(os.homedir(), 'im-remote-agent')
  try {
    fs.mkdirSync(workDir, { recursive: true })
    // 种子 server.url：Agent 内置默认 http://127.0.0.1:8080，装在无本机后端的被控机上
    // 必然 ConnectException 重试循环（免登录自启场景的头号启动报错）。这里把桌面端
    // 三层回落的解析结果（server-config.js，与前端同源）写进 Agent 的 config.properties；
    // 用户已手动配过（含 Agent 窗口「服务器地址」栏改动）则原样尊重，不覆盖。
    const cfgFile = path.join(workDir, 'config.properties')
    try {
      const existing = fs.existsSync(cfgFile) ? fs.readFileSync(cfgFile, 'utf8') : ''
      if (!/^\s*server\.url\s*=\s*\S+/m.test(existing)) {
        fs.appendFileSync(cfgFile, `server.url=${resolveServerBase().baseUrl}\n`, 'utf8')
      }
    } catch { /* 种子失败降级为 Agent 默认地址：只影响被控能力，不阻塞主进程 */ }
    // 清掉宿主机的 JAVA_TOOL_OPTIONS 再传给内置 JRE：例如 trustStoreType=WINDOWS-ROOT
    // 依赖 jdk.crypto.mscapi 模块，环境残留会让 Agent 起身即死
    const env = { ...process.env }
    delete env.JAVA_TOOL_OPTIONS
    spawn(paths.javaw, ['-jar', paths.jar], { cwd: workDir, stdio: 'ignore', windowsHide: true, env })
      .on('error', () => { /* 拉起失败静默降级：只影响被控能力，不影响 IM 主功能 */ })
  } catch { /* 建目录或启动失败都不阻塞主进程 */ }
}

/**
 * UDP 直连桥：打洞、DXP 分片、GBN 重传全在主进程做（见 direct.js），
 * 渲染进程只收「完整消息」，避免同一份 ARQ 状态被两处各持一半。
 * 浏览器 Web 部署没有 preload，前端拿不到 window.__IM_DIRECT__ 就自动跳过 UDP 档。
 */
function setupDirect() {
  const send = (channel, payload) => {
    const win = BrowserWindow.getAllWindows()[0]
    if (win && !win.isDestroyed()) {
      win.webContents.send(channel, payload)
    }
  }
  setupDirectBridge(ipcMain, send)
}

/**
 * 直播推流桥：spawn ffmpeg 切 HLS + PUT 到 Nginx 全在主进程做（见 live.js），
 * 渲染进程只收「已启动 / 推流中 / 停止 / 错误」状态事件。send 用第一个窗口的 webContents。
 */
function setupLive() {
  const send = (channel, payload) => {
    const win = BrowserWindow.getAllWindows()[0]
    if (win && !win.isDestroyed()) {
      win.webContents.send(channel, payload)
    }
  }
  setupLiveBridge(ipcMain, send)
}

// 单实例：防止双击两次图标起两个主进程、在回环探测窗口期内竞相拉起 Agent
if (!app.requestSingleInstanceLock()) {
  app.quit()
} else {
  app.on('second-instance', () => {
    // 必须包含 show()：图标被再点一次时窗口很可能正藏在托盘里（只是 hide 过），
    // 只 focus 一个隐藏窗口在 Windows 上是看不出任何反应的
    revealWindow()
  })
}

app.whenReady().then(async () => {
  /**
   * Windows 的 Toast 通知靠 AUMID 找到「该用哪个应用的名字与图标」，
   * 不设的话通知要么根本不弹、要么显示为“Electron”。这个值必须与打包时
   * NSIS 给快捷方式写的 AUMID 一致（见 package.json 的 build.appId = com.im.desktop），
   * 两边不一样时用户看到的仍是一个陌生的应用名。
   */
  if (process.platform === 'win32') {
    app.setAppUserModelId('com.im.desktop')
  }
  // 先注册地址解析通道，后建窗口：preload 在页面脚本之前就要能 sendSync 拿到地址
  setupServerConfig(ipcMain)
  setupCertWhitelist()
  setupDownload()
  setupVideoCompress()
  setupLocalDb()
  setupDirect()
  setupRecorder()
  setupLive()
  setupContentProtection()
  setupAppLog()
  setupDesktopConfig()
  setupNotify()
  // 建窗口前先探域名：被备案拦截的网络会切到内置 IP 回落，preload 第一次 resolve 拿到的就是可达地址；
  // 正常网络代价是一次 TLS 握手（几百毫秒），被拦网络 reset 包约百毫秒回来，不拖启动
  await resolveWithProbe()
  // Agent 自启放在探测之后：种子进 config.properties 的 server.url 要含 IP 回落态（见 setupAgentAutostart）
  setupAgentAutostart()
  createWindow()
  // 托盘在建窗口之后：close 钩子已经挂上，这时 tray 不是 null，「关窗口」才会落到收进托盘那一路
  setupTray()

  app.on('activate', () => {
    if (BrowserWindow.getAllWindows().length === 0) {
      createWindow()
    }
  })
})

// 托盘菜单的「退出」与系统登出都走 quit：先置位再关窗口，否则下面的 close 会把退出拆在原地
app.on('before-quit', () => {
  quitting = true
  globalShortcut.unregisterAll()
  if (tray) {
    tray.destroy()
    tray = null
  }
})

app.on('window-all-closed', () => {
  // 直连 socket 先礼貌收掉，免得被控端干等一轮 ARQ 超时才判死
  closeDirectLink()
  // 录制文件同步收口：关窗时渲染进程已经没了，没人再调 record-stop，fd 不收就丢尾巴
  closeRecording('window-closed')
  closeAppLog()
  // 直播推流同理收口：关窗即停 ffmpeg 并清理临时分片目录，不留一个没人管的推流进程
  closeLivePush('window-closed')
  if (process.platform !== 'darwin') {
    app.quit()
  }
})
