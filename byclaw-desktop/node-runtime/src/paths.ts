/**
 * Path confinement for device tools: a path is usable only inside an allowed dir and never inside
 * a forbidden dir, checked both lexically and after resolving symlinks.
 */

import fs from 'node:fs/promises'
import os from 'node:os'
import path from 'node:path'

/** Raised when a path is outside the allowed dirs or inside a forbidden dir (result code `not_allowed`). */
export class NotAllowedError extends Error {
  readonly code = 'not_allowed' as const
  /** True when the path is outside every allowed dir (as opposed to forbidden or malformed). */
  readonly outsideAllowed: boolean
  constructor(message: string, outsideAllowed = false) {
    super(message)
    this.outsideAllowed = outsideAllowed
  }
}

/** The dirs a node may touch. */
export interface PathPolicy {
  allowedDirs: readonly string[]
  forbiddenDirs: readonly string[]
}

/** A policy with every dir resolved; `allowedAliases` are the allowed dirs as configured (pre-realpath). */
export interface ResolvedPolicy extends PathPolicy {
  allowedAliases: readonly string[]
}

/** A confined path and the allowed dir that contains it. */
export interface ConfinedPath {
  /** Absolute path with symlinks resolved (for a new file: resolved parent + basename). */
  real: string
  /** The resolved allowed dir containing `real`. */
  root: string
}

/** True when `child` is `parent` or below it. Both must be absolute and normalized. */
export function isWithin(child: string, parent: string): boolean {
  if (child === parent) return true
  const prefix = parent.endsWith(path.sep) ? parent : parent + path.sep
  return child.startsWith(prefix)
}

/** Expand a leading `~` and make the path absolute and normalized; relative paths are rejected. */
export function normalizeUserPath(input: string, home = os.homedir()): string {
  if (typeof input !== 'string' || input.trim() === '') throw new NotAllowedError('路径为空')
  if (input.includes('\0')) throw new NotAllowedError('路径包含非法字符')
  let value = input
  if (value === '~') value = home
  else if (value.startsWith('~/')) value = path.join(home, value.slice(2))
  if (!path.isAbsolute(value)) throw new NotAllowedError(`需要绝对路径：${input}`)
  return path.resolve(value)
}

/**
 * Directories no device tool may ever touch, whatever the user allowed: SSH keys, the keychain,
 * browser profiles and this client's own data (device token, audit log, grants).
 */
export function defaultForbiddenDirs(options: { home?: string, platform?: NodeJS.Platform, ownDataDirs?: readonly string[] } = {}): string[] {
  const home = options.home ?? os.homedir()
  const platform = options.platform ?? process.platform
  const h = (...parts: string[]) => path.join(home, ...parts)
  const dirs = [h('.ssh'), h('.gnupg')]
  if (platform === 'darwin') {
    dirs.push(
      h('Library', 'Keychains'),
      h('Library', 'Cookies'),
      h('Library', 'Safari'),
      h('Library', 'Containers', 'com.apple.Safari'),
      h('Library', 'Application Support', 'Google', 'Chrome'),
      h('Library', 'Application Support', 'Chromium'),
      h('Library', 'Application Support', 'Firefox'),
      h('Library', 'Application Support', 'Microsoft Edge'),
      h('Library', 'Application Support', 'BraveSoftware'),
    )
  }
  else {
    dirs.push(
      h('.local', 'share', 'keyrings'),
      h('.config', 'google-chrome'),
      h('.config', 'chromium'),
      h('.config', 'microsoft-edge'),
      h('.config', 'BraveSoftware'),
      h('.mozilla'),
    )
  }
  dirs.push(...(options.ownDataDirs ?? []))
  return dirs.map(dir => path.resolve(dir))
}

async function realpathOrSelf(p: string): Promise<string> {
  try {
    return await fs.realpath(p)
  }
  catch {
    return p
  }
}

/** Resolve the policy dirs once (macOS `/tmp` -> `/private/tmp` etc.). Missing allowed dirs are dropped. */
export async function resolvePolicy(policy: PathPolicy): Promise<ResolvedPolicy> {
  const allowed: string[] = []
  const aliases: string[] = []
  for (const dir of policy.allowedDirs) {
    try {
      const real = await fs.realpath(path.resolve(dir))
      if ((await fs.stat(real)).isDirectory()) {
        allowed.push(real)
        aliases.push(path.resolve(dir))
      }
    }
    catch {
      // An allowed dir that no longer exists grants nothing.
    }
  }
  const forbidden = new Set<string>()
  for (const dir of policy.forbiddenDirs) {
    const lexical = path.resolve(dir)
    forbidden.add(lexical)
    forbidden.add(await realpathOrSelf(lexical))
  }
  return { allowedDirs: allowed, allowedAliases: aliases, forbiddenDirs: [...forbidden] }
}

function check(candidate: string, forbiddenDirs: readonly string[], allowedDirs: readonly string[], original: string): string {
  const forbidden = forbiddenDirs.find(dir => isWithin(candidate, dir))
  if (forbidden !== undefined) throw new NotAllowedError(`禁止访问：${original}`)
  const root = allowedDirs.find(dir => isWithin(candidate, dir))
  if (root === undefined) throw new NotAllowedError(`不在授权目录内：${original}`, true)
  return root
}

/** True when `dir` (resolved) may be added as an allowed dir: not inside or equal to a forbidden dir. */
export async function canAllowDir(dir: string, forbiddenDirs: readonly string[]): Promise<boolean> {
  const real = await realpathOrSelf(path.resolve(dir))
  const { forbiddenDirs: forbidden } = await resolvePolicy({ allowedDirs: [], forbiddenDirs })
  return !forbidden.some(f => isWithin(real, f))
}

/**
 * Confine `input` for reading (existing path) or writing (existing file, or a new file whose
 * parent exists). The lexical path is checked first so nothing outside the allowed dirs is even
 * stat-ed; then symlinks are resolved and the result checked again.
 * @param policy - Resolved policy (see resolvePolicy).
 * @throws NotAllowedError, or the fs error (ENOENT...) for a missing path inside the allowed dirs.
 */
export async function confinePath(input: string, policy: ResolvedPolicy, mode: 'read' | 'write'): Promise<ConfinedPath> {
  const lexical = normalizeUserPath(input)
  check(lexical, policy.forbiddenDirs, [...policy.allowedAliases, ...policy.allowedDirs], input)
  let real: string
  try {
    real = await fs.realpath(lexical)
  }
  catch (error) {
    if ((error as NodeJS.ErrnoException).code !== 'ENOENT' || mode === 'read') throw error
    // A dangling symlink would let a write create its target outside the allowed dirs.
    const link = await fs.lstat(lexical).catch(() => undefined)
    if (link?.isSymbolicLink()) throw new NotAllowedError(`符号链接指向不存在的目标：${input}`)
    const parent = await fs.realpath(path.dirname(lexical))
    real = path.join(parent, path.basename(lexical))
  }
  const root = check(real, policy.forbiddenDirs, policy.allowedDirs, input)
  return { real, root }
}

/** Confine a working directory for device_shell: an existing directory inside the allowed dirs. */
export async function confineDir(input: string, policy: ResolvedPolicy): Promise<string> {
  const { real } = await confinePath(input, policy, 'read')
  if (!(await fs.stat(real)).isDirectory()) throw new Error(`不是目录：${input}`)
  return real
}
