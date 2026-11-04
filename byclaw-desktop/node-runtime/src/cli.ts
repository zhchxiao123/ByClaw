#!/usr/bin/env node
/**
 * byclaw-node: run the device node headless (Linux validation, CI, or a Mac without the app).
 *
 *   byclaw-node --pair --be <baseUrl> --login-token <jwt> [--name <n>] [--token-file <f>]
 *   byclaw-node --relay <ws(s)://host/device-relay/node> (--token <t> | --token-file <f>)
 *               [--allow-dir <dir>]... [--confirm auto-allow|auto-deny|prompt] [--audit-dir <dir>]
 */

import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import readline from 'node:readline/promises'
import { parseArgs } from 'node:util'
import { AuditLog } from './audit.js'
import { autoAllow, autoDeny, DECISION_LABELS, JsonGrantStore, type ConfirmFn, type Decision } from './confirm.js'
import { defaultDeviceName, startNode } from './node.js'
import { pairDevice } from './pair.js'
import { canAllowDir, defaultForbiddenDirs } from './paths.js'
import { CAPABILITIES, type Capability } from './protocol.js'

const VERSION = '0.1.0'

const HELP = `byclaw-node ${VERSION} — ByClaw device node (headless)

Pair (writes the device token, mode 0600):
  byclaw-node --pair --be <baseUrl> --login-token <jwt> [--name <name>] [--token-file <file>]

Run:
  byclaw-node --relay <ws(s)://host/device-relay/node> [--token <t> | --token-file <file>]
              [--allow-dir <dir>]... [--confirm auto-allow|auto-deny|prompt]
              [--audit-dir <dir>] [--state-dir <dir>] [--capabilities shell,files.read,files.write]
              [--shell-timeout <seconds>] [--name <name>]

--pair together with --relay pairs, then runs.
Defaults: state dir ~/.byclaw-node (token file "device-token", grants.json, audit/),
--confirm prompt on a terminal and auto-deny otherwise, name = hostname.`

function fail(message: string): never {
  process.stderr.write(`byclaw-node: ${message}\n`)
  process.exit(2)
}

/** Terminal confirmation; prompts are serialized so concurrent invokes don't interleave. */
function terminalConfirm(): ConfirmFn {
  let queue = Promise.resolve<unknown>(undefined)
  return request => {
    const ask = async (): Promise<Decision> => {
      if (request.signal.aborted) return 'deny'
      const rl = readline.createInterface({ input: process.stdin, output: process.stderr })
      try {
        const lines = request.options.map((option, i) => `  ${i + 1}) ${DECISION_LABELS[option]}`).join('\n')
        const session = request.session ? `（会话 ${request.session}）` : ''
        const question = `\n[确认] ${request.device.name} · ${request.tool}${session}\n  ${request.summary}\n${lines}\n选择 [1-${request.options.length}]: `
        const answer = await rl.question(question, { signal: request.signal })
        const index = Number.parseInt(answer.trim(), 10) - 1
        return request.options[index] ?? 'deny'
      }
      catch {
        return 'deny'
      }
      finally {
        rl.close()
      }
    }
    const next = queue.then(ask, ask)
    queue = next
    return next
  }
}

