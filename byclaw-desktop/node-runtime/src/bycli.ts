/**
 * The local byCLI (`@sovovs/bycli`): locate the package, run its daemon as our child bound to
 * 127.0.0.1, poll its `/status` for the Chrome extension, and run `bycli` commands without a shell.
 */

import { execFile, spawn, type ChildProcess } from 'node:child_process'
import { EventEmitter } from 'node:events'
import fs from 'node:fs'
import http from 'node:http'
import os from 'node:os'
import path from 'node:path'

/** The byCLI release this client is built and tested against. */
export const BYCLI_VERSION = '2.1.78'
/** The extension connects to this port; it is compiled into the extension. */
export const BYCLI_DAEMON_PORT = 19825

export interface BycliInstall {
  /** Package root (contains package.json, dist/, extension/). */
  packageDir: string
  /** CLI entry (`bin.bycli`). */
  entry: string
  /** Daemon script, run directly so the daemon is our child. */
  daemonScript: string
  /** Unpacked Chrome extension shipped in the package. */
  extensionDir: string
  version: string
}

function readPackage(dir: string): { name?: string, version?: string, bin?: unknown } | undefined {
  try {
    return JSON.parse(fs.readFileSync(path.join(dir, 'package.json'), 'utf8')) as { name?: string, version?: string, bin?: unknown }
  }
  catch {
    return undefined
  }
}

function installAt(dir: string): BycliInstall | undefined {
  const pkg = readPackage(dir)
  if (pkg?.name !== '@sovovs/bycli') return undefined
  const bin = typeof pkg.bin === 'string' ? pkg.bin : (pkg.bin as Record<string, string> | undefined)?.bycli ?? 'dist/src/main.js'
  const entry = path.join(dir, bin)
  const daemonScript = path.join(path.dirname(entry), 'daemon.js')
  if (!fs.existsSync(entry) || !fs.existsSync(daemonScript)) return undefined
  return { packageDir: dir, entry, daemonScript, extensionDir: path.join(dir, 'extension'), version: pkg.version ?? 'unknown' }
}

function walkUp(start: string): BycliInstall | undefined {
  let dir = start
  for (let i = 0; i < 6; i++) {
    const found = installAt(dir)
    if (found) return found
    const parent = path.dirname(dir)
    if (parent === dir) break
    dir = parent
  }
  return undefined
}

/**
 * Find the byCLI package from a path the user gave: the package dir, its `main.js`, or a `bycli`
 * on PATH (npm's bin symlink, or a wrapper next to npm's global `lib/node_modules`).
 */
export function resolveBycli(given: string): BycliInstall | undefined {
  const candidates: string[] = []
  const absolute = path.resolve(given)
  try {
    candidates.push(fs.realpathSync(absolute))
  }
  catch {
    return undefined
  }
  candidates.push(absolute)
  for (const candidate of candidates) {
    const start = fs.statSync(candidate).isDirectory() ? candidate : path.dirname(candidate)
    const found = walkUp(start)
    if (found) return found
  }
  // A wrapper script in <prefix>/bin: npm's global layout keeps the package in <prefix>/lib/node_modules.
  return installAt(path.join(path.dirname(path.dirname(absolute)), 'lib', 'node_modules', '@sovovs', 'bycli'))
}

/** `bycli` on PATH, if any. */
export function findBycliOnPath(env: NodeJS.ProcessEnv = process.env): string | undefined {
  for (const dir of (env.PATH ?? '').split(path.delimiter)) {
    if (!dir) continue
    const file = path.join(dir, 'bycli')
    try {
      fs.accessSync(file, fs.constants.X_OK)
      return file
    }
    catch {
      // Next.
    }
  }
  return undefined
}

export type DaemonState = 'stopped' | 'starting' | 'running'

export interface BycliStatus {
  daemon: DaemonState
  /** The user's Chrome has the byCLI extension connected to the daemon. */
  extensionConnected: boolean
  /** The daemon was already running when we started (not our child; we do not stop it). */
  external: boolean
  daemonVersion?: string
  extensionVersion?: string
}

export interface RunResult {
  exitCode: number | null
  stdout: string
  stderr: string
  timedOut: boolean
  cancelled: boolean
}

export interface BycliManagerOptions {
  install: BycliInstall
  /** Node binary for the daemon and CLI; default process.execPath (Electron sets ELECTRON_RUN_AS_NODE via `env`). */
  nodePath?: string
  /** Base environment for byCLI processes (already scrubbed of the client's secrets). Default process.env. */
  env?: NodeJS.ProcessEnv
  port?: number
  /** `/status` poll interval, default 10 s. */
  healthIntervalMs?: number
  /** Start a daemon when none answers; default true. */
  startDaemon?: boolean
  log?: (message: string) => void
}

