// Interop smoke: the byclaw-device plugin's e2e mock relay <-> the built byclaw-node CLI, driven by a
// fake A host. Usage (after `pnpm build`):
//   node scripts/interop-smoke.mjs <path to byclaw-device/tests/e2e/relay.mjs>
// The relay is copied into a temp dir with its `ws` require pointed at this package's node_modules.
import { spawn } from 'node:child_process'
import { createHmac } from 'node:crypto'
import fs from 'node:fs'
import { createRequire } from 'node:module'
import os from 'node:os'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
const HERE = path.dirname(fileURLToPath(import.meta.url))
const WS = path.join(HERE, '..', 'node_modules', 'ws', 'package.json')
const require = createRequire(WS)
const WebSocket = require('ws')
const CLI = path.join(HERE, '..', 'dist', 'cli.js')
const relaySource = process.argv[2]
if (!relaySource) { console.error('usage: node scripts/interop-smoke.mjs <relay.mjs>'); process.exit(2) }
const W = fs.mkdtempSync(path.join(os.tmpdir(), 'byclaw-interop-'))
fs.mkdirSync(W + '/allowed', { recursive: true })
fs.writeFileSync(W + '/relay.mjs', fs.readFileSync(relaySource, 'utf8').replace(/createRequire\([^)]*\)/, `createRequire(${JSON.stringify(WS)})`))
fs.writeFileSync(W + '/allowed/hello.txt', 'hello from the device\n')
fs.writeFileSync(W + '/outside.txt', 'nope')
const b64 = v => Buffer.from(JSON.stringify(v)).toString('base64url')
const unsigned = b64({ alg: 'HS256', typ: 'JWT' }) + '.' + b64({ sub: 'u1', did: 'dev_cli1', purpose: 'device-node', iat: 1 })
const token = unsigned + '.' + createHmac('sha256', 'device-secret').update(unsigned).digest('base64url')
fs.writeFileSync(W + '/token', token + '\n', { mode: 0o600 })
const sleep = ms => new Promise(r => setTimeout(r, ms))
const procs = []
const run = (name, args, env = {}) => { const p = spawn('node', args, { env: { ...process.env, ...env }, stdio: ['ignore', 'ignore', 'pipe'] }); p.stderr.on('data', d => fs.appendFileSync(W + '/' + name + '.log', d)); procs.push(p); return p }
run('relay', [W + '/relay.mjs'], { RELAY_PORT: '9311' })
await sleep(500)
const startCli = (confirm) => run('cli-' + confirm, [CLI, '--relay', 'ws://127.0.0.1:9311/device-relay/node', '--token-file', W + '/token', '--allow-dir', W + '/allowed', '--confirm', confirm, '--state-dir', W + '/state', '--name', 'CLI 电脑', '--shell-timeout', '2'])
let cli = startCli('auto-allow')
const host = new WebSocket('ws://127.0.0.1:9311/device-relay/host', { headers: { Authorization: 'Bearer host-secret' } })
const frames = []
host.on('message', raw => frames.push(JSON.parse(raw)))
await new Promise(r => host.on('open', r))
host.send(JSON.stringify({ t: 'subscribe', users: ['u1'] }))
const waitFor = async (pred, ms = 8000) => { const end = Date.now() + ms; while (Date.now() < end) { const f = frames.find(pred); if (f) return f; await sleep(30) } throw new Error('timeout waiting') }
const presence = await waitFor(f => f.t === 'presence' && f.online)
console.log('presence', JSON.stringify(presence))
let n = 0
const invoke = async (tool, args, session) => {
  const id = 'x' + (++n)
  host.send(JSON.stringify({ t: 'invoke', id, user: 'u1', device: 'dev_cli1', tool, args, ...(session ? { session } : {}) }))
  const result = await waitFor(f => f.t === 'result' && f.id === id)
  const seq = frames.filter(f => f.id === id).map(f => f.t).join('>')
  console.log(id, tool, JSON.stringify(args).slice(0, 80), '=>', seq, JSON.stringify({ ok: result.ok, code: result.code, output: result.output?.slice(0, 80), error: result.error?.slice(0, 80) }))
  return { id, result }
}
await invoke('device_shell', { command: 'echo hi && uname -s', cwd: W + '/allowed' }, 'sessA')
await invoke('device_files', { action: 'read', path: W + '/allowed/hello.txt' })
await invoke('device_files', { action: 'list', path: W + '/allowed' })
await invoke('device_files', { action: 'write', path: W + '/allowed/new.txt', content: '写入测试' })
await invoke('device_files', { action: 'search', path: W + '/allowed', query: 'hello' })
await invoke('device_files', { action: 'read', path: W + '/allowed/../outside.txt' })
await invoke('device_shell', { command: 'sleep 10' })
// cancel
const cid = 'cancel1'
host.send(JSON.stringify({ t: 'invoke', id: cid, user: 'u1', device: 'dev_cli1', tool: 'device_shell', args: { command: 'sleep 30' } }))
await waitFor(f => f.t === 'ack' && f.id === cid)
await sleep(300)
host.send(JSON.stringify({ t: 'cancel', id: cid, user: 'u1', device: 'dev_cli1' }))
const cr = await waitFor(f => f.t === 'result' && f.id === cid)
console.log(cid, '=>', frames.filter(f => f.id === cid).map(f => f.t).join('>'), JSON.stringify({ ok: cr.ok, code: cr.code, error: cr.error }))
// restart with auto-deny
cli.kill('SIGTERM'); await waitFor(f => f.t === 'presence' && !f.online)
console.log('presence offline after SIGTERM')
cli = startCli('auto-deny')
await sleep(300)
await waitFor(f => f.t === 'presence' && f.online && frames.indexOf(f) > frames.findIndex(g => g.t === 'presence' && !g.online))
await invoke('device_shell', { command: 'touch ' + W + '/should-not-exist' })
console.log('denied command ran?', fs.existsSync(W + '/should-not-exist'))
// offline result
cli.kill('SIGTERM'); await sleep(500)
await invoke('device_shell', { command: 'echo x' })
// bad token -> 4401 -> CLI exits 3
fs.writeFileSync(W + '/badtoken', 'a.b.c')
const bad = spawn('node', [CLI, '--relay', 'ws://127.0.0.1:9311/device-relay/node', '--token-file', W + '/badtoken', '--confirm', 'auto-deny', '--state-dir', W + '/state2'], { stdio: ['ignore', 'ignore', 'pipe'] })
let badErr = ''; bad.stderr.on('data', d => { badErr += d })
const code = await new Promise(r => bad.on('exit', r))
console.log('bad token exit code', code, '|', badErr.trim().split('\n').slice(-1)[0])
console.log('audit lines', fs.readdirSync(W + '/state/audit').map(f => fs.readFileSync(W + '/state/audit/' + f, 'utf8').trim().split('\n').length))
host.close(); for (const p of procs) p.kill('SIGKILL')
console.log('work dir (logs, audit):', W)
process.exit(0)
