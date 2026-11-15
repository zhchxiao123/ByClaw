import { test } from 'node:test'
import assert from 'node:assert/strict'
import { SkillSummaryCache, WRITE_SUMMARIES } from '../src/skill-summary-cache.ts'
test('persistent cache skips scans, invalidates deletes, and fences stale writers', async () => {
  const hashes = new Map()
  const hashFor = key => { if (!hashes.has(key)) hashes.set(key, new Map()); return hashes.get(key) }
  const redis = {
    async hget(key, field) { return hashFor(key).get(field) ?? null },
    async eval(script, count, key, ...args) {
      const hash = hashFor(key)
      assert.equal(count, 1)
      if (script !== WRITE_SUMMARIES) { hash.clear(); hash.set('revision', args[0]); return 1 }
      if ((hash.get('revision') ?? '') !== args[0]) return 0
      hash.set(args[1], args[2]); return 1
    },
  }
  const cache = new SkillSummaryCache(redis, 'user')
  await cache.read('employee', 'v1', async () => [{name:'old'}])
  assert.deepEqual(await cache.read('employee', 'v1', () => assert.fail('must not scan')), [{name:'old'}])
  await cache.read('other', 'v1', async () => [{name:'other'}])
  await cache.invalidate('employee')
  await new SkillSummaryCache(redis, 'user').read('other', 'changed-on-restart', () => assert.fail('other Agent and restart must reuse cache'))
  assert.deepEqual(await cache.read('employee', 'v1', async () => []), [])
  await cache.invalidate('employee')
  let scans = 0
  assert.deepEqual(await cache.read('employee', 'v1', async () => {
    if (++scans === 1) { await cache.invalidate('employee'); return [{name:'deleted'}] }
    return []
  }), [])
  assert.equal(scans, 2)
})
