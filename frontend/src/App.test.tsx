import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, beforeEach, expect, test, vi } from 'vitest'
import App from './App'

const apiTicket = {
  id: 'ticket-a102', displayId: 'CL-A102', subject: 'Payment accepted but charging did not start',
  message: 'The driver was charged, but the session never began at the station.', channel: 'EMAIL',
  status: 'OPEN', scenarioKey: 'payment-without-session', createdAt: '2026-07-11T09:14:00Z', updatedAt: '2026-07-11T09:14:00Z',
}

function response(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

const sessionOptions = {
  defaultIndustry: 'ev-charging',
  industries: [
    { id: 'ev-charging', name: 'EV charging', featuredScenario: { key: 'charger-offline-site-wide', label: 'Every charger at the demo site appears offline' }, scenarios: [{ key: 'charger-offline-site-wide', label: 'Every charger at the demo site appears offline' }] },
    { id: 'e-commerce', name: 'E-commerce', featuredScenario: { key: 'shop-checkout-down', label: 'Every card fails at checkout' }, scenarios: [{ key: 'shop-checkout-down', label: 'Every card fails at checkout' }] },
    { id: 'saas-support', name: 'SaaS support', featuredScenario: { key: 'saas-sso-lockout', label: 'Whole team locked out after an SSO change' }, scenarios: [{ key: 'saas-sso-lockout', label: 'Whole team locked out after an SSO change' }] },
  ],
}

type PollStep = 'QUEUED' | 'PROCESSING' | 'RETRYABLE_FAILURE' | 'TERMINAL_FAILURE' | 'SYNTHETIC_FAILURE' | 429
type BackendOptions = { syntheticFailure?: boolean; pendingProcessingPolls?: number; pollSteps?: PollStep[]; industriesStatus?: number; industries?: unknown; sessionStatuses?: number[]; laterEvaluation?: () => Promise<Response> }

function pendingJob(status: string, lastErrorCode: string | null = null, ticketId = 'ticket-a102') {
  return response({ job: { id: 'job-1', eventId: 'event-1', ticketId, status, attemptCount: status === 'QUEUED' ? 0 : 1, startedAt: null, completedAt: null, lastErrorCode, attempts: [] }, result: null })
}

// A result as the API sends it before recorded results: no recorded, fallbackReason, labels or policies.
const legacyResult = { id: 'result-1', ticketId: 'ticket-a102', category: 'CHARGING_SESSION', urgency: 'CRITICAL', slaRisk: 'HIGH', sentiment: 'NEGATIVE', summary: 'Payment was accepted but no session started.', evidence: [{ quote: 'The driver was charged', meaning: 'The ticket reports a payment without a session start.' }], policyIds: ['payment-follow-up'], explanation: 'The payment signal and missing session start are the strongest evidence.', recommendedActions: ['Confirm the authorization outcome.'], suggestedReply: 'We are reviewing the session start.', reliabilitySignal: 'HIGH', warnings: [], priorityScore: 82, appliedRules: [{ code: 'URGENCY_CRITICAL', points: 55, explanation: 'Critical urgency baseline' }, { code: 'SLA_RISK_HIGH', points: 20, explanation: 'High risk of breaching the response target' }], decisionSource: 'AI_VALIDATED', modelVersion: 'openai/gpt-oss-20b', promptVersion: 'triage-v1', createdAt: '2026-07-11T09:14:02Z' }
const completedJob = { id: 'job-1', eventId: 'event-1', ticketId: 'ticket-a102', status: 'COMPLETED', attemptCount: 1, startedAt: '2026-07-11T09:14:01Z', completedAt: '2026-07-11T09:14:02Z', lastErrorCode: null, attempts: [{ id: 'attempt-1', jobId: 'job-1', attemptNumber: 1, status: 'COMPLETED', startedAt: '2026-07-11T09:14:01Z', completedAt: '2026-07-11T09:14:02Z', errorCode: null }] }

// The session POST may carry a query string (?seed=true asks the API to seed the workspace in the same call).
function isSessionPost(url: string, init?: RequestInit) {
  return new URL(url, 'http://test').pathname === '/api/demo/session' && init?.method === 'POST'
}

function mockBackend(options: BackendOptions = {}) {
  let pendingPolls = options.pendingProcessingPolls ?? 0
  const pollSteps = [...(options.pollSteps ?? [])]
  const sessionStatuses = [...(options.sessionStatuses ?? [])]
  let evaluationCalls = 0
  const fetchMock = vi.fn().mockImplementation(async (input: RequestInfo | URL, init?: RequestInit) => {
    if (input === undefined) return response({})
    const url = String(input)
    if (url.endsWith('/api/demo/session') && (init?.method ?? 'GET') === 'GET') return options.industriesStatus ? response({ detail: 'Unavailable' }, options.industriesStatus) : response(options.industries ?? sessionOptions)
    if (isSessionPost(url, init) && sessionStatuses.length) return response({ detail: 'Request failed with status 429' }, sessionStatuses.shift())
    if (isSessionPost(url, init)) return response({ token: 'demo-token', expiresAt: '2026-07-12T00:00:00Z' })
    if (url.endsWith('/api/demo/reset')) return response({ seeded: 9 })
    if (url.endsWith('/api/demo/runtime')) return response({ environment: 'LOCAL_REVIEW', database: 'NEON_POSTGRESQL', queue: 'LOCALSTACK_SQS', aiProvider: 'GROQ' })
    if (url.includes('/api/demo/scenarios/provider-retry-demo')) return response({ ...apiTicket, id: 'ticket-a105', displayId: 'CL-A105', subject: 'Synthetic provider timeout for retry review', scenarioKey: 'provider-retry-demo' }, 201)
    if (url.includes('/api/demo/scenarios/')) return response({ ...apiTicket, id: 'ticket-a104', displayId: 'CL-A104', subject: 'Every charger at the demo site appears offline', scenarioKey: 'charger-offline-site-wide' }, 201)
    if (url.includes('/api/tickets?page=')) return response({ content: [apiTicket], page: 0, size: 50, totalElements: 1, totalPages: 1 })
    if (url.includes('/processing') && pollSteps.length) {
      const step = pollSteps.shift()
      if (step === 429) return new Response('', { status: 429 })
      if (step === 'SYNTHETIC_FAILURE') return pendingJob('RETRYABLE_FAILURE', 'SYNTHETIC_PROVIDER_TIMEOUT')
      return pendingJob(String(step), step === 'RETRYABLE_FAILURE' || step === 'TERMINAL_FAILURE' ? 'PROVIDER_TIMEOUT' : null)
    }
    if (url.includes('/processing') && pendingPolls > 0) { pendingPolls -= 1; return pendingJob('QUEUED') }
    if (url.includes('/processing')) return response({ job: completedJob, result: legacyResult })
    if (url.includes('/triage')) return response({ id: 'job-1', eventId: 'event-1', ticketId: 'ticket-a102', status: 'QUEUED', attemptCount: 0, startedAt: null, completedAt: null, lastErrorCode: null, attempts: [] }, 202)
    if (url.includes('/feedback')) return response({ id: 'feedback-1' }, 201)
    if (url.endsWith('/api/evaluation') && options.laterEvaluation && evaluationCalls++ > 0) return options.laterEvaluation()
    if (url.endsWith('/api/evaluation')) return response({ generatedAt: '2026-07-11T09:14:02Z', triageResults: 1, evaluatedResults: 1, categoryAgreementRate: 1, urgencyAgreementRate: 1, agreementRate: 1, correctionRate: 0, medianLatencyMs: 184, p95LatencyMs: 220, failureRate: 0, providerUsage: [], recentEvents: [{ resultId: 'result-1', createdAt: '2026-07-11T09:14:02Z', latencyMs: 184, provider: 'groq', modelVersion: 'openai/gpt-oss-20b', decisionSource: 'AI_VALIDATED', evaluated: true, corrected: false }] })
    if (url.endsWith('/api/operations/overview')) return response({ generatedAt: '2026-07-11T09:14:02Z', queue: { queued: 0, processing: 0, completed: 1, retryableFailures: options.syntheticFailure ? 1 : 0, terminalFailures: 0 }, fallbackCount: 0, providerFailureCount: options.syntheticFailure ? 1 : 0, recent: [], providers: [] })
    if (url.includes('/api/operations/failures')) return response(options.syntheticFailure
      ? { content: [{ id: 'job-retry-1', eventId: 'event-retry-1', ticketId: 'ticket-retry-1', status: 'RETRYABLE_FAILURE', attemptCount: 1, startedAt: '2026-07-11T09:14:01Z', completedAt: null, lastErrorCode: 'SYNTHETIC_PROVIDER_TIMEOUT', attempts: [] }], page: 0, size: 50, totalElements: 1, totalPages: 1 }
      : { content: [], page: 0, size: 50, totalElements: 0, totalPages: 0 })
    throw new Error(`Unhandled test URL: ${url}`)
  })
  vi.stubGlobal('fetch', fetchMock)
  return fetchMock
}

beforeEach(() => mockBackend())

const LAUNCH = /^Open the .+ queue$/
// The launch button waits for the industry list, so wait until it is enabled before clicking it.
async function launch() {
  await waitFor(() => expect(screen.getByRole('button', { name: LAUNCH })).toBeEnabled())
  fireEvent.click(screen.getByRole('button', { name: LAUNCH }))
}
async function openInbox() {
  await launch()
  await waitFor(() => expect(screen.getByRole('heading', { name: 'Inbox' })).toBeInTheDocument())
}
const row = (subject: string | RegExp) => within(document.querySelector<HTMLElement>('.queue')!).getByRole('button', { name: typeof subject === 'string' ? new RegExp(subject) : subject })
const detail = () => within(document.querySelector<HTMLElement>('.detail')!)
const PAYMENT = 'Payment accepted but charging did not start'

afterEach(() => { cleanup(); vi.unstubAllGlobals() })

test('renders the honest CaseLens landing, with the byline and no sign-in', async () => {
  render(<App />)
  expect(screen.getByRole('heading', { name: 'Support triage that shows its evidence' })).toBeInTheDocument()
  expect(screen.getByText(/No sign-in, deleted after 24 hours/)).toBeInTheDocument()
  expect(screen.getByText('Synthetic tickets only.')).toBeInTheDocument()
  expect(screen.getByRole('link', { name: 'Harish Darko' })).toHaveAttribute('href', 'https://harishdarko.com')
  await waitFor(() => expect(screen.getByRole('button', { name: 'Open the SaaS support queue' })).toBeEnabled())
  expect(screen.queryByLabelText(/passcode|password/i)).not.toBeInTheDocument()
})

test('shows a recorded example for the picked industry beside the launch button', async () => {
  render(<App />)
  expect(screen.getByText(/Example: a recorded result from the SaaS support queue/)).toBeInTheDocument()
  fireEvent.click(screen.getByRole('radio', { name: /EV charging/ }))

  expect(screen.getByText(/Example: a recorded result from the EV charging queue/)).toBeInTheDocument()
  expect(screen.getByRole('button', { name: 'Open the EV charging queue' })).toBeInTheDocument()
  const card = document.querySelector('.preview-card')!
  expect(card.querySelectorAll('mark.ev').length).toBe(3)
  expect(card).toHaveTextContent('3 of 3 quotes found word for word in the ticket')
  fireEvent.click(screen.getByRole('radio', { name: /E-commerce/ }))
  expect(screen.getByRole('button', { name: 'Open the e-commerce queue' })).toBeInTheDocument()
})

test('explains a possible serverless cold start while opening the workspace', async () => {
  vi.stubGlobal('fetch', vi.fn((input: RequestInfo | URL, init?: RequestInit) => String(input).endsWith('/api/demo/session') && !init?.method
    ? Promise.resolve(response(sessionOptions)) : new Promise(() => undefined)))
  render(<App />)

  await launch()

  expect(screen.getByRole('button', { name: 'Opening the SaaS support queue...' })).toBeDisabled()
  expect(screen.getByRole('status')).toHaveTextContent('First access may take up to 20 seconds while serverless services resume.')
})

test('opens a reviewer workspace, without the avatar, and keeps the stack in How it\'s built', async () => {
  render(<App />)

  await openInbox()

  expect(document.querySelector('.avatar')).toBeNull()
  expect(screen.getByText('Demo workspace', { selector: '.scope-label' })).toBeInTheDocument()
  expect(screen.queryByText('Groq')).not.toBeInTheDocument()
  fireEvent.click(screen.getByRole('button', { name: "How it's built" }))
  expect(screen.getByRole('heading', { name: "How it's built" })).toBeInTheDocument()
  expect(screen.getByText('This workspace runs on a local review stack: Neon Postgres, LocalStack SQS and Groq.')).toBeInTheDocument()
  expect(within(screen.getByRole('dialog')).getByRole('link', { name: 'Harish Darko' })).toHaveAttribute('href', 'https://harishdarko.com')
  expect(screen.getAllByRole('link', { name: 'Harish Darko' }).length).toBe(2)
})

test('opens the reviewer workspace from the real session and ticket endpoints', async () => {
  const fetchMock = mockBackend()
  render(<App />)

  await launch()

  await waitFor(() => expect(row(PAYMENT)).toBeInTheDocument())
  expect(fetchMock).toHaveBeenCalledWith(expect.stringContaining('/api/demo/session'), expect.objectContaining({ method: 'POST' }))
  expect(fetchMock).toHaveBeenCalledWith(expect.stringContaining('/api/tickets?page=0&size=50'), expect.anything())
})

test('lists the industries, starts on SaaS support, and sends the picked industry when the workspace opens', async () => {
  const fetchMock = mockBackend()
  render(<App />)

  expect(await screen.findByRole('radio', { name: /SaaS support/ })).toBeChecked()
  fireEvent.click(screen.getByRole('radio', { name: /EV charging/ }))
  await openInbox()

  const sessionCall = fetchMock.mock.calls.find(([url, init]) => isSessionPost(String(url), init))
  expect(JSON.parse(String(sessionCall?.[1]?.body))).toEqual({ industry: 'ev-charging' })
  expect(screen.getByText('EV charging', { selector: '.scope-name' })).toBeInTheDocument()
})

test('still opens a workspace from the bundled list when the industry list does not load', async () => {
  const fetchMock = mockBackend({ industriesStatus: 503 })
  render(<App />)

  await waitFor(() => expect(fetchMock).toHaveBeenCalledWith(expect.stringContaining('/api/demo/session'), expect.anything()))
  expect(screen.getByRole('radio', { name: /SaaS support/ })).toBeChecked()
  await openInbox()

  const sessionCall = fetchMock.mock.calls.find(([url, init]) => isSessionPost(String(url), init))
  expect(JSON.parse(String(sessionCall?.[1]?.body))).toEqual({ industry: 'saas-support' })
  expect(row(PAYMENT)).toBeInTheDocument()
})

test('lets the visitor pick and launch at once, before a cold API answers the industry list', async () => {
  const fetchMock = vi.fn(() => new Promise<Response>(() => undefined))
  vi.stubGlobal('fetch', fetchMock)
  render(<App />)

  expect(screen.getByRole('button', { name: 'Open the SaaS support queue' })).toBeEnabled()
  fireEvent.click(screen.getByRole('radio', { name: /EV charging/ }))
  fireEvent.click(screen.getByRole('button', { name: 'Open the EV charging queue' }))

  await waitFor(() => expect(fetchMock).toHaveBeenCalledWith(expect.stringContaining('/api/demo/session'), expect.objectContaining({ method: 'POST', body: JSON.stringify({ industry: 'ev-charging' }) })))
  expect(screen.queryByText(/Loading the list of industries/)).not.toBeInTheDocument()
})

test('uses the list from the API when it differs from the bundled one', async () => {
  mockBackend({ industries: { ...sessionOptions, industries: [...sessionOptions.industries, { id: 'telecom', name: 'Telecom', featuredScenario: { key: 'x', label: 'X' }, scenarios: [] }] } })
  render(<App />)

  expect(screen.queryByRole('radio', { name: /Telecom/ })).not.toBeInTheDocument()
  expect(await screen.findByRole('radio', { name: /Telecom/ })).toBeInTheDocument()
  expect(screen.getByRole('radio', { name: /SaaS support/ })).toBeChecked()
})

test('gives the full journey when the API does not know the industry list yet (405 during a deploy)', async () => {
  mockBackend({ industriesStatus: 405 })
  render(<App />)
  await openInbox()

  fireEvent.click(row(PAYMENT))

  await waitFor(() => expect(detail().getByText('AI verified')).toBeInTheDocument())
  expect(detail().getByText('Charging session', { selector: '.fact' })).toBeInTheDocument()
})

test('runs live triage on an opened ticket, shows the real trace, and records an override (older API)', async () => {
  const fetchMock = mockBackend()
  render(<App />)
  await openInbox()

  fireEvent.click(row(PAYMENT))
  await waitFor(() => expect(detail().getByText('AI verified')).toBeInTheDocument())
  expect(document.querySelector('.msg mark.ev')).toHaveTextContent('The driver was charged')
  fireEvent.click(detail().getByRole('button', { name: /Outbox → SQS → Worker/ }))
  expect(detail().getByText('event-1')).toBeInTheDocument()
  expect(detail().getByText('job-1')).toBeInTheDocument()
  expect(detail().getByText('1 attempt')).toBeInTheDocument()
  // An API from before Accept records corrections only, so the page does not offer Accept there.
  expect(detail().queryByRole('button', { name: 'Accept' })).not.toBeInTheDocument()

  fireEvent.click(detail().getByRole('button', { name: 'Override urgency' }))
  fireEvent.click(detail().getByRole('button', { name: /^High/ }))
  fireEvent.change(detail().getByLabelText('Reason'), { target: { value: 'The synthetic case indicates higher customer impact.' } })
  fireEvent.click(detail().getByRole('button', { name: 'Save' }))
  await waitFor(() => expect(detail().getByText('Overridden.')).toBeInTheDocument())
  expect(screen.getByRole('status')).toHaveTextContent('Override logged. AI Critical, you High.')
  // The older API has no decision field; it reads triageResultId.
  const body = JSON.parse(String(fetchMock.mock.calls.find(([url]) => String(url).endsWith('/feedback'))?.[1]?.body)) as Record<string, unknown>
  expect(body).not.toHaveProperty('decision')
  expect(body).toMatchObject({ triageResultId: 'result-1', urgency: 'HIGH', note: 'The synthetic case indicates higher customer impact.' })
})

test('keeps waiting through a slow worker start and explains the delay', async () => {
  vi.useFakeTimers({ shouldAdvanceTime: true })
  try {
    mockBackend({ pendingProcessingPolls: 40 })
    render(<App />)
    await openInbox()

    fireEvent.click(row(PAYMENT))
    await vi.advanceTimersByTimeAsync(12_000)
    expect(screen.getByRole('status')).toHaveTextContent('Waking the triage worker. The first live run after a quiet spell can take up to a minute.')
    expect(document.querySelector('.waking')).toHaveTextContent(/^Waking the triage worker\./)
    expect(document.querySelector('.stage-note')).toBeNull()

    await vi.advanceTimersByTimeAsync(60_000)
    await waitFor(() => expect(detail().getByText('AI verified')).toBeInTheDocument())
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
    expect(document.querySelector('.waking')).toBeNull()
  } finally {
    vi.useRealTimers()
  }
})

async function openTicketWithFakeTimers(backend: BackendOptions) {
  const fetchMock = mockBackend(backend)
  render(<App />)
  await openInbox()
  vi.useFakeTimers({ shouldAdvanceTime: true })
  fireEvent.click(row(PAYMENT))
  return fetchMock
}

test('checks the triage status once a second, not four times', async () => {
  const fetchMock = await openTicketWithFakeTimers({ pendingProcessingPolls: 100 })
  await act(async () => { await vi.advanceTimersByTimeAsync(5_000) })

  expect(calls(fetchMock, '/processing')).toBeLessThanOrEqual(6)
})

test('says in plain words what the worker is doing, and never "local worker"', async () => {
  await openTicketWithFakeTimers({ pollSteps: ['QUEUED', 'QUEUED', 'PROCESSING', 'PROCESSING'] })
  await act(async () => { await vi.advanceTimersByTimeAsync(100) })
  expect(document.querySelector('.stage-note')).toHaveTextContent('Waiting for the triage worker to pick this up. After a quiet period it can take up to a minute to start.')
  expect(row(PAYMENT)).toHaveTextContent('Queued')

  await act(async () => { await vi.advanceTimersByTimeAsync(2_000) })
  expect(document.querySelector('.stage-note')).toHaveTextContent('The AI model is reading the ticket. This usually takes a few seconds.')
  expect(row(PAYMENT)).toHaveTextContent('Reading and checking')
  expect(screen.queryByText(/local worker/i)).not.toBeInTheDocument()

  await act(async () => { await vi.advanceTimersByTimeAsync(3_000) })
  await waitFor(() => expect(detail().getByText('AI verified')).toBeInTheDocument())
})

test('keeps waiting when one status check is throttled', async () => {
  await openTicketWithFakeTimers({ pollSteps: ['QUEUED', 429] })
  await act(async () => { await vi.advanceTimersByTimeAsync(5_000) })

  await waitFor(() => expect(detail().getByText('AI verified')).toBeInTheDocument())
  expect(screen.queryByRole('alert')).not.toBeInTheDocument()
})

test('keeps waiting while the worker retries a provider that did not answer', async () => {
  await openTicketWithFakeTimers({ pollSteps: ['PROCESSING', 'RETRYABLE_FAILURE', 'RETRYABLE_FAILURE'] })
  await act(async () => { await vi.advanceTimersByTimeAsync(1_500) })
  expect(document.querySelector('.stage-note')).toHaveTextContent('The AI model is busy. The worker tries again by itself in about a minute and a half.')

  await act(async () => { await vi.advanceTimersByTimeAsync(3_000) })
  await waitFor(() => expect(detail().getByText('AI verified')).toBeInTheDocument())
  expect(screen.queryByRole('alert')).not.toBeInTheDocument()
})

test('says where to go when triage fails, on that ticket', async () => {
  await openTicketWithFakeTimers({ pollSteps: ['TERMINAL_FAILURE'] })
  await act(async () => { await vi.advanceTimersByTimeAsync(500) })

  await waitFor(() => expect(detail().getByRole('alert')).toHaveTextContent('This ticket could not be triaged. Open Quality and operations to retry it.'))
  expect(row(PAYMENT)).toHaveTextContent('Triage failed')
  expect(detail().queryByText('Open this ticket to run the live AI on it.')).not.toBeInTheDocument()
  expect(screen.queryByText(/terminal failure/i)).not.toBeInTheDocument()
})

test('explains the controlled retry demo stop in plain words', async () => {
  await openTicketWithFakeTimers({ pollSteps: ['SYNTHETIC_FAILURE'] })
  await act(async () => { await vi.advanceTimersByTimeAsync(500) })

  await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent('The retry demo stopped this triage on purpose. Open Quality and operations to retry it.'))
  expect(screen.queryByText(/retryable failure/i)).not.toBeInTheDocument()
})

