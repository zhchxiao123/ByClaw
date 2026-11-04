import { describe, expect, it } from 'vitest'
import { accountChanged, deviceUserCode, loginUserCode } from '../src/account.ts'

const jwt = (claims: object) => `h.${Buffer.from(JSON.stringify(claims)).toString('base64url')}.s`

describe('account', () => {
  it('reads the user of a login token and of a device token', () => {
    expect(loginUserCode(jwt({ userCode: '0027012991', userName: '杜甫' }))).toBe('0027012991')
    expect(deviceUserCode(jwt({ sub: '0027016840', did: 'dev_x', purpose: 'device-node' }))).toBe('0027016840')
  })

  it('tolerates missing or malformed tokens', () => {
    expect(loginUserCode(undefined)).toBeUndefined()
    expect(loginUserCode('not-a-jwt')).toBeUndefined()
    expect(loginUserCode('a.%%%.b')).toBeUndefined()
    expect(deviceUserCode(jwt({ did: 'dev_x' }))).toBeUndefined()
  })

  it('reports a change only when both sides are known and differ', () => {
    expect(accountChanged('0027012991', '0027016840')).toBe(true)
    expect(accountChanged('0027012991', '0027012991')).toBe(false)
    expect(accountChanged(undefined, '0027016840')).toBe(false)
    expect(accountChanged('0027012991', undefined)).toBe(false)
  })
})
