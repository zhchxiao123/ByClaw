/**
 * ByClaw desktop main process: the ByClaw web app in a locked-down window, a tray, and the embedded
 * device node (pairing, local confirmation, allowed dirs, audit).
 */

import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { app, BrowserWindow, ipcMain, Menu, nativeImage, safeStorage, shell, Tray, type IpcMainInvokeEvent } from 'electron'
import { AuditLog, defaultForbiddenDirs, JsonGrantStore, startNode, type NodeHandle, type StatusInfo } from '@byclaw/node-runtime'
import { defaultDeviceName, effectiveApiBase, effectiveRelayUrl, effectiveWebUrl, loadConfig, normalizeWebUrl, saveConfig, type AppConfig } from './config.js'
import { dialogConfirm, dialogRequestAllowedDir, pickAllowedDir } from './dialogs.js'
import { allowedOrigins, isAllowedNavigation, isExternalOpenable, isWebOrigin } from './navigation.js'
import { accountChanged, deviceUserCode, loginUserCode } from './account.js'
import { pairWithPage, readLoginToken } from './pairing.js'
import { TokenStore } from './token-store.js'

const HERE = path.dirname(fileURLToPath(import.meta.url))
const APP_ROOT = path.join(HERE, '..')
const STATIC = path.join(APP_ROOT, 'static')
const PRELOAD = path.join(HERE, 'preload.cjs')

/** Pairing state shown in the tray besides the node status. */
type PairState = 'unpaired' | 'pairing' | 'paired' | 'revoked'

let config: AppConfig
let configFile: string
let userData: string
let tokens: TokenStore
let mainWindow: BrowserWindow | undefined
let localWindow: BrowserWindow | undefined
let tray: Tray | undefined
let node: NodeHandle | undefined
let nodeInfo: StatusInfo | undefined
let pairState: PairState = 'unpaired'
let pairError: string | undefined
/** The account the stored device token belongs to (its `sub`). */
let deviceUser: string | undefined
let switchingAccount = false
let pairTimer: NodeJS.Timeout | undefined
/** When pairing last failed (BE error); the timer waits a minute before trying again. */
let lastPairFailureAt = 0
let quitting = false

/** Log to stdout and `<userData>/main.log` (reset past 5 MB). Never logs tokens. */
function log(message: string): void {
  const line = `[byclaw-desktop ${new Date().toISOString()}] ${message}`
  console.log(line)
  if (!userData) return
  try {
    const file = path.join(userData, 'main.log')
    if ((fs.statSync(file, { throwIfNoEntry: false })?.size ?? 0) > 5 * 1024 * 1024) fs.writeFileSync(file, '')
    fs.appendFileSync(file, `${line}\n`, { mode: 0o600 })
  }
  catch {
    // Logging must never break the app.
  }
}

function persist(): void {
  saveConfig(configFile, config)
}

function webUrl(): string | undefined {
  return effectiveWebUrl(config)
}

function deviceName(): string {
  return config.deviceName || defaultDeviceName()
}

function auditDir(): string {
  return path.join(userData, 'audit')
}

/** Forbidden dirs including this app's own data dir (token, config, grants, audit). */
function forbiddenDirs(): string[] {
  return defaultForbiddenDirs({ ownDataDirs: [userData] })
}

// ---------------------------------------------------------------------------------------------
// Device node lifecycle

function startDeviceNode(deviceToken: string): void {
  const url = webUrl()
  if (!url || node) return
  deviceUser = deviceUserCode(deviceToken)
  node = startNode({
    relayUrl: effectiveRelayUrl(config, url),
    deviceToken,
    name: deviceName(),
    platform: process.platform,
    appVersion: app.getVersion(),
    capabilities: config.capabilities,
    allowedDirs: config.allowedDirs,
    ownDataDirs: [userData],
    confirm: dialogConfirm(() => mainWindow),
    requestAllowedDir: dialogRequestAllowedDir(() => mainWindow, forbiddenDirs),
    grants: new JsonGrantStore(path.join(userData, 'grants.json')),
    audit: new AuditLog(auditDir()),
    paused: config.paused,
    log,
  })
  node.on('status', info => {
    nodeInfo = info
    if (info.reason === 'unpaired' || info.reason === 'revoked') onRevoked(info.reason)
    refreshTray()
  })
  node.on('allowedDirs', dirs => {
    config.allowedDirs = dirs
    persist()
    broadcastState()
  })
  nodeInfo = node.info
  refreshTray()
}

