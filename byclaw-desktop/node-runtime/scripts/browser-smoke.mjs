// device_browser smoke against a real byCLI + Chrome/Chromium with the byCLI extension loaded.
// Runs the built node runtime behind an in-process stub relay, with a scripted confirmation.
//   node scripts/browser-smoke.mjs --bycli <byCLI package dir | main.js | bin> --page <url of a page with #btn and #out>
// The page should have a <button id="btn"> that writes "clicked" into #out. Exit code 0 = all checks passed.
import fs from 'node:fs'
import { createRequire } from 'node:module'
import os from 'node:os'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { parseArgs } from 'node:util'

const HERE = path.dirname(fileURLToPath(import.meta.url))
const require = createRequire(path.join(HERE, '..', 'node_modules', 'ws', 'package.json'))
const { WebSocketServer } = require('ws')
const { AuditLog, BycliManager, resolveBycli, startNode } = await import(path.join(HERE, '..', 'dist', 'index.js'))

const { values } = parseArgs({ options: { bycli: { type: 'string' }, page: { type: 'string' }, 'other-page': { type: 'string' } } })
if (!values.bycli || !values.page) {
  console.error('usage: node scripts/browser-smoke.mjs --bycli <path> --page <url> [--other-page <url on another host>]')
  process.exit(2)
}
const install = resolveBycli(values.bycli)
if (!install) throw new Error(`not a byCLI install: ${values.bycli}`)
const W = fs.mkdtempSync(path.join(os.tmpdir(), 'byclaw-browser-smoke-'))
const sleep = ms => new Promise(r => setTimeout(r, ms))
const log = m => process.stderr.write(`[smoke ${new Date().toISOString().slice(11, 23)}] ${m}\n`)
const checks = []
const check = (name, ok, detail = '') => {
  checks.push({ name, ok })
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? `  — ${detail}` : ''}`)
}

// Stub relay.
const server = new WebSocketServer({ port: 0, host: '127.0.0.1' })
await new Promise(r => server.once('listening', r))
const frames = []
let socket
server.on('connection', ws => {
  socket = ws
  ws.on('message', raw => frames.push(JSON.parse(raw.toString())))
})
const relayUrl = `ws://127.0.0.1:${server.address().port}/device-relay/node`
const waitFrame = async (pred, ms = 120_000) => {
  const end = Date.now() + ms
  while (Date.now() < end) {
    const f = frames.find(pred)
    if (f) return f
    await sleep(50)
  }
  throw new Error('timed out waiting for a frame')
}

// byCLI daemon (or the one already running).
const bycli = new BycliManager({ install, log, healthIntervalMs: 2000 })
await bycli.start()
log(`byCLI ${install.version} daemon ${bycli.status.daemon}${bycli.status.external ? ' (external)' : ''}`)

// Scripted confirmation: a queue of decisions per prompt action.
const script = { access: [], eval: [], upload: [], bind: [] }
const prompts = []
const confirm = async request => {
  const action = request.browser?.action ?? request.kind
  const decision = script[action]?.shift() ?? 'deny'
  prompts.push({ action, site: request.browser?.site, title: request.browser?.title, url: request.browser?.url, command: request.browser?.command, options: request.options, decision })
  log(`prompt ${action} site=${request.browser?.site ?? ''} ${request.browser?.title ? `title=${request.browser.title} ` : ''}options=${request.options.join('/')} -> ${decision}`)
  return decision
}

const node = startNode({
  relayUrl,
  deviceToken: 'smoke-token-0123456789',
  name: 'smoke',
  appVersion: '0.0.0-smoke',
  allowedDirs: [W],
  ownDataDirs: [path.join(W, 'state')],
  confirm,
  audit: new AuditLog(path.join(W, 'audit')),
  shellEnv: 'inherit',
  browser: { bycli, tempDir: path.join(W, 'state', 'browser-tmp') },
  log,
})
const register = await waitFrame(f => f.t === 'register', 10_000)
log(`register capabilities: ${register.capabilities.join(',')}`)
if (!register.capabilities.includes('browser')) {
  const update = await waitFrame(f => f.t === 'capabilities' && f.capabilities.includes('browser'), 60_000).catch(() => undefined)
  check('extension connected -> capabilities frame adds browser', Boolean(update), update ? update.capabilities.join(',') : 'never connected')
}
else check('extension connected -> register includes browser', true, register.capabilities.join(','))

