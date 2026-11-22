import { useEffect, useMemo, useRef, useState } from 'react'
import bundledSessionOptions from './industries.json'
import { ApiError, createCaseLensApi, type ApiTicket, type EvaluationSummary, type FailurePage, type Industry, type OperationsOverview, type RuntimeSummary, type SessionOptions, type TriageProcessing, type TriageResult } from './api'

type View = 'inbox' | 'evaluation' | 'operations'
type Urgency = 'low' | 'medium' | 'high' | 'critical'

type TimelineEvent = { label: string; time: string; kind: 'received' | 'decision' | 'review' }
type Ticket = {
  id: string; backendId: string; subject: string; message: string; category: string; urgency: Urgency | null; originalUrgency: Urgency | null; priority: number | null; reliabilitySignal: string | null; slaRisk: string; source: string; summary: string; rationale: string; evidence: string[]; actions: string[]; suggestedReply: string; policy: string; timeline: TimelineEvent[]; triageResultId: string | null; decisionSource: string | null; triageStatus: string; scenarioKey: string | null; latencyMs: number | null; jobId: string | null; eventId: string | null; attemptCount: number; attempts: TriageProcessing['job']['attempts']; provider: string | null; modelVersion: string | null
}

// A worker whose SnapStart snapshot was dropped after 14 idle days rebuilds it before SQS redelivers
// the job, which took about 90 seconds in production, so wait two minutes and back off while waiting.
const TRIAGE_WAIT_MS = 120_000
// Evaluation and Operations refresh every 5 seconds while someone is looking. They pause in a hidden tab and stop
// after 10 minutes without a click, key press, scroll or touch, so an open tab does not keep the API and Neon awake.
const LIVE_REFRESH_MS = 5_000
const LIVE_IDLE_MS = 10 * 60_000
type LiveState = 'loading' | 'live' | 'paused' | 'failed' | 'ended'
const SLOW_TRIAGE_NOTICE_MS = 8_000
// A triage takes about 4 to 6 seconds, and the API allows 5 requests a second for all visitors together, so check
// once a second (every 2 seconds after 30 seconds). Checking four times a second only used up that shared budget.
function triagePollDelay(elapsedMs: number) { return elapsedMs < 30_000 ? 1_000 : 2_000 }

// What the visitor reads while a ticket is triaged, for each state the job can be in.
const TRIAGE_STAGE_TEXT = {
  SENDING: 'Sending the ticket to the triage queue.',
  QUEUED: 'Waiting for the triage worker to pick this up. After a quiet period it can take up to a minute to start.',
  PROCESSING: 'The AI model is reading the ticket. This usually takes a few seconds.',
  RETRYABLE_FAILURE: 'The AI model did not answer. The worker tries again by itself in about a minute.',
} as const
const TRIAGE_FAILED_TEXT = 'This ticket could not be triaged. Open Operations to retry it.'
const RETRY_DEMO_STOPPED_TEXT = 'The retry demo stopped this triage on purpose. Open Operations to retry it.'
const TRIAGE_STILL_RUNNING_TEXT = 'Triage is still running. Open the ticket again in a minute to see the result.'

const SESSION_ENDED_TEXT = 'Your demo session has ended. Close the demo and open a new one.'
// The API answers every refused correction with the same 409 (an unchanged result, a result that is not the latest,
// or one that belongs to another ticket), so the sentence covers all of them.
const CORRECTION_CONFLICT_TEXT = 'This correction conflicts with the current triage result of this ticket, so it was not saved. Open the ticket again and check it.'
const WORKSPACE_LIMIT_TEXT = 'This demo workspace has reached its limit. Start a new demo to keep going.'
const SESSION_ENDED_BANNER_TEXT = 'Your demo session has ended. Start a new demo to keep exploring.'

/** A sentence written for the visitor, shown as it is. */
class PlainError extends Error {}

/** Waits, and stops waiting with the signal's reason when the signal aborts. */
function pause(ms: number, signal: AbortSignal): Promise<void> {
  return new Promise((resolve, reject) => {
    if (signal.aborted) { reject(signal.reason); return }
    const timer = window.setTimeout(() => { signal.removeEventListener('abort', onAbort); resolve() }, ms)
    function onAbort() { window.clearTimeout(timer); reject(signal.reason) }
    signal.addEventListener('abort', onAbort, { once: true })
  })
}

// Error text for visitors: never a status code, "Failed to fetch" or a server detail. A 409 means the API refused the
// change because of the current state (for example a correction that matches the original result); the caller can
// pass its own sentence for it.
function plainError(cause: unknown, fallback: string, conflict = 'That change does not apply to this ticket any more. Open the ticket again and check it.'): string {
  if (cause instanceof PlainError) return cause.message
  if (cause instanceof TypeError) return 'Could not reach the CaseLens service. Check your connection and try again.'
  if (cause instanceof ApiError) {
    if (cause.status === 401) return SESSION_ENDED_TEXT
    if (cause.status === 404) return 'That item is no longer in this demo workspace. Close the demo and open a new one.'
    if (cause.status === 409 && cause.code === 'workspace-limit') return WORKSPACE_LIMIT_TEXT
    if (cause.status === 409) return conflict
    if (cause.status === 429) return 'The demo is busy right now. Wait a few seconds and try again.'
    if (cause.status >= 500) return 'The CaseLens service had a problem. Try again in a few seconds.'
  }
  return fallback
}

function formatUrgency(urgency: Urgency | null) { return urgency ? urgency.charAt(0).toUpperCase() + urgency.slice(1) : 'Pending' }

const runtimeLabels = {
  environment: { LOCAL_REVIEW: 'Local review', AWS_DEMO: 'AWS demo' },
  database: { POSTGRESQL: 'PostgreSQL', NEON_POSTGRESQL: 'Neon PostgreSQL' },
  queue: { LOCALSTACK_SQS: 'LocalStack SQS', AMAZON_SQS: 'Amazon SQS' },
  aiProvider: { GROQ: 'Groq', GEMINI: 'Gemini', DETERMINISTIC: 'Deterministic' },
} as const

function mapApiTicket(ticket: ApiTicket): Ticket {
  return { id: ticket.displayId, backendId: ticket.id, subject: ticket.subject, message: ticket.message, category: 'Awaiting triage', urgency: null, originalUrgency: null, priority: null, reliabilitySignal: null, slaRisk: 'Pending', source: ticket.channel, summary: ticket.message, rationale: 'Run the real triage worker to see the evidence-backed explanation.', evidence: [], actions: [], suggestedReply: '', policy: 'No policy decision has been recorded yet.', timeline: [{ label: 'Ticket received', time: new Date(ticket.createdAt).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }), kind: 'received' }], triageResultId: null, decisionSource: null, triageStatus: ticket.status, scenarioKey: ticket.scenarioKey, latencyMs: null, jobId: null, eventId: null, attemptCount: 0, attempts: [], provider: null, modelVersion: null }
}

