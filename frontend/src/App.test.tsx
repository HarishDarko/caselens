import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
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
type BackendOptions = { syntheticFailure?: boolean; pendingProcessingPolls?: number; pollSteps?: PollStep[]; industriesStatus?: number; sessionStatuses?: number[]; laterEvaluation?: () => Promise<Response> }

function pendingJob(status: string, lastErrorCode: string | null = null) {
  return response({ job: { id: 'job-1', eventId: 'event-1', ticketId: 'ticket-a102', status, attemptCount: status === 'QUEUED' ? 0 : 1, startedAt: null, completedAt: null, lastErrorCode, attempts: [] }, result: null })
}

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
    if (url.endsWith('/api/demo/session') && (init?.method ?? 'GET') === 'GET') return options.industriesStatus ? response({ detail: 'Unavailable' }, options.industriesStatus) : response(sessionOptions)
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
    if (url.includes('/processing') && pendingPolls > 0) { pendingPolls -= 1; return response({ job: { id: 'job-1', eventId: 'event-1', ticketId: 'ticket-a102', status: 'QUEUED', attemptCount: 0, startedAt: null, completedAt: null, lastErrorCode: null, attempts: [] }, result: null }) }
    if (url.includes('/processing')) return response({ job: { id: 'job-1', eventId: 'event-1', ticketId: 'ticket-a102', status: 'COMPLETED', attemptCount: 1, startedAt: '2026-07-11T09:14:01Z', completedAt: '2026-07-11T09:14:02Z', lastErrorCode: null, attempts: [{ id: 'attempt-1', jobId: 'job-1', attemptNumber: 1, status: 'COMPLETED', startedAt: '2026-07-11T09:14:01Z', completedAt: '2026-07-11T09:14:02Z', errorCode: null }] }, result: { id: 'result-1', ticketId: 'ticket-a102', category: 'CHARGING_SESSION', urgency: 'CRITICAL', slaRisk: 'HIGH', sentiment: 'NEGATIVE', summary: 'Payment was accepted but no session started.', evidence: [{ quote: 'The driver was charged', meaning: 'The ticket reports a payment without a session start.' }], policyIds: ['payment-follow-up'], explanation: 'The payment signal and missing session start are the strongest evidence.', recommendedActions: ['Confirm the authorization outcome.'], suggestedReply: 'We are reviewing the session start.', reliabilitySignal: 'HIGH', warnings: [], priorityScore: 82, appliedRules: [], decisionSource: 'AI_VALIDATED', modelVersion: 'openai/gpt-oss-20b', promptVersion: 'triage-v1', createdAt: '2026-07-11T09:14:02Z' } })
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

// The launch button waits for the industry list, so wait until it is enabled before clicking it.
async function launch() {
  await waitFor(() => expect(screen.getByRole('button', { name: 'Launch live demo' })).toBeEnabled())
  fireEvent.click(screen.getByRole('button', { name: 'Launch live demo' }))
}
afterEach(() => { cleanup(); vi.unstubAllGlobals() })

test('renders the honest CaseLens demo shell', async () => {
  render(<App />)
  expect(screen.getByRole('heading', { name: 'CaseLens' })).toBeInTheDocument()
  expect(screen.getByText('Demo environment')).toBeInTheDocument()
  expect(screen.getByText(/No account is required/i)).toBeInTheDocument()
  await waitFor(() => expect(screen.getByRole('button', { name: 'Launch live demo' })).toBeEnabled())
  expect(screen.queryByLabelText(/passcode|password/i)).not.toBeInTheDocument()
})

test('explains a possible serverless cold start while opening the workspace', async () => {
  vi.stubGlobal('fetch', vi.fn((input: RequestInfo | URL, init?: RequestInit) => String(input).endsWith('/api/demo/session') && !init?.method
    ? Promise.resolve(response(sessionOptions)) : new Promise(() => undefined)))
  render(<App />)

  await launch()

  expect(screen.getByRole('button', { name: 'Creating isolated workspace...' })).toBeDisabled()
  expect(screen.getByRole('status')).toHaveTextContent('First access may take up to 20 seconds while serverless services resume.')
})

