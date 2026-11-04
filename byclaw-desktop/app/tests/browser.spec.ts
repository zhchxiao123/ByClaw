import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { afterEach, describe, expect, it } from 'vitest'
import type { ConfirmRequest } from '@byclaw/node-runtime'
import { browserDialogText } from '../src/browser-dialog.js'
import { bundledBycliPath, chromeSettingsLabel, chromeState, chromeTrayLabel, installExtension, withBrowser } from '../src/browser-support.js'

const dirs: string[] = []
function temp(): string {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'byclaw-app-browser-'))
  dirs.push(dir)
  return dir
}
afterEach(() => {
  for (const dir of dirs.splice(0)) fs.rmSync(dir, { recursive: true, force: true })
})

describe('bundled byCLI location', () => {
  it('is in Resources when packaged, vendor/ in development, overridable', () => {
    expect(bundledBycliPath({ packaged: true, resourcesPath: '/App.app/Contents/Resources', appRoot: '/x' })).toBe('/App.app/Contents/Resources/bycli/node_modules/@sovovs/bycli')
    expect(bundledBycliPath({ packaged: false, resourcesPath: '/r', appRoot: '/src/app' })).toBe('/src/app/vendor/bycli/node_modules/@sovovs/bycli')
    expect(bundledBycliPath({ packaged: true, resourcesPath: '/r', appRoot: '/x', env: { BYCLAW_DESKTOP_BYCLI: '/opt/bycli' } })).toBe('/opt/bycli')
  })
})

describe('installExtension', () => {
  it('copies once into a fixed folder and refreshes in place when the build changes', () => {
    const source = temp()
    fs.mkdirSync(path.join(source, 'dist'))
    fs.writeFileSync(path.join(source, 'manifest.json'), '{"version":"1"}')
    fs.writeFileSync(path.join(source, 'dist', 'build-info.json'), '{"buildId":"a"}')
    fs.writeFileSync(path.join(source, 'dist', 'background.js'), 'v1')
    const target = path.join(temp(), 'chrome-extension')
    expect(installExtension(source, target)).toBe(true)
    expect(fs.readFileSync(path.join(target, 'dist', 'background.js'), 'utf8')).toBe('v1')
    const inode = fs.statSync(target).ino
    expect(installExtension(source, target)).toBe(false)
    fs.writeFileSync(path.join(target, 'stale.js'), 'old')
    fs.writeFileSync(path.join(source, 'dist', 'build-info.json'), '{"buildId":"b"}')
    fs.writeFileSync(path.join(source, 'dist', 'background.js'), 'v2')
    expect(installExtension(source, target)).toBe(true)
    expect(fs.readFileSync(path.join(target, 'dist', 'background.js'), 'utf8')).toBe('v2')
    expect(fs.existsSync(path.join(target, 'stale.js'))).toBe(false)
    expect(fs.statSync(target).ino).toBe(inode)
  })
})

describe('Chrome status', () => {
  it('maps byCLI status to tray and settings text', () => {
    expect(chromeState(false, undefined)).toBe('unavailable')
    expect(chromeState(true, { daemon: 'starting', extensionConnected: false, external: false })).toBe('starting')
    expect(chromeState(true, { daemon: 'running', extensionConnected: false, external: false })).toBe('not_connected')
    expect(chromeState(true, { daemon: 'running', extensionConnected: true, external: true })).toBe('connected')
    expect(chromeTrayLabel('connected')).toBe('Chrome：已连接')
    expect(chromeTrayLabel('not_connected')).toBe('Chrome：未连接')
    expect(chromeSettingsLabel('not_connected')).toContain('未安装扩展')
    expect(chromeSettingsLabel('connected')).toBe('已连接')
  })

  it('adds the browser capability once', () => {
    expect(withBrowser(['shell'])).toEqual(['shell', 'browser'])
    expect(withBrowser(['shell', 'browser'])).toEqual(['shell', 'browser'])
  })
})

function request(over: Partial<ConfirmRequest>): ConfirmRequest {
  return {
    id: 'i1',
    tool: 'device_browser',
    args: {},
    session: 'chat-1',
    device: { name: '我的 Mac' },
    kind: 'browser',
    summary: '',
    options: ['allow_once', 'allow_session', 'deny'],
    signal: new AbortController().signal,
    ...over,
  }
}

describe('browser dialog text', () => {
  it('first access shows the site and the command', () => {
    const text = browserDialogText(request({ browser: { action: 'access', site: 'mail.example.com', session: 'chat-1', command: 'click 12', url: 'https://mail.example.com/inbox' } }))!
    expect(text.message).toBe('AI 请求在你的 Chrome 里访问 mail.example.com')
    expect(text.detail).toContain('命令：click 12')
    expect(text.detail).toContain('https://mail.example.com/inbox')
    expect(text.buttons).toEqual(['允许一次', '本会话内允许这个网站', '拒绝'])
  })

  it('bind shows the focused tab title and URL', () => {
    const text = browserDialogText(request({ browser: { action: 'bind', site: 'bank.example', session: 'chat-1', command: 'bind', url: 'https://bank.example/acct', title: '我的账户' } }))!
    expect(text.message).toBe('AI 请求接管你当前的 Chrome 标签页')
    expect(text.detail).toContain('标题：我的账户')
    expect(text.detail).toContain('网址：https://bank.example/acct')
  })

  it('eval shows the script, upload the files, each with only once / deny', () => {
    const evalText = browserDialogText(request({ options: ['allow_once', 'deny'], browser: { action: 'eval', site: 'a.example', session: 's', command: 'eval document.title' } }))!
    expect(evalText.message).toBe('AI 请求在 a.example 上运行脚本')
    expect(evalText.detail).toContain('document.title')
    expect(evalText.buttons).toEqual(['允许一次', '拒绝'])
    const upload = browserDialogText(request({ options: ['allow_once', 'deny'], browser: { action: 'upload', site: 'a.example', session: 's', command: 'upload 4 /Users/me/cv.pdf', files: ['/Users/me/cv.pdf'] } }))!
    expect(upload.message).toBe('AI 请求向 a.example 上传文件')
    expect(upload.detail).toContain('/Users/me/cv.pdf')
  })

  it('is undefined for other tools', () => {
    expect(browserDialogText(request({ kind: 'shell', tool: 'device_shell' }))).toBeUndefined()
  })
})
