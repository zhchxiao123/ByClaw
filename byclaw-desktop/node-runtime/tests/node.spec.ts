import fs from 'node:fs'
import path from 'node:path'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { AuditLog } from '../src/audit.js'
import { autoAllow, autoDeny, type ConfirmFn, type Decision } from '../src/confirm.js'
import { startNode, type NodeHandle, type NodeOptions, type StatusInfo } from '../src/node.js'
import WebSocket from 'ws'
import { FakeRelay } from './fake-relay.js'
import { isAlive, sleep, tempDir, waitFor } from './helpers.js'

let relay: FakeRelay
let url: string
let dir: string
let allowed: string
let cleanup: () => void
let nodes: NodeHandle[]

beforeEach(async () => {
  relay = new FakeRelay()
  url = await relay.start()
  ;({ dir, cleanup } = tempDir())
  allowed = path.join(dir, 'allowed')
  fs.mkdirSync(allowed)
  fs.writeFileSync(path.join(allowed, 'note.txt'), 'note body')
  nodes = []
})

afterEach(async () => {
  await Promise.all(nodes.map(n => n.stop()))
  await relay.stop()
  cleanup()
})

const TOKEN = 'device.jwt.token-value-123'

function start(over: Partial<NodeOptions> = {}): NodeHandle {
  const node = startNode({
    relayUrl: url,
    deviceToken: TOKEN,
    name: '测试电脑',
    platform: 'linux',
    appVersion: '0.1.0-test',
    allowedDirs: [allowed],
    forbiddenDirs: [path.join(allowed, 'secret')],
    confirm: autoAllow,
    audit: new AuditLog(path.join(dir, 'audit')),
    shellEnv: 'inherit',
    heartbeatMs: 100,
    backoff: { minMs: 50, maxMs: 400 },
    ...over,
  })
  nodes.push(node)
  return node
}

function statuses(node: NodeHandle): StatusInfo[] {
  const seen: StatusInfo[] = []
  node.on('status', info => seen.push(info))
  return seen
}

const online = (node: NodeHandle) => waitFor(() => node.status === 'online')

describe('connection', () => {
  it('registers with the bearer token and sends heartbeats', async () => {
    const node = start({ capabilities: ['shell', 'files.read'] })
    await online(node)
    const conn = await waitFor(() => relay.conns[0])
    expect(conn.auth).toBe(`Bearer ${TOKEN}`)
    await waitFor(() => conn.frames.length > 0)
    expect(conn.frames[0]).toEqual({ t: 'register', name: '测试电脑', platform: 'linux', appVersion: '0.1.0-test', capabilities: ['shell', 'files.read'] })
    await waitFor(() => conn.frames.filter(f => f.t === 'heartbeat').length >= 2, 2000)
  })

  it('sends a capabilities frame when capabilities change', async () => {
    const node = start()
    await online(node)
    node.setCapabilities(['files.read'])
    await waitFor(() => relay.last.frames.find(f => f.t === 'capabilities'))
    expect(relay.last.frames.find(f => f.t === 'capabilities')).toEqual({ t: 'capabilities', capabilities: ['files.read'] })
  })

  it('reconnects after an unexpected close and registers again', async () => {
    const node = start()
    const seen = statuses(node)
    await online(node)
    relay.last.ws.close(1011)
    await waitFor(() => relay.conns.length === 2 && relay.conns[1]!.frames[0]?.t === 'register')
    await online(node)
    expect(seen.map(s => s.status)).toEqual(expect.arrayContaining(['offline', 'connecting', 'online']))
    expect(seen.find(s => s.status === 'offline')).toMatchObject({ reason: 'disconnected', closeCode: 1011, retryInMs: 50 })
  })

  it('backs off exponentially up to the cap', async () => {
    relay.closeOnConnect = 1011
    const node = start()
    const seen = statuses(node)
    await waitFor(() => seen.filter(s => s.status === 'offline').length >= 5, 5000)
    expect(seen.filter(s => s.status === 'offline').slice(0, 5).map(s => s.retryInMs)).toEqual([50, 100, 200, 400, 400])
  })

  it.each([[4401, 'unpaired'], [4403, 'revoked']] as const)('stops reconnecting on close code %i (%s)', async (code, reason) => {
    relay.closeOnConnect = code
    const node = start()
    await waitFor(() => node.status === 'offline' && node.info.reason)
    expect(node.info).toMatchObject({ status: 'offline', reason, closeCode: code })
    await sleep(300)
    expect(relay.conns).toHaveLength(1)
  })

  it('treats an HTTP 401 at upgrade as unpaired', async () => {
    relay.refuseWith = 401
    const node = start()
    await waitFor(() => node.info.reason === 'unpaired')
    await sleep(200)
    expect(node.status).toBe('offline')
  })

  it('pause disconnects without reconnecting; resume registers again', async () => {
    const node = start()
    await online(node)
    const first = relay.last
    node.pause()
    await waitFor(() => node.status === 'paused')
    await waitFor(() => first.ws.readyState === WebSocket.CLOSED)
    await sleep(300)
    expect(relay.conns).toHaveLength(1)
    node.resume()
    await online(node)
    await waitFor(() => relay.conns.length === 2 && relay.conns[1]!.frames[0]?.t === 'register')
  })

  it('can start paused and connects only on resume', async () => {
    const node = start({ paused: true })
    expect(node.status).toBe('paused')
    await sleep(200)
    expect(relay.conns).toHaveLength(0)
    node.resume()
    await online(node)
    expect(relay.conns).toHaveLength(1)
  })

  it('rename re-registers under the new name', async () => {
    const node = start()
    await online(node)
    node.setName('新名字')
    await waitFor(() => relay.conns.length === 2 && relay.conns[1]!.frames[0]?.name === '新名字')
  })
})

