const { loginForVerification } = require('./browser_auth.cjs')
const { chromium } = require('playwright')
const assert = require('node:assert/strict')
const fs = require('node:fs')
async function main() {
  const browser = await chromium.launch({ headless: true, executablePath: 'C:/Program Files/Google/Chrome/Application/chrome.exe' })
  const page = await browser.newPage()
  try {
    await loginForVerification(page, process.argv[2])
    await page.goto(process.argv[2])
    await page.getByRole('button', { name: '任务与运行', exact: true }).click()
    const queued = page.locator('.task-card').filter({ hasText: process.argv[3] })
    await queued.waitFor()
    assert.equal(await queued.getAttribute('data-status'), 'QUEUED')
    assert.match(await page.getByRole('region', { name: '任务与运行' }).innerText(), /执行中 1\/1 · 排队 1\/2/)
    await queued.getByRole('button', { name: '取消运行', exact: true }).click()
    await page.waitForFunction(id => [...document.querySelectorAll('.task-card')].some(card => card.textContent.includes(id) && card.dataset.status === 'CANCELLED'), process.argv[3])
    if (process.argv[4]) {
      const documentTask = page.locator('.task-card').filter({ hasText: process.argv[4] })
      await documentTask.getByRole('button', { name: '查看尝试记录', exact: true }).click()
      await page.locator('details p').filter({ hasText: /#2 · PUBLISHED · SUCCEEDED/ }).waitFor()
      assert.match(await page.getByRole('region', { name: '任务与运行' }).locator('details').innerText(), /HTTP_503/)
    }
    fs.mkdirSync('logs', { recursive: true })
    await page.screenshot({ path: 'logs/r08-browser.png', fullPage: true })
    await queued.getByRole('button', { name: '查看会话', exact: true }).click()
    await page.locator('#trace-run').waitFor()
    await page.waitForFunction(id => document.querySelector('#trace-run')?.value === id, process.argv[3])
    console.log('PASS browser: capacity, QUEUED status, cancel without model call, open original session'+(process.argv[4] ? ', two document attempts' : ''))
  } catch (error) {
    await page.screenshot({ path: 'logs/r08-browser-failure.png', fullPage: true }).catch(() => {})
    throw error
  } finally { await browser.close() }
}
main().catch(error => { console.error(error.message); process.exitCode = 1 })
