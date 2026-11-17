/**
 * Copy a user's sessions from one `SessionPersistence` to another: the JSONL files a user wrote
 * before the host moved them to the database, into this backend. Sessions the target already holds
 * are left alone, so the copy can run again after an interruption. A session stored in an older
 * format, which the source refuses to read without the composition's migrations, is skipped and
 * counted rather than read wrongly.
 * @module @byclaw/dsh-db/session/import
 */
import type { SessionPersistence } from '@deepseek-ai/dsh-session-persistence'

export interface ImportSummary {
  /** Sessions written to the target. */
  imported: number
  /** Sessions the target already held. */
  present: number
  /** Sessions in a format the source needs migrations to read. */
  skippedFormat: number
  /** Sessions that failed to copy, with the reason. */
  failed: Array<{ id: string; reason: string }>
}

/**
 * Copy every session of `from` that `to` lacks.
 * @param from - the source persistence (e.g. JSONL over `<dsh-home>/sessions`).
 * @param to - the target persistence.
 * @returns what happened to each session.
 */
export async function importSessions(from: SessionPersistence, to: SessionPersistence): Promise<ImportSummary> {
  const summary: ImportSummary = { imported: 0, present: 0, skippedFormat: 0, failed: [] }
  for (const snapshot of await from.list()) {
    const id = snapshot.header.id
    if (snapshot.formatStatus === 'migration-required') {
      summary.skippedFormat++
      continue
    }
    try {
      if (await to.stat(id) !== undefined) {
        summary.present++
        continue
      }
      const source = await from.open(id, 'read')
      try {
        const { events } = await source.read()
        const target = await to.create(source.header, source.inheritedEventCount === 0 ? {} : { inheritedEventCount: source.inheritedEventCount })
        try {
          // A session without events exists once its header is flushed.
          if (events.length > 0) await target.append(events)
          else await target.flush()
        } finally {
          await target[Symbol.asyncDispose]()
        }
      } finally {
        await source[Symbol.asyncDispose]()
      }
      summary.imported++
    } catch (error) {
      summary.failed.push({ id: String(id), reason: (error as Error).message })
    }
  }
  await to.flush()
  return summary
}