test('opens an isolated reviewer workspace from the public launch screen', async () => {
  render(<App />)

  await launch()

  await waitFor(() => expect(screen.getByRole('heading', { name: 'Ticket inbox' })).toBeInTheDocument())
  expect(screen.getByText('HD', { selector: '.avatar' })).toBeInTheDocument()
  expect(screen.queryByText('HV', { selector: '.avatar' })).not.toBeInTheDocument()
  expect(screen.getByText('Isolated demo workspace')).toBeInTheDocument()
  expect(screen.getByText('Local review')).toBeInTheDocument()
  expect(screen.getByText('Neon PostgreSQL')).toBeInTheDocument()
  expect(screen.getByText('LocalStack SQS')).toBeInTheDocument()
  expect(screen.getByText('Groq')).toBeInTheDocument()
})

test('opens the reviewer workspace from the real session and ticket endpoints', async () => {
  const fetchMock = mockBackend()
  render(<App />)

  await launch()

  await waitFor(() => expect(screen.getByText('CL-A102')).toBeInTheDocument())
  expect(fetchMock).toHaveBeenCalledWith(expect.stringContaining('/api/demo/session'), expect.objectContaining({ method: 'POST' }))
  expect(fetchMock).toHaveBeenCalledWith(expect.stringContaining('/api/tickets?page=0&size=50'), expect.anything())
})

test('lists the industries and sends the picked industry when the workspace opens', async () => {
  const fetchMock = mockBackend()
  render(<App />)

  expect(await screen.findByRole('radio', { name: /EV charging/ })).toBeChecked()
  fireEvent.click(screen.getByRole('radio', { name: /SaaS support/ }))
  await launch()

  await waitFor(() => expect(screen.getByRole('heading', { name: 'Ticket inbox' })).toBeInTheDocument())
  const sessionCall = fetchMock.mock.calls.find(([url, init]) => isSessionPost(String(url), init))
  expect(JSON.parse(String(sessionCall?.[1]?.body))).toEqual({ industry: 'saas-support' })
  expect(screen.getByText('Industry: SaaS support')).toBeInTheDocument()
  expect(screen.getByText('Whole team locked out after an SSO change')).toBeInTheDocument()
  expect(screen.getByRole('button', { name: 'Load ticket: Whole team locked out after an SSO change' })).toBeEnabled()
})

test('still opens an EV charging workspace when the industry list does not load', async () => {
  const fetchMock = mockBackend({ industriesStatus: 503 })
  render(<App />)

  await waitFor(() => expect(fetchMock).toHaveBeenCalledWith(expect.stringContaining('/api/demo/session'), expect.anything()))
  expect(screen.getByRole('radio', { name: /EV charging/ })).toBeChecked()
  await launch()

  await waitFor(() => expect(screen.getByRole('heading', { name: 'Ticket inbox' })).toBeInTheDocument())
  const sessionCall = fetchMock.mock.calls.find(([url, init]) => isSessionPost(String(url), init))
  expect(JSON.parse(String(sessionCall?.[1]?.body))).toEqual({ industry: 'ev-charging' })
  expect(screen.getByText('Every charger at the demo site appears offline')).toBeInTheDocument()
  expect(screen.getByRole('button', { name: 'Load ticket: Every charger at the demo site appears offline' })).toBeEnabled()
})

