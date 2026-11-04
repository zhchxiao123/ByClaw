/**
 * The device node: keeps one WebSocket to the relay (register, heartbeat, reconnect with backoff),
 * and runs invokes through confinement -> local confirmation -> executor -> audit.
 */

import { EventEmitter } from 'node:events'
import os from 'node:os'
import WebSocket from 'ws'
import { AuditLog, clip, type AuditEntry } from './audit.js'
import { ConfirmGate, MemoryGrantStore, type ConfirmFn, type GrantKind, type GrantStore } from './confirm.js'
import { FileToolError, isReadAction, parseFileArgs, runFileTool, type FileArgs } from './files.js'
import { confineDir, confinePath, defaultForbiddenDirs, NotAllowedError, resolvePolicy, type ResolvedPolicy } from './paths.js'
import { CAPABILITIES, CLOSE_REVOKED, CLOSE_UNAUTHORIZED, parseRelayFrame, type Capability, type NodeFrame, type RelayFrame, type ResultCode } from './protocol.js'
import { captureLoginEnv, DEFAULT_SHELL_TIMEOUT_MS, defaultShellCwd, runShell, scrubEnv } from './shell.js'

export type NodeStatus = 'connecting' | 'online' | 'paused' | 'offline'

/** Why the node is offline. `unpaired` / `revoked` / `stopped` are final: no reconnect. */
export type OfflineReason = 'disconnected' | 'unpaired' | 'revoked' | 'stopped'

export interface StatusInfo {
  status: NodeStatus
  reason?: OfflineReason
  /** Close code of the last connection, when there was one. */
  closeCode?: number
  /** Delay before the next reconnect attempt. */
  retryInMs?: number
}

/** Asked when a path is outside every allowed dir; resolve with a dir to allow (must contain the path), or undefined. */
export type RequestAllowedDir = (request: { path: string, tool: string, signal: AbortSignal }) => Promise<string | undefined>

export interface NodeOptions {
  /** `ws(s)://<host>/device-relay/node`. */
  relayUrl: string
  deviceToken: string
  name: string
  /** Defaults to process.platform. */
  platform?: string
  appVersion: string
  /** Enabled capabilities; defaults to all P1 capabilities. */
  capabilities?: readonly Capability[]
  allowedDirs: readonly string[]
  /** Replaces the default forbidden dirs entirely (tests). */
  forbiddenDirs?: readonly string[]
  /** Added to the default forbidden dirs: this client's own data dirs or files (token, audit, grants). */
  ownDataDirs?: readonly string[]
  confirm: ConfirmFn
  grants?: GrantStore
  /** Audit log or its directory; omitted = no audit. */
  audit?: AuditLog | string
  /** device_shell timeout, default 120 s. */
  shellTimeoutMs?: number
  /** How long a confirmation may stay open, default 300 s (A gives up then). */
  confirmTimeoutMs?: number
  /**
   * Environment for device_shell: 'login' captures `$SHELL -ilc` once, 'inherit' uses process.env,
   * or a fixed env. Default: 'login' on macOS, 'inherit' elsewhere. The result is always scrubbed.
   */
  shellEnv?: 'login' | 'inherit' | NodeJS.ProcessEnv
  /** Extra values to scrub from the shell env (the device token is always scrubbed). */
  secrets?: readonly string[]
  requestAllowedDir?: RequestAllowedDir
  heartbeatMs?: number
  backoff?: { minMs: number, maxMs: number }
  log?: (message: string) => void
}

export interface NodeHandle {
  readonly status: NodeStatus
  readonly info: StatusInfo
  /** Disconnect from the relay; A sees the device go offline. Running calls are cancelled. */
  pause(): void
  /** Reconnect and register again. */
  resume(): void
  /** Disconnect for good. */
  stop(): Promise<void>
  setAllowedDirs(dirs: readonly string[]): void
  allowedDirs(): string[]
  setCapabilities(capabilities: readonly Capability[]): void
  /** Rename; re-registers by reconnecting when connected. */
  setName(name: string): void
  on(event: 'status', listener: (info: StatusInfo) => void): this
  on(event: 'audit', listener: (entry: AuditEntry) => void): this
  on(event: 'allowedDirs', listener: (dirs: string[]) => void): this
  off(event: string, listener: (...args: any[]) => void): this
}

