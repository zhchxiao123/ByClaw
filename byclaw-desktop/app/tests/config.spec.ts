import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { afterEach, describe, expect, it } from 'vitest'
import { defaultDeviceName, effectiveApiBase, effectiveRelayUrl, effectiveWebUrl, loadConfig, normalizeWebUrl, saveConfig } from '../src/config.js'
import { allowedOrigins, isAllowedNavigation, isExternalOpenable, isWebOrigin } from '../src/navigation.js'

const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'byclaw-app-'))
afterEach(() => fs.rmSync(path.join(dir, 'config.json'), { force: true }))

describe('config', () => {
  it('defaults on first run and round-trips', () => {
    const file = path.join(dir, 'config.json')
    const config = loadConfig(file)
    expect(config).toMatchObject({ allowedDirs: [], launchAtLogin: true, paused: false, capabilities: ['shell', 'files.read', 'files.write', 'browser'] })
    saveConfig(file, { ...config, webUrl: 'http://h/beyond/', allowedDirs: ['/a'], paused: true })
    expect(loadConfig(file)).toMatchObject({ webUrl: 'http://h/beyond/', allowedDirs: ['/a'], paused: true })
    expect((fs.statSync(file).mode & 0o777).toString(8)).toBe('600')
  })

  it('survives a broken file and drops unknown capabilities', () => {
    const file = path.join(dir, 'config.json')
    fs.writeFileSync(file, '{"capabilities":["shell","bogus"],"allowedDirs":"nope"')
    expect(loadConfig(file).allowedDirs).toEqual([])
    fs.writeFileSync(file, '{"capabilities":["shell","bogus"]}')
    expect(loadConfig(file).capabilities).toEqual(['shell'])
  })

  it('normalizes web URLs', () => {
    expect(normalizeWebUrl(' 203.0.113.10:18080/beyond/ ')).toBe('https://203.0.113.10:18080/beyond/')
    expect(normalizeWebUrl('http://h:18080/beyond/#/x')).toBe('http://h:18080/beyond/')
    expect(() => normalizeWebUrl('file:///etc/passwd')).toThrow()
  })

  it('derives relay and API base from the web URL, with overrides', () => {
    const c = loadConfig(path.join(dir, 'none.json'))
    expect(effectiveWebUrl(c, { BYCLAW_DESKTOP_URL: 'http://dev:8080/beyond/' })).toBe('http://dev:8080/beyond/')
    expect(effectiveRelayUrl(c, 'http://203.0.113.10:18080/beyond/', {})).toBe('ws://203.0.113.10:18080/device-relay/node')
    expect(effectiveRelayUrl({ ...c, relayUrl: 'wss://r/device-relay/node' }, 'http://h/', {})).toBe('wss://r/device-relay/node')
    expect(effectiveApiBase(c, 'https://h:8443/beyond/')).toBe('https://h:8443')
  })

  it('strips .local from the default device name', () => {
    expect(defaultDeviceName('Zhangs-MacBook-Pro.local')).toBe('Zhangs-MacBook-Pro')
  })
})

describe('navigation', () => {
  const origins = allowedOrigins('http://203.0.113.10:18080/beyond/', [], 'http://203.0.113.10:8888/mockLogin/')
  it('allows the web origin and extras only', () => {
    expect(isAllowedNavigation('http://203.0.113.10:18080/beyond/chat', origins)).toBe(true)
    expect(isAllowedNavigation('http://203.0.113.10:8888/mockLogin/', origins)).toBe(true)
    expect(isAllowedNavigation('http://203.0.113.10:9999/', origins)).toBe(false)
    expect(isAllowedNavigation('https://example.com/', origins)).toBe(false)
    expect(isAllowedNavigation('javascript:alert(1)', origins)).toBe(false)
  })

  it('opens only http(s)/mailto externally and reads tokens only on the web origin', () => {
    expect(isExternalOpenable('https://docs.example.com')).toBe(true)
    expect(isExternalOpenable('file:///Applications/Calculator.app')).toBe(false)
    expect(isWebOrigin('http://203.0.113.10:18080/beyond/x', 'http://203.0.113.10:18080/beyond/')).toBe(true)
    expect(isWebOrigin('http://203.0.113.10:8888/mockLogin/', 'http://203.0.113.10:18080/beyond/')).toBe(false)
  })
})
