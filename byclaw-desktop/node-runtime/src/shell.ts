/** device_shell: run a command in the user's login-shell environment, with timeout, cancel and truncation. */

import { spawn } from 'node:child_process'
import os from 'node:os'

/** Per-stream output cap (issue 05). */
export const MAX_STREAM_BYTES = 64 * 1024
export const DEFAULT_SHELL_TIMEOUT_MS = 120_000

/** Collects at most `limit` bytes, counting the rest. */
export class CappedBuffer {
  private readonly chunks: Buffer[] = []
  private kept = 0
  total = 0
  constructor(private readonly limit = MAX_STREAM_BYTES) {}

  push(chunk: Buffer): void {
    this.total += chunk.length
    const room = this.limit - this.kept
    if (room <= 0) return
    const part = chunk.length > room ? chunk.subarray(0, room) : chunk
    this.chunks.push(part)
    this.kept += part.length
  }

  get truncated(): boolean {
    return this.total > this.kept
  }

  /** The kept bytes as UTF-8, with a marker when bytes were dropped. */
  text(): string {
    const body = Buffer.concat(this.chunks).toString('utf8')
    return this.truncated ? `${body}\n[输出已截断：共 ${this.total} 字节，只保留前 ${this.kept} 字节]` : body
  }
}

/** Truncate a string to `limit` UTF-8 bytes with the same marker as shell output. */
export function truncateText(text: string, limit = MAX_STREAM_BYTES): string {
  const buffer = new CappedBuffer(limit)
  buffer.push(Buffer.from(text, 'utf8'))
  return buffer.text()
}

/** Environment variable names never passed to device commands (the client's own settings and secrets). */
const SCRUB_PATTERNS = [/^BYCLAW_/i, /^ELECTRON_/i, /DEVICE[_-]?TOKEN/i, /^NODE_OPTIONS$/, /^NODE_CHANNEL_FD$/, /^CHROME_DESKTOP$/]

/**
 * Remove the client's own variables from an environment: names matching BYCLAW_* / ELECTRON_* /
 * *DEVICE_TOKEN* and anything whose value contains one of `secrets` (the device token, login token).
 */
export function scrubEnv(env: NodeJS.ProcessEnv, secrets: readonly string[] = []): Record<string, string> {
  const live = secrets.filter(s => s.length >= 8)
  const out: Record<string, string> = {}
  for (const [key, value] of Object.entries(env)) {
    if (value === undefined) continue
    if (SCRUB_PATTERNS.some(re => re.test(key))) continue
    if (live.some(secret => value.includes(secret))) continue
    out[key] = value
  }
  return out
}

/** The user's shell: $SHELL, else zsh on macOS and sh elsewhere. */
export function userShell(env: NodeJS.ProcessEnv = process.env): string {
  return env.SHELL || (process.platform === 'darwin' ? '/bin/zsh' : '/bin/sh')
}

const ENV_MARK = '__BYCLAW_ENV_7f3a__'

/**
 * Capture the login-shell environment once (`$SHELL -ilc` printing process.env through this
 * runtime's own executable). GUI apps on macOS start without the user's shell profile, so PATH etc.
 * would otherwise be the bare launchd environment.
 * @returns the captured environment, or undefined on failure/timeout (caller falls back to process.env).
 */
export async function captureLoginEnv(options: { shell?: string, timeoutMs?: number } = {}): Promise<NodeJS.ProcessEnv | undefined> {
  const shell = options.shell ?? userShell()
  const timeoutMs = options.timeoutMs ?? 10_000
  const script = `process.stdout.write(${JSON.stringify(ENV_MARK)} + JSON.stringify(process.env) + ${JSON.stringify(ENV_MARK)})`
  const command = `'${process.execPath.replace(/'/g, `'\\''`)}' -e '${script.replace(/'/g, `'\\''`)}'`
  return new Promise(resolve => {
    let child
    try {
      child = spawn(shell, ['-ilc', command], {
        detached: true,
        stdio: ['ignore', 'pipe', 'ignore'],
        env: { ...process.env, ELECTRON_RUN_AS_NODE: '1', ELECTRON_NO_ATTACH_CONSOLE: '1' },
      })
    }
    catch {
      resolve(undefined)
      return
    }
    const out = new CappedBuffer(4 * 1024 * 1024)
    const timer = setTimeout(() => {
      killGroup(child.pid, 'SIGKILL')
      resolve(undefined)
    }, timeoutMs)
    child.stdout?.on('data', (chunk: Buffer) => out.push(chunk))
    child.on('error', () => {
      clearTimeout(timer)
      resolve(undefined)
    })
    child.on('close', () => {
      clearTimeout(timer)
      const text = out.text()
      const start = text.indexOf(ENV_MARK)
      const end = text.lastIndexOf(ENV_MARK)
      if (start < 0 || end <= start) return resolve(undefined)
      try {
        const env = JSON.parse(text.slice(start + ENV_MARK.length, end)) as NodeJS.ProcessEnv
        delete env.ELECTRON_RUN_AS_NODE
        delete env.ELECTRON_NO_ATTACH_CONSOLE
        resolve(env)
      }
      catch {
        resolve(undefined)
      }
    })
  })
}

