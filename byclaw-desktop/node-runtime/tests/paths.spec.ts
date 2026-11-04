import fs from 'node:fs'
import path from 'node:path'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { DEFAULT_SEARCH_LIMITS, MAX_FILE_BYTES, runFileTool } from '../src/files.js'
import { canAllowDir, confineDir, confinePath, defaultForbiddenDirs, NotAllowedError, resolvePolicy, type ResolvedPolicy } from '../src/paths.js'
import { tempDir } from './helpers.js'

let root: string
let cleanup: () => void
let allowed: string
let outside: string
let forbidden: string
let policy: ResolvedPolicy

beforeEach(async () => {
  ({ dir: root, cleanup } = tempDir())
  allowed = path.join(root, 'allowed')
  outside = path.join(root, 'outside')
  forbidden = path.join(allowed, 'secret')
  for (const d of [allowed, outside, forbidden, path.join(allowed, 'sub')]) fs.mkdirSync(d, { recursive: true })
  fs.writeFileSync(path.join(allowed, 'a.txt'), 'hello allowed\nsecond line needle\n')
  fs.writeFileSync(path.join(outside, 'o.txt'), 'outside secret')
  fs.writeFileSync(path.join(forbidden, 'key'), 'private key')
  policy = await resolvePolicy({ allowedDirs: [allowed], forbiddenDirs: [forbidden] })
})
afterEach(() => cleanup())

const notAllowed = async (p: Promise<unknown>) => {
  const error = await p.then(() => undefined, e => e)
  expect(error).toBeInstanceOf(NotAllowedError)
  return error as NotAllowedError
}

describe('confinePath', () => {
  it('accepts a file inside an allowed dir and reports its root', async () => {
    const c = await confinePath(path.join(allowed, 'a.txt'), policy, 'read')
    expect(c).toEqual({ real: path.join(allowed, 'a.txt'), root: allowed })
  })

  it('rejects paths outside, flagged as outsideAllowed', async () => {
    const e = await notAllowed(confinePath(path.join(outside, 'o.txt'), policy, 'read'))
    expect(e.outsideAllowed).toBe(true)
  })

  it('rejects .. escapes before touching the filesystem', async () => {
    await notAllowed(confinePath(`${allowed}/../outside/o.txt`, policy, 'read'))
    await notAllowed(confinePath(`${allowed}/sub/../../outside/missing`, policy, 'write'))
  })

  it('rejects relative paths and NUL bytes', async () => {
    await notAllowed(confinePath('a.txt', policy, 'read'))
    await notAllowed(confinePath(`${allowed}/a\0.txt`, policy, 'read'))
  })

  it('rejects a symlink inside the allowed dir pointing outside', async () => {
    fs.symlinkSync(path.join(outside, 'o.txt'), path.join(allowed, 'link.txt'))
    fs.symlinkSync(outside, path.join(allowed, 'linkdir'))
    await notAllowed(confinePath(path.join(allowed, 'link.txt'), policy, 'read'))
    await notAllowed(confinePath(path.join(allowed, 'linkdir', 'o.txt'), policy, 'read'))
    await notAllowed(confinePath(path.join(allowed, 'link.txt'), policy, 'write'))
    // New file through a symlinked parent: the parent's realpath is checked.
    await notAllowed(confinePath(path.join(allowed, 'linkdir', 'new.txt'), policy, 'write'))
  })

  it('rejects writing through a dangling symlink', async () => {
    fs.symlinkSync(path.join(outside, 'created-by-write.txt'), path.join(allowed, 'dangling'))
    await notAllowed(confinePath(path.join(allowed, 'dangling'), policy, 'write'))
  })

  it('allows a symlink that stays inside', async () => {
    fs.symlinkSync(path.join(allowed, 'a.txt'), path.join(allowed, 'inner'))
    expect((await confinePath(path.join(allowed, 'inner'), policy, 'read')).real).toBe(path.join(allowed, 'a.txt'))
  })

  it('rejects forbidden dirs even inside an allowed dir, including via symlink', async () => {
    const e = await notAllowed(confinePath(path.join(forbidden, 'key'), policy, 'read'))
    expect(e.outsideAllowed).toBe(false)
    fs.symlinkSync(forbidden, path.join(allowed, 'sneaky'))
    await notAllowed(confinePath(path.join(allowed, 'sneaky', 'key'), policy, 'read'))
  })

  it('allows a new file whose parent exists inside, fails when the parent is missing', async () => {
    expect((await confinePath(path.join(allowed, 'new.txt'), policy, 'write')).real).toBe(path.join(allowed, 'new.txt'))
    await expect(confinePath(path.join(allowed, 'nope', 'new.txt'), policy, 'write')).rejects.toMatchObject({ code: 'ENOENT' })
    await expect(confinePath(path.join(allowed, 'missing.txt'), policy, 'read')).rejects.toMatchObject({ code: 'ENOENT' })
  })

  it('accepts the alias of an allowed dir that is itself a symlink', async () => {
    const alias = path.join(root, 'alias')
    fs.symlinkSync(allowed, alias)
    const p = await resolvePolicy({ allowedDirs: [alias], forbiddenDirs: [] })
    expect((await confinePath(path.join(alias, 'a.txt'), p, 'read')).real).toBe(path.join(allowed, 'a.txt'))
  })

  it('confineDir requires a directory', async () => {
    expect(await confineDir(path.join(allowed, 'sub'), policy)).toBe(path.join(allowed, 'sub'))
    await expect(confineDir(path.join(allowed, 'a.txt'), policy)).rejects.toThrow('不是目录')
  })

  it('an empty allow list allows nothing', async () => {
    const p = await resolvePolicy({ allowedDirs: [], forbiddenDirs: [] })
    await notAllowed(confinePath(path.join(allowed, 'a.txt'), p, 'read'))
  })
})

