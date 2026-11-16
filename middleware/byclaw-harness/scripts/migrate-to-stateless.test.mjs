// Pure parts of the admin migration; the database steps are covered by dsh-db's import tests.
import assert from 'node:assert/strict'
import { createHash } from 'node:crypto'
import { mkdir, mkdtemp, rm, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import test from 'node:test'
import { imageObjects, parseArgs, parseEnviron, uploadImages } from './migrate-to-stateless.mjs'

test('options name one user and optionally its contexts', () => {
  assert.deepEqual(parseArgs(['--user-code', 'u1']), { contexts: [], userCode: 'u1' })
  assert.deepEqual(parseArgs(['--user-code', 'u1', '--context', 'ctx_a', '--home', '/h', '--env-from-pid', 'auto']),
    { contexts: ['ctx_a'], userCode: 'u1', home: '/h', envFromPid: 'auto' })
  assert.throws(() => parseArgs([]), /--user-code is required/)
  assert.throws(() => parseArgs(['--user-code', '../x']), /--user-code is required/)
  assert.throws(() => parseArgs(['--user-code', 'u1', '--context', '../x']), /--context/)
  assert.throws(() => parseArgs(['--user-code', 'u1', '--home', '/h']), /exactly one --context/)
  assert.throws(() => parseArgs(['--user-code', 'u1', '--force']), /unknown option/)
})

test('a host process environment is read as it started', () => {
  assert.deepEqual(parseEnviron(Buffer.from('DB_HOST=db\0DB_PASS=a=b\0EMPTY=\0junk\0')), { DB_HOST: 'db', DB_PASS: 'a=b', EMPTY: '' })
})

test('image objects are found by digest and stored where the attachment store looks for them', async () => {
  const home = await mkdtemp(join(tmpdir(), 'migrate-images-'))
  try {
    assert.deepEqual(await imageObjects(home), [])
    const data = Buffer.from('png bytes')
    const sha = createHash('sha256').update(data).digest('hex')
    await mkdir(join(home, 'attachments', 'v1', 'objects', sha.slice(0, 2)), { recursive: true })
    await writeFile(join(home, 'attachments', 'v1', 'objects', sha.slice(0, 2), sha), data)
    await writeFile(join(home, 'attachments', 'v1', 'objects', sha.slice(0, 2), `${sha}.123.fetch`), 'partial')
    const objects = await imageObjects(home)
    assert.deepEqual(objects.map(o => o.sha), [sha])

    const calls = []
    const ok = async (url, init) => { calls.push({ url, init }); return new Response(JSON.stringify({ code: 0 }), { status: 200 }) }
    assert.equal(await uploadImages(objects, { baseUrl: 'http://be/byaiService/', systemCode: 'sys', token: 'tok' }, ok), 1)
    assert.equal(calls[0].url, 'http://be/byaiService/fs/operation/v1/files/put')
    assert.deepEqual(calls[0].init.headers, { 'Beyond-Token': 'tok', 'system-code': 'sys' })
    assert.equal(calls[0].init.body.get('path'), `/by/.dsh/attachments/${sha.slice(0, 2)}/${sha}`)
    assert.equal(calls[0].init.body.get('spaceType'), 'USER')

    const refused = async () => new Response(JSON.stringify({ code: -1, msg: 'no space' }), { status: 200 })
    await assert.rejects(uploadImages(objects, { baseUrl: 'http://be', systemCode: '', token: 't' }, refused), /no space/)
    await writeFile(objects[0].path, 'changed')
    await assert.rejects(uploadImages(objects, { baseUrl: 'http://be', systemCode: '', token: 't' }, ok), /does not match its digest/)
  } finally { await rm(home, { recursive: true, force: true }) }
})
