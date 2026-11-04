/** App settings kept in `<userData>/config.json` (no secrets: the device token lives in token-store). */

import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { CAPABILITIES, relayUrlFor, type Capability } from '@byclaw/node-runtime'

export interface AppConfig {
  /** ByClaw web URL the window loads, e.g. `https://byclaw.example.com/beyond/`. */
  webUrl?: string
  /** Relay override; default derived from webUrl (same origin, `/device-relay/node`). */
  relayUrl?: string
  /** Pair API base override; default the web origin (FE nginx proxies `/byaiService`). */
  apiBaseUrl?: string
  /** Extra origins the window may navigate to (SSO). */
  extraOrigins: string[]
  /** Optional login entry page (e.g. a test env's mock login); loaded while unpaired, its origin is allowed. */
  loginUrl?: string
  deviceName?: string
  deviceId?: string
  allowedDirs: string[]
  capabilities: Capability[]
  launchAtLogin: boolean
  /** "暂停我的电脑" survives restarts. */
  paused: boolean
}

export const DEFAULT_CONFIG: AppConfig = {
  extraOrigins: [],
  allowedDirs: [],
  capabilities: [...CAPABILITIES],
  launchAtLogin: true,
  paused: false,
}

/** Normalize a user-entered web URL; throws on anything that is not http(s). */
export function normalizeWebUrl(input: string): string {
  const trimmed = input.trim()
  const url = new URL(/^[a-z]+:\/\//i.test(trimmed) ? trimmed : `https://${trimmed}`)
  if (url.protocol !== 'http:' && url.protocol !== 'https:') throw new Error('只支持 http 或 https 地址')
  url.hash = ''
  return url.toString()
}

function strings(value: unknown): string[] {
  return Array.isArray(value) ? value.filter((v): v is string => typeof v === 'string') : []
}

/** Read the config file, tolerating a missing or partly broken file. */
export function loadConfig(file: string): AppConfig {
  let raw: Record<string, unknown> = {}
  try {
    raw = JSON.parse(fs.readFileSync(file, 'utf8')) as Record<string, unknown>
  }
  catch {
    // First run or unreadable: defaults.
  }
  const caps = strings(raw.capabilities).filter((c): c is Capability => (CAPABILITIES as readonly string[]).includes(c))
  const config: AppConfig = {
    ...DEFAULT_CONFIG,
    extraOrigins: strings(raw.extraOrigins),
    allowedDirs: strings(raw.allowedDirs),
    capabilities: Array.isArray(raw.capabilities) ? caps : [...CAPABILITIES],
    launchAtLogin: typeof raw.launchAtLogin === 'boolean' ? raw.launchAtLogin : true,
    paused: raw.paused === true,
  }
  for (const key of ['webUrl', 'loginUrl', 'relayUrl', 'apiBaseUrl', 'deviceName', 'deviceId'] as const) {
    const value = raw[key]
    if (typeof value === 'string' && value !== '') config[key] = value
  }
  return config
}

export function saveConfig(file: string, config: AppConfig): void {
  fs.mkdirSync(path.dirname(file), { recursive: true })
  const tmp = `${file}.tmp`
  fs.writeFileSync(tmp, `${JSON.stringify(config, null, 2)}\n`, { mode: 0o600 })
  fs.renameSync(tmp, file)
}

/** The web URL in effect: `BYCLAW_DESKTOP_URL` (dev) wins over the config file. */
export function effectiveWebUrl(config: AppConfig, env: NodeJS.ProcessEnv = process.env): string | undefined {
  const fromEnv = env.BYCLAW_DESKTOP_URL
  if (fromEnv) return normalizeWebUrl(fromEnv)
  return config.webUrl
}

export function effectiveRelayUrl(config: AppConfig, webUrl: string, env: NodeJS.ProcessEnv = process.env): string {
  return env.BYCLAW_DESKTOP_RELAY_URL || config.relayUrl || relayUrlFor(webUrl)
}

export function effectiveApiBase(config: AppConfig, webUrl: string): string {
  return config.apiBaseUrl || new URL(webUrl).origin
}

/** Default device name: the host name without macOS's `.local`. */
export function defaultDeviceName(hostname = os.hostname()): string {
  return hostname.replace(/\.local$/i, '') || 'My Computer'
}
