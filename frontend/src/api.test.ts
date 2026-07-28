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
