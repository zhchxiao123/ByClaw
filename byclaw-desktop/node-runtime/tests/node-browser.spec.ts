import fs from 'node:fs'
import path from 'node:path'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { AuditLog } from '../src/audit.js'
import { BycliManager, resolveBycli } from '../src/bycli.js'
import type { ConfirmFn, ConfirmRequest, Decision } from '../src/confirm.js'
import { startNode, type NodeHandle, type NodeOptions } from '../src/node.js'
import { FAKE_BYCLI_DIR, FakeBycliState, freePort } from './fake-bycli.js'
import { FakeRelay } from './fake-relay.js'
import { tempDir, waitFor } from './helpers.js'

let relay: FakeRelay
let url: string
let dir: string
let cleanup: () => void
let state: FakeBycliState
let bycli: BycliManager
let nodes: NodeHandle[]
let asked: ConfirmRequest[]
let answer: (request: ConfirmRequest) => Decision

beforeEach(async () => {
  relay = new FakeRelay()
  url = await relay.start()
  ;({ dir, cleanup } = tempDir('byclaw-node-browser-'))
  fs.mkdirSync(path.join(dir, 'allowed'))
  fs.writeFileSync(path.join(dir, 'allowed', 'cv.pdf'), 'pdf')
  state = new FakeBycliState(dir, { connected: true })
  bycli = new BycliManager({ install: resolveBycli(FAKE_BYCLI_DIR)!, env: state.env(), port: await freePort(), healthIntervalMs: 100 })
  await bycli.start()
  nodes = []
  asked = []
  answer = () => 'allow_once'
})

afterEach(async () => {
  await Promise.all(nodes.map(n => n.stop()))
  await bycli.stop()
  await relay.stop()
  cleanup()
})

const confirm: ConfirmFn = async request => {
  asked.push(request)
  return answer(request)
}

async function start(over: Partial<NodeOptions> = {}): Promise<NodeHandle> {
  const node = startNode({
    relayUrl: url,
    deviceToken: 'device.jwt.token-value-123',
    name: '测试电脑',
    platform: 'linux',
    appVersion: '0.1.0-test',
    allowedDirs: [path.join(dir, 'allowed')],
    forbiddenDirs: [],
    confirm,
    audit: new AuditLog(path.join(dir, 'audit')),
    shellEnv: 'inherit',
    heartbeatMs: 100,
    backoff: { minMs: 50, maxMs: 400 },
    browser: { bycli, tempDir: path.join(dir, 'browser-tmp') },
    ...over,
  })
  nodes.push(node)
  await waitFor(() => node.status === 'online')
  return node
}

let seq = 0
async function browse(command: string, extra: Record<string, unknown> = {}, session = 'chat-1'): Promise<any> {
  const id = `b${++seq}`
  relay.invoke(id, 'device_browser', { command, ...extra }, session)
  return relay.result(id)
}

/** byCLI calls after the probe(s): the command the user's Chrome actually ran. */
function ran(sub: string): string[][] {
  return state.calls().filter(argv => argv[0] === 'browser' && argv[2] === sub)
}

describe('browser capability', () => {
  it('is advertised only while the extension is connected, with a capabilities frame on change', async () => {
    state.patch({ connected: false })
    await waitFor(() => !bycli.connected, 3000)
    const node = await start()
    const conn = relay.last
    await waitFor(() => conn.frames.length > 0)
    expect(conn.frames[0].capabilities).toEqual(['shell', 'files.read', 'files.write'])
    state.patch({ connected: true })
    await waitFor(() => conn.frames.find(f => f.t === 'capabilities'), 3000)
    expect(conn.frames.filter(f => f.t === 'capabilities').at(-1).capabilities).toEqual(['shell', 'files.read', 'files.write', 'browser'])
    expect(node.advertisedCapabilities()).toContain('browser')
    state.patch({ connected: false })
    await waitFor(() => conn.frames.filter(f => f.t === 'capabilities').length >= 2, 3000)
    expect(conn.frames.filter(f => f.t === 'capabilities').at(-1).capabilities).toEqual(['shell', 'files.read', 'files.write'])
    // Steady state: no repeated frames.
    const count = conn.frames.filter(f => f.t === 'capabilities').length
    await new Promise(resolve => setTimeout(resolve, 400))
    expect(conn.frames.filter(f => f.t === 'capabilities').length).toBe(count)
  })

  it('registers with browser when connected, and not when the user turned it off', async () => {
    await start()
    await waitFor(() => relay.last.frames.length > 0)
    expect(relay.last.frames[0].capabilities).toContain('browser')
    await start({ capabilities: ['shell'] })
    await waitFor(() => relay.conns.length === 2 && relay.last.frames.length > 0)
    expect(relay.last.frames[0].capabilities).toEqual(['shell'])
  })

  it('refuses device_browser while the extension is not connected', async () => {
    await start()
    state.patch({ connected: false })
    await waitFor(() => !bycli.connected, 3000)
    const result = await browse('state')
    expect(result).toMatchObject({ ok: false, code: 'failed' })
    expect(result.error).toContain('Chrome')
  })
})

