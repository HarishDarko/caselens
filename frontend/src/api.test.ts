import { describe, expect, it, vi } from 'vitest'
import { createCaseLensApi } from './api'

describe('CaseLens API client', () => {
  it('creates a session and uses its bearer token for workspace requests', async () => {
    const fetcher = vi.fn()
      .mockResolvedValueOnce(new Response(JSON.stringify({ token: 'demo-token', expiresAt: '2026-07-12T00:00:00Z' }), { status: 200 }))
      .mockResolvedValueOnce(new Response(JSON.stringify({ content: [], page: 0, size: 50, totalElements: 0, totalPages: 0 }), { status: 200 }))
    const api = createCaseLensApi('http://localhost:8080', fetcher)

    await expect(api.createSession()).resolves.toEqual({ token: 'demo-token', expiresAt: '2026-07-12T00:00:00Z' })
    api.setToken('demo-token')
    await api.listTickets()

    expect(fetcher).toHaveBeenNthCalledWith(1, 'http://localhost:8080/api/demo/session', expect.objectContaining({ method: 'POST' }))
    expect(fetcher.mock.calls[0]?.[1]).not.toHaveProperty('body')
    const secondRequest = fetcher.mock.calls[1]?.[1]
    expect(fetcher.mock.calls[1]?.[0]).toBe('http://localhost:8080/api/tickets?page=0&size=50')
    expect(new Headers(secondRequest?.headers).get('Authorization')).toBe('Bearer demo-token')
  })

  it('reads the industries and sends the picked industry when it creates a session', async () => {
    const fetcher = vi.fn()
      .mockResolvedValueOnce(new Response(JSON.stringify({ defaultIndustry: 'ev-charging', industries: [] }), { status: 200 }))
      .mockResolvedValueOnce(new Response(JSON.stringify({ token: 'demo-token', expiresAt: '2026-07-12T00:00:00Z', industry: 'saas-support' }), { status: 200 }))
    const api = createCaseLensApi('', fetcher)

    await expect(api.getSessionOptions()).resolves.toEqual({ defaultIndustry: 'ev-charging', industries: [] })
    await expect(api.createSession('saas-support')).resolves.toMatchObject({ industry: 'saas-support' })

    expect(fetcher.mock.calls[0]?.[0]).toBe('/api/demo/session')
    expect(fetcher.mock.calls[0]?.[1]).not.toHaveProperty('method')
    expect(fetcher).toHaveBeenNthCalledWith(2, '/api/demo/session', expect.objectContaining({ method: 'POST', body: JSON.stringify({ industry: 'saas-support' }) }))
  })

  it('asks the API to seed the workspace in the same call when the page opens one', async () => {
    const opened = { token: 'demo-token', expiresAt: '2026-07-12T00:00:00Z', industry: 'ev-charging', tickets: [], runtime: { environment: 'AWS_DEMO', database: 'NEON_POSTGRESQL', queue: 'AMAZON_SQS', aiProvider: 'GROQ' } }
    const fetcher = vi.fn().mockResolvedValueOnce(new Response(JSON.stringify(opened), { status: 200 }))
    const api = createCaseLensApi('', fetcher)

    await expect(api.createSession('ev-charging', true)).resolves.toEqual(opened)

    expect(fetcher).toHaveBeenNthCalledWith(1, '/api/demo/session?seed=true', expect.objectContaining({ method: 'POST', body: JSON.stringify({ industry: 'ev-charging' }) }))
  })

  it('tries once more after a 429, waiting as long as Retry-After says', async () => {
    vi.useFakeTimers()
    try {
      const fetcher = vi.fn()
        .mockResolvedValueOnce(new Response('', { status: 429, headers: { 'Retry-After': '2' } }))
        .mockResolvedValueOnce(new Response(JSON.stringify({ content: [] }), { status: 200 }))
      const api = createCaseLensApi('', fetcher)

      const pending = api.listTickets()
      await vi.advanceTimersByTimeAsync(1_999)
      expect(fetcher).toHaveBeenCalledTimes(1)
      await vi.advanceTimersByTimeAsync(1)

      await expect(pending).resolves.toEqual({ content: [] })
      expect(fetcher).toHaveBeenCalledTimes(2)
    } finally {
      vi.useRealTimers()
    }
  })

  it('keeps the problem code on the error so the page can tell a workspace cap from other conflicts', async () => {
    const fetcher = vi.fn().mockImplementation(async () => new Response(
      JSON.stringify({ type: 'urn:caselens:problem:workspace-limit', code: 'workspace-limit', status: 409, detail: 'Full' }),
      { status: 409, headers: { 'Content-Type': 'application/problem+json' } }))
    const api = createCaseLensApi('', fetcher)

    await expect(api.requestTriage('ticket-1')).rejects.toMatchObject({ status: 409, code: 'workspace-limit', message: 'Full' })
  })

  it('tries a GET once more after a 503, but never a non-idempotent POST, and keeps the status on the error', async () => {
    vi.useFakeTimers()
    try {
      const fetcher = vi.fn().mockImplementation(async () => new Response('', { status: 503 }))
      const api = createCaseLensApi('', fetcher)

      const get = api.getProcessing('ticket-1').catch((error: unknown) => error)
      await vi.advanceTimersByTimeAsync(1_000)
      expect(await get).toMatchObject({ status: 503 })
      expect(fetcher).toHaveBeenCalledTimes(2)

      await expect(api.submitFeedback('ticket-1', { triageResultId: 'result-1', category: 'Billing', urgency: 'HIGH', slaRisk: 'LOW', note: 'n' })).rejects.toMatchObject({ status: 503 })
      expect(fetcher).toHaveBeenCalledTimes(3)
    } finally {
      vi.useRealTimers()
    }
  })

  it('tries a GET once more when fetch fails with a TypeError (a gateway answer without CORS headers), but never a non-idempotent POST', async () => {
    vi.useFakeTimers()
    try {
      const fetcher = vi.fn()
        .mockRejectedValueOnce(new TypeError('Failed to fetch'))
        .mockResolvedValueOnce(new Response(JSON.stringify({ content: [] }), { status: 200 }))
      const api = createCaseLensApi('', fetcher)

      const pending = api.listTickets()
      await vi.advanceTimersByTimeAsync(999)
      expect(fetcher).toHaveBeenCalledTimes(1)
      await vi.advanceTimersByTimeAsync(1)
      await expect(pending).resolves.toEqual({ content: [] })
      expect(fetcher).toHaveBeenCalledTimes(2)

      fetcher.mockRejectedValue(new TypeError('Failed to fetch'))
      await expect(api.submitFeedback('ticket-1', { triageResultId: 'result-1', category: 'Billing', urgency: 'HIGH', slaRisk: 'LOW', note: 'n' })).rejects.toBeInstanceOf(TypeError)
      await expect(api.retryFailure('job-1')).rejects.toBeInstanceOf(TypeError)
      await expect(api.createScenario('provider-retry-demo')).rejects.toBeInstanceOf(TypeError)
      expect(fetcher).toHaveBeenCalledTimes(5)

      const get = api.getProcessing('ticket-1').catch((error: unknown) => error)
      await vi.advanceTimersByTimeAsync(1_000)
      expect(await get).toBeInstanceOf(TypeError)
      expect(fetcher).toHaveBeenCalledTimes(7)
    } finally {
      vi.useRealTimers()
    }
  })

  // CL-05: the API returns the ticket's existing job when the triage POST arrives twice, so a throttled triage
  // request (which a browser reports as a TypeError, or a 503) is sent once more instead of failing.
  it('tries the idempotent triage POST once more after a TypeError or a 503', async () => {
    vi.useFakeTimers()
    try {
      const job = { id: 'job-1', status: 'QUEUED' }
      const fetcher = vi.fn()
        .mockRejectedValueOnce(new TypeError('Failed to fetch'))
        .mockResolvedValueOnce(new Response(JSON.stringify(job), { status: 202 }))
        .mockResolvedValueOnce(new Response('', { status: 503 }))
        .mockResolvedValueOnce(new Response(JSON.stringify(job), { status: 202 }))
      const api = createCaseLensApi('', fetcher)

      const first = api.requestTriage('ticket-1')
      await vi.advanceTimersByTimeAsync(1_000)
      await expect(first).resolves.toEqual(job)
      expect(fetcher).toHaveBeenCalledTimes(2)

      const second = api.requestTriage('ticket-1')
      await vi.advanceTimersByTimeAsync(1_000)
      await expect(second).resolves.toEqual(job)
      expect(fetcher).toHaveBeenCalledTimes(4)
      expect(fetcher.mock.calls.every(([url, init]) => url === '/api/tickets/ticket-1/triage' && init?.method === 'POST')).toBe(true)
    } finally {
      vi.useRealTimers()
    }
  })

  // CL2-13: the observability reads and the mutations take a signal, so leaving a view or closing the demo stops a
  // retry they are waiting to send instead of firing it 1 to 5 seconds later with the old token.
  it.each([
    ['getEvaluation', (api: ReturnType<typeof createCaseLensApi>, signal: AbortSignal) => api.getEvaluation(signal)],
    ['getOperationsOverview', (api: ReturnType<typeof createCaseLensApi>, signal: AbortSignal) => api.getOperationsOverview(signal)],
    ['listFailures', (api: ReturnType<typeof createCaseLensApi>, signal: AbortSignal) => api.listFailures(signal)],
    ['submitFeedback', (api: ReturnType<typeof createCaseLensApi>, signal: AbortSignal) => api.submitFeedback('ticket-1', { triageResultId: 'result-1', category: 'Billing', urgency: 'HIGH', slaRisk: 'LOW', note: 'n' }, signal)],
    ['retryFailure', (api: ReturnType<typeof createCaseLensApi>, signal: AbortSignal) => api.retryFailure('job-1', signal)],
    ['createScenario', (api: ReturnType<typeof createCaseLensApi>, signal: AbortSignal) => api.createScenario('provider-retry-demo', signal)],
  ])('%s passes its signal to fetch and does not send the retry once it aborts', async (_name, call) => {
    vi.useFakeTimers()
    try {
      const fetcher = vi.fn().mockResolvedValue(new Response('', { status: 429, headers: { 'Retry-After': '3' } }))
      const api = createCaseLensApi('', fetcher)
      api.setToken('demo-token')
      const controller = new AbortController()

      const pending = call(api, controller.signal).catch((error: unknown) => error)
      await vi.advanceTimersByTimeAsync(1_000)
      expect(fetcher).toHaveBeenCalledTimes(1)
      expect(fetcher.mock.calls[0]?.[1]?.signal).toBe(controller.signal)
      controller.abort()
      expect(await pending).toMatchObject({ name: 'AbortError' })
      await vi.advanceTimersByTimeAsync(5_000)
      expect(fetcher).toHaveBeenCalledTimes(1)
    } finally {
      vi.useRealTimers()
    }
  })

  it('sends nothing once the signal has aborted, and stops waiting to retry when it aborts', async () => {
    vi.useFakeTimers()
    try {
      const fetcher = vi.fn().mockRejectedValue(new TypeError('Failed to fetch'))
      const api = createCaseLensApi('', fetcher)
      const controller = new AbortController()

      const get = api.getProcessing('ticket-1', controller.signal).catch((error: unknown) => error)
      await vi.advanceTimersByTimeAsync(500)
      controller.abort()
      expect(await get).toMatchObject({ name: 'AbortError' })
      await vi.advanceTimersByTimeAsync(5_000)
      expect(fetcher).toHaveBeenCalledTimes(1)

      await expect(api.getProcessing('ticket-1', controller.signal)).rejects.toMatchObject({ name: 'AbortError' })
      expect(fetcher).toHaveBeenCalledTimes(1)
    } finally {
      vi.useRealTimers()
    }
  })

  it('does not wait out a long rate-limit window', async () => {
    const fetcher = vi.fn().mockResolvedValue(new Response('', { status: 429, headers: { 'Retry-After': '60' } }))
    const api = createCaseLensApi('', fetcher)

    await expect(api.createSession()).rejects.toMatchObject({ status: 429 })
    expect(fetcher).toHaveBeenCalledTimes(1)
  })

  it('calls scenario, evaluation, and failure endpoints with the active session', async () => {
    const fetcher = vi.fn().mockImplementation(() => new Response(JSON.stringify({}), { status: 200 }))
    const api = createCaseLensApi('', fetcher)
    api.setToken('demo-token')

    await api.createScenario('charger-offline-site-wide')
    await api.getEvaluation()
    await api.getOperationsOverview()
    await api.listFailures()

    expect(fetcher).toHaveBeenNthCalledWith(1, '/api/demo/scenarios/charger-offline-site-wide', expect.objectContaining({ method: 'POST' }))
    expect(fetcher).toHaveBeenNthCalledWith(2, '/api/evaluation', expect.anything())
    expect(fetcher).toHaveBeenNthCalledWith(3, '/api/operations/overview', expect.anything())
    expect(fetcher).toHaveBeenNthCalledWith(4, '/api/operations/failures?page=0&size=50', expect.anything())
  })
})
