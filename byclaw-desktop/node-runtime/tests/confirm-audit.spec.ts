import fs from 'node:fs'
import path from 'node:path'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { AuditLog } from '../src/audit.js'
import { ConfirmGate, JsonGrantStore, optionsFor, type ConfirmFn, type Decision, type GateRequest } from '../src/confirm.js'
import { tempDir } from './helpers.js'

let dir: string
let cleanup: () => void
beforeEach(() => ({ dir, cleanup } = tempDir()))
afterEach(() => cleanup())

const req = (over: Partial<GateRequest> = {}): GateRequest => ({ id: 'i1', tool: 'device_shell', args: {}, device: { name: 'mac' }, kind: 'shell', summary: 'ls', ...over })
const answering = (decision: Decision) => vi.fn<ConfirmFn>(async () => decision)

describe('options', () => {
  it('offers allow_always only for reads and allow_session only with a session', () => {
    expect(optionsFor('shell', 's1')).toEqual(['allow_once', 'allow_session', 'deny'])
    expect(optionsFor('files.write', undefined)).toEqual(['allow_once', 'deny'])
    expect(optionsFor('files.read', 's1')).toEqual(['allow_once', 'allow_session', 'allow_always', 'deny'])
  })
})

describe('ConfirmGate', () => {
  it('refuses retries right after a deny without asking, then asks again after the cooldown', async () => {
    const confirm = answering('deny')
    let t = 1_000
    const gate = new ConfirmGate(confirm, undefined, 300_000, 60_000, () => t)
    const onAsk = vi.fn()
    expect(await gate.check(req({ session: 's', kind: 'browser', scope: 'example.com' }), onAsk)).toEqual({ decision: 'deny', source: 'user' })
    t += 30_000
    expect(await gate.check(req({ session: 's', kind: 'browser', scope: 'example.com' }), onAsk)).toEqual({ decision: 'deny', source: 'recent-deny' })
    // Another site or session is not affected.
    await gate.check(req({ session: 's', kind: 'browser', scope: 'other.test' }), onAsk)
    await gate.check(req({ session: 's2', kind: 'browser', scope: 'example.com' }), onAsk)
    expect(confirm).toHaveBeenCalledTimes(3)
    t += 31_000
    expect((await gate.check(req({ session: 's', kind: 'browser', scope: 'example.com' }), onAsk)).source).toBe('user')
    expect(confirm).toHaveBeenCalledTimes(4)
  })

  it('allow_once asks every time and calls onAsk', async () => {
    const confirm = answering('allow_once')
    const gate = new ConfirmGate(confirm)
    const onAsk = vi.fn()
    expect((await gate.check(req({ session: 's' }), onAsk)).decision).toBe('allow_once')
    await gate.check(req({ session: 's' }), onAsk)
    expect(confirm).toHaveBeenCalledTimes(2)
    expect(onAsk).toHaveBeenCalledTimes(2)
  })

  it('allow_session covers the same session and kind only', async () => {
    const confirm = answering('allow_session')
    const gate = new ConfirmGate(confirm)
    await gate.check(req({ session: 's1' }), () => {})
    const onAsk = vi.fn()
    expect(await gate.check(req({ session: 's1', summary: 'rm -rf x' }), onAsk)).toEqual({ decision: 'allow_session', source: 'session' })
    expect(onAsk).not.toHaveBeenCalled()
    await gate.check(req({ session: 's2' }), () => {})
    await gate.check(req({ session: 's1', kind: 'files.write' }), () => {})
    expect(confirm).toHaveBeenCalledTimes(3)
  })

  it('allow_session without a session id is not remembered', async () => {
    const confirm = answering('allow_session')
    const gate = new ConfirmGate(confirm)
    expect((await gate.check(req(), () => {})).decision).toBe('allow_once')
    await gate.check(req(), () => {})
    expect(confirm).toHaveBeenCalledTimes(2)
  })

  it('allow_always is persisted per root for reads and survives a restart', async () => {
    const file = path.join(dir, 'grants.json')
    const gate = new ConfirmGate(answering('allow_always'), new JsonGrantStore(file))
    await gate.check(req({ kind: 'files.read', tool: 'device_files', root: '/data' }), () => {})
    expect(JSON.parse(fs.readFileSync(file, 'utf8'))).toEqual({ alwaysReadRoots: ['/data'] })
    expect((fs.statSync(file).mode & 0o777).toString(8)).toBe('600')
    const confirm = answering('deny')
    const again = new ConfirmGate(confirm, new JsonGrantStore(file))
    expect(await again.check(req({ kind: 'files.read', root: '/data' }), () => {})).toEqual({ decision: 'allow_always', source: 'always' })
    expect((await again.check(req({ kind: 'files.read', root: '/other' }), () => {})).decision).toBe('deny')
    expect((await again.check(req({ kind: 'files.write', root: '/data' }), () => {})).decision).toBe('deny')
  })

  it('allow_always for shell or write is downgraded to allow_once and not stored', async () => {
    const file = path.join(dir, 'grants.json')
    const confirm = answering('allow_always')
    const gate = new ConfirmGate(confirm, new JsonGrantStore(file))
    expect((await gate.check(req({ kind: 'shell' }), () => {})).decision).toBe('allow_once')
    expect((await gate.check(req({ kind: 'files.write', root: '/data' }), () => {})).decision).toBe('allow_once')
    expect(fs.existsSync(file)).toBe(false)
  })

  it('times out and cancellation as deny, and passes an aborting signal to the UI', async () => {
    let seen: AbortSignal | undefined
    const gate = new ConfirmGate(async r => { seen = r.signal; return new Promise<Decision>(() => {}) }, undefined, 50)
    expect((await gate.check(req(), () => {})).decision).toBe('deny')
    expect(seen?.aborted).toBe(true)
    const controller = new AbortController()
    const pending = new ConfirmGate(async () => new Promise<Decision>(() => {})).check(req({ signal: controller.signal }), () => {})
    controller.abort()
    expect((await pending).decision).toBe('deny')
  })

  it('a throwing UI counts as deny', async () => {
    const gate = new ConfirmGate(async () => { throw new Error('dialog failed') })
    expect((await gate.check(req(), () => {})).decision).toBe('deny')
  })
})

