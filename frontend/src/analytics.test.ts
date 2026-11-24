import { afterEach, describe, expect, it, vi } from 'vitest'
import { cleanProps, EVENTS, initAnalytics, resetAnalytics, track } from './analytics'

const page = (search = '', dnt: string | null = null) => ({ location: { search }, navigator: { doNotTrack: dnt }, doNotTrack: null })
const fakeClient = () => ({ init: vi.fn(), capture: vi.fn(), register: vi.fn() })
const prod = { PROD: true, VITE_POSTHOG_KEY: 'phc_test_key', VITE_POSTHOG_HOST: 'https://eu.i.posthog.com' }

afterEach(() => resetAnalytics())

describe('analytics are off without a key', () => {
  it('loads nothing and track() is a no-op', async () => {
    const load = vi.fn(async () => fakeClient())
    for (const env of [{ PROD: true }, { PROD: true, VITE_POSTHOG_KEY: '' }, { PROD: true, VITE_POSTHOG_KEY: '   ' }]) {
      expect(await initAnalytics({ env, page: page(), load })).toBe(false)
    }
    expect(() => track('workspace_opened', { industry: 'saas-support' })).not.toThrow()
    expect(load).not.toHaveBeenCalled()
  })

  it('stays off outside a production build even with a key (dev and tests)', async () => {
    const load = vi.fn(async () => fakeClient())
    expect(await initAnalytics({ env: { PROD: false, VITE_POSTHOG_KEY: 'phc_test_key' }, page: page(), load })).toBe(false)
    expect(load).not.toHaveBeenCalled()
  })

  it('stays off for Do Not Track and for a host outside the two regions', async () => {
    const load = vi.fn(async () => fakeClient())
    expect(await initAnalytics({ env: prod, page: page('', '1'), load })).toBe(false)
    expect(await initAnalytics({ env: { ...prod, VITE_POSTHOG_HOST: 'https://evil.example' }, page: page(), load })).toBe(false)
    expect(load).not.toHaveBeenCalled()
  })

  it('the test environment itself has no key built in', async () => {
    expect(await initAnalytics()).toBe(false)
  })
})

describe('analytics with a key', () => {
  it('starts PostHog cookieless, masked and with autocapture, then sends events', async () => {
    const client = fakeClient()
    expect(await initAnalytics({ env: prod, page: page(), load: async () => client })).toBe(true)
    const [key, config] = client.init.mock.calls[0]!
    expect(key).toBe('phc_test_key')
    expect(config).toMatchObject({ api_host: 'https://eu.i.posthog.com', persistence: 'memory', autocapture: true, capture_pageview: 'history_change', respect_dnt: true, session_recording: { maskAllInputs: true } })
    expect(client.register).not.toHaveBeenCalled()
    track('review_saved', { decision: 'ACCEPTED' })
    expect(client.capture).toHaveBeenCalledWith('review_saved', { decision: 'ACCEPTED' })
  })

  it('defaults to the US host and marks ?internal=1 as an internal super property', async () => {
    const client = fakeClient()
    await initAnalytics({ env: { PROD: true, VITE_POSTHOG_KEY: 'phc_test_key' }, page: page('?internal=1'), load: async () => client })
    expect(client.init.mock.calls[0]![1]).toMatchObject({ api_host: 'https://us.i.posthog.com' })
    expect(client.register).toHaveBeenCalledWith({ internal: true })
  })
})

describe('events carry only allowed properties', () => {
  it('drops unknown keys, free text and values outside the allowed set', () => {
    expect(cleanProps('ticket_opened', { recorded: true, urgency: 'HIGH', decision_source: 'AI_VALIDATED', subject: 'My card 4242 was charged', email: 'a@b.com', token: 'abc' }))
      .toEqual({ recorded: true, urgency: 'HIGH', decision_source: 'AI_VALIDATED' })
    expect(cleanProps('ticket_opened', { urgency: 'My card was charged twice', decision_source: 'x', recorded: 'yes' })).toEqual({})
    expect(cleanProps('workspace_opened', { industry: 'saas-support' })).toEqual({ industry: 'saas-support' })
    expect(cleanProps('workspace_opened', { industry: 'jane@example.com' })).toEqual({})
    expect(cleanProps('workspace_opened', { industry: 'a'.repeat(80) })).toEqual({})
    expect(cleanProps('quality_ops_viewed', { note: 'free text' })).toEqual({})
    expect(cleanProps('not_an_event', { a: 1 })).toEqual({})
  })

  it('sends the cleaned properties, never what the caller passed', async () => {
    const client = fakeClient()
    await initAnalytics({ env: prod, page: page(), load: async () => client })
    track('live_triage_requested', { source: 'rerun', ticketId: 'T-1', message: 'secret' } as never)
    expect(client.capture).toHaveBeenCalledWith('live_triage_requested', { source: 'rerun' })
  })

  it('has no property that could hold free text: every one is a flag, a short slug or a fixed list', () => {
    for (const props of Object.values(EVENTS)) {
      for (const shape of Object.values(props as Record<string, unknown>)) expect(shape === 'boolean' || shape === 'slug' || Array.isArray(shape)).toBe(true)
    }
  })
})
