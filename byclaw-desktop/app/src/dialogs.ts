/** Native confirmation and folder-picker dialogs for the device node. */

import fs from 'node:fs'
import path from 'node:path'
import { dialog, Notification, app, type BrowserWindow } from 'electron'
import { canAllowDir, DECISION_LABELS, isWithin, type ConfirmFn, type Decision, type RequestAllowedDir } from '@byclaw/node-runtime'
import { browserDialogText } from './browser-dialog.js'

const TOOL_LABELS: Record<string, string> = {
  shell: '执行命令',
  'files.read': '读取文件',
  'files.write': '写入文件',
  browser: '操作 Chrome',
}

/** Serialize dialogs: one native prompt at a time. */
function queue() {
  let tail: Promise<unknown> = Promise.resolve()
  return <T>(task: () => Promise<T>): Promise<T> => {
    const next = tail.then(task, task)
    tail = next.catch(() => undefined)
    return next
  }
}

const serial = queue()

/** Bring the app forward and, when the window is hidden, also post a notification. */
function attention(window: BrowserWindow | undefined, body: string): void {
  if (!window || !window.isVisible() || !window.isFocused()) {
    if (Notification.isSupported()) {
      const note = new Notification({ title: 'ByClaw 需要你确认', body, silent: false })
      note.on('click', () => app.focus({ steal: true }))
      note.show()
    }
  }
  app.focus({ steal: true })
}

/**
 * The confirmation dialog: 允许一次 / 本会话内允许 / 始终允许（仅读取文件） / 拒绝 (whichever are offered).
 * Browser prompts show the site and command (or the tab to bind, the script, the files).
 */
export function dialogConfirm(getWindow: () => BrowserWindow | undefined): ConfirmFn {
  return request => serial(async (): Promise<Decision> => {
    if (request.signal.aborted) return 'deny'
    const action = TOOL_LABELS[request.kind] ?? request.tool
    attention(getWindow(), `${action}：${request.summary}`)
    const browser = browserDialogText(request)
    if (browser) {
      const deny = Math.max(0, request.options.indexOf('deny'))
      const { response } = await dialog.showMessageBox({
        type: request.browser?.action === 'eval' || request.browser?.action === 'upload' ? 'warning' : 'question',
        title: 'ByClaw',
        message: browser.message,
        detail: browser.detail,
        buttons: browser.buttons,
        defaultId: deny,
        cancelId: deny,
        noLink: true,
        signal: request.signal,
      })
      return request.options[response] ?? 'deny'
    }
    const buttons = request.options.map(option => DECISION_LABELS[option])
    const deny = Math.max(0, request.options.indexOf('deny'))
    const detail = [
      `设备：${request.device.name}`,
      `操作：${action}（${request.tool}）`,
      '',
      request.summary,
      '',
      request.session ? '「本会话内允许」只对这次对话有效。' : '',
      request.kind === 'shell' ? '命令会以你的用户身份在本机运行。' : '',
    ].filter((line, i, all) => line !== '' || (i > 0 && all[i - 1] !== '')).join('\n')
    const { response } = await dialog.showMessageBox({
      type: 'question',
      title: 'ByClaw',
      message: `AI 请求在你的电脑上${action}`,
      detail,
      buttons,
      defaultId: deny,
      cancelId: deny,
      noLink: true,
      signal: request.signal,
    })
    return request.options[response] ?? 'deny'
  })
}

function nearestExisting(p: string): string {
  let current = p
  while (!fs.existsSync(current)) {
    const parent = path.dirname(current)
    if (parent === current) break
    current = parent
  }
  return current
}

/** Ask for a folder (directory picker) when the AI touches a path outside every allowed dir. */
export function dialogRequestAllowedDir(getWindow: () => BrowserWindow | undefined, forbiddenDirs: () => string[]): RequestAllowedDir {
  return request => serial(async () => {
    if (request.signal.aborted) return undefined
    attention(getWindow(), `需要授权目录：${request.path}`)
    const ask = await dialog.showMessageBox({
      type: 'question',
      title: 'ByClaw',
      message: 'AI 要访问的位置不在授权目录内',
      detail: `${request.path}\n\n可以选择一个包含它的目录授权给 AI；授权后，读写仍会逐次请你确认。`,
      buttons: ['选择授权目录…', '拒绝'],
      defaultId: 1,
      cancelId: 1,
      noLink: true,
      signal: request.signal,
    })
    if (ask.response !== 0 || request.signal.aborted) return undefined
    const picked = await dialog.showOpenDialog({
      title: '选择授权目录',
      buttonLabel: '授权',
      defaultPath: nearestExisting(path.dirname(request.path)),
      properties: ['openDirectory', 'createDirectory'],
    })
    const dir = picked.filePaths[0]
    if (picked.canceled || !dir) return undefined
    const realDir = fs.realpathSync(dir)
    const target = path.resolve(request.path)
    let realTarget = target
    try {
      realTarget = fs.realpathSync(nearestExisting(target))
    }
    catch {
      // Keep the lexical path.
    }
    if (!isWithin(target, dir) && !isWithin(realTarget, realDir)) {
      await dialog.showMessageBox({ type: 'warning', title: 'ByClaw', message: '所选目录不包含要访问的位置', detail: `${request.path}\n不在\n${dir}`, buttons: ['好'] })
      return undefined
    }
    if (!(await canAllowDir(dir, forbiddenDirs()))) {
      await dialog.showMessageBox({ type: 'warning', title: 'ByClaw', message: '这个目录不能授权', detail: '钥匙串、SSH 密钥、浏览器配置和 ByClaw 自己的数据目录始终禁止访问。', buttons: ['好'] })
      return undefined
    }
    return dir
  })
}

/** Folder picker for the settings page. */
export async function pickAllowedDir(forbiddenDirs: string[]): Promise<string | undefined> {
  const picked = await dialog.showOpenDialog({ title: '添加授权目录', buttonLabel: '授权', properties: ['openDirectory', 'createDirectory'] })
  const dir = picked.filePaths[0]
  if (picked.canceled || !dir) return undefined
  if (!(await canAllowDir(dir, forbiddenDirs))) {
    await dialog.showMessageBox({ type: 'warning', title: 'ByClaw', message: '这个目录不能授权', detail: '钥匙串、SSH 密钥、浏览器配置和 ByClaw 自己的数据目录始终禁止访问。', buttons: ['好'] })
    return undefined
  }
  return dir
}