test('says the demo is busy instead of showing a status code', async () => {
  mockBackend({ sessionStatuses: [429, 429] })
  render(<App />)
  await launch()

  await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent('The demo is busy right now. Wait a few seconds and try again.'), { timeout: 4_000 })
  expect(screen.queryByText(/status 429/)).not.toBeInTheDocument()
})

test('says plainly when the service cannot be reached', async () => {
  vi.stubGlobal('fetch', vi.fn((input: RequestInfo | URL, init?: RequestInit) => String(input).endsWith('/api/demo/session') && !init?.method
    ? Promise.resolve(response(sessionOptions)) : Promise.reject(new TypeError('Failed to fetch'))))
  render(<App />)
  await launch()

  await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent('Could not reach the CaseLens service. Check your connection and try again.'))
  expect(screen.queryByText('Failed to fetch')).not.toBeInTheDocument()
})

test('requires a reason before saving an override on an API that records corrections only', async () => {
  render(<App />)
  await openInbox()
  fireEvent.click(row(PAYMENT))
  await waitFor(() => expect(detail().getByRole('button', { name: 'Override urgency' })).toBeEnabled())
  fireEvent.click(detail().getByRole('button', { name: 'Override urgency' }))
  fireEvent.click(detail().getByRole('button', { name: /^High/ }))

  expect(detail().getByRole('button', { name: 'Save' })).toBeDisabled()
  expect(detail().getByText('Add a reason to save.')).toBeInTheDocument()
})

const QO = 'Quality and operations'
async function openQuality() {
  fireEvent.click(screen.getByRole('button', { name: QO }))
  await waitFor(() => expect(screen.getByRole('heading', { name: 'Live queue and reliability' })).toBeInTheDocument())
}

