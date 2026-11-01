import test from 'node:test'
import assert from 'node:assert/strict'
import { messageBlocks, inlineParts } from './messageFormat.js'
test('代码围栏完整或流式未闭合时仍是独立代码块，保留换行', () => {
  assert.deepEqual(messageBlocks('说明\n\n```java\nint n = 1;\nreturn n;'), [
    { type: 'paragraph', text: '说明' }, { type: 'code', language: 'java', text: 'int n = 1;\nreturn n;' },
  ])
})
test('标题、列表与引用独立成块，来源编号不因基础排版丢失', () => {
  const blocks = messageBlocks('## 规则\n- 一条 [K1]\n- 二条\n\n> 引文')
  assert.equal(blocks[0].type, 'heading');assert.deepEqual(blocks[1].items, ['一条 [K1]', '二条'])
  assert.equal(blocks[2].type, 'quote');assert.equal(inlineParts(blocks[1].items[0])[1].id, 'K1')
})
test('HTML与危险链接只当文本，行内代码和强调仅产生受限文本类型', () => {
  const malicious = '<img src=x onerror=alert(1)> [点击](javascript:alert(1))'
  assert.deepEqual(messageBlocks(malicious), [{ type: 'paragraph', text: malicious }])
  assert.deepEqual(inlineParts('**接口** `main`'), [{ type: 'strong', text: '接口' }, { type: 'text', text: ' ' }, { type: 'code', text: 'main' }])
})
