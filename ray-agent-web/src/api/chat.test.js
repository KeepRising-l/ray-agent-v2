import test from 'node:test'
import assert from 'node:assert/strict'
import { streamChat, streamAnswer } from './chat.js'

function responseFrom(text) {
  const bytes = new TextEncoder().encode(text)
  // 故意逐字节拆分，模拟中文和 SSE 边界跨网络数据块。
  const body = new ReadableStream({
    start(controller) {
      for (const byte of bytes) controller.enqueue(Uint8Array.of(byte))
      controller.close()
    },
  })
  return new Response(body, { headers: { 'content-type': 'text/event-stream' } })
}

async function withResponse(response, run) {
  const previous = globalThis.fetch
  globalThis.fetch = async () => response
  try {
    await run()
  } finally {
    globalThis.fetch = previous
  }
}

test('正确解析跨数据块的中文和完成事件', async () => {
  const input = 'event:text\ndata:{"text":"你好"}\n\nevent:done\ndata:{"reason":"STOP"}\n\n'
  await withResponse(responseFrom(input), async () => {
    let answer = ''
    const reason = await streamChat('问题', new AbortController().signal, text => { answer += text })
    assert.equal(answer, '你好')
    assert.equal(reason, 'STOP')
  })
})

test('工具事件和文本独立传递，工具失败不冒充最终模型错误', async () => {
  const input = 'event:tool\ndata:{"seq":3,"type":"TOOL_FAILED","detail":"TIMEOUT"}\n\nevent:text\ndata:{"text":"搜索失败"}\n\nevent:done\ndata:{"reason":"STOP"}\n\n'
  await withResponse(responseFrom(input), async () => {
    const events = []
    let text = ''
    await streamChat('问题', new AbortController().signal, chunk => { text += chunk }, 'channel', { onTool: event => events.push(event) })
    assert.equal(events[0].type, 'TOOL_FAILED')
    assert.equal(text, '搜索失败')
  })
})

test('知识库事件独立传递，不把证据判断显示成回答文本', async () => {
  await withResponse(responseFrom('event:rag\ndata:{"type":"RAG_DECISION","detail":"{}"}\n\nevent:text\ndata:{"text":"答案 [K1]"}\n\nevent:done\ndata:{"reason":"STOP"}\n\n'), async () => {
    const events = []
    let text = ''
    await streamChat('question', new AbortController().signal, value => { text += value }, 'channel', { onRag: event => events.push(event) })
    assert.equal(events[0].type, 'RAG_DECISION')
    assert.equal(text, '答案 [K1]')
  })
})

test('已收到部分文本后出错，不显示完成', async () => {
  const input = 'event:text\ndata:{"text":"部分"}\n\nevent:error\ndata:{"message":"模型失败"}\n\n'
  await withResponse(responseFrom(input), async () => {
    let answer = ''
    await assert.rejects(streamChat('问题', new AbortController().signal, text => { answer += text }), /模型失败/)
    assert.equal(answer, '部分')
  })
})

test('流提前结束且没有完成事件时报告错误', async () => {
  await withResponse(responseFrom('event:text\ndata:{"text":"部分"}\n\n'), async () => {
    await assert.rejects(streamChat('问题', new AbortController().signal, () => {}), /未收到完成事件/)
  })
})

test('请求带上选择的渠道名称', async () => {
  const previous = globalThis.fetch
  let requestBody
  globalThis.fetch = async (_url, options) => {
    requestBody = JSON.parse(options.body)
    return responseFrom('event:done\ndata:{"reason":"STOP"}\n\n')
  }
  try {
    await streamChat('问题', new AbortController().signal, () => {}, 'second')
    assert.equal(requestBody.message, '问题')
    assert.equal(requestBody.channelId, 'second')
  } finally {
    globalThis.fetch = previous
  }
})

test('会话和请求编号随输入提交，运行编号由服务端元数据提供', async () => {
  const previous = globalThis.fetch
  let requestBody
  let metadata
  globalThis.fetch = async (_url, options) => {
    requestBody = JSON.parse(options.body)
    return responseFrom('event:meta\ndata:{"sessionId":"session-1","runId":"run-1"}\n\nevent:done\ndata:{"reason":"STOP"}\n\n')
  }
  try {
    await streamChat('问题', new AbortController().signal, () => {}, 'channel', {
      sessionId: 'session-1', clientRequestId: 'request-1', onMeta: value => { metadata = value },
    })
    assert.equal(requestBody.sessionId, 'session-1')
    assert.equal(requestBody.clientRequestId, 'request-1')
    assert.equal(metadata.runId, 'run-1')
    assert.notEqual(metadata.runId, requestBody.clientRequestId)
  } finally {
    globalThis.fetch = previous
  }
})

test('等待用户是暂停而非流提前结束，提问与摘要事件不混入回答文本', async () => {
  await withResponse(responseFrom('event:context\ndata:{"type":"COMPACTION_APPLIED","detail":"{}"}\n\nevent:question\ndata:{"id":"q1","runId":"r1","question":"城市？"}\n\n'), async () => {
    const contexts = []; let question; let text = ''
    const result = await streamChat('问题', new AbortController().signal, value => { text += value }, 'channel', {
      onContext: value => contexts.push(value), onQuestion: value => { question = value },
    })
    assert.equal(result, 'WAITING_USER'); assert.equal(question.id, 'q1'); assert.equal(text, '')
    assert.equal(contexts[0].type, 'COMPACTION_APPLIED')
  })
})

test('补充回答提交原runId和questionId，不创建新的聊天请求', async () => {
  const previous = globalThis.fetch; let url; let body
  globalThis.fetch = async (path, options) => { url = path; body = JSON.parse(options.body); return responseFrom('event:done\ndata:{"reason":"STOP"}\n\n') }
  try {
    await streamAnswer('run-1', 'question-1', '杭州', new AbortController().signal, () => {})
    assert.equal(url, '/api/runs/run-1/answers'); assert.deepEqual(body, { questionId: 'question-1', answer: '杭州' })
  } finally { globalThis.fetch = previous }
})

test('排队与启动分别通知，收到排队事件仍须等完成事件', async () => {
  const input = 'event:queue\ndata:{"runId":"r1","status":"QUEUED"}\n\nevent:queue\ndata:{"runId":"r1","status":"RUNNING"}\n\nevent:done\ndata:{"reason":"STOP"}\n\n'
  await withResponse(responseFrom(input), async () => {
    const states = []
    assert.equal(await streamChat('问题', new AbortController().signal, () => {}, 'channel', { onQueue: value => states.push(value.status) }), 'STOP')
    assert.deepEqual(states, ['QUEUED', 'RUNNING'])
  })
  await withResponse(responseFrom('event:queue\ndata:{"status":"QUEUED"}\n\n'), async () => {
    await assert.rejects(streamChat('问题', new AbortController().signal, () => {}), /未收到完成事件/)
  })
})

test('过载429明确提示等待或取消，不开始读取流', async () => {
  await withResponse(new Response('{}', { status: 429 }), async () => {
    await assert.rejects(streamChat('问题', new AbortController().signal, () => {}), /容量已满/)
  })
})