interface Outcome {
  frame: { ok: boolean, output?: string, error?: string, code?: ResultCode }
  audit: Pick<AuditEntry, 'summary' | 'outcome'> & Partial<Pick<AuditEntry, 'cwd' | 'decision' | 'exitCode' | 'reason'>>
}

class Cancelled extends Error {}

const FILE_LABELS: Record<FileArgs['action'], string> = { list: '列出目录', read: '读取文件', write: '写入文件', search: '搜索' }

function formatShellOutput(stdout: string, stderr: string): string {
  if (stderr === '') return stdout
  return `${stdout}${stdout === '' || stdout.endsWith('\n') ? '' : '\n'}[stderr]\n${stderr}`
}

function errorMessage(error: unknown, path?: string): string {
  const e = error as NodeJS.ErrnoException
  if (e?.code === 'ENOENT') return `路径不存在：${path ?? e.path ?? ''}`
  if (e?.code === 'EACCES' || e?.code === 'EPERM') return `没有权限：${path ?? e.path ?? ''}`
  if (e?.code === 'ENOTDIR') return `不是目录：${path ?? e.path ?? ''}`
  return e instanceof Error ? e.message : String(error)
}

class DeviceNode extends EventEmitter implements NodeHandle {
  private ws: WebSocket | undefined
  private info_: StatusInfo = { status: 'connecting' }
  private paused = false
  private stopped = false
  private attempt = 0
  private reconnectTimer: NodeJS.Timeout | undefined
  private heartbeatTimer: NodeJS.Timeout | undefined
  private readonly inflight = new Map<string, AbortController>()
  private readonly gate: ConfirmGate
  private readonly audit: AuditLog | undefined
  private readonly forbidden: string[]
  private dirs: string[]
  private caps: Capability[]
  private name: string
  private envPromise: Promise<Record<string, string>> | undefined
  private readonly heartbeatMs: number
  private readonly backoff: { minMs: number, maxMs: number }
  private readonly log: (message: string) => void

  constructor(private readonly options: NodeOptions) {
    super()
    this.gate = new ConfirmGate(options.confirm, options.grants ?? new MemoryGrantStore(), options.confirmTimeoutMs)
    this.audit = typeof options.audit === 'string' ? new AuditLog(options.audit) : options.audit
    this.forbidden = [...(options.forbiddenDirs ?? defaultForbiddenDirs({ ownDataDirs: options.ownDataDirs ?? [] }))]
    if (options.forbiddenDirs && options.ownDataDirs) this.forbidden.push(...options.ownDataDirs)
    if (this.audit) this.forbidden.push(this.audit.dir)
    this.dirs = [...options.allowedDirs]
    this.caps = [...(options.capabilities ?? CAPABILITIES)]
    this.name = options.name
    this.heartbeatMs = options.heartbeatMs ?? 10_000
    this.backoff = options.backoff ?? { minMs: 1000, maxMs: 30_000 }
    this.log = options.log ?? (() => {})
    try {
      this.audit?.prune()
    }
    catch (error) {
      this.log(`audit prune failed: ${errorMessage(error)}`)
    }
    this.connect()
  }

  get status(): NodeStatus {
    return this.info_.status
  }

  get info(): StatusInfo {
    return { ...this.info_ }
  }

  private setStatus(info: StatusInfo): void {
    this.info_ = info
    this.emit('status', { ...info })
  }

  private send(ws: WebSocket | undefined, frame: NodeFrame): void {
    if (ws && ws === this.ws && ws.readyState === WebSocket.OPEN) ws.send(JSON.stringify(frame))
  }

