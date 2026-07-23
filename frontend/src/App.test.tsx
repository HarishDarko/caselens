import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
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

function mockBackend(options: { syntheticFailure?: boolean } = {}) {
  const fetchMock = vi.fn().mockImplementation(async (input: RequestInfo | URL) => {
    if (input === undefined) return response({})
    const url = String(input)
    if (url.endsWith('/api/demo/session')) return response({ token: 'demo-token', expiresAt: '2026-07-12T00:00:00Z' })
    if (url.endsWith('/api/demo/reset')) return response({ seeded: 9 })
    if (url.endsWith('/api/demo/runtime')) return response({ environment: 'LOCAL_REVIEW', database: 'NEON_POSTGRESQL', queue: 'LOCALSTACK_SQS', aiProvider: 'GROQ' })
    if (url.includes('/api/demo/scenarios/provider-retry-demo')) return response({ ...apiTicket, id: 'ticket-a105', displayId: 'CL-A105', subject: 'Synthetic provider timeout for retry review', scenarioKey: 'provider-retry-demo' }, 201)
    if (url.includes('/api/demo/scenarios/')) return response({ ...apiTicket, id: 'ticket-a104', displayId: 'CL-A104', subject: 'Every charger at the demo site appears offline', scenarioKey: 'charger-offline-site-wide' }, 201)
    if (url.includes('/api/tickets?page=')) return response({ content: [apiTicket], page: 0, size: 50, totalElements: 1, totalPages: 1 })
    if (url.includes('/processing')) return response({ job: { id: 'job-1', eventId: 'event-1', ticketId: 'ticket-a102', status: 'COMPLETED', attemptCount: 1, startedAt: '2026-07-11T09:14:01Z', completedAt: '2026-07-11T09:14:02Z', lastErrorCode: null, attempts: [{ id: 'attempt-1', jobId: 'job-1', attemptNumber: 1, status: 'COMPLETED', startedAt: '2026-07-11T09:14:01Z', completedAt: '2026-07-11T09:14:02Z', errorCode: null }] }, result: { id: 'result-1', ticketId: 'ticket-a102', category: 'CHARGING_SESSION', urgency: 'CRITICAL', slaRisk: 'HIGH', sentiment: 'NEGATIVE', summary: 'Payment was accepted but no session started.', evidence: [{ quote: 'The driver was charged', meaning: 'The ticket reports a payment without a session start.' }], policyIds: ['payment-follow-up'], explanation: 'The payment signal and missing session start are the strongest evidence.', recommendedActions: ['Confirm the authorization outcome.'], suggestedReply: 'We are reviewing the session start.', reliabilitySignal: 'HIGH', warnings: [], priorityScore: 82, appliedRules: [], decisionSource: 'AI_VALIDATED', modelVersion: 'openai/gpt-oss-20b', promptVersion: 'triage-v1', createdAt: '2026-07-11T09:14:02Z' } })
    if (url.includes('/triage')) return response({ id: 'job-1', eventId: 'event-1', ticketId: 'ticket-a102', status: 'QUEUED', attemptCount: 0, startedAt: null, completedAt: null, lastErrorCode: null, attempts: [] }, 202)
    if (url.includes('/feedback')) return response({ id: 'feedback-1' }, 201)
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
afterEach(() => { cleanup(); vi.unstubAllGlobals() })

test('renders the honest CaseLens demo shell', () => {
  render(<MemoryRouter><App /></MemoryRouter>)
  expect(screen.getByRole('heading', { name: 'CaseLens' })).toBeInTheDocument()
  expect(screen.getByText('Demo environment')).toBeInTheDocument()
  expect(screen.getByText(/backed by the CaseLens API/i)).toBeInTheDocument()
})

test('explains a possible serverless cold start while opening the workspace', () => {
  vi.stubGlobal('fetch', vi.fn(() => new Promise(() => undefined)))
  render(<MemoryRouter><App /></MemoryRouter>)

  fireEvent.change(screen.getByLabelText('Shared demo passcode'), { target: { value: 'reviewer' } })
  fireEvent.click(screen.getByRole('button', { name: 'Open reviewer workspace' }))

  expect(screen.getByRole('status')).toHaveTextContent('First access may take up to 20 seconds while serverless services resume.')
})

test('opens the reviewer workspace from the shared passcode screen', async () => {
  render(<MemoryRouter><App /></MemoryRouter>)

  fireEvent.change(screen.getByLabelText('Shared demo passcode'), { target: { value: 'reviewer' } })
  fireEvent.click(screen.getByRole('button', { name: 'Open reviewer workspace' }))

  await waitFor(() => expect(screen.getByRole('heading', { name: 'Ticket inbox' })).toBeInTheDocument())
  expect(screen.getByText('Isolated demo workspace')).toBeInTheDocument()
  expect(screen.getByText('Local review')).toBeInTheDocument()
  expect(screen.getByText('Neon PostgreSQL')).toBeInTheDocument()
  expect(screen.getByText('LocalStack SQS')).toBeInTheDocument()
  expect(screen.getByText('Groq')).toBeInTheDocument()
})

test('opens the reviewer workspace from the real session and ticket endpoints', async () => {
  const fetchMock = mockBackend()
  render(<MemoryRouter><App /></MemoryRouter>)

  fireEvent.change(screen.getByLabelText('Shared demo passcode'), { target: { value: 'reviewer' } })
  fireEvent.click(screen.getByRole('button', { name: 'Open reviewer workspace' }))

  await waitFor(() => expect(screen.getByText('CL-A102')).toBeInTheDocument())
  expect(fetchMock).toHaveBeenCalledWith(expect.stringContaining('/api/demo/session'), expect.objectContaining({ method: 'POST' }))
  expect(fetchMock).toHaveBeenCalledWith(expect.stringContaining('/api/tickets?page=0&size=50'), expect.anything())
})

test('loads a synthetic scenario into the inbox and records reviewer feedback', async () => {
  render(<MemoryRouter><App /></MemoryRouter>)
  fireEvent.change(screen.getByLabelText('Shared demo passcode'), { target: { value: 'reviewer' } })
  fireEvent.click(screen.getByRole('button', { name: 'Open reviewer workspace' }))
  await waitFor(() => expect(screen.getByRole('heading', { name: 'Ticket inbox' })).toBeInTheDocument())

  fireEvent.click(screen.getByRole('button', { name: /Load charger-offline-site-wide/i }))
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

test('requires a reviewer note before saving a correction', async () => {
  render(<MemoryRouter><App /></MemoryRouter>)
  fireEvent.change(screen.getByLabelText('Shared demo passcode'), { target: { value: 'reviewer' } })
  fireEvent.click(screen.getByRole('button', { name: 'Open reviewer workspace' }))
  await waitFor(() => expect(screen.getByText('CL-A102')).toBeInTheDocument())
  fireEvent.click(screen.getByRole('button', { name: /Review CL-A102/i }))
  await waitFor(() => expect(screen.getByRole('button', { name: 'Mark urgency high' })).not.toBeDisabled())
  fireEvent.click(screen.getByRole('button', { name: 'Mark urgency high' }))

  expect(screen.getByRole('button', { name: 'Save correction' })).toBeDisabled()
})

test('launches the fixed synthetic retry demonstration', async () => {
  const fetchMock = mockBackend()
  render(<MemoryRouter><App /></MemoryRouter>)
  fireEvent.change(screen.getByLabelText('Shared demo passcode'), { target: { value: 'reviewer' } })
  fireEvent.click(screen.getByRole('button', { name: 'Open reviewer workspace' }))
  await waitFor(() => expect(screen.getByRole('heading', { name: 'Ticket inbox' })).toBeInTheDocument())

  expect(screen.getByText('Controlled retry demonstration')).toBeInTheDocument()
  fireEvent.click(screen.getByRole('button', { name: 'Run controlled retry demo' }))

  await waitFor(() => expect(fetchMock).toHaveBeenCalledWith(
    expect.stringContaining('/api/demo/scenarios/provider-retry-demo'),
    expect.objectContaining({ method: 'POST' }),
  ))
})

test('shows measured evaluation and operational failure views', async () => {
  render(<MemoryRouter><App /></MemoryRouter>)
  fireEvent.change(screen.getByLabelText('Shared demo passcode'), { target: { value: 'reviewer' } })
  fireEvent.click(screen.getByRole('button', { name: 'Open reviewer workspace' }))
  await waitFor(() => expect(screen.getByRole('heading', { name: 'Ticket inbox' })).toBeInTheDocument())

  fireEvent.click(screen.getByRole('button', { name: 'Evaluation' }))
  expect(screen.getByRole('heading', { name: 'Evaluation signal' })).toBeInTheDocument()
  expect(screen.getByText('Agreement')).toBeInTheDocument()

  fireEvent.click(screen.getByRole('button', { name: 'Operations' }))
  await waitFor(() => expect(screen.getByRole('heading', { name: 'No unresolved failures' })).toBeInTheDocument())
})

test('surfaces persisted evaluation activity and operations telemetry', async () => {
  render(<MemoryRouter><App /></MemoryRouter>)
  fireEvent.change(screen.getByLabelText('Shared demo passcode'), { target: { value: 'reviewer' } })
  fireEvent.click(screen.getByRole('button', { name: 'Open reviewer workspace' }))
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
  render(<MemoryRouter><App /></MemoryRouter>)
  fireEvent.change(screen.getByLabelText('Shared demo passcode'), { target: { value: 'reviewer' } })
  fireEvent.click(screen.getByRole('button', { name: 'Open reviewer workspace' }))
  await waitFor(() => expect(screen.getByRole('heading', { name: 'Ticket inbox' })).toBeInTheDocument())

  fireEvent.click(screen.getByRole('button', { name: 'Operations' }))

  await waitFor(() => expect(screen.getByRole('heading', { name: 'Recovery required' })).toBeInTheDocument())
  expect(screen.getByText('A controlled synthetic timeout was recorded. Retry the case to verify the recovery path.')).toBeInTheDocument()
  expect(screen.getByRole('button', { name: 'Retry case' })).toBeInTheDocument()
  expect(screen.queryByText(/Provider degraded/)).not.toBeInTheDocument()
})
