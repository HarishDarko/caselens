import { afterEach, describe, expect, it, vi } from 'vitest'
import { cleanProps, EVENTS, initAnalytics, NO_CAPTURE, rerunSource, resetAnalytics, track } from './analytics'

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

describe('recording config and the internal flag', () => {
  it('never records canvas, masks inputs, and respects Do Not Track', async () => {
    const client = fakeClient()
    await initAnalytics({ env: prod, page: page(), load: async () => client })
    expect(client.init.mock.calls[0]![1]).toMatchObject({ session_recording: { maskAllInputs: true, recordCanvas: false }, respect_dnt: true, persistence: 'memory' })
    expect(NO_CAPTURE).toBe('ph-no-capture')
  })

  it('tags every event, including the first pageview, when the page was opened with ?internal=1', async () => {
    const client = fakeClient()
    const arrival = page('?internal=1')
    // The address is read before PostHog loads, so a change while it loads does not matter.
    await initAnalytics({ env: prod, page: arrival, load: async () => { arrival.location.search = ''; return client } })
    const beforeSend = (client.init.mock.calls[0]![1] as { before_send: (e: { properties?: Record<string, unknown> } | null) => { properties?: Record<string, unknown> } | null }).before_send
    expect(beforeSend({ properties: { $current_url: 'x' } })?.properties).toEqual({ $current_url: 'x', internal: true })
    expect(beforeSend(null)).toBeNull()
    expect(client.register).toHaveBeenCalledWith({ internal: true })
  })

  it('leaves events untagged without the flag', async () => {
    const client = fakeClient()
    await initAnalytics({ env: prod, page: page('?other=1'), load: async () => client })
    const beforeSend = (client.init.mock.calls[0]![1] as { before_send: (e: { properties?: Record<string, unknown> }) => { properties?: Record<string, unknown> } }).before_send
    expect(beforeSend({ properties: { a: 1 } }).properties).toEqual({ a: 1 })
  })
})

describe('which button asked for a live run', () => {
  it('Try again has no result, Re-run live on a recorded result is run_live, on a live result rerun', () => {
    expect(rerunSource({ result: null })).toBe('try_again')
    expect(rerunSource({ result: { recorded: true } })).toBe('run_live')
    expect(rerunSource({ result: { recorded: false } })).toBe('rerun')
    expect(rerunSource({ result: {} })).toBe('rerun')
  })
})
