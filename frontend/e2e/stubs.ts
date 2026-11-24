import { readFileSync } from 'node:fs'
import type { Page } from '@playwright/test'

type Workspace = { industry: string; tickets: Array<{ id: string; scenarioKey: string }>; live: Record<string, unknown> }
const workspace = JSON.parse(readFileSync(new URL('./ev-workspace.json', import.meta.url), 'utf-8')) as Workspace

// An EV charging workspace as the API sends it once recorded results ship: 3 tickets with a recorded result and 5 to
// triage live. The recorded and live results are real model outputs captured from the deployed pipeline on
// 2026-11-23 (the rules-fallback reason on the connector ticket is set for the test). Nothing here calls a real API.
export const now = '2026-11-23T20:45:00Z'
const industries = [
  { id: 'ev-charging', name: 'EV charging', featuredScenario: { key: 'charger-offline-site-wide', label: 'Every charger at the demo site appears offline' }, scenarios: [{ key: 'charger-offline-site-wide', label: 'Every charger at the demo site appears offline' }] },
  { id: 'e-commerce', name: 'E-commerce', featuredScenario: { key: 'shop-checkout-down', label: 'Every card fails at checkout' }, scenarios: [{ key: 'shop-checkout-down', label: 'Every card fails at checkout' }] },
  { id: 'saas-support', name: 'SaaS support', featuredScenario: { key: 'saas-sso-lockout', label: 'Whole team locked out after an SSO change' }, scenarios: [{ key: 'saas-sso-lockout', label: 'Whole team locked out after an SSO change' }] },
]
const runtime = { environment: 'AWS_DEMO', database: 'NEON_POSTGRESQL', queue: 'AMAZON_SQS', aiProvider: 'GROQ' }
const evaluation = { generatedAt: now, triageResults: 5, evaluatedResults: 2, categoryAgreementRate: 1, urgencyAgreementRate: 0.5, agreementRate: 0.5, correctionRate: 0.5, medianLatencyMs: 2880, p95LatencyMs: 2880, failureRate: 0, providerUsage: [{ provider: 'groq', requests: 2, successes: 2, failures: 0, inputTokens: 2400, outputTokens: 900 }], recentEvents: [], reviewedResults: 2, acceptedResults: 1, acceptanceRate: 0.5, overrideRate: 0.5, recordedResults: 3 }
const recentJob = (id: string, decisionSource: string, latencyMs: number) => ({ jobId: `job-${id}`, ticketId: id, status: 'COMPLETED', attemptCount: 1, createdAt: now, updatedAt: now, completedAt: now, lastErrorCode: null, provider: 'groq', modelVersion: 'openai/gpt-oss-20b', decisionSource, latencyMs })
const operationsOverview = { generatedAt: now, queue: { queued: 0, processing: 0, completed: 2, retryableFailures: 0, terminalFailures: 0 }, fallbackCount: 1, providerFailureCount: 0, recent: [recentJob('00000000-0000-4000-8000-000000000005', 'RULES_FALLBACK', 3120), recentJob('00000000-0000-4000-8000-000000000004', 'AI_VALIDATED', 2880)], providers: [{ provider: 'groq', requests: 2, successes: 2, failures: 0, inputTokens: 2400, outputTokens: 900, averageLatencyMs: 2950 }] }
const retryTicket = { id: '00000000-0000-4000-8000-000000000009', displayId: 'CL-9D2A01', subject: 'Synthetic provider timeout for retry review', message: 'A synthetic ticket used to show failure recovery.', channel: 'WEB', status: 'NEW', scenarioKey: 'provider-retry-demo', createdAt: now, updatedAt: now }
const syntheticFailure = { id: 'job-retry-1', eventId: 'event-retry-1', ticketId: retryTicket.id, status: 'RETRYABLE_FAILURE', attemptCount: 1, startedAt: now, completedAt: null, lastErrorCode: 'SYNTHETIC_PROVIDER_TIMEOUT', attempts: [] }

