/** 本机浏览器验收；由 verify_r03.py 启动隔离后端和前端，不操作用户的 IDEA 进程。 */
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
    await page.locator('#message').fill(process.argv[3] === 'live'
      ? '请调用 web_fetch 抓取 https://example.com/ ，仅根据抓取正文用中文说明这个网站的用途，并引用来源编号。'
      : 'fetch')
    await page.getByRole('button', { name: '发送', exact: true }).click()
    await page.locator('.tool-card[data-status="SUCCEEDED"]').first().waitFor({ timeout: 100000 })
    await page.getByRole('button', { name: '发送', exact: true }).waitFor()
    await page.waitForFunction(() => !document.querySelector('#message').disabled, null, { timeout: 100000 })
    await page.waitForFunction(() => document.querySelector('section[aria-label="历史消息"]').innerText.includes('[W1]'), null, { timeout: 10000 })
    assert.match(await page.locator('section[aria-label="历史消息"]').innerText(), /\[W1\]/)
    assert.match(await page.locator('.tool-card').innerText(), /已抓取正文/)
    assert.equal(await page.locator('.tool-card a').first().getAttribute('href'), 'https://example.com/')
    await page.reload()
    await page.locator('.tool-card[data-status="SUCCEEDED"]').first().waitFor()
    await page.waitForFunction(() => document.querySelector('section[aria-label="历史消息"]').innerText.includes('[W1]'))
    assert.match(await page.locator('.tool-card').innerText(), /Example Domain/)
    assert.match(await page.locator('section[aria-label="历史消息"]').innerText(), /\[W1\]/)
    if (process.argv[3] !== 'live') {
      await page.locator('#message').fill('blocked')
      await page.getByRole('button', { name: '发送', exact: true }).click()
      await page.locator('.tool-card[data-status="FAILED"]').first().waitFor()
      await page.waitForFunction(() => !document.querySelector('#message').disabled)
      assert.equal(await page.locator('.tool-card a').count(), 0)
    }
    fs.mkdirSync('logs', { recursive: true })
    await page.screenshot({ path: 'logs/r03-browser.png', fullPage: true })
    console.log(`PASS browser (${process.argv[3]}): tool card, source link, cited answer, reload persistence`)
  } catch (error) {
    if (page) {
      fs.mkdirSync('logs', { recursive: true })
      await page.screenshot({ path: 'logs/r03-browser-failure.png', fullPage: true }).catch(() => {})
      console.error('Browser status: ' + await page.getByRole('status').textContent().catch(() => 'unavailable'))
    }
    throw error
  } finally {
    await browser.close()
  }
}

main().catch(error => {
  console.error(error.message)
  process.exitCode = 1
})
