/** "连接 Chrome": where the bundled byCLI lives, the extension copy the user loads, status text. */

import fs from 'node:fs'
import path from 'node:path'
import type { BycliStatus, Capability } from '@byclaw/node-runtime'

/**
 * The byCLI package shipped with the app: `Contents/Resources/bycli/...` when packaged,
 * `app/vendor/bycli/...` in development; `BYCLAW_DESKTOP_BYCLI` overrides both.
 */
export function bundledBycliPath(options: { packaged: boolean, resourcesPath: string, appRoot: string, env?: NodeJS.ProcessEnv }): string {
  const override = options.env?.BYCLAW_DESKTOP_BYCLI
  if (override) return override
  const base = options.packaged ? path.join(options.resourcesPath, 'bycli') : path.join(options.appRoot, 'vendor', 'bycli')
  return path.join(base, 'node_modules', '@sovovs', 'bycli')
}

const MARKER = '.byclaw-source.json'

function fingerprint(dir: string): string {
  const parts: string[] = []
  for (const name of ['manifest.json', path.join('dist', 'build-info.json')]) {
    try {
      parts.push(fs.readFileSync(path.join(dir, name), 'utf8'))
    }
    catch {
      parts.push('')
    }
  }
  return JSON.stringify(parts)
}

/**
 * Copy the extension out of the app bundle to a fixed folder the user loads in Chrome
 * (`<userData>/chrome-extension`). The path never changes, so Chrome keeps the same extension ID
 * across app updates; the copy is refreshed only when the bundled build differs.
 * @returns true when files were (re)copied.
 */
export function installExtension(source: string, target: string): boolean {
  const want = fingerprint(source)
  try {
    if (JSON.parse(fs.readFileSync(path.join(target, MARKER), 'utf8')).fingerprint === want) return false
  }
  catch {
    // Missing or stale copy.
  }
  fs.mkdirSync(target, { recursive: true })
  // Replace contents in place (not the folder itself) so a loaded extension keeps its path.
  for (const name of fs.readdirSync(target)) fs.rmSync(path.join(target, name), { recursive: true, force: true })
  fs.cpSync(source, target, { recursive: true, dereference: true })
  fs.writeFileSync(path.join(target, MARKER), JSON.stringify({ fingerprint: want }))
  return true
}

export type ChromeState = 'unavailable' | 'starting' | 'not_connected' | 'connected'

export function chromeState(available: boolean, status: BycliStatus | undefined): ChromeState {
  if (!available || !status) return 'unavailable'
  if (status.extensionConnected) return 'connected'
  return status.daemon === 'running' ? 'not_connected' : 'starting'
}

/** Tray line. */
export function chromeTrayLabel(state: ChromeState): string {
  switch (state) {
    case 'connected': return 'Chrome：已连接'
    case 'unavailable': return 'Chrome：不可用（安装包缺少 byCLI）'
    default: return 'Chrome：未连接'
  }
}

/** Settings page status line. */
export function chromeSettingsLabel(state: ChromeState): string {
  switch (state) {
    case 'connected': return '已连接'
    case 'starting': return '正在启动…'
    case 'not_connected': return '未安装扩展（或 Chrome 没有打开）'
    default: return '不可用：这个安装包没有带 byCLI'
  }
}

/** The node's capabilities with `browser` added (it is only advertised while Chrome is connected). */
export function withBrowser(capabilities: readonly Capability[]): Capability[] {
  return capabilities.includes('browser') ? [...capabilities] : [...capabilities, 'browser']
}