async function stopDeviceNode(): Promise<void> {
  const current = node
  node = undefined
  nodeInfo = undefined
  await current?.stop()
}

/** The relay refused the token: unpaired in "我的设备" or invalid. Forget it; re-pair only on request. */
function onRevoked(reason: 'unpaired' | 'revoked'): void {
  log(`device token ${reason}; clearing it`)
  tokens.clear()
  delete config.deviceId
  persist()
  pairState = 'revoked'
  pairError = reason === 'revoked' ? '这台电脑已在「我的设备」中解绑' : '设备凭证无效'
  void stopDeviceNode().then(refreshTray)
}

// ---------------------------------------------------------------------------------------------
// Account switch: the device belongs to one account; when the page logs in as another, re-pair.

async function checkAccount(): Promise<void> {
  const url = webUrl()
  if (!url || !mainWindow || switchingAccount || deviceUser === undefined) return
  if (pairState !== 'paired' && pairState !== 'revoked') return
  const loginUser = loginUserCode(await readLoginToken(mainWindow.webContents, url))
  if (!accountChanged(loginUser, deviceUser)) return
  switchingAccount = true
  log(`login account changed from ${deviceUser} to ${loginUser}; pairing this computer with the new account`)
  try {
    await stopDeviceNode()
    tokens.clear()
    delete config.deviceId
    persist()
    deviceUser = undefined
    pairState = 'unpaired'
    pairError = undefined
    lastPairFailureAt = 0
    refreshTray()
    schedulePairing()
    await tryPair()
  }
  finally {
    switchingAccount = false
  }
}

// ---------------------------------------------------------------------------------------------
// Pairing: once the page is logged in, pair with its login token.

async function tryPair(): Promise<void> {
  const url = webUrl()
  if (!url || !mainWindow || pairState === 'paired' || pairState === 'pairing' || pairState === 'revoked') return
  const contents = mainWindow.webContents
  if (!isWebOrigin(contents.getURL(), url)) return
  pairState = 'pairing'
  try {
    const result = await pairWithPage({ contents, webUrl: url, apiBaseUrl: effectiveApiBase(config, url), name: deviceName(), appVersion: app.getVersion() })
    if (!result) {
      pairState = 'unpaired'
      return
    }
    tokens.save(result.deviceToken)
    config.deviceId = result.deviceId
    persist()
    pairState = 'paired'
    pairError = undefined
    log(`paired as ${result.deviceId}`)
    startDeviceNode(result.deviceToken)
  }
  catch (error) {
    pairState = 'unpaired'
    pairError = error instanceof Error ? error.message : String(error)
    log(`pairing failed: ${pairError}`)
    lastPairFailureAt = Date.now()
  }
  finally {
    refreshTray()
    broadcastState()
  }
}

function schedulePairing(): void {
  clearInterval(pairTimer)
  pairTimer = setInterval(() => {
    if (pairState === 'paired') return clearInterval(pairTimer)
    if (Date.now() - lastPairFailureAt < 60_000) return
    void tryPair()
  }, 3000)
}

// ---------------------------------------------------------------------------------------------
// Windows

function hardenContents(contents: Electron.WebContents, mayNavigate: (url: string) => boolean): void {
  contents.setWindowOpenHandler(({ url }) => {
    if (isExternalOpenable(url)) void shell.openExternal(url)
    return { action: 'deny' }
  })
  const guard = (event: Electron.Event, url: string) => {
    if (mayNavigate(url)) return
    event.preventDefault()
    log(`blocked navigation to ${url}`)
    if (isExternalOpenable(url)) void shell.openExternal(url)
  }
  contents.on('will-navigate', guard)
  contents.on('will-redirect', guard)
  contents.on('will-attach-webview', event => event.preventDefault())
}

