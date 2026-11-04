import fs from 'node:fs'
import net from 'node:net'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

/** The fake `@sovovs/bycli` package (tests/fixtures/fake-bycli). */
export const FAKE_BYCLI_DIR = path.join(path.dirname(fileURLToPath(import.meta.url)), 'fixtures', 'fake-bycli')

/** A free TCP port on 127.0.0.1. */
export function freePort(): Promise<number> {
  return new Promise((resolve, reject) => {
    const server = net.createServer()
    server.once('error', reject)
    server.listen(0, '127.0.0.1', () => {
      const { port } = server.address() as net.AddressInfo
      server.close(() => resolve(port))
    })
  })
}

/** State file + logs of one fake byCLI instance. */
export class FakeBycliState {
  readonly file: string
  constructor(readonly dir: string, initial: Record<string, unknown> = {}) {
    this.file = path.join(dir, 'fake-bycli-state.json')
    this.write(initial)
  }

  read(): Record<string, any> {
    return JSON.parse(fs.readFileSync(this.file, 'utf8')) as Record<string, any>
  }

  write(state: Record<string, unknown>): void {
    fs.writeFileSync(this.file, JSON.stringify(state))
  }

  patch(update: Record<string, unknown>): void {
    this.write({ ...this.read(), ...update })
  }

  private lines(name: string): any[] {
    try {
      return fs.readFileSync(path.join(this.dir, name), 'utf8').split('\n').filter(Boolean).map(l => JSON.parse(l))
    }
    catch {
      return []
    }
  }

  /** argv of every `bycli` call. */
  calls(): string[][] {
    return this.lines('calls.jsonl') as string[][]
  }

  /** Daemon start / stop records. */
  daemons(): Array<{ pid: number, host?: string, port?: number, noProxy?: string, stopped?: boolean, token?: string | null }> {
    return this.lines('daemon.jsonl')
  }

  /** Env for the manager: points the fake at this state file. */
  env(extra: Record<string, string> = {}): NodeJS.ProcessEnv {
    return { ...process.env, FAKE_BYCLI_STATE: this.file, ...extra }
  }
}