test('simulates a provider timeout from Quality and operations, then offers Retry case, and keeps the ticket out of the inbox', async () => {
  let failed = false
  let retriedOnce = false
  const backend = mockBackend()
  const fetchMock = stubOver(backend, (url) => {
    if (url.endsWith('/ticket-a105/triage')) { failed = true; return undefined }
    if (url.endsWith('/job-retry-1/retry')) { failed = false; retriedOnce = true; return response({ id: 'job-retry-2', eventId: 'event-retry-1', ticketId: 'ticket-a105', status: 'QUEUED', attemptCount: 0, startedAt: null, completedAt: null, lastErrorCode: null, attempts: [] }) }
    if (url.includes('/api/operations/failures') && failed) return response({ content: [{ id: 'job-retry-1', eventId: 'event-retry-1', ticketId: 'ticket-a105', status: 'RETRYABLE_FAILURE', attemptCount: 1, startedAt: '2026-07-11T09:14:01Z', completedAt: null, lastErrorCode: 'SYNTHETIC_PROVIDER_TIMEOUT', attempts: [] }], page: 0, size: 50, totalElements: 1, totalPages: 1 })
    // The replayed job stays a RETRYABLE_FAILURE row in the queue counts; only the failures list drops it.
    if (url.endsWith('/api/operations/overview') && retriedOnce) return response({ generatedAt: '2026-07-11T09:14:02Z', queue: { queued: 1, processing: 0, completed: 1, retryableFailures: 1, terminalFailures: 0 }, fallbackCount: 0, providerFailureCount: 0, recent: [], providers: [] })
    return undefined
  })
  render(<App />)
  await openInbox()
  await openQuality()

  expect(screen.getByRole('heading', { name: 'No unresolved failures' })).toBeInTheDocument()
  const overviewReads = calls(fetchMock, '/api/operations/overview')
  fireEvent.click(screen.getByRole('button', { name: 'Simulate provider timeout' }))

  await waitFor(() => expect(fetchMock).toHaveBeenCalledWith(expect.stringContaining('/api/tickets/ticket-a105/triage'), expect.objectContaining({ method: 'POST' })))
  expect(fetchMock).toHaveBeenCalledWith(expect.stringContaining('/api/demo/scenarios/provider-retry-demo'), expect.objectContaining({ method: 'POST' }))
  // The screen reads again at once instead of waiting for its next 5-second tick, and the banner flips in place.
  await waitFor(() => expect(calls(fetchMock, '/api/operations/overview')).toBe(overviewReads + 1), { timeout: 1_000 })
  await waitFor(() => expect(screen.getByRole('heading', { name: 'Recovery required' })).toBeInTheDocument())
  expect(screen.getByRole('button', { name: 'Retry case' })).toBeEnabled()
  expect(screen.getByText('Recovery required', { selector: '.qo-health' })).toBeInTheDocument()
  expect(document.querySelector('.qo [aria-live="polite"]')).toHaveTextContent('Recovery required. The synthetic provider timeout is ready to retry.')
  const failuresCard = () => screen.getByText('Recoverable failures', { selector: '.qo-label' }).closest('.qo-figure')!
  expect(failuresCard()).toHaveTextContent('1')
  expect(failuresCard()).toHaveClass('tone-coral')
  fireEvent.click(screen.getByRole('button', { name: 'Retry case' }))
  // After the retry the banner reads healthy again, focus moves to it, and the demo can run again.
  await waitFor(() => expect(screen.getByRole('heading', { name: 'No unresolved failures' })).toHaveFocus())
  expect(document.querySelector('.qo [aria-live="polite"]')).toHaveTextContent('Retried. The job is back in the queue and there are no unresolved failures.')
  expect(screen.getByRole('button', { name: 'Simulate provider timeout' })).toBeEnabled()
  // The card follows the failures list, not the queue's cumulative failed rows, so it agrees with the banner.
  await waitFor(() => expect(failuresCard()).toHaveTextContent('None waiting; 1 failed attempt in all'))
  expect(failuresCard().querySelector('.qo-value')).toHaveTextContent('0')
  expect(failuresCard()).not.toHaveClass('tone-coral')
  fireEvent.click(screen.getByRole('button', { name: 'Inbox' }))
  expect(screen.queryByText('Synthetic provider timeout for retry review')).not.toBeInTheDocument()
})

test('shows a pending simulation until the failure is listed', async () => {
  const fetchMock = mockBackend()
  render(<App />)
  await openInbox()
  await openQuality()

  fireEvent.click(screen.getByRole('button', { name: 'Simulate provider timeout' }))
  await waitFor(() => expect(screen.getByRole('heading', { name: 'Simulating a provider timeout' })).toHaveFocus())
  expect(screen.getByRole('button', { name: 'Simulate provider timeout' })).toBeDisabled()
  expect(document.querySelector('.qo [aria-live="polite"]')).toHaveTextContent('Simulating a provider timeout. The failure shows here in a few seconds.')
  expect(fetchMock.mock.calls.filter(([url]) => String(url).includes('/api/demo/scenarios/provider-retry-demo'))).toHaveLength(1)
})

test('shows live queue, reliability, quality and review figures, the job log and provider usage on one screen', async () => {
  const backend = mockBackend()
  stubOver(backend, (url) => {
    if (url.endsWith('/api/evaluation')) return response({ generatedAt: '2026-07-11T09:14:02Z', triageResults: 5, evaluatedResults: 4, categoryAgreementRate: 1, urgencyAgreementRate: 0.75, agreementRate: 0.75, correctionRate: 0.2, medianLatencyMs: 2900, p95LatencyMs: 4100, failureRate: 0, providerUsage: [], recentEvents: [], reviewedResults: 4, acceptedResults: 3, acceptanceRate: 0.75, overrideRate: 0.25, recordedResults: 3 })
    if (url.endsWith('/api/operations/overview')) return response({ generatedAt: '2026-07-11T09:14:02Z', queue: { queued: 1, processing: 1, completed: 2, retryableFailures: 0, terminalFailures: 0 }, fallbackCount: 1, providerFailureCount: 0, recent: [{ jobId: 'job-1', ticketId: 'ticket-a102', status: 'COMPLETED', attemptCount: 1, createdAt: '2026-07-11T09:14:00Z', updatedAt: '2026-07-11T09:14:02Z', completedAt: '2026-07-11T09:14:02Z', lastErrorCode: null, provider: 'groq', modelVersion: 'openai/gpt-oss-20b', decisionSource: 'AI_VALIDATED', latencyMs: 2900 }, { jobId: 'job-2', ticketId: 'ticket-a103', status: 'COMPLETED', attemptCount: 1, createdAt: '2026-07-11T09:15:00Z', updatedAt: '2026-07-11T09:15:03Z', completedAt: '2026-07-11T09:15:03Z', lastErrorCode: null, provider: 'groq', modelVersion: 'openai/gpt-oss-20b', decisionSource: 'RULES_FALLBACK', latencyMs: 3100 }, { jobId: 'job-3', ticketId: 'ticket-a104', status: 'TERMINAL_FAILURE', attemptCount: 3, createdAt: '2026-07-11T09:16:00Z', updatedAt: '2026-07-11T09:18:00Z', completedAt: null, lastErrorCode: 'PROVIDER_TIMEOUT', provider: 'groq', modelVersion: null, decisionSource: null, latencyMs: null }, { jobId: 'job-4', ticketId: 'ticket-a105', status: 'RETRYABLE_FAILURE', attemptCount: 1, createdAt: '2026-07-11T09:19:00Z', updatedAt: '2026-07-11T09:19:01Z', completedAt: null, lastErrorCode: 'SYNTHETIC_PROVIDER_TIMEOUT', provider: null, modelVersion: null, decisionSource: null, latencyMs: null }], providers: [{ provider: 'groq', requests: 2, successes: 2, failures: 0, inputTokens: 2000, outputTokens: 600, averageLatencyMs: 2950 }] })
    return undefined
  })
  render(<App />)
  await openInbox()
  await openQuality()

  const figure = (label: string) => screen.getByText(label, { selector: '.qo-label' }).closest('.qo-figure')!
  expect(figure('In flight')).toHaveTextContent('2')
  expect(figure('Completed live')).toHaveTextContent('2')
  expect(figure('Rules fallbacks')).toHaveTextContent('1')
  expect(figure('Reviewer acceptance')).toHaveTextContent('75%')
  expect(figure('Reviewer acceptance')).toHaveTextContent('3 of 4 reviewed results accepted')
  expect(figure('Override rate')).toHaveTextContent('25%')
  expect(figure('Benchmark match')).toHaveTextContent('75%')
  expect(figure('Live latency')).toHaveTextContent('p50 2900 ms')
  expect(figure('Live latency')).toHaveTextContent('p95 4100 ms')
  expect(screen.getByText('AI verified', { selector: '.qo-log .badge' })).toBeInTheDocument()
  expect(screen.getByText('Rules fallback', { selector: '.qo-log .badge' })).toBeInTheDocument()
  expect(screen.getAllByText('openai/gpt-oss-20b', { exact: false }).length).toBeGreaterThan(0)
  expect(document.querySelector('.qo-providers li')).toHaveTextContent('Groq · 2 calls · 2 succeeded · 0 failed · 2600 tokens')
  expect(screen.getByText('How retries and dead-letter recovery work')).toBeInTheDocument()
  expect(screen.getByText('Healthy', { selector: '.qo-health' })).toBeInTheDocument()
  const logRow = (status: string) => screen.getByText(status, { selector: '.qo-log b' }).closest('li')!
  expect(logRow('Failed').querySelector('.qo-log-latency')).toHaveTextContent('Not measured yet')
  expect(logRow('Stopped on purpose').querySelector('.qo-log-latency')).toHaveTextContent('Not measured yet')
  expect(screen.queryByText('Pending')).not.toBeInTheDocument()
  expect(screen.queryByText('Retrying')).not.toBeInTheDocument()
  expect(screen.queryByText('Needs a newer backend')).not.toBeInTheDocument()
  expect(document.body.textContent).not.toContain('\u2014')
})

test('asks for a newer backend when an API sends no reviewer figures, and uses its correction rate for overrides', async () => {
  render(<App />)
  await openInbox()
  await openQuality()

  const figure = (label: string) => screen.getByText(label, { selector: '.qo-label' }).closest('.qo-figure')!
  expect(figure('Reviewer acceptance')).toHaveTextContent('Needs a newer backend')
  expect(figure('Reviewer acceptance')).toHaveTextContent('Not measured yet')
  expect(figure('Override rate')).toHaveTextContent('0%')
  expect(figure('Override rate')).toHaveTextContent('Corrections as a share of results')
  expect(screen.queryByText('No reviews yet')).not.toBeInTheDocument()
})

test('shows the session-ended state, not a loading line, when the first read is refused', async () => {
  // The seeded open reads nothing from evaluation, so only the screen's first read is refused.
  stubOver(mockSeededBackend(), (url) => url.endsWith('/api/evaluation') ? response({ detail: 'Unauthorized' }, 401) : undefined)
  render(<App />)
  await openInbox()
  fireEvent.click(screen.getByRole('button', { name: QO }))

  await waitFor(() => expect(screen.getByRole('status')).toHaveTextContent('Your demo session has ended, so live updates stopped.'))
  expect(screen.queryByText(/Loading the latest numbers/)).not.toBeInTheDocument()
  expect(document.querySelector('.qo-skeleton')).toBeNull()
})

test('creates one retry demo ticket on a double click, and reuses it when its triage request failed', async () => {
  let triageAttempts = 0
  const fetchMock = stubOver(mockBackend(), (url) => url.endsWith('/ticket-a105/triage') && triageAttempts++ === 0 ? response({ detail: 'Busy' }, 500) : undefined)
  render(<App />)
  await openInbox()
  await openQuality()
  const scenarioPosts = () => fetchMock.mock.calls.filter(([url]) => String(url).includes('/api/demo/scenarios/provider-retry-demo')).length
  const triagePosts = () => fetchMock.mock.calls.filter(([url, init]) => String(url).endsWith('/ticket-a105/triage') && init?.method === 'POST').length

  const simulate = screen.getByRole('button', { name: 'Simulate provider timeout' })
  act(() => { simulate.click(); simulate.click() })
  await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent('The CaseLens service had a problem.'))
  expect(scenarioPosts()).toBe(1)

  fireEvent.click(screen.getByRole('button', { name: 'Simulate provider timeout' }))
  await waitFor(() => expect(triagePosts()).toBe(2))
  expect(scenarioPosts()).toBe(1)
})