function applyApiTriage(ticket: Ticket, result: TriageResult, job: TriageProcessing['job'], provider: string): Ticket {
  const urgency = result.urgency.toLowerCase() as Urgency
  const startedAt = job.startedAt ? Date.parse(job.startedAt) : Number.NaN
  const completedAt = job.completedAt ? Date.parse(job.completedAt) : Number.NaN
  const latencyMs = Number.isFinite(startedAt) && Number.isFinite(completedAt) ? Math.max(0, completedAt - startedAt) : null
  return { ...ticket, category: result.category, urgency, originalUrgency: urgency, priority: result.priorityScore, reliabilitySignal: result.reliabilitySignal, slaRisk: result.slaRisk, summary: result.summary, rationale: result.explanation, evidence: result.evidence.map((item) => typeof item === 'string' ? item : `“${item.quote}” — ${item.meaning}`), actions: result.recommendedActions, suggestedReply: result.suggestedReply, policy: result.policyIds.length ? `Policy IDs: ${result.policyIds.join(', ')}` : 'No policy IDs returned.', triageResultId: result.id, decisionSource: result.decisionSource, triageStatus: 'COMPLETED', latencyMs, jobId: job.id, eventId: job.eventId, attemptCount: job.attemptCount, attempts: job.attempts, provider, modelVersion: result.modelVersion, timeline: [...ticket.timeline.filter((event) => event.kind === 'received'), { label: `Triage decision: ${result.category.toLowerCase().replaceAll('_', ' ')}`, time: new Date(result.createdAt).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }), kind: 'decision' }, { label: 'Human review pending', time: 'now', kind: 'review' }] }
}

type IndustryChoice = { industries: Industry[]; selected: string; onSelect: (id: string) => void }

// The industry list ships in the bundle (a backend test keeps it equal to GET /api/demo/session), so the picker and
// the launch button work at first paint, before a cold API answers.
const BUNDLED_SESSION_OPTIONS: SessionOptions = bundledSessionOptions
// The default industry, and its first ticket, for a workspace whose industry is not in the list.
// The server makes a workspace without an industry an EV charging one.
const DEFAULT_INDUSTRY = 'ev-charging'
const DEFAULT_FEATURED_SCENARIO = { key: 'charger-offline-site-wide', label: 'Every charger at the demo site appears offline' }

function IndustryPicker({ industries, selected, onSelect, busy }: IndustryChoice & { busy: boolean }) {
  return (
    <fieldset className="industry-picker" disabled={busy}>
      <legend>Choose an industry</legend>
      <p className="muted">CaseLens loads demo tickets for the industry you pick. To try another one, close the demo and pick again.</p>
      <div className="industry-options">{industries.map((industry) => <label key={industry.id} className={industry.id === selected ? 'industry-option selected' : 'industry-option'}><input type="radio" name="industry" value={industry.id} checked={industry.id === selected} onChange={() => onSelect(industry.id)} /><span><strong>{industry.name}</strong><span className="muted">{industry.scenarios.length} demo tickets</span></span></label>)}</div>
    </fieldset>
  )
}

function Landing({ onOpen, busy, error, choice }: { onOpen: () => void; busy: boolean; error: string | null; choice: IndustryChoice }) {
  const selectedName = choice.industries.find((industry) => industry.id === choice.selected)?.name
  return (
    <main className="landing-shell">
      <div className="landing-grid" aria-hidden="true" />
      <section className="landing-panel" aria-labelledby="caselens-title">
        <div className="brand-lockup"><span className="brand-mark">CL</span><span>CaseLens / reviewer console</span></div>
        <p className="environment">Demo environment</p>
        <p className="environment-note">synthetic data · reviewer console</p>
        <h1 id="caselens-title" tabIndex={-1}>CaseLens</h1>
        <p className="landing-claim">Turn a messy case into a defensible next step.</p>
        <div className="access-card">
          <div><p className="eyebrow">Public reviewer demo</p><h2>Open an isolated workspace</h2><p className="muted">Launch a temporary workspace containing synthetic support cases. No account is required, and no customer or production systems are connected.</p></div>
          <IndustryPicker {...choice} busy={busy} />
          <button type="button" onClick={onOpen} disabled={busy}>{busy ? 'Creating isolated workspace...' : 'Launch live demo'}</button>
          {busy && <p className="access-status" role="status" aria-live="polite"><span className="status-dot" aria-hidden="true" />Creating an isolated workspace, connecting to the CaseLens API, and loading {selectedName ? `${selectedName} demo tickets` : 'synthetic fixtures'}. First access may take up to 20 seconds while serverless services resume.</p>}
          {error && <p className="error-banner" role="alert">{error}</p>}
        </div>
        <p className="landing-summary">CaseLens makes support-ticket triage explainable: see the signal, inspect the evidence, and keep a human in control of the correction.</p>
        <div className="honesty-row"><span className="status-dot" /><span>Integrated API</span><span className="divider" /><span>Reserved fixtures only</span></div>
      </section>
      <aside className="landing-aside" aria-label="CaseLens product promise"><p className="aside-index">01 / 03</p><p className="aside-quote">“The useful answer is the one a reviewer can explain five minutes later.”</p><div className="aside-rule" /><dl><div><dt>Signal</dt><dd>What changed?</dd></div><div><dt>Evidence</dt><dd>Why believe it?</dd></div><div><dt>Control</dt><dd>Who decides next?</dd></div></dl></aside>
    </main>
  )
}

