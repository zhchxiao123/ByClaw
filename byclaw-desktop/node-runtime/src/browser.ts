/**
 * device_browser: the user's own Chrome through byCLI (`bycli browser <session> <subcommand…>`).
 * Pure parts live here: tokenizing the model's command line, the subcommand whitelist, the site a
 * command touches, prompt and audit text, and screenshot packaging for `result.images`.
 */

import crypto from 'node:crypto'
import { clip } from './audit.js'
import { decodePng, downscale, encodePng, pngSize, PngError } from './png.js'
import { MAX_IMAGES_BASE64, type ResultImage } from './protocol.js'

export class BrowserArgError extends Error {}

/** Longest command line accepted (typed text included). */
export const MAX_COMMAND_LENGTH = 16 * 1024

/**
 * Split a command line like a POSIX shell would, without any expansion: whitespace separates,
 * '…' is literal, "…" honours \\ \" \$ \` escapes, a bare backslash escapes the next character.
 * Nothing is ever run through a shell; `$(…)`, globs and `;` are plain text.
 */
export function tokenize(command: string): string[] {
  if (command.length > MAX_COMMAND_LENGTH) throw new BrowserArgError(`command 太长（超过 ${MAX_COMMAND_LENGTH} 个字符）`)
  const tokens: string[] = []
  let current = ''
  let inToken = false
  let i = 0
  while (i < command.length) {
    const ch = command[i] as string
    if (ch === "'") {
      const end = command.indexOf("'", i + 1)
      if (end < 0) throw new BrowserArgError('引号没有闭合（\'）')
      current += command.slice(i + 1, end)
      inToken = true
      i = end + 1
    }
    else if (ch === '"') {
      i++
      let closed = false
      while (i < command.length) {
        const c = command[i] as string
        if (c === '"') {
          closed = true
          i++
          break
        }
        if (c === '\\' && i + 1 < command.length && '\\"$`\n'.includes(command[i + 1] as string)) {
          if (command[i + 1] !== '\n') current += command[i + 1]
          i += 2
          continue
        }
        current += c
        i++
      }
      if (!closed) throw new BrowserArgError('引号没有闭合（"）')
      inToken = true
    }
    else if (ch === '\\') {
      if (i + 1 < command.length) {
        if (command[i + 1] !== '\n') current += command[i + 1]
        inToken = true
      }
      i += 2
    }
    else if (/\s/.test(ch)) {
      if (inToken) tokens.push(current)
      current = ''
      inToken = false
      i++
    }
    else {
      current += ch
      inToken = true
      i++
    }
  }
  if (inToken) tokens.push(current)
  return tokens
}

/**
 * How a subcommand is confirmed (PRD P2a "本机确认"):
 * - `read`: no prompt on an allowed site; first access to a site asks.
 * - `interact`: same; clicking, typing etc. on an allowed site is not asked again.
 * - `navigate`: the site is the target URL's host, not the current tab's.
 * - `always`: eval / upload, asked every time.
 * - `bind`: attaches the user's focused tab, always asked (title and URL shown).
 * - `session`: releases this session's tab or binding; never asked.
 */
export type BrowserKind = 'read' | 'interact' | 'navigate' | 'always' | 'bind' | 'session'

const SUBCOMMANDS: Record<string, BrowserKind> = {
  state: 'read',
  find: 'read',
  frames: 'read',
  screenshot: 'read',
  get: 'read',
  extract: 'read',
  wait: 'read',
  console: 'read',
  click: 'interact',
  dblclick: 'interact',
  hover: 'interact',
  focus: 'interact',
  type: 'interact',
  fill: 'interact',
  select: 'interact',
  check: 'interact',
  uncheck: 'interact',
  drag: 'interact',
  keys: 'interact',
  scroll: 'interact',
  back: 'interact',
  dialog: 'interact',
  open: 'navigate',
  eval: 'always',
  upload: 'always',
  bind: 'bind',
  close: 'session',
  unbind: 'session',
}

/** Second-level words for subcommands that have them. */
const NESTED: Record<string, Record<string, BrowserKind>> = {
  get: { title: 'read', url: 'read', text: 'read', value: 'read', html: 'read', attributes: 'read' },
  tab: { list: 'read', new: 'navigate', select: 'interact', close: 'interact' },
  dialog: { accept: 'interact', dismiss: 'interact' },
}

/** Subcommands (and `tab …`) the model may use; anything else is refused. */
export const ALLOWED_SUBCOMMANDS = [...Object.keys(SUBCOMMANDS), 'tab'].sort()

/** Options that would leave the session/profile the confirmation is about, or write files. */
const BLOCKED_OPTIONS = new Set(['--session', '--profile', '--diagnostics', '--write-fixture', '--update-fixture'])

