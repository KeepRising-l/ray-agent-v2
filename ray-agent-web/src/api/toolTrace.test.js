import test from 'node:test'
import assert from 'node:assert/strict'
import { toolTrace } from './toolTrace.js'

const event = (type, data) => ({ type, detail: JSON.stringify({ callId: 'a', name: 'web_search', step: 1, ...data }) })

test('失败工具没有来源，取消中的工具不显示成功', () => {
  assert.equal(toolTrace([event('TOOL_STARTED'), event('TOOL_FAILED', { code: 'TIMEOUT', sources: [{ url: 'https://example.com' }] })], 'SUCCEEDED')[0].sources.length, 0)
  assert.equal(toolTrace([event('TOOL_STARTED')], 'CANCELLED')[0].status, 'INTERRUPTED')
  assert.equal(toolTrace([event('TOOL_STARTED')], 'RUNNING')[0].status, 'RUNNING')
})

test('成功事件可刷新重建卡片，仅允许 HTTP(S) 来源链接', () => {
  const trace = toolTrace([event('TOOL_STARTED'), event('TOOL_SUCCEEDED', { code: 'OK', sources: [
    { id: 'W1', url: 'https://example.com' }, { id: 'W2', url: 'javascript:alert(1)' },
  ] })], 'SUCCEEDED')
  assert.equal(trace[0].status, 'SUCCEEDED')
  assert.equal(trace[0].sources.length, 1)
  assert.equal(trace[0].sources[0].id, 'W1')
})