test('drops a refresh that was already on its way when a retry lands, so the old failure never comes back', async () => {
  let failed = true
  let hold: () => void = () => undefined
  let holdNext = false
  const failure = { id: 'job-retry-1', eventId: 'event-retry-1', ticketId: 'ticket-retry-1', status: 'RETRYABLE_FAILURE', attemptCount: 1, startedAt: '2026-07-11T09:14:01Z', completedAt: null, lastErrorCode: 'SYNTHETIC_PROVIDER_TIMEOUT', attempts: [] }
  const page = (withFailure: boolean) => response(withFailure ? { content: [failure], page: 0, size: 50, totalElements: 1, totalPages: 1 } : { content: [], page: 0, size: 50, totalElements: 0, totalPages: 0 })
  const fetchMock = stubOver(mockBackend(), (url) => {
    if (url.endsWith('/retry')) { failed = false; return response({ ...failure, id: 'job-retry-2', status: 'QUEUED', lastErrorCode: null }) }
    if (url.includes('/api/operations/failures') && holdNext) { holdNext = false; const answer = page(failed); return new Promise<Response>((resolve) => { hold = () => resolve(answer) }) as unknown as Response }
    if (url.includes('/api/operations/failures')) return page(failed)
    return undefined
  })
  render(<App />)
  await openInbox()
  vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval', 'Date'] })
  fireEvent.click(screen.getByRole('button', { name: QO }))
  await waitFor(() => expect(screen.getByRole('button', { name: 'Retry case' })).toBeEnabled())

  // A routine refresh starts and its failure list (read before the retry) is slow to answer.
  holdNext = true
  await advance(5_000)
  const reads = calls(fetchMock, '/api/operations/overview')
  fireEvent.click(screen.getByRole('button', { name: 'Retry case' }))
  await waitFor(() => expect(screen.getByRole('heading', { name: 'No unresolved failures' })).toBeInTheDocument())
  // No second set of reads runs beside the one in flight.
  expect(calls(fetchMock, '/api/operations/overview')).toBe(reads)

  await act(async () => { hold() })
  await waitFor(() => expect(calls(fetchMock, '/api/operations/overview')).toBe(reads + 1))
  expect(screen.getByRole('heading', { name: 'No unresolved failures' })).toBeInTheDocument()
  expect(screen.queryByRole('heading', { name: 'Recovery required' })).not.toBeInTheDocument()
})

test('never reports a retry as failed because the refresh after it was throttled', async () => {
  let retried = false
  const failure = { id: 'job-retry-1', eventId: 'event-retry-1', ticketId: 'ticket-retry-1', status: 'RETRYABLE_FAILURE', attemptCount: 1, startedAt: '2026-07-11T09:14:01Z', completedAt: null, lastErrorCode: 'SYNTHETIC_PROVIDER_TIMEOUT', attempts: [] }
  stubOver(mockBackend(), (url) => {
    if (url.endsWith('/retry')) { retried = true; return response({ ...failure, id: 'job-retry-2', status: 'QUEUED', lastErrorCode: null }) }
    if (url.endsWith('/api/operations/overview') && retried) return response({ detail: 'Busy' }, 503)
    if (url.includes('/api/operations/failures')) return response({ content: [failure], page: 0, size: 50, totalElements: 1, totalPages: 1 })
    return undefined
  })
  render(<App />)
  await openInbox()
  await openQuality()
  fireEvent.click(screen.getByRole('button', { name: 'Retry case' }))

  await waitFor(() => expect(screen.getByRole('status')).toHaveTextContent('Could not load the latest numbers.'), { timeout: 3_000 })
  expect(screen.queryByRole('alert')).not.toBeInTheDocument()
})

test('labels a controlled synthetic timeout as reviewer recovery rather than provider degradation', async () => {
  mockBackend({ syntheticFailure: true })
  render(<App />)
  await openInbox()

  fireEvent.click(screen.getByRole('button', { name: QO }))

  await waitFor(() => expect(screen.getByRole('heading', { name: 'Recovery required' })).toBeInTheDocument())
  expect(screen.getByText('A controlled synthetic timeout was recorded. Retry the case to verify the recovery path.')).toBeInTheDocument()
  expect(screen.getByRole('button', { name: 'Retry case' })).toBeInTheDocument()
  expect(screen.queryByText('Degraded')).not.toBeInTheDocument()
  expect(screen.getByRole('button', { name: QO })).toHaveTextContent('1 failed')
})

// Live refresh: the tab can be hidden, and a visitor can walk away. Timers and Date are faked only after the
// workspace is open, so the waits above still use real time.
function setVisibility(state: 'visible' | 'hidden') {
  Object.defineProperty(document, 'visibilityState', { configurable: true, get: () => state })
  document.dispatchEvent(new Event('visibilitychange'))
}

async function advance(ms: number) {
  await act(async () => { await vi.advanceTimersByTimeAsync(ms) })
}

function calls(fetchMock: ReturnType<typeof vi.fn>, path: string) {
  return fetchMock.mock.calls.filter(([url]) => String(url).endsWith(path)).length
}

async function openView(backend: Parameters<typeof mockBackend>[0] = {}) {
  const fetchMock = mockBackend(backend)
  render(<App />)
  await openInbox()
  vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval', 'Date'] })
  fireEvent.click(screen.getByRole('button', { name: 'Quality and operations' }))
  await advance(0)
  return fetchMock
}

afterEach(() => {
  vi.useRealTimers()
  delete (document as { visibilityState?: string }).visibilityState
})

test('refreshes Quality and operations every 5 seconds, with the same three reads, only while the tab is visible', async () => {
  const fetchMock = await openView()
  const start = calls(fetchMock, '/api/evaluation')

  const overview = calls(fetchMock, '/api/operations/overview')
  const failures = calls(fetchMock, '/api/operations/failures?page=0&size=50')
  await advance(60_000)
  expect(calls(fetchMock, '/api/evaluation') - start).toBe(12)
  expect(calls(fetchMock, '/api/operations/overview') - overview).toBe(12)
  expect(calls(fetchMock, '/api/operations/failures?page=0&size=50') - failures).toBe(12)
  expect(screen.getByRole('status')).toHaveTextContent('Live: updates every 5 seconds while this tab is open.')

  setVisibility('hidden')
  const hidden = calls(fetchMock, '/api/evaluation')
  await advance(60_000)
  expect(calls(fetchMock, '/api/evaluation')).toBe(hidden)

  setVisibility('visible')
  await advance(0)
  expect(calls(fetchMock, '/api/evaluation')).toBe(hidden + 1)
})

test('makes no requests from a hidden tab', async () => {
  const fetchMock = await openView()
  setVisibility('hidden')
  const overview = calls(fetchMock, '/api/operations/overview')
  const failures = calls(fetchMock, '/api/operations/failures?page=0&size=50')

  await advance(10 * 60_000)

  expect(calls(fetchMock, '/api/operations/overview')).toBe(overview)
  expect(calls(fetchMock, '/api/operations/failures?page=0&size=50')).toBe(failures)
})

test('stops refreshing after 10 minutes without activity, says so, and resumes on request', async () => {
  const fetchMock = await openView()

  await advance(10 * 60_000 + 5_000)
  expect(screen.getByRole('status')).toHaveTextContent('Live updates are paused because there was no activity for 10 minutes.')
  const paused = calls(fetchMock, '/api/evaluation')
  await advance(60_000)
  expect(calls(fetchMock, '/api/evaluation')).toBe(paused)

  fireEvent.click(screen.getByRole('button', { name: 'Refresh now' }))
  await advance(0)
  expect(calls(fetchMock, '/api/evaluation')).toBe(paused + 1)
  expect(screen.getByRole('status')).toHaveTextContent('Live: updates every 5 seconds while this tab is open.')
})

test('announces only state changes, not every routine refresh', async () => {
  await openView()
  await advance(5_000)
  const announced = screen.getByRole('status').textContent
  const updated = screen.getByText(/Last updated at/).textContent

  await advance(5_000)

  expect(screen.getByRole('status').textContent).toBe(announced)
  expect(screen.getByRole('status')).not.toHaveTextContent(/Last updated/)
  expect(screen.getByText(/Last updated at/).textContent).not.toBe(updated)
})

test('waits for a slow refresh instead of sending overlapping requests', async () => {
  const fetchMock = await openView({ laterEvaluation: () => new Promise<Response>(() => undefined) })
  const started = calls(fetchMock, '/api/evaluation')

  await advance(60_000)

  expect(calls(fetchMock, '/api/evaluation')).toBe(started)
  expect(screen.getByRole('status')).toHaveTextContent('Loading the latest numbers for this workspace.')
})

test('ignores a late answer for a view the reviewer has already left', async () => {
  const failLate: Array<(reason: Error) => void> = []
  await openView({ laterEvaluation: () => new Promise<Response>((_, reject) => { failLate.push(reject) }) })
  await advance(5_000)

  fireEvent.click(screen.getByRole('button', { name: 'Inbox' }))
  fireEvent.click(screen.getByRole('button', { name: 'Quality and operations' }))
  await advance(0)
  await act(async () => { failLate[0]?.(new Error('Failed to fetch')) })
  await advance(0)

  expect(screen.getByRole('status')).not.toHaveTextContent('Could not load the latest numbers.')
  expect(screen.queryByText('Failed to fetch')).not.toBeInTheDocument()
})

test('says in plain words when the latest numbers could not load', async () => {
  await openView({ laterEvaluation: () => Promise.resolve(response({ detail: 'Request failed with status 503' }, 503)) })

  // The client tries a GET once more after a 503 (1 second later), so the failed state comes after that try.
  await waitFor(() => expect(screen.getByRole('status')).toHaveTextContent('Could not load the latest numbers. The service may be starting or busy. Trying again in 5 seconds.'), { timeout: 3_000 })
  expect(screen.queryByText(/status 503/)).not.toBeInTheDocument()
})

// Opening a workspace in one call: POST /api/demo/session?seed=true seeds the workspace and returns its tickets and the
// runtime summary. mockBackend answers like the API before this change (a bare session), which is the deploy gap.
const seededRuntime = { environment: 'AWS_DEMO', database: 'NEON_POSTGRESQL', queue: 'AMAZON_SQS', aiProvider: 'GROQ' }
const chargerTicket = { ...apiTicket, id: 'ticket-a104', displayId: 'CL-A104', subject: 'Every charger at the demo site appears offline', message: 'All six chargers show Offline and three drivers are waiting.', scenarioKey: 'charger-offline-site-wide', createdAt: '2026-07-11T09:15:00Z' }

type Result = Record<string, unknown>
function resultFor(ticketId: string, overrides: Result = {}): Result {
  return { id: `result-${ticketId}`, ticketId, category: 'GENERAL', urgency: 'LOW', slaRisk: 'LOW', sentiment: 'NEUTRAL', summary: `Summary for ${ticketId}.`, evidence: [{ quote: 'charged', meaning: `A test reason for ${ticketId}.` }], policyIds: [], explanation: 'A test explanation.', recommendedActions: [], suggestedReply: '', reliabilitySignal: 'MEDIUM', warnings: [], priorityScore: 40, appliedRules: [], decisionSource: 'AI_VALIDATED', modelVersion: 'test-model', promptVersion: 'triage-v1', createdAt: '2026-07-11T09:14:02Z', ...overrides }
}
// A result as the newer API sends it, with the fields recorded results brought.
function enriched(ticketId: string, overrides: Result = {}): Result {
  return resultFor(ticketId, { recorded: false, fallbackReason: null, categoryLabel: 'General', categoryDescription: 'Questions and anything that fits no other category.', policyVersion: 'ev-v1', policies: [], ...overrides })
}
function completedProcessing(ticketId: string, priorityScore: number, slaRisk: 'LOW' | 'MEDIUM' | 'HIGH', overrides: Result = {}) {
  return response({ job: { ...completedJob, id: `job-${ticketId}`, eventId: `event-${ticketId}`, ticketId, attempts: [] }, result: resultFor(ticketId, { priorityScore, slaRisk, ...overrides }) })
}