/** Options whose value is the next token (so it is not mistaken for a positional). */
const VALUE_OPTIONS = new Set([
  '--tab', '--nth', '--role', '--name', '--label', '--text', '--testid', '--frame', '--source', '--css', '--limit',
  '--text-max', '--selector', '--as', '--depth', '--children-max', '--max', '--timeout', '--amount', '--width',
  '--height', '--dpr', '--chunk-size', '--start', '--window', '--from-role', '--from-name', '--to-role', '--to-name',
  '--level', '--since',
])
const LOCATOR_OPTIONS = new Set(['--role', '--name', '--label', '--text', '--testid'])

export interface BrowserCommand {
  /** e.g. `click`, `get url`, `tab new`. */
  sub: string
  kind: BrowserKind
  /** Arguments after `browser <session>`, with a navigate URL normalized. */
  args: string[]
  /** Positionals after the subcommand words. */
  positionals: string[]
  /** `--tab <id>` when given: the tab whose site matters. */
  tab?: string
  /** navigate: the normalized target URL (absent for a blank `tab new`). */
  url?: string
  /** upload: file arguments. */
  files?: string[]
  /** `--timeout <ms>` of `wait`. */
  waitTimeoutMs?: number
}

/** Normalize a URL the model wants to open: http(s) only (a bare host gets https://). */
export function normalizeTargetUrl(input: string): string {
  const raw = input.trim()
  if (raw === 'about:blank') return raw
  let url: URL
  try {
    url = new URL(/^[a-z][a-z0-9+.-]*:/i.test(raw) && !/^[^/:]+:\d+(\/|$)/.test(raw) ? raw : `https://${raw}`)
  }
  catch {
    throw new BrowserArgError(`不是有效的网址：${clip(raw, 200)}`)
  }
  if (url.protocol !== 'http:' && url.protocol !== 'https:') throw new BrowserArgError(`只能打开 http 或 https 网址：${clip(raw, 200)}`)
  return url.toString()
}

/** Parse the model's `command` into a whitelisted byCLI browser command. */
export function parseBrowserCommand(command: string): BrowserCommand {
  let tokens = tokenize(command)
  // Tolerate a copied `bycli browser` prefix (the session comes from the `session` argument).
  if (tokens[0] === 'bycli') tokens = tokens.slice(1)
  if (tokens[0] === 'browser') tokens = tokens.slice(1)
  const first = tokens[0]
  if (first === undefined) throw new BrowserArgError('command 为空，例如 state、click 12、screenshot')
  let kind: BrowserKind | undefined = Object.hasOwn(SUBCOMMANDS, first) ? SUBCOMMANDS[first] : undefined
  let sub = first
  let rest = tokens.slice(1)
  const nested = Object.hasOwn(NESTED, first) ? NESTED[first] : undefined
  if (nested) {
    const second = rest[0]
    if (second === undefined || !Object.hasOwn(nested, second)) {
      throw new BrowserArgError(`不支持的子命令：${first} ${second ?? ''}（可用：${Object.keys(nested).map(n => `${first} ${n}`).join('、')}）`)
    }
    kind = nested[second]
    sub = `${first} ${second}`
    rest = rest.slice(1)
  }
  if (kind === undefined) throw new BrowserArgError(`不支持的子命令：${first}（可用：${ALLOWED_SUBCOMMANDS.join('、')}）`)

  const positionals: string[] = []
  let tab: string | undefined
  let waitTimeoutMs: number | undefined
  let locator = false
  for (let i = 0; i < rest.length; i++) {
    const token = rest[i] as string
    if (token === '--') {
      positionals.push(...rest.slice(i + 1))
      break
    }
    if (token.startsWith('--')) {
      const eq = token.indexOf('=')
      const name = eq < 0 ? token : token.slice(0, eq)
      if (BLOCKED_OPTIONS.has(name)) throw new BrowserArgError(`不允许使用选项 ${name}`)
      let value: string | undefined = eq < 0 ? undefined : token.slice(eq + 1)
      if (value === undefined && VALUE_OPTIONS.has(name) && i + 1 < rest.length) value = rest[++i]
      if (name === '--tab') tab = value
      if (name === '--timeout' && value !== undefined && /^\d+$/.test(value)) waitTimeoutMs = Number(value)
      if (LOCATOR_OPTIONS.has(name)) locator = true
      continue
    }
    positionals.push(token)
  }

  const parsed: BrowserCommand = { sub, kind, args: [...tokens], positionals, ...(tab !== undefined ? { tab } : {}), ...(waitTimeoutMs !== undefined ? { waitTimeoutMs } : {}) }
  if (sub === 'open' || sub === 'tab new') {
    const target = positionals[0]
    if (target === undefined && sub === 'open') throw new BrowserArgError('open 需要网址，例如 open https://example.com')
    if (target !== undefined) {
      const url = normalizeTargetUrl(target)
      parsed.url = url
      const at = tokens.lastIndexOf(target)
      parsed.args[at] = url
    }
  }
  if (sub === 'screenshot' && positionals.length > 0) {
    throw new BrowserArgError('screenshot 不接受保存路径；截图会直接回传')
  }
  if (sub === 'upload') {
    const files = locator ? positionals : positionals.slice(positionals.length > 1 ? 1 : 0)
    if (files.length === 0) throw new BrowserArgError('upload 需要文件路径，例如 upload 12 /Users/me/a.pdf')
    parsed.files = files
  }
  return parsed
}

