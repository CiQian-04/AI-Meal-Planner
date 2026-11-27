/**
 * Capture a frame-by-frame recording of the real UI for the README GIF.
 *
 * The browser is driven exactly like a user would: type the plan, click generate, wait for the
 * result, tick a shopping list item and export. Frames are captured along the way so the GIF
 * shows the actual application rather than a mock-up.
 */
import { chromium } from 'playwright-core'
import { existsSync, mkdirSync } from 'node:fs'
import { join } from 'node:path'

const BASE_URL = process.env.UI_BASE_URL ?? 'http://127.0.0.1:5173'
const FRAME_DIR = process.env.UI_FRAME_DIR ?? 'C:/tmp/frames'

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

if (!existsSync(FRAME_DIR)) mkdirSync(FRAME_DIR, { recursive: true })

let frameIndex = 0
const capture = async (page, label) => {
  const name = `${String(frameIndex).padStart(3, '0')}-${label}.png`
  await page.screenshot({ path: join(FRAME_DIR, name) })
  console.log(`  frame ${frameIndex}: ${label}`)
  frameIndex++
}

const browser = await chromium.launch({
  executablePath,
  args: ['--no-sandbox', '--disable-dev-shm-usage'],
})

const context = await browser.newContext({
  viewport: { width: 1200, height: 860 },
  deviceScaleFactor: 1.5,
  locale: 'zh-CN',
})
const page = await context.newPage()

try {
  await page.goto(BASE_URL, { waitUntil: 'networkidle', timeout: 30000 })
  await page.waitForTimeout(600)
  await capture(page, 'home')

  // Type the plan, one field at a time so the recording shows real interaction.
  await page.click('#days', { clickCount: 3 })
  await page.type('#days', '3')
  await page.waitForTimeout(250)
  await capture(page, 'days')

  await page.click('#budget', { clickCount: 3 })
  await page.type('#budget', '100')
  await page.waitForTimeout(250)
  await capture(page, 'budget')

  await page.click('#preference')
  await page.type('#preference', '清淡')
  await page.click('button:has-text("添加")')
  await page.waitForTimeout(350)
  await capture(page, 'preference')

  // Generate the plan.
  await page.click('button[type="submit"]')
  await page.waitForTimeout(700)
  await capture(page, 'loading')

  await page.waitForSelector('text=菜单总览', { timeout: 60000 })
  await page.waitForTimeout(900)
  await capture(page, 'overview')

  // Scroll to the recipe cards.
  await page.locator('.day-header').first().scrollIntoViewIfNeeded()
  await page.waitForTimeout(600)
  await capture(page, 'recipes')

  // Scroll through the second and third day.
  await page.locator('.day-header').nth(1).scrollIntoViewIfNeeded()
  await page.waitForTimeout(600)
  await capture(page, 'recipes-2')

  // Scroll to the shopping list.
  await page.locator('.panel-title:has-text("购物清单")').scrollIntoViewIfNeeded()
  await page.waitForTimeout(600)
  await capture(page, 'shopping')

  // Tick a few shopping list items.
  const items = page.locator('.shop-item')
  const count = Math.min(4, await items.count())
  for (let index = 0; index < count; index++) {
    await items.nth(index).locator('input[type="checkbox"]').check()
    await page.waitForTimeout(220)
  }
  await capture(page, 'checked')

  // Export the shopping list.
  await page.click('button:has-text("下载 TXT")')
  await page.waitForTimeout(900)
  await capture(page, 'exported')

  // Back to the top for a closing frame.
  await page.evaluate(() => window.scrollTo({ top: 0, behavior: 'smooth' }))
  await page.waitForTimeout(800)
  await capture(page, 'final')
} catch (error) {
  console.error('Recording failed:', error)
  process.exitCode = 1
} finally {
  await browser.close()
}

console.log(`\n${frameIndex} frames written to ${FRAME_DIR}`)