function createMainWindow(show: boolean): void {
  const url = webUrl()
  if (!url) return
  const origins = () => allowedOrigins(url, config.extraOrigins, config.loginUrl)
  mainWindow = new BrowserWindow({
    width: 1280,
    height: 820,
    minWidth: 900,
    minHeight: 600,
    show,
    title: 'ByClaw',
    webPreferences: {
      // The remote page gets no preload, no Node and no bridge: it cannot reach the device token.
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: true,
      webSecurity: true,
      spellcheck: false,
    },
  })
  hardenContents(mainWindow.webContents, target => isAllowedNavigation(target, origins()))
  mainWindow.webContents.session.setPermissionRequestHandler((contents, permission, callback, details) => {
    const allowed = ['media', 'notifications', 'clipboard-sanitized-write', 'fullscreen'].includes(permission)
    callback(allowed && isAllowedNavigation(details.requestingUrl || contents.getURL(), origins()))
  })
  mainWindow.on('close', event => {
    if (quitting) return
    event.preventDefault()
    mainWindow?.hide()
  })
  const created = mainWindow
  mainWindow.on('closed', () => { if (mainWindow === created) mainWindow = undefined })
  watchContents(mainWindow.webContents)
  mainWindow.webContents.on('did-finish-load', () => void checkAccount().then(tryPair))
  mainWindow.webContents.on('did-navigate-in-page', () => void checkAccount().then(tryPair))
  const first = pairState !== 'paired' && config.loginUrl ? config.loginUrl : url
  void mainWindow.webContents.session.resolveProxy(first).then(proxy => log(`opening ${first} via ${proxy}`))
  // Not paired yet and a login entry is configured (e.g. mock login): start there.
  void mainWindow.loadURL(first)
}

/** Log why a page is blank: failed loads, crashes, script errors; show a retry page instead of white. */
function watchContents(contents: Electron.WebContents): void {
  let consoleErrors = 0
  contents.on('did-fail-load', (_event, code, description, url, isMainFrame) => {
    if (!isMainFrame || code === -3) return // -3 = aborted by a newer navigation
    log(`load failed ${code} ${description} ${url}`)
    void contents.loadFile(path.join(STATIC, 'error.html'), { query: { code: String(code), desc: description, url } })
  })
  contents.on('did-finish-load', () => log(`loaded ${contents.getURL()}`))
  contents.on('render-process-gone', (_event, details) => log(`renderer gone: ${details.reason} (exit ${details.exitCode})`))
  contents.on('unresponsive', () => log('page unresponsive'))
  contents.on('console-message', (event: Electron.Event & { level?: string | number, message?: string, sourceId?: string, lineNumber?: number }, ...legacy: unknown[]) => {
    const level = event.level ?? legacy[0]
    if (!(level === 'error' || level === 3) || consoleErrors >= 50) return
    consoleErrors++
    const message = event.message ?? legacy[1]
    const source = event.sourceId ?? legacy[3]
    log(`page error: ${String(message).slice(0, 300)} (${String(source ?? '').slice(0, 120)})`)
  })
  if (process.env.BYCLAW_DESKTOP_DEVTOOLS === '1') contents.openDevTools({ mode: 'detach' })
}

function openLoginPage(): void {
  if (!config.loginUrl) return
  showMainWindow()
  void mainWindow?.loadURL(config.loginUrl)
}

function showMainWindow(): void {
  if (!webUrl()) return openLocal('setup')
  if (!mainWindow) createMainWindow(true)
  mainWindow?.show()
  mainWindow?.focus()
}

type LocalPage = 'setup' | 'settings' | 'audit'

/** Our own local pages (file://) get the preload bridge; nothing else does. */
function openLocal(page: LocalPage): void {
  if (localWindow && !localWindow.isDestroyed()) {
    void localWindow.loadFile(path.join(STATIC, `${page}.html`))
    localWindow.show()
    localWindow.focus()
    return
  }
  localWindow = new BrowserWindow({
    width: page === 'audit' ? 900 : 560,
    height: page === 'audit' ? 640 : 600,
    title: 'ByClaw',
    show: true,
    webPreferences: { contextIsolation: true, nodeIntegration: false, sandbox: true, preload: PRELOAD },
  })
  hardenContents(localWindow.webContents, isLocalPage)
  const created = localWindow
  localWindow.on('closed', () => { if (localWindow === created) localWindow = undefined })
  void localWindow.loadFile(path.join(STATIC, `${page}.html`))
}

// ---------------------------------------------------------------------------------------------
// Tray

