import { createHash, randomUUID } from 'node:crypto'

export interface SkillSummaryRedis {
  hget(key: string, field: string): Promise<string | null>
  eval(script: string, count: number, ...args: string[]): Promise<unknown>
}
export const WRITE_SUMMARIES = `
if (redis.call('HGET', KEYS[1], 'revision') or '') ~= ARGV[1] then return 0 end
redis.call('HSET', KEYS[1], ARGV[2], ARGV[3])
return 1`
export const INVALIDATE_SUMMARIES = `
redis.call('DEL', KEYS[1])
redis.call('HSET', KEYS[1], 'revision', ARGV[1])
return 1`

/** Permanent summaries; one hash and explicit KEYS keep all operations Cluster safe. */
export class SkillSummaryCache<T> {
  private readonly dirty = new Set<string>()
  private readonly namespace: string
  private readonly redis: SkillSummaryRedis
  constructor(redis: SkillSummaryRedis, namespace: string) {
    this.redis = redis
    this.namespace = namespace
  }
  private key(identity: string): string {
    return `byclaw:skill-catalog:v3:${createHash('sha256').update(JSON.stringify([this.namespace, identity])).digest('hex')}`
  }
  async invalidate(identity: string): Promise<void> {
    const key = this.key(identity)
    this.dirty.add(key)
    try {
      await this.redis.eval(INVALIDATE_SUMMARIES, 1, key, randomUUID())
      this.dirty.delete(key)
    } catch (error) { this.dirty.add(key); throw error }
  }
  async read(identity: string, version: string, load: () => Promise<T[]>): Promise<T[]> {
    const key = this.key(identity)
    const field = 'summaries'
    for (let attempt = 0; attempt < 3; attempt++) {
      if (this.dirty.has(key)) await this.invalidate(identity)
      const revision = await this.redis.hget(key, 'revision') ?? ''
      const raw = await this.redis.hget(key, field)
      if (raw !== null) {
        const record = JSON.parse(raw)
        if (record?.revision === revision && Array.isArray(record.items)) return record.items
      }
      const items = await load()
      if (Number(await this.redis.eval(WRITE_SUMMARIES, 1, key, revision, field,
        JSON.stringify({ revision, version, items }))) === 1) return items
    }
    throw new Error('Skill catalog changed during scanning; retry')
  }
}
