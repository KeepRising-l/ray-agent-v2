const { chromium } = require('playwright')
const assert = require('node:assert/strict')
const fs = require('node:fs')

async function main() {
  const browser = await chromium.launch({ headless: true, executablePath: 'C:/Program Files/Google/Chrome/Application/chrome.exe' })
  let page
  try {
    page = await browser.newPage()
    await page.goto(process.argv[2])
    await page.locator('#channel option').first().waitFor({ state: 'attached' })
    await page.locator('#new-mode').selectOption('AUTO')
    await page.getByRole('checkbox', { name: 'R06 demo', exact: true }).check()
    await page.getByRole('button', { name: '新建会话', exact: true }).click()
    await page.waitForFunction(() => document.querySelector('main').innerText.includes('当前会话模式：AUTO'))
    await page.locator('#message').fill('比较我的资料推荐的Java版本与网上最新Java长期支持版本，分别标明来源。')
    await page.getByRole('button', { name: '发送', exact: true }).click()
    await page.waitForFunction(() => !document.querySelector('#message').disabled && document.querySelector('section[aria-label="历史消息"]').innerText.includes('比较与局限'), null, { timeout: 100000 })
    assert.match(await page.getByRole('region', { name: 'AUTO路由' }).innerText(), /知识库与网页组合/)
    const cited = page.locator('.citation-card').filter({ hasText: '回答已引用' }).first()
    await cited.getByRole('button').click()
    await cited.locator('pre').waitFor()
    assert.match(await cited.locator('pre').innerText(), /R6Mango91/)
    if (process.argv[3] === 'live') assert.ok(await page.locator('.tool-card a').count() > 0, 'missing webpage source links')
    await page.reload()
    await page.getByRole('region', { name: 'AUTO路由' }).waitFor()
    assert.match(await page.getByRole('region', { name: 'AUTO路由' }).innerText(), /知识库与网页组合/)
    fs.mkdirSync('logs', { recursive: true })
    await page.screenshot({ path: 'logs/r06-browser.png', fullPage: true })
    // 仅改变“新会话模式”不能修改当前 AUTO 会话，必须显式新建。
    await page.locator('#new-mode').selectOption('CHAT')
    assert.match(await page.locator('main').innerText(), /当前会话模式：AUTO/)
    await page.getByRole('button', { name: '新建会话', exact: true }).click()
    await page.waitForFunction(() => document.querySelector('main').innerText.includes('当前会话模式：CHAT'))
    assert.equal(await page.getByRole('region', { name: 'AUTO路由' }).count(), 0)
    console.log('PASS browser: AUTO hybrid, route, current K source, web cards/failure, reload, immutable mode/new CHAT')
  } catch (error) {
    if (page) {
      await page.screenshot({ path: 'logs/r06-browser-failure.png', fullPage: true }).catch(() => {})
      console.error('Browser status: '+await page.getByRole('status').innerText().catch(() => 'unavailable'))
    }
    throw error
  } finally { await browser.close() }
}
main().catch(error => { console.error(error.message); process.exitCode = 1 })
