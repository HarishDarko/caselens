import { expect, test } from '@playwright/test'

async function installApiStubs(page: import('@playwright/test').Page) {
  const ticketId = '11111111-1111-1111-1111-111111111111'
  const now = '2026-07-12T04:00:00Z'
  const ticket = { id: ticketId, displayId: 'CL-E2E01', subject: 'Every charger at the demo site appears offline', message: 'All six chargers at synthetic site SITE-DEMO-TOR-07 show Offline since 09:18 UTC. Charger CHG-CA-2001 last reported a heartbeat at 09:16 UTC and three drivers are waiting.', channel: 'EMAIL', status: 'NEW', scenarioKey: 'charger-offline-site-wide', createdAt: now, updatedAt: now }
  const result = { id: '22222222-2222-2222-2222-222222222222', ticketId, category: 'CONNECTIVITY', urgency: 'CRITICAL', slaRisk: 'HIGH', sentiment: 'NEGATIVE', summary: 'All chargers at the synthetic site are offline and drivers are waiting.', evidence: [{ quote: 'All six chargers at synthetic site SITE-DEMO-TOR-07 show Offline since 09:18 UTC.', meaning: 'The issue affects the full synthetic site.' }, { quote: 'three drivers are waiting.', meaning: 'The outage is affecting multiple drivers.' }], policyIds: ['CHARGER_SITE_OUTAGE'], explanation: 'The evidence indicates a site-wide connectivity outage affecting multiple waiting drivers.', recommendedActions: ['Verify the site heartbeat path', 'Escalate the synthetic site outage'], suggestedReply: 'We are reviewing the site-wide charger outage and will update you after the heartbeat path is checked.', reliabilitySignal: 'HIGH', warnings: [], priorityScore: 85, appliedRules: [], decisionSource: 'AI_VALIDATED', modelVersion: 'mock-v1', promptVersion: 'triage-v1', createdAt: now }
  const job = { id: '33333333-3333-3333-3333-333333333333', eventId: '44444444-4444-4444-4444-444444444444', ticketId, status: 'COMPLETED', attemptCount: 1, startedAt: now, completedAt: now, lastErrorCode: null, attempts: [] }
  const evaluation = { generatedAt: now, triageResults: 1, evaluatedResults: 1, categoryAgreementRate: 1, urgencyAgreementRate: 1, agreementRate: 1, correctionRate: 0, medianLatencyMs: 184, p95LatencyMs: 184, failureRate: 0, providerUsage: [{ provider: 'mock', requests: 1, successes: 1, failures: 0, inputTokens: 0, outputTokens: 0 }], recentEvents: [{ resultId: result.id, createdAt: now, latencyMs: 184, provider: 'mock', modelVersion: 'mock-v1', decisionSource: 'AI_VALIDATED', evaluated: true, corrected: false }] }
  const operationsOverview = { generatedAt: now, queue: { queued: 0, processing: 0, completed: 1, retryableFailures: 0, terminalFailures: 0 }, fallbackCount: 0, providerFailureCount: 0, recent: [{ jobId: job.id, ticketId, status: 'COMPLETED', attemptCount: 1, createdAt: now, updatedAt: now, completedAt: now, lastErrorCode: null, provider: 'mock', modelVersion: 'mock-v1', decisionSource: 'AI_VALIDATED', latencyMs: 184 }], providers: [{ provider: 'mock', requests: 1, successes: 1, failures: 0, inputTokens: 0, outputTokens: 0, averageLatencyMs: 184 }] }
  await page.route('**/api/**', async (route) => {
    const request = route.request()
    const url = new URL(request.url())
    let body: unknown
    if (url.pathname.endsWith('/api/demo/session')) body = { token: 'e2e-token', expiresAt: now }
    else if (url.pathname.endsWith('/api/demo/runtime')) body = { environment: 'LOCAL_REVIEW', database: 'POSTGRESQL', queue: 'LOCALSTACK_SQS', aiProvider: 'DETERMINISTIC' }
    else if (url.pathname.endsWith('/api/demo/reset')) body = { seeded: 9 }
    else if (url.pathname.endsWith('/api/tickets') && request.method() === 'GET') body = { content: [], page: 0, size: 50, totalElements: 0, totalPages: 0 }
    else if (url.pathname.endsWith('/api/evaluation')) body = evaluation
    else if (url.pathname.endsWith('/api/operations/overview')) body = operationsOverview
    else if (url.pathname.endsWith('/api/operations/failures')) body = { content: [], page: 0, size: 50, totalElements: 0, totalPages: 0 }
    else if (url.pathname.endsWith('/api/demo/scenarios/charger-offline-site-wide')) body = ticket
    else if (url.pathname.endsWith(`/api/tickets/${ticketId}/triage`)) body = job
    else if (url.pathname.endsWith(`/api/tickets/${ticketId}/processing`)) body = { job, result }
    else if (url.pathname.endsWith(`/api/tickets/${ticketId}/feedback`)) body = { id: '55555555-5555-5555-5555-555555555555', ticketId, originalResultId: result.id, correctedCategory: result.category, correctedUrgency: 'HIGH', correctedSlaRisk: result.slaRisk, note: 'Reviewer corrected urgency during the local demo.', submittedBy: 'demo-reviewer', createdAt: now }
    else return route.continue()
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(body) })
  })
}