function mockSeededBackend(tickets: unknown[] = [apiTicket], processing: Record<string, () => Response> = {}, onFeedback?: (body: Record<string, unknown>) => Response) {
  const backend = mockBackend()
  const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input)
    if (url.endsWith('/api/demo/session?seed=true') && init?.method === 'POST') return response({ token: 'demo-token', expiresAt: '2026-07-12T00:00:00Z', industry: 'ev-charging', tickets, runtime: seededRuntime })
    const ticketId = Object.keys(processing).find((id) => url.endsWith(`/api/tickets/${id}/processing`))
    if (ticketId) return processing[ticketId]()
    if (onFeedback && url.endsWith('/feedback')) return onFeedback(JSON.parse(String(init?.body)) as Record<string, unknown>)
    return backend(input, init)
  })
  vi.stubGlobal('fetch', fetchMock)
  return fetchMock
}

function apiCallsAfterLaunch(fetchMock: ReturnType<typeof vi.fn>) {
  return fetchMock.mock.calls.map(([url, init]) => `${init?.method ?? 'GET'} ${String(url)}`).filter((call) => call !== 'GET /api/demo/session')
}

function rowOrder() {
  return [...document.querySelectorAll<HTMLElement>('.queue .row')].map((item) => item.dataset.id)
}

test('opens a seeded workspace with one request and keeps its runtime for How it\'s built', async () => {
  const fetchMock = mockSeededBackend()
  render(<App />)
  await launch()

  await waitFor(() => expect(row(PAYMENT)).toBeInTheDocument())
  expect(apiCallsAfterLaunch(fetchMock)).toEqual(['POST /api/demo/session?seed=true'])
  const sessionCall = fetchMock.mock.calls.find(([url, init]) => String(url).endsWith('/api/demo/session?seed=true') && init?.method === 'POST')
  expect(JSON.parse(String(sessionCall?.[1]?.body))).toEqual({ industry: 'saas-support' })
  expect(screen.getByRole('button', { name: 'Quality and operations' })).not.toHaveTextContent(/failed/)
  fireEvent.click(screen.getByRole('button', { name: "How it's built" }))
  expect(screen.getByText('This workspace runs on AWS: Neon Postgres, Amazon SQS and Groq.')).toBeInTheDocument()
})

test('still opens through reset and the separate reads when the API returns a bare session (deploy gap)', async () => {
  const fetchMock = mockBackend()
  render(<App />)
  await launch()

  await waitFor(() => expect(row(PAYMENT)).toBeInTheDocument())
  expect(apiCallsAfterLaunch(fetchMock)).toEqual(expect.arrayContaining(['POST /api/demo/session?seed=true', 'POST /api/demo/reset', 'GET /api/tickets?page=0&size=50', 'GET /api/demo/runtime']))
  // Without recorded results nothing is triaged yet, so nothing opens by itself and nothing is sent to the worker.
  expect(screen.getByText('Nothing triaged yet. Open a ticket to run the live AI on it.')).toBeInTheDocument()
  expect(fetchMock.mock.calls.some(([url]) => String(url).includes('/triage'))).toBe(false)
})

test('loads Quality and operations when it is opened, and never shows numbers from an earlier workspace', async () => {
  const fetchMock = mockSeededBackend()
  render(<App />)
  await openInbox()

  await openQuality()
  expect(calls(fetchMock, '/api/evaluation')).toBe(1)
  expect(calls(fetchMock, '/api/operations/overview')).toBe(1)
  expect(calls(fetchMock, '/api/operations/failures?page=0&size=50')).toBe(1)

  fireEvent.click(screen.getByRole('button', { name: 'Close demo' }))
  await openInbox()
  fireEvent.click(screen.getByRole('button', { name: QO }))
  expect(screen.getByRole('status')).toHaveTextContent('Loading the latest numbers for this workspace.')
  expect(document.querySelector('.qo-skeleton')).toBeInTheDocument()
  await waitFor(() => expect(screen.getByRole('heading', { name: 'Live queue and reliability' })).toBeInTheDocument())
})

test('lists triaged tickets first, by the AI priority score and then SLA risk, and untriaged ones after in API order', async () => {
  const receipt = { ...apiTicket, id: 'ticket-a103', displayId: 'CL-A103', subject: 'Receipt requested for a completed session', scenarioKey: 'receipt-request-general' }
  const offline = { ...chargerTicket, id: 'ticket-a106', displayId: 'CL-A106' }
  mockSeededBackend([receipt, offline, apiTicket], {
    'ticket-a103': () => completedProcessing('ticket-a103', 40, 'LOW'),
    'ticket-a106': () => completedProcessing('ticket-a106', 40, 'HIGH'),
    'ticket-a102': () => completedProcessing('ticket-a102', 82, 'MEDIUM'),
  })
  render(<App />)
  await openInbox()
  expect(rowOrder()).toEqual(['CL-A103', 'CL-A106', 'CL-A102'])

  fireEvent.click(row('Receipt requested'))
  await waitFor(() => expect(detail().getByText('A test reason for ticket-a103.')).toBeInTheDocument())
  expect(rowOrder()).toEqual(['CL-A103', 'CL-A106', 'CL-A102'])
  expect(screen.getByText('Triaged, highest priority first')).toBeInTheDocument()

  fireEvent.click(row(PAYMENT))
  await waitFor(() => expect(detail().getByText('A test reason for ticket-a102.')).toBeInTheDocument())
  expect(rowOrder()).toEqual(['CL-A102', 'CL-A103', 'CL-A106'])

  fireEvent.click(row('Every charger'))
  await waitFor(() => expect(detail().getByText('A test reason for ticket-a106.')).toBeInTheDocument())
  expect(rowOrder()).toEqual(['CL-A102', 'CL-A106', 'CL-A103'])
})

// The hybrid queue: recorded results arrive with the session, the other tickets wait for a live run.
const recordedOffline = { ...chargerTicket, latestResult: enriched('ticket-a104', { recorded: true, urgency: 'CRITICAL', slaRisk: 'HIGH', priorityScore: 100, category: 'CONNECTIVITY', categoryLabel: 'Connectivity', evidence: [{ quote: 'Every charger at the demo site appears offline', meaning: 'The whole site is down.' }, { quote: 'three drivers are waiting', meaning: 'Drivers are blocked now.' }], policies: [{ id: 'CHARGER_SITE_OUTAGE', label: 'Charger site outage', guidance: 'Check the site network and notify the operator.' }], appliedRules: [{ code: 'URGENCY_CRITICAL', points: 55, explanation: 'Critical urgency baseline' }, { code: 'SLA_RISK_HIGH', points: 20, explanation: 'High risk of breaching the response target' }, { code: 'BROAD_IMPACT', points: 30, explanation: 'Many drivers are affected' }] }), latestFeedback: null }
const recordedReceipt = { ...apiTicket, id: 'ticket-a103', displayId: 'CL-A103', subject: 'Receipt requested for a completed session', message: 'Please send a receipt, I was charged for the session.', scenarioKey: 'receipt-request-general', latestResult: enriched('ticket-a103', { recorded: true, priorityScore: 10 }), latestFeedback: null }
const retryTicket = { ...apiTicket, id: 'ticket-a105', displayId: 'CL-A105', subject: 'Synthetic provider timeout for retry review', scenarioKey: 'provider-retry-demo' }

test('opens the hybrid queue: recorded results first, the top one already open, and the rest ready for a live run', async () => {
  const fetchMock = mockSeededBackend([apiTicket, recordedReceipt, retryTicket, recordedOffline])
  render(<App />)
  await openInbox()

  expect(screen.getByText('1 critical ticket: every charger at the demo site appears offline.')).toBeInTheDocument()
  expect(screen.getByText('3 tickets, 2 triaged, 1 not triaged yet')).toBeInTheDocument()
  expect(rowOrder()).toEqual(['CL-A104', 'CL-A103', 'CL-A102'])
  expect(row('Every charger')).toHaveTextContent('Recorded')
  expect(row(PAYMENT)).toHaveTextContent('Run live AI')
  expect(screen.queryByText('Synthetic provider timeout for retry review')).not.toBeInTheDocument()
  expect(screen.getByRole('button', { name: 'Inbox' })).toHaveTextContent('2 to review')
  // A laptop opens the most urgent recorded ticket without asking the worker for anything.
  expect(row('Every charger')).toHaveAttribute('aria-current', 'true')
  expect(detail().getByText('Recorded AI result')).toBeInTheDocument()
  expect(detail().getByText('Charger site outage')).toBeInTheDocument()
  expect(detail().getByText('Check the site network and notify the operator.', { selector: '.guidance' })).toBeInTheDocument()
  expect(detail().getByText('Capped at 100')).toBeInTheDocument()
  expect(document.querySelector('.d-subject mark.ev')).toHaveTextContent('Every charger at the demo site appears offline')
  expect(document.querySelector('.msg mark.ev')).toHaveTextContent('three drivers are waiting')
  expect(detail().getByText('2 of 2 quotes found word for word in the ticket', { selector: '.verify span' })).toBeInTheDocument()
  expect(apiCallsAfterLaunch(fetchMock)).toEqual(['POST /api/demo/session?seed=true'])
})

test('moves focus to the ticket heading when the reviewer opens one, but not on the automatic open', async () => {
  mockSeededBackend([recordedOffline, recordedReceipt])
  render(<App />)
  await openInbox()

  expect(document.getElementById('detail-subject')).toHaveTextContent('Every charger at the demo site appears offline')
  expect(document.getElementById('detail-subject')).not.toHaveFocus()
  fireEvent.click(row('Receipt requested'))

  await waitFor(() => expect(document.getElementById('detail-subject')).toHaveFocus())
  expect(document.getElementById('detail-subject')).toHaveTextContent('Receipt requested for a completed session')
})

test('accepts the AI call with one click and logs it, without a note or an urgency', async () => {
  const bodies: Array<Record<string, unknown>> = []
  mockSeededBackend([recordedOffline, apiTicket], {}, (body) => { bodies.push(body); return response({ id: 'feedback-1', decision: body.decision }, 201) })
  render(<App />)
  await openInbox()

  expect(detail().getByText('Connectivity', { selector: '.rv-text' , exact: false })).toBeInTheDocument()
  fireEvent.click(detail().getByRole('button', { name: 'Accept' }))

  await waitFor(() => expect(detail().getByText('Accepted.')).toBeInTheDocument())
  expect(bodies).toEqual([expect.objectContaining({ decision: 'ACCEPTED', triageResultId: 'result-ticket-a104', originalResultId: 'result-ticket-a104' })])
  expect(bodies[0]).not.toHaveProperty('urgency')
  expect(bodies[0]).not.toHaveProperty('note')
  expect(screen.getByRole('status')).toHaveTextContent("Accepted. The AI's call is logged as confirmed.")
  expect(row('Every charger')).toHaveTextContent('Accepted')
  expect(screen.getByRole('button', { name: 'Inbox' })).not.toHaveTextContent('to review')
  // There is no Undo: the next step is another review, added beside this one.
  expect(detail().queryByRole('button', { name: /Undo/ })).not.toBeInTheDocument()
  expect(detail().getByRole('button', { name: 'Override urgency' })).toBeEnabled()
})

test('overrides urgency without a reason, keeps the AI score as the original, and changes the review by adding another', async () => {
  const bodies: Array<Record<string, unknown>> = []
  mockSeededBackend([recordedOffline], {}, (body) => { bodies.push(body); return response({ id: `feedback-${bodies.length}` }, 201) })
  render(<App />)
  await openInbox()

  fireEvent.click(detail().getByRole('button', { name: 'Override urgency' }))
  expect(detail().getByRole('group', { name: 'Change urgency from Critical to' })).toBeInTheDocument()
  expect(detail().getByRole('button', { name: /^Critical/ })).toBeDisabled()
  expect(detail().getByRole('button', { name: 'Save' })).toBeDisabled()
  fireEvent.click(detail().getByRole('button', { name: /^High/ }))
  expect(detail().getByRole('button', { name: /^High/ })).toHaveAttribute('aria-pressed', 'true')
  fireEvent.click(detail().getByRole('button', { name: 'Save' }))

  await waitFor(() => expect(detail().getByText('Overridden.')).toBeInTheDocument())
  expect(bodies[0]).toEqual(expect.objectContaining({ decision: 'CORRECTED', urgency: 'HIGH', triageResultId: 'result-ticket-a104' }))
  expect(bodies[0]).not.toHaveProperty('note')
  expect(detail().getByText('AI priority (original)')).toBeInTheDocument()
  expect(detail().getByText("This score comes from the AI's original Critical call and is never overwritten.")).toBeInTheDocument()
  expect(detail().getByText('AI said Critical')).toBeInTheDocument()
  expect(row('Every charger')).toHaveTextContent('You, AI said Critical')
  // The queue keeps its order by the AI's score: an override is logged beside it, never rescored.
  expect(screen.getByText('No critical tickets. Top of the queue: every charger at the demo site appears offline.')).toBeInTheDocument()

  fireEvent.click(detail().getByRole('button', { name: 'Change review' }))
  expect(detail().getByRole('button', { name: /^High/ })).toBeDisabled()
  fireEvent.click(detail().getByRole('button', { name: "Critical AI's call, accept it" }))
  fireEvent.click(detail().getByRole('button', { name: 'Save' }))

  await waitFor(() => expect(detail().getByText('Accepted.')).toBeInTheDocument())
  expect(bodies[1]).toEqual(expect.objectContaining({ decision: 'ACCEPTED' }))
  expect(detail().getByText(/2 reviews are logged for this ticket/)).toBeInTheDocument()
})