function App() {
  const api = useMemo(() => createCaseLensApi(import.meta.env.VITE_API_BASE_URL ?? ''), [])
  const [sessionOpen, setSessionOpen] = useState(false)
  const [view, setView] = useState<View>('inbox')
  const [tickets, setTickets] = useState<Ticket[]>([])
  const [selectedTicketId, setSelectedTicketId] = useState<string | null>(null)
  const [draftUrgency, setDraftUrgency] = useState<Urgency | null>(null)
  const [feedbackNote, setFeedbackNote] = useState('')
  // The ticket whose correction was just saved. A save that finishes after the reviewer opened another ticket must not
  // show "Correction saved" there, so the message is keyed by ticket rather than a plain flag.
  const [feedbackSavedTicketId, setFeedbackSavedTicketId] = useState<string | null>(null)
  const [evaluation, setEvaluation] = useState<EvaluationSummary | null>(null)
  const [operationsOverview, setOperationsOverview] = useState<OperationsOverview | null>(null)
  const [runtime, setRuntime] = useState<RuntimeSummary | null>(null)
  const [failures, setFailures] = useState<FailurePage | null>(null)
  // Each ticket has its own triage poll, so one finishing never clears the busy state or stage text of another.
  const [busyTicketIds, setBusyTicketIds] = useState<ReadonlySet<string>>(() => new Set())
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [notice, setNotice] = useState<{ ticketId: string; text: string } | null>(null)
  const [sessionEnded, setSessionEnded] = useState(false)
  const [sessionOptions, setSessionOptions] = useState<SessionOptions>(BUNDLED_SESSION_OPTIONS)
  const [industryId, setIndustryId] = useState<string>(BUNDLED_SESSION_OPTIONS.defaultIndustry)
  const [triageStages, setTriageStages] = useState<Record<string, string>>({})
  const [workspaceIndustryId, setWorkspaceIndustryId] = useState<string | null>(null)
  const [liveState, setLiveState] = useState<LiveState>('loading')
  const [lastUpdated, setLastUpdated] = useState<Date | null>(null)
  const resumeLiveRef = useRef<(() => void) | null>(null)
  // Every request a workspace starts carries this signal. Closing the demo or opening another workspace aborts it, so a
  // triage poll from the old workspace stops and never writes into the next one.
  const sessionAbortRef = useRef(new AbortController())
  const busyTicketsRef = useRef(new Set<string>())
  // After "Start a new demo", focus follows the page: the landing heading while the workspace opens, then the inbox.
  const restartFocusRef = useRef(false)
  // The industry list may arrive after the workspace opens; without an id the workspace is the default industry.
  const workspaceIndustry = useMemo(() => sessionOptions.industries.find((industry) => industry.id === (workspaceIndustryId ?? sessionOptions.defaultIndustry)) ?? null, [sessionOptions, workspaceIndustryId])
  const featuredScenario = workspaceIndustry?.featuredScenario ?? ((workspaceIndustryId ?? DEFAULT_INDUSTRY) === DEFAULT_INDUSTRY ? DEFAULT_FEATURED_SCENARIO : null)
  const selectedTicket = useMemo(() => tickets.find((ticket) => ticket.id === selectedTicketId) ?? null, [selectedTicketId, tickets])

  useEffect(() => { if (selectedTicketId) window.requestAnimationFrame(() => document.querySelector<HTMLButtonElement>('.detail-column .back-button')?.focus({ preventScroll: true })) }, [selectedTicketId])
  useEffect(() => { document.documentElement.scrollTop = 0; document.body.scrollTop = 0 }, [view, sessionOpen, selectedTicketId])
  useEffect(() => {
    if (!restartFocusRef.current) return
    const target = sessionOpen ? document.querySelector<HTMLElement>('.workspace-main .page-heading h1') : document.getElementById('caselens-title')
    target?.focus()
    if (sessionOpen || !busy) restartFocusRef.current = false
  }, [sessionOpen, busy])
  useEffect(() => {
    let active = true
    // Runs in the background: it wakes the API and its database before the visitor clicks, and replaces the bundled
    // list if this API serves a different one (for example during a deploy). If it fails, the bundled list stays.
    api.getSessionOptions().then((options) => {
      if (!active || !options?.industries?.length) return
      setSessionOptions((current) => JSON.stringify(current) === JSON.stringify(options) ? current : options)
      setIndustryId((current) => options.industries.some((industry) => industry.id === current) ? current : options.defaultIndustry)
    }).catch(() => undefined)
    return () => { active = false }
  }, [api])
  useEffect(() => {
    if (!sessionOpen || sessionEnded || (view !== 'evaluation' && view !== 'operations')) return
    let interval: number | undefined
    let lastActivity = Date.now()
    let paused = false
    let inFlight = false
    let cancelled = false
    // Leaving the view or closing the demo aborts these reads, including any retry they are waiting to send.
    const controller = new AbortController()
    const { signal } = controller
    const refresh = async () => {
      // One request at a time: during a cold start a response can take longer than the 5-second interval.
      if (inFlight) return
      inFlight = true
      try {
        if (view === 'evaluation') {
          const next = await api.getEvaluation(signal)
          if (cancelled) return
          setEvaluation(next)
        } else {
          const [overview, failurePage] = await Promise.all([api.getOperationsOverview(signal), api.listFailures(signal)])
          if (cancelled) return
          setOperationsOverview(overview)
          setFailures(failurePage)
        }
        // A background refresh leaves the error banner alone: it belongs to the reviewer's last action.
        setLastUpdated(new Date())
        if (!paused) setLiveState('live')
      } catch (cause: unknown) {
        if (cancelled) return
        // An expired or unknown token will not come back, so stop refreshing and offer a new demo instead.
        if (cause instanceof ApiError && cause.status === 401) { stop(); setSessionEnded(true); return }
        // The status line says what failed in plain words; the raw error ("Failed to fetch", a status code) is not shown.
        if (!paused) setLiveState('failed')
      } finally {
        inFlight = false
      }
    }
    const stop = () => { if (interval !== undefined) { window.clearInterval(interval); interval = undefined } }
    const tick = () => {
      if (Date.now() - lastActivity >= LIVE_IDLE_MS) { stop(); paused = true; setLiveState('paused'); return }
      void refresh()
    }
    const resume = () => {
      lastActivity = Date.now()
      if (document.visibilityState === 'hidden' || interval !== undefined) return
      paused = false
      void refresh()
      interval = window.setInterval(tick, LIVE_REFRESH_MS)
    }
    const onVisibility = () => { if (document.visibilityState === 'hidden') stop(); else if (!paused) resume() }
    const onActivity = () => { lastActivity = Date.now(); if (paused) resume() }
    resumeLiveRef.current = resume
    setLiveState('loading')
    setLastUpdated(null)
    resume()
    document.addEventListener('visibilitychange', onVisibility)
    const activityEvents = ['pointerdown', 'keydown', 'wheel', 'touchstart'] as const
    activityEvents.forEach((name) => window.addEventListener(name, onActivity, { passive: true }))
    return () => {
      cancelled = true
      controller.abort()
      stop()
      resumeLiveRef.current = null
      document.removeEventListener('visibilitychange', onVisibility)
      activityEvents.forEach((name) => window.removeEventListener(name, onActivity))
    }
  }, [api, sessionOpen, sessionEnded, view])

  function startSession(): AbortSignal {
    sessionAbortRef.current.abort()
    sessionAbortRef.current = new AbortController()
    busyTicketsRef.current = new Set()
    return sessionAbortRef.current.signal
  }
  // Clears the token and everything the last workspace left on screen or in flight.
  function resetWorkspace() {
    api.setToken('')
    setTickets([]); setSelectedTicketId(null); setDraftUrgency(null); setFeedbackNote(''); setFeedbackSavedTicketId(null)
    setEvaluation(null); setOperationsOverview(null); setFailures(null); setRuntime(null); setWorkspaceIndustryId(null)
    setBusyTicketIds(new Set()); setTriageStages({}); setNotice(null); setError(null); setSessionEnded(false); setView('inbox')
  }
  function closeDemo() { startSession(); resetWorkspace(); setBusy(false); setSessionOpen(false) }
  function startNewDemo() { restartFocusRef.current = true; closeDemo(); void openWorkspace() }
  // A 401 means the token expired or is unknown. It will not come back, so offer a new demo instead of a retry.
  function showActionError(cause: unknown, fallback: string, conflict?: string) {
    if (cause instanceof ApiError && cause.status === 401) { setSessionEnded(true); return }
    setError(plainError(cause, fallback, conflict))
  }

  async function openWorkspace() {
    const signal = startSession()
    resetWorkspace(); setBusy(true)
    try {
      // One call opens a seeded workspace. Evaluation and Operations load themselves when they are opened, so a new
      // workspace never shows the numbers of the one before it.
      const session = await api.createSession(industryId, true)
      if (signal.aborted) return
      api.setToken(session.token); setWorkspaceIndustryId(session.industry ?? industryId)
      if (session.tickets && session.runtime) {
        setTickets(session.tickets.map(mapApiTicket)); setRuntime(session.runtime)
      } else {
        // An API from before the one-call open (for example during a deploy) returns a bare session: open it as before.
        await api.resetDemo(); const [page, currentEvaluation, currentFailures, currentRuntime] = await Promise.all([api.listTickets(), api.getEvaluation(), api.listFailures(), api.getRuntimeSummary()])
        if (signal.aborted) return
        setTickets(page.content.map(mapApiTicket)); setEvaluation(currentEvaluation); setFailures(currentFailures); setRuntime(currentRuntime)
      }
      setSelectedTicketId(null); setView('inbox'); setSessionOpen(true)
    }
    catch (cause: unknown) { if (!signal.aborted) setError(plainError(cause, 'Could not open the demo workspace. Try again in a few seconds.')) } finally { if (!signal.aborted) setBusy(false) }
  }

  function setStage(ticketId: string, text: string) { setTriageStages((current) => current[ticketId] === text ? current : { ...current, [ticketId]: text }) }
  async function hydrateTriage(ticket: Ticket) {
    if (!ticket.backendId || ticket.triageResultId || busyTicketsRef.current.has(ticket.backendId)) return
    const ticketId = ticket.backendId
    const signal = sessionAbortRef.current.signal
    const busyTickets = busyTicketsRef.current
    busyTickets.add(ticketId); setBusyTicketIds(new Set(busyTickets)); setError(null); setStage(ticketId, TRIAGE_STAGE_TEXT.SENDING)
    try {
      await api.requestTriage(ticketId, signal)
      const startedAt = Date.now()
      while (!signal.aborted && Date.now() - startedAt < TRIAGE_WAIT_MS) {
        const processing = await api.getProcessing(ticketId, signal)
        if (signal.aborted) return
        if (processing.result) {
          const provider = runtime ? runtimeLabels.aiProvider[runtime.aiProvider] : 'Configured provider'
          setTickets((current) => current.map((item) => item.backendId === ticketId ? applyApiTriage(item, processing.result as TriageResult, processing.job, provider) : item))
          return
        }
        const { status, lastErrorCode } = processing.job
        if (status === 'TERMINAL_FAILURE') throw new PlainError(TRIAGE_FAILED_TEXT)
        if (status === 'RETRYABLE_FAILURE' && lastErrorCode === 'SYNTHETIC_PROVIDER_TIMEOUT') throw new PlainError(RETRY_DEMO_STOPPED_TEXT)
        // A real provider failure is retried by the queue on its own, so keep waiting and say so.
        const text = TRIAGE_STAGE_TEXT[status === 'COMPLETED' ? 'PROCESSING' : status]
        setStage(ticketId, text)
        const elapsed = Date.now() - startedAt
        if (elapsed > SLOW_TRIAGE_NOTICE_MS) setNotice({ ticketId, text })
        await pause(triagePollDelay(elapsed), signal)
      }
      if (!signal.aborted) throw new PlainError(TRIAGE_STILL_RUNNING_TEXT)
    }
    catch (cause: unknown) { if (!signal.aborted) showActionError(cause, 'Could not triage this ticket. Try again in a few seconds.') }
    finally {
      // A closed workspace was already reset; touching state here would reach into the next one.
      if (!signal.aborted) {
        busyTickets.delete(ticketId); setBusyTicketIds(new Set(busyTickets))
        setTriageStages((current) => Object.fromEntries(Object.entries(current).filter(([id]) => id !== ticketId)))
        setNotice((current) => current?.ticketId === ticketId ? null : current)
      }
    }
  }
  function selectTicket(ticketId: string) { setSelectedTicketId(ticketId); setDraftUrgency(null); setFeedbackNote(''); setFeedbackSavedTicketId(null); const ticket = tickets.find((item) => item.id === ticketId); if (ticket) void hydrateTriage(ticket) }
  // The featured ticket is already in the inbox after the workspace opens, so open that one instead of adding a copy.
  function openScenario(scenarioKey: string) {
    const existing = tickets.find((ticket) => ticket.scenarioKey === scenarioKey)
    if (!existing) return loadScenario(scenarioKey)
    setView('inbox'); selectTicket(existing.id)
  }
  // The page is busy only while the scenario ticket is created. Its triage poll (up to two minutes) then runs like any
  // opened ticket, under that ticket's own busy state, so other tickets and the Operations retry stay usable.
  async function loadScenario(scenarioKey: string) {
    const signal = sessionAbortRef.current.signal
    setBusy(true); setError(null)
    let scenario: Ticket
    try {
      scenario = mapApiTicket(await api.createScenario(scenarioKey, signal))
      if (signal.aborted) return
      setTickets((current) => current.some((ticket) => ticket.backendId === scenario.backendId) ? current : [...current, scenario]); setSelectedTicketId(scenario.id); setDraftUrgency(null); setFeedbackNote(''); setFeedbackSavedTicketId(null); setView('inbox')
    }
    catch (cause: unknown) { if (!signal.aborted) showActionError(cause, 'Could not load the scenario. Try again in a few seconds.'); return } finally { if (!signal.aborted) setBusy(false) }
    await hydrateTriage(scenario)
  }
  function changeDraftUrgency(urgency: Urgency) { setDraftUrgency(urgency); setFeedbackSavedTicketId(null) }
  function changeFeedbackNote(note: string) { setFeedbackNote(note); setFeedbackSavedTicketId(null) }
  async function saveFeedback() {
    const ticket = selectedTicket
    if (!ticket?.backendId || !ticket.triageResultId || !draftUrgency || !canSaveCorrection(ticket, draftUrgency, feedbackNote)) return
    const urgency = draftUrgency
    const signal = sessionAbortRef.current.signal
    setBusy(true); setError(null)
    try {
      await api.submitFeedback(ticket.backendId, { triageResultId: ticket.triageResultId, category: ticket.category, urgency: urgency.toUpperCase(), slaRisk: ticket.slaRisk.toUpperCase(), note: feedbackNote.trim() }, signal)
      if (signal.aborted) return
      setTickets((current) => current.map((item) => item.id === ticket.id ? { ...item, urgency } : item)); setFeedbackSavedTicketId(ticket.id)
      // The correction is saved; a failed metrics read must not say otherwise. Evaluation reloads itself when opened.
      api.getEvaluation(signal).then((next) => { if (!signal.aborted) setEvaluation(next) }).catch(() => undefined)
    }
    catch (cause: unknown) { if (!signal.aborted) showActionError(cause, 'Could not save the correction. Try again in a few seconds.', CORRECTION_CONFLICT_TEXT) } finally { if (!signal.aborted) setBusy(false) }
  }
  async function retryFailure() {
    const failure = failures?.content[0]; if (!failure) return
    const signal = sessionAbortRef.current.signal
    setBusy(true); setError(null)
    try { await api.retryFailure(failure.id, signal); const [overview, failurePage] = await Promise.all([api.getOperationsOverview(signal), api.listFailures(signal)]); if (signal.aborted) return; setOperationsOverview(overview); setFailures(failurePage) }
    catch (cause: unknown) { if (!signal.aborted) showActionError(cause, 'Could not retry the failed case. Try again in a few seconds.') } finally { if (!signal.aborted) setBusy(false) }
  }

  if (!sessionOpen) return <Landing onOpen={openWorkspace} busy={busy} error={error} choice={{ industries: sessionOptions.industries, selected: industryId, onSelect: setIndustryId }} />
  return <div className="app-shell"><aside className="app-rail"><div className="rail-brand"><span className="brand-mark">CL</span><span>CaseLens</span></div><div className="rail-workspace"><span className="eyebrow">Workspace</span><strong>Isolated demo workspace</strong>{workspaceIndustry && <span className="workspace-industry">Industry: {workspaceIndustry.name}</span>}<span className="workspace-id">token-scoped / 24 hours</span></div><nav className="primary-nav" aria-label="Primary navigation"><button aria-label="Inbox" className={view === 'inbox' ? 'nav-item active' : 'nav-item'} onClick={() => setView('inbox')} aria-current={view === 'inbox' ? 'page' : undefined}><span className="nav-icon">↗</span><span>Inbox</span><span className="nav-count">{tickets.length}</span></button><button aria-label="Evaluation" className={view === 'evaluation' ? 'nav-item active' : 'nav-item'} onClick={() => setView('evaluation')} aria-current={view === 'evaluation' ? 'page' : undefined}><span className="nav-icon">◌</span><span>Evaluation</span></button><button aria-label="Operations" className={view === 'operations' ? 'nav-item active' : 'nav-item'} onClick={() => setView('operations')} aria-current={view === 'operations' ? 'page' : undefined}><span className="nav-icon">⌁</span><span>Operations</span><span className="nav-status">{failures?.totalElements ? failures.totalElements : 'OK'}</span></button></nav><div className="rail-footer"><div className="preview-label"><span className="status-dot" />{runtime ? runtimeLabels.environment[runtime.environment] : 'Runtime unavailable'}</div>{runtime && <div className="runtime-stack" aria-label="Runtime services"><span>{runtimeLabels.database[runtime.database]}</span><span>{runtimeLabels.queue[runtime.queue]}</span><span>{runtimeLabels.aiProvider[runtime.aiProvider]}</span></div>}<p>All records are synthetic and scoped to this temporary workspace.</p><button className="text-button" onClick={closeDemo}>Close demo</button></div></aside><main className="workspace-main"><header className="topbar"><div className="breadcrumbs"><span>CaseLens</span><span className="breadcrumb-slash">/</span><strong>{view === 'inbox' ? 'Review queue' : view === 'evaluation' ? 'Evaluation signal' : 'Operations'}</strong></div><div className="topbar-actions"><span className="live-chip"><span className="status-dot" />{runtime ? `${runtimeLabels.aiProvider[runtime.aiProvider]} · ${runtimeLabels.database[runtime.database]}` : 'Backend data'}</span><span className="avatar">HD</span></div></header>{sessionEnded ? <div className="error-banner workspace-error session-ended" role="alert"><span>{SESSION_ENDED_BANNER_TEXT}</span> <button type="button" className="text-button" onClick={startNewDemo}>Start a new demo</button></div> : error && <p className="error-banner workspace-error" role="alert">{error}</p>}{notice && !error && !sessionEnded && <p className="notice-banner workspace-error" role="status">{notice.text}</p>}{view === 'inbox' && <InboxView tickets={tickets} selectedTicket={selectedTicket} draftUrgency={draftUrgency} feedbackNote={feedbackNote} feedbackSaved={selectedTicket !== null && feedbackSavedTicketId === selectedTicket.id} busy={busy || busyTicketIds.size > 0} detailBusy={busy || (selectedTicket !== null && busyTicketIds.has(selectedTicket.backendId))} triageStages={triageStages} featuredScenario={featuredScenario} onLoadScenario={loadScenario} onOpenScenario={(key) => void openScenario(key)} onSelectTicket={selectTicket} onBack={() => setSelectedTicketId(null)} onDraftUrgency={changeDraftUrgency} onFeedbackNote={changeFeedbackNote} onSaveFeedback={saveFeedback} />}{(view === 'evaluation' || view === 'operations') && <LiveStatus state={sessionEnded ? 'ended' : liveState} lastUpdated={lastUpdated} onRefresh={() => resumeLiveRef.current?.()} />}{view === 'evaluation' && <EvaluationView evaluation={evaluation} />}{view === 'operations' && <OperationsView overview={operationsOverview} failures={failures} busy={busy} onRetry={retryFailure} />}</main></div>
}

