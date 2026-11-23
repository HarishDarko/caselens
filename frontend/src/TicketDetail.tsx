import { useState } from 'react'
import type { TriageResult } from './api'
import { meaningOf, placeEvidence, quoteOf, quoteSummary, type EvidencePlacement } from './evidence'
import { categoryLabel, channelLabel, deciderOf, fallbackText, formatTime, isOverridden, policiesOf, provenanceOf, seconds, shownUrgency, TRIAGE_STAGE_TEXT, URGENCY_LABEL, WAKING_TEXT, type Ticket, type TriageStage } from './model'
import { ReviewBar, type ReviewActions } from './ReviewBar'
import { CheckIcon, DashIcon, EvidenceText, UrgencyPill } from './ui'

export type DetailState = { running: boolean; stage: TriageStage | null; slow: boolean; error: string | null; saving: boolean }

function BackIcon() {
  return <svg viewBox="0 0 16 16" aria-hidden="true" focusable="false"><path d="M10 3.5 5.5 8l4.5 4.5" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round" /></svg>
}

function Provenance({ result }: { result: TriageResult }) {
  const provenance = provenanceOf(result)
  if (provenance === 'recorded') return <span className="badge b-recorded">Recorded AI result</span>
  if (provenance === 'fallback') return <span className="badge b-fallback">Rules fallback</span>
  return <span className="badge b-verified"><CheckIcon />AI verified</span>
}

// The three states a live job really goes through (QUEUED, PROCESSING, COMPLETED), never invented sub-steps.
function Stages({ stage }: { stage: TriageStage | null }) {
  const at = stage === 'PROCESSING' || stage === 'RETRYABLE_FAILURE' ? 1 : 0
  const steps = [['Queued', 'Saved to the outbox, sent to SQS'], ['Reading and checking', 'The worker runs the model, then checks every quote'], ['Done', 'Result saved']]
  return (
    <ol className="stages" aria-label="Live triage progress">
      {steps.map(([name, sub], index) => <li key={name} className={index < at ? 'done' : index === at ? 'now' : ''} aria-current={index === at ? 'step' : undefined}><span className="st-name">{name}</span><span className="st-sub">{sub}</span></li>)}
    </ol>
  )
}

function PriorityBlock({ ticket, result }: { ticket: Ticket; result: TriageResult }) {
  const rules = result.appliedRules ?? []
  const raw = rules.reduce((sum, rule) => sum + rule.points, 0)
  const overridden = isOverridden(ticket)
  const by = deciderOf(result)
  return (
    <section className="block prio" aria-label="Priority breakdown">
      <div className="prio-head"><h3>{overridden ? `${by.name} priority (original)` : 'Priority'}</h3><span className="prio-score num">{result.priorityScore}<span className="of"> /100</span></span></div>
      <div className="prio-bar" role="img" aria-label={`Priority ${result.priorityScore} of 100`}>
        {rules.filter((rule) => rule.points > 0).map((rule, index) => <span key={rule.code} className={`seg seg-${Math.min(index, 3)}`} style={{ width: `${Math.min(rule.points, 100)}%` }} />)}
      </div>
      {rules.length > 0 && <ul className="prio-rules">
        {rules.map((rule) => <li key={rule.code}><span>{rule.explanation}</span><span className="num">{rule.points < 0 ? `-${Math.abs(rule.points)}` : `+${rule.points}`}</span></li>)}
        {raw > 100 && <li className="cap"><span>Capped at 100</span><span className="num">{raw}</span></li>}
      </ul>}
      <p className="block-note">{overridden
        ? `This score comes from the ${by.possessive} original ${URGENCY_LABEL[result.urgency]} call and is never overwritten.`
        : by.name === 'AI'
          ? "The model never picks the number. Fixed rules add it up from the AI's urgency and response-time risk plus words in the ticket."
          : "The number comes from fixed rules: the rules' urgency and response-time risk plus words in the ticket."}</p>
    </section>
  )
}

