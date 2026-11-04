import type { AddressInfo } from 'node:net'
import WebSocket, { WebSocketServer } from 'ws'
import { waitFor } from './helpers.js'

/** One connection seen by the fake relay. */
export interface Conn {
  ws: WebSocket
  auth: string | undefined
  frames: any[]
  at: number
}

/** A real ws server on an ephemeral port speaking the relay side of the node protocol. */
export class FakeRelay {
  readonly conns: Conn[] = []
  private server!: WebSocketServer
  /** HTTP status to refuse the upgrade with, if set. */
  refuseWith: number | undefined
  /** Close code to close every new connection with immediately, if set. */
  closeOnConnect: number | undefined

  async start(): Promise<string> {
    this.server = new WebSocketServer({
      port: 0,
      host: '127.0.0.1',
      verifyClient: (_info, cb) => this.refuseWith ? cb(false, this.refuseWith) : cb(true),
    })
    await new Promise<void>(resolve => this.server.once('listening', () => resolve()))
    this.server.on('connection', (ws, req) => {
      const conn: Conn = { ws, auth: req.headers.authorization, frames: [], at: Date.now() }
      this.conns.push(conn)
      ws.on('message', raw => conn.frames.push(JSON.parse(raw.toString())))
      if (this.closeOnConnect) ws.close(this.closeOnConnect)
    })
    return `ws://127.0.0.1:${(this.server.address() as AddressInfo).port}/device-relay/node`
  }

  get last(): Conn {
    return this.conns.at(-1) as Conn
  }

  invoke(id: string, tool: string, args: Record<string, unknown>, session?: string): void {
    this.last.ws.send(JSON.stringify({ t: 'invoke', id, tool, args, ...(session ? { session } : {}) }))
  }

  cancel(id: string): void {
    this.last.ws.send(JSON.stringify({ t: 'cancel', id }))
  }

  framesFor(id: string, conn = this.last): any[] {
    return conn.frames.filter(f => f.id === id)
  }

  result(id: string, conn = this.last): Promise<any> {
    return waitFor(() => conn.frames.find(f => f.t === 'result' && f.id === id), 10_000)
  }

  async stop(): Promise<void> {
    for (const c of this.server.clients) c.terminate()
    await new Promise<void>(resolve => this.server.close(() => resolve()))
  }
}