  private connect(): void {
    clearTimeout(this.reconnectTimer)
    this.reconnectTimer = undefined
    if (this.stopped || this.paused) return
    this.setStatus({ status: 'connecting' })
    const ws = new WebSocket(this.options.relayUrl, {
      headers: { Authorization: `Bearer ${this.options.deviceToken}` },
      handshakeTimeout: 15_000,
    })
    this.ws = ws
    let openedAt = 0
    let lastAlive = Date.now()
    let forcedCode: number | undefined
    ws.on('unexpected-response', (request, response) => {
      // A relay that refuses at the HTTP upgrade instead of closing with 4401/4403.
      forcedCode = response.statusCode === 401 ? CLOSE_UNAUTHORIZED : response.statusCode === 403 ? CLOSE_REVOKED : undefined
      this.log(`relay refused upgrade: HTTP ${response.statusCode}`)
      request.destroy()
      ws.terminate()
    })
    ws.on('error', error => this.log(`relay connection error: ${error.message}`))
    ws.on('open', () => {
      if (ws !== this.ws) return
      openedAt = Date.now()
      lastAlive = openedAt
      this.send(ws, { t: 'register', name: this.name, platform: this.options.platform ?? process.platform, appVersion: this.options.appVersion, capabilities: [...this.caps] })
      this.heartbeatTimer = setInterval(() => {
        if (Date.now() - lastAlive > this.heartbeatMs * 3.5) {
          this.log('relay silent (no pong), reconnecting')
          ws.terminate()
          return
        }
        this.send(ws, { t: 'heartbeat' })
        try {
          ws.ping()
        }
        catch {
          // Closed meanwhile.
        }
      }, this.heartbeatMs)
      this.setStatus({ status: 'online' })
      this.log(`online at ${this.options.relayUrl}`)
    })
    ws.on('pong', () => { lastAlive = Date.now() })
    ws.on('message', (data, isBinary) => {
      lastAlive = Date.now()
      if (isBinary) return
      const frame = parseRelayFrame(data.toString())
      if (frame) this.onFrame(ws, frame)
    })
    ws.on('close', code => {
      if (ws !== this.ws) return
      this.ws = undefined
      clearInterval(this.heartbeatTimer)
      this.heartbeatTimer = undefined
      this.cancelAll()
      const closeCode = forcedCode ?? code
      if (openedAt && Date.now() - openedAt >= this.heartbeatMs) this.attempt = 0
      if (this.stopped) return this.setStatus({ status: 'offline', reason: 'stopped', closeCode })
      if (this.paused) return this.setStatus({ status: 'paused', closeCode })
      if (closeCode === CLOSE_UNAUTHORIZED || closeCode === CLOSE_REVOKED) {
        const reason: OfflineReason = closeCode === CLOSE_REVOKED ? 'revoked' : 'unpaired'
        this.log(`relay closed with ${closeCode} (${reason}); not reconnecting`)
        this.stopped = true
        return this.setStatus({ status: 'offline', reason, closeCode })
      }
      const retryInMs = Math.min(this.backoff.maxMs, this.backoff.minMs * 2 ** this.attempt)
      this.attempt++
      this.log(`relay closed (${closeCode}); reconnecting in ${retryInMs} ms`)
      this.setStatus({ status: 'offline', reason: 'disconnected', closeCode, retryInMs })
      this.reconnectTimer = setTimeout(() => this.connect(), retryInMs)
    })
  }

  private disconnect(code: number, reason: string): void {
    clearTimeout(this.reconnectTimer)
    this.reconnectTimer = undefined
    const ws = this.ws
    if (!ws) return
    if (ws.readyState === WebSocket.CONNECTING) ws.terminate()
    else ws.close(code, reason)
  }

  private cancelAll(): void {
    for (const controller of this.inflight.values()) controller.abort(new Cancelled('disconnected'))
  }

  pause(): void {
    if (this.stopped || this.paused) return
    this.paused = true
    if (this.ws) this.disconnect(1000, 'paused')
    else {
      clearTimeout(this.reconnectTimer)
      this.setStatus({ status: 'paused' })
    }
  }

  resume(): void {
    if (this.stopped || !this.paused) return
    this.paused = false
    this.attempt = 0
    this.connect()
  }

