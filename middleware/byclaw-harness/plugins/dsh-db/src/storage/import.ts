/**
 * Copy the storage units a user's host kept as `@deepseek-ai/dsh-storage-json` files into the
 * database, for a user who moves to a stateless host. It runs where the files are (an admin
 * migration), without the plugins that declare the units, so it reads the layouts as files:
 * - `<unit>.json` = `{ unit: { name, version }, global, tables: { <table>: { <key>: value } } }`:
 *   a `single` unit, or the legacy form of a `per-record` one; its records take the unit's version;
 * - `<unit>/<table>/<key>.json` and `<unit>/global.json`, each `{ version, record }`: a
 *   `per-record` unit; when both forms exist the tree is the state, as storage-json reads it.
 * The database backend then opens these rows as it opens its own: a `single` unit must carry the
 * descriptor's version, a `per-record` unit is restamped and its records keep their versions.
 * A unit the database already holds is left alone, so the copy can run again; files are not changed.
 * @module @byclaw/dsh-db/storage/import
 */
import { readdir, readFile } from 'node:fs/promises'
import { join } from 'node:path'
import { UNIT_NAME_RE } from '@deepseek-ai/dsh-storage'
import { transaction, type SqlPool } from '../pool.ts'
import { ensureSchema } from './store.ts'

export interface StorageImportSummary {
  /** Units written to the database. */
  imported: string[]
  /** Units the database already held. */
  present: string[]
  /** Units left out, with the reason. */
  skipped: Array<{ unit: string; reason: string }>
}

interface UnitRows {
  version: number
  global: string | null
  records: Array<{ table: string; key: string; version: number; value: string }>
}

const isObject = (value: unknown): value is Record<string, unknown> => typeof value === 'object' && value !== null && !Array.isArray(value)

const isDocumentName = (name: string): boolean => !name.startsWith('.') && name.endsWith('.json')

async function entriesOf(dir: string) {
  try { return await readdir(dir, { withFileTypes: true }) } catch (error) {
    if ((error as NodeJS.ErrnoException).code === 'ENOENT') return []
    throw error
  }
}

/** A whole-unit document. */
async function wholeUnit(path: string, unit: string): Promise<UnitRows> {
  const document = JSON.parse(await readFile(path, 'utf8')) as unknown
  if (!isObject(document) || !isObject(document['unit']) || document['unit']['name'] !== unit) throw new Error('not a storage-json unit document of this name')
  const version = document['unit']['version']
  if (typeof version !== 'number' || !Number.isInteger(version)) throw new Error('unit version missing')
  const records: UnitRows['records'] = []
  if (isObject(document['tables'])) {
    for (const [table, rows] of Object.entries(document['tables'])) {
      if (!isObject(rows)) continue
      for (const [key, value] of Object.entries(rows)) records.push({ table, key, version, value: JSON.stringify(value) })
    }
  }
  const global = document['global'] === undefined || document['global'] === null ? null : JSON.stringify(document['global'])
  return { version, global, records }
}

/** One `{ version, record }` document. */
async function recordDocument(path: string): Promise<{ version: number; value: string }> {
  const document = JSON.parse(await readFile(path, 'utf8')) as unknown
  if (!isObject(document) || typeof document['version'] !== 'number' || !('record' in document)) throw new Error(`${path} is not a record document`)
  return { version: document['version'], value: JSON.stringify(document['record']) }
}

/** A per-record tree. Its unit version is restamped when the unit is opened; the highest record version stands in. */
async function recordTree(dir: string): Promise<UnitRows> {
  const records: UnitRows['records'] = []
  let global: string | null = null
  let version = 0
  for (const entry of await entriesOf(dir)) {
    if (entry.isDirectory() && !entry.name.startsWith('.')) {
      for (const file of await entriesOf(join(dir, entry.name))) {
        if (!file.isFile() || !isDocumentName(file.name)) continue
        const record = await recordDocument(join(dir, entry.name, file.name))
        records.push({ table: entry.name, key: file.name.slice(0, -'.json'.length), ...record })
        version = Math.max(version, record.version)
      }
    } else if (entry.isFile() && entry.name === 'global.json') {
      const record = await recordDocument(join(dir, entry.name))
      global = record.value
      version = Math.max(version, record.version)
    }
  }
  return { version, global, records }
}

/**
 * Copy every unit under a storage-json root that the database does not hold yet.
 * @param pool - the database.
 * @param schema - its schema (`byai`).
 * @param user - the context the rows belong to (the host's `userCode` for the plugins).
 * @param root - the storage-json root (`<dsh-home>/storages`).
 */
export async function importJsonStorage(pool: SqlPool, schema: string, user: string, root: string): Promise<StorageImportSummary> {
  const summary: StorageImportSummary = { imported: [], present: [], skipped: [] }
  const units = new Map<string, 'file' | 'tree'>()
  for (const entry of await entriesOf(root)) {
    if (entry.isDirectory() && UNIT_NAME_RE.test(entry.name)) units.set(entry.name, 'tree')
    else if (entry.isFile() && isDocumentName(entry.name)) {
      const unit = entry.name.slice(0, -'.json'.length)
      if (!UNIT_NAME_RE.test(unit)) summary.skipped.push({ unit, reason: 'not a unit name' })
      else if (!units.has(unit)) units.set(unit, 'file')
    }
  }
  if (units.size === 0) return summary
  await ensureSchema(pool, schema)
  for (const [unit, form] of [...units].sort(([a], [b]) => a.localeCompare(b))) {
    let rows: UnitRows
    try {
      rows = form === 'tree' ? await recordTree(join(root, unit)) : await wholeUnit(join(root, `${unit}.json`), unit)
    } catch (error) {
      summary.skipped.push({ unit, reason: (error as Error).message })
      continue
    }
    const written = await transaction(pool, async (tx) => {
      const { rows: existing } = await tx.query(`SELECT 1 FROM ${schema}.dsh_storage_unit WHERE user_code = $1 AND unit = $2 FOR UPDATE`, [user, unit])
      if (existing.length > 0) return false
      const now = Date.now()
      // Epoch 0: the first open claims epoch 1, as for a unit the backend created itself.
      await tx.query(
        `INSERT INTO ${schema}.dsh_storage_unit (user_code, unit, version, owner_epoch, global_value, updated_at) VALUES ($1, $2, $3, 0, $4, $5)`,
        [user, unit, rows.version, rows.global, now],
      )
      for (const record of rows.records) {
        await tx.query(
          `INSERT INTO ${schema}.dsh_storage_record (user_code, unit, table_name, record_key, version, value, updated_at) VALUES ($1, $2, $3, $4, $5, $6, $7)`,
          [user, unit, record.table, record.key, record.version, record.value, now],
        )
      }
      return true
    })
    ;(written ? summary.imported : summary.present).push(unit)
  }
  return summary
}