/** Environment every byCLI process gets: loopback only, never through the user's HTTP proxy. */
export function bycliEnv(base: NodeJS.ProcessEnv, port: number): Record<string, string> {
  const env: Record<string, string> = {}
  for (const [key, value] of Object.entries(base)) if (value !== undefined) env[key] = value
  const noProxy = ['127.0.0.1', 'localhost', ...(env.NO_PROXY ?? env.no_proxy ?? '').split(',').filter(Boolean)]
  env.NO_PROXY = [...new Set(noProxy)].join(',')
  env.no_proxy = env.NO_PROXY
  env.BYCLI_DAEMON_HOST = '127.0.0.1'
  env.BYCLI_DAEMON_PORT = String(port)
  env.NODE_NO_WARNINGS = '1'
  return env
}

/** GET /status on the daemon; undefined when nothing (or not byCLI) answers. */
export function fetchDaemonStatus(port: number, timeoutMs = 2000): Promise<Record<string, unknown> | undefined> {
  return new Promise(resolve => {
    const request = http.get({ host: '127.0.0.1', port, path: '/status', headers: { 'X-byCLI': '1' }, timeout: timeoutMs }, response => {
      const chunks: Buffer[] = []
      response.on('data', (chunk: Buffer) => chunks.push(chunk))
      response.on('end', () => {
        try {
          const body = JSON.parse(Buffer.concat(chunks).toString('utf8')) as Record<string, unknown>
          resolve(response.statusCode === 200 && body.ok === true ? body : undefined)
        }
        catch {
          resolve(undefined)
        }
      })
      response.on('error', () => resolve(undefined))
    })
    request.on('timeout', () => request.destroy())
    request.on('error', () => resolve(undefined))
  })
}

/**
 * Owns the byCLI daemon for this client: started as a child bound to 127.0.0.1, restarted with
 * backoff if it dies, stopped with the client. If a daemon already answers on the port (e.g. the
 * user's own byCLI) it is used as is and left running on stop.
 */
export class BycliManager extends EventEmitter {
  readonly install: BycliInstall
  readonly port: number
  private readonly nodePath: string
  private readonly env: Record<string, string>
  private readonly healthIntervalMs: number
  private readonly startDaemon: boolean
  private readonly log: (message: string) => void
  private child: ChildProcess | undefined
  private timer: NodeJS.Timeout | undefined
  private restartTimer: NodeJS.Timeout | undefined
  private restarts = 0
  private running = false
  private polling: Promise<void> | undefined
  private status_: BycliStatus = { daemon: 'stopped', extensionConnected: false, external: false }

  constructor(options: BycliManagerOptions) {
    super()
    this.install = options.install
    this.port = options.port ?? BYCLI_DAEMON_PORT
    this.nodePath = options.nodePath ?? process.execPath
    this.env = bycliEnv(options.env ?? process.env, this.port)
    this.healthIntervalMs = options.healthIntervalMs ?? 10_000
    this.startDaemon = options.startDaemon ?? true
    this.log = options.log ?? (() => {})
  }

  get status(): BycliStatus {
    return { ...this.status_ }
  }

  get connected(): boolean {
    return this.status_.extensionConnected
  }

  private update(next: BycliStatus): void {
    const previous = this.status_
    this.status_ = next
    if (previous.daemon !== next.daemon || previous.extensionConnected !== next.extensionConnected || previous.external !== next.external) {
      this.log(`byCLI daemon ${next.daemon}${next.external ? ' (external)' : ''}, extension ${next.extensionConnected ? 'connected' : 'not connected'}`)
      this.emit('status', this.status)
    }
  }

  /** Start (or adopt) the daemon and begin polling. Resolves after the first status check. */
  async start(): Promise<void> {
    if (this.running) return
    this.running = true
    const existing = await fetchDaemonStatus(this.port)
    if (existing) {
      this.log(`byCLI daemon already running on 127.0.0.1:${this.port} (version ${String(existing.daemonVersion ?? '?')}); using it`)
      this.applyStatus(existing, true)
    }
    else if (this.startDaemon) this.spawnDaemon()
    this.timer = setInterval(() => void this.poll(), this.healthIntervalMs)
    this.timer.unref?.()
    await this.waitForDaemon(5000)
  }

  private async waitForDaemon(timeoutMs: number): Promise<void> {
    const deadline = Date.now() + timeoutMs
    while (this.running && Date.now() < deadline) {
      await this.poll()
      if (this.status_.daemon === 'running') return
      await new Promise(resolve => setTimeout(resolve, 150))
    }
  }