test('lets the visitor pick and launch at once, before a cold API answers the industry list', async () => {
  const fetchMock = vi.fn((input: RequestInfo | URL, init?: RequestInit) => String(input).endsWith('/api/demo/session') && !init?.method
    ? new Promise<Response>(() => undefined) : new Promise<Response>(() => undefined))
  vi.stubGlobal('fetch', fetchMock)
  render(<App />)

  expect(screen.getByRole('button', { name: 'Launch live demo' })).toBeEnabled()
  expect(screen.getByRole('radio', { name: /EV charging/ })).toBeChecked()
  fireEvent.click(screen.getByRole('radio', { name: /SaaS support/ }))
  fireEvent.click(screen.getByRole('button', { name: 'Launch live demo' }))

  await waitFor(() => expect(fetchMock).toHaveBeenCalledWith(expect.stringContaining('/api/demo/session'), expect.objectContaining({ method: 'POST', body: JSON.stringify({ industry: 'saas-support' }) })))
  expect(screen.queryByText(/Loading the list of industries/)).not.toBeInTheDocument()
})

test('uses the list from the API when it differs from the bundled one', async () => {
  render(<App />)

  expect(screen.getAllByText('9 demo tickets').length).toBe(3)
  await waitFor(() => expect(screen.getAllByText('1 demo tickets').length).toBe(3))
})

test('gives the full EV journey when the API does not know the industry list yet (405 during a deploy)', async () => {
  mockBackend({ industriesStatus: 405 })
  render(<App />)
  await launch()
  await waitFor(() => expect(screen.getByRole('heading', { name: 'Ticket inbox' })).toBeInTheDocument())

  fireEvent.click(screen.getByRole('button', { name: /Load ticket: Every charger at the demo site appears offline/i }))

  await waitFor(() => expect(screen.getAllByText('Every charger at the demo site appears offline').length).toBeGreaterThan(1))
  expect(screen.getByRole('button', { name: /Review CL-A104/i })).toBeInTheDocument()
})

test('loads a synthetic scenario into the inbox and records reviewer feedback', async () => {
  render(<App />)
  await launch()
  await waitFor(() => expect(screen.getByRole('heading', { name: 'Ticket inbox' })).toBeInTheDocument())

  fireEvent.click(screen.getByRole('button', { name: /Load ticket: Every charger at the demo site appears offline/i }))
  await waitFor(() => expect(screen.getAllByText('Every charger at the demo site appears offline').length).toBeGreaterThan(0))
  fireEvent.click(screen.getByRole('button', { name: /Review CL-A104/i }))
  expect(screen.getByRole('heading', { name: 'Why this ticket is here' })).toBeInTheDocument()
  await waitFor(() => expect(screen.getByRole('heading', { name: 'Durable processing trace' })).toBeInTheDocument())
  expect(screen.getByText('Outbox committed')).toBeInTheDocument()
  expect(screen.getByText('Worker completed')).toBeInTheDocument()
  expect(screen.getByText('Groq validated')).toBeInTheDocument()
  expect(screen.getByText('event-1')).toBeInTheDocument()
  expect(screen.getByText('job-1')).toBeInTheDocument()
  expect(screen.getByText('1 attempt')).toBeInTheDocument()
  await waitFor(() => expect(screen.getByRole('button', { name: 'Mark urgency high' })).not.toBeDisabled())
  fireEvent.click(screen.getByRole('button', { name: 'Mark urgency high' }))
  fireEvent.change(screen.getByLabelText('Review note'), { target: { value: 'The synthetic case indicates higher customer impact.' } })
  fireEvent.click(screen.getByRole('button', { name: 'Save correction' }))
  await waitFor(() => expect(screen.getByRole('status')).toHaveTextContent('Correction saved. Evaluation metrics updated.'))
})

test('keeps waiting through a slow worker start and explains the delay', async () => {
  vi.useFakeTimers({ shouldAdvanceTime: true })
  try {
    mockBackend({ pendingProcessingPolls: 40 })
    render(<App />)
    await launch()
    await waitFor(() => expect(screen.getByText('CL-A102')).toBeInTheDocument())

    fireEvent.click(screen.getByRole('button', { name: /Review CL-A102/i }))
    await vi.advanceTimersByTimeAsync(12_000)
    expect(screen.getByRole('status')).toHaveTextContent('Waiting for the triage worker to pick this up. After a quiet period it can take up to a minute to start.')

    await vi.advanceTimersByTimeAsync(60_000)
    await waitFor(() => expect(screen.getByText('Groq validated')).toBeInTheDocument())
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
    expect(screen.queryByText(/Waiting for the triage worker/)).not.toBeInTheDocument()
  } finally {
    vi.useRealTimers()
  }
})