function statusLabel(): string {
  if (!webUrl()) return '未设置网页地址'
  if (pairState === 'revoked') return `未连接：${pairError ?? '已解绑'}`
  if (pairState !== 'paired') return pairError ? `未配对：${pairError}` : '未配对：请在窗口中登录'
  switch (nodeInfo?.status) {
    case 'online': return '在线'
    case 'paused': return '已暂停'
    case 'connecting': return '连接中…'
    case 'offline': return nodeInfo.retryInMs ? `离线，${Math.round(nodeInfo.retryInMs / 1000)} 秒后重试` : '离线'
    default: return '离线'
  }
}

function trayImage(): Electron.NativeImage {
  const image = nativeImage.createFromPath(path.join(APP_ROOT, 'assets', 'trayTemplate.png'))
  image.setTemplateImage(true)
  return image
}

function refreshTray(): void {
  if (!tray) return
  const paused = config.paused
  const status = statusLabel()
  tray.setToolTip(`ByClaw · ${status}`)
  // Template icons cannot be coloured: show a short word next to the icon unless online.
  if (process.platform === 'darwin') tray.setTitle(nodeInfo?.status === 'online' ? '' : paused && pairState === 'paired' ? '已暂停' : '离线')
  tray.setContextMenu(Menu.buildFromTemplate([
    { label: `ByClaw · ${status}`, enabled: false },
    { label: deviceUser ? `设备：${deviceName()}（账号 ${deviceUser}）` : `设备：${deviceName()}`, enabled: false },
    { type: 'separator' },
    { label: '打开 ByClaw', click: showMainWindow },
    ...(config.loginUrl ? [{ label: '打开登录页', click: openLoginPage }] : []),
    {
      label: paused ? '恢复' : '暂停我的电脑',
      enabled: pairState === 'paired',
      click: () => {
        config.paused = !paused
        persist()
        if (config.paused) node?.pause()
        else node?.resume()
        refreshTray()
      },
    },
    ...(pairState === 'revoked' ? [{ label: '重新配对', click: () => { pairState = 'unpaired'; pairError = undefined; lastPairFailureAt = 0; schedulePairing(); showMainWindow(); void tryPair() } }] : []),
    { label: '授权目录…', click: () => openLocal('settings') },
    { label: '查看操作记录', click: () => openLocal('audit') },
    {
      label: '开机自启',
      type: 'checkbox',
      checked: config.launchAtLogin,
      click: item => {
        config.launchAtLogin = item.checked
        persist()
        applyLoginItem()
      },
    },
    { type: 'separator' },
    { label: '退出', click: () => { quitting = true; app.quit() } },
  ]))
}

function applyLoginItem(): void {
  if (process.platform === 'darwin' || process.platform === 'win32') app.setLoginItemSettings({ openAtLogin: config.launchAtLogin })
}

// ---------------------------------------------------------------------------------------------
// IPC for the local pages only

/** True for our own pages in static/ (file://). */
function isLocalPage(url: string): boolean {
  if (!url.startsWith('file://')) return false
  try {
    return path.dirname(fileURLToPath(url)) === STATIC
  }
  catch {
    return false
  }
}

function fromLocalPage(event: IpcMainInvokeEvent): boolean {
  return isLocalPage(event.senderFrame?.url ?? '')
}

function localState() {
  return {
    webUrl: webUrl() ?? '',
    webUrlFromEnv: Boolean(process.env.BYCLAW_DESKTOP_URL),
    loginUrl: config.loginUrl ?? '',
    extraOrigins: config.extraOrigins,
    deviceName: deviceName(),
    deviceId: config.deviceId ?? '',
    allowedDirs: config.allowedDirs,
    launchAtLogin: config.launchAtLogin,
    status: statusLabel(),
    auditDir: auditDir(),
  }
}

function broadcastState(): void {
  if (localWindow && !localWindow.isDestroyed()) localWindow.webContents.send('byclaw:state', localState())
}

function handle(channel: string, fn: (...args: any[]) => unknown): void {
  ipcMain.handle(channel, (event, ...args) => {
    if (!fromLocalPage(event)) throw new Error('forbidden')
    return fn(...args)
  })
}