test('shows the latest review a seeded ticket already has', async () => {
  mockSeededBackend([{ ...recordedOffline, latestFeedback: { decision: 'CORRECTED', urgency: 'MEDIUM', note: 'Only one site.', createdAt: '2026-07-11T09:20:00Z' } }])
  render(<App />)
  await openInbox()

  expect(detail().getByText('Overridden.')).toBeInTheDocument()
  expect(detail().getByText('“Only one site.”')).toBeInTheDocument()
  expect(row('Every charger')).toHaveTextContent('Medium')
})

test('never calls a correction that kept the AI urgency an Accept', async () => {
  mockSeededBackend([{ ...recordedOffline, latestFeedback: { decision: 'CORRECTED', urgency: null, note: 'Category was wrong.', createdAt: '2026-07-11T09:20:00Z' } }])
  render(<App />)
  await openInbox()

  expect(detail().getByText('Corrected.')).toBeInTheDocument()
  expect(detail().queryByText('Accepted.')).not.toBeInTheDocument()
  expect(row('Every charger')).toHaveTextContent('Corrected')
})

test('says plainly why the pack rules decided a ticket, and labels it as a rules fallback', async () => {
  mockSeededBackend([apiTicket], { 'ticket-a102': () => response({ job: completedJob, result: enriched('ticket-a102', { decisionSource: 'RULES_FALLBACK', fallbackReason: 'BUDGET_SPENT', reliabilitySignal: 'LOW', evidence: [{ quote: PAYMENT, meaning: 'Matched the pack keywords for charging sessions.' }] }) }) })
  render(<App />)
  await openInbox()
  fireEvent.click(row(PAYMENT))

  await waitFor(() => expect(detail().getByText('Rules fallback', { selector: '.badge' })).toBeInTheDocument())
  expect(document.querySelector('.fallback-note')).toHaveTextContent("Rules fallback. Today's live AI budget for this demo is spent. Fixed pack rules decided this ticket instead")
  expect(detail().getByRole('heading', { name: 'Why the rules decided this' })).toBeInTheDocument()
  expect(document.querySelector('.d-subject mark.ev')).toHaveTextContent(PAYMENT)
  expect(row(PAYMENT)).toHaveTextContent('Rules fallback')
})

