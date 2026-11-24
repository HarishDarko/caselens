// Anonymous product analytics (PostHog), off until a project key is built in.
//
// With no VITE_POSTHOG_KEY, with a Do Not Track browser, in dev and in tests, nothing here loads and track() does
// nothing. With a key, posthog-js is fetched on demand (its own chunk), so a build without a key ships no PostHog code
// on the page at all. It runs cookieless: persistence 'memory' writes no cookie and no localStorage, so every page
// load is a new anonymous visitor.
//
// track() carries only the properties listed in EVENTS, and only values of the listed shape. Ticket text, quotes,
// emails, tokens and addresses have no slot, and anything else a caller passes is dropped before it leaves the page.

/** Put this class on any element that renders text a visitor typed: PostHog leaves it out of session recordings. */
export const NO_CAPTURE = 'ph-no-capture'

type Slug = 'slug'
type Flag = 'boolean'
type Choice = readonly string[]
type Shape = Slug | Flag | Choice

export const EVENTS = {
  workspace_opened: { industry: 'slug' },
  ticket_opened: { recorded: 'boolean', urgency: ['LOW', 'MEDIUM', 'HIGH', 'CRITICAL'], decision_source: ['AI_VALIDATED', 'RULES_FALLBACK'] },
  live_triage_requested: { source: ['first_open', 'run_live', 'rerun', 'try_again'] },
  review_saved: { decision: ['ACCEPTED', 'CORRECTED'] },
  quality_ops_viewed: {},
  budget_spent_shown: {},
  how_its_built_opened: {},
} as const satisfies Record<string, Record<string, Shape>>

export type EventName = keyof typeof EVENTS
type ValueOf<S> = S extends Slug ? string : S extends Flag ? boolean : S extends readonly (infer V)[] ? V : never
export type EventProps<E extends EventName> = { [K in keyof (typeof EVENTS)[E]]?: ValueOf<(typeof EVENTS)[E][K]> }

/** The two PostHog regions the page may talk to. The CSP names the same hosts, so any other host would be blocked. */
export const POSTHOG_HOSTS = ['https://us.i.posthog.com', 'https://eu.i.posthog.com'] as const

const SLUG = /^[a-z0-9][a-z0-9-]{0,39}$/

/** Which button asked for a live run: Try again (no result), Re-run live on a recorded result, or Re-run live on a live one. */
export const rerunSource = (ticket: { result?: { recorded?: boolean } | null }): 'try_again' | 'run_live' | 'rerun' =>
  !ticket.result ? 'try_again' : ticket.result.recorded ? 'run_live' : 'rerun'

/** Keeps only the properties an event allows, with values of the allowed shape. Everything else is dropped. */
export function cleanProps(event: string, props: Record<string, unknown> | undefined): Record<string, string | boolean> {
  const schema = (EVENTS as Record<string, Record<string, Shape> | undefined>)[event]
  const clean: Record<string, string | boolean> = {}
  if (!schema || !props) return clean
  for (const [key, shape] of Object.entries(schema)) {
    const value = props[key]
    if (shape === 'boolean') { if (typeof value === 'boolean') clean[key] = value }
    else if (shape === 'slug') { if (typeof value === 'string' && SLUG.test(value)) clean[key] = value }
    else if (typeof value === 'string' && shape.includes(value)) clean[key] = value
  }
  return clean
}

type Client = {
  init: (key: string, config: Record<string, unknown>) => unknown
  capture: (event: string, props?: Record<string, unknown>) => unknown
  register: (props: Record<string, unknown>) => unknown
}
type Env = { PROD?: boolean; VITE_POSTHOG_KEY?: string; VITE_POSTHOG_HOST?: string }
type Page = { location: { search: string }; navigator: { doNotTrack?: string | null; msDoNotTrack?: string | null }; doNotTrack?: string | null }
export type InitOptions = { env?: Env; page?: Page; load?: () => Promise<Client> }

let client: Client | null = null
let starting = false
let waiting: Array<[string, Record<string, string | boolean>]> = []

const loadPostHog = async (): Promise<Client> => (await import('posthog-js')).default as unknown as Client

function doNotTrack(page: Page): boolean {
  return [page.navigator.doNotTrack, page.doNotTrack, page.navigator.msDoNotTrack].some((value) => value === '1' || value === 'yes')
}

/** Starts PostHog when a key is built in. Returns true once it is running, false when analytics stay off. */
export async function initAnalytics(options: InitOptions = {}): Promise<boolean> {
  const env = options.env ?? (import.meta.env as Env)
  const key = env.VITE_POSTHOG_KEY?.trim()
  if (!key || !env.PROD || client || starting) return client !== null
  const page = options.page ?? window
  if (doNotTrack(page)) return false
  const host = (env.VITE_POSTHOG_HOST?.trim() || POSTHOG_HOSTS[0]).replace(/\/+$/, '')
  if (!(POSTHOG_HOSTS as readonly string[]).includes(host)) return false
  // Read before the dynamic import, so the flag is the address the visitor arrived on.
  const internal = new URLSearchParams(page.location.search).get('internal') === '1'
  starting = true
  try {
    const posthog = await (options.load ?? loadPostHog)()
    posthog.init(key, {
      api_host: host,
      persistence: 'memory',
      autocapture: true,
      capture_pageview: 'history_change',
      respect_dnt: true,
      person_profiles: 'identified_only',
      // Autocapture records what was clicked, so ticket subjects and other text on screen are masked out of it.
      mask_all_text: true,
      mask_all_element_attributes: true,
      disable_session_recording: false,
      // Inputs are masked, and visitor-typed text rendered back on the page carries the ph-no-capture class (see
      // NO_CAPTURE), which keeps it out of recordings. Canvas is never recorded.
      session_recording: { maskAllInputs: true, recordCanvas: false },
      // The first pageview goes out before register() has run, so the flag is also added to every event here.
      before_send: (event: { properties?: Record<string, unknown> } | null) => {
        if (internal && event) event.properties = { ...event.properties, internal: true }
        return event
      },
      disable_surveys: true,
    })
    if (internal) posthog.register({ internal: true })
    client = posthog
    for (const [event, props] of waiting) posthog.capture(event, props)
    return true
  } catch {
    return false
  } finally {
    starting = false
    waiting = []
  }
}

/** Sends one product event. Does nothing unless analytics are running (or about to). */
export function track<E extends EventName>(event: E, props?: EventProps<E>): void {
  const clean = cleanProps(event, props as Record<string, unknown> | undefined)
  if (client) client.capture(event, clean)
  else if (starting && waiting.length < 20) waiting.push([event, clean])
}

/** For tests: forget a running client. */
export function resetAnalytics(): void {
  client = null; starting = false; waiting = []
}
