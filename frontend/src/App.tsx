import { useEffect, useMemo, useRef, useState } from 'react'
import bundledSessionOptions from './industries.json'
import { ApiError, createCaseLensApi, type EvaluationSummary, type FailurePage, type OperationsOverview, type RuntimeSummary, type SessionOptions, type TriageResult } from './api'
import { HowItsBuilt, PhoneMenu, type NavView } from './Dialogs'
import { Landing } from './Landing'
import {
  applyReview, CONFLICT_NEWEST_SHOWN_TEXT, CONFLICT_REOPEN_TEXT, CORRECTION_CONFLICT_TEXT, isPhone, mapApiTicket, pause, plainError, PlainError, RETRY_DEMO_SCENARIO, RETRY_DEMO_STOPPED_TEXT,
  reviewOrder, SESSION_ENDED_BANNER_TEXT, SLOW_TRIAGE_NOTICE_MS, TRIAGE_FAILED_TEXT, TRIAGE_STAGE_TEXT, TRIAGE_STILL_RUNNING_TEXT,
  TRIAGE_WAIT_MS, triagePollDelay, URGENCY_LABEL, deciderOf, WAKING_TEXT, type Ticket, type TriageStage, type Urgency,
} from './model'
import { LiveStatus, QualityOps, type LiveState } from './QualityOps'
import { Queue, type TicketActivity } from './Queue'
import { TicketDetail } from './TicketDetail'
import { Byline, Wordmark } from './ui'

// Quality and operations refreshes every 5 seconds while someone is looking. They pause in a hidden tab and stop
// after 10 minutes without a click, key press, scroll or touch, so an open tab does not keep the API and Neon awake.
const LIVE_REFRESH_MS = 5_000
const LIVE_IDLE_MS = 10 * 60_000

// The industry list ships in the bundle (a backend test keeps it equal to GET /api/demo/session), so the picker and
// the launch button work at first paint, before a cold API answers.
const BUNDLED_SESSION_OPTIONS: SessionOptions = bundledSessionOptions
// The landing opens on SaaS support, which every visitor can read without knowing the industry. The API's own default
// (EV charging) still applies to a client that sends no industry.
const PREFERRED_INDUSTRY = 'saas-support'
const preferredIndustry = (options: SessionOptions) => options.industries.some((industry) => industry.id === PREFERRED_INDUSTRY) ? PREFERRED_INDUSTRY : options.defaultIndustry

type Pane = 'list' | 'detail'
type TicketError = { text: string; hold: boolean }
type Stage = { stage: TriageStage; slow: boolean }

const VIEW_TITLE: Record<NavView, string> = { inbox: 'Inbox', quality: 'Quality and operations' }
// The retry demo shows as pending until its failure is listed, or for at most this long.
const RETRY_DEMO_PENDING_MS = 90_000

function NavIcon({ view }: { view: NavView }) {
  const path = view === 'inbox' ? 'M2 9.5h3.2l1 2h3.6l1-2H14M2 9.5 4 3h8l2 6.5V13H2z' : 'M2 13.5h12M4 11V7.5M8 11V3.5M12 11V6'
  return <svg viewBox="0 0 16 16" aria-hidden="true" focusable="false"><path d={path} fill="none" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round" strokeLinejoin="round" /></svg>
}

