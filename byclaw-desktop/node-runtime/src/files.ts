/** device_files: list / read / write / search, confined to the allowed dirs. */

import fs from 'node:fs/promises'
import path from 'node:path'
import { confinePath, isWithin, type ResolvedPolicy } from './paths.js'

/** Per read/write cap (PRD §6). */
export const MAX_FILE_BYTES = 8 * 1024 * 1024

export const FILE_ACTIONS = ['list', 'read', 'write', 'search'] as const
export type FileAction = typeof FILE_ACTIONS[number]

/** Search caps; exceeded caps end the walk and are reported in the output. */
export interface SearchLimits {
  maxNameMatches: number
  maxContentMatches: number
  maxFilesVisited: number
  maxDepth: number
  /** Files larger than this are matched by name only. */
  maxGrepBytes: number
  timeBudgetMs: number
}

export const DEFAULT_SEARCH_LIMITS: SearchLimits = {
  maxNameMatches: 200,
  maxContentMatches: 200,
  maxFilesVisited: 20_000,
  maxDepth: 12,
  maxGrepBytes: 1024 * 1024,
  timeBudgetMs: 10_000,
}

export const MAX_LIST_ENTRIES = 1000

/** Directory names the search walk never descends into (noise, not a security boundary). */
const SEARCH_SKIP = new Set(['.git', 'node_modules', '.Trash'])

export class FileToolError extends Error {}

export interface FileArgs {
  action: FileAction
  path: string
  content?: string
  query?: string
}

/** Validate raw invoke args. */
export function parseFileArgs(args: Record<string, unknown>): FileArgs {
  const action = args.action
  if (typeof action !== 'string' || !(FILE_ACTIONS as readonly string[]).includes(action)) throw new FileToolError(`未知的 action：${String(action)}`)
  if (typeof args.path !== 'string' || args.path === '') throw new FileToolError('缺少 path')
  const parsed: FileArgs = { action: action as FileAction, path: args.path }
  if (action === 'write') {
    if (typeof args.content !== 'string') throw new FileToolError('action=write 需要 content')
    parsed.content = args.content
  }
  if (action === 'search') {
    if (typeof args.query !== 'string' || args.query === '') throw new FileToolError('action=search 需要 query')
    parsed.query = args.query
  }
  return parsed
}

/** True for list / read / search, the actions that only read. */
export function isReadAction(action: FileAction): boolean {
  return action !== 'write'
}

function looksBinary(buffer: Buffer): boolean {
  return buffer.subarray(0, 8192).includes(0)
}

async function list(target: string, policy: ResolvedPolicy): Promise<string> {
  const stat = await fs.stat(target)
  if (!stat.isDirectory()) throw new FileToolError(`不是目录：${target}`)
  const names = (await fs.readdir(target)).sort()
  const entries: Array<{ name: string, type: string, size?: number }> = []
  let hidden = 0
  for (const name of names) {
    if (entries.length >= MAX_LIST_ENTRIES) break
    const full = path.join(target, name)
    if (policy.forbiddenDirs.some(dir => isWithin(full, dir))) {
      hidden++
      continue
    }
    const entry = await fs.lstat(full).catch(() => undefined)
    if (!entry) continue
    const type = entry.isDirectory() ? 'dir' : entry.isFile() ? 'file' : entry.isSymbolicLink() ? 'symlink' : 'other'
    entries.push(type === 'file' ? { name, type, size: entry.size } : { name, type })
  }
  return JSON.stringify({
    path: target,
    entries,
    ...(names.length - hidden > entries.length ? { truncated: true, total: names.length - hidden } : {}),
  })
}

async function read(target: string): Promise<string> {
  const stat = await fs.stat(target)
  if (stat.isDirectory()) throw new FileToolError(`是目录，请用 action=list：${target}`)
  if (stat.size > MAX_FILE_BYTES) throw new FileToolError(`文件 ${stat.size} 字节，超过单次读取上限 ${MAX_FILE_BYTES} 字节`)
  const buffer = await fs.readFile(target)
  if (buffer.length > MAX_FILE_BYTES) throw new FileToolError(`文件超过单次读取上限 ${MAX_FILE_BYTES} 字节`)
  if (looksBinary(buffer)) return `[二进制文件，${buffer.length} 字节，未返回内容]`
  return buffer.toString('utf8')
}