test('calls a rules-fallback decision the rules\' call everywhere, never the AI\'s', async () => {
  mockSeededBackend([apiTicket], { 'ticket-a102': () => response({ job: completedJob, result: enriched('ticket-a102', { decisionSource: 'RULES_FALLBACK', fallbackReason: 'PROVIDER_UNAVAILABLE', reliabilitySignal: 'LOW', urgency: 'MEDIUM', evidence: [{ quote: PAYMENT, meaning: 'Matched the pack keywords.' }] }) }) })
  render(<App />)
  await openInbox()
  fireEvent.click(row(PAYMENT))
  await waitFor(() => expect(detail().getByText('Rules fallback', { selector: '.badge' })).toBeInTheDocument())

  expect(document.querySelector('.rv-text')).toHaveTextContent('Rules call: Medium, General')
  expect(detail().getByText("The number comes from fixed rules: the rules' urgency and response-time risk plus words in the ticket.")).toBeInTheDocument()
  fireEvent.click(detail().getByRole('button', { name: 'Accept' }))
  await waitFor(() => expect(detail().getByText(/The rules' call is logged as confirmed\./)).toBeInTheDocument())
  expect(screen.getByRole('status')).toHaveTextContent("Accepted. The rules' call is logged as confirmed.")

  fireEvent.click(detail().getByRole('button', { name: 'Override urgency' }))
  expect(detail().getByRole('button', { name: "Medium Rules' call" })).toBeDisabled()
  expect(detail().getByPlaceholderText('What did the rules miss?')).toBeInTheDocument()
  fireEvent.click(detail().getByRole('button', { name: /^High/ }))
  fireEvent.click(detail().getByRole('button', { name: 'Save' }))
  await waitFor(() => expect(detail().getByText('Overridden.')).toBeInTheDocument())
  expect(detail().getByText('Rules said Medium')).toBeInTheDocument()
  expect(detail().getByText('Rules priority (original)')).toBeInTheDocument()
  expect(row(PAYMENT)).toHaveTextContent('You, rules said Medium')
  expect(screen.getByRole('status')).toHaveTextContent('Override logged. Rules Medium, you High.')
  expect(document.body.textContent).not.toMatch(/AI call|AI said|AI's call|AI priority|from the AI's|AI: |the AI miss/)
})

test('gives a general fallback reason when an older API sends none', async () => {
  mockSeededBackend([apiTicket], { 'ticket-a102': () => response({ job: completedJob, result: { ...legacyResult, decisionSource: 'RULES_FALLBACK', reliabilitySignal: 'LOW' } }) })
  render(<App />)
  await openInbox()
  fireEvent.click(row(PAYMENT))

  await waitFor(() => expect(document.querySelector('.fallback-note')).toHaveTextContent('The live AI was unavailable or its answer failed a check.'))
})

test('lists every matched policy in the phone policy card, not only the first', async () => {
  const policies = [{ id: 'OCPP_CONNECTIVITY', label: 'OCPP connectivity', guidance: 'Verify heartbeat and network path.' }, { id: 'CHARGER_SITE_OUTAGE', label: 'Charger site outage', guidance: 'Check the site network and notify the operator.' }]
  mockSeededBackend([{ ...recordedOffline, latestResult: { ...recordedOffline.latestResult, policies } }])
  render(<App />)
  await openInbox()

  const card = document.querySelector<HTMLElement>('.b-pol')!
  expect(card).toHaveTextContent('OCPP connectivity policy')
  expect(card).toHaveTextContent('Charger site outage policy')
  expect(card).toHaveTextContent('Check the site network and notify the operator.')
})

test('lights every reason a shared highlight carries', async () => {
  mockSeededBackend([{ ...recordedOffline, latestResult: enriched('ticket-a104', { recorded: true, evidence: [{ quote: 'three drivers are waiting', meaning: 'Drivers are blocked now.' }, { quote: 'drivers are waiting', meaning: 'People are on site.' }] }) }])
  render(<App />)
  await openInbox()

  fireEvent.mouseEnter(document.querySelector('.msg mark.ev')!)
  expect(detail().getByText('Drivers are blocked now.').closest('li')).toHaveClass('on')
  expect(detail().getByText('People are on site.').closest('li')).toHaveClass('on')
})

test('shows a background failure when the ticket is opened again, and retries only on Try again', async () => {
  let polls = 0
  const fetchMock = mockSeededBackend([apiTicket, recordedReceipt], { 'ticket-a102': () => (polls++ === 0 ? pendingJob('TERMINAL_FAILURE') : completedProcessing('ticket-a102', 82, 'HIGH')) })
  render(<App />)
  await openInbox()
  const triagePosts = () => fetchMock.mock.calls.filter(([url, init]) => String(url).endsWith('/ticket-a102/triage') && init?.method === 'POST').length

  fireEvent.click(row(PAYMENT))
  fireEvent.click(row('Receipt requested'))
  await waitFor(() => expect(row(PAYMENT)).toHaveTextContent('Triage failed'))
  fireEvent.click(row(PAYMENT))

  expect(detail().getByRole('alert')).toHaveTextContent('This ticket could not be triaged. Open Quality and operations to retry it.')
  expect(detail().queryByText('Open this ticket to run the live AI on it.')).not.toBeInTheDocument()
  expect(triagePosts()).toBe(1)
  fireEvent.click(detail().getByRole('button', { name: 'Try again' }))
  await waitFor(() => expect(detail().getByText('A test reason for ticket-a102.')).toBeInTheDocument())
  expect(triagePosts()).toBe(2)
})

test('reads the status again, without a new triage request, when a ticket whose wait ran out is opened again', async () => {
  let running = true
  const fetchMock = mockSeededBackend([apiTicket, recordedReceipt], { 'ticket-a102': () => running ? pendingJob('PROCESSING') : completedProcessing('ticket-a102', 82, 'HIGH') })
  render(<App />)
  await openInbox()
  vi.useFakeTimers({ shouldAdvanceTime: true })
  const triagePosts = () => fetchMock.mock.calls.filter(([url, init]) => String(url).endsWith('/ticket-a102/triage') && init?.method === 'POST').length

  fireEvent.click(row(PAYMENT))
  // The page waits 180 seconds (a rate-limited triage is retried once after about 90 s); still waiting at 170 s.
  await act(async () => { await vi.advanceTimersByTimeAsync(170_000) })
  expect(detail().queryByRole('alert')).not.toBeInTheDocument()
  await act(async () => { await vi.advanceTimersByTimeAsync(15_000) })
  await waitFor(() => expect(detail().getByRole('alert')).toHaveTextContent('Triage is still running. Open the ticket again in a minute to see the result.'))
  running = false
  fireEvent.click(row('Receipt requested'))
  const reads = calls(fetchMock, '/ticket-a102/processing')
  fireEvent.click(row(PAYMENT))

  await waitFor(() => expect(detail().getByText('A test reason for ticket-a102.')).toBeInTheDocument())
  expect(triagePosts()).toBe(1)
  expect(calls(fetchMock, '/ticket-a102/processing')).toBeGreaterThan(reads)
})

test('clears a ticket\'s failure once Quality and operations retries it, so opening it loads the result', async () => {
  const backend = mockBackend({ pollSteps: ['TERMINAL_FAILURE'] })
  let retried = false
  const failedJob = { id: 'job-1', eventId: 'event-1', ticketId: 'ticket-a102', status: 'TERMINAL_FAILURE', attemptCount: 3, startedAt: '2026-07-11T09:14:01Z', completedAt: null, lastErrorCode: 'PROVIDER_TIMEOUT', attempts: [] }
  const fetchMock = stubOver(backend, (url) => {
    if (url.endsWith('/retry')) { retried = true; return response({ ...failedJob, id: 'job-2', status: 'QUEUED', lastErrorCode: null }) }
    if (url.includes('/api/operations/failures')) return response(retried ? { content: [], page: 0, size: 50, totalElements: 0, totalPages: 0 } : { content: [failedJob], page: 0, size: 50, totalElements: 1, totalPages: 1 })
    return undefined
  })
  render(<App />)
  await openInbox()
  fireEvent.click(row(PAYMENT))
  await waitFor(() => expect(detail().getByRole('alert')).toHaveTextContent('This ticket could not be triaged.'))

  fireEvent.click(screen.getByRole('button', { name: 'Quality and operations' }))
  await waitFor(() => expect(screen.getByRole('button', { name: 'Retry failed case' })).toBeEnabled())
  fireEvent.click(screen.getByRole('button', { name: 'Retry failed case' }))
  await waitFor(() => expect(retried).toBe(true))
  fireEvent.click(screen.getByRole('button', { name: 'Inbox' }))
  await waitFor(() => expect(row(PAYMENT)).not.toHaveTextContent('Triage failed'))
  fireEvent.click(row(PAYMENT))

  await waitFor(() => expect(detail().getByText('AI verified')).toBeInTheDocument())
  expect(fetchMock.mock.calls.filter(([url, init]) => String(url).endsWith('/ticket-a102/triage') && init?.method === 'POST')).toHaveLength(2)
})

test('keeps Re-run live off while a review is being saved', async () => {
  const seeded = mockSeededBackend([recordedOffline])
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => String(input).endsWith('/feedback') ? new Promise<Response>(() => undefined) : seeded(input, init)))
  render(<App />)
  await openInbox()

  fireEvent.click(detail().getByRole('button', { name: 'Accept' }))
  await waitFor(() => expect(detail().getByRole('button', { name: 'Saving...' })).toBeDisabled())
  for (const button of detail().getAllByRole('button', { name: 'Re-run live' })) expect(button).toBeDisabled()
  fireEvent.click(screen.getByRole('button', { name: 'Close demo' }))
})

test('shows a dash, never 0% or "null ms", for rates and latencies an API leaves empty, and the empty-state copy', async () => {
  const backend = mockBackend()
  stubOver(backend, (url) => url.endsWith('/api/evaluation') ? response({ generatedAt: '2026-07-11T09:14:02Z', triageResults: 3, evaluatedResults: 0, categoryAgreementRate: null, urgencyAgreementRate: null, agreementRate: null, correctionRate: null, medianLatencyMs: null, p95LatencyMs: null, failureRate: null, providerUsage: [], recentEvents: [], reviewedResults: 0, acceptedResults: 0, acceptanceRate: null, overrideRate: null, recordedResults: 3 })
    : url.endsWith('/api/operations/overview') ? response({ generatedAt: '2026-07-11T09:14:02Z', queue: { queued: 0, processing: 0, completed: 0, retryableFailures: 0, terminalFailures: 0 }, fallbackCount: 0, providerFailureCount: 0, recent: [], providers: [] }) : undefined)
  render(<App />)
  await openInbox()
  await openQuality()

  expect(screen.queryByText(/\d%/)).not.toBeInTheDocument()
  expect(document.body.textContent).not.toMatch(/null|0 ms/)
  expect(screen.getAllByText('Not measured yet').length).toBeGreaterThanOrEqual(4)
  expect(screen.getByText('No reviews yet')).toBeInTheDocument()
  expect(screen.getByText(/0 of 3 recorded tickets reviewed\./)).toBeInTheDocument()
  expect(screen.getByText('No live worker runs yet. Open any untriaged ticket in the Inbox to run it live, or use Simulate provider timeout above to test failure recovery.')).toBeInTheDocument()
  expect(screen.getByRole('button', { name: 'Simulate provider timeout' })).toBeEnabled()
})

test('shows an older API\'s 0 rate as a dash when nothing has been scored', async () => {
  const backend = mockBackend()
  stubOver(backend, (url) => url.endsWith('/api/evaluation') ? response({ generatedAt: '2026-07-11T09:14:02Z', triageResults: 0, evaluatedResults: 0, categoryAgreementRate: 0, urgencyAgreementRate: 0, agreementRate: 0, correctionRate: 0, medianLatencyMs: 0, p95LatencyMs: 0, failureRate: 0, providerUsage: [], recentEvents: [] }) : undefined)
  render(<App />)
  await openInbox()
  await openQuality()

  const benchmark = screen.getByText('Benchmark match', { selector: '.qo-label' }).closest('.qo-figure')!
  expect(benchmark).toHaveTextContent('Not measured yet')
  expect(benchmark).not.toHaveTextContent('0%')
})

test('lists a reason whose quote is not in the ticket, and says how many quotes were found', async () => {
  mockSeededBackend([{ ...apiTicket, latestResult: enriched('ticket-a102', { recorded: true, evidence: [{ quote: 'The driver was charged', meaning: 'Money was taken.' }, { quote: 'a sentence that is not there', meaning: 'Something else.' }] }) }])
  render(<App />)
  await openInbox()

  expect(detail().getByText('Quote not found in the ticket text: “a sentence that is not there”')).toBeInTheDocument()
  expect(detail().getByText('1 of 2 quotes found word for word in the ticket', { selector: '.verify span' })).toBeInTheDocument()
  expect(document.querySelectorAll('mark.ev')).toHaveLength(1)
})

test('pairs a reason with its highlighted quote on hover and keyboard focus', async () => {
  mockSeededBackend([recordedOffline])
  render(<App />)
  await openInbox()

  const reason = detail().getByText('Drivers are blocked now.').closest('li')!
  act(() => reason.focus())
  expect(document.querySelector('.msg mark.ev')).toHaveClass('on')
  expect(document.querySelector('.d-subject mark.ev')).not.toHaveClass('on')
  fireEvent.mouseEnter(document.querySelector('.d-subject mark.ev')!)
  expect(detail().getByText('The whole site is down.').closest('li')).toHaveClass('on')
})

test('re-runs a recorded ticket live and replaces the recorded result with the new one', async () => {
  let polls = 0
  const fetchMock = mockSeededBackend([recordedOffline], {
    'ticket-a104': () => {
      polls += 1
      // The first answer still carries the recorded result with no job: the page keeps waiting for a new one.
      if (polls === 1) return response({ job: null, result: recordedOffline.latestResult })
      if (polls === 2) return pendingJob('PROCESSING', null, 'ticket-a104')
      return response({ job: { ...completedJob, ticketId: 'ticket-a104' }, result: enriched('ticket-a104', { id: 'result-live', urgency: 'HIGH', priorityScore: 60, categoryLabel: 'Connectivity' }) })
    },
  })
  render(<App />)
  await openInbox()

  fireEvent.click(detail().getAllByRole('button', { name: 'Re-run live' })[0])
  await waitFor(() => expect(fetchMock).toHaveBeenCalledWith(expect.stringContaining('/api/tickets/ticket-a104/triage'), expect.objectContaining({ method: 'POST' })))
  expect(detail().getByRole('list', { name: 'Live triage progress' })).toBeInTheDocument()
  expect(row('Every charger')).toHaveTextContent('Queued')

  await waitFor(() => expect(detail().getByText('AI verified')).toBeInTheDocument(), { timeout: 5_000 })
  expect(detail().queryByText('Recorded AI result')).not.toBeInTheDocument()
  expect(detail().getByRole('button', { name: /Outbox → SQS → Worker/ })).toBeInTheDocument()
})

// The phone shows one pane at a time and keeps the review bar in reach.
function asPhone() {
  vi.stubGlobal('matchMedia', (query: string) => ({ matches: query.includes('max-width: 699px'), media: query, addEventListener: () => undefined, removeEventListener: () => undefined }))
}

test('on a phone, opens on the list, shows a ticket as its own screen, and returns to the list with Back', async () => {
  asPhone()
  const pushState = vi.spyOn(window.history, 'pushState')
  mockSeededBackend([recordedOffline, apiTicket])
  render(<App />)
  await openInbox()

  expect(document.querySelector('.app')).toHaveAttribute('data-pane', 'list')
  expect(document.querySelector('.detail-empty')).toBeInTheDocument()
  const scrollTo = vi.fn()
  vi.stubGlobal('scrollTo', scrollTo)
  fireEvent.click(row('Every charger'))
  expect(document.querySelector('.app')).toHaveAttribute('data-pane', 'detail')
  expect(pushState).toHaveBeenCalledTimes(1)
  // The ticket screen starts at the top, with focus on its heading.
  await waitFor(() => expect(document.getElementById('detail-subject')).toHaveFocus())
  expect(scrollTo).toHaveBeenCalledWith(0, 0)
  // With the list hidden, the ticket subject is the page's main heading.
  expect(screen.getByRole('heading', { level: 1, name: /Every charger at the demo site appears offline/ })).toBeInTheDocument()
  expect(screen.getByRole('region', { name: 'Review' })).toBeInTheDocument()
  expect(detail().getByRole('button', { name: 'Accept' })).toBeInTheDocument()

  // The system Back gesture returns to the list instead of leaving the demo.
  act(() => { window.dispatchEvent(new PopStateEvent('popstate')) })
  expect(document.querySelector('.app')).toHaveAttribute('data-pane', 'list')
  fireEvent.click(row('Every charger'))
  fireEvent.click(detail().getByRole('button', { name: 'Back to inbox' }))
  expect(document.querySelector('.app')).toHaveAttribute('data-pane', 'list')
  pushState.mockRestore()
})

test('on a phone, the menu holds the screens, How it\'s built and the byline', async () => {
  asPhone()
  mockSeededBackend([recordedOffline])
  render(<App />)
  await openInbox()

  fireEvent.click(screen.getByRole('button', { name: 'Open menu' }))
  const menu = screen.getByRole('dialog', { name: 'Menu' })
  expect(within(menu).getByRole('link', { name: 'Harish Darko' })).toHaveAttribute('href', 'https://harishdarko.com')
  expect(within(menu).getByText('Demo workspace, EV charging')).toBeInTheDocument()
  expect(within(menu).getAllByRole('button').map((button) => button.textContent)).toEqual(['', 'Inbox', 'Quality and operations', "How it's built", 'Close demo'])
  fireEvent.click(within(menu).getByRole('button', { name: 'Quality and operations' }))
  expect(screen.queryByRole('dialog', { name: 'Menu' })).not.toBeInTheDocument()
  expect(screen.getByRole('heading', { name: 'Quality and operations' })).toBeInTheDocument()
})

// Neon review CL-13, CL-23, CL-24 and CL-16: polling stops with the workspace, an ended session stops live updates,
// a correction never repeats the original result, and an unfinished job does not look like a 0 ms rules decision.
function stubOver(backend: ReturnType<typeof mockBackend>, override: (url: string, init?: RequestInit) => Response | undefined) {
  const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => override(String(input), init) ?? backend(input, init))
  vi.stubGlobal('fetch', fetchMock)
  return fetchMock
}

test('stops the triage poll when the demo closes, and the next workspace starts without the old token', async () => {
  const fetchMock = await openTicketWithFakeTimers({ pendingProcessingPolls: 100 })
  await act(async () => { await vi.advanceTimersByTimeAsync(3_000) })
  expect(calls(fetchMock, '/processing')).toBeGreaterThan(0)

  fireEvent.click(screen.getByRole('button', { name: 'Close demo' }))
  const atClose = calls(fetchMock, '/processing')
  await act(async () => { await vi.advanceTimersByTimeAsync(20_000) })
  expect(calls(fetchMock, '/processing')).toBe(atClose)

  await openInbox()
  const sessionPosts = fetchMock.mock.calls.filter(([url, init]) => isSessionPost(String(url), init))
  expect(sessionPosts).toHaveLength(2)
  expect(new Headers(sessionPosts[1]?.[1]?.headers).get('Authorization')).toBeNull()
  await act(async () => { await vi.advanceTimersByTimeAsync(5_000) })
  expect(calls(fetchMock, '/processing')).toBe(atClose)
  expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  expect(document.querySelector('.stage-note')).toBeNull()
})

test('stops live updates when the session has ended and offers a new demo', async () => {
  const fetchMock = await openView({ laterEvaluation: () => Promise.resolve(response({ detail: 'Unauthorized' }, 401)) })

  await waitFor(() => expect(screen.getByRole('status')).toHaveTextContent('Your demo session has ended, so live updates stopped.'))
  const ended = calls(fetchMock, '/api/evaluation')
  await advance(60_000)
  expect(calls(fetchMock, '/api/evaluation')).toBe(ended)

  const sessionPosts = () => fetchMock.mock.calls.filter(([url, init]) => isSessionPost(String(url), init)).length
  const before = sessionPosts()
  fireEvent.click(screen.getByRole('button', { name: 'Start a new demo' }))
  await waitFor(() => expect(sessionPosts()).toBe(before + 1))
})

test('keeps the error from a failed action on screen while the view refreshes in the background', async () => {
  const backend = mockBackend({ syntheticFailure: true })
  stubOver(backend, (url) => url.endsWith('/retry') ? response({ detail: 'Request failed with status 500' }, 500) : undefined)
  render(<App />)
  await openInbox()
  vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval', 'Date'] })
  fireEvent.click(screen.getByRole('button', { name: 'Quality and operations' }))
  await advance(0)
  await waitFor(() => expect(screen.getByRole('button', { name: 'Retry case' })).toBeEnabled())

  fireEvent.click(screen.getByRole('button', { name: 'Retry case' }))
  await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent('The CaseLens service had a problem. Try again in a few seconds.'))
  await advance(10_000)

  expect(screen.getByRole('alert')).toHaveTextContent('The CaseLens service had a problem. Try again in a few seconds.')
})

