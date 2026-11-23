import type { KeyboardEvent } from 'react'
import { categoryLabel, channelLabel, deciderOf, lowerFirst, provenanceOf, reviewOrder, shownUrgency, URGENCY_LABEL, type Ticket, type TriageStage } from './model'
import { UrgencyPill } from './ui'

export type TicketActivity = { running: boolean; stage: TriageStage | null; failed: boolean }

function rowWho(ticket: Ticket): { text: string; reviewed: boolean } {
  const result = ticket.result
  if (!result) return { text: '', reviewed: false }
  if (ticket.review?.decision === 'CORRECTED' && ticket.review.urgency && ticket.review.urgency !== result.urgency) return { text: `You, ${deciderOf(result).lower} said ${URGENCY_LABEL[result.urgency]}`, reviewed: true }
  if (ticket.review) return { text: ticket.review.decision === 'ACCEPTED' ? 'Accepted' : 'Corrected', reviewed: true }
  const provenance = provenanceOf(result)
  return { text: provenance === 'recorded' ? 'Recorded' : provenance === 'verified' ? 'AI verified' : 'Rules fallback', reviewed: false }
}

function Row({ ticket, activity, selected, onSelect }: { ticket: Ticket; activity: TicketActivity; selected: boolean; onSelect: (id: string) => void }) {
  const triaged = ticket.result !== null && !activity.running
  const urgency = triaged ? shownUrgency(ticket) : null
  const who = rowWho(ticket)
  let pill
  if (activity.running) pill = <span className="pill p-running">{activity.stage === 'PROCESSING' || activity.stage === 'RETRYABLE_FAILURE' ? 'Reading and checking' : 'Queued'}</span>
  else if (urgency) pill = <UrgencyPill urgency={urgency} />
  else if (activity.failed) pill = <span className="pill p-failed">Triage failed</span>
  else pill = <span className="pill p-run">Run live AI</span>
  return (
    <button type="button" className={`row ${urgency ? `u-${urgency.toLowerCase()}` : 'u-none'}${selected ? ' selected' : ''}`} data-id={ticket.id} aria-current={selected ? 'true' : undefined} onClick={() => onSelect(ticket.id)}>
      <span className="row-line"><span className="row-subject">{ticket.subject}</span><span className="row-chan">{channelLabel(ticket.channel)}</span></span>
      <span className="row-line row-sub">
        {pill}
        {triaged && ticket.result && <><span className="row-cat">{categoryLabel(ticket.result)}</span><span className={who.reviewed ? 'row-prov reviewed' : 'row-prov'}>{who.text}</span></>}
        <span className="row-score num">{triaged && ticket.result ? <><span className="sr-only">Priority </span>{ticket.result.priorityScore}</> : <><span className="sr-only">No priority yet</span><span className="dash" aria-hidden="true">-</span></>}</span>
      </span>
    </button>
  )
}

/** Arrow keys move between rows; Enter or Space opens one. */
function moveFocus(event: KeyboardEvent<HTMLDivElement>) {
  if (event.key !== 'ArrowDown' && event.key !== 'ArrowUp') return
  const rows = [...event.currentTarget.querySelectorAll<HTMLButtonElement>('.row')]
  const index = rows.indexOf(document.activeElement as HTMLButtonElement)
  if (index < 0) return
  event.preventDefault()
  rows[Math.max(0, Math.min(rows.length - 1, index + (event.key === 'ArrowDown' ? 1 : -1)))]?.focus()
}

export function Queue({ tickets, activity, selectedId, onSelect }: { tickets: Ticket[]; activity: (ticket: Ticket) => TicketActivity; selectedId: string | null; onSelect: (id: string) => void }) {
  const ordered = reviewOrder(tickets)
  const done = ordered.filter((ticket) => ticket.result !== null && !activity(ticket).running)
  const waiting = ordered.filter((ticket) => !done.includes(ticket))
  const critical = done.filter((ticket) => shownUrgency(ticket) === 'CRITICAL')
  const finding = critical.length === 1 ? `1 critical ticket: ${lowerFirst(critical[0].subject)}.`
    : critical.length > 1 ? `${critical.length} critical tickets, starting with ${lowerFirst(critical[0].subject)}.`
      : done.length ? `No critical tickets. Top of the queue: ${lowerFirst(done[0].subject)}.` : 'Nothing triaged yet. Open a ticket to run the live AI on it.'
  return (
    <section className="queue" aria-labelledby="inbox-title">
      <header className="queue-head">
        <h1 id="inbox-title" tabIndex={-1}>Inbox</h1>
        <p className="finding">{critical.length > 0 && <span className="dot u-critical" aria-hidden="true" />}{finding}</p>
        <p className="queue-meta">{tickets.length} tickets, {done.length} triaged, {waiting.length} not triaged yet</p>
      </header>
      <div className="queue-list" onKeyDown={moveFocus}>
        {done.length > 0 && <>
          <div className="group"><h2>Triaged, highest priority first</h2><span className="num">{done.length}</span></div>
          <div className="rows">{done.map((ticket) => <Row key={ticket.backendId} ticket={ticket} activity={activity(ticket)} selected={ticket.id === selectedId} onSelect={onSelect} />)}</div>
        </>}
        {waiting.length > 0 && <>
          <div className="group"><h2>Not triaged yet</h2><span className="num">{waiting.length}</span><p className="group-sub">Open one to run the live AI on it</p></div>
          <div className="rows">{waiting.map((ticket) => <Row key={ticket.backendId} ticket={ticket} activity={activity(ticket)} selected={ticket.id === selectedId} onSelect={onSelect} />)}</div>
        </>}
      </div>
    </section>
  )
}
