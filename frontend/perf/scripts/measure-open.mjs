// Time from the "Launch live demo" click to the first ticket row in the inbox, and the API requests the page sends
// for it, with every API answer delayed to act like a slow API. Serves a built dist folder through Playwright routes
// (no server). The stub API answers both the old open flow (session, reset, then four reads) and a session that
// seeds the workspace when asked (POST /api/demo/session?seed=true), so old and new bundles run in the same harness.
// Usage:
//   node measure-open.mjs <distDir> <label> <apiDelayMs> <runs>
import { chromium } from '@playwright/test'
import { readFile } from 'node:fs/promises'
import path from 'node:path'

const [distDir, label, delayArg, runsArg] = process.argv.slice(2)
const delayMs = Number(delayArg)
const runs = Number(runsArg)
const types = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.json': 'application/json', '.svg': 'image/svg+xml', '.png': 'image/png' }
const now = '2026-11-10T12:00:00Z'
const options = { defaultIndustry: 'ev-charging', industries: [{ id: 'ev-charging', name: 'EV charging', featuredScenario: { key: 'charger-offline-site-wide', label: 'Every charger at the demo site appears offline' }, scenarios: [{ key: 'charger-offline-site-wide', label: 'Every charger at the demo site appears offline' }] }] }
const tickets = Array.from({ length: 9 }, (_, index) => ({ id: `00000000-0000-0000-0000-00000000000${index}`, displayId: `CL-P10${index}`, subject: `Synthetic ticket ${index}`, message: 'A synthetic support message for the open-workspace measurement.', channel: 'EMAIL', status: 'NEW', scenarioKey: `scenario-${index}`, createdAt: now, updatedAt: now }))
const runtime = { environment: 'AWS_DEMO', database: 'NEON_POSTGRESQL', queue: 'AMAZON_SQS', aiProvider: 'GROQ' }
const emptyPage = { content: [], page: 0, size: 50, totalElements: 0, totalPages: 0 }
const evaluation = { generatedAt: now, triageResults: 0, evaluatedResults: 0, categoryAgreementRate: 0, urgencyAgreementRate: 0, agreementRate: 0, correctionRate: 0, medianLatencyMs: 0, p95LatencyMs: 0, failureRate: 0, providerUsage: [], recentEvents: [] }

function apiBody(method, url) {
  if (url.pathname === '/api/demo/session' && method === 'GET') return options
  if (url.pathname === '/api/demo/session' && method === 'POST') {
    const session = { token: 'perf-token', expiresAt: now, industry: 'ev-charging' }
    return url.searchParams.get('seed') === 'true' ? { ...session, tickets, runtime } : session
  }
  if (url.pathname === '/api/demo/reset') return { seeded: tickets.length }
  if (url.pathname === '/api/tickets' && method === 'GET') return { content: tickets, page: 0, size: 50, totalElements: tickets.length, totalPages: 1 }
  if (url.pathname === '/api/demo/runtime') return runtime
  if (url.pathname === '/api/evaluation') return evaluation
  if (url.pathname === '/api/operations/failures') return emptyPage
  return null
}

const browser = await chromium.launch()
const results = []
for (let run = 1; run <= runs; run++) {
  const page = await browser.newPage()
  const requests = []
  let clicked = false
  await page.route('**/*', async (route) => {
    const request = route.request()
    const url = new URL(request.url())
    if (url.pathname.startsWith('/api/')) {
      if (clicked) requests.push(`${request.method()} ${url.pathname}${url.search}`)
      await new Promise((resolve) => setTimeout(resolve, delayMs))
      const body = apiBody(request.method(), url)
      return body === null ? route.fulfill({ status: 404, body: '' }) : route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(body) })
    }
    const file = url.pathname === '/' ? 'index.html' : url.pathname.slice(1)
    try {
      const body = await readFile(path.join(distDir, file))
      return route.fulfill({ status: 200, contentType: types[path.extname(file)] ?? 'application/octet-stream', body })
    } catch {
      return route.fulfill({ status: 404, body: '' })
    }
  })
  await page.goto('http://caselens.test/')
  const launch = page.getByRole('button', { name: 'Launch live demo' })
  await launch.waitFor()
  await page.waitForFunction(() => [...document.querySelectorAll('button')].some((item) => item.textContent === 'Launch live demo' && !item.disabled))
  // Let the background industry-list call finish, so only the open flow is counted.
  await page.waitForTimeout(delayMs + 200)
  await page.evaluate(() => {
    window.__openPaint = new Promise((resolve) => {
      const seen = () => document.querySelector('.ticket-row')
      const observer = new MutationObserver(() => { if (seen()) { observer.disconnect(); resolve(performance.now()) } })
      observer.observe(document.body, { childList: true, subtree: true })
    })
  })
  clicked = true
  // Click inside the page, so the time starts at the click itself and not at Playwright's actionability checks.
  const clickedAt = await page.evaluate(() => {
    const button = [...document.querySelectorAll('button')].find((item) => item.textContent === 'Launch live demo')
    const at = performance.now()
    button.click()
    return at
  })
  const paintedAt = await page.evaluate(() => window.__openPaint)
  const requestsBeforePaint = requests.length
  // Count any request the open flow sends after the inbox is on screen too.
  await page.waitForTimeout(delayMs * 2 + 500)
  results.push({ run, inboxMs: Math.round(paintedAt - clickedAt), requestsBeforePaint, requests: [...requests] })
  await page.close()
}
await browser.close()
const sorted = results.map((item) => item.inboxMs).sort((a, b) => a - b)
console.log(JSON.stringify({ label, apiDelayMs: delayMs, runs, results, medianInboxMs: sorted[Math.floor(sorted.length / 2)], minInboxMs: sorted[0], maxInboxMs: sorted[sorted.length - 1] }))