function ChecksBlock({ result, placement, total, industryName }: { result: TriageResult; placement: EvidencePlacement; total: number; industryName: string }) {
  const found = placement.placed.size
  const item = (ok: boolean, text: string) => <li className={ok ? 'ok' : 'partial'}>{ok ? <CheckIcon /> : <DashIcon />}<span>{text}</span></li>
  const fallback = result.decisionSource === 'RULES_FALLBACK'
  return (
    <section className="block checks">
      <h3>Checks</h3>
      <ul className="check-list">
        {item(total > 0 && found === total, quoteSummary(total, placement))}
        {fallback
          ? item(false, "The AI's answer was not used. The pack's keyword rules decided.")
          : <>
            {item(true, `Category and policy are in the ${industryName} pack`)}
            {result.reliabilitySignal === 'HIGH'
              ? item(true, "Quotes contain the pack's keywords for this category and urgency")
              : item(false, 'Limited keyword match for this category or urgency. Check the reasoning.')}
          </>}
      </ul>
      {placement.redacted.size > 0 && <p className="block-note">Payment IDs, card numbers, emails and phone numbers are hidden from the AI. Quotes that mention them are matched to the original text here.</p>}
    </section>
  )
}

function Trace({ ticket, result, onRerun, rerunDisabled }: { ticket: Ticket; result: TriageResult; onRerun: () => void; rerunDisabled: boolean }) {
  const [open, setOpen] = useState(false)
  const job = ticket.job
  if (result.recorded && !job) {
    return <section className="trace trace-recorded"><p>Recorded result from <code>{result.modelVersion}</code>. No live run on this visit.</p><button type="button" className="btn btn-quiet btn-small" onClick={onRerun} disabled={rerunDisabled}>Re-run live</button></section>
  }
  if (!job) return <section className="trace trace-recorded"><p>Result from <code>{result.modelVersion}</code>.</p></section>
  const attempts = job.attemptCount
  return (
    <section className="trace">
      <button type="button" className="trace-line" aria-expanded={open} onClick={() => setOpen((value) => !value)}>
        <span>Outbox → SQS → Worker</span><span className="sep" aria-hidden="true">·</span>
        <span>{attempts} attempt{attempts === 1 ? '' : 's'}</span><span className="sep" aria-hidden="true">·</span>
        <code>{result.modelVersion}</code>
        <span className="trace-more">{open ? 'Hide' : 'Details'}</span>
      </button>
      {open && <>
        <ol className="trace-steps">
          <li>Saved with the ticket in the outbox as event <code>{job.eventId}</code>, then relayed to SQS as job <code>{job.id}</code></li>
          {job.attempts.map((attempt) => <li key={attempt.id}>Attempt {attempt.attemptNumber}: {attempt.status === 'COMPLETED' ? 'completed' : attempt.status === 'PROCESSING' ? 'processing' : attempt.errorCode ? `failed (${attempt.errorCode})` : 'failed'}{formatTime(attempt.startedAt) && `, started ${formatTime(attempt.startedAt)}`}{seconds(attempt.startedAt, attempt.completedAt) && `, took ${seconds(attempt.startedAt, attempt.completedAt)}`}</li>)}
          <li>{result.decisionSource === 'AI_VALIDATED' ? 'Completed: the answer passed every check and was saved as AI verified' : 'Completed: the pack rules decided and the result was saved as a rules fallback'}{formatTime(job.completedAt) && ` at ${formatTime(job.completedAt)}`}{seconds(job.startedAt, job.completedAt) && `, ${seconds(job.startedAt, job.completedAt)} from start to finish`}</li>
        </ol>
        <p className="trace-foot">Prompt {result.promptVersion}. Times are the ones saved on the job, not estimates.</p>
      </>}
    </section>
  )
}