let seq = 0
const invoke = async (command, session = 'smoke-chat', extra = {}) => {
  const id = `s${++seq}`
  const before = prompts.length
  socket.send(JSON.stringify({ t: 'invoke', id, tool: 'device_browser', args: { command, ...extra }, session }))
  const result = await waitFrame(f => f.t === 'result' && f.id === id)
  const asked = prompts.slice(before)
  const waited = frames.some(f => f.t === 'human_wait' && f.id === id)
  const out = (result.output ?? result.error ?? '').replace(/\s+/g, ' ').slice(0, 160)
  log(`${command} -> ok=${result.ok}${result.code ? ` code=${result.code}` : ''} prompts=${asked.length} ${out}`)
  return { result, asked, waited }
}

const page = new URL(values.page)
script.access.push('allow_session')
let r = await invoke(`open ${values.page}`)
check('open: first access asks (allow_once/allow_session/deny) by target host', r.result.ok && r.asked.length === 1 && r.asked[0].site === page.hostname && r.waited && r.asked[0].options.join() === 'allow_once,allow_session,deny', JSON.stringify(r.asked[0] ?? {}))

r = await invoke('state')
check('state on the allowed site: no prompt, page snapshot returned', r.result.ok && r.asked.length === 0 && /btn|Press/i.test(r.result.output ?? ''), (r.result.output ?? '').slice(0, 120).replace(/\s+/g, ' '))

r = await invoke("click '#btn'")
check('click on the allowed site: no prompt', r.result.ok && r.asked.length === 0, r.result.output?.replace(/\s+/g, ' ').slice(0, 120))

r = await invoke("get text '#out'")
check('click took effect (get text #out = clicked)', r.result.ok && /clicked/.test(r.result.output ?? ''), r.result.output?.replace(/\s+/g, ' ').slice(0, 120))

r = await invoke('screenshot')
const image = r.result.images?.[0]
check('screenshot returns an image', r.result.ok && image && image.mime === 'image/png' && image.data.length > 1000 && image.data.length <= 2 * 1024 * 1024, image ? `${image.width}x${image.height}, ${image.data.length} base64 chars` : r.result.error)
if (image) fs.writeFileSync(path.join(W, 'screenshot.png'), Buffer.from(image.data, 'base64'))
check('screenshot temp file deleted', fs.readdirSync(path.join(W, 'state', 'browser-tmp')).length === 0)

script.eval.push('deny', 'allow_once')
r = await invoke('eval "document.title"')
check('eval asks even on an allowed site; deny -> denied, not run', !r.result.ok && r.result.code === 'denied' && r.asked.length === 1 && r.asked[0].options.join() === 'allow_once,deny')
r = await invoke('eval "document.title"')
check('eval asks again; allow_once -> runs', r.result.ok && r.asked.length === 1, r.result.output)

script.bind.push('allow_session')
r = await invoke('bind', 'smoke-bind')
check('bind asks with the focused tab title/url, then binds', r.result.ok && r.asked.length === 1 && r.asked[0].action === 'bind' && Boolean(r.asked[0].url), `${r.asked[0]?.title ?? ''} ${r.asked[0]?.url ?? ''}`)
r = await invoke('get title', 'smoke-bind')
check('bound session: reads need no further prompt', r.result.ok && r.asked.length === 0, r.result.output)

if (values['other-page']) {
  script.access.push('deny')
  r = await invoke(`open ${values['other-page']}`)
  check('another host: asks again; deny -> denied', !r.result.ok && r.result.code === 'denied' && r.asked.length === 1 && r.asked[0].site === new URL(values['other-page']).hostname, r.asked[0]?.site)
}

r = await invoke('analyze https://example.com')
check('non-whitelisted subcommand refused', !r.result.ok && /不支持的子命令/.test(r.result.error ?? ''))

const audit = new AuditLog(path.join(W, 'audit')).recent()
log(`audit:\n${audit.map(e => `  ${e.outcome} ${e.decision ?? '-'} ${e.summary}`).join('\n')}`)
check('audit has one line per call', audit.length === seq, `${audit.length} lines`)

await invoke('unbind', 'smoke-bind')
await invoke('close')
await node.stop()
await bycli.stop()
server.close()
const failed = checks.filter(c => !c.ok)
console.log(`\n${checks.length - failed.length}/${checks.length} checks passed; workdir ${W}`)
process.exit(failed.length ? 1 : 0)
