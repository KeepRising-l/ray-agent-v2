import test from 'node:test'
import assert from 'node:assert/strict'
import { knowledgeRequest, isProcessing } from './knowledge.js'

test('上传与处理中状态均不等于已就绪，失败可结束轮询', () => {
  for (const status of ['UPLOADED', 'PARSING', 'CHUNKING', 'EMBEDDING']) assert.equal(isProcessing(status), true)
  for (const status of ['READY', 'FAILED', 'INTERRUPTED']) assert.equal(isProcessing(status), false)
})

test('向用户显示明确业务错误，成功响应保留版本与数量', async () => {
  const previous = globalThis.fetch
  try {
    globalThis.fetch = async () => new Response(JSON.stringify({ message: '文件摘要校验失败' }), { status: 400 })
    await assert.rejects(knowledgeRequest('/test'), /文件摘要校验失败/)
    globalThis.fetch = async () => new Response(JSON.stringify({ version: { status: 'READY' }, vectorCount: 3 }))
    assert.equal((await knowledgeRequest('/test')).vectorCount, 3)
  } finally { globalThis.fetch = previous }
})