/**
 * The site a URL belongs to, for confirmation: the hostname for http(s), "" for a blank page, and
 * the scheme (plus host) for anything else, which is confirmed like any other site.
 */
export function siteOf(url: string | undefined): string {
  const raw = (url ?? '').trim()
  if (raw === '' || raw === 'about:blank') return ''
  try {
    const parsed = new URL(raw)
    if (parsed.protocol === 'http:' || parsed.protocol === 'https:') return parsed.hostname.toLowerCase()
    return `${parsed.protocol}${parsed.host ? `//${parsed.host}` : ''}`
  }
  catch {
    return clip(raw, 80)
  }
}

/** A byCLI session name from the model's `session` or the A-side session id. */
export function browserSessionName(value: string | undefined): string {
  const raw = (value ?? '').trim()
  if (raw === '') return 'byclaw'
  if (/^[A-Za-z0-9][A-Za-z0-9._:-]{0,63}$/.test(raw)) return raw
  return `s-${crypto.createHash('sha256').update(raw).digest('hex').slice(0, 16)}`
}

/** Subcommands whose positionals carry text the user may consider private (typed text, scripts). */
const TEXT_SUBCOMMANDS = new Set(['type', 'fill', 'eval'])

/**
 * The command as recorded in the audit log and shown in prompts: typed text and scripts are
 * replaced by their length (`eval` prompts show the script separately).
 */
export function redactedCommand(parsed: BrowserCommand): string {
  if (!TEXT_SUBCOMMANDS.has(parsed.sub)) return clip(parsed.args.join(' '), 300)
  const hasLocator = parsed.args.some(a => LOCATOR_OPTIONS.has(a.split('=')[0] as string))
  const keep = parsed.sub === 'eval' || hasLocator ? 0 : parsed.positionals.length > 1 ? 1 : 0
  const secret = parsed.positionals.slice(keep)
  const chars = secret.reduce((n, s) => n + s.length, 0)
  const shown = [parsed.sub, ...parsed.positionals.slice(0, keep)]
  return clip(`${shown.join(' ')} …（${chars} 个字符）`, 300)
}

/** Re-encodes screenshots; Electron provides a JPEG-capable one, the default is PNG-only. */
export interface ImageCodec {
  /** Lossy codecs are tried at lower quality before shrinking. */
  lossy: boolean
  encode(png: Buffer, options: { scale: number, quality: number }): Promise<{ mime: ResultImage['mime'], data: Buffer, width: number, height: number }>
}

/** Pure-JS PNG downscaling (no quality knob). */
export const pngCodec: ImageCodec = {
  lossy: false,
  async encode(png, { scale }) {
    const image = scale >= 1 ? decodePng(png) : downscale(decodePng(png), scale)
    return { mime: 'image/png', data: encodePng(image), width: image.width, height: image.height }
  },
}

export function base64Length(bytes: number): number {
  return Math.ceil(bytes / 3) * 4
}

/** Steps tried in order: first lower quality, then smaller (protocol §3 "图片"). */
function steps(codec: ImageCodec): Array<{ scale: number, quality: number }> {
  const scales = [0.75, 0.5, 0.35, 0.25, 0.15, 0.1]
  return codec.lossy
    ? [{ scale: 1, quality: 80 }, { scale: 1, quality: 60 }, { scale: 1, quality: 40 }, ...scales.map(scale => ({ scale, quality: 40 }))]
    : scales.map(scale => ({ scale, quality: 100 }))
}

export interface PackagedImage {
  image: ResultImage
  /** The screenshot had to be re-encoded or shrunk to fit. */
  reduced: boolean
}

/** Turn a screenshot PNG into a result image whose base64 fits `maxBase64`. */
export async function packageScreenshot(png: Buffer, options: { codec?: ImageCodec, maxBase64?: number } = {}): Promise<PackagedImage> {
  const max = options.maxBase64 ?? MAX_IMAGES_BASE64
  const size = pngSize(png)
  if (!size) throw new PngError('截图不是 PNG')
  if (base64Length(png.length) <= max) {
    return { image: { mime: 'image/png', data: png.toString('base64'), width: size.width, height: size.height }, reduced: false }
  }
  const codec = options.codec ?? pngCodec
  for (const step of steps(codec)) {
    const out = await codec.encode(png, step)
    if (base64Length(out.data.length) <= max) {
      return { image: { mime: out.mime, data: out.data.toString('base64'), width: out.width, height: out.height }, reduced: true }
    }
  }
  throw new PngError(`截图压缩后仍超过 ${Math.round(max / 1024)} KB`)
}

const ACTION_LABELS: Record<'access' | 'eval' | 'upload' | 'bind', string> = {
  access: '访问网站',
  eval: '在网页里运行脚本',
  upload: '向网页上传文件',
  bind: '接管你当前的 Chrome 标签页',
}

export function browserActionLabel(action: keyof typeof ACTION_LABELS): string {
  return ACTION_LABELS[action]
}
