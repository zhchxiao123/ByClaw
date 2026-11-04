/** Screenshot re-encoding with Electron's nativeImage: JPEG at lower quality first, then smaller. */

import { nativeImage } from 'electron'
import type { ImageCodec } from '@byclaw/node-runtime'

export const nativeImageCodec: ImageCodec = {
  lossy: true,
  async encode(png, { scale, quality }) {
    let image = nativeImage.createFromBuffer(png)
    const size = image.getSize()
    if (scale < 1) image = image.resize({ width: Math.max(1, Math.round(size.width * scale)), quality: 'good' })
    const out = image.getSize()
    return { mime: 'image/jpeg', data: image.toJPEG(quality), width: out.width, height: out.height }
  },
}
