// Shared state of the fake byCLI: a JSON file named by FAKE_BYCLI_STATE.
import fs from 'node:fs'
import path from 'node:path'

export const file = process.env.FAKE_BYCLI_STATE
export function read() {
  try { return JSON.parse(fs.readFileSync(file, 'utf8')) } catch { return {} }
}
export function write(state) {
  fs.writeFileSync(`${file}.tmp`, JSON.stringify(state))
  fs.renameSync(`${file}.tmp`, file)
}
export function log(name, value) {
  fs.appendFileSync(path.join(path.dirname(file), name), `${JSON.stringify(value)}\n`)
}