// Save is possible only for a real change: not the urgency the model gave (the API refuses that with 409, since it
// matches the original result) and not the correction already saved.
function canSaveCorrection(ticket: Ticket, draftUrgency: Urgency | null, note: string) {
  return Boolean(ticket.triageResultId && draftUrgency && draftUrgency !== ticket.originalUrgency && draftUrgency !== ticket.urgency && note.trim())
}

const SLA_RISK_RANK: Record<string, number> = { HIGH: 3, MEDIUM: 2, LOW: 1 }
// Triaged tickets first, the highest priority score first and then the higher SLA risk. Tickets not triaged yet come
// after them in the order the API sent (newest first); the sort is stable, so equal tickets keep that order too.
function reviewOrder(tickets: Ticket[]): Ticket[] {
  return [...tickets].sort((a, b) => {
    if (a.priority === null || b.priority === null) return Number(a.priority === null) - Number(b.priority === null)
    return b.priority - a.priority || (SLA_RISK_RANK[b.slaRisk] ?? 0) - (SLA_RISK_RANK[a.slaRisk] ?? 0)
  })
}
type InboxViewProps = { tickets: Ticket[]; selectedTicket: Ticket | null; draftUrgency: Urgency | null; feedbackNote: string; feedbackSaved: boolean; busy: boolean; detailBusy: boolean; triageStages: Record<string, string>; featuredScenario: Industry['featuredScenario'] | null; onLoadScenario: (scenarioKey: string) => void; onOpenScenario: (scenarioKey: string) => void; onSelectTicket: (ticketId: string) => void; onBack: () => void; onDraftUrgency: (urgency: Urgency) => void; onFeedbackNote: (note: string) => void; onSaveFeedback: () => void }
function InboxView({ tickets, selectedTicket, draftUrgency, feedbackNote, feedbackSaved, busy, detailBusy, triageStages, featuredScenario, onLoadScenario, onOpenScenario, onSelectTicket, onBack, onDraftUrgency, onFeedbackNote, onSaveFeedback }: InboxViewProps) {
  return <div className="page-content"><div className="page-heading"><div><p className="eyebrow">Human review queue</p><h1 tabIndex={-1}>Ticket inbox</h1><p className="page-intro">Prioritized cases with the evidence behind each recommendation.</p></div><div className="heading-meta"><span className="metric-label">Queue health</span><strong><span className="status-dot" />{busy ? 'Processing' : 'Stable'}</strong><span className="metric-detail">{tickets.length} synthetic cases</span></div></div><div className={selectedTicket ? 'inbox-layout with-detail' : 'inbox-layout'}><section className="queue-column" aria-labelledby="queue-heading"><div className="section-bar"><div><h2 id="queue-heading">Needs review</h2><span className="muted">Triaged cases first, by priority and SLA risk</span></div><span className="count-pill">{tickets.length}</span></div><div className="scenario-card">{featuredScenario ? <><div><span className="scenario-kicker">Try a scenario</span><strong>{featuredScenario.label}</strong><span className="muted">Open this demo ticket and send it through the real queue worker.</span></div><button className="secondary-button" onClick={() => onOpenScenario(featuredScenario.key)} disabled={busy}>Load ticket: {featuredScenario.label}</button></> : <div><span className="scenario-kicker">Try a scenario</span><span className="muted">The demo scenarios did not load. The tickets in the queue still work.</span></div>}<div className="scenario-divider" /><div><span className="scenario-kicker">Failure handling</span><strong>Controlled retry demonstration</strong><span className="muted">Records one synthetic provider timeout, then exposes the job in Operations for a reviewer retry.</span></div><button className="secondary-button retry-demo-button" onClick={() => void onLoadScenario('provider-retry-demo')} disabled={busy}>Run controlled retry demo</button></div><div className="ticket-list">{reviewOrder(tickets).map((ticket) => <TicketRow key={`${ticket.id}-${ticket.backendId ?? 'fixture'}`} ticket={ticket} selected={ticket.id === selectedTicket?.id} onSelect={onSelectTicket} />)}</div></section>{selectedTicket && <TicketDetail ticket={selectedTicket} stage={triageStages[selectedTicket.backendId] ?? null} draftUrgency={draftUrgency} feedbackNote={feedbackNote} feedbackSaved={feedbackSaved} busy={detailBusy} onBack={onBack} onDraftUrgency={onDraftUrgency} onFeedbackNote={onFeedbackNote} onSaveFeedback={onSaveFeedback} />}</div></div>
}

