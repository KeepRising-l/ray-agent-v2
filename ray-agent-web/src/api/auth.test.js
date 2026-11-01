import test from 'node:test'
import assert from 'node:assert/strict'
import { login, csrfHeaders } from './auth.js'
test('登录使用表单和当前CSRF，登录后换用新令牌，不把口令存进浏览器存储', async () => {
  const original = globalThis.fetch; const calls = []; let sessions = 0
  globalThis.fetch = async (url, options) => {
    calls.push([url, options])
    return new Response(JSON.stringify(url.endsWith('/session') ? { authenticated: sessions++ > 0, csrfToken: sessions === 1 ? 'before-login' : 'after-login', csrfHeader: 'X-CSRF-TOKEN' } : { message: 'ok' }), { headers: { 'Content-Type': 'application/json' } })
  }
  try {
    await login('owner', 'test-only-password')
    assert.equal(calls[1][1].headers['X-CSRF-TOKEN'], 'before-login')
    assert.equal(calls[1][1].body.get('username'), 'owner')
    assert.equal(calls[1][1].body.get('password'), 'test-only-password')
    assert.equal(csrfHeaders('POST')['X-CSRF-TOKEN'], 'after-login')
    assert.deepEqual(csrfHeaders('GET'), {})
  } finally { globalThis.fetch = original }
})
