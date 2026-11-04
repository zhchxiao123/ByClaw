import http from 'node:http'
import type { AddressInfo } from 'node:net'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { pairDevice, PairError, pairUrl, relayUrlFor } from '../src/pair.js'

let server: http.Server
let base: string
let seen: { url?: string, headers?: http.IncomingHttpHeaders, body?: any }
let reply: { status: number, body: unknown }

beforeEach(async () => {
  seen = {}
  reply = { status: 200, body: { code: 0, msg: 'success', data: { deviceId: 'dev_abc', deviceToken: 'tok.en.sig' } } }
  server = http.createServer((req, res) => {
    let raw = ''
    req.on('data', c => { raw += c })
    req.on('end', () => {
      seen = { url: req.url, headers: req.headers, body: JSON.parse(raw || '{}') }
      res.writeHead(reply.status, { 'content-type': 'application/json' })
      res.end(JSON.stringify(reply.body))
    })
  })
  await new Promise<void>(resolve => server.listen(0, '127.0.0.1', () => resolve()))
  base = `http://127.0.0.1:${(server.address() as AddressInfo).port}`
})
afterEach(async () => new Promise<void>(resolve => server.close(() => resolve())))

describe('pairDevice', () => {
  it('posts name/platform/appVersion with the Beyond-Token header', async () => {
    expect(await pairDevice({ baseUrl: base, loginToken: 'login.jwt', name: 'mac', platform: 'darwin', appVersion: '0.1.0' })).toEqual({ deviceId: 'dev_abc', deviceToken: 'tok.en.sig' })
    expect(seen.url).toBe('/byaiService/device/pair')
    expect(seen.headers?.['beyond-token']).toBe('login.jwt')
    expect(seen.body).toEqual({ name: 'mac', platform: 'darwin', appVersion: '0.1.0' })
  })

  it('omits Beyond-Token when relying on a session cookie', async () => {
    await pairDevice({ baseUrl: base, loginToken: '', name: 'mac', platform: 'darwin', appVersion: '0.1.0' })
    expect(seen.headers?.['beyond-token']).toBeUndefined()
  })

  it('surfaces the BE message on a business error and HTTP errors', async () => {
    reply = { status: 200, body: { code: 1, msg: '设备配对未启用', data: null } }
    await expect(pairDevice({ baseUrl: base, loginToken: 't', name: 'n', platform: 'p', appVersion: 'v' })).rejects.toThrow(new PairError('配对失败：设备配对未启用'))
    reply = { status: 401, body: {} }
    await expect(pairDevice({ baseUrl: base, loginToken: 't', name: 'n', platform: 'p', appVersion: 'v' })).rejects.toThrow('HTTP 401')
  })
})

describe('urls', () => {
  it('appends /byaiService once', () => {
    expect(pairUrl('http://h:8086')).toBe('http://h:8086/byaiService/device/pair')
    expect(pairUrl('http://h:8086/byaiService/')).toBe('http://h:8086/byaiService/device/pair')
  })

  it('derives the relay URL from the web URL origin', () => {
    expect(relayUrlFor('http://118.178.253.73:18080/beyond/')).toBe('ws://118.178.253.73:18080/device-relay/node')
    expect(relayUrlFor('https://byclaw.example.com/beyond/chat?x=1')).toBe('wss://byclaw.example.com/device-relay/node')
  })
})