  stop(): Promise<void> {
    this.stopped = true
    clearTimeout(this.reconnectTimer)
    const ws = this.ws
    if (!ws) {
      if (this.info_.reason !== 'unpaired' && this.info_.reason !== 'revoked') this.setStatus({ status: 'offline', reason: 'stopped' })
      return Promise.resolve()
    }
    return new Promise(resolve => {
      ws.once('close', () => resolve())
      this.disconnect(1000, 'stopped')
      setTimeout(() => {
        ws.terminate()
        resolve()
      }, 2000).unref()
    })
  }

  setAllowedDirs(dirs: readonly string[]): void {
    this.dirs = [...dirs]
    this.emit('allowedDirs', [...this.dirs])
  }

  allowedDirs(): string[] {
    return [...this.dirs]
  }

  setCapabilities(capabilities: readonly Capability[]): void {
    this.caps = [...capabilities]
    this.send(this.ws, { t: 'capabilities', capabilities: [...this.caps] })
  }

  setName(name: string): void {
    if (name === this.name) return
    this.name = name
    if (this.ws && !this.paused && !this.stopped) {
      // `register` is the first frame of a connection, so a new name means a new connection.
      this.attempt = 0
      this.disconnect(1000, 'renamed')
    }
  }

  private onFrame(ws: WebSocket, frame: RelayFrame): void {
    if (frame.t === 'cancel') {
      this.inflight.get(frame.id)?.abort(new Cancelled('cancelled'))
      return
    }
    if (this.inflight.has(frame.id)) return
    void this.handleInvoke(ws, frame)
  }

  private async handleInvoke(ws: WebSocket, invoke: Extract<RelayFrame, { t: 'invoke' }>): Promise<void> {
    const controller = new AbortController()
    this.inflight.set(invoke.id, controller)
    this.send(ws, { t: 'ack', id: invoke.id })
    const started = Date.now()
    let waited = false
    const humanWait = (prompt: string) => {
      if (waited) return
      waited = true
      this.send(ws, { t: 'human_wait', id: invoke.id, prompt })
    }
    let outcome: Outcome
    try {
      outcome = await this.execute(invoke, controller.signal, humanWait)
    }
    catch (error) {
      outcome = error instanceof Cancelled || controller.signal.aborted
        ? { frame: { ok: false, code: 'failed', error: '这一步已取消' }, audit: { summary: invoke.tool, outcome: 'cancelled' } }
        : { frame: { ok: false, code: 'failed', error: errorMessage(error) }, audit: { summary: invoke.tool, outcome: 'failed', reason: clip(errorMessage(error), 200) } }
    }
    finally {
      this.inflight.delete(invoke.id)
    }
    if (controller.signal.aborted && outcome.audit.outcome !== 'cancelled' && outcome.audit.outcome !== 'timeout') {
      outcome = { frame: { ok: false, code: 'failed', error: '这一步已取消' }, audit: { ...outcome.audit, outcome: 'cancelled' } }
    }
    this.send(ws, { t: 'result', id: invoke.id, ...outcome.frame })
    const entry: AuditEntry = {
      ts: new Date().toISOString(),
      id: invoke.id,
      ...(invoke.session ? { session: invoke.session } : {}),
      tool: invoke.tool,
      ...outcome.audit,
      ms: Date.now() - started,
    }
    try {
      this.audit?.append(entry)
    }
    catch (error) {
      this.log(`audit write failed: ${errorMessage(error)}`)
    }
    this.emit('audit', entry)
  }

  private async policy(): Promise<ResolvedPolicy> {
    return resolvePolicy({ allowedDirs: this.dirs, forbiddenDirs: this.forbidden })
  }

