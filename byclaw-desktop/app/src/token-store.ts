/**
 * The device token, encrypted with Electron safeStorage (on macOS the key lives in the login
 * keychain) in `<userData>/device-token.bin`. Never handed to the web page.
 */

import fs from 'node:fs'
import path from 'node:path'

/** The subset of Electron's safeStorage used here (injectable for tests). */
export interface Encryptor {
  isEncryptionAvailable(): boolean
  encryptString(plain: string): Buffer
  decryptString(encrypted: Buffer): string
}

export class TokenStore {
  private readonly file: string
  constructor(dir: string, private readonly crypto: Encryptor) {
    this.file = path.join(dir, 'device-token.bin')
  }

  load(): string | undefined {
    try {
      const value = this.crypto.decryptString(fs.readFileSync(this.file))
      return value === '' ? undefined : value
    }
    catch {
      return undefined
    }
  }

  /** @throws when OS encryption is unavailable: the token is never written in the clear. */
  save(token: string): void {
    if (!this.crypto.isEncryptionAvailable()) throw new Error('系统加密存储不可用，无法保存设备凭证')
    fs.mkdirSync(path.dirname(this.file), { recursive: true })
    fs.writeFileSync(this.file, this.crypto.encryptString(token), { mode: 0o600 })
  }

  clear(): void {
    fs.rmSync(this.file, { force: true })
  }
}