async function openTicketWithFakeTimers(backend: BackendOptions) {
  const fetchMock = mockBackend(backend)
  render(<App />)
  await launch()
  await waitFor(() => expect(screen.getByText('CL-A102')).toBeInTheDocument())
  vi.useFakeTimers({ shouldAdvanceTime: true })
  fireEvent.click(screen.getByRole('button', { name: /Review CL-A102/i }))
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
  expect(document.querySelector('.lead-copy')).toHaveTextContent('Waiting for the triage worker to pick this up. After a quiet period it can take up to a minute to start.')

  await act(async () => { await vi.advanceTimersByTimeAsync(2_000) })
  expect(document.querySelector('.lead-copy')).toHaveTextContent('The AI model is reading the ticket. This usually takes a few seconds.')
  expect(screen.queryByText(/local worker/i)).not.toBeInTheDocument()

  await act(async () => { await vi.advanceTimersByTimeAsync(3_000) })
  await waitFor(() => expect(screen.getByText('Groq validated')).toBeInTheDocument())
})

test('keeps waiting when one status check is throttled', async () => {
  await openTicketWithFakeTimers({ pollSteps: ['QUEUED', 429] })
  await act(async () => { await vi.advanceTimersByTimeAsync(5_000) })

  await waitFor(() => expect(screen.getByText('Groq validated')).toBeInTheDocument())
  expect(screen.queryByRole('alert')).not.toBeInTheDocument()
})

test('keeps waiting while the worker retries a provider that did not answer', async () => {
  await openTicketWithFakeTimers({ pollSteps: ['PROCESSING', 'RETRYABLE_FAILURE', 'RETRYABLE_FAILURE'] })
  await act(async () => { await vi.advanceTimersByTimeAsync(1_500) })
  expect(document.querySelector('.lead-copy')).toHaveTextContent('The AI model did not answer. The worker tries again by itself in about a minute.')

  await act(async () => { await vi.advanceTimersByTimeAsync(3_000) })
  await waitFor(() => expect(screen.getByText('Groq validated')).toBeInTheDocument())
  expect(screen.queryByRole('alert')).not.toBeInTheDocument()
})

test('says where to go when triage fails', async () => {
  await openTicketWithFakeTimers({ pollSteps: ['TERMINAL_FAILURE'] })
  await act(async () => { await vi.advanceTimersByTimeAsync(500) })

  await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent('This ticket could not be triaged. Open Operations to retry it.'))
  expect(screen.queryByText(/terminal failure/i)).not.toBeInTheDocument()
})

