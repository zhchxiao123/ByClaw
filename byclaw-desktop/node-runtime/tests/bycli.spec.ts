import { spawn, type ChildProcess } from 'node:child_process'
import fs from 'node:fs'
import path from 'node:path'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { bycliEnv, BycliManager, fetchDaemonStatus, resolveBycli, type BycliStatus } from '../src/bycli.js'
import { FAKE_BYCLI_DIR, FakeBycliState, freePort } from './fake-bycli.js'
import { isAlive, tempDir, waitFor } from './helpers.js'

let dir: string
let cleanup: () => void
let state: FakeBycliState
let port: number
let managers: BycliManager[]
let extra: ChildProcess[]

beforeEach(async () => {
  ;({ dir, cleanup } = tempDir('byclaw-bycli-'))
  state = new FakeBycliState(dir)
  port = await freePort()
  managers = []
  extra = []
})

afterEach(async () => {
  await Promise.all(managers.map(m => m.stop()))
  for (const child of extra) child.kill('SIGKILL')
  cleanup()
})

function manager(over: Partial<ConstructorParameters<typeof BycliManager>[0]> = {}): BycliManager {
  const install = resolveBycli(FAKE_BYCLI_DIR)
  if (!install) throw new Error('fake bycli not found')
  const m = new BycliManager({ install, env: state.env(), port, healthIntervalMs: 100, ...over })
  managers.push(m)
  return m
}

describe('resolveBycli', () => {
  it('accepts the package dir, its main.js, a symlinked bin and npm\'s global wrapper layout', () => {
    const fromDir = resolveBycli(FAKE_BYCLI_DIR)
    expect(fromDir).toMatchObject({
      packageDir: FAKE_BYCLI_DIR,
      entry: path.join(FAKE_BYCLI_DIR, 'dist/src/main.js'),
      daemonScript: path.join(FAKE_BYCLI_DIR, 'dist/src/daemon.js'),
      extensionDir: path.join(FAKE_BYCLI_DIR, 'extension'),
      version: '0.0.0-fake',
    })
    expect(resolveBycli(path.join(FAKE_BYCLI_DIR, 'dist/src/main.js'))?.packageDir).toBe(FAKE_BYCLI_DIR)
    const bin = path.join(dir, 'bin')
    fs.mkdirSync(bin)
    fs.symlinkSync(path.join(FAKE_BYCLI_DIR, 'dist/src/main.js'), path.join(bin, 'bycli'))
    expect(resolveBycli(path.join(bin, 'bycli'))?.packageDir).toBe(FAKE_BYCLI_DIR)
    // <prefix>/bin/bycli is a wrapper script; the package sits in <prefix>/lib/node_modules.
    const prefix = path.join(dir, 'prefix')
    fs.mkdirSync(path.join(prefix, 'bin'), { recursive: true })
    fs.mkdirSync(path.join(prefix, 'lib', 'node_modules', '@sovovs'), { recursive: true })
    fs.symlinkSync(FAKE_BYCLI_DIR, path.join(prefix, 'lib', 'node_modules', '@sovovs', 'bycli'))
    fs.writeFileSync(path.join(prefix, 'bin', 'bycli'), '#!/bin/sh\nexit 0\n', { mode: 0o755 })
    expect(resolveBycli(path.join(prefix, 'bin', 'bycli'))?.entry).toContain(path.join('dist', 'src', 'main.js'))
    expect(resolveBycli(path.join(dir, 'nothing-here'))).toBeUndefined()
    expect(resolveBycli(dir)).toBeUndefined()
  })
})

describe('bycliEnv', () => {
  it('binds to loopback and keeps the daemon off the HTTP proxy', () => {
    const env = bycliEnv({ PATH: '/bin', HTTP_PROXY: 'http://proxy:3128', NO_PROXY: 'corp.local' }, 19825)
    expect(env).toMatchObject({ BYCLI_DAEMON_HOST: '127.0.0.1', BYCLI_DAEMON_PORT: '19825', HTTP_PROXY: 'http://proxy:3128' })
    expect(env.NO_PROXY?.split(',')).toEqual(['127.0.0.1', 'localhost', 'corp.local'])
    expect(env.no_proxy).toBe(env.NO_PROXY)
  })
})

describe('BycliManager', () => {
  it('starts the daemon as its child on 127.0.0.1 and stops it on stop()', async () => {
    const m = manager()
    await m.start()
    expect(m.status).toMatchObject({ daemon: 'running', external: false, extensionConnected: false })
    const [started] = state.daemons()
    expect(started).toMatchObject({ host: '127.0.0.1', port, noProxy: expect.stringContaining('127.0.0.1') })
    expect(isAlive(started!.pid)).toBe(true)
    await m.stop()
    expect(m.status.daemon).toBe('stopped')
    await waitFor(() => !isAlive(started!.pid))
    expect(state.daemons().some(d => d.stopped)).toBe(true)
    expect(await fetchDaemonStatus(port, 500)).toBeUndefined()
  })

  it('reports the extension as it connects and disconnects (status events)', async () => {
    const m = manager()
    const seen: BycliStatus[] = []
    m.on('status', s => seen.push(s))
    await m.start()
    expect(m.connected).toBe(false)
    state.patch({ connected: true })
    await waitFor(() => m.connected, 3000)
    state.patch({ connected: false })
    await waitFor(() => !m.connected, 3000)
    expect(seen.map(s => s.extensionConnected)).toEqual(expect.arrayContaining([false, true]))
    expect(seen.filter(s => s.extensionConnected).length).toBe(1)
  })

  it('restarts its daemon after a crash', async () => {
    const m = manager()
    await m.start()
    const first = state.daemons()[0]!.pid
    process.kill(first, 'SIGKILL')
    await waitFor(() => state.daemons().filter(d => !d.stopped).length >= 2, 8000)
    await waitFor(() => m.status.daemon === 'running', 5000)
    expect(state.daemons().at(-1)!.pid).not.toBe(first)
  })

  it('uses a daemon that already answers and leaves it running on stop()', async () => {
    const other = spawn(process.execPath, [path.join(FAKE_BYCLI_DIR, 'dist/src/daemon.js')], { env: { ...state.env(), BYCLI_DAEMON_PORT: String(port) }, stdio: 'ignore' })
    extra.push(other)
    await waitFor(() => state.daemons().length > 0, 5000)
    state.patch({ connected: true })
    const m = manager()
    await m.start()
    expect(m.status).toMatchObject({ daemon: 'running', external: true, extensionConnected: true })
    expect(state.daemons().length).toBe(1)
    await m.stop()
    expect(isAlive(other.pid!)).toBe(true)
  })

  it('never starts a daemon with startDaemon: false', async () => {
    const m = manager({ startDaemon: false })
    await m.start()
    expect(m.status.daemon).toBe('stopped')
    expect(state.daemons()).toEqual([])
  })

  it('runs bycli without a shell and reports timeouts', async () => {
    const m = manager()
    await m.start()
    const ok = await m.run(['browser', 's1', 'click', '$(id)'], { timeoutMs: 10_000 })
    expect(ok.exitCode).toBe(0)
    expect(JSON.parse(ok.stdout)).toMatchObject({ sub: 'click', args: ['$(id)'] })
    const slow = await m.run(['browser', 's1', 'wait', 'time', '5'], { timeoutMs: 300 })
    expect(slow.timedOut).toBe(true)
    const controller = new AbortController()
    const pending = m.run(['browser', 's1', 'wait', 'time', '5'], { timeoutMs: 10_000, signal: controller.signal })
    setTimeout(() => controller.abort(), 100)
    expect((await pending).cancelled).toBe(true)
  })
})
