#!/usr/bin/env node
/**
 * Move one user's state from a mounted shared host to the stores a stateless host reads, so the
 * user can be switched to the stateless pool without losing it. An admin runs it on a mounted host
 * (it reads the user's DSH home there), before switching the user:
 *
 *   node /workspace/scripts/migrate-to-stateless.mjs --user-code <code> [--context <key>]... [--home <dir>] [--env-from-pid <pid>|auto]
 *
 * For each of the user's contexts it copies, into the deployment database (DB_*):
 * - credential store and tool-environment SSH identities (`dsh_host_file`, encrypted with the service secret);
 * - session logs (`dsh_session`, `dsh_session_event`);
 * - storage units: workspace records, session roots, schedules and the rest (`dsh_storage_unit`, `dsh_storage_record`);
 * and image attachments into the user's own space through BE (`/by/.dsh/attachments`), with the
 * user's current login token from Redis.
 *
 * Nothing on the host is changed or deleted. What the database already holds is left alone, so a
 * failed run can be repeated. The exit code is 0 only when every step of every context succeeded:
 * switch the user only then.
 *
 * The user must not be served while it runs: it refuses a context any host still claims. Release
 * the user's shared context first, and switch right after it succeeds.
 *
 * Settings come from the environment, as the host gets them: DB_HOST, DB_PORT, DB_USER, DB_PASS,
 * DB_DATABASE, DB_SCHEMA; BYCLAW_CREDENTIAL_SECRET_FILE or BYCLAW_CREDENTIAL_SECRET; REDIS_*;
 * BYCLAW_TOOL_ENV_BE_BASE_URL (or BYAI_SERVICE_BASE_URL); BYCLAW_MULTI_HOST_USER_ROOT (homes are
 * <root>/<context>/dsh-home unless --home is given). `--env-from-pid` reads them from the host
 * process (`auto` finds it), whose environment the admin's shell does not have.
 */
import { readdir, readFile, stat } from 'node:fs/promises'
import { createRequire } from 'node:module'
import { join } from 'node:path'
import { pathToFileURL } from 'node:url'
import { DurableFiles } from './host-durable-files.mjs'
import {
  createHostRedis, currentUserToken, isValidUserCode, managedStorageKey, openHostFileStore, readSessionDatabase, readToolEnvBeConfig, sharedHostKey,
} from './byclaw-multi-host.mjs'

const log = (...args) => console.error('[migrate-to-stateless]', ...args)

/** Command-line options. @throws on unknown or malformed ones. */
export function parseArgs(argv) {
  const options = { contexts: [] }
  for (let i = 0; i < argv.length; i++) {
    const value = argv[i + 1]
    switch (argv[i]) {
      case '--user-code': options.userCode = value; i++; break
      case '--context': options.contexts.push(value); i++; break
      case '--home': options.home = value; i++; break
      case '--env-from-pid': options.envFromPid = value; i++; break
      default: throw new Error(`unknown option ${argv[i]}`)
    }
  }
  if (!isValidUserCode(options.userCode ?? '')) throw new Error('--user-code is required')
  for (const context of options.contexts) if (!isValidUserCode(context ?? '')) throw new Error(`--context ${context} is invalid`)
  if (options.home !== undefined && options.contexts.length !== 1) throw new Error('--home needs exactly one --context')
  return options
}

/** A process's environment as it started (`/proc/<pid>/environ`). */
export function parseEnviron(buffer) {
  const env = {}
  for (const entry of buffer.toString('utf8').split('\0')) {
    const at = entry.indexOf('=')
    if (at > 0) env[entry.slice(0, at)] = entry.slice(at + 1)
  }
  return env
}

async function hostPid() {
  for (const pid of await readdir('/proc')) {
    if (!/^\d+$/.test(pid) || Number(pid) === process.pid) continue
    const cmdline = await readFile(`/proc/${pid}/cmdline`, 'utf8').catch(() => '')
    if (cmdline.split('\0').some(arg => arg.endsWith('byclaw-multi-host.mjs'))) return pid
  }
  throw new Error('no byclaw-multi-host.mjs process found; pass --env-from-pid <pid>')
}