test('explains the controlled retry demo stop in plain words', async () => {
  await openTicketWithFakeTimers({ pollSteps: ['SYNTHETIC_FAILURE'] })
  await act(async () => { await vi.advanceTimersByTimeAsync(500) })

  await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent('The retry demo stopped this triage on purpose. Open Operations to retry it.'))
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

test('requires a reviewer note before saving a correction', async () => {
  render(<App />)
  await launch()
  await waitFor(() => expect(screen.getByText('CL-A102')).toBeInTheDocument())
  fireEvent.click(screen.getByRole('button', { name: /Review CL-A102/i }))
  await waitFor(() => expect(screen.getByRole('button', { name: 'Mark urgency high' })).not.toBeDisabled())
  fireEvent.click(screen.getByRole('button', { name: 'Mark urgency high' }))

  expect(screen.getByRole('button', { name: 'Save correction' })).toBeDisabled()
})

test('launches the fixed synthetic retry demonstration', async () => {
  const fetchMock = mockBackend()
  render(<App />)
  await launch()
  await waitFor(() => expect(screen.getByRole('heading', { name: 'Ticket inbox' })).toBeInTheDocument())

  expect(screen.getByText('Controlled retry demonstration')).toBeInTheDocument()
  fireEvent.click(screen.getByRole('button', { name: 'Run controlled retry demo' }))

  await waitFor(() => expect(fetchMock).toHaveBeenCalledWith(
    expect.stringContaining('/api/demo/scenarios/provider-retry-demo'),
    expect.objectContaining({ method: 'POST' }),
  ))
})

test('shows measured evaluation and operational failure views', async () => {
  render(<App />)
  await launch()
  await waitFor(() => expect(screen.getByRole('heading', { name: 'Ticket inbox' })).toBeInTheDocument())

  fireEvent.click(screen.getByRole('button', { name: 'Evaluation' }))
  expect(screen.getByRole('heading', { name: 'Evaluation signal' })).toBeInTheDocument()
  expect(screen.getByText('Agreement')).toBeInTheDocument()

  fireEvent.click(screen.getByRole('button', { name: 'Operations' }))
  await waitFor(() => expect(screen.getByRole('heading', { name: 'No unresolved failures' })).toBeInTheDocument())
})

test('surfaces persisted evaluation activity and operations telemetry', async () => {
  render(<App />)
  await launch()
  await waitFor(() => expect(screen.getByRole('heading', { name: 'Ticket inbox' })).toBeInTheDocument())

  fireEvent.click(screen.getByRole('button', { name: 'Evaluation' }))
  await waitFor(() => expect(screen.getByText('Recent triage activity')).toBeInTheDocument())
  expect(screen.getByText('openai/gpt-oss-20b')).toBeInTheDocument()
  expect(screen.getByText('AI_VALIDATED')).toBeInTheDocument()
  expect(screen.getAllByText('184 ms').length).toBeGreaterThan(0)

  fireEvent.click(screen.getByRole('button', { name: 'Operations' }))
  await waitFor(() => expect(screen.getByText('Provider failures')).toBeInTheDocument())
  expect(screen.getByText('Completed jobs')).toBeInTheDocument()
  expect(screen.getByText('1', { selector: '.ops-value' })).toBeInTheDocument()
})

test('labels a controlled synthetic timeout as reviewer recovery rather than provider degradation', async () => {
  mockBackend({ syntheticFailure: true })
  render(<App />)
  await launch()
  await waitFor(() => expect(screen.getByRole('heading', { name: 'Ticket inbox' })).toBeInTheDocument())

  fireEvent.click(screen.getByRole('button', { name: 'Operations' }))

  await waitFor(() => expect(screen.getByRole('heading', { name: 'Recovery required' })).toBeInTheDocument())
  expect(screen.getByText('A controlled synthetic timeout was recorded. Retry the case to verify the recovery path.')).toBeInTheDocument()
  expect(screen.getByRole('button', { name: 'Retry case' })).toBeInTheDocument()
  expect(screen.queryByText(/Provider degraded/)).not.toBeInTheDocument()
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

async function openView(name: 'Evaluation' | 'Operations', backend: Parameters<typeof mockBackend>[0] = {}) {
  const fetchMock = mockBackend(backend)
  render(<App />)
  await launch()
  await waitFor(() => expect(screen.getByRole('heading', { name: 'Ticket inbox' })).toBeInTheDocument())
  vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval', 'Date'] })
  fireEvent.click(screen.getByRole('button', { name }))
  await advance(0)
  return fetchMock
}

afterEach(() => {
  vi.useRealTimers()
  delete (document as { visibilityState?: string }).visibilityState
})

test('refreshes Evaluation every 5 seconds only while the tab is visible', async () => {
  const fetchMock = await openView('Evaluation')
  const start = calls(fetchMock, '/api/evaluation')

  await advance(60_000)
  expect(calls(fetchMock, '/api/evaluation') - start).toBe(12)
  expect(screen.getByRole('status')).toHaveTextContent('Live: updates every 5 seconds while this tab is open.')

  setVisibility('hidden')
  const hidden = calls(fetchMock, '/api/evaluation')
  await advance(60_000)
  expect(calls(fetchMock, '/api/evaluation')).toBe(hidden)

  setVisibility('visible')
  await advance(0)
  expect(calls(fetchMock, '/api/evaluation')).toBe(hidden + 1)
})

test('makes no Operations requests from a hidden tab', async () => {
  const fetchMock = await openView('Operations')
  setVisibility('hidden')
  const overview = calls(fetchMock, '/api/operations/overview')
  const failures = calls(fetchMock, '/api/operations/failures?page=0&size=50')

  await advance(10 * 60_000)

  expect(calls(fetchMock, '/api/operations/overview')).toBe(overview)
  expect(calls(fetchMock, '/api/operations/failures?page=0&size=50')).toBe(failures)
})

test('stops refreshing after 10 minutes without activity, says so, and resumes on request', async () => {
  const fetchMock = await openView('Evaluation')

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
  await openView('Evaluation')
  await advance(5_000)
  const announced = screen.getByRole('status').textContent
  const updated = screen.getByText(/Last updated at/).textContent

  await advance(5_000)

  expect(screen.getByRole('status').textContent).toBe(announced)
  expect(screen.getByRole('status')).not.toHaveTextContent(/Last updated/)
  expect(screen.getByText(/Last updated at/).textContent).not.toBe(updated)
})

test('waits for a slow refresh instead of sending overlapping requests', async () => {
  const fetchMock = await openView('Evaluation', { laterEvaluation: () => new Promise<Response>(() => undefined) })
  const started = calls(fetchMock, '/api/evaluation')

  await advance(60_000)

  expect(calls(fetchMock, '/api/evaluation')).toBe(started)
  expect(screen.getByRole('status')).toHaveTextContent('Loading the latest numbers for this workspace.')
})

test('ignores a late answer for a view the reviewer has already left', async () => {
  let failLate: (reason: Error) => void = () => undefined
  await openView('Evaluation', { laterEvaluation: () => new Promise<Response>((_, reject) => { failLate = reject }) })

  fireEvent.click(screen.getByRole('button', { name: 'Operations' }))
  await advance(0)
  expect(screen.getByRole('status')).toHaveTextContent('Live: updates every 5 seconds while this tab is open.')

  await act(async () => { failLate(new Error('Failed to fetch')) })
  await advance(0)

  expect(screen.getByRole('status')).toHaveTextContent('Live: updates every 5 seconds while this tab is open.')
  expect(screen.queryByText('Failed to fetch')).not.toBeInTheDocument()
})

test('says in plain words when the latest numbers could not load', async () => {
  await openView('Evaluation', { laterEvaluation: () => Promise.resolve(response({ detail: 'Request failed with status 503' }, 503)) })

  // The client tries a GET once more after a 503 (1 second later), so the failed state comes after that try.
  await waitFor(() => expect(screen.getByRole('status')).toHaveTextContent('Could not load the latest numbers. The service may be starting or busy. Trying again in 5 seconds.'), { timeout: 3_000 })
  expect(screen.queryByText(/status 503/)).not.toBeInTheDocument()
})

// Opening a workspace in one call: POST /api/demo/session?seed=true seeds the workspace and returns its tickets and the
// runtime summary. mockBackend answers like the API before this change (a bare session), which is the deploy gap.
const seededRuntime = { environment: 'AWS_DEMO', database: 'NEON_POSTGRESQL', queue: 'AMAZON_SQS', aiProvider: 'GROQ' }
const chargerTicket = { ...apiTicket, id: 'ticket-a104', displayId: 'CL-A104', subject: 'Every charger at the demo site appears offline', scenarioKey: 'charger-offline-site-wide', createdAt: '2026-07-11T09:15:00Z' }

function completedProcessing(ticketId: string, priorityScore: number, slaRisk: 'LOW' | 'MEDIUM' | 'HIGH') {
  return response({ job: { id: `job-${ticketId}`, eventId: `event-${ticketId}`, ticketId, status: 'COMPLETED', attemptCount: 1, startedAt: '2026-07-11T09:14:01Z', completedAt: '2026-07-11T09:14:02Z', lastErrorCode: null, attempts: [] }, result: { id: `result-${ticketId}`, ticketId, category: 'GENERAL_QUESTION', urgency: 'LOW', slaRisk, sentiment: 'NEUTRAL', summary: `Summary for ${ticketId}.`, evidence: [{ quote: 'charged', meaning: 'A test quote.' }], policyIds: [], explanation: 'A test explanation.', recommendedActions: [], suggestedReply: '', reliabilitySignal: 'MEDIUM', warnings: [], priorityScore, appliedRules: [], decisionSource: 'AI_VALIDATED', modelVersion: 'test-model', promptVersion: 'triage-v1', createdAt: '2026-07-11T09:14:02Z' } })
}

function mockSeededBackend(tickets: unknown[] = [apiTicket], processing: Record<string, () => Response> = {}) {
  const backend = mockBackend()
  const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input)
    if (url.endsWith('/api/demo/session?seed=true') && init?.method === 'POST') return response({ token: 'demo-token', expiresAt: '2026-07-12T00:00:00Z', industry: 'ev-charging', tickets, runtime: seededRuntime })
    const ticketId = Object.keys(processing).find((id) => url.endsWith(`/api/tickets/${id}/processing`))
    if (ticketId) return processing[ticketId]()
    return backend(input, init)
  })
  vi.stubGlobal('fetch', fetchMock)
  return fetchMock
}

