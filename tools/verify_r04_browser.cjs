const { chromium } = require('playwright')
const assert = require('node:assert/strict')
const path = require('node:path')
const fs = require('node:fs')

async function main() {
  const browser = await chromium.launch({ headless: true, executablePath: 'C:/Program Files/Google/Chrome/Application/chrome.exe' })
  let page
  try {
    page = await browser.newPage()
    await page.goto(process.argv[2])
    await page.getByRole('button', { name: '知识库管理', exact: true }).click()
    await page.locator('#kb-name').fill('R04 browser test')
    await page.getByRole('button', { name: '创建知识库', exact: true }).click()
    await page.locator('#document-file').waitFor()
    await page.locator('#document-file').setInputFiles(path.join(process.argv[3], 'r04-source.md'))
    await page.getByRole('button', { name: '上传文档', exact: true }).click()
    await page.waitForFunction(() => document.querySelector('section[aria-label="文档版本详情"]')?.textContent.includes('入库就绪 · 2 个片段 · 已保存 2 条向量'), null, { timeout: 90000 })
    assert.match(await page.locator('[aria-label="文档版本详情"]').innerText(), /已保存 2 条向量/)
    assert.match(await page.getByRole('status').innerText(), /文档已完成入库/)
    await page.locator('.chunk-preview summary').last().click()
    assert.match(await page.locator('.chunk-preview').last().innerText(), /Pine42/)
    await page.reload()
    await page.getByRole('button', { name: '知识库管理', exact: true }).click()
    await page.getByRole('button', { name: 'r04-source.md', exact: true }).click()
    await page.waitForFunction(() => document.querySelector('section[aria-label="文档版本详情"]')?.textContent.includes('入库就绪 · 2 个片段 · 已保存 2 条向量'))
    await page.locator('#document-file').setInputFiles(path.join(process.argv[3], 'r04-pages.pdf'))
    await page.getByRole('button', { name: '上传文档', exact: true }).click()
    await page.waitForFunction(() => document.querySelector('section[aria-label="文档版本详情"]')?.textContent.includes('入库就绪 · 2 个片段 · 已保存 2 条向量')
      && document.querySelector('section[aria-label="文档版本详情"]')?.textContent.includes('r04-pages.pdf'), null, { timeout: 90000 })
    assert.match(await page.locator('.chunk-preview').last().innerText(), /第 2 页/)
    await page.locator('.chunk-preview summary').last().click()
    assert.match(await page.locator('.chunk-preview').last().innerText(), /Pine42/)
    fs.mkdirSync('logs', { recursive: true })
    await page.screenshot({ path: 'logs/r04-browser.png', fullPage: true })
    await page.locator('#document-file').setInputFiles(path.join(process.argv[3], 'r04-empty.pdf'))
    await page.getByRole('button', { name: '上传文档', exact: true }).click()
    await page.waitForFunction(() => document.querySelector('section[aria-label="文档版本详情"]')?.textContent.includes('OCR'))
    assert.match(await page.locator('[aria-label="文档版本详情"]').innerText(), /处理失败/)
    console.log('PASS browser: create KB, Markdown embedding, reload, PDF page source, empty PDF failure')
  } catch (error) {
    if (page) await page.screenshot({ path: 'logs/r04-browser-failure.png', fullPage: true }).catch(() => {})
    throw error
  } finally {
    await browser.close()
  }
}

main().catch(error => { console.error(error.message); process.exitCode = 1 })