describe('invokes', () => {
  it('acks, waits for the human, then returns the shell result and audits it', async () => {
    const node = start({ confirm: async () => 'allow_once' })
    await online(node)
    relay.invoke('i1', 'device_shell', { command: 'echo hi; echo oops >&2', cwd: allowed }, 'sess-1')
    const result = await relay.result('i1')
    expect(relay.framesFor('i1').map(f => f.t)).toEqual(['ack', 'human_wait', 'result'])
    expect(relay.framesFor('i1')[1].prompt).toContain('请在「测试电脑」上确认是否执行命令：echo hi')
    expect(result).toEqual({ t: 'result', id: 'i1', ok: true, output: 'hi\n[stderr]\noops\n' })
    const audit = new AuditLog(path.join(dir, 'audit')).recent()
    expect(audit).toEqual([expect.objectContaining({ id: 'i1', session: 'sess-1', tool: 'device_shell', summary: 'echo hi; echo oops >&2', cwd: allowed, decision: 'allow_once', outcome: 'ok', exitCode: 0 })])
  })

  it('denial returns code denied and does not run the command', async () => {
    const marker = path.join(dir, 'ran')
    start({ confirm: autoDeny })
    await waitFor(() => relay.conns[0]?.frames.length)
    relay.invoke('i2', 'device_shell', { command: `touch ${marker}` })
    expect(await relay.result('i2')).toEqual({ t: 'result', id: 'i2', ok: false, code: 'denied', error: '用户拒绝了这一步' })
    expect(fs.existsSync(marker)).toBe(false)
    // A fixed policy asks no human, so A gets no human_wait.
    expect(relay.framesFor('i2').map(f => f.t)).toEqual(['ack', 'result'])
  })

  it('remembers allow_session for the session without asking again', async () => {
    const confirm = vi.fn<ConfirmFn>(async () => 'allow_session')
    start({ confirm })
    await waitFor(() => relay.conns[0]?.frames.length)
    relay.invoke('a', 'device_shell', { command: 'true' }, 's1')
    await relay.result('a')
    relay.invoke('b', 'device_shell', { command: 'echo second' }, 's1')
    expect((await relay.result('b')).output).toBe('second\n')
    expect(relay.framesFor('b').map(f => f.t)).toEqual(['ack', 'result'])
    relay.invoke('c', 'device_shell', { command: 'true' }, 's2')
    await relay.result('c')
    expect(confirm).toHaveBeenCalledTimes(2)
    expect(confirm.mock.calls[0]![0].options).toEqual(['allow_once', 'allow_session', 'deny'])
  })

  it('file read outside the allowed dirs is not_allowed without asking', async () => {
    const confirm = vi.fn<ConfirmFn>(async () => 'allow_once')
    start({ confirm })
    await waitFor(() => relay.conns[0]?.frames.length)
    relay.invoke('f1', 'device_files', { action: 'read', path: `${allowed}/../outside.txt` })
    expect(await relay.result('f1')).toMatchObject({ ok: false, code: 'not_allowed' })
    relay.invoke('f2', 'device_files', { action: 'read', path: path.join(allowed, 'secret', 'k') })
    expect(await relay.result('f2')).toMatchObject({ ok: false, code: 'not_allowed' })
    relay.invoke('f3', 'device_shell', { command: 'pwd', cwd: '/' })
    expect(await relay.result('f3')).toMatchObject({ ok: false, code: 'not_allowed' })
    expect(confirm).not.toHaveBeenCalled()
  })

  it('reads, writes and lists inside the allowed dir; offers allow_always for reads', async () => {
    const confirm = vi.fn<ConfirmFn>(async () => 'allow_once')
    start({ confirm })
    await waitFor(() => relay.conns[0]?.frames.length)
    relay.invoke('r', 'device_files', { action: 'read', path: path.join(allowed, 'note.txt') }, 's')
    expect(await relay.result('r')).toEqual({ t: 'result', id: 'r', ok: true, output: 'note body' })
    expect(confirm.mock.calls[0]![0]).toMatchObject({ kind: 'files.read', options: ['allow_once', 'allow_session', 'allow_always', 'deny'], summary: `读取文件：${path.join(allowed, 'note.txt')}` })
    relay.invoke('w', 'device_files', { action: 'write', path: path.join(allowed, 'out.txt'), content: '写入' }, 's')
    expect(await relay.result('w')).toMatchObject({ ok: true })
    expect(fs.readFileSync(path.join(allowed, 'out.txt'), 'utf8')).toBe('写入')
    expect(confirm.mock.calls[1]![0].options).toEqual(['allow_once', 'allow_session', 'deny'])
    relay.invoke('l', 'device_files', { action: 'list', path: allowed })
    expect(JSON.parse((await relay.result('l')).output).entries.map((e: any) => e.name)).toEqual(['note.txt', 'out.txt'])
  })

  it('asks for a new allowed dir when the path is outside, then proceeds', async () => {
    const extra = path.join(dir, 'extra')
    fs.mkdirSync(extra)
    fs.writeFileSync(path.join(extra, 'x.txt'), 'extra body')
    const requestAllowedDir = vi.fn(async () => extra)
    const node = start({ requestAllowedDir })
    const persisted: string[][] = []
    node.on('allowedDirs', dirs => persisted.push(dirs))
    await waitFor(() => relay.conns[0]?.frames.length)
    relay.invoke('d', 'device_files', { action: 'read', path: path.join(extra, 'x.txt') })
    expect(await relay.result('d')).toMatchObject({ ok: true, output: 'extra body' })
    expect(relay.framesFor('d').map(f => f.t)).toEqual(['ack', 'human_wait', 'result'])
    expect(persisted).toEqual([[allowed, extra]])
  })

  it('shell timeout returns code timeout', async () => {
    start({ shellTimeoutMs: 200 })
    await waitFor(() => relay.conns[0]?.frames.length)
    relay.invoke('t', 'device_shell', { command: 'echo before; sleep 20' })
    const result = await relay.result('t')
    expect(result).toMatchObject({ ok: false, code: 'timeout' })
    expect(result.error).toContain('before')
  })

  it('cancel kills the running command and its children', async () => {
    const pidFile = path.join(dir, 'pid')
    start()
    await waitFor(() => relay.conns[0]?.frames.length)
    relay.invoke('c', 'device_shell', { command: `sleep 30 & echo $! > ${pidFile}; wait` })
    const pid = await waitFor(() => fs.existsSync(pidFile) && Number(fs.readFileSync(pidFile, 'utf8')))
    relay.cancel('c')
    expect(await relay.result('c')).toMatchObject({ ok: false, code: 'failed', error: '这一步已取消' })
    await waitFor(() => !isAlive(pid), 3000)
    expect(new AuditLog(path.join(dir, 'audit')).recent().at(-1)).toMatchObject({ id: 'c', outcome: 'cancelled' })
  })

  it('cancel during confirmation aborts the dialog', async () => {
    let signal: AbortSignal | undefined
    start({ confirm: async r => { signal = r.signal; return new Promise<Decision>(() => {}) } })
    await waitFor(() => relay.conns[0]?.frames.length)
    relay.invoke('q', 'device_shell', { command: 'echo never' })
    await waitFor(() => relay.framesFor('q').find(f => f.t === 'human_wait'))
    relay.cancel('q')
    expect(await relay.result('q')).toMatchObject({ ok: false, error: '这一步已取消' })
    expect(signal?.aborted).toBe(true)
  })

  it('reports failed for a missing capability, an unknown tool and a non-zero exit', async () => {
    start({ capabilities: ['files.read'] })
    await waitFor(() => relay.conns[0]?.frames.length)
    relay.invoke('s', 'device_shell', { command: 'true' })
    expect(await relay.result('s')).toMatchObject({ ok: false, code: 'failed', error: '电脑端没有开启能力 shell' })
    relay.invoke('u', 'device_magic', {})
    expect(await relay.result('u')).toMatchObject({ ok: false, code: 'failed' })
    relay.invoke('w', 'device_files', { action: 'write', path: path.join(allowed, 'x'), content: '' })
    expect(await relay.result('w')).toMatchObject({ ok: false, code: 'failed', error: '电脑端没有开启能力 files.write' })
  })

  it('non-zero exit is failed with the exit code and output', async () => {
    start()
    await waitFor(() => relay.conns[0]?.frames.length)
    relay.invoke('e', 'device_shell', { command: 'echo partial; exit 7' })
    expect(await relay.result('e')).toEqual({ t: 'result', id: 'e', ok: false, code: 'failed', error: '退出码 7\npartial\n' })
  })

  it('runs commands without the device token or BYCLAW_/ELECTRON_ variables', async () => {
    process.env.BYCLAW_TEST_SECRET = 'leak-me-please'
    process.env.SOME_VAR_WITH_TOKEN = TOKEN
    try {
      start()
      await waitFor(() => relay.conns[0]?.frames.length)
      relay.invoke('env', 'device_shell', { command: 'env' })
      const { output } = await relay.result('env')
      expect(output).toContain('PATH=')
      expect(output).not.toContain('leak-me-please')
      expect(output).not.toContain(TOKEN)
    }
    finally {
      delete process.env.BYCLAW_TEST_SECRET
      delete process.env.SOME_VAR_WITH_TOKEN
    }
  })

  it('ignores a duplicate invoke id while the first is running', async () => {
    start({ shellTimeoutMs: 5000 })
    await waitFor(() => relay.conns[0]?.frames.length)
    relay.invoke('dup', 'device_shell', { command: 'sleep 0.3; echo once' })
    relay.invoke('dup', 'device_shell', { command: 'echo twice' })
    expect((await relay.result('dup')).output).toBe('once\n')
    await sleep(100)
    expect(relay.framesFor('dup').filter(f => f.t === 'result')).toHaveLength(1)
  })

  it('pausing cancels a running command', async () => {
    const pidFile = path.join(dir, 'pid')
    const node = start()
    await online(node)
    relay.invoke('p', 'device_shell', { command: `sleep 30 & echo $! > ${pidFile}; wait` })
    const pid = await waitFor(() => fs.existsSync(pidFile) && Number(fs.readFileSync(pidFile, 'utf8')))
    node.pause()
    await waitFor(() => !isAlive(pid), 4000)
  })
})
