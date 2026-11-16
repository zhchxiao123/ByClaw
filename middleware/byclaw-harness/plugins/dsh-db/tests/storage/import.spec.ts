/** Storage units a host kept as storage-json files read back the same from the database after the import. */
import { mkdir, mkdtemp, readdir, rm, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { afterEach, expect, it } from 'vitest'
import type { KvUnitDescriptor } from '@deepseek-ai/dsh-storage'
import { JsonStorageBackend } from '@deepseek-ai/dsh-storage-json'
import { Config, createBackend, importJsonStorage } from '../../src/storage/index.ts'
import { createTestDatabase, type TestDatabase } from '../pglite-pool.ts'

const SINGLE: KvUnitDescriptor = { name: 'workspace', version: 2, tables: ['pins', 'roots'], hasGlobal: true }
const PER_RECORD: KvUnitDescriptor = { name: 'projection', version: 7, tables: ['sessions'], hasGlobal: true, layout: 'per-record', compatibleVersions: [6] }

const cleanups: Array<() => Promise<unknown>> = []
afterEach(async () => { for (const cleanup of cleanups.splice(0).reverse()) await cleanup() })

let databases = 0
async function dbBackend(database: TestDatabase, address: string, userCode = 'ctx-1') {
  return createBackend(new Config({ connectionString: address, userCode }), () => database.pool())
}

async function filesRoot() {
  const root = await mkdtemp(join(tmpdir(), 'storage-import-'))
  cleanups.push(() => rm(root, { recursive: true, force: true }))
  const files = new JsonStorageBackend(root)
  const single = await files.kv.open(SINGLE)
  await single.putRecord('pins', 'a', { path: '/by/a', n: 1 })
  await single.putRecord('roots', 'b', { s: 'nul \u0000 kept' })
  await single.setGlobal({ selected: 'a' })
  await single.close()
  const perRecord = await files.kv.open(PER_RECORD)
  await perRecord.putRecord('sessions', 's1', { title: 'one' })
  await perRecord.putRecord('sessions', 's2', { title: 'two' })
  await perRecord.setGlobal({ cursor: 3 })
  await perRecord.close()
  await files.close()
  return root
}

async function read(backend: Awaited<ReturnType<typeof dbBackend>>, descriptor: KvUnitDescriptor) {
  const unit = await backend.kv.open(descriptor)
  try { return await unit.loadAll() } finally { await unit.close() }
}

it('copies single and per-record units so the database backend reads what storage-json read', async () => {
  const root = await filesRoot()
  const database = await createTestDatabase()
  const address = `pglite://storage-import-${++databases}`
  const files = new JsonStorageBackend(root)
  const expected = { single: await read(files as never, SINGLE), perRecord: await read(files as never, PER_RECORD) }
  await files.close()

  expect(await importJsonStorage(database.pool(), 'byai', 'ctx-1', root)).toEqual({ imported: ['projection', 'workspace'], present: [], skipped: [] })
  const backend = await dbBackend(database, address)
  expect(await read(backend, SINGLE)).toEqual(expected.single)
  expect(await read(backend, PER_RECORD)).toEqual(expected.perRecord)
  // Another context's rows stay empty.
  expect((await read(await dbBackend(database, address, 'ctx-2'), SINGLE)).tables['pins']).toEqual({})

  // Running again leaves the units, including what was written since, alone.
  const unit = await backend.kv.open(SINGLE)
  await unit.putRecord('pins', 'c', { written: 'after' })
  await unit.close()
  expect(await importJsonStorage(database.pool(), 'byai', 'ctx-1', root)).toEqual({ imported: [], present: ['projection', 'workspace'], skipped: [] })
  expect((await read(backend, SINGLE)).tables['pins']).toEqual({ ...expected.single.tables['pins'], c: { written: 'after' } })
  // The files are not touched.
  expect((await readdir(root)).sort()).toEqual(['projection', 'workspace.json'])
})

it('reports what it cannot read and imports nothing from an absent root', async () => {
  const database = await createTestDatabase()
  expect(await importJsonStorage(database.pool(), 'byai', 'ctx-1', join(tmpdir(), 'no-such-storage-root'))).toEqual({ imported: [], present: [], skipped: [] })
  const root = await mkdtemp(join(tmpdir(), 'storage-import-bad-'))
  cleanups.push(() => rm(root, { recursive: true, force: true }))
  await writeFile(join(root, 'broken.json'), '{')
  await writeFile(join(root, 'Bad-Name.json'), '{}')
  await writeFile(join(root, '.workspace.1234.tmp'), '{}')
  await mkdir(join(root, 'tree', 'rows'), { recursive: true })
  await writeFile(join(root, 'tree', 'rows', 'k.json'), JSON.stringify({ version: 1, record: { ok: true } }))
  await writeFile(join(root, 'tree', 'rows', '.k.tmp'), 'partial')
  const summary = await importJsonStorage(database.pool(), 'byai', 'ctx-1', root)
  expect(summary.imported).toEqual(['tree'])
  expect(summary.skipped.map(entry => entry.unit).sort()).toEqual(['Bad-Name', 'broken'])
})
