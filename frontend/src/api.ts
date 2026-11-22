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

// latencyMs is null while a job has not finished (the API sends 0 instead before it made the field nullable).
export type EvaluationEvent = {
  resultId: string
  createdAt: string
  latencyMs: number | null
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
    latencyMs: number | null
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

// With seed=true the API seeds the workspace and returns its tickets and runtime summary in the same answer.
// An API from before that change returns the bare session, without tickets and runtime.
export type DemoSession = { token: string; expiresAt: string; industry?: string; tickets?: ApiTicket[]; runtime?: RuntimeSummary }

/**
 * An error answer from the API. The message is the server's detail; the page shows its own plain sentence. The code,
 * when the problem body has one, tells answers with the same status apart (for example "workspace-limit" on a 409).
 */
export class ApiError extends Error {
  readonly status: number
  readonly code: string | null
  constructor(message: string, status: number, code: string | null = null) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.code = code
  }
}

// One more try after a throttle (429, any method: the request was refused before it ran) or a 503 on an idempotent
// request. A POST is never sent twice after a 503, because the first one may have run, unless sending it twice is
// harmless: the triage POST returns the ticket's existing job, so it is idempotent. A long rate-limit window is not
// waited out. An idempotent request is also tried once more when fetch itself fails with a TypeError. API Gateway
// answers its own throttle (429) and errors without CORS headers, so the browser hides that answer and fetch rejects
// instead of returning the status.
const RETRY_DEFAULT_MS = 1_000
const RETRY_MAX_MS = 5_000
function retryDelay(response: Response, idempotent: boolean): number | null {
  if (response.status !== 429 && !(response.status === 503 && idempotent)) return null
  const header = response.headers.get('Retry-After')
  const delay = header === null || header.trim() === '' ? RETRY_DEFAULT_MS : Number(header) * 1_000
  return Number.isFinite(delay) && delay >= 0 && delay <= RETRY_MAX_MS ? delay : null
}

function wait(ms: number, signal?: AbortSignal | null): Promise<void> {
  return new Promise((resolve, reject) => {
    if (signal?.aborted) { reject(signal.reason); return }
    const timer = setTimeout(() => { signal?.removeEventListener('abort', onAbort); resolve() }, ms)
    function onAbort() { clearTimeout(timer); reject(signal?.reason) }
    signal?.addEventListener('abort', onAbort, { once: true })
  })
}

export function createCaseLensApi(baseUrl = '', fetcher: Fetcher = fetch): CaseLensApi {
  let token = ''

  // idempotent marks a POST the server answers the same way when it arrives twice; every GET is idempotent.
  async function request<T>(path: string, init: RequestInit = {}, { idempotent = false } = {}): Promise<T> {
    const headers = new Headers(init.headers)
    if (init.body && !headers.has('Content-Type')) headers.set('Content-Type', 'application/json')
    if (token) headers.set('Authorization', `Bearer ${token}`)
    const url = `${baseUrl.replace(/\/$/, '')}${path}`
    const method = (init.method ?? 'GET').toUpperCase()
    const retryable = method === 'GET' || idempotent
    init.signal?.throwIfAborted()
    let response: Response
    try {
      response = await fetcher(url, { ...init, headers })
    } catch (cause: unknown) {
      if (!(cause instanceof TypeError) || !retryable || init.signal?.aborted) throw cause
      await wait(RETRY_DEFAULT_MS, init.signal)
      response = await fetcher(url, { ...init, headers })
    }
    const delay = response.ok ? null : retryDelay(response, retryable)
    if (delay !== null) {
      await wait(delay, init.signal)
      response = await fetcher(url, { ...init, headers })
    }
    if (!response.ok) {
      let detail = `Request failed with status ${response.status}`
      let code: string | null = null
      try {
        const problem = await response.json() as { detail?: string; code?: string }
        if (problem.detail) detail = problem.detail
        if (typeof problem.code === 'string') code = problem.code
      } catch { /* Preserve the status-only error when the body is not JSON. */ }
      throw new ApiError(detail, response.status, code)
    }
    if (response.status === 204) return undefined as T
    return response.json() as Promise<T>
  }

  return {
    getSessionOptions: () => request<SessionOptions>('/api/demo/session'),
    createSession: (industry, seed = false) => request<DemoSession>(seed ? '/api/demo/session?seed=true' : '/api/demo/session', {
      method: 'POST', ...(industry ? { body: JSON.stringify({ industry }) } : {}),
    }),
    setToken: (value) => { token = value },
    resetDemo: () => request<{ seeded: number }>('/api/demo/reset', { method: 'POST' }),
    getRuntimeSummary: () => request<RuntimeSummary>('/api/demo/runtime'),
    listTickets: () => request<TicketPage>('/api/tickets?page=0&size=50'),
    // A signal stops a request and its retry wait: the page aborts it when the reviewer leaves the view or the demo.
    createScenario: (scenarioKey, signal) => request<ApiTicket>(`/api/demo/scenarios/${encodeURIComponent(scenarioKey)}`, { method: 'POST', signal }),
    requestTriage: (ticketId, signal) => request<TriageProcessing['job']>(`/api/tickets/${ticketId}/triage`, { method: 'POST', signal }, { idempotent: true }),
    getProcessing: (ticketId, signal) => request<TriageProcessing>(`/api/tickets/${ticketId}/processing`, { signal }),
    submitFeedback: (ticketId, payload, signal) => request<FeedbackResponse>(`/api/tickets/${ticketId}/feedback`, {
      method: 'POST', body: JSON.stringify(payload), signal,
    }),
    getEvaluation: (signal) => request<EvaluationSummary>('/api/evaluation', { signal }),
    getOperationsOverview: (signal) => request<OperationsOverview>('/api/operations/overview', { signal }),
    listFailures: (signal) => request<FailurePage>('/api/operations/failures?page=0&size=50', { signal }),
    retryFailure: (jobId, signal) => request<TriageProcessing['job']>(`/api/operations/failures/${jobId}/retry`, { method: 'POST', signal }),
  }
}

export type CaseLensApi = {
  getSessionOptions: () => Promise<SessionOptions>
  createSession: (industry?: string, seed?: boolean) => Promise<DemoSession>
  setToken: (token: string) => void
  resetDemo: () => Promise<{ seeded: number }>
  getRuntimeSummary: () => Promise<RuntimeSummary>
  listTickets: () => Promise<TicketPage>
  createScenario: (scenarioKey: string, signal?: AbortSignal) => Promise<ApiTicket>
  requestTriage: (ticketId: string, signal?: AbortSignal) => Promise<TriageProcessing['job']>
  getProcessing: (ticketId: string, signal?: AbortSignal) => Promise<TriageProcessing>
  submitFeedback: (ticketId: string, payload: { triageResultId: string; category: string; urgency: string; slaRisk: string; note: string }, signal?: AbortSignal) => Promise<FeedbackResponse>
  getEvaluation: (signal?: AbortSignal) => Promise<EvaluationSummary>
  getOperationsOverview: (signal?: AbortSignal) => Promise<OperationsOverview>
  listFailures: (signal?: AbortSignal) => Promise<FailurePage>
  retryFailure: (jobId: string, signal?: AbortSignal) => Promise<TriageProcessing['job']>
}