function TicketRow({ ticket, selected, onSelect }: { ticket: Ticket; selected: boolean; onSelect: (ticketId: string) => void }) {
  return <article className={selected ? 'ticket-row selected' : 'ticket-row'}><div className="ticket-row-top"><span className="ticket-id">{ticket.id}</span><span className={ticket.urgency ? `urgency urgency-${ticket.urgency}` : 'urgency urgency-pending'}>{formatUrgency(ticket.urgency)}</span></div><h3>{ticket.subject}</h3><p>{ticket.summary}</p><div className="ticket-row-bottom"><span>{ticket.category}</span><span className="sla-indicator"><span className="status-dot" />{ticket.slaRisk}</span></div><button className="review-link" onClick={() => onSelect(ticket.id)}>Review {ticket.id} <span>→</span></button></article>
}

function TicketDetail({ ticket, stage, draftUrgency, feedbackNote, feedbackSaved, busy, onBack, onDraftUrgency, onFeedbackNote, onSaveFeedback }: { ticket: Ticket; stage: string | null; draftUrgency: Urgency | null; feedbackNote: string; feedbackSaved: boolean; busy: boolean; onBack: () => void; onDraftUrgency: (urgency: Urgency) => void; onFeedbackNote: (note: string) => void; onSaveFeedback: () => void }) {
  const displayedUrgency = draftUrgency ?? ticket.urgency
  return (
    <section className="detail-column" aria-labelledby="detail-heading">
      <button className="back-button" onClick={onBack}>← Back to queue</button>
      <div className="detail-heading"><div><span className="ticket-id">{ticket.id}</span><h2 id="detail-heading">Why this ticket is here</h2></div><div className="detail-badges">{ticket.priority !== null && <span className="activity-chip">Priority {ticket.priority}</span>}<span className={`urgency urgency-${displayedUrgency}`}>{formatUrgency(displayedUrgency)} urgency</span></div></div>
      <div className="detail-subject"><h3>{ticket.subject}</h3><p>{ticket.message}</p></div>
      <div className="explain-card"><div className="explain-header"><div><span className="eyebrow">Triage explanation</span><h3>{ticket.category}</h3></div><div className="confidence"><strong>{ticket.reliabilitySignal ?? 'Pending'}</strong><span>reliability</span></div></div><p className="lead-copy">{!ticket.triageResultId && (stage ?? (busy ? TRIAGE_STAGE_TEXT.SENDING : null)) || ticket.rationale}</p><div className="evidence-list"><span className="eyebrow">Evidence used</span>{ticket.evidence.length ? ticket.evidence.map((item) => <div className="evidence-item" key={item}><span className="evidence-mark">+</span><span>{item}</span></div>) : <p className="muted">Evidence will appear after triage completes.</p>}</div></div>
      <div className="detail-grid"><section className="detail-card"><span className="eyebrow">Recommended next steps</span><ol className="action-list">{ticket.actions.map((action) => <li key={action}>{action}</li>)}</ol></section><section className="detail-card"><span className="eyebrow">Guardrail</span><p className="guardrail-copy">{ticket.policy}</p><span className="source-line">Source: {ticket.source}</span></section></div>
      {ticket.suggestedReply && <section className="detail-card suggested-reply"><span className="eyebrow">Suggested reply</span><p className="guardrail-copy">{ticket.suggestedReply}</p><span className="source-line">A draft for the reviewer to check before sending.</span></section>}
      {ticket.jobId && <section className="processing-card"><div className="section-bar"><div><span className="eyebrow">Persisted backend evidence</span><h3>Durable processing trace</h3></div><span className="activity-chip">{ticket.attemptCount} attempt{ticket.attemptCount === 1 ? '' : 's'}</span></div><dl className="processing-identifiers"><div><dt>Event</dt><dd><code title={ticket.eventId ?? undefined}>{ticket.eventId}</code></dd></div><div><dt>Job</dt><dd><code title={ticket.jobId}>{ticket.jobId}</code></dd></div><div><dt>Model</dt><dd>{ticket.modelVersion}</dd></div><div><dt>Decision</dt><dd>{ticket.decisionSource}</dd></div></dl><ol className="processing-stages"><li><span>01</span><div><strong>Outbox committed</strong><small>Ticket and triage event entered durable storage together.</small></div></li><li><span>02</span><div><strong>Queue accepted</strong><small>Worker job references the persisted outbox event.</small></div></li><li><span>03</span><div><strong>Worker completed</strong><small>{ticket.attempts.length ? `${ticket.attempts.length} persisted attempt record${ticket.attempts.length === 1 ? '' : 's'}.` : 'Completion recorded on the job.'}</small></div></li><li><span>04</span><div><strong>{ticket.decisionSource === 'AI_VALIDATED' ? `${ticket.provider} validated` : 'Rules fallback selected'}</strong><small>{ticket.decisionSource === 'AI_VALIDATED' ? 'Structured output passed evidence and policy validation.' : 'Deterministic rules produced the safe result.'}</small></div></li></ol></section>}
      <section className="timeline-card"><div className="section-bar"><div><span className="eyebrow">Decision trace</span><h3>What happened</h3></div><span className="latency">{ticket.latencyMs === null ? '—' : `${ticket.latencyMs} ms`}</span></div><div className="timeline">{ticket.timeline.map((event) => <div className="timeline-event" key={`${event.label}-${event.time}`}><span className={`timeline-marker ${event.kind}`} /><div><strong>{event.label}</strong><span>{event.time}</span></div></div>)}</div></section>
      <section className="feedback-card"><div><span className="eyebrow">Human feedback</span><h3>Would you change the urgency?</h3><p className="muted">Corrections are written to the backend workspace and kept beside the original result.</p>{ticket.originalUrgency && ticket.urgency !== ticket.originalUrgency && <p className="muted">Original triage urgency: {formatUrgency(ticket.originalUrgency)}.</p>}</div><div className="feedback-actions"><div className="urgency-options" role="group" aria-label="Urgency correction"><button disabled={!ticket.triageResultId || busy} className={displayedUrgency === 'low' ? 'selected' : ''} aria-pressed={displayedUrgency === 'low'} onClick={() => onDraftUrgency('low')}>Mark urgency low</button><button disabled={!ticket.triageResultId || busy} className={displayedUrgency === 'medium' ? 'selected' : ''} aria-pressed={displayedUrgency === 'medium'} onClick={() => onDraftUrgency('medium')}>Mark urgency medium</button><button disabled={!ticket.triageResultId || busy} className={displayedUrgency === 'high' ? 'selected' : ''} aria-pressed={displayedUrgency === 'high'} onClick={() => onDraftUrgency('high')}>Mark urgency high</button><button disabled={!ticket.triageResultId || busy} className={displayedUrgency === 'critical' ? 'selected' : ''} aria-pressed={displayedUrgency === 'critical'} onClick={() => onDraftUrgency('critical')}>Mark urgency critical</button></div><label htmlFor="feedback-note">Review note</label><textarea id="feedback-note" value={feedbackNote} onChange={(event) => onFeedbackNote(event.target.value)} placeholder="Explain the correction" rows={3} disabled={!ticket.triageResultId || busy} />{draftUrgency && draftUrgency === ticket.originalUrgency && draftUrgency !== ticket.urgency && <p className="muted">This is the urgency the triage gave, so there is no correction to save.</p>}<button className="primary-button" onClick={onSaveFeedback} disabled={!canSaveCorrection(ticket, draftUrgency, feedbackNote) || busy}>Save correction</button></div>{feedbackSaved && <p className="success-toast" role="status" aria-live="polite"><span aria-hidden="true">✓</span>Correction saved. Evaluation metrics updated.</p>}</section>
    </section>
  )
}

