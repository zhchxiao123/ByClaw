import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { describe, expect, it } from 'vitest'
import { TokenStore, type Encryptor } from '../src/token-store.js'

const fake = (available = true): Encryptor => ({
  isEncryptionAvailable: () => available,
  encryptString: plain => Buffer.from(`enc:${Buffer.from(plain).toString('base64')}`),
  decryptString: buf => Buffer.from(buf.toString().replace(/^enc:/, ''), 'base64').toString(),
})

describe('TokenStore', () => {
  it('stores the token encrypted, 0600, and clears it', () => {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'byclaw-token-'))
    const store = new TokenStore(dir, fake())
    expect(store.load()).toBeUndefined()
    store.save('header.payload.sig')
    const raw = fs.readFileSync(path.join(dir, 'device-token.bin'), 'utf8')
    expect(raw).not.toContain('header.payload.sig')
    expect((fs.statSync(path.join(dir, 'device-token.bin')).mode & 0o777).toString(8)).toBe('600')
    expect(store.load()).toBe('header.payload.sig')
    store.clear()
    expect(store.load()).toBeUndefined()
    fs.rmSync(dir, { recursive: true })
  })

  it('refuses to write when OS encryption is unavailable', () => {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'byclaw-token-'))
    expect(() => new TokenStore(dir, fake(false)).save('t')).toThrow('不可用')
    expect(fs.existsSync(path.join(dir, 'device-token.bin'))).toBe(false)
    fs.rmSync(dir, { recursive: true })
  })
})
