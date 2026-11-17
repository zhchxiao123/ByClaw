/** Sessions a user wrote as JSONL files move into the database once, unchanged. */
import { mkdtemp, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { afterEach, expect, it } from 'vitest'
import { Context } from '@deepseek-ai/cordis'
import SessionStore, { SessionId } from '@deepseek-ai/dsh-session'
import * as Jsonl from '@deepseek-ai/dsh-session-persistence-jsonl'
import { Config, importSessions, mount } from '../../src/session/index.ts'
import { meta, oneTurnLog } from './contract/contract.ts'
import { createTestDatabase } from '../pglite-pool.ts'

const cleanups: Array<() => Promise<unknown>> = []
afterEach(async () => { for (const cleanup of cleanups.splice(0).reverse()) await cleanup() })

async function jsonlAt(root: string) {
  const ctx = new Context()
  cleanups.push(async () => ctx.dispose?.())
  await ctx.plugin(SessionStore)
  await ctx.plugin(Jsonl, { root })
  return ctx.sessionPersistence
}

async function database(address: string, db: Awaited<ReturnType<typeof createTestDatabase>>) {
  const ctx = new Context()
  cleanups.push(async () => ctx.dispose?.())
  await ctx.plugin(SessionStore)
  await ctx.plugin({ name: 'db', apply: (inner: Context) => mount(inner, Config({ connectionString: address, userCode: 'u1' }), () => db.pool()) })
  return ctx.sessionPersistence
}

it('copies every JSONL session the database lacks, and only those, with header and events intact', async () => {
  const root = await mkdtemp(join(tmpdir(), 'jsonl-import-'))
  cleanups.push(() => rm(root, { recursive: true, force: true }))
  const files = await jsonlAt(root)
  for (const id of ['11351138', 'child-1']) {
    const handle = await files.create(meta(id, `/by/.sessions/${id}`))
    await handle.append(oneTurnLog())
    await handle[Symbol.asyncDispose]()
  }
  await files.flush()

  const db = await createTestDatabase()
  const target = await database('pglite://import-sessions', db)
  expect(await importSessions(files, target)).toEqual({ imported: 2, present: 0, skippedFormat: 0, failed: [] })

  for (const id of ['11351138', 'child-1']) {
    const original = await files.open(SessionId(id), 'read')
    const copy = await target.open(SessionId(id), 'read')
    expect(copy.header).toEqual(original.header)
    expect((await copy.read()).events).toEqual((await original.read()).events)
    await original[Symbol.asyncDispose]()
    await copy[Symbol.asyncDispose]()
  }
  // Running again changes nothing; a session continued in the database is not overwritten.
  const continued = await target.open(SessionId('11351138'), 'write')
  const before = (await continued.read()).events.length
  await continued[Symbol.asyncDispose]()
  expect(await importSessions(files, target)).toEqual({ imported: 0, present: 2, skippedFormat: 0, failed: [] })
  const again = await target.open(SessionId('11351138'), 'read')
  expect((await again.read()).events.length).toBe(before)
  await again[Symbol.asyncDispose]()
})

it('copies a session that has no events yet', async () => {
  const root = await mkdtemp(join(tmpdir(), 'jsonl-import-empty-'))
  cleanups.push(() => rm(root, { recursive: true, force: true }))
  const files = await jsonlAt(root)
  const handle = await files.create(meta('empty-1', '/by/.sessions/empty-1'))
  await handle.flush()
  await handle[Symbol.asyncDispose]()
  await files.flush()

  const db = await createTestDatabase()
  const target = await database('pglite://import-empty-session', db)
  expect(await importSessions(files, target)).toEqual({ imported: 1, present: 0, skippedFormat: 0, failed: [] })
  expect(await target.stat(SessionId('empty-1'))).not.toBeUndefined()
  // A second run finds it stored.
  expect(await importSessions(files, target)).toEqual({ imported: 0, present: 1, skippedFormat: 0, failed: [] })
})