async function write(target: string, content: string): Promise<string> {
  const bytes = Buffer.byteLength(content, 'utf8')
  if (bytes > MAX_FILE_BYTES) throw new FileToolError(`内容 ${bytes} 字节，超过单次写入上限 ${MAX_FILE_BYTES} 字节`)
  const existing = await fs.stat(target).catch(() => undefined)
  if (existing?.isDirectory()) throw new FileToolError(`是目录，不能写入：${target}`)
  await fs.writeFile(target, content, 'utf8')
  return `已写入 ${bytes} 字节：${target}`
}

async function search(root: string, query: string, policy: ResolvedPolicy, limits: SearchLimits): Promise<string> {
  const rootStat = await fs.stat(root)
  if (!rootStat.isDirectory()) throw new FileToolError(`不是目录：${root}`)
  const needle = query.toLowerCase()
  const nameMatches: string[] = []
  const contentMatches: string[] = []
  const deadline = Date.now() + limits.timeBudgetMs
  let visited = 0
  let stopped: string | undefined
  const walk = async (dir: string, depth: number): Promise<void> => {
    let names: string[]
    try {
      names = (await fs.readdir(dir)).sort()
    }
    catch {
      return
    }
    for (const name of names) {
      if (stopped) return
      if (Date.now() > deadline) { stopped = '超过搜索时间'; return }
      if (++visited > limits.maxFilesVisited) { stopped = `已检查 ${limits.maxFilesVisited} 个条目`; return }
      const full = path.join(dir, name)
      if (policy.forbiddenDirs.some(f => isWithin(full, f))) continue
      // lstat: the walk never follows symlinks, so it cannot leave the allowed dir.
      const entry = await fs.lstat(full).catch(() => undefined)
      if (!entry || entry.isSymbolicLink()) continue
      if (name.toLowerCase().includes(needle)) {
        nameMatches.push(full)
        if (nameMatches.length >= limits.maxNameMatches) { stopped = `文件名匹配达到 ${limits.maxNameMatches} 条`; return }
      }
      if (entry.isDirectory()) {
        if (!SEARCH_SKIP.has(name) && depth < limits.maxDepth) await walk(full, depth + 1)
        continue
      }
      if (!entry.isFile() || entry.size > limits.maxGrepBytes || contentMatches.length >= limits.maxContentMatches) continue
      const buffer = await fs.readFile(full).catch(() => undefined)
      if (!buffer || looksBinary(buffer)) continue
      const lines = buffer.toString('utf8').split('\n')
      for (let i = 0; i < lines.length; i++) {
        const line = lines[i] as string
        if (!line.toLowerCase().includes(needle)) continue
        const text = line.length > 200 ? `${line.slice(0, 200)}…` : line
        contentMatches.push(`${full}:${i + 1}: ${text.trim()}`)
        if (contentMatches.length >= limits.maxContentMatches) break
      }
    }
  }
  await walk(root, 0)
  const parts = [
    `文件名包含「${query}」：${nameMatches.length} 个`,
    ...nameMatches,
    '',
    `内容包含「${query}」：${contentMatches.length} 处`,
    ...contentMatches,
  ]
  if (stopped) parts.push('', `[搜索提前结束：${stopped}]`)
  return parts.join('\n')
}

/**
 * Run one device_files call. Confinement happens before anything is read or written.
 * @throws NotAllowedError (code not_allowed), FileToolError or an fs error (code failed).
 */
export async function runFileTool(args: FileArgs, policy: ResolvedPolicy, limits: SearchLimits = DEFAULT_SEARCH_LIMITS): Promise<string> {
  const { real } = await confinePath(args.path, policy, args.action === 'write' ? 'write' : 'read')
  switch (args.action) {
    case 'list': return list(real, policy)
    case 'read': return read(real)
    case 'write': return write(real, args.content as string)
    case 'search': return search(real, args.query as string, policy, limits)
  }
}


