// Time from navigation to an enabled "Launch live demo" button, with GET /api/demo/session delayed to act like a
// cold API. Serves a built dist folder through Playwright routes (no server). Usage:
//   node measure-launch.mjs <distDir> <label> <getDelayMs> <runs>
import { chromium } from '@playwright/test'
import { readFile } from 'node:fs/promises'
import path from 'node:path'

const [distDir, label, delayArg, runsArg] = process.argv.slice(2)
const delayMs = Number(delayArg)
const runs = Number(runsArg)
const types = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.json': 'application/json', '.svg': 'image/svg+xml', '.png': 'image/png' }
const options = { defaultIndustry: 'ev-charging', industries: [{ id: 'ev-charging', name: 'EV charging', featuredScenario: { key: 'charger-offline-site-wide', label: 'x' }, scenarios: [{ key: 'charger-offline-site-wide', label: 'x' }] }] }

const browser = await chromium.launch()
const results = []
for (let run = 1; run <= runs; run++) {
  const page = await browser.newPage()
  await page.route('**/*', async (route) => {
    const url = new URL(route.request().url())
    if (url.pathname.startsWith('/api/demo/session') && route.request().method() === 'GET') {
      await new Promise((resolve) => setTimeout(resolve, delayMs))
      return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(options) })
    }
    if (url.pathname.startsWith('/api/')) return route.fulfill({ status: 503, body: '' })
    const file = url.pathname === '/' ? 'index.html' : url.pathname.slice(1)
    try {
      const body = await readFile(path.join(distDir, file))
      return route.fulfill({ status: 200, contentType: types[path.extname(file)] ?? 'application/octet-stream', body })
    } catch {
      return route.fulfill({ status: 404, body: '' })
    }
  })
  await page.goto('http://caselens.test/')
  await page.getByRole('button', { name: 'Launch live demo' }).waitFor()
  const enabledAt = await page.evaluate(() => new Promise((resolve) => {
    const button = [...document.querySelectorAll('button')].find((item) => item.textContent === 'Launch live demo')
    const check = () => { if (button && !button.disabled) resolve(performance.now()); else requestAnimationFrame(check) }
    check()
  }))
  const firstPaint = await page.evaluate(() => performance.getEntriesByName('first-contentful-paint')[0]?.startTime ?? null)
  results.push({ run, enabledMs: Math.round(enabledAt), fcpMs: firstPaint === null ? null : Math.round(firstPaint) })
  await page.close()
}
await browser.close()
const sorted = results.map((item) => item.enabledMs).sort((a, b) => a - b)
console.log(JSON.stringify({ label, getDelayMs: delayMs, runs, results, medianEnabledMs: sorted[Math.floor(sorted.length / 2)] }))