describe('AuditLog', () => {
  it('appends JSONL lines to a per-day file', () => {
    const log = new AuditLog(dir, () => new Date('2026-11-04T10:00:00Z'))
    log.append({ id: 'a', tool: 'device_shell', summary: 'ls', outcome: 'ok', ms: 3, decision: 'allow_once' })
    log.append({ id: 'b', tool: 'device_files', summary: 'read /x', outcome: 'denied', ms: 1 })
    const lines = fs.readFileSync(path.join(dir, 'audit-2026-11-04.jsonl'), 'utf8').trim().split('\n').map(l => JSON.parse(l))
    expect(lines).toHaveLength(2)
    expect(lines[0]).toMatchObject({ ts: '2026-11-04T10:00:00.000Z', id: 'a', tool: 'device_shell', outcome: 'ok', decision: 'allow_once' })
    expect(log.recent(1)).toEqual([expect.objectContaining({ id: 'b' })])
  })

  it('prunes files older than 30 days', () => {
    for (const d of ['2026-09-01', '2026-10-04', '2026-10-05', '2026-11-04']) fs.writeFileSync(path.join(dir, `audit-${d}.jsonl`), '')
    fs.writeFileSync(path.join(dir, 'unrelated.txt'), '')
    const log = new AuditLog(dir, () => new Date('2026-11-04T10:00:00Z'))
    expect(log.prune().sort()).toEqual(['audit-2026-09-01.jsonl', 'audit-2026-10-04.jsonl'])
    expect(fs.readdirSync(dir).sort()).toEqual(['audit-2026-10-05.jsonl', 'audit-2026-11-04.jsonl', 'unrelated.txt'])
  })
})
