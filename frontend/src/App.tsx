import { useEffect, useMemo, useState, type FormEvent } from 'react'
import { createCaseLensApi, type ApiTicket, type EvaluationSummary, type FailurePage, type OperationsOverview, type RuntimeSummary, type TriageProcessing, type TriageResult } from './api'

type View = 'inbox' | 'evaluation' | 'operations'
type Urgency = 'low' | 'medium' | 'high' | 'critical'

type TimelineEvent = { label: string; time: string; kind: 'received' | 'decision' | 'review' }
type Ticket = {
  id: string; backendId: string; subject: string; message: string; category: string; urgency: Urgency | null; priority: number | null; reliabilitySignal: string | null; slaRisk: string; source: string; summary: string; rationale: string; evidence: string[]; actions: string[]; suggestedReply: string; policy: string; timeline: TimelineEvent[]; triageResultId: string | null; decisionSource: string | null; triageStatus: string; scenarioKey: string | null; latencyMs: number | null; jobId: string | null; eventId: string | null; attemptCount: number; attempts: TriageProcessing['job']['attempts']; provider: string | null; modelVersion: string | null
}

function formatUrgency(urgency: Urgency | null) { return urgency ? urgency.charAt(0).toUpperCase() + urgency.slice(1) : 'Pending' }

const runtimeLabels = {
  environment: { LOCAL_REVIEW: 'Local review', AWS_DEMO: 'AWS demo' },
  database: { POSTGRESQL: 'PostgreSQL', NEON_POSTGRESQL: 'Neon PostgreSQL' },
  queue: { LOCALSTACK_SQS: 'LocalStack SQS', AMAZON_SQS: 'Amazon SQS' },
  aiProvider: { GROQ: 'Groq', GEMINI: 'Gemini', DETERMINISTIC: 'Deterministic' },
} as const

function mapApiTicket(ticket: ApiTicket): Ticket {
  return { id: ticket.displayId, backendId: ticket.id, subject: ticket.subject, message: ticket.message, category: 'Awaiting triage', urgency: null, priority: null, reliabilitySignal: null, slaRisk: 'Pending', source: ticket.channel, summary: ticket.message, rationale: 'Run the real triage worker to see the evidence-backed explanation.', evidence: [], actions: [], suggestedReply: '', policy: 'No policy decision has been recorded yet.', timeline: [{ label: 'Ticket received', time: new Date(ticket.createdAt).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }), kind: 'received' }], triageResultId: null, decisionSource: null, triageStatus: ticket.status, scenarioKey: ticket.scenarioKey, latencyMs: null, jobId: null, eventId: null, attemptCount: 0, attempts: [], provider: null, modelVersion: null }
}

function applyApiTriage(ticket: Ticket, result: TriageResult, job: TriageProcessing['job'], provider: string): Ticket {
  const urgency = result.urgency.toLowerCase() as Urgency
  const startedAt = job.startedAt ? Date.parse(job.startedAt) : Number.NaN
  const completedAt = job.completedAt ? Date.parse(job.completedAt) : Number.NaN
  const latencyMs = Number.isFinite(startedAt) && Number.isFinite(completedAt) ? Math.max(0, completedAt - startedAt) : null
  return { ...ticket, category: result.category, urgency, priority: result.priorityScore, reliabilitySignal: result.reliabilitySignal, slaRisk: result.slaRisk, summary: result.summary, rationale: result.explanation, evidence: result.evidence.map((item) => typeof item === 'string' ? item : `“${item.quote}” — ${item.meaning}`), actions: result.recommendedActions, suggestedReply: result.suggestedReply, policy: result.policyIds.length ? `Policy IDs: ${result.policyIds.join(', ')}` : 'No policy IDs returned.', triageResultId: result.id, decisionSource: result.decisionSource, triageStatus: 'COMPLETED', latencyMs, jobId: job.id, eventId: job.eventId, attemptCount: job.attemptCount, attempts: job.attempts, provider, modelVersion: result.modelVersion, timeline: [...ticket.timeline.filter((event) => event.kind === 'received'), { label: `Triage decision: ${result.category.toLowerCase().replaceAll('_', ' ')}`, time: new Date(result.createdAt).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }), kind: 'decision' }, { label: 'Human review pending', time: 'now', kind: 'review' }] }
}