function killGroup(pid: number | undefined, signal: NodeJS.Signals): void {
  if (pid === undefined) return
  try {
    process.kill(-pid, signal)
  }
  catch {
    try {
      process.kill(pid, signal)
    }
    catch {
      // Already gone.
    }
  }
}

export interface ShellRun {
  command: string
  cwd: string
  env: Record<string, string>
  shell?: string
  timeoutMs?: number
  signal?: AbortSignal
}

export interface ShellOutcome {
  exitCode: number | null
  signal: NodeJS.Signals | null
  stdout: string
  stderr: string
  timedOut: boolean
  cancelled: boolean
  ms: number
}

/**
 * Run `command` with `$SHELL -c` in its own process group. Timeout and abort kill the whole group
 * (SIGTERM, then SIGKILL after 2 s), so children started by the command die with it.
 */
export function runShell(run: ShellRun): Promise<ShellOutcome> {
  const started = Date.now()
  const timeoutMs = run.timeoutMs ?? DEFAULT_SHELL_TIMEOUT_MS
  return new Promise((resolve, reject) => {
    const child = spawn(run.shell ?? userShell(run.env), ['-c', run.command], {
      cwd: run.cwd,
      env: run.env,
      detached: true,
      stdio: ['ignore', 'pipe', 'pipe'],
    })
    const stdout = new CappedBuffer()
    const stderr = new CappedBuffer()
    let timedOut = false
    let cancelled = false
    let settled = false
    let forceTimer: NodeJS.Timeout | undefined
    let exitInfo: { code: number | null, signal: NodeJS.Signals | null } | undefined
    const finish = () => {
      if (settled) return
      settled = true
      clearTimeout(timer)
      clearTimeout(forceTimer)
      run.signal?.removeEventListener('abort', onAbort)
      resolve({
        exitCode: exitInfo?.code ?? null,
        signal: exitInfo?.signal ?? null,
        stdout: stdout.text(),
        stderr: stderr.text(),
        timedOut,
        cancelled,
        ms: Date.now() - started,
      })
    }
    const terminate = () => {
      killGroup(child.pid, 'SIGTERM')
      forceTimer = setTimeout(() => {
        killGroup(child.pid, 'SIGKILL')
        // Pipes held open by an escaped grandchild must not keep the call pending.
        setTimeout(finish, 1000).unref()
      }, 2000)
      forceTimer.unref()
    }
    const onAbort = () => {
      cancelled = true
      terminate()
    }
    const timer = setTimeout(() => {
      timedOut = true
      terminate()
    }, timeoutMs)
    child.stdout.on('data', (chunk: Buffer) => stdout.push(chunk))
    child.stderr.on('data', (chunk: Buffer) => stderr.push(chunk))
    child.on('error', error => {
      if (settled) return
      settled = true
      clearTimeout(timer)
      clearTimeout(forceTimer)
      run.signal?.removeEventListener('abort', onAbort)
      reject(error)
    })
    child.on('exit', (code, signal) => {
      exitInfo = { code, signal }
    })
    child.on('close', (code, signal) => {
      exitInfo ??= { code, signal }
      // The group may still hold children after a normal exit; leave them to the user's command.
      finish()
    })
    if (run.signal?.aborted) onAbort()
    else run.signal?.addEventListener('abort', onAbort, { once: true })
  })
}

/** Default cwd for device_shell when the model gives none. */
export function defaultShellCwd(): string {
  return os.homedir()
}