// The API sends null while a job has not finished. An older API sends 0 for the same case, which is shown as it is.
function formatLatency(latencyMs: number | null | undefined) { return latencyMs === null || latencyMs === undefined ? 'Pending' : `${latencyMs} ms` }

function formatEventTime(value: string) {
  return new Date(value).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })
}

const LIVE_STATUS_TEXT: Record<LiveState, string> = {
  loading: 'Loading the latest numbers for this workspace. The first load can take up to 20 seconds while the service starts.',
  live: 'Live: updates every 5 seconds while this tab is open.',
  paused: 'Live updates are paused because there was no activity for 10 minutes.',
  failed: 'Could not load the latest numbers. The service may be starting or busy. Trying again in 5 seconds.',
  ended: 'Your demo session has ended, so live updates stopped.',
}

// Only the state sentence is a live region, so a screen reader speaks when the state changes, not on every refresh.
function LiveStatus({ state, lastUpdated, onRefresh }: { state: LiveState; lastUpdated: Date | null; onRefresh: () => void }) {
  const time = lastUpdated ? lastUpdated.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' }) : null
  return <p className={state === 'live' || state === 'loading' ? 'live-status' : 'live-status paused'}><span role="status">{LIVE_STATUS_TEXT[state]}</span>{time && <span className="live-updated"> Last updated at {time}.</span>}{state === 'paused' && <> <button type="button" className="text-button" onClick={onRefresh}>Refresh now</button></>}</p>
}