async function overrideToHigh(note = 'Lower than the model said.') {
  fireEvent.click(row(PAYMENT))
  await waitFor(() => expect(detail().getByRole('button', { name: 'Override urgency' })).toBeEnabled())
  fireEvent.click(detail().getByRole('button', { name: 'Override urgency' }))
  fireEvent.click(detail().getByRole('button', { name: /^High/ }))
  fireEvent.change(detail().getByLabelText(/^Reason/), { target: { value: note } })
  fireEvent.click(detail().getByRole('button', { name: 'Save' }))
}

test('says plainly when the API refuses a correction (409), without claiming which conflict it was', async () => {
  const backend = mockBackend()
  // The API sends the same generic detail for every refused correction.
  stubOver(backend, (url) => url.includes('/feedback') ? response({ detail: 'The requested operation is not valid' }, 409) : undefined)
  render(<App />)
  await openInbox()
  await overrideToHigh()

  await waitFor(() => expect(detail().getByRole('alert')).toHaveTextContent('This correction conflicts with the current triage result of this ticket, so it was not saved. Open the ticket again and check it.'))
  expect(screen.queryByText(/409|not valid|Try again in a few seconds/)).not.toBeInTheDocument()
  // The override panel stays open with the reviewer's choice, so they can try again.
  expect(detail().getByRole('button', { name: /^High/ })).toHaveAttribute('aria-pressed', 'true')
})

test('says the workspace is full when the API refuses a correction for the workspace cap', async () => {
  const backend = mockBackend()
  stubOver(backend, (url) => url.includes('/feedback')
    ? response({ type: 'urn:caselens:problem:workspace-limit', code: 'workspace-limit', detail: 'This demo workspace holds at most 100 feedback entries.' }, 409)
    : undefined)
  render(<App />)
  await openInbox()
  await overrideToHigh()

  await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent('This demo workspace has reached its limit. Start a new demo to keep going.'))
  expect(screen.queryByText(/conflicts with the current triage result/)).not.toBeInTheDocument()
})

test('keeps the original urgency apart from the saved override, and shows the priority and suggested reply', async () => {
  const fetchMock = mockBackend()
  render(<App />)
  await openInbox()
  fireEvent.click(row(PAYMENT))
  await waitFor(() => expect(detail().getByText('AI verified')).toBeInTheDocument())
  expect(document.querySelector('.d-head .tags')).toHaveTextContent('Priority 82')
  expect(detail().getByText('We are reviewing the session start.')).toBeInTheDocument()
  expect(detail().getByText('Critical urgency baseline')).toBeInTheDocument()

  fireEvent.click(detail().getByRole('button', { name: 'Override urgency' }))
  fireEvent.click(detail().getByRole('button', { name: /^High/ }))
  expect(detail().getByRole('button', { name: /^High/ })).toHaveAttribute('aria-pressed', 'true')
  expect(detail().getByRole('button', { name: /^Critical/ })).toHaveAttribute('aria-pressed', 'false')
  fireEvent.change(detail().getByLabelText('Reason'), { target: { value: 'The synthetic case is less urgent.' } })
  fireEvent.click(detail().getByRole('button', { name: 'Save' }))
  await waitFor(() => expect(detail().getByText('AI said Critical')).toBeInTheDocument())

  // Going back to the urgency the model gave would send a no-op this API refuses, and the saved override is already
  // logged, so neither can be picked again.
  fireEvent.click(detail().getByRole('button', { name: 'Change review' }))
  expect(detail().getByRole('button', { name: /^Critical/ })).toBeDisabled()
  expect(detail().getByRole('button', { name: /^High/ })).toBeDisabled()
  expect(detail().getByRole('button', { name: 'Save' })).toBeDisabled()
  expect(calls(fetchMock, '/feedback')).toBe(1)
})

test('shows an unfinished job as Pending and Not called, not as a 0 ms rules decision', async () => {
  const backend = mockBackend()
  stubOver(backend, (url) => {
    if (url.endsWith('/api/evaluation')) return response({ generatedAt: '2026-07-11T09:14:02Z', triageResults: 1, evaluatedResults: 0, categoryAgreementRate: 0, urgencyAgreementRate: 0, agreementRate: 0, correctionRate: 0, medianLatencyMs: 0, p95LatencyMs: 0, failureRate: 0, providerUsage: [], recentEvents: [{ resultId: 'result-9', createdAt: '2026-07-11T09:14:02Z', latencyMs: null, provider: null, modelVersion: 'rules-v1', decisionSource: 'RULES_FALLBACK', evaluated: false, corrected: false }] })
    if (url.endsWith('/api/operations/overview')) return response({ generatedAt: '2026-07-11T09:14:02Z', queue: { queued: 1, processing: 0, completed: 0, retryableFailures: 0, terminalFailures: 0 }, fallbackCount: 0, providerFailureCount: 0, recent: [{ jobId: 'job-9', ticketId: 'ticket-a102', status: 'QUEUED', attemptCount: 0, createdAt: '2026-07-11T09:14:02Z', updatedAt: '2026-07-11T09:14:02Z', completedAt: null, lastErrorCode: null, provider: null, modelVersion: null, decisionSource: null, latencyMs: null }], providers: [] })
    return undefined
  })
  render(<App />)
  await openInbox()

  await openQuality()
  await waitFor(() => expect(screen.getByText('Pending', { selector: '.qo-log-latency' })).toBeInTheDocument())
  expect(screen.getByText(/^Not called · 0 attempts · updated/)).toBeInTheDocument()
  expect(screen.queryByText('Rules fallback')).not.toBeInTheDocument()
  expect(screen.queryByText('0 ms', { selector: '.qo-log-latency' })).not.toBeInTheDocument()
  const latency = screen.getByText('Live latency', { selector: '.qo-label' }).closest('.qo-figure')!
  expect(latency).not.toHaveTextContent('0 ms')
})

test('offers a new demo when a ticket action finds the session ended, and moves focus to the new inbox', async () => {
  const backend = mockBackend()
  let expired = true
  const fetchMock = stubOver(backend, (url) => {
    if (url.endsWith('/triage') && expired) { expired = false; return response({ detail: 'Unauthorized' }, 401) }
    return undefined
  })
  render(<App />)
  await openInbox()
  fireEvent.click(row(PAYMENT))

  await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent('Your demo session has ended. Start a new demo to keep exploring.'))
  const sessionPosts = () => fetchMock.mock.calls.filter(([url, init]) => isSessionPost(String(url), init)).length
  expect(sessionPosts()).toBe(1)
  fireEvent.click(screen.getByRole('button', { name: 'Start a new demo' }))

  await waitFor(() => expect(screen.getByRole('heading', { name: 'Inbox' })).toHaveFocus())
  expect(sessionPosts()).toBe(2)
  expect(screen.queryByRole('alert')).not.toBeInTheDocument()
})

test('sends one triage request when a ticket being triaged is opened twice', async () => {
  const fetchMock = mockBackend({ pendingProcessingPolls: 100 })
  render(<App />)
  await openInbox()
  vi.useFakeTimers({ shouldAdvanceTime: true })

  // Both clicks land before React re-renders, so only a synchronous guard can stop the second request.
  const ticketRow = row(PAYMENT)
  act(() => { ticketRow.click(); ticketRow.click() })
  await act(async () => { await vi.advanceTimersByTimeAsync(3_000) })

  expect(fetchMock.mock.calls.filter(([url, init]) => String(url).endsWith('/triage') && init?.method === 'POST')).toHaveLength(1)
  fireEvent.click(screen.getByRole('button', { name: 'Close demo' }))
})

test('keeps the stage text of a ticket still in triage when another ticket finishes', async () => {
  mockSeededBackend([chargerTicket, apiTicket], {
    'ticket-a104': () => pendingJob('QUEUED', null, 'ticket-a104'),
    'ticket-a102': () => completedProcessing('ticket-a102', 82, 'HIGH'),
  })
  render(<App />)
  await openInbox()

  fireEvent.click(row('Every charger'))
  await waitFor(() => expect(document.querySelector('.stage-note')).toHaveTextContent('Waiting for the triage worker to pick this up.'))
  fireEvent.click(row(PAYMENT))
  await waitFor(() => expect(detail().getByText('A test reason for ticket-a102.')).toBeInTheDocument())

  fireEvent.click(row('Every charger'))
  expect(document.querySelector('.stage-note')).toHaveTextContent('Waiting for the triage worker to pick this up.')
  fireEvent.click(screen.getByRole('button', { name: 'Close demo' }))
})

// CL2-09: a live triage can wait three minutes. Only that ticket waits; other tickets stay usable.
test('keeps other tickets reviewable while one ticket waits for live triage', async () => {
  mockSeededBackend([chargerTicket, recordedReceipt], { 'ticket-a104': () => pendingJob('QUEUED', null, 'ticket-a104') })
  render(<App />)
  await openInbox()

  fireEvent.click(row('Every charger'))
  await waitFor(() => expect(document.querySelector('.stage-note')).toHaveTextContent('Waiting for the triage worker to pick this up.'))
  fireEvent.click(row('Receipt requested'))

  expect(detail().getByRole('button', { name: 'Accept' })).toBeEnabled()
  expect(detail().getByRole('button', { name: 'Override urgency' })).toBeEnabled()
  fireEvent.click(screen.getByRole('button', { name: 'Close demo' }))
})

// CL2-14: the ticket rows stay clickable while a review is being saved. A save that finishes after the reviewer opened
// another ticket updates its own ticket and never shows its result on the other one.
test('shows a saved review only on the ticket it was saved for', async () => {
  let finishSave: (value: Response) => void = () => undefined
  const seeded = mockSeededBackend([recordedOffline, recordedReceipt])
  const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => String(input).endsWith('/feedback')
    ? new Promise<Response>((resolve) => { finishSave = resolve })
    : seeded(input, init))
  vi.stubGlobal('fetch', fetchMock)
  render(<App />)
  await openInbox()

  fireEvent.click(detail().getByRole('button', { name: 'Accept' }))
  await waitFor(() => expect(calls(fetchMock, '/feedback')).toBe(1))
  expect(detail().getByRole('button', { name: 'Saving...' })).toBeDisabled()

  fireEvent.click(row('Receipt requested'))
  await waitFor(() => expect(document.querySelector('.d-subject')).toHaveTextContent('Receipt requested for a completed session'))
  await act(async () => { finishSave(response({ id: 'feedback-1' }, 201)) })

  expect(detail().getByRole('button', { name: 'Accept' })).toBeEnabled()
  expect(detail().queryByText('Accepted.')).not.toBeInTheDocument()
  fireEvent.click(row('Every charger'))
  expect(detail().getByText('Accepted.')).toBeInTheDocument()
})

// CL2-13: a throttled live read waits up to 5 seconds before its retry. Leaving the view or closing the demo aborts it,
// so the retry is never sent with the old token.
test('stops a throttled live read from retrying once the reviewer leaves the view', async () => {
  // The seeded open reads nothing from evaluation, so only the live read is throttled.
  const fetchMock = stubOver(mockSeededBackend(), (url) => url.endsWith('/api/evaluation') ? new Response('', { status: 429, headers: { 'Retry-After': '3' } }) : undefined)
  render(<App />)
  await openInbox()
  vi.useFakeTimers({ shouldAdvanceTime: true })

  fireEvent.click(screen.getByRole('button', { name: 'Quality and operations' }))
  await advance(1_000)
  expect(calls(fetchMock, '/api/evaluation')).toBe(1)
  fireEvent.click(screen.getByRole('button', { name: 'Inbox' }))
  await advance(10_000)

  expect(calls(fetchMock, '/api/evaluation')).toBe(1)
})

test('stops throttled operations reads from retrying when the demo closes', async () => {
  const throttled = () => new Response('', { status: 429, headers: { 'Retry-After': '3' } })
  const fetchMock = stubOver(mockSeededBackend(), (url) => url.endsWith('/api/operations/overview') || url.includes('/api/operations/failures') ? throttled() : undefined)
  render(<App />)
  await openInbox()
  vi.useFakeTimers({ shouldAdvanceTime: true })

  fireEvent.click(screen.getByRole('button', { name: 'Quality and operations' }))
  await advance(1_000)
  const failureReads = () => fetchMock.mock.calls.filter(([url]) => String(url).includes('/api/operations/failures')).length
  expect(calls(fetchMock, '/api/operations/overview')).toBe(1)
  expect(failureReads()).toBe(1)
  fireEvent.click(screen.getByRole('button', { name: 'Close demo' }))
  await advance(10_000)

  expect(calls(fetchMock, '/api/operations/overview')).toBe(1)
  expect(failureReads()).toBe(1)
})