  /** Confine, asking the user for a new allowed dir once when the path is outside all of them. */
  private async confine<T>(input: string, tool: string, signal: AbortSignal, humanWait: (p: string) => void, attempt: (policy: ResolvedPolicy) => Promise<T>): Promise<T> {
    try {
      return await attempt(await this.policy())
    }
    catch (error) {
      if (!(error instanceof NotAllowedError) || !error.outsideAllowed || !this.options.requestAllowedDir) throw error
      humanWait(`请在「${this.name}」上选择是否授权访问：${clip(input, 160)}`)
      const dir = await this.options.requestAllowedDir({ path: input, tool, signal })
      if (signal.aborted) throw new Cancelled('cancelled')
      if (dir === undefined) throw error
      if (!this.dirs.includes(dir)) this.setAllowedDirs([...this.dirs, dir])
      return attempt(await this.policy())
    }
  }

  private requireCapability(capability: Capability): void {
    if (!this.caps.includes(capability)) throw new FileToolError(`电脑端没有开启能力 ${capability}`)
  }

  private async execute(invoke: Extract<RelayFrame, { t: 'invoke' }>, signal: AbortSignal, humanWait: (p: string) => void): Promise<Outcome> {
    try {
      if (invoke.tool === 'device_shell') return await this.executeShell(invoke, signal, humanWait)
      if (invoke.tool === 'device_files') return await this.executeFiles(invoke, signal, humanWait)
      return { frame: { ok: false, code: 'failed', error: `不支持的工具：${invoke.tool}` }, audit: { summary: invoke.tool, outcome: 'failed', reason: 'unknown tool' } }
    }
    catch (error) {
      if (error instanceof Cancelled || signal.aborted) throw new Cancelled('cancelled')
      if (error instanceof NotAllowedError) {
        return { frame: { ok: false, code: 'not_allowed', error: error.message }, audit: { summary: clip(String(invoke.args.path ?? invoke.args.cwd ?? ''), 300), outcome: 'not_allowed', reason: clip(error.message, 200) } }
      }
      throw error
    }
  }

  private async decide(invoke: Extract<RelayFrame, { t: 'invoke' }>, kind: GrantKind, summary: string, prompt: string, root: string | undefined, signal: AbortSignal, humanWait: (p: string) => void) {
    const outcome = await this.gate.check({
      id: invoke.id,
      tool: invoke.tool,
      args: invoke.args,
      ...(invoke.session ? { session: invoke.session } : {}),
      device: { name: this.name },
      kind,
      summary,
      ...(root ? { root } : {}),
      signal,
    }, () => humanWait(prompt))
    if (signal.aborted) throw new Cancelled('cancelled')
    return { allowed: outcome.decision !== 'deny', decision: outcome.source === 'user' ? outcome.decision : outcome.source }
  }

  private shellEnv(): Promise<Record<string, string>> {
    this.envPromise ??= (async () => {
      const mode = this.options.shellEnv ?? (process.platform === 'darwin' ? 'login' : 'inherit')
      let env: NodeJS.ProcessEnv = process.env
      if (mode === 'login') {
        const captured = await captureLoginEnv()
        if (captured) env = captured
        else this.log('login shell env capture failed; using process env')
      }
      else if (typeof mode === 'object') env = mode
      return scrubEnv(env, [this.options.deviceToken, ...(this.options.secrets ?? [])])
    })()
    return this.envPromise
  }