function EvaluationView({ evaluation }: { evaluation: EvaluationSummary | null }) {
  if (!evaluation) return <div className="page-content narrow-page"><div className="page-heading"><div><p className="eyebrow">Measured quality</p><h1>Evaluation signal</h1><p className="page-intro">Loading the evaluation numbers for this workspace...</p></div></div></div>
  const percent = (value: number | undefined) => value === undefined ? '—' : `${Math.round(value * 100)}%`
  const events = evaluation?.recentEvents ?? []
  const providers = evaluation?.providerUsage ?? []
  return <div className="page-content narrow-page"><div className="page-heading"><div><p className="eyebrow">Measured quality</p><h1>Evaluation signal</h1><p className="page-intro">Persisted results, reviewer corrections, and provider activity from this workspace.</p></div><span className="date-stamp">{evaluation ? new Date(evaluation.generatedAt).toLocaleDateString() : 'Loading…'}</span></div><section className="signal-hero"><div><span className="eyebrow">Current signal</span><h2>Reviewers can see where model output earns trust.</h2><p>Evaluation keeps model usefulness separate from human authority. Empty values mean this workspace has not processed a case yet.</p></div><div className="signal-score"><strong>{percent(evaluation?.agreementRate)}</strong><span>agreement</span></div></section><div className="metric-grid"><MetricCard label="Agreement" value={percent(evaluation?.agreementRate)} detail={`${evaluation?.evaluatedResults ?? 0} evaluated cases`} tone="teal" /><MetricCard label="Correction rate" value={percent(evaluation?.correctionRate)} detail={`${evaluation?.triageResults ?? 0} persisted results`} tone="coral" /><MetricCard label="p50 latency" value={evaluation ? `${evaluation.medianLatencyMs} ms` : '—'} detail={evaluation ? `p95 ${evaluation.p95LatencyMs} ms` : 'Waiting for data'} tone="ink" /><MetricCard label="Failure rate" value={percent(evaluation?.failureRate)} detail="From recorded triage jobs" tone={evaluation?.failureRate ? 'coral' : 'teal'} /></div><section className="provider-card"><div><span className="eyebrow">Provider usage</span><h2>{providers.length ? `${providers.length} active provider${providers.length === 1 ? '' : 's'}` : 'No provider calls yet'}</h2><p className="muted">Usage is read from model-invocation records without exposing credentials or raw ticket content.</p></div><div className="provider-summary">{providers.length ? providers.map((provider) => <div className="provider-row" key={provider.provider}><span>{provider.provider} · {provider.requests} requests / {provider.successes} successes</span><strong>{provider.inputTokens + provider.outputTokens} tokens</strong><span className="bar"><span style={{ width: provider.requests ? `${Math.round((provider.successes / provider.requests) * 100)}%` : '0%' }} /></span></div>) : <span className="muted">No persisted provider activity yet.</span>}</div></section><section className="activity-card"><span className="eyebrow">Persisted event stream</span><h2>Recent triage activity</h2><p className="muted">A compact view of the decisions and reviewer signals that feed the evaluation summary.</p>{events.length ? <div className="activity-list">{events.map((event) => <div className="activity-row" key={event.resultId}><div className="activity-main"><div className="activity-title"><strong>{event.decisionSource}</strong><span className={event.corrected ? 'activity-chip corrected' : 'activity-chip'}>{event.corrected ? 'Corrected' : event.evaluated ? 'Evaluated' : 'Not evaluated'}</span></div><span className="activity-meta"><span>{event.provider ?? 'Not called'}</span>{event.modelVersion && <> · <span>{event.modelVersion}</span></>} · <span>{formatEventTime(event.createdAt)}</span></span></div><span className="activity-latency">{formatLatency(event.latencyMs)}</span></div>)}</div> : <p className="empty-state">No evaluations yet. Process a synthetic ticket from Inbox to populate this activity stream.</p>}</section></div>
}

