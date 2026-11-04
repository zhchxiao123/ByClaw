/** Frames of the device node protocol (ByClaw desktop-node protocol.md §3), device side. */

/**
 * Capability names: P1 `shell`, `files.read`, `files.write`; P2a `browser` (enabled here, but only
 * advertised while the local byCLI daemon has the user's Chrome extension connected).
 */
export const CAPABILITIES = ['shell', 'files.read', 'files.write', 'browser'] as const
export type Capability = typeof CAPABILITIES[number]

/** Result codes a device may report. */
export type ResultCode = 'denied' | 'not_allowed' | 'timeout' | 'failed'

/** An image in a result frame (P2a screenshots). All images of one frame stay within 2 MB of base64. */
export interface ResultImage {
  mime: 'image/png' | 'image/jpeg'
  /** Base64, no data: prefix. */
  data: string
  width?: number
  height?: number
}

/** Cap on the base64 of all images in one result frame (protocol §3 "图片"). */
export const MAX_IMAGES_BASE64 = 2 * 1024 * 1024

/** Device -> relay. */
export type NodeFrame =
  | { t: 'register', name: string, platform: string, appVersion: string, capabilities: string[] }
  | { t: 'capabilities', capabilities: string[] }
  | { t: 'heartbeat' }
  | { t: 'ack', id: string }
  | { t: 'human_wait', id: string, prompt: string }
  | { t: 'result', id: string, ok: boolean, output?: string, error?: string, code?: ResultCode, images?: ResultImage[] }

/** Relay -> device. */
export type RelayFrame =
  | { t: 'invoke', id: string, tool: string, args: Record<string, unknown>, session?: string }
  | { t: 'cancel', id: string }

/** Close codes the relay uses for credentials it will never accept again. */
export const CLOSE_UNAUTHORIZED = 4401
export const CLOSE_REVOKED = 4403

/** Parse one relay text frame; undefined for anything that is not a well-formed relay frame. */
export function parseRelayFrame(raw: string): RelayFrame | undefined {
  let value: unknown
  try {
    value = JSON.parse(raw)
  }
  catch {
    return undefined
  }
  if (typeof value !== 'object' || value === null) return undefined
  const frame = value as Record<string, unknown>
  if (typeof frame.id !== 'string' || frame.id === '') return undefined
  if (frame.t === 'cancel') return { t: 'cancel', id: frame.id }
  if (frame.t !== 'invoke' || typeof frame.tool !== 'string') return undefined
  const args = typeof frame.args === 'object' && frame.args !== null && !Array.isArray(frame.args)
    ? frame.args as Record<string, unknown>
    : {}
  return {
    t: 'invoke',
    id: frame.id,
    tool: frame.tool,
    args,
    ...(typeof frame.session === 'string' && frame.session !== '' ? { session: frame.session } : {}),
  }
}
