import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'

/** A fresh temp dir (realpath'd), removed by the returned cleanup. */
export function tempDir(prefix = 'byclaw-node-'): { dir: string, cleanup: () => void } {
  const dir = fs.realpathSync(fs.mkdtempSync(path.join(os.tmpdir(), prefix)))
  return { dir, cleanup: () => fs.rmSync(dir, { recursive: true, force: true }) }
}

export const sleep = (ms: number) => new Promise(resolve => setTimeout(resolve, ms))

/** Poll until `fn` returns truthy or time runs out. */
export async function waitFor<T>(fn: () => T | undefined | false | null, timeoutMs = 5000, stepMs = 20): Promise<T> {
  const deadline = Date.now() + timeoutMs
  for (;;) {
    const value = fn()
    if (value) return value
    if (Date.now() > deadline) throw new Error('waitFor timed out')
    await sleep(stepMs)
  }
}

/** True while `pid` runs. A zombie counts as dead (containers whose PID 1 never reaps leave them). */
export function isAlive(pid: number): boolean {
  try {
    process.kill(pid, 0)
  }
  catch {
    return false
  }
  try {
    const stat = fs.readFileSync(`/proc/${pid}/stat`, 'utf8')
    return stat.slice(stat.lastIndexOf(')') + 2, stat.lastIndexOf(')') + 3) !== 'Z'
  }
  catch {
    return true
  }
}