  private spawnDaemon(): void {
    clearTimeout(this.restartTimer)
    this.restartTimer = undefined
    if (!this.running) return
    this.update({ ...this.status_, daemon: 'starting', external: false })
    const child = spawn(this.nodePath, [this.install.daemonScript], {
      env: this.env,
      cwd: os.homedir(),
      stdio: ['ignore', 'ignore', 'pipe'],
    })
    this.child = child
    let tail = ''
    child.stderr?.on('data', (chunk: Buffer) => { tail = (tail + chunk.toString('utf8')).slice(-2000) })
    child.on('error', error => this.log(`byCLI daemon failed to start: ${error.message}`))
    child.on('exit', (code, signal) => {
      if (this.child !== child) return
      this.child = undefined
      if (!this.running) return
      const firstLine = tail.trim().split('\n').slice(-3).join(' | ')
      const delay = Math.min(30_000, 1000 * 2 ** this.restarts)
      this.restarts++
      this.log(`byCLI daemon exited (${signal ?? code})${firstLine ? `: ${firstLine.slice(0, 300)}` : ''}; checking again in ${delay} ms`)
      this.update({ daemon: 'stopped', extensionConnected: false, external: false })
      this.restartTimer = setTimeout(() => {
        // Another daemon may hold the port now (EADDRINUSE): adopt it instead of fighting.
        void fetchDaemonStatus(this.port).then(existing => {
          if (!this.running) return
          if (existing) this.applyStatus(existing, true)
          else this.spawnDaemon()
        })
      }, delay)
      this.restartTimer.unref?.()
    })
  }

  private applyStatus(body: Record<string, unknown>, external: boolean): void {
    this.update({
      daemon: 'running',
      extensionConnected: body.extensionConnected === true,
      external,
      ...(typeof body.daemonVersion === 'string' ? { daemonVersion: body.daemonVersion } : {}),
      ...(typeof body.extensionVersion === 'string' ? { extensionVersion: body.extensionVersion } : {}),
    })
  }

  /** One health check now (also run every healthIntervalMs). */
  poll(): Promise<void> {
    this.polling ??= (async () => {
      try {
        const body = await fetchDaemonStatus(this.port)
        if (!this.running) return
        if (body) {
          const external = this.child === undefined
          if (!external) this.restarts = 0
          this.applyStatus(body, external)
        }
        else if (this.child) this.update({ ...this.status_, daemon: 'starting', extensionConnected: false })
        else {
          this.update({ daemon: 'stopped', extensionConnected: false, external: false })
          // An adopted daemon went away: run our own.
          if (this.startDaemon && !this.restartTimer) this.spawnDaemon()
        }
      }
      finally {
        this.polling = undefined
      }
    })()
    return this.polling
  }

  /** Stop polling and our daemon child (an adopted daemon keeps running). */
  async stop(): Promise<void> {
    this.running = false
    clearInterval(this.timer)
    clearTimeout(this.restartTimer)
    this.timer = undefined
    this.restartTimer = undefined
    const child = this.child
    this.child = undefined
    if (child && child.exitCode === null && child.signalCode === null) {
      await new Promise<void>(resolve => {
        const force = setTimeout(() => {
          child.kill('SIGKILL')
          resolve()
        }, 3000)
        child.once('exit', () => {
          clearTimeout(force)
          resolve()
        })
        child.kill('SIGTERM')
      })
    }
    this.update({ daemon: 'stopped', extensionConnected: false, external: false })
  }

  /** Run `bycli <args…>` (no shell). Output is capped at `maxBuffer` per stream. */
  run(args: readonly string[], options: { timeoutMs: number, signal?: AbortSignal, maxBuffer?: number }): Promise<RunResult> {
    return new Promise(resolve => {
      if (options.signal?.aborted) {
        resolve({ exitCode: null, stdout: '', stderr: '', timedOut: false, cancelled: true })
        return
      }
      let timedOut = false
      const child = execFile(this.nodePath, [this.install.entry, ...args], {
        env: this.env,
        cwd: os.homedir(),
        timeout: options.timeoutMs,
        killSignal: 'SIGKILL',
        maxBuffer: options.maxBuffer ?? 8 * 1024 * 1024,
        encoding: 'utf8',
        windowsHide: true,
        ...(options.signal ? { signal: options.signal } : {}),
      }, (error, stdout, stderr) => {
        const e = error as (NodeJS.ErrnoException & { killed?: boolean, code?: number | string }) | null
        const cancelled = options.signal?.aborted === true
        timedOut = !cancelled && e?.killed === true && e.code !== 'ERR_CHILD_PROCESS_STDIO_MAXBUFFER'
        resolve({
          exitCode: e ? (typeof e.code === 'number' ? e.code : null) : 0,
          stdout: String(stdout ?? ''),
          stderr: String(stderr ?? '') + (e && typeof e.code === 'string' && e.code !== 'ABORT_ERR' ? `\n${e.message}` : ''),
          timedOut,
          cancelled,
        })
      })
      child.stdin?.end()
    })
  }
}
