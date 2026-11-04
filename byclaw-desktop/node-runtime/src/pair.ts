/** Device pairing against the ByClaw BE (`POST /byaiService/device/pair`, login token in `Beyond-Token`). */

export interface PairRequest {
  /** BE or web origin, e.g. `https://byclaw.example.com` or `http://host:8086`; `/byaiService` is appended unless present. */
  baseUrl: string
  /** The web login JWT (byclaw-fe keeps it in localStorage `Beyond-Token`). */
  loginToken: string
  name: string
  platform: string
  appVersion: string
  fetch?: typeof fetch
}

export interface PairResult {
  deviceId: string
  deviceToken: string
}

export class PairError extends Error {}

/** The pair endpoint for a base URL. */
export function pairUrl(baseUrl: string): string {
  const base = baseUrl.replace(/\/+$/, '')
  return `${/\/byaiService$/.test(base) ? base : `${base}/byaiService`}/device/pair`
}

/** Pair this device; the returned token is long-lived and must be stored privately. */
export async function pairDevice(request: PairRequest): Promise<PairResult> {
  const doFetch = request.fetch ?? fetch
  const response = await doFetch(pairUrl(request.baseUrl), {
    method: 'POST',
    headers: { 'content-type': 'application/json', 'Beyond-Token': request.loginToken },
    body: JSON.stringify({ name: request.name, platform: request.platform, appVersion: request.appVersion }),
  })
  const text = await response.text()
  if (!response.ok) throw new PairError(`配对失败：HTTP ${response.status} ${text.slice(0, 200)}`)
  let body: { code?: unknown, msg?: unknown, data?: { deviceId?: unknown, deviceToken?: unknown } }
  try {
    body = JSON.parse(text)
  }
  catch {
    throw new PairError(`配对失败：响应不是 JSON：${text.slice(0, 200)}`)
  }
  if (body.code !== 0 && body.code !== '0') throw new PairError(`配对失败：${typeof body.msg === 'string' ? body.msg : JSON.stringify(body).slice(0, 200)}`)
  const deviceId = body.data?.deviceId
  const deviceToken = body.data?.deviceToken
  if (typeof deviceId !== 'string' || typeof deviceToken !== 'string' || deviceToken === '') throw new PairError('配对失败：响应缺少 deviceId 或 deviceToken')
  return { deviceId, deviceToken }
}

/** Relay URL for a ByClaw web URL: same origin, ws(s), path `/device-relay/node`. */
export function relayUrlFor(webUrl: string): string {
  const url = new URL(webUrl)
  const scheme = url.protocol === 'https:' ? 'wss:' : 'ws:'
  return `${scheme}//${url.host}/device-relay/node`
}
