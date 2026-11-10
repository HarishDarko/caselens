export type ApiTicket = {
  id: string
  displayId: string
  subject: string
  message: string
  channel: string
  status: string
  scenarioKey: string | null
  createdAt: string
  updatedAt: string
}

export type TicketPage = {
  content: ApiTicket[]
  page: number
  size: number
  totalElements: number
  totalPages: number
}

export type RuntimeSummary = {
  environment: 'LOCAL_REVIEW' | 'AWS_DEMO'
  database: 'POSTGRESQL' | 'NEON_POSTGRESQL'
  queue: 'LOCALSTACK_SQS' | 'AMAZON_SQS'
  aiProvider: 'GROQ' | 'GEMINI' | 'DETERMINISTIC'
}

export type TriageProcessing = {
  job: {
    id: string
    eventId: string
    ticketId: string
    status: 'QUEUED' | 'PROCESSING' | 'COMPLETED' | 'RETRYABLE_FAILURE' | 'TERMINAL_FAILURE'
    attemptCount: number
    startedAt: string | null
    completedAt: string | null
    lastErrorCode: string | null
    attempts: Array<{
      id: string
      jobId: string
      attemptNumber: number
      status: 'PROCESSING' | 'COMPLETED' | 'RETRYABLE_FAILURE' | 'TERMINAL_FAILURE'
      startedAt: string
      completedAt: string | null
      errorCode: string | null
    }>
  }
  result: TriageResult | null
}

export type TriageResult = {
  id: string
  ticketId: string
  category: string
  urgency: 'LOW' | 'MEDIUM' | 'HIGH' | 'CRITICAL'
  slaRisk: 'LOW' | 'MEDIUM' | 'HIGH'
  sentiment: string
  summary: string
  evidence: Array<{ quote: string; meaning: string } | string>
  policyIds: string[]
  explanation: string
  recommendedActions: string[]
  suggestedReply: string
  reliabilitySignal: string
  warnings: string[]
  priorityScore: number
  appliedRules: Array<{ code: string; points: number; explanation: string }>
  decisionSource: 'AI_VALIDATED' | 'RULES_FALLBACK'
  modelVersion: string
  promptVersion: string
  createdAt: string
}

export type FeedbackResponse = {
  id: string
  ticketId: string
  originalResultId: string
  correctedCategory: string
  correctedUrgency: 'LOW' | 'MEDIUM' | 'HIGH' | 'CRITICAL'
  correctedSlaRisk: 'LOW' | 'MEDIUM' | 'HIGH'
  note: string
  submittedBy: string
  createdAt: string
}

export type EvaluationSummary = {
  generatedAt: string
  triageResults: number
  evaluatedResults: number
  categoryAgreementRate: number
  urgencyAgreementRate: number
  agreementRate: number
  correctionRate: number
  medianLatencyMs: number
  p95LatencyMs: number
  failureRate: number
  providerUsage: Array<{ provider: string; requests: number; successes: number; failures: number; inputTokens: number; outputTokens: number }>
  recentEvents: EvaluationEvent[]
}

export type EvaluationEvent = {
  resultId: string
  createdAt: string
  latencyMs: number
  provider: string | null
  modelVersion: string
  decisionSource: 'AI_VALIDATED' | 'RULES_FALLBACK'
  evaluated: boolean
  corrected: boolean
}

export type OperationsOverview = {
  generatedAt: string
  queue: {
    queued: number
    processing: number
    completed: number
    retryableFailures: number
    terminalFailures: number
  }
  fallbackCount: number
  providerFailureCount: number
  recent: Array<{
    jobId: string
    ticketId: string
    status: TriageProcessing['job']['status']
    attemptCount: number
    createdAt: string
    updatedAt: string
    completedAt: string | null
    lastErrorCode: string | null
    provider: string | null
    modelVersion: string | null
    decisionSource: 'AI_VALIDATED' | 'RULES_FALLBACK' | null
    latencyMs: number
  }>
  providers: Array<{
    provider: string
    requests: number
    successes: number
    failures: number
    inputTokens: number
    outputTokens: number
    averageLatencyMs: number | null
  }>
}

export type FailurePage = {
  content: Array<TriageProcessing['job']>
  page: number
  size: number
  totalElements: number
  totalPages: number
}

type Fetcher = (input: RequestInfo | URL, init?: RequestInit) => Promise<Response>

export type DemoScenarioOption = { key: string; label: string }

export type Industry = { id: string; name: string; featuredScenario: DemoScenarioOption; scenarios: DemoScenarioOption[] }

export type SessionOptions = { defaultIndustry: string; industries: Industry[] }

export type DemoSession = { token: string; expiresAt: string; industry?: string }

