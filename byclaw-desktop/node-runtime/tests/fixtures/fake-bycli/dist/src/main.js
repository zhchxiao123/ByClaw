#!/usr/bin/env node
// Fake `bycli`: `browser <session> <sub…>` against tabs kept in the state file; logs every call.
import fs from 'node:fs'
import zlib from 'node:zlib'
import { log, read, write } from './state.js'

const argv = process.argv.slice(2)
log('calls.jsonl', argv)
const state = read()
state.tabs ??= {}
const out = value => process.stdout.write(`${typeof value === 'string' ? value : JSON.stringify(value, null, 2)}\n`)

function crc32(buf) {
  let c, crc = 0xffffffff
  for (const b of buf) { c = (crc ^ b) & 0xff; for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1; crc = c ^ (crc >>> 8) }
  return (crc ^ 0xffffffff) >>> 0
}
function chunk(type, body) {
  const len = Buffer.alloc(4); len.writeUInt32BE(body.length)
  const tb = Buffer.concat([Buffer.from(type, 'latin1'), body])
  const crc = Buffer.alloc(4); crc.writeUInt32BE(crc32(tb))
  return Buffer.concat([len, tb, crc])
}
function png(width, height) {
  const raw = Buffer.alloc((width * 3 + 1) * height)
  for (let y = 0; y < height; y++) for (let x = 0; x < width; x++) raw[y * (width * 3 + 1) + 1 + x * 3] = (x * 7 + y * 3) & 0xff
  const ihdr = Buffer.alloc(13); ihdr.writeUInt32BE(width, 0); ihdr.writeUInt32BE(height, 4); ihdr[8] = 8; ihdr[9] = 2
  return Buffer.concat([Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]), chunk('IHDR', ihdr), chunk('IDAT', zlib.deflateSync(raw)), chunk('IEND', Buffer.alloc(0))])
}

if (argv[0] !== 'browser') { out({ fake: true, argv }); process.exit(0) }
const [, session, sub, ...rest] = argv
const second = rest[0]
const focused = state.focused ?? { url: 'https://example.com/', title: 'Example Domain' }
if (sub === 'get' && second === 'url') {
  state.tabs[session] ??= 'about:blank'
  write(state)
  out(state.tabs[session])
}
else if (sub === 'bind') {
  state.binds = (state.binds ?? 0) + 1
  const tab = state.binds > 1 && state.focusedLater ? state.focusedLater : focused
  state.tabs[session] = tab.url
  write(state)
  out({ session, url: tab.url, title: tab.title, page: 'PAGE1' })
}
else if (sub === 'unbind') { delete state.tabs[session]; write(state); out({ unbound: true, session }) }
else if (sub === 'open') { state.tabs[session] = rest[0]; write(state); out({ url: rest[0], page: 'PAGE2' }) }
else if (sub === 'screenshot') {
  const file = rest.find(a => a.endsWith('.png'))
  const { width = 40, height = 30 } = state.shot ?? {}
  fs.writeFileSync(file, png(width, height))
  out(`Screenshot saved to: ${file}`)
}
else if (sub === 'click' && state.failClick) {
  out({ error: { code: 'not_found', message: `ref=${rest[0]} not found in DOM` } })
  process.stderr.write(`✖  [not_found] ref=${rest[0]} not found in DOM\n`)
  process.exit(1)
}
else if (sub === 'wait' && second === 'time') setTimeout(() => out('waited'), Number(rest[1]) * 1000)
else out({ ok: true, session, sub, args: rest })