describe('device_browser confirmation', () => {
  it('asks on first access to a site, then not again in the session after allow_session', async () => {
    state.patch({ tabs: { 'chat-1': 'https://mail.example.com/inbox' } })
    answer = () => 'allow_session'
    await start()
    const first = await browse('state')
    expect(first).toMatchObject({ ok: true })
    expect(asked).toHaveLength(1)
    expect(asked[0]!.options).toEqual(['allow_once', 'allow_session', 'deny'])
    expect(asked[0]!.browser).toMatchObject({ action: 'access', site: 'mail.example.com', session: 'chat-1', command: 'state' })
    expect(relay.framesFor(first.id).map(f => f.t)).toEqual(['ack', 'human_wait', 'result'])
    // Interactions and reads on the allowed site: no prompt.
    expect(await browse('click 12')).toMatchObject({ ok: true })
    expect(await browse('type 3 "hello there"')).toMatchObject({ ok: true })
    expect(await browse('get text 5')).toMatchObject({ ok: true })
    expect(asked).toHaveLength(1)
    expect(ran('click')).toEqual([['browser', 'chat-1', 'click', '12']])
    expect(ran('type')).toEqual([['browser', 'chat-1', 'type', '3', 'hello there']])
  })

  it('asks again for another site, another session, and after allow_once', async () => {
    state.patch({ tabs: { 'chat-1': 'https://a.example/' } })
    answer = () => 'allow_session'
    await start()
    await browse('state')
    expect(asked).toHaveLength(1)
    // The page navigated to another host.
    state.patch({ tabs: { 'chat-1': 'https://b.example/x' } })
    await browse('click 1')
    expect(asked).toHaveLength(2)
    expect(asked[1]!.browser?.site).toBe('b.example')
    // Same site, another ByClaw conversation.
    state.patch({ tabs: { 'chat-1': 'https://a.example/', 'chat-2': 'https://a.example/' } })
    await browse('state', {}, 'chat-2')
    expect(asked).toHaveLength(3)
    // allow_once does not stick.
    answer = () => 'allow_once'
    state.patch({ tabs: { 'chat-1': 'https://c.example/' } })
    await browse('state')
    await browse('state')
    expect(asked.filter(r => r.browser?.site === 'c.example')).toHaveLength(2)
  })

  it('confirms open by the target host, and blank pages need no prompt', async () => {
    answer = () => 'allow_session'
    await start()
    expect(await browse('state')).toMatchObject({ ok: true })
    expect(asked).toHaveLength(0)
    const opened = await browse('open example.com')
    expect(opened).toMatchObject({ ok: true })
    expect(asked).toHaveLength(1)
    expect(asked[0]!.browser).toMatchObject({ site: 'example.com', url: 'https://example.com/' })
    expect(ran('open')).toEqual([['browser', 'chat-1', 'open', 'https://example.com/']])
    // Now the tab is on example.com, already allowed for this session.
    expect(await browse('click 2')).toMatchObject({ ok: true })
    expect(asked).toHaveLength(1)
  })

  it('asks for eval and upload every time, offering only once / deny', async () => {
    state.patch({ tabs: { 'chat-1': 'https://app.example/' } })
    answer = () => 'allow_session'
    await start()
    await browse('state')
    expect(asked).toHaveLength(1)
    await browse('eval "document.title"')
    await browse('eval "document.title"')
    const evals = asked.filter(r => r.browser?.action === 'eval')
    expect(evals).toHaveLength(2)
    expect(evals[0]!.options).toEqual(['allow_once', 'deny'])
    expect(evals[0]!.browser?.command).toBe('eval document.title')
    const file = path.join(dir, 'allowed', 'cv.pdf')
    expect(await browse(`upload 4 ${file}`)).toMatchObject({ ok: true })
    const upload = asked.find(r => r.browser?.action === 'upload')
    expect(upload?.options).toEqual(['allow_once', 'deny'])
    expect(upload?.browser?.files).toEqual([file])
    expect(ran('upload')).toEqual([['browser', 'chat-1', 'upload', '4', file]])
  })

  it('refuses uploads from outside the allowed dirs', async () => {
    state.patch({ tabs: { 'chat-1': 'https://app.example/' } })
    await start()
    const result = await browse('upload 4 /etc/passwd')
    expect(result).toMatchObject({ ok: false, code: 'not_allowed' })
    expect(ran('upload')).toEqual([])
  })

  it('bind shows the focused tab, binds after allow, and grants its site with allow_session', async () => {
    state.patch({ focused: { url: 'https://bank.example/account', title: '我的账户' } })
    answer = () => 'allow_session'
    await start()
    const result = await browse('bind')
    expect(result).toMatchObject({ ok: true })
    expect(asked).toHaveLength(1)
    expect(asked[0]!.browser).toMatchObject({ action: 'bind', site: 'bank.example', url: 'https://bank.example/account', title: '我的账户' })
    expect(asked[0]!.summary).toContain('我的账户')
    // A throwaway probe session read the tab and was released before the real bind.
    const calls = state.calls().map(a => `${a[1]?.startsWith('byclaw-probe-') ? 'probe' : a[1]} ${a[2]}`)
    expect(calls).toEqual(['probe bind', 'probe unbind', 'chat-1 bind'])
    // The bound site is allowed now; reads go through without a prompt.
    expect(await browse('state')).toMatchObject({ ok: true })
    expect(asked).toHaveLength(1)
    // bind always asks, even on an allowed site.
    await browse('bind')
    expect(asked).toHaveLength(2)
  })

  it('bind refuses when the focused tab changed during the prompt', async () => {
    state.patch({ focused: { url: 'https://a.example/', title: 'A' }, focusedLater: { url: 'https://b.example/', title: 'B' } })
    await start()
    const result = await browse('bind')
    expect(result).toMatchObject({ ok: false, code: 'failed' })
    expect(result.error).toContain('标签页变了')
    expect(state.calls().at(-1)).toEqual(['browser', 'chat-1', 'unbind'])
  })

  it('deny: nothing runs, the result is denied, the audit records it without typed text', async () => {
    state.patch({ tabs: { 'chat-1': 'https://mail.example.com/' } })
    answer = () => 'deny'
    await start()
    const result = await browse('type 3 "my secret password"')
    expect(result).toMatchObject({ ok: false, code: 'denied' })
    expect(ran('type')).toEqual([])
    const entries = new AuditLog(path.join(dir, 'audit')).recent()
    expect(entries.at(-1)).toMatchObject({ tool: 'device_browser', outcome: 'denied', decision: 'deny', session: 'chat-1' })
    expect(entries.at(-1)!.summary).toBe('chat-1 type 3 …（18 个字符） @mail.example.com')
    expect(JSON.stringify(entries)).not.toContain('secret')
  })

  it('session commands (close, unbind) never ask', async () => {
    state.patch({ tabs: { 'chat-1': 'https://x.example/' } })
    await start()
    expect(await browse('unbind')).toMatchObject({ ok: true })
    expect(asked).toHaveLength(0)
  })
})

