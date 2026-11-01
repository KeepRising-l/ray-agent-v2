const fs = require('node:fs')
async function loginForVerification(page, base) {
  const file = process.env.RAY_TEST_AUTH_FILE
  if (!file) return
  const credentials = Object.fromEntries(fs.readFileSync(file, 'utf8').trim().split(/\r?\n/).map(line => { const split = line.indexOf('='); return [line.slice(0, split), line.slice(split + 1)] }))
  const state = await (await page.request.get(base+'/api/auth/session')).json()
  const response = await page.request.post(base+'/api/auth/login', { form: credentials, headers: { [state.csrfHeader]: state.csrfToken } })
  if (!response.ok()) throw new Error('验证环境登录失败，未输出口令')
}
module.exports = { loginForVerification }
