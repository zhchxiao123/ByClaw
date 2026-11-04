import crypto from 'node:crypto'
import { describe, expect, it } from 'vitest'
import {
  ALLOWED_SUBCOMMANDS, base64Length, BrowserArgError, browserSessionName, packageScreenshot, parseBrowserCommand,
  pngCodec, redactedCommand, siteOf, tokenize, type ImageCodec,
} from '../src/browser.js'
import { decodePng, downscale, encodePng, pngSize, type RawImage } from '../src/png.js'

describe('tokenize', () => {
  it('splits on whitespace and honours quotes and escapes', () => {
    expect(tokenize('click 12')).toEqual(['click', '12'])
    expect(tokenize('  type  12   "hello world" ')).toEqual(['type', '12', 'hello world'])
    expect(tokenize(`fill 3 'it''s'`)).toEqual(['fill', '3', 'its'])
    expect(tokenize(`fill 3 "say \\"hi\\""`)).toEqual(['fill', '3', 'say "hi"'])
    expect(tokenize(`type 4 a\\ b`)).toEqual(['type', '4', 'a b'])
    expect(tokenize(`type 4 ""`)).toEqual(['type', '4', ''])
    expect(tokenize(`eval "(() => document.title)()"`)).toEqual(['eval', '(() => document.title)()'])
    expect(tokenize('type 5 "line1\nline2"')).toEqual(['type', '5', 'line1\nline2'])
  })

  it('never expands anything: shell syntax stays literal', () => {
    expect(tokenize('type 1 $(rm -rf ~); `id` *')).toEqual(['type', '1', '$(rm', '-rf', '~);', '`id`', '*'])
    expect(tokenize(`type 1 '$HOME'`)).toEqual(['type', '1', '$HOME'])
  })

  it('rejects unterminated quotes and huge input', () => {
    expect(() => tokenize('type 1 "abc')).toThrow(BrowserArgError)
    expect(() => tokenize("type 1 'abc")).toThrow(BrowserArgError)
    expect(() => tokenize(`type 1 ${'x'.repeat(20_000)}`)).toThrow(/太长/)
  })
})