function App() {
  const api = useMemo(() => createCaseLensApi(import.meta.env.VITE_API_BASE_URL ?? ''), [])
  const [sessionOpen, setSessionOpen] = useState(false)
  const [view, setView] = useState<NavView>('inbox')
  const [pane, setPane] = useState<Pane>('list')
  const [tickets, setTickets] = useState<Ticket[]>([])
  const [selectedTicketId, setSelectedTicketId] = useState<string | null>(null)
  const [evaluation, setEvaluation] = useState<EvaluationSummary | null>(null)
  const [operationsOverview, setOperationsOverview] = useState<OperationsOverview | null>(null)
  const [runtime, setRuntime] = useState<RuntimeSummary | null>(null)
  const [failures, setFailures] = useState<FailurePage | null>(null)
  // Each ticket has its own triage poll and its own review save, so one finishing never clears the state of another.
  const [busyTicketIds, setBusyTicketIds] = useState<ReadonlySet<string>>(() => new Set())
  const [savingTicketIds, setSavingTicketIds] = useState<ReadonlySet<string>>(() => new Set())
  const [triageStages, setTriageStages] = useState<Record<string, Stage>>({})
  // An error from a ticket action belongs to that ticket and shows on it; workspace errors show above the sheet.
  // hold: a failure a reload cannot fix (the worker gave up), shown until the reviewer acts on it.
  const [ticketErrors, setTicketErrors] = useState<Record<string, TicketError>>({})
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [sessionEnded, setSessionEnded] = useState(false)
  const [sessionOptions, setSessionOptions] = useState<SessionOptions>(BUNDLED_SESSION_OPTIONS)
  const [industryId, setIndustryId] = useState<string>(() => preferredIndustry(BUNDLED_SESSION_OPTIONS))
  const [workspaceIndustryId, setWorkspaceIndustryId] = useState<string | null>(null)
  const [liveState, setLiveState] = useState<LiveState>('loading')
  const [lastUpdated, setLastUpdated] = useState<Date | null>(null)
  const [dialog, setDialog] = useState<'built' | 'menu' | null>(null)
  const [retryDemoStartedAt, setRetryDemoStartedAt] = useState<number | null>(null)
  // One polite live region carries state changes (triage stage, review saved). It is never refreshed on a timer.
  const [announcement, setAnnouncement] = useState('')
  const resumeLiveRef = useRef<(() => void) | null>(null)
  // Reads Quality and operations once, now, without waiting for the next 5-second tick.
  const refreshLiveRef = useRef<(() => void) | null>(null)
  // Retries a failed job through the same reads as the live refresh: one request at a time, aborted when the screen
  // closes, and an answer that started before the retry never overwrites the state after it.
  const retryLiveRef = useRef<((jobId: string) => Promise<boolean>) | null>(null)
  // A synchronous guard against a double click, and the retry demo ticket kept when its triage request failed.
  const retryDemoRef = useRef<{ running: boolean; ticketId: string | null }>({ running: false, ticketId: null })
  // Bumped after Refresh now, Simulate provider timeout and Retry case, so focus moves to the recovery banner heading.
  const [qualityFocus, setQualityFocus] = useState(0)
  // Every request a workspace starts carries this signal. Closing the demo or opening another workspace aborts it, so a
  // triage poll from the old workspace stops and never writes into the next one.
  const sessionAbortRef = useRef(new AbortController())
  const busyTicketsRef = useRef(new Set<string>())
  // One rule keeps the shared AI quota safe: POST /triage happens only on an explicit click (Run live AI, Re-run live,
  // Try again) or the first time a ticket with no result and no request is opened. This records "a triage was requested
  // for this ticket in this session", set when the POST is sent (or a retry or a refused review shows a job may exist)
  // and cleared only when the workspace resets. Any later open only reads the status (GET /processing).
  const requestedRef = useRef(new Set<string>())
  // The ticket an Operations retry touched while it stayed selected: going back to the Inbox reads it once.
  const retriedSelectedRef = useRef<string | null>(null)
  // Bumped after a refused review so its open draft is dropped.
  const [reviewEpochs, setReviewEpochs] = useState<Record<string, number>>({})
  const savingTicketsRef = useRef(new Set<string>())
  const selectedRef = useRef<string | null>(null)
  // After "Start a new demo", focus follows the page: the landing heading while the workspace opens, then the inbox.
  const restartFocusRef = useRef(false)
  // The industry list may arrive after the workspace opens; without an id the workspace is the default industry.
  const workspaceIndustry = useMemo(() => sessionOptions.industries.find((industry) => industry.id === (workspaceIndustryId ?? sessionOptions.defaultIndustry)) ?? null, [sessionOptions, workspaceIndustryId])
  const industryName = workspaceIndustry?.name ?? 'demo'
  // The retry demo ticket fails on purpose; it lives in Quality and operations, never in the inbox.
  const inboxTickets = useMemo(() => tickets.filter((ticket) => ticket.scenarioKey !== RETRY_DEMO_SCENARIO), [tickets])
  const selectedTicket = useMemo(() => inboxTickets.find((ticket) => ticket.id === selectedTicketId) ?? null, [selectedTicketId, inboxTickets])
  selectedRef.current = selectedTicket?.backendId ?? null
  const ticketsRef = useRef(tickets)
  ticketsRef.current = tickets
  const stillShows = (ticketId: string, resultId: string) => ticketsRef.current.find((item) => item.backendId === ticketId)?.result?.id === resultId
  // The phone layout follows the viewport, including a rotation, so the ticket heading is the h1 only while the list is hidden.
  const [phoneLayout, setPhoneLayout] = useState(isPhone)
  useEffect(() => {
    if (typeof window.matchMedia !== 'function') return
    const query = window.matchMedia('(max-width: 699px)')
    const onChange = () => setPhoneLayout(query.matches)
    query.addEventListener?.('change', onChange)
    return () => query.removeEventListener?.('change', onChange)
  }, [])

  useEffect(() => { document.documentElement.scrollTop = 0; document.body.scrollTop = 0 }, [view, sessionOpen])
  // Once the simulated failure is listed, the demo is no longer pending; after its retry the banner reads healthy again.
  useEffect(() => {
    if (retryDemoStartedAt !== null && failures?.content.some((failure) => failure.lastErrorCode === 'SYNTHETIC_PROVIDER_TIMEOUT')) {
      setRetryDemoStartedAt(null)
      setAnnouncement('Recovery required. The synthetic provider timeout is ready to retry.')
    }
  }, [failures, retryDemoStartedAt])
  useEffect(() => {
    if (!restartFocusRef.current) return
    const target = sessionOpen ? document.getElementById('inbox-title') : document.getElementById('caselens-title')
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
      setIndustryId((current) => options.industries.some((industry) => industry.id === current) ? current : preferredIndustry(options))
    }).catch(() => undefined)
    return () => { active = false }
  }, [api])
  // The phone shows one pane at a time. Opening a ticket adds a history entry, so the system Back gesture returns to
  // the list instead of leaving the demo.
  useEffect(() => {
    const onPop = () => setPane('list')
    window.addEventListener('popstate', onPop)
    return () => window.removeEventListener('popstate', onPop)
  }, [])
  useEffect(() => {
    if (!sessionOpen || sessionEnded || view !== 'quality') return
    let interval: number | undefined
    let lastActivity = Date.now()
    let paused = false
    let inFlight = false
    let cancelled = false
    // queued: a refresh asked for while one is in flight runs right after it. generation: a retry bumps it, so an
    // answer that was already on its way is dropped instead of overwriting the newer state.
    let queued = false
    let generation = 0
    // Leaving the view or closing the demo aborts these reads, including any retry they are waiting to send.
    const controller = new AbortController()
    const { signal } = controller
    const refresh = async (queue = false) => {
      // One request at a time: during a cold start a response can take longer than the 5-second interval.
      if (inFlight) { if (queue) queued = true; return }
      inFlight = true
      const started = generation
      try {
        // The same three reads the separate Evaluation and Operations screens made, together and never overlapping.
        const [next, overview, failurePage] = await Promise.all([api.getEvaluation(signal), api.getOperationsOverview(signal), api.listFailures(signal)])
        if (cancelled || started !== generation) return
        setEvaluation(next)
        setOperationsOverview(overview)
        setFailures(failurePage)
        // A background refresh leaves the error banner alone: it belongs to the reviewer's last action.
        setLastUpdated(new Date())
        if (!paused) setLiveState('live')
      } catch (cause: unknown) {
        if (cancelled || started !== generation) return
        // An expired or unknown token will not come back, so stop refreshing and offer a new demo instead.
        if (cause instanceof ApiError && cause.status === 401) { stop(); setSessionEnded(true); return }
        // The status line says what failed in plain words; the raw error ("Failed to fetch", a status code) is not shown.
        if (!paused) setLiveState('failed')
      } finally {
        inFlight = false
        if (queued && !cancelled) { queued = false; void refresh() }
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
    refreshLiveRef.current = () => { generation += 1; void refresh(true) }
    retryLiveRef.current = async (jobId: string) => {
      await api.retryFailure(jobId, signal)
      if (cancelled) return false
      generation += 1
      void refresh(true)
      return true
    }
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
      refreshLiveRef.current = null
      retryLiveRef.current = null
      document.removeEventListener('visibilitychange', onVisibility)
      activityEvents.forEach((name) => window.removeEventListener(name, onActivity))
    }
  }, [api, sessionOpen, sessionEnded, view])

  function startSession(): AbortSignal {
    sessionAbortRef.current.abort()
    sessionAbortRef.current = new AbortController()
    busyTicketsRef.current = new Set()
    requestedRef.current = new Set()
    retriedSelectedRef.current = null
    savingTicketsRef.current = new Set()
    return sessionAbortRef.current.signal
  }
  // Clears the token and everything the last workspace left on screen or in flight.
  function resetWorkspace() {
    api.setToken('')
    setTickets([]); setSelectedTicketId(null); setPane('list')
    setEvaluation(null); setOperationsOverview(null); setFailures(null); setRuntime(null); setWorkspaceIndustryId(null)
    setBusyTicketIds(new Set()); setSavingTicketIds(new Set()); setTriageStages({}); setTicketErrors({}); setReviewEpochs({}); setQualityFocus(0); setError(null); setSessionEnded(false); setView('inbox'); setAnnouncement(''); setDialog(null); setRetryDemoStartedAt(null); retryDemoRef.current = { running: false, ticketId: null }
  }
  function closeDemo() { startSession(); resetWorkspace(); setBusy(false); setSessionOpen(false) }
  function startNewDemo() { restartFocusRef.current = true; closeDemo(); void openWorkspace() }
  // A 401 means the token expired or is unknown. It will not come back, so offer a new demo instead of a retry.
  function showActionError(cause: unknown, fallback: string, conflict?: string) {
    if (cause instanceof ApiError && cause.status === 401) { setSessionEnded(true); return }
    setError(plainError(cause, fallback, conflict))
  }
  function showTicketError(ticketId: string, cause: unknown, fallback: string, conflict?: string) {
    if (cause instanceof ApiError && cause.status === 401) { setSessionEnded(true); return }
    const text = plainError(cause, fallback, conflict)
    const plain = cause instanceof PlainError ? cause.message : null
    const error: TicketError = { text, hold: plain === TRIAGE_FAILED_TEXT || plain === RETRY_DEMO_STOPPED_TEXT }
    setTicketErrors((current) => ({ ...current, [ticketId]: error }))
  }
  function clearTicketError(ticketId: string) {
    setTicketErrors((current) => ticketId in current ? Object.fromEntries(Object.entries(current).filter(([id]) => id !== ticketId)) : current)
  }
  // The same sentence twice in a row still has to be heard: a changed text node is what a screen reader announces, so a
  // repeat gets a trailing no-break space (written as an escape so it is visible here).
  const NBSP = '\u00A0'
  const announce = (text: string) => setAnnouncement((current) => current === text ? text + NBSP : text)

  async function openWorkspace() {
    const signal = startSession()
    resetWorkspace(); setBusy(true)
    try {
      // One call opens a seeded workspace. Quality and operations loads itself when it is opened, so a new
      // workspace never shows the numbers of the one before it.
      const session = await api.createSession(industryId, true)
      if (signal.aborted) return
      api.setToken(session.token); setWorkspaceIndustryId(session.industry ?? industryId)
      let opened: Ticket[]
      if (session.tickets && session.runtime) {
        opened = session.tickets.map(mapApiTicket); setRuntime(session.runtime)
      } else {
        // An API from before the one-call open (for example during a deploy) returns a bare session: open it as before.
        await api.resetDemo(); const [page, currentEvaluation, currentFailures, currentRuntime] = await Promise.all([api.listTickets(), api.getEvaluation(), api.listFailures(), api.getRuntimeSummary()])
        if (signal.aborted) return
        opened = page.content.map(mapApiTicket); setEvaluation(currentEvaluation); setFailures(currentFailures); setRuntime(currentRuntime)
      }
      setTickets(opened)
      // A laptop opens the most urgent triaged ticket straight away. It already has a result, so nothing is sent; a
      // ticket without one waits for a click, because opening it runs the live AI.
      const top = reviewOrder(opened.filter((ticket) => ticket.scenarioKey !== RETRY_DEMO_SCENARIO))[0]
      setSelectedTicketId(!isPhone() && top?.result ? top.id : null)
      setView('inbox'); setSessionOpen(true)
    }
    catch (cause: unknown) { if (!signal.aborted) setError(plainError(cause, 'Could not open the demo workspace. Try again in a few seconds.')) } finally { if (!signal.aborted) setBusy(false) }
  }

  function setStage(ticketId: string, stage: TriageStage, slow = false) {
    setTriageStages((current) => current[ticketId]?.stage === stage && current[ticketId]?.slow === slow ? current : { ...current, [ticketId]: { stage, slow } })
  }
  // Reads a ticket's triage status and waits for its result. post: an explicit request (Run live AI, Re-run live, Try
  // again, or the first open of an untriaged ticket) that sends POST /triage first. Without it only GET /processing is
  // used, so it never spends AI quota. A recorded result is replaced only by a newer one.
  async function hydrateTriage(ticket: Ticket, { post = false } = {}) {
    // A review being saved belongs to the current result, so a re-run waits until it is saved.
    if (!ticket.backendId || busyTicketsRef.current.has(ticket.backendId) || savingTicketsRef.current.has(ticket.backendId)) return
    const ticketId = ticket.backendId
    const previousResultId = ticket.result?.id ?? null
    const signal = sessionAbortRef.current.signal
    const busyTickets = busyTicketsRef.current
    const isSelected = () => selectedRef.current === ticketId
    // Reading a ticket that already shows a result stays quiet (its result stays on screen) unless a job is still running.
    const quiet = !post && previousResultId !== null
    const firstText = post ? TRIAGE_STAGE_TEXT.SENDING : TRIAGE_STAGE_TEXT.CHECKING
    let visible = false
    const showBusy = () => {
      if (visible) return
      visible = true
      setBusyTicketIds(new Set(busyTickets)); setStage(ticketId, post ? 'SENDING' : 'CHECKING'); announce(firstText)
    }
    busyTickets.add(ticketId); clearTicketError(ticketId)
    if (!quiet) showBusy()
    let lastAnnounced: string = firstText
    const say = (text: string) => { if (text !== lastAnnounced && isSelected()) { lastAnnounced = text; announce(text) } }
    try {
      if (post) { requestedRef.current.add(ticketId); await api.requestTriage(ticketId, signal) }
      const startedAt = Date.now()
      while (!signal.aborted && Date.now() - startedAt < TRIAGE_WAIT_MS) {
        const processing = await api.getProcessing(ticketId, signal)
        if (signal.aborted) return
        const result = processing.result
        if (result && result.id !== previousResultId) {
          setTickets((current) => current.map((item) => item.backendId === ticketId ? { ...item, result: result as TriageResult, job: processing.job, review: null, reviewsLogged: 0 } : item))
          announce(isSelected() ? 'Triage done. Evidence and priority are ready.' : `Triage done: ${ticket.subject}.`)
          return
        }
        const job = processing.job
        const status = job?.status ?? 'QUEUED'
        // Reading a ticket that already has a result: nothing newer is queued, so the shown result is the newest one.
        if (!post && previousResultId && (!job || status === 'COMPLETED')) return
        if (status === 'TERMINAL_FAILURE') throw new PlainError(TRIAGE_FAILED_TEXT)
        if (status === 'RETRYABLE_FAILURE' && job?.lastErrorCode === 'SYNTHETIC_PROVIDER_TIMEOUT') throw new PlainError(RETRY_DEMO_STOPPED_TEXT)
        showBusy()
        // A real provider failure is retried by the queue on its own, so keep waiting and say so.
        const stage: TriageStage = status === 'COMPLETED' ? 'PROCESSING' : status
        const elapsed = Date.now() - startedAt
        const slow = elapsed > SLOW_TRIAGE_NOTICE_MS && stage === 'QUEUED'
        setStage(ticketId, stage, slow)
        say(slow ? WAKING_TEXT : TRIAGE_STAGE_TEXT[stage])
        await pause(triagePollDelay(elapsed), signal)
      }
      if (!signal.aborted) throw new PlainError(TRIAGE_STILL_RUNNING_TEXT)
    }
    catch (cause: unknown) { if (!signal.aborted) showTicketError(ticketId, cause, 'Could not triage this ticket. Try again in a few seconds.') }
    finally {
      // A closed workspace was already reset; touching state here would reach into the next one.
      if (!signal.aborted) {
        busyTickets.delete(ticketId); setBusyTicketIds(new Set(busyTickets))
        setTriageStages((current) => Object.fromEntries(Object.entries(current).filter(([id]) => id !== ticketId)))
      }
    }
  }
  function selectTicket(ticketId: string) {
    const ticket = inboxTickets.find((item) => item.id === ticketId)
    setSelectedTicketId(ticketId)
    const phone = isPhone()
    if (phone) {
      if (pane !== 'detail') window.history.pushState({ caselens: 'detail' }, '')
      setPane('detail')
    }
    // Focus follows the reviewer's choice to the ticket heading, so a screen reader reads it out. The laptop's own
    // auto-open on launch does not come through here, so it never takes focus.
    window.requestAnimationFrame(() => {
      if (phone) { try { window.scrollTo(0, 0) } catch { /* jsdom and old browsers */ } }
      document.getElementById('detail-subject')?.focus({ preventScroll: true })
    })
    // A POST /triage only comes from an explicit click or the first open of an untriaged ticket nobody has requested yet.
    // Every other open (after a timeout, an error, a 409, an Operations retry) only reads the status. A ticket whose
    // worker gave up shows the failure first; its Try again button runs it again.
    if (!ticket || ticketErrors[ticket.backendId]?.hold) return
    if (requestedRef.current.has(ticket.backendId)) void hydrateTriage(ticket)
    else if (!ticket.result) void hydrateTriage(ticket, { post: true })
  }
  function backToList() {
    const id = selectedTicketId
    if ((window.history.state as { caselens?: string } | null)?.caselens === 'detail') window.history.back()
    setPane('list')
    window.requestAnimationFrame(() => document.querySelector<HTMLElement>(`.row[data-id="${id}"]`)?.focus({ preventScroll: true }))
  }

  async function submitReview(ticket: Ticket, decision: 'ACCEPTED' | 'CORRECTED', urgency: Urgency | null, note: string): Promise<boolean> {
    const result = ticket.result
    // A ticket in a live run has no settled result to review yet.
    if (!result || savingTicketsRef.current.has(ticket.backendId) || busyTicketsRef.current.has(ticket.backendId)) return false
    const ticketId = ticket.backendId
    const signal = sessionAbortRef.current.signal
    const saving = savingTicketsRef.current
    saving.add(ticketId); setSavingTicketIds(new Set(saving)); clearTicketError(ticketId)
    try {
      // An older API has no decision field and records every entry as a correction, with a note.
      const legacy = typeof result.recorded !== 'boolean'
      await api.submitFeedback(ticketId, decision === 'ACCEPTED'
        ? { triageResultId: result.id, decision }
        : { triageResultId: result.id, ...(legacy ? {} : { decision }), urgency: urgency ?? undefined, ...(note ? { note } : {}) }, signal)
      if (signal.aborted) return false
      // The review belongs to the result it was made on; a newer result on the ticket starts unreviewed and hears nothing.
      setTickets((current) => applyReview(current, ticketId, result.id, { decision, urgency, note: note || null }))
      if (!stillShows(ticketId, result.id)) return false
      const by = deciderOf(result)
      announce(decision === 'ACCEPTED' ? `Accepted. The ${by.possessive} call is logged as confirmed.` : `Override logged. ${by.name} ${URGENCY_LABEL[result.urgency]}, you ${URGENCY_LABEL[urgency ?? result.urgency]}.`)
      // The review is saved; a failed metrics read must not say otherwise. Quality and operations reloads itself.
      api.getEvaluation(signal).then((next) => { if (!signal.aborted) setEvaluation(next) }).catch(() => undefined)
      return true
    }
    catch (cause: unknown) {
      if (!signal.aborted && stillShows(ticketId, result.id)) {
        showTicketError(ticketId, cause, 'Could not save the review. Try again in a few seconds.', CORRECTION_CONFLICT_TEXT)
        if (cause instanceof ApiError && cause.status === 409 && cause.code !== 'workspace-limit') {
          // The API refused because a newer result exists. Drop the open draft, read the newest result (GET only) and
          // wait for that before Save is available again. If the read fails, opening the ticket again reads it.
          requestedRef.current.add(ticketId)
          setReviewEpochs((current) => ({ ...current, [ticketId]: (current[ticketId] ?? 0) + 1 }))
          const shown = await refreshNewest(ticketId, result.id)
          if (!signal.aborted) {
            const text = shown ? CONFLICT_NEWEST_SHOWN_TEXT : CONFLICT_REOPEN_TEXT
            setTicketErrors((current) => current[ticketId]?.text === CORRECTION_CONFLICT_TEXT ? { ...current, [ticketId]: { ...current[ticketId], text } } : current)
          }
        }
      }
      return false
    }
    finally { if (!signal.aborted) { saving.delete(ticketId); setSavingTicketIds(new Set(saving)) } }
  }
  // Reads one ticket's status once (GET only, never a triage request) and shows a result newer than the one on screen.
  // Returns true when a newer result is now shown.
  async function refreshNewest(ticketId: string, shownResultId: string): Promise<boolean> {
    const signal = sessionAbortRef.current.signal
    try {
      const processing = await api.getProcessing(ticketId, signal)
      if (signal.aborted) return false
      const result = processing.result
      if (result && result.id !== shownResultId && stillShows(ticketId, shownResultId)) {
        setTickets((current) => current.map((item) => item.backendId === ticketId ? { ...item, result: result as TriageResult, job: processing.job, review: null, reviewsLogged: 0 } : item))
        return true
      }
    }
    catch { /* The caller tells the reviewer to open the ticket again. */ }
    return false
  }
  // The retry goes through the screen's own reads: its follow-up refresh failing never reports the retry as failed,
  // and leaving the screen drops it quietly.
  async function retryFailure() {
    const failure = failures?.content[0]
    const retry = retryLiveRef.current
    if (!failure || !retry) return
    const signal = sessionAbortRef.current.signal
    setBusy(true); setError(null)
    try {
      if (!(await retry(failure.id)) || signal.aborted) return
      clearTicketError(failure.ticketId)
      // The retried job runs on the server: opening its ticket only reads the status, and the Inbox reads it once more
      // if that ticket is still selected when the reviewer comes back.
      requestedRef.current.add(failure.ticketId)
      if (selectedRef.current === failure.ticketId) retriedSelectedRef.current = failure.ticketId
      setFailures((current) => current && { ...current, content: current.content.filter((item) => item.id !== failure.id), totalElements: Math.max(0, current.totalElements - 1) })
      announce('Retried. The job is back in the queue and there are no unresolved failures.')
      setQualityFocus((value) => value + 1)
    }
    catch (cause: unknown) { if (!signal.aborted && retryLiveRef.current === retry) showActionError(cause, 'Could not retry the failed case. Try again in a few seconds.') }
    finally { if (!signal.aborted) setBusy(false) }
  }
  // The retry demo creates its ticket and sends it to the worker, which records a synthetic provider timeout. The
  // failure then shows in Quality and operations, where the reviewer retries it. The page stays there.
  async function runRetryDemo() {
    const demo = retryDemoRef.current
    if (demo.running) return
    demo.running = true
    const signal = sessionAbortRef.current.signal
    setBusy(true); setError(null)
    try {
      // A ticket created by an earlier click whose triage request failed is sent again instead of creating another.
      let ticketId = demo.ticketId
      if (!ticketId) {
        const created = mapApiTicket(await api.createScenario(RETRY_DEMO_SCENARIO, signal))
        if (signal.aborted) return
        setTickets((current) => current.some((ticket) => ticket.backendId === created.backendId) ? current : [...current, created])
        ticketId = created.backendId
        demo.ticketId = ticketId
      }
      await api.requestTriage(ticketId, signal)
      if (signal.aborted) return
      demo.ticketId = null
      setRetryDemoStartedAt(Date.now())
      announce('Simulating a provider timeout. The failure shows here in a few seconds.')
      setQualityFocus((value) => value + 1)
      refreshLiveRef.current?.()
    }
    catch (cause: unknown) { if (!signal.aborted) showActionError(cause, 'Could not start the retry demo. Try again in a few seconds.') }
    finally { demo.running = false; if (!signal.aborted) setBusy(false) }
  }

  function navigate(next: NavView) {
    setView(next); setError(null); setQualityFocus(0)
    // After an Operations retry the selected ticket may show a stale result: read its status once (GET).
    const retried = retriedSelectedRef.current
    if (next === 'inbox' && retried) {
      retriedSelectedRef.current = null
      const ticket = ticketsRef.current.find((item) => item.backendId === retried)
      if (ticket && selectedRef.current === retried) void hydrateTriage(ticket)
    }
  }
  const activity = (ticket: Ticket): TicketActivity => ({ running: busyTicketIds.has(ticket.backendId), stage: triageStages[ticket.backendId]?.stage ?? null, failed: Boolean(ticketErrors[ticket.backendId]) })
  const hasRecorded = inboxTickets.some((ticket) => ticket.result?.recorded)
  // Pending from the click until the synthetic failure is listed (each 5-second refresh re-checks), or 90 seconds.
  const retryDemoListed = Boolean(failures?.content.some((failure) => failure.lastErrorCode === 'SYNTHETIC_PROVIDER_TIMEOUT'))
  const retryDemoPending = retryDemoStartedAt !== null && !retryDemoListed && Date.now() - retryDemoStartedAt < RETRY_DEMO_PENDING_MS
  const toReview = inboxTickets.filter((ticket) => ticket.result && !ticket.review && !busyTicketIds.has(ticket.backendId)).length

  const builtDialog = dialog === 'built' && <HowItsBuilt runtime={runtime} onClose={() => setDialog(null)} />
  if (!sessionOpen) return <>
    <Landing onOpen={openWorkspace} busy={busy} error={error} onShowBuilt={() => setDialog('built')} choice={{ industries: sessionOptions.industries, selected: industryId, onSelect: setIndustryId }} />
    {builtDialog}
  </>

  const navItem = (target: NavView) => (
    <button type="button" className="nav-item" aria-current={view === target ? 'page' : undefined} onClick={() => navigate(target)}>
      <NavIcon view={target} /><span>{VIEW_TITLE[target]}</span>
      {target === 'inbox' && toReview > 0 && <span className="nav-count">{toReview} to review</span>}
      {target === 'quality' && failures?.totalElements ? <span className="nav-count">{failures.totalElements}<span className="sr-only"> failed</span></span> : null}
    </button>
  )
  const banner = sessionEnded
    ? <div className="error-banner workspace-error session-ended" role="alert"><span>{SESSION_ENDED_BANNER_TEXT}</span> <button type="button" className="link-button" onClick={startNewDemo}>Start a new demo</button></div>
    : error && <p className="error-banner workspace-error" role="alert">{error}</p>
  const detailState = selectedTicket && {
    running: busyTicketIds.has(selectedTicket.backendId),
    stage: triageStages[selectedTicket.backendId]?.stage ?? null,
    slow: triageStages[selectedTicket.backendId]?.slow ?? false,
    error: ticketErrors[selectedTicket.backendId]?.text ?? null,
    saving: savingTicketIds.has(selectedTicket.backendId),
    reviewEpoch: reviewEpochs[selectedTicket.backendId] ?? 0,
  }
  return (
    <div className="app" data-pane={view === 'inbox' ? pane : 'list'} data-view={view}>
      <aside className="sidebar" aria-label="CaseLens">
        <Wordmark />
        <div className="scope"><span className="scope-label">Demo workspace</span><span className="scope-name">{industryName}</span></div>
        <nav className="nav" aria-label="Screens">{navItem('inbox')}{navItem('quality')}</nav>
        <div className="sidebar-foot">
          <button type="button" className="nav-item" onClick={() => setDialog('built')}><svg viewBox="0 0 16 16" aria-hidden="true" focusable="false"><path d="M3 3.5h10v3H3zM3 9.5h10v3H3z" fill="none" stroke="currentColor" strokeWidth="1.4" /></svg><span>How it's built</span></button>
          <button type="button" className="nav-item" onClick={closeDemo}><svg viewBox="0 0 16 16" aria-hidden="true" focusable="false"><path d="M10 3.5 5.5 8l4.5 4.5" fill="none" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round" strokeLinejoin="round" /></svg><span>Close demo</span></button>
          <Byline />
        </div>
      </aside>
      <header className="topbar">
        <button type="button" className="icon-button menu-button" aria-label="Open menu" aria-haspopup="dialog" onClick={() => setDialog('menu')}>
          <svg viewBox="0 0 16 16" aria-hidden="true" focusable="false"><path d="M2.5 4.5h11M2.5 8h11M2.5 11.5h11" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" /></svg>
        </button>
        <div className="topbar-mark"><Wordmark /></div>
        <p className="crumbs"><span>Demo workspace</span><span aria-hidden="true">/</span><span>{industryName}</span><span aria-hidden="true">/</span><strong>{VIEW_TITLE[view]}</strong></p>
      </header>
      <main className={view === 'inbox' ? 'sheet' : 'sheet sheet-single'}>
        {banner}
        {view === 'inbox' && <>
          <Queue tickets={inboxTickets} activity={activity} selectedId={selectedTicket?.id ?? null} onSelect={selectTicket} />
          {selectedTicket && detailState
            ? <TicketDetail key={selectedTicket.backendId} ticket={selectedTicket} state={detailState} industryName={industryName} hasRecorded={hasRecorded} headingLevel={pane === 'detail' && phoneLayout ? 'h1' : 'h2'}
              actions={{ onAccept: (ticket) => submitReview(ticket, 'ACCEPTED', null, ''), onOverride: (ticket, urgency, note) => submitReview(ticket, 'CORRECTED', urgency, note) }}
              onBack={backToList} onRerun={() => void hydrateTriage(selectedTicket, { post: true })} />
            : <section className="detail" aria-label="Ticket detail"><div className="detail-empty"><p>{inboxTickets.some((ticket) => ticket.result) ? 'Pick a ticket from the inbox.' : 'Pick a ticket to run the live AI on it. Triage takes a few seconds.'}</p></div></section>}
          <p className="sr-only" role="status" aria-live="polite">{announcement}</p>
        </>}
        {view === 'quality' && <QualityOps evaluation={evaluation} overview={operationsOverview} failures={failures}
          live={<LiveStatus state={sessionEnded ? 'ended' : liveState} lastUpdated={lastUpdated} onRefresh={() => { resumeLiveRef.current?.(); setQualityFocus((value) => value + 1) }} />}
          loading={!sessionEnded && liveState === 'loading'} focusToken={qualityFocus} announcement={announcement}
          recovery={{ busy, pending: retryDemoPending }} onSimulate={() => void runRetryDemo()} onRetry={() => void retryFailure()} />}
      </main>
      {dialog === 'menu' && <PhoneMenu industryName={industryName} view={view} onNavigate={navigate} onShowBuilt={() => setDialog('built')} onCloseDemo={closeDemo} onClose={() => setDialog(null)} />}
      {builtDialog}
    </div>
  )
}

export default App