  private async executeShell(invoke: Extract<RelayFrame, { t: 'invoke' }>, signal: AbortSignal, humanWait: (p: string) => void): Promise<Outcome> {
    const { command, cwd } = invoke.args
    this.requireCapability('shell')
    if (typeof command !== 'string' || command.trim() === '') throw new FileToolError('缺少 command')
    if (cwd !== undefined && typeof cwd !== 'string') throw new FileToolError('cwd 必须是字符串')
    const summary = clip(command, 300)
    const dir = cwd === undefined || cwd === ''
      ? defaultShellCwd()
      : await this.confine(cwd, invoke.tool, signal, humanWait, policy => confineDir(cwd, policy))
    const audit = { summary, cwd: clip(dir, 300) }
    const { allowed, decision } = await this.decide(invoke, 'shell', summary, `请在「${this.name}」上确认是否执行命令：${clip(command, 120)}`, undefined, signal, humanWait)
    if (!allowed) return { frame: { ok: false, code: 'denied', error: '用户拒绝了这一步' }, audit: { ...audit, decision, outcome: 'denied' } }
    const env = await this.shellEnv()
    const timeoutMs = this.options.shellTimeoutMs ?? DEFAULT_SHELL_TIMEOUT_MS
    const result = await runShell({ command, cwd: dir, env, timeoutMs, signal })
    const output = formatShellOutput(result.stdout, result.stderr)
    if (result.cancelled) throw new Cancelled('cancelled')
    if (result.timedOut) {
      return {
        frame: { ok: false, code: 'timeout', error: `命令超过 ${Math.round(timeoutMs / 1000)} 秒未结束，已终止${output ? `\n${output}` : ''}` },
        audit: { ...audit, decision, outcome: 'timeout', exitCode: result.exitCode },
      }
    }
    if (result.exitCode === 0) return { frame: { ok: true, output }, audit: { ...audit, decision, outcome: 'ok', exitCode: 0 } }
    const status = result.exitCode === null ? `被信号 ${result.signal} 终止` : `退出码 ${result.exitCode}`
    return {
      frame: { ok: false, code: 'failed', error: `${status}${output ? `\n${output}` : ''}` },
      audit: { ...audit, decision, outcome: 'failed', exitCode: result.exitCode, reason: status },
    }
  }

  private async executeFiles(invoke: Extract<RelayFrame, { t: 'invoke' }>, signal: AbortSignal, humanWait: (p: string) => void): Promise<Outcome> {
    let args: FileArgs
    try {
      args = parseFileArgs(invoke.args)
    }
    catch (error) {
      return { frame: { ok: false, code: 'failed', error: errorMessage(error) }, audit: { summary: 'device_files', outcome: 'failed', reason: clip(errorMessage(error), 200) } }
    }
    const read = isReadAction(args.action)
    this.requireCapability(read ? 'files.read' : 'files.write')
    const label = FILE_LABELS[args.action]
    const detail = args.action === 'search' ? `${args.path}（搜索「${clip(args.query as string, 60)}」）`
      : args.action === 'write' ? `${args.path}（${Buffer.byteLength(args.content as string, 'utf8')} 字节）`
      : args.path
    const summary = clip(`${args.action} ${detail}`, 300)
    const confined = await this.confine(args.path, invoke.tool, signal, humanWait, policy => confinePath(args.path, policy, read ? 'read' : 'write'))
      .catch(error => {
        if (error instanceof NotAllowedError || error instanceof Cancelled) throw error
        return { error }
      })
    if ('error' in confined) {
      const message = errorMessage(confined.error, args.path)
      return { frame: { ok: false, code: 'failed', error: message }, audit: { summary, outcome: 'failed', reason: clip(message, 200) } }
    }
    const kind: GrantKind = read ? 'files.read' : 'files.write'
    const { allowed, decision } = await this.decide(invoke, kind, `${label}：${detail}`, `请在「${this.name}」上确认是否${label}：${clip(detail, 160)}`, confined.root, signal, humanWait)
    if (!allowed) return { frame: { ok: false, code: 'denied', error: '用户拒绝了这一步' }, audit: { summary, decision, outcome: 'denied' } }
    try {
      const output = await runFileTool(args, await this.policy())
      return { frame: { ok: true, output }, audit: { summary, decision, outcome: 'ok' } }
    }
    catch (error) {
      if (error instanceof NotAllowedError) return { frame: { ok: false, code: 'not_allowed', error: error.message }, audit: { summary, decision, outcome: 'not_allowed', reason: clip(error.message, 200) } }
      const message = errorMessage(error, args.path)
      return { frame: { ok: false, code: 'failed', error: message }, audit: { summary, decision, outcome: 'failed', reason: clip(message, 200) } }
    }
  }
}

/** Start a device node; it connects immediately and keeps reconnecting until paused or stopped. */
export function startNode(options: NodeOptions): NodeHandle {
  return new DeviceNode(options)
}

/** Default device name. */
export function defaultDeviceName(): string {
  return os.hostname()
}