describe('parseBrowserCommand (whitelist)', () => {
  it('classifies the PRD subcommands', () => {
    expect(parseBrowserCommand('state').kind).toBe('read')
    expect(parseBrowserCommand('get text 12').sub).toBe('get text')
    expect(parseBrowserCommand('get url').kind).toBe('read')
    expect(parseBrowserCommand('find --role button --name Save').kind).toBe('read')
    expect(parseBrowserCommand('screenshot --full-page').kind).toBe('read')
    expect(parseBrowserCommand('tab list').kind).toBe('read')
    expect(parseBrowserCommand('click 12').kind).toBe('interact')
    expect(parseBrowserCommand('type 3 hello').kind).toBe('interact')
    expect(parseBrowserCommand('keys Enter').kind).toBe('interact')
    expect(parseBrowserCommand('scroll down --amount 300').kind).toBe('interact')
    expect(parseBrowserCommand('tab select ABC').kind).toBe('interact')
    expect(parseBrowserCommand('eval document.title').kind).toBe('always')
    expect(parseBrowserCommand('upload 4 /tmp/a.pdf').kind).toBe('always')
    expect(parseBrowserCommand('bind').kind).toBe('bind')
    expect(parseBrowserCommand('unbind').kind).toBe('session')
    expect(parseBrowserCommand('close').kind).toBe('session')
  })

  it('refuses anything outside the whitelist', () => {
    for (const bad of ['analyze https://x.com', 'init hn/top', 'verify hn/top', 'network', 'daemon stop', 'doctor', 'rm -rf /', 'get cookies', 'tab', 'dialog']) {
      expect(() => parseBrowserCommand(bad), bad).toThrow(BrowserArgError)
    }
    expect(ALLOWED_SUBCOMMANDS).not.toContain('analyze')
    expect(ALLOWED_SUBCOMMANDS).toContain('click')
  })

  it('refuses options that leave the confirmed session or write files', () => {
    expect(() => parseBrowserCommand('state --session other')).toThrow(/--session/)
    expect(() => parseBrowserCommand('click 1 --session=other')).toThrow(/--session/)
    expect(() => parseBrowserCommand('state --profile work')).toThrow(/--profile/)
    expect(() => parseBrowserCommand('screenshot /etc/passwd')).toThrow(/不接受保存路径/)
  })

  it('tolerates a copied "bycli browser" prefix', () => {
    expect(parseBrowserCommand('bycli browser state').sub).toBe('state')
    expect(parseBrowserCommand('browser click 3').args).toEqual(['click', '3'])
  })

  it('normalizes navigation targets and only allows http(s)', () => {
    const open = parseBrowserCommand('open example.com/a?b=1')
    expect(open.kind).toBe('navigate')
    expect(open.url).toBe('https://example.com/a?b=1')
    expect(open.args).toEqual(['open', 'https://example.com/a?b=1'])
    expect(parseBrowserCommand('open http://intranet.local:8080/x').url).toBe('http://intranet.local:8080/x')
    expect(parseBrowserCommand('tab new https://a.test/').url).toBe('https://a.test/')
    expect(parseBrowserCommand('tab new').url).toBeUndefined()
    expect(() => parseBrowserCommand('open javascript:alert(1)')).toThrow(/http/)
    expect(() => parseBrowserCommand('open file:///etc/passwd')).toThrow(/http/)
    expect(() => parseBrowserCommand('open')).toThrow(/网址/)
  })

  it('finds --tab, wait timeouts and upload files', () => {
    expect(parseBrowserCommand('get url --tab T1').tab).toBe('T1')
    expect(parseBrowserCommand('click 3 --tab=T2').tab).toBe('T2')
    expect(parseBrowserCommand('wait text Done --timeout 30000').waitTimeoutMs).toBe(30000)
    expect(parseBrowserCommand('upload 4 /a/x.pdf /a/y.pdf').files).toEqual(['/a/x.pdf', '/a/y.pdf'])
    expect(parseBrowserCommand('upload --role button --name Attach /a/x.pdf').files).toEqual(['/a/x.pdf'])
    expect(parseBrowserCommand('upload /a/x.pdf').files).toEqual(['/a/x.pdf'])
    expect(() => parseBrowserCommand('upload')).toThrow(BrowserArgError)
  })
})

describe('siteOf / session names / redaction', () => {
  it('maps URLs to sites', () => {
    expect(siteOf('https://Mail.Example.com:8443/inbox?x')).toBe('mail.example.com')
    expect(siteOf('http://10.0.0.5/a')).toBe('10.0.0.5')
    expect(siteOf('about:blank')).toBe('')
    expect(siteOf('')).toBe('')
    expect(siteOf(undefined)).toBe('')
    expect(siteOf('chrome://settings/')).toBe('chrome://settings')
    expect(siteOf('data:text/html,hi')).toBe('data:')
  })

  it('derives a safe byCLI session name', () => {
    expect(browserSessionName(undefined)).toBe('byclaw')
    expect(browserSessionName('chat-42')).toBe('chat-42')
    const hashed = browserSessionName('--session evil; rm')
    expect(hashed).toMatch(/^s-[0-9a-f]{16}$/)
    expect(browserSessionName('--session evil; rm')).toBe(hashed)
  })

  it('keeps typed text and scripts out of the audit summary', () => {
    expect(redactedCommand(parseBrowserCommand('type 12 "hunter2 secret"'))).toBe('type 12 …（14 个字符）')
    expect(redactedCommand(parseBrowserCommand('fill --role textbox --name Password hunter2'))).toBe('fill …（7 个字符）')
    expect(redactedCommand(parseBrowserCommand('eval "document.cookie"'))).toBe('eval …（15 个字符）')
    expect(redactedCommand(parseBrowserCommand('click 12'))).toBe('click 12')
  })
})