/** The user's contexts: the given ones, or every one the shared pool has served for the user. */
async function userContexts(pool, schema, userCode) {
  // The platform's member rows; payload is JSON text (json has no equality operator for DISTINCT).
  const { rows } = await pool.query(`SELECT payload FROM ${schema}.ss_sandbox_shared_member`)
  const contexts = new Set()
  for (const row of rows) {
    let payload
    try { payload = typeof row.payload === 'string' ? JSON.parse(row.payload) : row.payload } catch { continue }
    if (payload?.userCode === userCode && isValidUserCode(payload.contextKey ?? '')) contexts.add(payload.contextKey)
  }
  return [...contexts].sort()
}

/** Image objects of a local attachment store, by digest. */
export async function imageObjects(home) {
  const objects = join(home, 'attachments', 'v1', 'objects')
  const found = []
  let buckets
  try { buckets = await readdir(objects) } catch (error) { if (error.code === 'ENOENT') return found; throw error }
  for (const bucket of buckets.sort()) {
    let names
    try { names = await readdir(join(objects, bucket)) } catch { continue }
    for (const sha of names.sort()) if (/^[a-f0-9]{64}$/.test(sha) && sha.startsWith(bucket)) found.push({ sha, path: join(objects, bucket, sha) })
  }
  return found
}

/**
 * Store image objects in the user's space where `@byclaw/dsh-attachment-be` looks for them.
 * @param be - { baseUrl, systemCode, token }.
 * @returns how many were stored.
 * @throws on the first failure.
 */
export async function uploadImages(objects, be, fetchImpl = fetch) {
  const { createHash } = await import('node:crypto')
  let stored = 0
  for (const { sha, path } of objects) {
    const data = await readFile(path)
    // A damaged local copy is not sent; the store would refuse it anyway.
    if (createHash('sha256').update(data).digest('hex') !== sha) throw new Error(`local image ${sha.slice(0, 12)} does not match its digest`)
    const form = new FormData()
    form.append('spaceType', 'USER')
    form.append('path', `/by/.dsh/attachments/${sha.slice(0, 2)}/${sha}`)
    form.append('contentType', 'application/octet-stream')
    form.append('file', new Blob([data], { type: 'application/octet-stream' }), sha)
    const headers = { 'Beyond-Token': be.token, ...be.systemCode ? { 'system-code': be.systemCode } : {} }
    const response = await fetchImpl(`${be.baseUrl.replace(/\/+$/, '')}/fs/operation/v1/files/put`, { method: 'POST', body: form, headers, signal: AbortSignal.timeout(30_000) })
    const text = await response.text()
    let envelope = {}
    try { envelope = JSON.parse(text) } catch { /* reported below */ }
    if (!response.ok || envelope.code !== 0) throw new Error(`storing image ${sha.slice(0, 12)} failed: HTTP ${response.status} ${String(envelope.msg ?? text.slice(0, 200))}`)
    stored++
  }
  return stored
}

async function importSessionLogs(load, sessionDb, context, home) {
  const dir = join(home, 'sessions')
  if (!await stat(dir).then(info => info.isDirectory(), () => false)) return { imported: 0, present: 0, skippedFormat: 0, failed: [] }
  const [{ Context }, sessionStore, jsonl, db] = await Promise.all([
    load('@deepseek-ai/cordis'), load('@deepseek-ai/dsh-session'),
    load('@deepseek-ai/dsh-session-persistence-jsonl'), load('@byclaw/dsh-db/session'),
  ])
  const source = new Context()
  const target = new Context()
  try {
    await source.plugin(sessionStore.default ?? sessionStore)
    await source.plugin(jsonl, { root: dir })
    await target.plugin(sessionStore.default ?? sessionStore)
    await target.plugin(db, { connectionString: sessionDb.url, schema: sessionDb.schema, userCode: context })
    return await db.importSessions(source.sessionPersistence, target.sessionPersistence)
  } finally {
    await Promise.allSettled([source.dispose?.(), target.dispose?.()])
  }
}

