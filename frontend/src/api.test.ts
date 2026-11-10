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

  it('tries a GET once more after a 503, but never a POST, and keeps the status on the error', async () => {
    vi.useFakeTimers()
    try {
      const fetcher = vi.fn().mockImplementation(async () => new Response('', { status: 503 }))
      const api = createCaseLensApi('', fetcher)

      const get = api.getProcessing('ticket-1').catch((error: unknown) => error)
      await vi.advanceTimersByTimeAsync(1_000)
      expect(await get).toMatchObject({ status: 503 })
      expect(fetcher).toHaveBeenCalledTimes(2)

      await expect(api.requestTriage('ticket-1')).rejects.toMatchObject({ status: 503 })
      expect(fetcher).toHaveBeenCalledTimes(3)
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