type Live = Record<string, { job: Record<string, unknown>; result: Record<string, unknown> }>

/** Routes every /api call. queuedFor keeps the named tickets queued, to show the live progress and cold-start states. */
export async function installApiStubs(page: Page, { queuedPolls = 1, stuckTickets = [] as string[] } = {}) {
  const polls = new Map<string, number>()
  // The retry demo: its ticket fails on its first triage and shows in the failures list until it is retried.
  let retryFailed = false
  const live = workspace.live as Live
  await page.route('**/api/**', async (route) => {
    const request = route.request()
    const url = new URL(request.url())
    const path = url.pathname
    const ticketMatch = path.match(/\/api\/tickets\/([^/]+)\/(triage|processing|feedback)$/)
    let body: unknown
    let status = 200
    if (path.endsWith('/api/demo/session') && request.method() === 'GET') body = { defaultIndustry: 'ev-charging', industries }
    else if (path.endsWith('/api/demo/session')) body = { token: 'e2e-token', expiresAt: now, industry: workspace.industry, tickets: workspace.tickets, runtime }
    else if (path.endsWith('/api/evaluation')) body = evaluation
    else if (path.endsWith('/api/operations/overview')) body = operationsOverview
    else if (path.endsWith('/api/operations/failures')) body = retryFailed ? { content: [syntheticFailure], page: 0, size: 50, totalElements: 1, totalPages: 1 } : { content: [], page: 0, size: 50, totalElements: 0, totalPages: 0 }
    else if (path.endsWith('/api/operations/failures/job-retry-1/retry')) { retryFailed = false; body = { ...syntheticFailure, status: 'QUEUED', lastErrorCode: null } }
    else if (path.endsWith('/api/demo/scenarios/provider-retry-demo')) { status = 201; body = retryTicket }
    else if (path.endsWith(`/api/tickets/${retryTicket.id}/triage`)) { retryFailed = true; status = 202; body = { ...syntheticFailure, status: 'QUEUED', lastErrorCode: null } }
    else if (ticketMatch?.[2] === 'triage') { status = 202; body = { id: `job-${ticketMatch[1]}`, eventId: `event-${ticketMatch[1]}`, ticketId: ticketMatch[1], status: 'QUEUED', attemptCount: 0, startedAt: null, completedAt: null, lastErrorCode: null, attempts: [] } }
    else if (ticketMatch?.[2] === 'processing') {
      const id = ticketMatch[1]
      const count = (polls.get(id) ?? 0) + 1
      polls.set(id, count)
      const queued = { id: `job-${id}`, eventId: `event-${id}`, ticketId: id, status: 'QUEUED', attemptCount: 0, startedAt: null, completedAt: null, lastErrorCode: null, attempts: [] }
      if (stuckTickets.includes(id) || !live[id]) body = { job: queued, result: null }
      else if (count <= queuedPolls) body = { job: { ...queued, status: count === queuedPolls ? 'PROCESSING' : 'QUEUED' }, result: null }
      else body = live[id]
    }
    else if (ticketMatch?.[2] === 'feedback') {
      status = 201
      const sent = JSON.parse(request.postData() ?? '{}') as { decision?: string; urgency?: string; note?: string; originalResultId?: string }
      body = { id: crypto.randomUUID(), ticketId: ticketMatch[1], originalResultId: sent.originalResultId, correctedCategory: null, correctedUrgency: sent.urgency ?? null, correctedSlaRisk: null, note: sent.note ?? null, submittedBy: 'demo-reviewer', createdAt: now, decision: sent.decision ?? 'CORRECTED' }
    }
    else return route.continue()
    await route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) })
  })
}

export const ticketIds = Object.fromEntries(workspace.tickets.map((ticket) => [ticket.scenarioKey, ticket.id])) as Record<string, string>
