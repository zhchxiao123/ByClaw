/** Which ByClaw account a token belongs to, read from its JWT payload (not verified: only compared). */

function payload(token: string): Record<string, unknown> | undefined {
  const part = token.split('.')[1]
  if (!part) return undefined
  try {
    const value: unknown = JSON.parse(Buffer.from(part, 'base64url').toString('utf8'))
    return value !== null && typeof value === 'object' ? value as Record<string, unknown> : undefined
  }
  catch {
    return undefined
  }
}

function stringClaim(token: string | undefined, ...keys: string[]): string | undefined {
  const claims = token ? payload(token) : undefined
  for (const key of keys) {
    const value = claims?.[key]
    if (typeof value === 'string' && value !== '') return value
  }
  return undefined
}

/** The user code of the page's login token (byclaw-be puts `userCode` in it). */
export function loginUserCode(loginToken: string | undefined): string | undefined {
  return stringClaim(loginToken, 'userCode', 'sub')
}

/** The user a device token was issued to (`sub`, see the device protocol). */
export function deviceUserCode(deviceToken: string | undefined): string | undefined {
  return stringClaim(deviceToken, 'sub')
}

/** True when the page is logged in as someone other than the device's owner. */
export function accountChanged(loginUser: string | undefined, deviceUser: string | undefined): boolean {
  return loginUser !== undefined && deviceUser !== undefined && loginUser !== deviceUser
}