function Landing({ onOpen, busy, error }: { onOpen: (passcode: string) => void; busy: boolean; error: string | null }) {
  const [passcode, setPasscode] = useState('')
  function submit(event: FormEvent<HTMLFormElement>) { event.preventDefault(); if (passcode.trim() && !busy) onOpen(passcode.trim()) }
  return (
    <main className="landing-shell">
      <div className="landing-grid" aria-hidden="true" />
      <section className="landing-panel" aria-labelledby="caselens-title">
        <div className="brand-lockup"><span className="brand-mark">CL</span><span>CaseLens / reviewer console</span></div>
        <p className="environment">Demo environment</p>
        <p className="environment-note">synthetic data · reviewer console</p>
        <h1 id="caselens-title">CaseLens</h1>
        <p className="landing-claim">Turn a messy case into a defensible next step.</p>
        <p className="landing-summary">CaseLens makes support-ticket triage explainable: see the signal, inspect the evidence, and keep a human in control of the correction.</p>
        <form className="access-card" onSubmit={submit}>
          <div><p className="eyebrow">Shared review space</p><h2>Open the working demo</h2><p className="muted">The passcode opens an isolated workspace backed by the local API. No customer or production systems are connected.</p></div>
          <label htmlFor="demo-passcode">Shared demo passcode</label>
          <div className="access-row"><input id="demo-passcode" value={passcode} onChange={(event) => setPasscode(event.target.value)} placeholder="reviewer" autoComplete="off" /><button type="submit" disabled={!passcode.trim() || busy}>{busy ? 'Opening workspace…' : 'Open reviewer workspace'}</button></div>
          {error && <p className="error-banner" role="alert">{error}</p>}
        </form>
        <div className="honesty-row"><span className="status-dot" /><span>Local API mode</span><span className="divider" /><span>Reserved fixtures only</span></div>
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
  const [feedbackSaved, setFeedbackSaved] = useState(false)
  const [evaluation, setEvaluation] = useState<EvaluationSummary | null>(null)
  const [operationsOverview, setOperationsOverview] = useState<OperationsOverview | null>(null)
  const [runtime, setRuntime] = useState<RuntimeSummary | null>(null)
  const [failures, setFailures] = useState<FailurePage | null>(null)
  const [busyTicketId, setBusyTicketId] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const selectedTicket = useMemo(() => tickets.find((ticket) => ticket.id === selectedTicketId) ?? null, [selectedTicketId, tickets])

  useEffect(() => { if (selectedTicketId) window.requestAnimationFrame(() => document.querySelector<HTMLButtonElement>('.detail-column .back-button')?.focus()) }, [selectedTicketId])
  useEffect(() => {
    if (!sessionOpen || (view !== 'evaluation' && view !== 'operations')) return
    const refresh = async () => {
      try {
        if (view === 'evaluation') { setEvaluation(await api.getEvaluation()); setError(null) }
        else {
          const [overview, failurePage] = await Promise.all([api.getOperationsOverview(), api.listFailures()])
          setOperationsOverview(overview)
          setFailures(failurePage)
          setError(null)
        }
      } catch (cause: unknown) {
        setError(cause instanceof Error ? cause.message : 'Could not refresh the workspace.')
      }
    }
    void refresh()
    const interval = window.setInterval(() => void refresh(), 5000)
    return () => window.clearInterval(interval)
  }, [api, sessionOpen, view])

  async function openWorkspace(passcode: string) {
    setBusy(true); setError(null)
    try { const session = await api.createSession(passcode); api.setToken(session.token); await api.resetDemo(); const [page, currentEvaluation, currentFailures, currentRuntime] = await Promise.all([api.listTickets(), api.getEvaluation(), api.listFailures(), api.getRuntimeSummary()]); setTickets(page.content.map(mapApiTicket)); setEvaluation(currentEvaluation); setFailures(currentFailures); setRuntime(currentRuntime); setSelectedTicketId(null); setView('inbox'); setSessionOpen(true) }
    catch (cause: unknown) { setError(cause instanceof Error ? cause.message : 'Could not open the demo workspace.') } finally { setBusy(false) }
  }

  async function hydrateTriage(ticket: Ticket) {
    if (!ticket.backendId || ticket.triageResultId || busyTicketId === ticket.backendId) return
    setBusyTicketId(ticket.backendId); setError(null)
    try { await api.requestTriage(ticket.backendId); for (let attempt = 0; attempt < 40; attempt += 1) { const processing = await api.getProcessing(ticket.backendId); if (processing.result) { const provider = runtime ? runtimeLabels.aiProvider[runtime.aiProvider] : 'Configured provider'; setTickets((current) => current.map((item) => item.backendId === ticket.backendId ? applyApiTriage(item, processing.result as TriageResult, processing.job, provider) : item)); return } if (processing.job.status === 'RETRYABLE_FAILURE' || processing.job.status === 'TERMINAL_FAILURE') throw new Error(`Triage stopped with ${processing.job.status.toLowerCase().replaceAll('_', ' ')}.`); await new Promise((resolve) => window.setTimeout(resolve, 250)) } throw new Error('Triage did not complete within the local review window.') }
    catch (cause: unknown) { setError(cause instanceof Error ? cause.message : 'Could not triage this ticket.') } finally { setBusyTicketId(null) }
  }
  function selectTicket(ticketId: string) { setSelectedTicketId(ticketId); setDraftUrgency(null); setFeedbackNote(''); setFeedbackSaved(false); const ticket = tickets.find((item) => item.id === ticketId); if (ticket) void hydrateTriage(ticket) }
  async function loadScenario() {
    setBusy(true); setError(null)
    try { const scenario = mapApiTicket(await api.createScenario('charger-offline-site-wide')); setTickets((current) => current.some((ticket) => ticket.backendId === scenario.backendId) ? current : [...current, scenario]); setSelectedTicketId(scenario.id); setDraftUrgency(null); setFeedbackNote(''); setFeedbackSaved(false); setView('inbox'); await hydrateTriage(scenario) }
    catch (cause: unknown) { setError(cause instanceof Error ? cause.message : 'Could not load the scenario.') } finally { setBusy(false) }
  }
  async function saveFeedback() {
    if (!selectedTicket?.backendId || !selectedTicket.triageResultId || !draftUrgency || draftUrgency === selectedTicket.urgency || !feedbackNote.trim()) return
    setBusy(true); setError(null)
    try { await api.submitFeedback(selectedTicket.backendId, { triageResultId: selectedTicket.triageResultId, category: selectedTicket.category, urgency: draftUrgency.toUpperCase(), slaRisk: selectedTicket.slaRisk.toUpperCase(), note: feedbackNote.trim() }); setTickets((current) => current.map((ticket) => ticket.id === selectedTicket.id ? { ...ticket, urgency: draftUrgency } : ticket)); setFeedbackSaved(true); setEvaluation(await api.getEvaluation()) }
    catch (cause: unknown) { setError(cause instanceof Error ? cause.message : 'Could not save reviewer feedback.') } finally { setBusy(false) }
  }
  async function retryFailure() { const failure = failures?.content[0]; if (!failure) return; setBusy(true); setError(null); try { await api.retryFailure(failure.id); const [overview, failurePage] = await Promise.all([api.getOperationsOverview(), api.listFailures()]); setOperationsOverview(overview); setFailures(failurePage) } catch (cause: unknown) { setError(cause instanceof Error ? cause.message : 'Could not retry the failed case.') } finally { setBusy(false) } }

  if (!sessionOpen) return <Landing onOpen={openWorkspace} busy={busy} error={error} />
  return <div className="app-shell"><aside className="app-rail"><div className="rail-brand"><span className="brand-mark">CL</span><span>CaseLens</span></div><div className="rail-workspace"><span className="eyebrow">Workspace</span><strong>Isolated demo workspace</strong><span className="workspace-id">token-scoped / 24 hours</span></div><nav className="primary-nav" aria-label="Primary navigation"><button aria-label="Inbox" className={view === 'inbox' ? 'nav-item active' : 'nav-item'} onClick={() => setView('inbox')} aria-current={view === 'inbox' ? 'page' : undefined}><span className="nav-icon">↗</span><span>Inbox</span><span className="nav-count">{tickets.length}</span></button><button aria-label="Evaluation" className={view === 'evaluation' ? 'nav-item active' : 'nav-item'} onClick={() => setView('evaluation')} aria-current={view === 'evaluation' ? 'page' : undefined}><span className="nav-icon">◌</span><span>Evaluation</span></button><button aria-label="Operations" className={view === 'operations' ? 'nav-item active' : 'nav-item'} onClick={() => setView('operations')} aria-current={view === 'operations' ? 'page' : undefined}><span className="nav-icon">⌁</span><span>Operations</span><span className="nav-status">{failures?.totalElements ? failures.totalElements : 'OK'}</span></button></nav><div className="rail-footer"><div className="preview-label"><span className="status-dot" />{runtime ? runtimeLabels.environment[runtime.environment] : 'Runtime unavailable'}</div>{runtime && <div className="runtime-stack" aria-label="Runtime services"><span>{runtimeLabels.database[runtime.database]}</span><span>{runtimeLabels.queue[runtime.queue]}</span><span>{runtimeLabels.aiProvider[runtime.aiProvider]}</span></div>}<p>All records are synthetic and scoped to this temporary workspace.</p><button className="text-button" onClick={() => setSessionOpen(false)}>Close demo</button></div></aside><main className="workspace-main"><header className="topbar"><div className="breadcrumbs"><span>CaseLens</span><span className="breadcrumb-slash">/</span><strong>{view === 'inbox' ? 'Review queue' : view === 'evaluation' ? 'Evaluation signal' : 'Operations'}</strong></div><div className="topbar-actions"><span className="live-chip"><span className="status-dot" />{runtime ? `${runtimeLabels.aiProvider[runtime.aiProvider]} · ${runtimeLabels.database[runtime.database]}` : 'Backend data'}</span><span className="avatar">HV</span></div></header>{error && <p className="error-banner workspace-error" role="alert">{error}</p>}{view === 'inbox' && <InboxView tickets={tickets} selectedTicket={selectedTicket} draftUrgency={draftUrgency} feedbackNote={feedbackNote} feedbackSaved={feedbackSaved} busy={busy || Boolean(busyTicketId)} onLoadScenario={loadScenario} onSelectTicket={selectTicket} onBack={() => setSelectedTicketId(null)} onDraftUrgency={setDraftUrgency} onFeedbackNote={setFeedbackNote} onSaveFeedback={saveFeedback} />}{view === 'evaluation' && <EvaluationView evaluation={evaluation} />}{view === 'operations' && <OperationsView overview={operationsOverview} failures={failures} busy={busy} onRetry={retryFailure} />}</main></div>
}

type InboxViewProps = { tickets: Ticket[]; selectedTicket: Ticket | null; draftUrgency: Urgency | null; feedbackNote: string; feedbackSaved: boolean; busy: boolean; onLoadScenario: () => void; onSelectTicket: (ticketId: string) => void; onBack: () => void; onDraftUrgency: (urgency: Urgency) => void; onFeedbackNote: (note: string) => void; onSaveFeedback: () => void }
function InboxView({ tickets, selectedTicket, draftUrgency, feedbackNote, feedbackSaved, busy, onLoadScenario, onSelectTicket, onBack, onDraftUrgency, onFeedbackNote, onSaveFeedback }: InboxViewProps) {
  return <div className="page-content"><div className="page-heading"><div><p className="eyebrow">Human review queue</p><h1>Ticket inbox</h1><p className="page-intro">Prioritized cases with the evidence behind each recommendation.</p></div><div className="heading-meta"><span className="metric-label">Queue health</span><strong><span className="status-dot" />{busy ? 'Processing' : 'Stable'}</strong><span className="metric-detail">{tickets.length} synthetic cases</span></div></div><div className={selectedTicket ? 'inbox-layout with-detail' : 'inbox-layout'}><section className="queue-column" aria-labelledby="queue-heading"><div className="section-bar"><div><h2 id="queue-heading">Needs review</h2><span className="muted">Sorted by priority and SLA risk</span></div><span className="count-pill">{tickets.length}</span></div><div className="scenario-card"><div><span className="scenario-kicker">Try a scenario</span><strong>Site-wide availability signal</strong><span className="muted">Create a real synthetic ticket and send it through the local worker.</span></div><button className="secondary-button" onClick={() => void onLoadScenario()} disabled={busy}>Load charger-offline-site-wide</button></div><div className="ticket-list">{tickets.map((ticket) => <TicketRow key={`${ticket.id}-${ticket.backendId ?? 'fixture'}`} ticket={ticket} selected={ticket.id === selectedTicket?.id} onSelect={onSelectTicket} />)}</div></section>{selectedTicket && <TicketDetail ticket={selectedTicket} draftUrgency={draftUrgency} feedbackNote={feedbackNote} feedbackSaved={feedbackSaved} busy={busy} onBack={onBack} onDraftUrgency={onDraftUrgency} onFeedbackNote={onFeedbackNote} onSaveFeedback={onSaveFeedback} />}</div></div>
}

function TicketRow({ ticket, selected, onSelect }: { ticket: Ticket; selected: boolean; onSelect: (ticketId: string) => void }) {
  return <article className={selected ? 'ticket-row selected' : 'ticket-row'}><div className="ticket-row-top"><span className="ticket-id">{ticket.id}</span><span className={ticket.urgency ? `urgency urgency-${ticket.urgency}` : 'urgency urgency-pending'}>{formatUrgency(ticket.urgency)}</span></div><h3>{ticket.subject}</h3><p>{ticket.summary}</p><div className="ticket-row-bottom"><span>{ticket.category}</span><span className="sla-indicator"><span className="status-dot" />{ticket.slaRisk}</span></div><button className="review-link" onClick={() => onSelect(ticket.id)}>Review {ticket.id} <span>→</span></button></article>
}

function TicketDetail({ ticket, draftUrgency, feedbackNote, feedbackSaved, busy, onBack, onDraftUrgency, onFeedbackNote, onSaveFeedback }: { ticket: Ticket; draftUrgency: Urgency | null; feedbackNote: string; feedbackSaved: boolean; busy: boolean; onBack: () => void; onDraftUrgency: (urgency: Urgency) => void; onFeedbackNote: (note: string) => void; onSaveFeedback: () => void }) {
  const displayedUrgency = draftUrgency ?? ticket.urgency
  return (
    <section className="detail-column" aria-labelledby="detail-heading">
      <button className="back-button" onClick={onBack}>← Back to queue</button>
      <div className="detail-heading"><div><span className="ticket-id">{ticket.id}</span><h2 id="detail-heading">Why this ticket is here</h2></div><span className={`urgency urgency-${displayedUrgency}`}>{formatUrgency(displayedUrgency)} urgency</span></div>
      <div className="detail-subject"><h3>{ticket.subject}</h3><p>{ticket.message}</p></div>
      <div className="explain-card"><div className="explain-header"><div><span className="eyebrow">Triage explanation</span><h3>{ticket.category}</h3></div><div className="confidence"><strong>{ticket.reliabilitySignal ?? 'Pending'}</strong><span>reliability</span></div></div><p className="lead-copy">{busy && !ticket.triageResultId ? 'The local worker is processing this ticket…' : ticket.rationale}</p><div className="evidence-list"><span className="eyebrow">Evidence used</span>{ticket.evidence.length ? ticket.evidence.map((item) => <div className="evidence-item" key={item}><span className="evidence-mark">+</span><span>{item}</span></div>) : <p className="muted">Evidence will appear after triage completes.</p>}</div></div>
      <div className="detail-grid"><section className="detail-card"><span className="eyebrow">Recommended next steps</span><ol className="action-list">{ticket.actions.map((action) => <li key={action}>{action}</li>)}</ol></section><section className="detail-card"><span className="eyebrow">Guardrail</span><p className="guardrail-copy">{ticket.policy}</p><span className="source-line">Source: {ticket.source}</span></section></div>
      {ticket.jobId && <section className="processing-card"><div className="section-bar"><div><span className="eyebrow">Persisted backend evidence</span><h3>Durable processing trace</h3></div><span className="activity-chip">{ticket.attemptCount} attempt{ticket.attemptCount === 1 ? '' : 's'}</span></div><dl className="processing-identifiers"><div><dt>Event</dt><dd><code title={ticket.eventId ?? undefined}>{ticket.eventId}</code></dd></div><div><dt>Job</dt><dd><code title={ticket.jobId}>{ticket.jobId}</code></dd></div><div><dt>Model</dt><dd>{ticket.modelVersion}</dd></div><div><dt>Decision</dt><dd>{ticket.decisionSource}</dd></div></dl><ol className="processing-stages"><li><span>01</span><div><strong>Outbox committed</strong><small>Ticket and triage event entered durable storage together.</small></div></li><li><span>02</span><div><strong>Queue accepted</strong><small>Worker job references the persisted outbox event.</small></div></li><li><span>03</span><div><strong>Worker completed</strong><small>{ticket.attempts.length ? `${ticket.attempts.length} persisted attempt record${ticket.attempts.length === 1 ? '' : 's'}.` : 'Completion recorded on the job.'}</small></div></li><li><span>04</span><div><strong>{ticket.decisionSource === 'AI_VALIDATED' ? `${ticket.provider} validated` : 'Rules fallback selected'}</strong><small>{ticket.decisionSource === 'AI_VALIDATED' ? 'Structured output passed evidence and policy validation.' : 'Deterministic rules produced the safe result.'}</small></div></li></ol></section>}
      <section className="timeline-card"><div className="section-bar"><div><span className="eyebrow">Decision trace</span><h3>What happened</h3></div><span className="latency">{ticket.latencyMs === null ? '—' : `${ticket.latencyMs} ms`}</span></div><div className="timeline">{ticket.timeline.map((event) => <div className="timeline-event" key={`${event.label}-${event.time}`}><span className={`timeline-marker ${event.kind}`} /><div><strong>{event.label}</strong><span>{event.time}</span></div></div>)}</div></section>
      <section className="feedback-card"><div><span className="eyebrow">Human feedback</span><h3>Would you change the urgency?</h3><p className="muted">Corrections are written to the backend workspace and kept beside the original result.</p></div><div className="feedback-actions"><div className="urgency-options" role="group" aria-label="Urgency correction"><button disabled={!ticket.triageResultId || busy} className={displayedUrgency === 'low' ? 'selected' : ''} onClick={() => onDraftUrgency('low')}>Mark urgency low</button><button disabled={!ticket.triageResultId || busy} className={displayedUrgency === 'medium' ? 'selected' : ''} onClick={() => onDraftUrgency('medium')}>Mark urgency medium</button><button disabled={!ticket.triageResultId || busy} className={displayedUrgency === 'high' ? 'selected' : ''} onClick={() => onDraftUrgency('high')}>Mark urgency high</button><button disabled={!ticket.triageResultId || busy} className={displayedUrgency === 'critical' ? 'selected' : ''} onClick={() => onDraftUrgency('critical')}>Mark urgency critical</button></div><label htmlFor="feedback-note">Review note</label><textarea id="feedback-note" value={feedbackNote} onChange={(event) => onFeedbackNote(event.target.value)} placeholder="Explain the correction" rows={3} disabled={!ticket.triageResultId || busy} /><button className="primary-button" onClick={onSaveFeedback} disabled={!ticket.triageResultId || !draftUrgency || draftUrgency === ticket.urgency || !feedbackNote.trim() || busy}>Save correction</button></div>{feedbackSaved && <p className="success-banner" role="status">Correction recorded in backend</p>}</section>
    </section>
  )
}

function formatEventTime(value: string) {
  return new Date(value).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })
}

