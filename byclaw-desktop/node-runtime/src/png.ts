/**
 * Minimal PNG decode / box-filter downscale / encode for screenshots, with no native dependency.
 * Handles what Chrome produces: 8-bit, non-interlaced, grayscale / RGB / RGBA (+ grayscale-alpha).
 */

import zlib from 'node:zlib'

const SIGNATURE = Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a])

export interface RawImage {
  width: number
  height: number
  /** Samples per pixel: 1 gray, 2 gray+alpha, 3 RGB, 4 RGBA. */
  channels: 1 | 2 | 3 | 4
  pixels: Buffer
}

const CHANNELS: Record<number, RawImage['channels']> = { 0: 1, 4: 2, 2: 3, 6: 4 }
const COLOR_TYPE: Record<RawImage['channels'], number> = { 1: 0, 2: 4, 3: 2, 4: 6 }

export class PngError extends Error {}

/** Width and height from the IHDR chunk, or undefined when `data` is not a PNG. */
export function pngSize(data: Buffer): { width: number, height: number } | undefined {
  if (data.length < 24 || !data.subarray(0, 8).equals(SIGNATURE) || data.toString('latin1', 12, 16) !== 'IHDR') return undefined
  return { width: data.readUInt32BE(16), height: data.readUInt32BE(20) }
}

export function decodePng(data: Buffer): RawImage {
  if (!data.subarray(0, 8).equals(SIGNATURE)) throw new PngError('not a PNG')
  let offset = 8
  let width = 0
  let height = 0
  let channels: RawImage['channels'] | undefined
  const idat: Buffer[] = []
  while (offset + 8 <= data.length) {
    const length = data.readUInt32BE(offset)
    const type = data.toString('latin1', offset + 4, offset + 8)
    const body = data.subarray(offset + 8, offset + 8 + length)
    offset += 12 + length
    if (type === 'IHDR') {
      width = body.readUInt32BE(0)
      height = body.readUInt32BE(4)
      const depth = body[8]
      const colorType = body[9] as number
      const interlace = body[12]
      channels = CHANNELS[colorType]
      if (depth !== 8 || channels === undefined || interlace !== 0) throw new PngError(`unsupported PNG (depth ${depth}, color type ${colorType}, interlace ${interlace})`)
    }
    else if (type === 'IDAT') idat.push(body)
    else if (type === 'IEND') break
  }
  if (!channels || width === 0 || height === 0) throw new PngError('PNG without IHDR')
  const raw = zlib.inflateSync(Buffer.concat(idat))
  const stride = width * channels
  if (raw.length < (stride + 1) * height) throw new PngError('truncated PNG data')
  const pixels = Buffer.alloc(stride * height)
  for (let y = 0; y < height; y++) {
    const filter = raw[y * (stride + 1)]
    const src = y * (stride + 1) + 1
    const dst = y * stride
    for (let x = 0; x < stride; x++) {
      const value = raw[src + x] as number
      const left = x >= channels ? pixels[dst + x - channels] as number : 0
      const up = y > 0 ? pixels[dst + x - stride] as number : 0
      const upLeft = y > 0 && x >= channels ? pixels[dst + x - stride - channels] as number : 0
      let out: number
      switch (filter) {
        case 0: out = value; break
        case 1: out = value + left; break
        case 2: out = value + up; break
        case 3: out = value + ((left + up) >> 1); break
        case 4: {
          const p = left + up - upLeft
          const pa = Math.abs(p - left)
          const pb = Math.abs(p - up)
          const pc = Math.abs(p - upLeft)
          out = value + (pa <= pb && pa <= pc ? left : pb <= pc ? up : upLeft)
          break
        }
        default: throw new PngError(`bad PNG filter ${filter}`)
      }
      pixels[dst + x] = out & 0xff
    }
  }
  return { width, height, channels, pixels }
}

const CRC_TABLE = (() => {
  const table = new Uint32Array(256)
  for (let n = 0; n < 256; n++) {
    let c = n
    for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1
    table[n] = c >>> 0
  }
  return table
})()

function crc32(buffer: Buffer): number {
  let crc = 0xffffffff
  for (const byte of buffer) crc = (CRC_TABLE[(crc ^ byte) & 0xff] as number) ^ (crc >>> 8)
  return (crc ^ 0xffffffff) >>> 0
}

function chunk(type: string, body: Buffer): Buffer {
  const head = Buffer.alloc(8)
  head.writeUInt32BE(body.length, 0)
  head.write(type, 4, 'latin1')
  const crc = Buffer.alloc(4)
  crc.writeUInt32BE(crc32(Buffer.concat([head.subarray(4), body])), 0)
  return Buffer.concat([head, body, crc])
}

/** Encode with filter "up" on every row (cheap and compresses screenshots well). */
export function encodePng(image: RawImage): Buffer {
  const { width, height, channels, pixels } = image
  const stride = width * channels
  const raw = Buffer.alloc((stride + 1) * height)
  for (let y = 0; y < height; y++) {
    const dst = y * (stride + 1)
    raw[dst] = 2
    for (let x = 0; x < stride; x++) {
      const up = y > 0 ? pixels[(y - 1) * stride + x] as number : 0
      raw[dst + 1 + x] = ((pixels[y * stride + x] as number) - up) & 0xff
    }
  }
  const ihdr = Buffer.alloc(13)
  ihdr.writeUInt32BE(width, 0)
  ihdr.writeUInt32BE(height, 4)
  ihdr[8] = 8
  ihdr[9] = COLOR_TYPE[channels]
  return Buffer.concat([SIGNATURE, chunk('IHDR', ihdr), chunk('IDAT', zlib.deflateSync(raw, { level: 9 })), chunk('IEND', Buffer.alloc(0))])
}

/** Area-average downscale to `scale` (0 < scale ≤ 1) of the original size; alpha is dropped. */
export function downscale(image: RawImage, scale: number): RawImage {
  const width = Math.max(1, Math.round(image.width * scale))
  const height = Math.max(1, Math.round(image.height * scale))
  const color = image.channels >= 3 ? 3 : 1
  const out = Buffer.alloc(width * height * color)
  const fx = image.width / width
  const fy = image.height / height
  for (let y = 0; y < height; y++) {
    const y0 = Math.floor(y * fy)
    const y1 = Math.max(y0 + 1, Math.floor((y + 1) * fy))
    for (let x = 0; x < width; x++) {
      const x0 = Math.floor(x * fx)
      const x1 = Math.max(x0 + 1, Math.floor((x + 1) * fx))
      const sums = [0, 0, 0]
      let n = 0
      for (let sy = y0; sy < y1 && sy < image.height; sy++) {
        for (let sx = x0; sx < x1 && sx < image.width; sx++) {
          const i = (sy * image.width + sx) * image.channels
          for (let c = 0; c < color; c++) sums[c] = (sums[c] as number) + (image.pixels[i + c] as number)
          n++
        }
      }
      for (let c = 0; c < color; c++) out[(y * width + x) * color + c] = Math.round((sums[c] as number) / Math.max(1, n))
    }
  }
  return { width, height, channels: color as 1 | 3, pixels: out }
}
