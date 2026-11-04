// Fake byCLI daemon: GET /status reflects state.connected; records how it was started.
import http from 'node:http'
import { log, read } from './state.js'

const host = process.env.BYCLI_DAEMON_HOST || '127.0.0.1'
const port = Number(process.env.BYCLI_DAEMON_PORT || 19825)
if (read().crashDaemon) process.exit(7)
const server = http.createServer((req, res) => {
  if (req.method === 'GET' && req.url.startsWith('/status') && req.headers['x-bycli'] === '1') {
    res.writeHead(200, { 'content-type': 'application/json' })
    res.end(JSON.stringify({ ok: true, pid: process.pid, daemonVersion: 'fake', extensionConnected: read().connected === true, port }))
    return
  }
  res.writeHead(403)
  res.end('{"ok":false}')
})
server.listen(port, host, () => log('daemon.jsonl', { pid: process.pid, host, port, noProxy: process.env.NO_PROXY, token: process.env.BYCLAW_SECRET_PROBE ?? null }))
process.on('SIGTERM', () => {
  log('daemon.jsonl', { pid: process.pid, stopped: true })
  server.close(() => process.exit(0))
})
