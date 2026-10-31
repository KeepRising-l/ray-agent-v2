import test from 'node:test'
import assert from 'node:assert/strict'
import { timeline } from './runEvents.js'
test('时间线按seq去重，摘要保留估算说明，不展示原始错误正文', () => {
  const rows = timeline([{ seq: 2, type: 'COMPACTION_APPLIED', detail: '{"beforeTokens":100,"afterTokens":40}' }, { seq: 1, type: 'QUEUED', detail: 'null' }, { seq: 1, type: 'QUEUED', detail: 'null' }, { seq: 3, type: 'FAILED', detail: 'raw provider body' }])
  assert.deepEqual(rows.map(row => row.seq), [1, 2, 3]); assert.equal(rows[0].label, '已排队'); assert.match(rows[1].description, /本地估算 100 → 40/); assert.equal(rows[2].description, '')
})
