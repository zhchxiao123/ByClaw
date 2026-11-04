/** Local audit log: one JSONL line per device call, a file per day, kept 30 days. Never file contents. */

import fs from 'node:fs'
import path from 'node:path'

export const AUDIT_RETENTION_DAYS = 30

export interface AuditEntry {
  ts: string
  id: string
  session?: string
  tool: string
  /** Command / action + path, truncated. */
  summary: string
  cwd?: string
  /** User decision or how it was granted: allow_once, allow_session, allow_always, deny, session, always. */
  decision?: string
  outcome: 'ok' | 'denied' | 'not_allowed' | 'timeout' | 'failed' | 'cancelled'
  exitCode?: number | null
  /** Our own short reason (never command output or file contents). */
  reason?: string
  ms: number
}

/** Cut a string to `max` characters with an ellipsis. */
export function clip(text: string, max: number): string {
  return text.length > max ? `${text.slice(0, max)}…` : text
}

const FILE_RE = /^audit-(\d{4}-\d{2}-\d{2})\.jsonl$/

function day(date: Date): string {
  return date.toISOString().slice(0, 10)
}

export class AuditLog {
  constructor(readonly dir: string, private readonly now: () => Date = () => new Date()) {
    fs.mkdirSync(dir, { recursive: true, mode: 0o700 })
  }

  /** Path of today's file. */
  currentFile(): string {
    return path.join(this.dir, `audit-${day(this.now())}.jsonl`)
  }

  append(entry: Omit<AuditEntry, 'ts'> & { ts?: string }): void {
    const line = JSON.stringify({ ts: entry.ts ?? this.now().toISOString(), ...entry })
    fs.appendFileSync(this.currentFile(), `${line}\n`, { mode: 0o600 })
  }

  /** Delete day files older than the retention window. @returns deleted file names. */
  prune(retentionDays = AUDIT_RETENTION_DAYS): string[] {
    const cutoff = day(new Date(this.now().getTime() - retentionDays * 86_400_000))
    const removed: string[] = []
    for (const name of fs.readdirSync(this.dir)) {
      const match = FILE_RE.exec(name)
      if (match && (match[1] as string) < cutoff) {
        fs.rmSync(path.join(this.dir, name), { force: true })
        removed.push(name)
      }
    }
    return removed
  }

  /** The latest `limit` entries, newest last. */
  recent(limit = 200): AuditEntry[] {
    const files = fs.readdirSync(this.dir).filter(n => FILE_RE.test(n)).sort()
    const out: AuditEntry[] = []
    for (let i = files.length - 1; i >= 0 && out.length < limit; i--) {
      const lines = fs.readFileSync(path.join(this.dir, files[i] as string), 'utf8').split('\n').filter(Boolean)
      for (let j = lines.length - 1; j >= 0 && out.length < limit; j--) {
        try {
          out.push(JSON.parse(lines[j] as string) as AuditEntry)
        }
        catch {
          // Skip a torn line.
        }
      }
    }
    return out.reverse()
  }
}
