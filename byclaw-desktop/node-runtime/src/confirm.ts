/**
 * Local confirmation (PRD §6): every device action is confirmed by the user, or covered by an
 * earlier "allow for this session" / "always allow" (file reads only).
 */

import fs from 'node:fs'
import path from 'node:path'

export type Decision = 'allow_once' | 'allow_session' | 'allow_always' | 'deny'

/**
 * The kind of grant a decision can be remembered under. Shell grants cover any command in the
 * session; file grants are split into reading (list/read/search) and writing.
 */
export type GrantKind = 'shell' | 'files.read' | 'files.write'

/** What the user is asked about. */
export interface ConfirmRequest {
  /** Invoke id. */
  id: string
  tool: string
  args: Record<string, unknown>
  /** A-side session id, when the invoke carried one. */
  session?: string
  device: { name: string }
  kind: GrantKind
  /** One-line human summary: the command, or the action and path. */
  summary: string
  /** Decisions the UI should offer, in display order. */
  options: Decision[]
  /** Aborted when the invoke is cancelled or the confirmation times out. */
  signal: AbortSignal
}

/**
 * UI hook: Electron dialog, terminal prompt, or a fixed policy. A function with
 * `interactive === false` is a fixed policy: no human is asked, so no `human_wait` is sent.
 */
export type ConfirmFn = ((request: ConfirmRequest) => Promise<Decision>) & { interactive?: boolean }

/** Where "always allow" grants are kept. */
export interface GrantStore {
  /** Allowed-dir roots under which file reads are always allowed. */
  alwaysReadRoots(): string[]
  addAlwaysReadRoot(root: string): void
}

/** In-memory grant store (tests, CLI without --state-dir). */
export class MemoryGrantStore implements GrantStore {
  private readonly roots = new Set<string>()
  alwaysReadRoots(): string[] {
    return [...this.roots]
  }

  addAlwaysReadRoot(root: string): void {
    this.roots.add(root)
  }
}

/** Grants persisted in a small JSON file (`{"alwaysReadRoots": [...]}`), written 0600. */
export class JsonGrantStore implements GrantStore {
  private roots: Set<string>
  constructor(private readonly file: string) {
    this.roots = new Set()
    try {
      const data = JSON.parse(fs.readFileSync(file, 'utf8')) as { alwaysReadRoots?: unknown }
      if (Array.isArray(data.alwaysReadRoots)) for (const r of data.alwaysReadRoots) if (typeof r === 'string') this.roots.add(r)
    }
    catch {
      // Missing or unreadable: start empty.
    }
  }

  alwaysReadRoots(): string[] {
    return [...this.roots]
  }

  addAlwaysReadRoot(root: string): void {
    if (this.roots.has(root)) return
    this.roots.add(root)
    fs.mkdirSync(path.dirname(this.file), { recursive: true, mode: 0o700 })
    fs.writeFileSync(this.file, JSON.stringify({ alwaysReadRoots: [...this.roots] }, null, 2), { mode: 0o600 })
  }
}

/** How a call got permission (recorded in the audit line). */
export type DecisionSource = 'user' | 'session' | 'always'

export interface GateOutcome {
  decision: Decision
  source: DecisionSource
}

export interface GateRequest extends Omit<ConfirmRequest, 'options' | 'signal'> {
  /** The allowed dir containing the path (file tools); "always allow" is remembered per root. */
  root?: string
  signal?: AbortSignal
}

/** Offered decisions for a request: always-allow only for reads, session only with a session id. */
export function optionsFor(kind: GrantKind, session: string | undefined): Decision[] {
  const options: Decision[] = ['allow_once']
  if (session !== undefined) options.push('allow_session')
  if (kind === 'files.read') options.push('allow_always')
  options.push('deny')
  return options
}

/**
 * Applies remembered grants and asks the user otherwise. A decision outside the offered options
 * (a UI returning allow_always for a shell command) is downgraded to allow_once and not remembered.
 */
export class ConfirmGate {
  private readonly sessionGrants = new Set<string>()
  constructor(
    private readonly confirm: ConfirmFn,
    private readonly grants: GrantStore = new MemoryGrantStore(),
    private readonly timeoutMs = 300_000,
  ) {}

  /** Grants for this session id, if any (tests and diagnostics). */
  hasSessionGrant(session: string, kind: GrantKind): boolean {
    return this.sessionGrants.has(`${session}\u0000${kind}`)
  }

  /** Forget every session grant (e.g. on pause). */
  clearSessions(): void {
    this.sessionGrants.clear()
  }

  /**
   * @param onAsk - called right before the user is asked (the node sends `human_wait` there).
   */
  async check(request: GateRequest, onAsk: () => void): Promise<GateOutcome> {
    const { session, kind, root } = request
    if (session !== undefined && this.sessionGrants.has(`${session}\u0000${kind}`)) return { decision: 'allow_session', source: 'session' }
    if (kind === 'files.read' && root !== undefined && this.grants.alwaysReadRoots().includes(root)) return { decision: 'allow_always', source: 'always' }
    const options = optionsFor(kind, session)
    const controller = new AbortController()
    const timer = setTimeout(() => controller.abort(new Error('confirmation timed out')), this.timeoutMs)
    const forward = () => controller.abort(request.signal?.reason)
    request.signal?.addEventListener('abort', forward, { once: true })
    if (this.confirm.interactive !== false) onAsk()
    let decision: Decision
    try {
      const { root: _root, signal: _signal, ...rest } = request
      decision = await Promise.race([
        this.confirm({ ...rest, options, signal: controller.signal }),
        new Promise<Decision>(resolve => {
          if (controller.signal.aborted) resolve('deny')
          controller.signal.addEventListener('abort', () => resolve('deny'), { once: true })
        }),
      ])
    }
    catch {
      decision = 'deny'
    }
    finally {
      clearTimeout(timer)
      request.signal?.removeEventListener('abort', forward)
    }
    if (!options.includes(decision)) decision = decision === 'deny' ? 'deny' : 'allow_once'
    if (decision === 'allow_session' && session !== undefined) this.sessionGrants.add(`${session}\u0000${kind}`)
    if (decision === 'allow_always' && root !== undefined) this.grants.addAlwaysReadRoot(root)
    return { decision, source: 'user' }
  }
}

/** Fixed policies for tests and headless runs. */
export const autoAllow: ConfirmFn = Object.assign(async (): Promise<Decision> => 'allow_once', { interactive: false })
export const autoDeny: ConfirmFn = Object.assign(async (): Promise<Decision> => 'deny', { interactive: false })

/** Chinese labels for the decisions (dialogs and terminal prompt). */
export const DECISION_LABELS: Record<Decision, string> = {
  allow_once: '允许一次',
  allow_session: '本会话内允许',
  allow_always: '始终允许（仅读取文件）',
  deny: '拒绝',
}