function MetricCard({ label, value, detail, tone }: { label: string; value: string; detail: string; tone: string }) { return <article className={`metric-card tone-${tone}`}><span className="metric-label">{label}</span><strong>{value}</strong><span className="metric-detail">{detail}</span></article> }

function OpsMetric({ label, value, detail, tone }: { label: string; value: string; detail: string; tone: string }) { return <article className={`ops-card tone-${tone}`}><span className="eyebrow">{label}</span><strong className="ops-value">{value}</strong><p>{detail}</p></article> }

function OperationsView({ overview, failures, busy, onRetry }: { overview: OperationsOverview | null; failures: FailurePage | null; busy: boolean; onRetry: () => void }) {
  if (!overview) return <div className="page-content narrow-page"><div className="page-heading"><div><p className="eyebrow">Reliability view</p><h1>Operations</h1><p className="page-intro">Loading the queue and AI provider status for this workspace...</p></div></div></div>
  const count = failures?.totalElements ?? 0
  const queue = overview?.queue
  const operations = overview.recent ?? []
  const providers = overview?.providers ?? []
  const providerDegraded = Boolean(overview && (overview.providerFailureCount > 0 || overview.fallbackCount > 0))
  const firstFailure = failures?.content[0]
  const controlledRecovery = firstFailure?.lastErrorCode === 'SYNTHETIC_PROVIDER_TIMEOUT'
  const recoveryHeading = controlledRecovery ? 'Recovery required' : `${count} action${count === 1 ? '' : 's'} required`
  const recoveryDetail = controlledRecovery ? 'A controlled synthetic timeout was recorded. Retry the case to verify the recovery path.' : `Job ${firstFailure?.id ?? 'unknown'} remains visible until a reviewer retries it.`
  return <div className="page-content narrow-page"><div className="page-heading"><div><p className="eyebrow">Reliability view</p><h1>Operations</h1><p className="page-intro">Live queue state, provider signals, and recoverable work from the backend.</p></div><span className="live-chip"><span className="status-dot" />{count ? 'Attention' : providerDegraded ? 'Degraded' : 'Healthy'}</span></div><section className={count ? 'operations-hero failure-state' : providerDegraded ? 'operations-hero provider-state' : 'operations-hero'}><div className="checkmark">{count ? '!' : providerDegraded ? '!' : '✓'}</div><div><h2>{count ? recoveryHeading : providerDegraded ? 'Provider degraded, queue completing with guardrails' : 'No unresolved failures'}</h2><p>{count ? recoveryDetail : providerDegraded ? 'Provider failures and fallback decisions are visible without exposing ticket content.' : 'The queue has no recorded reviewer-recoverable failures.'}</p></div>{count ? <button className="primary-button" onClick={() => void onRetry()} disabled={busy}>{controlledRecovery ? 'Retry case' : 'Retry failed case'}</button> : <span className="muted">Observed from backend</span>}</section><div className="operations-grid"><OpsMetric label="Queued jobs" value={String(queue?.queued ?? 0)} detail="Waiting for worker capacity." tone="teal" /><OpsMetric label="Processing jobs" value={String(queue?.processing ?? 0)} detail="Currently being handled." tone="ink" /><OpsMetric label="Completed jobs" value={String(queue?.completed ?? 0)} detail="Persisted successful jobs." tone="teal" /><OpsMetric label="Queue failures" value={String((queue?.retryableFailures ?? 0) + (queue?.terminalFailures ?? 0))} detail={`${queue?.retryableFailures ?? 0} retryable · ${queue?.terminalFailures ?? 0} terminal`} tone={count ? 'coral' : 'ink'} /><OpsMetric label="Fallback results" value={String(overview?.fallbackCount ?? 0)} detail="Guardrailed rules decisions." tone={overview?.fallbackCount ? 'coral' : 'ink'} /><OpsMetric label="Provider failures" value={String(overview?.providerFailureCount ?? 0)} detail="Recorded invocation failures." tone={overview?.providerFailureCount ? 'coral' : 'ink'} /></div><section className="activity-card"><span className="eyebrow">Persisted processing</span><h2>Recent processing</h2><p className="muted">Recent job state is derived from the workspace database and capped for safe review.</p>{operations.length ? <div className="activity-list">{operations.map((operation) => <div className="activity-row" key={operation.jobId}><div className="activity-main"><div className="activity-title"><strong>{operation.status.replaceAll('_', ' ')}</strong><span className={operation.lastErrorCode ? 'activity-chip corrected' : 'activity-chip'}>{operation.lastErrorCode ?? `${operation.attemptCount} attempt${operation.attemptCount === 1 ? '' : 's'}`}</span></div><span className="activity-meta">{[operation.provider ?? 'Not called', operation.modelVersion].filter(Boolean).join(' · ')} · updated {formatEventTime(operation.updatedAt)}</span></div><span className="activity-latency">{formatLatency(operation.latencyMs)}</span></div>)}</div> : <p className="empty-state">No persisted processing activity yet. Run a synthetic ticket from Inbox to see queue and provider state here.</p>}</section><section className="provider-card"><div><span className="eyebrow">Provider health</span><h2>{providers.length ? 'Invocation telemetry' : 'No provider calls yet'}</h2><p className="muted">Counts and latency only; prompts, responses, and credentials stay out of the operations surface.</p></div><div className="provider-summary">{providers.length ? providers.map((provider) => <div className="provider-row" key={provider.provider}><span>{provider.provider} · {provider.requests} requests / {provider.successes} successes / {provider.failures} failures</span><strong>{provider.averageLatencyMs === null ? '—' : `${provider.averageLatencyMs} ms avg`}</strong><span className="bar"><span style={{ width: provider.requests ? `${Math.round((provider.successes / provider.requests) * 100)}%` : '0%' }} /></span></div>) : <span className="muted">No persisted provider activity yet.</span>}</div></section><section className="runbook-card"><span className="eyebrow">Failure handling</span><h2>Every failure has a next state.</h2><div className="runbook-steps"><div><span>01</span><strong>Retryable</strong><p>SQS owns automatic redelivery.</p></div><div><span>02</span><strong>Terminal</strong><p>Record the failure and surface it.</p></div><div><span>03</span><strong>Human recovery</strong><p>Replay only acknowledged or terminal work.</p></div></div></section></div>
}

export default App