function EvaluationView({ evaluation }: { evaluation: EvaluationSummary | null }) {
  if (!evaluation) return <div className="page-content narrow-page"><div className="page-heading"><div><p className="eyebrow">Measured quality</p><h1>Evaluation signal</h1><p className="page-intro">Loading persisted evaluation data from this workspace…</p></div></div></div>
  const percent = (value: number | undefined) => value === undefined ? '—' : `${Math.round(value * 100)}%`
  const events = evaluation?.recentEvents ?? []
  const providers = evaluation?.providerUsage ?? []
  return <div className="page-content narrow-page"><div className="page-heading"><div><p className="eyebrow">Measured quality</p><h1>Evaluation signal</h1><p className="page-intro">Persisted results, reviewer corrections, and provider activity from this workspace.</p></div><span className="date-stamp">{evaluation ? new Date(evaluation.generatedAt).toLocaleDateString() : 'Loading…'}</span></div><section className="signal-hero"><div><span className="eyebrow">Current signal</span><h2>Reviewers can see where model output earns trust.</h2><p>Evaluation keeps model usefulness separate from human authority. Empty values mean this workspace has not processed a case yet.</p></div><div className="signal-score"><strong>{percent(evaluation?.agreementRate)}</strong><span>agreement</span></div></section><div className="metric-grid"><MetricCard label="Agreement" value={percent(evaluation?.agreementRate)} detail={`${evaluation?.evaluatedResults ?? 0} evaluated cases`} tone="teal" /><MetricCard label="Correction rate" value={percent(evaluation?.correctionRate)} detail={`${evaluation?.triageResults ?? 0} persisted results`} tone="coral" /><MetricCard label="p50 latency" value={evaluation ? `${evaluation.medianLatencyMs} ms` : '—'} detail={evaluation ? `p95 ${evaluation.p95LatencyMs} ms` : 'Waiting for data'} tone="ink" /><MetricCard label="Failure rate" value={percent(evaluation?.failureRate)} detail="From recorded triage jobs" tone={evaluation?.failureRate ? 'coral' : 'teal'} /></div><section className="provider-card"><div><span className="eyebrow">Provider usage</span><h2>{providers.length ? `${providers.length} active provider${providers.length === 1 ? '' : 's'}` : 'No provider calls yet'}</h2><p className="muted">Usage is read from model-invocation records without exposing credentials or raw ticket content.</p></div><div className="provider-summary">{providers.length ? providers.map((provider) => <div className="provider-row" key={provider.provider}><span>{provider.provider} · {provider.requests} requests / {provider.successes} successes</span><strong>{provider.inputTokens + provider.outputTokens} tokens</strong><span className="bar"><span style={{ width: provider.requests ? `${Math.round((provider.successes / provider.requests) * 100)}%` : '0%' }} /></span></div>) : <span className="muted">No persisted provider activity yet.</span>}</div></section><section className="activity-card"><span className="eyebrow">Persisted event stream</span><h2>Recent triage activity</h2><p className="muted">A compact view of the decisions and reviewer signals that feed the evaluation summary.</p>{events.length ? <div className="activity-list">{events.map((event) => <div className="activity-row" key={event.resultId}><div className="activity-main"><div className="activity-title"><strong>{event.decisionSource}</strong><span className={event.corrected ? 'activity-chip corrected' : 'activity-chip'}>{event.corrected ? 'Corrected' : event.evaluated ? 'Evaluated' : 'Not evaluated'}</span></div><span className="activity-meta"><span>{event.provider ?? 'Rules fallback'}</span> · <span>{event.modelVersion ?? 'deterministic rules'}</span> · <span>{formatEventTime(event.createdAt)}</span></span></div><span className="activity-latency">{`${event.latencyMs} ms`}</span></div>)}</div> : <p className="empty-state">No evaluations yet. Process a synthetic ticket from Inbox to populate this activity stream.</p>}</section></div>
}