function apiCallsAfterLaunch(fetchMock: ReturnType<typeof vi.fn>) {
  return fetchMock.mock.calls.map(([url, init]) => `${init?.method ?? 'GET'} ${String(url)}`).filter((call) => call !== 'GET /api/demo/session')
}

function rowOrder() {
  return screen.getAllByRole('article').map((row) => row.querySelector('.ticket-id')?.textContent)
}

test('opens a seeded workspace with one request and shows its tickets and runtime', async () => {
  const fetchMock = mockSeededBackend()
  render(<App />)
  await launch()

  await waitFor(() => expect(screen.getByText('CL-A102')).toBeInTheDocument())
  expect(screen.getByText('AWS demo')).toBeInTheDocument()
  expect(screen.getByText('Amazon SQS')).toBeInTheDocument()
  expect(apiCallsAfterLaunch(fetchMock)).toEqual(['POST /api/demo/session?seed=true'])
  const sessionCall = fetchMock.mock.calls.find(([url, init]) => String(url).endsWith('/api/demo/session?seed=true') && init?.method === 'POST')
  expect(JSON.parse(String(sessionCall?.[1]?.body))).toEqual({ industry: 'ev-charging' })
  expect(screen.getByRole('button', { name: 'Operations' })).toHaveTextContent('OK')
})