describe('device_browser execution', () => {
  it('returns screenshots as images and deletes the temp file', async () => {
    state.patch({ shot: { width: 64, height: 40 } })
    await start()
    const result = await browse('screenshot')
    expect(result).toMatchObject({ ok: true })
    expect(result.images).toHaveLength(1)
    expect(result.images[0]).toMatchObject({ mime: 'image/png', width: 64, height: 40 })
    expect(Buffer.from(result.images[0].data, 'base64').subarray(1, 4).toString()).toBe('PNG')
    expect(result.output).toContain('64×40')
    const argv = ran('screenshot')[0]!
    expect(argv[3]).toMatch(new RegExp(`^${path.join(dir, 'browser-tmp').replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}/shot-.*\\.png$`))
    expect(fs.readdirSync(path.join(dir, 'browser-tmp'))).toEqual([])
  })

  it('reports byCLI failures with its error envelope, and timeouts', async () => {
    state.patch({ failClick: true })
    await start({ browser: { bycli, tempDir: path.join(dir, 'browser-tmp'), timeoutMs: 1500 } })
    const failed = await browse('click 999')
    expect(failed).toMatchObject({ ok: false, code: 'failed' })
    expect(failed.error).toContain('not_found')
    const slow = await browse('wait time 5')
    expect(slow).toMatchObject({ ok: false, code: 'timeout' })
  })

  it('refuses commands outside the whitelist without running byCLI', async () => {
    await start()
    const result = await browse('analyze https://x.example')
    expect(result).toMatchObject({ ok: false, code: 'failed' })
    expect(result.error).toContain('不支持的子命令')
    expect(state.calls()).toEqual([])
  })

  it('uses the session argument over the A-side session', async () => {
    await start()
    await browse('state', { session: 'work' })
    expect(state.calls().map(a => a[1])).toEqual(['work', 'work'])
  })
})