test('completes the reviewer journey from queue to measured views', async ({ page }, testInfo) => {
  test.skip(testInfo.project.name !== 'chromium', 'The primary journey is covered at desktop width.')

  await installApiStubs(page)
  await page.goto('/')
  await expect(page.getByRole('heading', { name: 'CaseLens' })).toBeVisible()
  await expect(page.getByText('Demo environment')).toBeVisible()
  await page.getByLabel('Shared demo passcode').fill('reviewer')
  await page.getByRole('button', { name: 'Open reviewer workspace' }).click()

  await expect(page.getByRole('heading', { name: 'Ticket inbox' })).toBeVisible()
  await page.getByRole('button', { name: 'Load charger-offline-site-wide' }).click()
  await expect(page.getByText('Every charger at the demo site appears offline').first()).toBeVisible()
  await page.getByRole('article').filter({ hasText: 'Every charger at the demo site appears offline' }).getByRole('button', { name: /Review CL-/ }).click()
  await expect(page.getByRole('heading', { name: 'Why this ticket is here' })).toBeVisible()
  await page.getByRole('button', { name: 'Mark urgency high' }).click()
  await page.getByLabel('Review note').fill('The synthetic outage affects multiple drivers.')
  await page.getByRole('button', { name: 'Save correction' }).click()
  await expect(page.getByRole('status')).toContainText('Correction saved. Evaluation metrics updated.')

  await page.getByRole('button', { name: 'Evaluation' }).click()
  await expect(page.getByRole('heading', { name: 'Evaluation signal' })).toBeVisible()
  await expect(page.getByText('Agreement', { exact: true })).toBeVisible()
  await expect(page.getByText('Recent triage activity')).toBeVisible()
  await expect(page.getByText('mock-v1')).toBeVisible()
  await page.getByRole('button', { name: 'Operations' }).click()
  await expect(page.getByRole('heading', { name: 'No unresolved failures' })).toBeVisible()
  await expect(page.getByText('Completed jobs')).toBeVisible()
  await expect(page.getByText('Recent processing')).toBeVisible()
})

test('keeps the access and queue flow usable on a phone viewport', async ({ page }, testInfo) => {
  test.skip(testInfo.project.name !== 'mobile', 'The responsive journey is covered in the phone project.')

  await installApiStubs(page)
  await page.goto('/')
  await expect(page.getByRole('heading', { name: 'CaseLens' })).toBeVisible()
  await page.getByLabel('Shared demo passcode').fill('reviewer')
  await page.getByRole('button', { name: 'Open reviewer workspace' }).click()
  await expect(page.getByRole('heading', { name: 'Ticket inbox' })).toBeVisible()
  await expect(page.getByRole('button', { name: 'Evaluation' })).toBeVisible()
  const hasHorizontalOverflow = await page.evaluate(() => document.documentElement.scrollWidth > document.documentElement.clientWidth + 1)
  expect(hasHorizontalOverflow).toBe(false)
  await page.getByRole('button', { name: 'Load charger-offline-site-wide' }).click()
  await page.getByRole('article').filter({ hasText: 'Every charger at the demo site appears offline' }).getByRole('button', { name: /Review CL-/ }).click()
  await expect(page.getByRole('heading', { name: 'Why this ticket is here' })).toBeVisible()
})