test('still opens through reset and the separate reads when the API returns a bare session (deploy gap)', async () => {
  const fetchMock = mockBackend()
  render(<App />)
  await launch()

  await waitFor(() => expect(screen.getByText('CL-A102')).toBeInTheDocument())
  expect(screen.getByText('Local review')).toBeInTheDocument()
  expect(apiCallsAfterLaunch(fetchMock)).toEqual(expect.arrayContaining(['POST /api/demo/session?seed=true', 'POST /api/demo/reset', 'GET /api/tickets?page=0&size=50', 'GET /api/demo/runtime']))
})

test('loads Evaluation and Operations when they are opened, and never shows numbers from an earlier workspace', async () => {
  const fetchMock = mockSeededBackend()
  render(<App />)
  await launch()
  await waitFor(() => expect(screen.getByText('CL-A102')).toBeInTheDocument())

  fireEvent.click(screen.getByRole('button', { name: 'Evaluation' }))
  await waitFor(() => expect(screen.getByText('Agreement')).toBeInTheDocument())
  fireEvent.click(screen.getByRole('button', { name: 'Operations' }))
  await waitFor(() => expect(screen.getByRole('heading', { name: 'No unresolved failures' })).toBeInTheDocument())
  expect(calls(fetchMock, '/api/evaluation')).toBe(1)
  expect(calls(fetchMock, '/api/operations/failures?page=0&size=50')).toBe(1)

  fireEvent.click(screen.getByRole('button', { name: 'Close demo' }))
  await launch()
  await waitFor(() => expect(screen.getByText('CL-A102')).toBeInTheDocument())
  fireEvent.click(screen.getByRole('button', { name: 'Evaluation' }))
  expect(screen.getByText('Loading the evaluation numbers for this workspace...')).toBeInTheDocument()
  await waitFor(() => expect(screen.getByText('Agreement')).toBeInTheDocument())
})

