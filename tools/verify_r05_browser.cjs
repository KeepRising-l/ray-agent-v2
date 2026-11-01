const {openInspector,closeInspector,openNewSession}=require('./ui_navigation.cjs')
const { loginForVerification } = require('./browser_auth.cjs')
const { chromium } = require('playwright')
const assert = require('node:assert/strict')
const fs = require('node:fs')

async function main() {
  const browser = await chromium.launch({ headless: true, executablePath: 'C:/Program Files/Google/Chrome/Application/chrome.exe' })
  let page
  try {
    page = await browser.newPage()
    const base = process.argv[2]
    await loginForVerification(page, process.argv[2])
    await page.goto(base)
    await page.locator('#channel option').first().waitFor({ state: 'attached' })
    await openNewSession(page)
    await page.locator('#new-mode').selectOption('KNOWLEDGE')
    await page.getByRole('checkbox', { name: 'R05 orchard', exact: true }).check()
    await page.getByRole('button', { name: '新建会话', exact: true }).click()
    await page.waitForFunction(() => document.querySelector('main').innerText.includes('当前会话模式：KNOWLEDGE'))
    await page.locator('#message').fill('虹杉果园的测试代号是什么？')
    await page.getByRole('button', { name: '发送', exact: true }).click()
    await page.waitForFunction(() => document.querySelector('section[aria-label="历史消息"]').innerText.includes('Pine42') && !document.querySelector('#message').disabled, null, { timeout: 90000 })
    await openInspector(page)
    const cited = page.locator('.citation-card').filter({ hasText: '回答已引用' }).first()
    await cited.getByRole('button').click()
    await cited.locator('pre').waitFor()
    assert.match(await cited.locator('pre').innerText(), /Pine42/)
    assert.equal(await page.locator('.tool-card').count(), 0)
    await page.reload()
    await openInspector(page)
    await page.locator('.citation-card').filter({ hasText: '回答已引用' }).first().waitFor()
    const sessions = await (await page.request.get(base + '/api/sessions')).json()
    const runs = await (await page.request.get(base + `/api/sessions/${sessions[0].id}/runs`)).json()
    const events = await (await page.request.get(base + `/api/runs/${runs[0].id}/events`)).json()
    const used = JSON.parse(events.find(event => event.type === 'RAG_VALIDATED').detail).sourceIds[0]
    const sources = JSON.parse(events.find(event => event.type === 'RAG_EVIDENCE').detail).citations
    const source = sources.find(source => source.id === used)
    await page.locator('.citation-card').filter({ hasText: '回答已引用' }).first().getByRole('button').click()
    await page.locator('.citation-card pre').first().waitFor()
    fs.mkdirSync('logs', { recursive: true })
    await page.screenshot({ path: 'logs/r05-browser.png', fullPage: true })
    const csrf = await (await page.request.get(base+'/api/auth/session')).json()
    const deletion = await page.request.delete(base + `/api/knowledge-bases/${source.kbId}/documents/${source.documentId}`, { headers: { [csrf.csrfHeader]: csrf.csrfToken } })
    assert.ok(deletion.ok())
    await page.locator('.citation-card').filter({ hasText: '回答已引用' }).first().getByRole('button').click()
    await page.getByText('引用已失效：资料已更新或删除。', { exact: true }).waitFor()
    await closeInspector(page)
    await page.locator('#message').fill('虹杉果园的测试代号是什么？')
    await page.getByRole('button', { name: '发送', exact: true }).click()
    await page.waitForFunction(() => document.querySelector('section[aria-label="历史消息"]').lastElementChild?.textContent.includes('没有足够依据') && !document.querySelector('#message').disabled, null, { timeout: 90000 })
    assert.equal(await page.locator('.tool-card').count(), 0)
    console.log('PASS browser: KNOWLEDGE scope, cited answer, source text, reload, expired citation, refusal without web tools')
  } catch (error) {
    if (page) {
      await page.screenshot({ path: 'logs/r05-browser-failure.png', fullPage: true }).catch(() => {})
      console.error('Browser status: ' + await page.getByRole('status').innerText().catch(() => 'unavailable'))
    }
    throw error
  } finally {
    await browser.close()
  }
}

main().catch(error => { console.error(error.message); process.exitCode = 1 })
