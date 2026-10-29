import test from 'node:test'
import assert from 'node:assert/strict'
import { streamChat } from './chat.js'

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