test('lists triaged tickets first, by priority score and then SLA risk, and untriaged ones after in API order', async () => {
  const receipt = { ...apiTicket, id: 'ticket-a103', displayId: 'CL-A103', subject: 'Receipt requested for a completed session', scenarioKey: 'receipt-request-general' }
  const offline = { ...chargerTicket, id: 'ticket-a106', displayId: 'CL-A106' }
  mockSeededBackend([receipt, offline, apiTicket], {
    'ticket-a103': () => completedProcessing('ticket-a103', 40, 'LOW'),
    'ticket-a106': () => completedProcessing('ticket-a106', 40, 'HIGH'),
    'ticket-a102': () => completedProcessing('ticket-a102', 82, 'MEDIUM'),
  })
  render(<App />)
  await launch()
  await waitFor(() => expect(screen.getByText('CL-A102')).toBeInTheDocument())
  expect(rowOrder()).toEqual(['CL-A103', 'CL-A106', 'CL-A102'])
  expect(screen.getByText('Triaged cases first, by priority and SLA risk')).toBeInTheDocument()

  fireEvent.click(screen.getByRole('button', { name: /Review CL-A103/ }))
  await waitFor(() => expect(screen.getAllByText('Summary for ticket-a103.').length).toBeGreaterThan(0))
  expect(rowOrder()).toEqual(['CL-A103', 'CL-A106', 'CL-A102'])

  fireEvent.click(screen.getByRole('button', { name: /Review CL-A102/ }))
  await waitFor(() => expect(screen.getAllByText('Summary for ticket-a102.').length).toBeGreaterThan(0))
  expect(rowOrder()).toEqual(['CL-A102', 'CL-A103', 'CL-A106'])

  fireEvent.click(screen.getByRole('button', { name: /Review CL-A106/ }))
  await waitFor(() => expect(screen.getAllByText('Summary for ticket-a106.').length).toBeGreaterThan(0))
  expect(rowOrder()).toEqual(['CL-A102', 'CL-A106', 'CL-A103'])
})

test('opens the featured ticket already in the inbox instead of adding a copy, with a plain label', async () => {
  const fetchMock = mockSeededBackend([chargerTicket, apiTicket])
  render(<App />)
  await launch()
  await waitFor(() => expect(screen.getByText('CL-A104')).toBeInTheDocument())
  expect(screen.queryByRole('button', { name: /charger-offline-site-wide/ })).not.toBeInTheDocument()

  fireEvent.click(screen.getByRole('button', { name: 'Load ticket: Every charger at the demo site appears offline' }))

  await waitFor(() => expect(screen.getByRole('heading', { name: 'Why this ticket is here' })).toBeInTheDocument())
  expect(document.querySelector('.detail-column .ticket-id')).toHaveTextContent('CL-A104')
  expect(screen.getAllByRole('article')).toHaveLength(2)
  expect(fetchMock.mock.calls.some(([url]) => String(url).includes('/api/demo/scenarios/'))).toBe(false)
  await waitFor(() => expect(fetchMock).toHaveBeenCalledWith(expect.stringContaining('/api/tickets/ticket-a104/triage'), expect.objectContaining({ method: 'POST' })))
})