/** Migrate one context. Every step runs; the result says which failed. */
export async function migrateContext({ context, home, userCode, sessionDb, hostFiles, pool, load, redis, be, dsh }) {
  const result = { context, home, ok: true, steps: {} }
  const step = async (name, run) => {
    try { result.steps[name] = await run() } catch (error) {
      result.ok = false
      result.steps[name] = { error: error.message }
    }
  }
  const claimedBy = await redis.get(managedStorageKey(context)) ?? await redis.get(sharedHostKey(userCode))
  if (claimedBy) return { context, home, ok: false, error: `a host still serves this context (${claimedBy}); release it and keep the user out until the switch` }
  if (!await stat(home).then(info => info.isDirectory(), () => false)) return { context, home, ok: false, error: 'DSH home not found on this host' }

  await step('credentialsAndKeys', async () => {
    const files = new DurableFiles({ ...hostFiles, user: context, home, log })
    await files.restore()
    await files.close()
    if (files.stopped) throw new Error('stored rows of this context cannot be opened with this service secret')
    return { stored: [...files.stored.keys()].length }
  })
  await step('sessions', async () => {
    const summary = await importSessionLogs(load, sessionDb, context, home)
    if (summary.failed.length > 0 || summary.skippedFormat > 0) {
      throw new Error(`imported ${summary.imported}, present ${summary.present}, older format ${summary.skippedFormat}, failed ${summary.failed.map(f => `${f.id}: ${f.reason}`).join('; ')}`)
    }
    return summary
  })
  await step('storageUnits', async () => {
    const summary = await dsh.importJsonStorage(pool, sessionDb.schema, context, join(home, 'storages'))
    if (summary.skipped.length > 0) throw new Error(`imported ${summary.imported.join(',') || '-'}; unreadable: ${summary.skipped.map(s => `${s.unit} (${s.reason})`).join('; ')}`)
    return summary
  })
  await step('imageAttachments', async () => {
    const objects = await imageObjects(home)
    if (objects.length === 0) return { stored: 0 }
    const token = await currentUserToken(redis, userCode)
    if (token === undefined) throw new Error(`${objects.length} image(s) to copy, but the user has no login token in Redis; ask the user to log in, then run again`)
    return { stored: await uploadImages(objects, { ...be, token }) }
  })
  return result
}

async function main() {
  const options = parseArgs(process.argv.slice(2))
  const env = options.envFromPid === undefined ? process.env
    : { ...process.env, ...parseEnviron(await readFile(`/proc/${options.envFromPid === 'auto' ? await hostPid() : options.envFromPid}/environ`)) }
  const sessionDb = readSessionDatabase(env)
  const managedProfile = env.BYCLAW_MANAGED_PROFILE ?? '/opt/byclaw/dsh-managed/profiles/web'
  const require = createRequire(join(managedProfile, 'package.json'))
  const load = specifier => import(pathToFileURL(require.resolve(specifier)).href)
  const hostFiles = await openHostFileStore({ sessionDb, managedProfile }, env)
  if (hostFiles === undefined) throw new Error('BYCLAW_CREDENTIAL_SECRET_FILE or BYCLAW_CREDENTIAL_SECRET is required: credentials and SSH identities are stored encrypted with it')
  const be = readToolEnvBeConfig(env)
  const dsh = await load('@byclaw/dsh-db/storage')
  const { createPgPool } = await load('@byclaw/dsh-db')
  const pool = createPgPool({ connectionString: sessionDb.url, schema: sessionDb.schema, poolMax: 2, queryTimeoutMs: 30_000 })
  const redis = createHostRedis(require('ioredis'), env)
  redis.on('error', error => log('redis:', error.message))
  try {
    const contexts = options.contexts.length > 0 ? options.contexts : await userContexts(pool, sessionDb.schema, options.userCode)
    if (contexts.length === 0) throw new Error(`no shared context found for ${options.userCode}; pass --context`)
    const results = []
    for (const context of contexts) {
      const home = options.home ?? join(env.BYCLAW_MULTI_HOST_USER_ROOT ?? '/data/byclaw-contexts', context, 'dsh-home')
      log(`migrating ${context} from ${home}`)
      results.push(await migrateContext({ context, home, userCode: options.userCode, sessionDb, hostFiles, pool, load, redis, be, dsh }))
    }
    const ok = results.every(result => result.ok)
    console.log(JSON.stringify({ userCode: options.userCode, ok, contexts: results }, null, 2))
    log(ok ? 'done: switch the user to the stateless pool now' : 'NOT complete: do not switch the user; fix the errors above and run again')
    process.exitCode = ok ? 0 : 1
  } finally {
    await Promise.allSettled([pool.end(), hostFiles.end(), redis.quit()])
  }
}

if (import.meta.url === pathToFileURL(process.argv[1] ?? '').href) {
  main().catch((error) => {
    log(error.message)
    process.exitCode = 2
  })
}
