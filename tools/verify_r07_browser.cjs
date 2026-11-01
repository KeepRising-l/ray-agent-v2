const {openNewSession}=require('./ui_navigation.cjs')
const { loginForVerification } = require('./browser_auth.cjs')
const { chromium } = require('playwright')
const assert = require('node:assert/strict')
const fs = require('node:fs')
async function main() {
  const browser = await chromium.launch({ headless: true, executablePath: 'C:/Program Files/Google/Chrome/Application/chrome.exe' })
  let page
  try {
    page = await browser.newPage()
    await loginForVerification(page, process.argv[2])
    await page.goto(process.argv[2])
    await page.locator('#channel option').first().waitFor({ state: 'attached' })
    await openNewSession(page)
    await page.locator('#new-mode').selectOption('CHAT')
    await page.getByRole('button', { name: '新建会话', exact: true }).click()
    await page.waitForFunction(() => !document.querySelector('#message').disabled)
    await page.locator('#message').fill('请单独调用ask_user问我目标城市，等我回答后再写一句欢迎语；当前城市尚未提供。')
    await page.getByRole('button', { name: '发送', exact: true }).click()
    await page.getByRole('heading', { name: '需要你补充信息' }).waitFor({ timeout: 100000 })
    await page.reload()
    await page.getByRole('heading', { name: '需要你补充信息' }).waitFor()
    assert.ok(await page.locator('.tool-card[data-status="WAITING_USER"]').count() > 0)
    const runId = await page.locator('#trace-run').inputValue()
    assert.ok(runId)
    await page.locator('#user-answer').fill('苏州')
    await page.getByRole('button', { name: '回答并继续', exact: true }).click()
    await page.waitForFunction(() => document.querySelector('section[aria-label="历史消息"]').innerText.includes('苏州') && !document.querySelector('#message').disabled, null, { timeout: 100000 })
    assert.equal(await page.locator('#trace-run').inputValue(), runId)
    assert.equal(await page.locator('#trace-run option').count(), 1)
    assert.match(await page.getByRole('region', { name: '待补充信息' }).innerText(), /你已补充：苏州/)
    assert.ok(await page.locator('.tool-card[data-status="SUCCEEDED"]').count() > 0)
    assert.equal(await page.locator('.tool-card[data-status="FAILED"]').count(), 0)
    fs.mkdirSync('logs', { recursive: true })
    await page.screenshot({ path: 'logs/r07-browser.png', fullPage: true })
    console.log('PASS browser: waiting form, refresh restores question, answer continues same run, user supplement shown')
  } catch (error) {
    if (page) {
      await page.screenshot({ path: 'logs/r07-browser-failure.png', fullPage: true }).catch(() => {})
      console.error('Browser status: '+await page.getByRole('status').innerText().catch(() => 'unavailable'))
    }
    throw error
  } finally { await browser.close() }
}
main().catch(error => { console.error(error.message); process.exitCode=1 })