function noise(width: number, height: number, channels: RawImage['channels'] = 3): RawImage {
  return { width, height, channels, pixels: crypto.randomBytes(width * height * channels) }
}

describe('png', () => {
  it('round-trips RGB and RGBA', () => {
    for (const channels of [3, 4] as const) {
      const image = noise(17, 9, channels)
      const back = decodePng(encodePng(image))
      expect(back).toMatchObject({ width: 17, height: 9, channels })
      expect(back.pixels.equals(image.pixels)).toBe(true)
    }
  })

  it('downscales by area averaging and drops alpha', () => {
    const image: RawImage = { width: 2, height: 2, channels: 4, pixels: Buffer.from([0, 0, 0, 255, 100, 100, 100, 255, 200, 200, 200, 255, 100, 100, 100, 255]) }
    const small = downscale(image, 0.5)
    expect(small).toMatchObject({ width: 1, height: 1, channels: 3 })
    expect([...small.pixels]).toEqual([100, 100, 100])
    expect(pngSize(encodePng(small))).toEqual({ width: 1, height: 1 })
  })
})

describe('packageScreenshot', () => {
  it('passes a small PNG through untouched', async () => {
    const png = encodePng(noise(20, 10))
    const { image, reduced } = await packageScreenshot(png)
    expect(reduced).toBe(false)
    expect(image).toEqual({ mime: 'image/png', data: png.toString('base64'), width: 20, height: 10 })
  })

  it('shrinks a PNG over the cap until its base64 fits', async () => {
    const png = encodePng(noise(400, 300))
    const max = 120_000
    expect(base64Length(png.length)).toBeGreaterThan(max)
    const { image, reduced } = await packageScreenshot(png, { maxBase64: max })
    expect(reduced).toBe(true)
    expect(image.data.length).toBeLessThanOrEqual(max)
    expect(image.mime).toBe('image/png')
    expect(image.width).toBeLessThan(400)
    expect(pngSize(Buffer.from(image.data, 'base64'))).toEqual({ width: image.width, height: image.height })
  })

  it('keeps under the protocol default of 2 MB', async () => {
    const png = encodePng(noise(1200, 800))
    expect(base64Length(png.length)).toBeGreaterThan(2 * 1024 * 1024)
    const { image } = await packageScreenshot(png)
    expect(image.data.length).toBeLessThanOrEqual(2 * 1024 * 1024)
  })

  it('with a lossy codec lowers quality before size', async () => {
    const tried: Array<{ scale: number, quality: number }> = []
    const codec: ImageCodec = {
      lossy: true,
      async encode(_png, step) {
        tried.push(step)
        const bytes = step.quality <= 40 ? 1000 : 900_000
        return { mime: 'image/jpeg', data: Buffer.alloc(bytes), width: 100 * step.scale, height: 50 * step.scale }
      },
    }
    const { image } = await packageScreenshot(encodePng(noise(300, 300)), { codec, maxBase64: 100_000 })
    expect(tried).toEqual([{ scale: 1, quality: 80 }, { scale: 1, quality: 60 }, { scale: 1, quality: 40 }])
    expect(image.mime).toBe('image/jpeg')
  })

  it('gives up with an error when nothing fits', async () => {
    await expect(packageScreenshot(encodePng(noise(200, 200)), { maxBase64: 10 })).rejects.toThrow(/仍超过/)
    await expect(packageScreenshot(Buffer.from('not a png'))).rejects.toThrow(/不是 PNG/)
  })

  it('pngCodec reports the scaled size', async () => {
    const out = await pngCodec.encode(encodePng(noise(100, 60)), { scale: 0.5, quality: 100 })
    expect(out).toMatchObject({ mime: 'image/png', width: 50, height: 30 })
  })
})
