/**
 * End-to-end UI verification with a real browser.
 *
 * Drives the actual Vue application against the running backend, exercises the flow a user
 * would follow (fill in the form, generate a plan, tick shopping list items, export) and
 * captures screenshots for the README.
 *
 * The system Chrome/Edge is reused instead of downloading a browser bundle.
 */
import { chromium } from 'playwright-core'
import { existsSync } from 'node:fs'
import { mkdirSync } from 'node:fs'
import { join } from 'node:path'

const BASE_URL = process.env.UI_BASE_URL ?? 'http://127.0.0.1:5173'
const OUT_DIR = process.env.UI_SHOT_DIR ?? 'C:/tmp/shots'

const BROWSER_CANDIDATES = [
  'C:/Program Files/Google/Chrome/Application/chrome.exe',
  'C:/Program Files (x86)/Google/Chrome/Application/chrome.exe',
  'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',
  'C:/Program Files/Microsoft/Edge/Application/msedge.exe',
]

const executablePath = BROWSER_CANDIDATES.find((path) => existsSync(path))
if (!executablePath) {
  console.error('No Chrome or Edge installation found')
  process.exit(1)
}

if (!existsSync(OUT_DIR)) mkdirSync(OUT_DIR, { recursive: true })

const failures = []
const check = (condition, message) => {
  console.log(`  [${condition ? 'PASS' : 'FAIL'}] ${message}`)
  if (!condition) failures.push(message)
}

const browser = await chromium.launch({
  executablePath,
  args: ['--no-sandbox', '--disable-dev-shm-usage'],
})

const context = await browser.newContext({
  viewport: { width: 1280, height: 900 },
  deviceScaleFactor: 1.5,
  locale: 'zh-CN',
})
const page = await context.newPage()

const consoleErrors = []
page.on('console', (message) => {
  if (message.type() === 'error') consoleErrors.push(message.text())
})
page.on('pageerror', (error) => consoleErrors.push(String(error)))

try {
  console.log('=== 打开首页 ===')
  await page.goto(BASE_URL, { waitUntil: 'networkidle', timeout: 30000 })
  await page.screenshot({ path: join(OUT_DIR, '01-home.png'), fullPage: true })
  check(await page.locator('h1').innerText() === 'AI 菜谱规划师', '首页标题正确')

  console.log('=== 填写规划条件 ===')
  await page.fill('#days', '3')
  await page.fill('#budget', '100')
  await page.fill('#meals', '3')
  await page.fill('#servings', '2')
  // "不吃辣" 开关默认开启，这里显式确认一次状态
  const spicyChecked = await page.locator('.switch input').isChecked()
  check(spicyChecked, '「不吃辣」默认开启')
  await page.fill('#preference', '清淡')
  await page.click('button:has-text("添加")')
  await page.waitForTimeout(200)
  check((await page.locator('.chip').count()) > 0, '口味偏好已添加为标签')

  console.log('=== 点击生成菜谱 ===')
  await page.click('button[type="submit"]')
  // 等待结果面板出现
  await page.waitForSelector('text=菜单总览', { timeout: 60000 })
  await page.waitForTimeout(800)
  await page.screenshot({ path: join(OUT_DIR, '02-plan.png'), fullPage: true })

  const dayHeaders = await page.locator('.day-header').count()
  check(dayHeaders === 3, `显示 3 天菜单（实际 ${dayHeaders}）`)

  const recipeCards = await page.locator('.recipe-card').count()
  check(recipeCards === 9, `显示 9 道菜（实际 ${recipeCards}）`)

  const spicyTags = await page.locator('.tag.spicy-none').count()
  check(spicyTags === 9, `每道菜都有「不辣」标记（实际 ${spicyTags}）`)

  const shoppingItems = await page.locator('.shop-item').count()
  check(shoppingItems > 0, `购物清单有 ${shoppingItems} 项`)

  console.log('=== 检查工具调用展示 ===')
  const toolCalls = await page.locator('.tool-call').count()
  check(toolCalls > 0, `展示了模型发起的函数调用（${toolCalls} 次）`)
  const toolName = await page.locator('.tool-call .name').first().innerText()
  check(toolName.includes('lookupIngredientPrice'), `函数名正确（${toolName}）`)

  console.log('=== 勾选购物清单 ===')
  const firstItem = page.locator('.shop-item').first()
  await firstItem.locator('input[type="checkbox"]').check()
  await page.waitForTimeout(300)
  const checkedClass = await firstItem.locator('.shop-name').getAttribute('class')
  check(checkedClass?.includes('checked'), '勾选后条目显示删除线')
  await page.screenshot({ path: join(OUT_DIR, '03-shopping.png'), fullPage: true })

  console.log('=== 导出购物清单 ===')
  const downloadPromise = page.waitForEvent('download', { timeout: 15000 })
  await page.click('button:has-text("下载 TXT")')
  const download = await downloadPromise
  const filename = download.suggestedFilename()
  check(filename.endsWith('.txt'), `下载文件名正确（${filename}）`)
  const downloadPath = await download.path()
  check(Boolean(downloadPath), '购物清单文件已下载')

  const notice = await page.locator('text=已下载购物清单').count()
  check(notice > 0, '界面显示导出成功提示')

  console.log('=== 预算提示 ===')
  const budgetValue = await page.locator('.stat-value').nth(1).innerText()
  check(budgetValue.includes('¥'), `显示实际预估成本（${budgetValue}）`)

  check(consoleErrors.length === 0, `无控制台错误${consoleErrors.length ? '：' + consoleErrors[0] : ''}`)

  await page.screenshot({ path: join(OUT_DIR, '04-final.png'), fullPage: true })
} catch (error) {
  console.error('验证过程中出错:', error)
  await page.screenshot({ path: join(OUT_DIR, 'error.png'), fullPage: true }).catch(() => {})
  failures.push(String(error))
} finally {
  await browser.close()
}

console.log('\n============================')
if (failures.length === 0) {
  console.log('  UI 验证全部通过')
} else {
  console.log(`  ${failures.length} 项失败：`)
  failures.forEach((f) => console.log(`   - ${f}`))
}
console.log('============================')
process.exit(failures.length === 0 ? 0 : 1)