function registerIpc(): void {
  handle('byclaw:getState', () => localState())
  handle('byclaw:saveWebUrl', (input: unknown, extraOrigins: unknown, loginUrl: unknown) => {
    let url: string
    let login: string | undefined
    try {
      url = normalizeWebUrl(String(input))
      login = typeof loginUrl === 'string' && loginUrl.trim() !== '' ? normalizeWebUrl(loginUrl) : undefined
    }
    catch (error) {
      return { ok: false, error: error instanceof Error ? error.message : String(error) }
    }
    const previous = config.webUrl
    config.webUrl = url
    if (login) config.loginUrl = login
    else delete config.loginUrl
    config.extraOrigins = Array.isArray(extraOrigins) ? extraOrigins.filter((o): o is string => typeof o === 'string' && o.trim() !== '').map(o => o.trim()) : config.extraOrigins
    // A device token belongs to the BE it was issued by.
    if (previous && new URL(previous).origin !== new URL(url).origin) {
      tokens.clear()
      delete config.deviceId
      pairState = 'unpaired'
      void stopDeviceNode()
    }
    persist()
    mainWindow?.destroy()
    mainWindow = undefined
    createMainWindow(true)
    schedulePairing()
    localWindow?.close()
    refreshTray()
    return { ok: true }
  })
  handle('byclaw:rename', (name: unknown) => {
    const value = String(name ?? '').trim().slice(0, 64)
    if (value === '') return { ok: false, error: '名称不能为空' }
    config.deviceName = value
    persist()
    node?.setName(value)
    refreshTray()
    broadcastState()
    return { ok: true }
  })
  handle('byclaw:addDir', async () => {
    const dir = await pickAllowedDir(forbiddenDirs())
    if (!dir) return localState()
    if (!config.allowedDirs.includes(dir)) config.allowedDirs = [...config.allowedDirs, dir]
    persist()
    node?.setAllowedDirs(config.allowedDirs)
    return localState()
  })
  handle('byclaw:removeDir', (dir: unknown) => {
    config.allowedDirs = config.allowedDirs.filter(d => d !== dir)
    persist()
    node?.setAllowedDirs(config.allowedDirs)
    return localState()
  })
  handle('byclaw:setLaunchAtLogin', (value: unknown) => {
    config.launchAtLogin = value === true
    persist()
    applyLoginItem()
    refreshTray()
    return localState()
  })
  handle('byclaw:auditRecent', () => {
    try {
      return new AuditLog(auditDir()).recent(500)
    }
    catch {
      return []
    }
  })
  handle('byclaw:openAuditFolder', () => shell.openPath(auditDir()))
}

// ---------------------------------------------------------------------------------------------
// Boot

async function boot(): Promise<void> {
  userData = app.getPath('userData')
  configFile = path.join(userData, 'config.json')
  const firstRun = !fs.existsSync(configFile)
  config = loadConfig(configFile)
  tokens = new TokenStore(userData, safeStorage)
  fs.mkdirSync(userData, { recursive: true })
  log(`starting ${app.getVersion()} on ${process.platform}/${process.arch}`)
  if (firstRun) {
    persist()
    applyLoginItem()
  }
  registerIpc()
  tray = new Tray(trayImage())
  refreshTray()

  const openedAtLogin = process.platform === 'darwin' && app.getLoginItemSettings().wasOpenedAtLogin
  if (!webUrl()) {
    openLocal('setup')
    return
  }
  // Token first: the window starts on the login entry only while unpaired.
  const stored = tokens.load()
  if (stored) pairState = 'paired'
  if (stored) deviceUser = deviceUserCode(stored)
  createMainWindow(!openedAtLogin)
  if (stored) startDeviceNode(stored)
  else schedulePairing()
  // The web app may switch accounts without a page load.
  setInterval(() => void checkAccount(), 5000)
}

if (!app.requestSingleInstanceLock()) {
  app.quit()
}
else {
  app.on('second-instance', () => showMainWindow())
  app.on('activate', () => showMainWindow())
  app.on('before-quit', () => { quitting = true })
  app.on('window-all-closed', () => {
    // Stay in the tray; quitting is explicit (tray → 退出, Cmd+Q).
  })
  app.on('will-quit', event => {
    if (!node) return
    event.preventDefault()
    void stopDeviceNode().finally(() => app.exit(0))
  })
  app.whenReady().then(boot).catch(error => {
    log(`boot failed: ${error instanceof Error ? error.stack : String(error)}`)
    app.exit(1)
  })
}
