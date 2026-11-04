import fs from 'node:fs'
import path from 'node:path'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { CappedBuffer, captureLoginEnv, MAX_STREAM_BYTES, runShell, scrubEnv, truncateText } from '../src/shell.js'
import { isAlive, sleep, tempDir, waitFor } from './helpers.js'

let dir: string
let cleanup: () => void
beforeEach(() => ({ dir, cleanup } = tempDir()))
afterEach(() => cleanup())

const env = () => scrubEnv(process.env)

describe('truncation', () => {
  it('keeps the first 64 KB and marks the rest', () => {
    const b = new CappedBuffer()
    b.push(Buffer.alloc(MAX_STREAM_BYTES - 10, 97))
    b.push(Buffer.alloc(100, 98))
    expect(b.truncated).toBe(true)
    const text = b.text()
    expect(text.startsWith('a'.repeat(MAX_STREAM_BYTES - 10) + 'b'.repeat(10))).toBe(true)
    expect(text).toContain(`共 ${MAX_STREAM_BYTES + 90} 字节`)
    expect(truncateText('short')).toBe('short')
  })

  it('truncates stdout and stderr independently', async () => {
    const r = await runShell({ command: `head -c 100000 /dev/zero | tr '\\0' x; head -c 70000 /dev/zero | tr '\\0' y >&2`, cwd: dir, env: env() })
    expect(r.exitCode).toBe(0)
    expect(r.stdout).toContain('[输出已截断：共 100000 字节')
    expect(r.stderr).toContain('[输出已截断：共 70000 字节')
    expect(r.stdout.split('\n')[0]).toHaveLength(MAX_STREAM_BYTES)
  })
})

describe('runShell', () => {
  it('runs in cwd with the given env and reports the exit code', async () => {
    const r = await runShell({ command: 'pwd; echo "$FOO"; exit 3', cwd: dir, env: { ...env(), FOO: 'bar' } })
    expect(r.stdout).toBe(`${dir}\nbar\n`)
    expect(r.exitCode).toBe(3)
  })

  it('times out and kills the whole process group', async () => {
    const pidFile = path.join(dir, 'pid')
    const r = await runShell({ command: `sleep 30 & echo $! > ${pidFile}; wait`, cwd: dir, env: env(), timeoutMs: 300 })
    expect(r.timedOut).toBe(true)
    const pid = Number(fs.readFileSync(pidFile, 'utf8'))
    await waitFor(() => !isAlive(pid), 3000)
  })

  it('cancel via AbortSignal kills children too', async () => {
    const pidFile = path.join(dir, 'pid')
    const controller = new AbortController()
    const running = runShell({ command: `sh -c 'sleep 30' & echo $! > ${pidFile}; wait`, cwd: dir, env: env(), signal: controller.signal })
    const pid = await waitFor(() => fs.existsSync(pidFile) && Number(fs.readFileSync(pidFile, 'utf8')))
    expect(isAlive(pid)).toBe(true)
    controller.abort()
    const r = await running
    expect(r.cancelled).toBe(true)
    await waitFor(() => !isAlive(pid), 3000)
  })

  it('escalates to SIGKILL when the command ignores SIGTERM', async () => {
    const t0 = Date.now()
    const r = await runShell({ command: `trap '' TERM; sleep 30`, cwd: dir, env: env(), timeoutMs: 200 })
    expect(r.timedOut).toBe(true)
    expect(Date.now() - t0).toBeLessThan(6000)
  })
})

describe('environment', () => {
  it('scrubs the client\'s own variables and secrets', () => {
    const out = scrubEnv({ PATH: '/bin', BYCLAW_DESKTOP_URL: 'x', ELECTRON_RUN_AS_NODE: '1', MY_DEVICE_TOKEN: 't', OTHER: 'contains-secret-token-value', HOME: '/h' }, ['secret-token-value'])
    expect(out).toEqual({ PATH: '/bin', HOME: '/h' })
  })

  it('captures the login shell env through the runtime executable', async () => {
    const captured = await captureLoginEnv({ shell: '/bin/sh', timeoutMs: 10_000 })
    expect(captured?.PATH).toBeTruthy()
    expect(captured?.ELECTRON_RUN_AS_NODE).toBeUndefined()
  })

  it('returns undefined when the shell does not answer in time', async () => {
    const fake = path.join(dir, 'slowshell')
    fs.writeFileSync(fake, '#!/bin/sh\nsleep 5\n', { mode: 0o755 })
    const t0 = Date.now()
    expect(await captureLoginEnv({ shell: fake, timeoutMs: 200 })).toBeUndefined()
    expect(Date.now() - t0).toBeLessThan(2000)
    await sleep(10)
  })
})