/** An error answer from the API. The message is the server's detail; the page shows its own plain sentence. */
export class ApiError extends Error {
  readonly status: number
  constructor(message: string, status: number) {
    super(message)
    this.name = 'ApiError'
    this.status = status
  }
}

// One more try after a throttle (429, any method: the request was refused before it ran) or a 503 on a GET.
// A POST is never sent twice after a 503, because the first one may have run. A long rate-limit window is not waited out.
const RETRY_DEFAULT_MS = 1_000
const RETRY_MAX_MS = 5_000
function retryDelay(response: Response, method: string): number | null {
  if (response.status !== 429 && !(response.status === 503 && method === 'GET')) return null
  const header = response.headers.get('Retry-After')
  const delay = header === null || header.trim() === '' ? RETRY_DEFAULT_MS : Number(header) * 1_000
  return Number.isFinite(delay) && delay >= 0 && delay <= RETRY_MAX_MS ? delay : null
}

export function createCaseLensApi(baseUrl = '', fetcher: Fetcher = fetch): CaseLensApi {
  let token = ''

  async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
    const headers = new Headers(init.headers)
    if (init.body && !headers.has('Content-Type')) headers.set('Content-Type', 'application/json')
    if (token) headers.set('Authorization', `Bearer ${token}`)
    const url = `${baseUrl.replace(/\/$/, '')}${path}`
    let response = await fetcher(url, { ...init, headers })
    const delay = response.ok ? null : retryDelay(response, (init.method ?? 'GET').toUpperCase())
    if (delay !== null) {
      await new Promise((resolve) => setTimeout(resolve, delay))
      response = await fetcher(url, { ...init, headers })
    }
    if (!response.ok) {
      let detail = `Request failed with status ${response.status}`
      try {
        const problem = await response.json() as { detail?: string }
        if (problem.detail) detail = problem.detail
      } catch { /* Preserve the status-only error when the body is not JSON. */ }
      throw new ApiError(detail, response.status)
    }
    if (response.status === 204) return undefined as T
    return response.json() as Promise<T>
  }

  return {
    getSessionOptions: () => request<SessionOptions>('/api/demo/session'),
    createSession: (industry) => request<DemoSession>('/api/demo/session', {
      method: 'POST', ...(industry ? { body: JSON.stringify({ industry }) } : {}),
    }),
    setToken: (value) => { token = value },
    resetDemo: () => request<{ seeded: number }>('/api/demo/reset', { method: 'POST' }),
    getRuntimeSummary: () => request<RuntimeSummary>('/api/demo/runtime'),
    listTickets: () => request<TicketPage>('/api/tickets?page=0&size=50'),
    createScenario: (scenarioKey) => request<ApiTicket>(`/api/demo/scenarios/${encodeURIComponent(scenarioKey)}`, { method: 'POST' }),
    requestTriage: (ticketId) => request<TriageProcessing['job']>(`/api/tickets/${ticketId}/triage`, { method: 'POST' }),
    getProcessing: (ticketId) => request<TriageProcessing>(`/api/tickets/${ticketId}/processing`),
    submitFeedback: (ticketId, payload) => request<FeedbackResponse>(`/api/tickets/${ticketId}/feedback`, {
      method: 'POST', body: JSON.stringify(payload),
    }),
    getEvaluation: () => request<EvaluationSummary>('/api/evaluation'),
    getOperationsOverview: () => request<OperationsOverview>('/api/operations/overview'),
    listFailures: () => request<FailurePage>('/api/operations/failures?page=0&size=50'),
    retryFailure: (jobId) => request<TriageProcessing['job']>(`/api/operations/failures/${jobId}/retry`, { method: 'POST' }),
  }
}

export type CaseLensApi = {
  getSessionOptions: () => Promise<SessionOptions>
  createSession: (industry?: string) => Promise<DemoSession>
  setToken: (token: string) => void
  resetDemo: () => Promise<{ seeded: number }>
  getRuntimeSummary: () => Promise<RuntimeSummary>
  listTickets: () => Promise<TicketPage>
  createScenario: (scenarioKey: string) => Promise<ApiTicket>
  requestTriage: (ticketId: string) => Promise<TriageProcessing['job']>
  getProcessing: (ticketId: string) => Promise<TriageProcessing>
  submitFeedback: (ticketId: string, payload: { triageResultId: string; category: string; urgency: string; slaRisk: string; note: string }) => Promise<FeedbackResponse>
  getEvaluation: () => Promise<EvaluationSummary>
  getOperationsOverview: () => Promise<OperationsOverview>
  listFailures: () => Promise<FailurePage>
  retryFailure: (jobId: string) => Promise<TriageProcessing['job']>
}
