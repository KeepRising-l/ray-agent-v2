import test from 'node:test'
import assert from 'node:assert/strict'
import { citationsFromEvents } from './citations.js'

test('检索候选和实际引用分别还原，拒答不会被候选数量伪装成有依据回答', () => {
  const event = (type, data) => ({ type, detail: JSON.stringify(data) })
  const candidates = [event('RAG_EVIDENCE', { citations: [{ id: 'K1' }, { id: 'K2' }] })]
  assert.equal(citationsFromEvents(candidates).used.length, 0)
  const result = citationsFromEvents([...candidates, event('RAG_VALIDATED', { sourceIds: ['K2'] })])
  assert.deepEqual(result.used, ['K2'])
  assert.equal(citationsFromEvents([...candidates, event('RAG_REFUSED', { reason: 'INSUFFICIENT_EVIDENCE' })]).refused, true)
})