describe('forbidden dirs', () => {
  it('include ssh, keychain, browser profiles and own data on macOS', () => {
    const dirs = defaultForbiddenDirs({ home: '/Users/u', platform: 'darwin', ownDataDirs: ['/Users/u/Library/Application Support/ByClaw'] })
    expect(dirs).toEqual(expect.arrayContaining([
      '/Users/u/.ssh',
      '/Users/u/Library/Keychains',
      '/Users/u/Library/Safari',
      '/Users/u/Library/Application Support/Google/Chrome',
      '/Users/u/Library/Application Support/Firefox',
      '/Users/u/Library/Application Support/Microsoft Edge',
      '/Users/u/Library/Application Support/ByClaw',
    ]))
  })

  it('canAllowDir refuses a forbidden dir or one inside it', async () => {
    expect(await canAllowDir(forbidden, [forbidden])).toBe(false)
    expect(await canAllowDir(path.join(forbidden, 'x'), [forbidden])).toBe(false)
    expect(await canAllowDir(allowed, [forbidden])).toBe(true)
  })
})

describe('runFileTool', () => {
  it('lists, hiding forbidden entries', async () => {
    const out = JSON.parse(await runFileTool({ action: 'list', path: allowed }, policy))
    const names = out.entries.map((e: { name: string }) => e.name)
    expect(names).toEqual(['a.txt', 'sub'])
  })

  it('reads text, refuses binary content and files over 8 MB', async () => {
    expect(await runFileTool({ action: 'read', path: path.join(allowed, 'a.txt') }, policy)).toContain('hello allowed')
    fs.writeFileSync(path.join(allowed, 'bin'), Buffer.from([1, 0, 2]))
    expect(await runFileTool({ action: 'read', path: path.join(allowed, 'bin') }, policy)).toContain('二进制文件')
    fs.writeFileSync(path.join(allowed, 'big'), Buffer.alloc(MAX_FILE_BYTES + 1, 97))
    await expect(runFileTool({ action: 'read', path: path.join(allowed, 'big') }, policy)).rejects.toThrow('上限')
  })

  it('writes new and existing files, refuses over 8 MB', async () => {
    await runFileTool({ action: 'write', path: path.join(allowed, 'new.txt'), content: '新内容' }, policy)
    expect(fs.readFileSync(path.join(allowed, 'new.txt'), 'utf8')).toBe('新内容')
    await expect(runFileTool({ action: 'write', path: path.join(allowed, 'x'), content: 'a'.repeat(MAX_FILE_BYTES + 1) }, policy)).rejects.toThrow('上限')
    expect(fs.existsSync(path.join(allowed, 'x'))).toBe(false)
  })

  it('never writes outside', async () => {
    await notAllowed(runFileTool({ action: 'write', path: path.join(outside, 'pwn.txt'), content: 'x' }, policy))
    expect(fs.existsSync(path.join(outside, 'pwn.txt'))).toBe(false)
  })

  it('searches names and content, skipping forbidden dirs and symlinks', async () => {
    fs.writeFileSync(path.join(allowed, 'sub', 'needle-name.md'), 'nothing')
    fs.writeFileSync(path.join(forbidden, 'needle.txt'), 'needle')
    fs.symlinkSync(outside, path.join(allowed, 'out-link'))
    fs.writeFileSync(path.join(outside, 'needle-outside'), 'needle')
    const out = await runFileTool({ action: 'search', path: allowed, query: 'NEEDLE' }, policy)
    expect(out).toContain(path.join(allowed, 'sub', 'needle-name.md'))
    expect(out).toContain(`${path.join(allowed, 'a.txt')}:2: second line needle`)
    expect(out).not.toContain(forbidden)
    expect(out).not.toContain('outside')
  })

  it('caps search results', async () => {
    for (let i = 0; i < 10; i++) fs.writeFileSync(path.join(allowed, `hit-${i}`), '')
    const out = await runFileTool({ action: 'search', path: allowed, query: 'hit' }, policy, { ...DEFAULT_SEARCH_LIMITS, maxNameMatches: 3 })
    expect(out).toContain('文件名包含「hit」：3 个')
    expect(out).toContain('搜索提前结束')
  })
})