async function main(): Promise<void> {
  let values
  try {
    ({ values } = parseArgs({
      options: {
        'relay': { type: 'string' },
        'token': { type: 'string' },
        'token-file': { type: 'string' },
        'pair': { type: 'boolean', default: false },
        'be': { type: 'string' },
        'login-token': { type: 'string' },
        'name': { type: 'string' },
        'allow-dir': { type: 'string', multiple: true, default: [] },
        'confirm': { type: 'string' },
        'audit-dir': { type: 'string' },
        'state-dir': { type: 'string' },
        'capabilities': { type: 'string' },
        'shell-timeout': { type: 'string' },
        'help': { type: 'boolean', short: 'h', default: false },
        'version': { type: 'boolean', default: false },
      },
      strict: true,
    }))
  }
  catch (error) {
    fail(`${(error as Error).message}\n\n${HELP}`)
  }
  if (values.help) {
    process.stdout.write(`${HELP}\n`)
    return
  }
  if (values.version) {
    process.stdout.write(`${VERSION}\n`)
    return
  }
  const stateDir = path.resolve(values['state-dir'] ?? path.join(os.homedir(), '.byclaw-node'))
  const tokenFile = path.resolve(values['token-file'] ?? path.join(stateDir, 'device-token'))
  const name = values.name ?? defaultDeviceName()
  const log = (message: string) => process.stderr.write(`[byclaw-node ${new Date().toISOString()}] ${message}\n`)

  if (values.pair) {
    if (!values.be || !values['login-token']) fail('--pair needs --be and --login-token')
    const paired = await pairDevice({ baseUrl: values.be, loginToken: values['login-token'], name, platform: process.platform, appVersion: VERSION })
    fs.mkdirSync(path.dirname(tokenFile), { recursive: true, mode: 0o700 })
    fs.writeFileSync(tokenFile, `${paired.deviceToken}\n`, { mode: 0o600 })
    fs.chmodSync(tokenFile, 0o600)
    log(`paired as ${paired.deviceId}; token written to ${tokenFile}`)
    if (!values.relay) return
  }

  if (!values.relay) fail(`--relay is required\n\n${HELP}`)
  let token = values.token
  if (!token) {
    try {
      token = fs.readFileSync(tokenFile, 'utf8').trim()
    }
    catch {
      fail(`no --token and cannot read ${tokenFile} (pair first with --pair)`)
    }
  }
  if (!token) fail('device token is empty')

  const mode = values.confirm ?? (process.stdin.isTTY ? 'prompt' : 'auto-deny')
  const confirm = mode === 'auto-allow' ? autoAllow : mode === 'auto-deny' ? autoDeny : mode === 'prompt' ? terminalConfirm() : fail(`unknown --confirm ${mode}`)
  const capabilities = values.capabilities === undefined
    ? [...CAPABILITIES]
    : values.capabilities.split(',').map(s => s.trim()).filter(Boolean)
  for (const c of capabilities) if (!(CAPABILITIES as readonly string[]).includes(c)) fail(`unknown capability ${c}`)
  const shellTimeoutMs = values['shell-timeout'] === undefined ? undefined : Number(values['shell-timeout']) * 1000
  if (shellTimeoutMs !== undefined && !(shellTimeoutMs > 0)) fail('--shell-timeout must be a positive number of seconds')
  const auditDir = path.resolve(values['audit-dir'] ?? path.join(stateDir, 'audit'))
  const allowedDirs = values['allow-dir'].map(dir => path.resolve(dir))

  const node = startNode({
    relayUrl: values.relay,
    deviceToken: token,
    name,
    appVersion: VERSION,
    capabilities: capabilities as Capability[],
    allowedDirs,
    // The token file itself, not its dir: a token kept in e.g. the home dir must not forbid all of it.
    ownDataDirs: [stateDir, tokenFile],
    confirm,
    grants: new JsonGrantStore(path.join(stateDir, 'grants.json')),
    audit: new AuditLog(auditDir),
    ...(shellTimeoutMs ? { shellTimeoutMs } : {}),
    secrets: values['login-token'] ? [values['login-token']] : [],
    log,
  })
  const forbidden = defaultForbiddenDirs({ ownDataDirs: [stateDir, tokenFile, auditDir] })
  for (const dir of allowedDirs) {
    if (!(await canAllowDir(dir, forbidden))) log(`warning: allowed dir ${dir} is inside a forbidden dir; nothing in it is reachable`)
  }
  log(`confirm=${mode} allowed=${allowedDirs.join(',') || '(none)'} audit=${auditDir}`)
  node.on('status', info => {
    log(`status ${info.status}${info.reason ? ` (${info.reason})` : ''}${info.retryInMs ? ` retry in ${info.retryInMs} ms` : ''}`)
    if (info.reason === 'unpaired' || info.reason === 'revoked') {
      log(info.reason === 'revoked' ? '设备已被解绑（吊销），请重新配对' : '设备凭证无效，请重新配对')
      process.exitCode = 3
    }
  })
  node.on('audit', entry => log(`audit ${entry.tool} ${entry.outcome} ${entry.summary}`))
  let stopping = false
  const shutdown = () => {
    if (stopping) return
    stopping = true
    void node.stop().then(() => process.exit(process.exitCode ?? 0))
  }
  process.on('SIGINT', shutdown)
  process.on('SIGTERM', shutdown)
  // Exit once the node gives up for good (unpaired / revoked).
  node.on('status', info => {
    if (info.status === 'offline' && (info.reason === 'unpaired' || info.reason === 'revoked')) setTimeout(() => process.exit(3), 50)
  })
}

main().catch(error => fail(error instanceof Error ? error.message : String(error)))