export function TicketDetail({ ticket, state, industryName, hasRecorded, headingLevel = 'h2', actions, onBack, onRerun }: { ticket: Ticket; state: DetailState; industryName: string; hasRecorded: boolean; headingLevel?: 'h1' | 'h2'; actions: ReviewActions; onBack: () => void; onRerun: () => void }) {
  const [active, setActive] = useState<number[] | null>(null)
  // Keyed by result, so a re-run with a new reply never shows "Copied" for text that was not copied.
  const [copiedResultId, setCopiedResultId] = useState<string | null>(null)
  const Heading = headingLevel
  const result = state.running ? null : ticket.result
  const quotes = result ? result.evidence.map(quoteOf) : []
  const placement = placeEvidence(ticket.subject, ticket.message, quotes)
  const fallback = result?.decisionSource === 'RULES_FALLBACK'
  const urgency = result ? shownUrgency(ticket) : null
  const policies = result ? policiesOf(result) : []
  const found = placement.placed.size
  const error = state.error && <p className="error-banner" role="alert">{state.error}</p>

  const head = (
    <header className="d-head">
      <div className="d-top">
        <button type="button" className="back" aria-label="Back to inbox" onClick={onBack}><BackIcon />Inbox</button>
        <p className="d-id"><code>{ticket.id}</code><span>{channelLabel(ticket.channel)}</span></p>
      </div>
      <Heading className="d-subject" id="detail-subject" tabIndex={-1}><EvidenceText text={ticket.subject} marks={placement.subject} active={active} onActive={setActive} /></Heading>
      {result && urgency ? (
        <div className="tags">
          <UrgencyPill urgency={urgency} />
          {isOverridden(ticket) && <span className="tag tag-was">{deciderOf(result).name} said {URGENCY_LABEL[result.urgency]}</span>}
          <span className="tag">Priority <b className="num">{result.priorityScore}</b></span>
          <span className="tag tag-cat">{categoryLabel(result)}</span>
          <Provenance result={result} />
          {result.recorded && !ticket.job && <button type="button" className="link-button head-rerun" onClick={onRerun} disabled={state.saving}>Re-run live</button>}
        </div>
      ) : state.running ? <>
        <Stages stage={state.stage} />
        {/* After 8 seconds in the queue the worker is probably cold: say so instead of the routine stage text. */}
        {state.slow && state.stage === 'QUEUED'
          ? <p className="waking">{WAKING_TEXT}{hasRecorded ? ' You can read the recorded tickets meanwhile.' : ''}</p>
          : <p className="stage-note">{TRIAGE_STAGE_TEXT[state.stage ?? 'SENDING']}</p>}
      </> : null}
      {error}
    </header>
  )

  if (!result) {
    return (
      <section className="detail" aria-labelledby="detail-subject" tabIndex={-1}>
        {head}
        <div className="d-grid">
          <div className="d-main">
            <section className="block b-msg"><h3>Customer message</h3><p className="msg">{ticket.message}</p></section>
            <section className="block b-why"><h3>Why the AI decided this</h3>
              {state.running ? <><p className="block-note">Evidence appears once every quote has been checked against the message.</p><div className="skeleton gap-top"><span style={{ width: '82%' }} /><span style={{ width: '64%' }} /></div></>
                : state.error ? <><p className="block-note">The live run did not finish, so there is no result yet.</p><button type="button" className="btn btn-secondary btn-small try-again" onClick={onRerun}>Try again</button></>
                  : <p className="block-note">Open this ticket to run the live AI on it.</p>}
            </section>
          </div>
          <aside className="d-side">
            <section className="block"><h3>Priority</h3>{state.running ? <div className="skeleton"><span className="tall" style={{ width: '30%' }} /><span style={{ width: '100%' }} /></div> : <p className="block-note">Scored once the ticket is triaged.</p>}</section>
            <section className="block"><h3>Category and policy</h3>{state.running ? <div className="skeleton"><span style={{ width: '50%' }} /><span style={{ width: '90%' }} /><span style={{ width: '70%' }} /></div> : <p className="block-note">Matched once the ticket is triaged.</p>}</section>
          </aside>
        </div>
      </section>
    )
  }

  const copy = () => {
    const done = () => setCopiedResultId(result.id)
    try { void navigator.clipboard?.writeText(result.suggestedReply).then(done, () => undefined) } catch { /* Clipboard blocked: the text stays selectable. */ }
  }
  return (
    <section className="detail" aria-labelledby="detail-subject" tabIndex={-1}>
      {head}
      {fallback && <div className="fallback-note"><b>Rules fallback.</b> {fallbackText(result)} Fixed pack rules decided this ticket instead, so check the call before you accept it.</div>}
      <div className="d-grid">
        <div className="d-main">
          <section className="block b-msg">
            <h3>Customer message</h3>
            <p className="msg"><EvidenceText text={ticket.message} marks={placement.message} active={active} onActive={setActive} /></p>
            {quotes.length > 0 && <p className={found === quotes.length ? 'verify' : 'verify partial'}>{found === quotes.length ? <CheckIcon /> : <DashIcon />}<span>{quoteSummary(quotes.length, placement)}</span></p>}
          </section>
          <section className="block b-why">
            <h3>{fallback ? 'Why the rules decided this' : 'Why the AI decided this'}</h3>
            {result.evidence.length ? <ol className="reasons">
              {result.evidence.map((item, index) => {
                const ref = index + 1
                const placed = placement.placed.has(ref)
                return (
                  <li key={index} data-ev={ref} tabIndex={0} className={active?.includes(ref) ? 'on' : undefined} onMouseEnter={() => setActive([ref])} onMouseLeave={() => setActive(null)} onFocus={() => setActive([ref])} onBlur={() => setActive(null)}>
                    <span className="ref-chip" aria-hidden="true">{ref}</span>
                    <div><p><span className="sr-only">Evidence {ref}: </span>{meaningOf(item) || quoteOf(item)}</p>
                      {!placed && <p className="reason-quote">Quote not found in the ticket text: “{quoteOf(item)}”</p>}</div>
                  </li>
                )
              })}
            </ol> : <p className="block-note">No evidence quotes were returned.</p>}
          </section>
          <section className="block b-pol"><h3 className="sr-only">Policy</h3>
            {/* The phone shows this card instead of the side panel, so it lists every matched policy. */}
            {policies.length ? policies.map((policy, index) => <div key={policy.id} className="pol-item"><p className="pol-line"><b>{policy.label} policy</b>{index === 0 && <span>{categoryLabel(result)}</span>}</p>{policy.guidance && <p className="pol-text">{policy.guidance}</p>}</div>)
              : <p className="pol-line"><b>No policy applies</b><span>{categoryLabel(result)}</span></p>}
          </section>
          <div className="pair">
            <section className="block"><h3>Next steps</h3>{result.recommendedActions.length ? <ol className="steps">{result.recommendedActions.map((action, index) => <li key={index}>{action}</li>)}</ol> : <p className="block-note">No next steps were returned.</p>}</section>
            {result.suggestedReply && <section className="block reply"><div className="block-head"><h3>Suggested reply</h3><button type="button" className="link-button" onClick={copy}>{copiedResultId === result.id ? 'Copied' : 'Copy'}</button></div><p>{result.suggestedReply}</p><p className="block-note">A draft for the reviewer to check. Accepting does not send it.</p></section>}
          </div>
        </div>
        <aside className="d-side">
          <PriorityBlock ticket={ticket} result={result} />
          <section className="block b-cat"><h3>Category</h3><p className="fact">{categoryLabel(result)}</p>{result.categoryDescription && <p className="block-note">{result.categoryDescription}</p>}</section>
          <section className="block b-policy"><h3>Policy</h3>
            {policies.length ? policies.map((policy) => <div key={policy.id} className="policy-item"><p className="fact">{policy.label}</p>{policy.guidance && <p className="guidance">{policy.guidance}</p>}</div>)
              : <><p className="fact muted-fact">No policy applies</p><p className="block-note">None of the {industryName} policies matches this ticket.</p></>}
            {policies.length > 0 && result.policyVersion && <p className="block-note">Policy set {result.policyVersion}</p>}
          </section>
          <ChecksBlock result={result} placement={placement} total={quotes.length} industryName={industryName} />
        </aside>
      </div>
      <Trace ticket={ticket} result={result} onRerun={onRerun} rerunDisabled={state.saving} />
      <ReviewBar key={ticket.backendId} ticket={ticket} saving={state.saving} actions={actions} />
    </section>
  )
}