function MetricCard({ label, value, detail, tone }: { label: string; value: string; detail: string; tone: string }) { return <article className={`metric-card tone-${tone}`}><span className="metric-label">{label}</span><strong>{value}</strong><span className="metric-detail">{detail}</span></article> }

function OpsMetric({ label, value, detail, tone }: { label: string; value: string; detail: string; tone: string }) { return <article className={`ops-card tone-${tone}`}><span className="eyebrow">{label}</span><strong className="ops-value">{value}</strong><p>{detail}</p></article> }

function OperationsView({ overview, failures, busy, onRetry }: { overview: OperationsOverview | null; failures: FailurePage | null; busy: boolean; onRetry: () => void }) {
  if (!overview) return <div className="page-content narrow-page"><div className="page-heading"><div><p className="eyebrow">Reliability view</p><h1>Operations</h1><p className="page-intro">Loading persisted queue and provider telemetry…</p></div></div></div>
  const count = failures?.totalElements ?? 0
  const queue = overview?.queue
  const operations = overview.recent ?? []
  const providers = overview?.providers ?? []
  const providerDegraded = Boolean(overview && (overview.providerFailureCount > 0 || overview.fallbackCount > 0))
  return <div className="page-content narrow-page"><div className="page-heading"><div><p className="eyebrow">Reliability view</p><h1>Operations</h1><p className="page-intro">Live queue state, provider signals, and recoverable work from the local backend.</p></div><span className="live-chip"><span className="status-dot" />{count ? 'Attention' : providerDegraded ? 'Degraded' : 'Healthy'}</span></div><section className={count ? 'operations-hero failure-state' : providerDegraded ? 'operations-hero provider-state' : 'operations-hero'}><div className="checkmark">{count ? '!' : providerDegraded ? '!' : '✓'}</div><div><h2>{count ? `${count} unresolved queue failure${count === 1 ? '' : 's'}` : providerDegraded ? 'Provider degraded, queue completing with guardrails' : 'No unresolved failures'}</h2><p>{count ? `Job ${failures?.content[0]?.id ?? 'unknown'} remains visible until a reviewer retries it.` : providerDegraded ? 'Provider failures and fallback decisions are visible without exposing ticket content.' : 'The local queue has no recorded retryable or terminal failures.'}</p></div>{count ? <button className="primary-button" onClick={() => void onRetry()} disabled={busy}>Retry failed case</button> : <span className="muted">Observed from backend</span>}</section><div className="operations-grid"><OpsMetric label="Queued jobs" value={String(queue?.queued ?? 0)} detail="Waiting for worker capacity." tone="teal" /><OpsMetric label="Processing jobs" value={String(queue?.processing ?? 0)} detail="Currently being handled." tone="ink" /><OpsMetric label="Completed jobs" value={String(queue?.completed ?? 0)} detail="Persisted successful jobs." tone="teal" /><OpsMetric label="Queue failures" value={String((queue?.retryableFailures ?? 0) + (queue?.terminalFailures ?? 0))} detail={`${queue?.retryableFailures ?? 0} retryable · ${queue?.terminalFailures ?? 0} terminal`} tone={count ? 'coral' : 'ink'} /><OpsMetric label="Fallback results" value={String(overview?.fallbackCount ?? 0)} detail="Guardrailed rules decisions." tone={overview?.fallbackCount ? 'coral' : 'ink'} /><OpsMetric label="Provider failures" value={String(overview?.providerFailureCount ?? 0)} detail="Recorded invocation failures." tone={overview?.providerFailureCount ? 'coral' : 'ink'} /></div><section className="activity-card"><span className="eyebrow">Persisted processing</span><h2>Recent processing</h2><p className="muted">Recent job state is derived from the workspace database and capped for safe review.</p>{operations.length ? <div className="activity-list">{operations.map((operation) => <div className="activity-row" key={operation.jobId}><div className="activity-main"><div className="activity-title"><strong>{operation.status.replaceAll('_', ' ')}</strong><span className={operation.lastErrorCode ? 'activity-chip corrected' : 'activity-chip'}>{operation.lastErrorCode ?? `${operation.attemptCount} attempt${operation.attemptCount === 1 ? '' : 's'}`}</span></div><span className="activity-meta">{operation.provider ?? 'Rules fallback'} · {operation.modelVersion ?? 'deterministic rules'} · updated {formatEventTime(operation.updatedAt)}</span></div><span className="activity-latency">{operation.latencyMs === null ? '—' : `${operation.latencyMs} ms`}</span></div>)}</div> : <p className="empty-state">No persisted processing activity yet. Run a synthetic ticket from Inbox to see queue and provider state here.</p>}</section><section className="provider-card"><div><span className="eyebrow">Provider health</span><h2>{providers.length ? 'Invocation telemetry' : 'No provider calls yet'}</h2><p className="muted">Counts and latency only; prompts, responses, and credentials stay out of the operations surface.</p></div><div className="provider-summary">{providers.length ? providers.map((provider) => <div className="provider-row" key={provider.provider}><span>{provider.provider} · {provider.requests} requests / {provider.successes} successes / {provider.failures} failures</span><strong>{provider.averageLatencyMs === null ? '—' : `${provider.averageLatencyMs} ms avg`}</strong><span className="bar"><span style={{ width: provider.requests ? `${Math.round((provider.successes / provider.requests) * 100)}%` : '0%' }} /></span></div>) : <span className="muted">No persisted provider activity yet.</span>}</div></section><section className="runbook-card"><span className="eyebrow">Failure handling</span><h2>Every failure has a next state.</h2><div className="runbook-steps"><div><span>01</span><strong>Retryable</strong><p>Keep the case queued and visible.</p></div><div><span>02</span><strong>Terminal</strong><p>Record the failure and surface it.</p></div><div><span>03</span><strong>Human review</strong><p>Make the decision safe to explain.</p></div></div></section></div>
}

export default App
