import { NO_CAPTURE } from './analytics'
import { useRef, useState } from 'react'
import { categoryLabel, deciderOf, isOverridden, supportsAccept, URGENCIES, URGENCY_LABEL, type Ticket, type Urgency } from './model'
import { CheckIcon, UrgencyPill } from './ui'

export type ReviewActions = {
  onAccept: (ticket: Ticket) => Promise<boolean>
  onOverride: (ticket: Ticket, urgency: Urgency, note: string) => Promise<boolean>
}

/**
 * Accept the AI's call, or override its urgency. Reviews are append-only: there is no Undo. After a review the bar
 * offers the next one (Override urgency after an Accept, Change review after an override).
 */
export function ReviewBar({ ticket, saving, actions }: { ticket: Ticket; saving: boolean } & { actions: ReviewActions }) {
  const [open, setOpen] = useState(false)
  const [draft, setDraft] = useState<Urgency | null>(null)
  const [note, setNote] = useState('')
  const barRef = useRef<HTMLDivElement>(null)
  const result = ticket.result
  if (!result) return null
  const ai = result.urgency
  // A rules-fallback ticket is the rules' call, never the AI's.
  const by = deciderOf(result)
  const review = ticket.review
  const canAccept = supportsAccept(result)
  // An older API records corrections only, and refuses one without a note.
  const noteRequired = !canAccept
  const current = isOverridden(ticket) ? ticket.review?.urgency ?? null : null
  const focusBar = (selector: string) => window.requestAnimationFrame(() => barRef.current?.querySelector<HTMLElement>(selector)?.focus({ preventScroll: true }))

  function startReview() { setOpen(true); setDraft(null); setNote(''); focusBar('.urg-opt:not(:disabled)') }
  function cancel() { setOpen(false); setDraft(null); focusBar('.btn') }
  async function accept() { if (await actions.onAccept(ticket)) focusBar('.btn') }
  async function save() {
    if (!draft) return
    const saved = draft === ai ? await actions.onAccept(ticket) : await actions.onOverride(ticket, draft, note.trim())
    if (saved) { setOpen(false); setDraft(null); setNote(''); focusBar('.btn') }
  }

  if (open) {
    const changing = review !== null
    const canSave = draft !== null && !saving && (!noteRequired || note.trim().length > 0)
    return (
      <div className="review-bar is-open" role="region" aria-label="Review" ref={barRef}>
        <fieldset className="urg-set" disabled={saving}>
          <legend>{changing ? 'Your call on urgency' : `Change urgency from ${URGENCY_LABEL[ai]} to`}</legend>
          <div className="urg-opts">
            {URGENCIES.map((urgency) => {
              const isAi = urgency === ai
              // The AI's own urgency is an Accept, which an older API cannot record; the current override is already saved.
              const disabled = (isAi && (!changing || !canAccept || !review || review.decision === 'ACCEPTED')) || urgency === current
              const tag = isAi ? (changing && !disabled ? `${by.Possessive} call, accept it` : `${by.Possessive} call`) : urgency === current ? 'Your call' : null
              return <button type="button" key={urgency} className={`urg-opt u-${urgency.toLowerCase()}`} aria-pressed={draft === urgency} disabled={disabled} onClick={() => setDraft(urgency)}>{URGENCY_LABEL[urgency]}{tag && <span className="ai-tag">{tag}</span>}</button>
            })}
          </div>
        </fieldset>
        <label className="rv-reason"><span>{noteRequired ? 'Reason' : 'Reason, optional'}</span><input type="text" value={note} maxLength={500} required={noteRequired} disabled={saving} placeholder={`What did the ${by.lower} miss?`} onChange={(event) => setNote(event.target.value)} /></label>
        <div className="rv-actions">
          <button type="button" className="btn btn-quiet" onClick={cancel} disabled={saving}>Cancel</button>
          <button type="button" className="btn btn-primary" onClick={() => void save()} disabled={!canSave} aria-describedby={canSave ? undefined : 'rv-hint'}>{saving ? 'Saving...' : 'Save'}</button>
        </div>
        {!canSave && !saving && <p className="rv-hint" id="rv-hint">{draft === null ? `Pick ${changing ? 'an' : 'a different'} urgency to save.` : 'Add a reason to save.'}</p>}
      </div>
    )
  }

  const logged = ticket.reviewsLogged > 1 ? ` ${ticket.reviewsLogged} reviews are logged for this ticket.` : ''
  if (review && review.decision === 'CORRECTED' && !current) {
    // A correction that leaves urgency as the AI had it (an older client could correct other fields).
    return (
      <div className="review-bar is-done" role="region" aria-label="Review" ref={barRef}>
        <p className="rv-text"><span className="rv-icon ok"><CheckIcon /></span><span><span><b>Corrected.</b> A reviewer correction is logged beside the {by.lower} result.{logged}</span></span></p>
        <div className="rv-actions"><button type="button" className="btn btn-secondary" onClick={startReview} disabled={saving}>Change review</button></div>
      </div>
    )
  }
  if (review?.decision === 'ACCEPTED') {
    return (
      <div className="review-bar is-done" role="region" aria-label="Review" ref={barRef}>
        <p className="rv-text"><span className="rv-icon ok"><CheckIcon /></span><span><span><b>Accepted.</b> The {by.possessive} call is logged as confirmed.{logged}</span></span></p>
        <div className="rv-actions"><button type="button" className="btn btn-secondary" onClick={startReview} disabled={saving}>Override urgency</button></div>
      </div>
    )
  }
  if (review && current) {
    return (
      <div className="review-bar is-done" role="region" aria-label="Review" ref={barRef}>
        <div className="rv-text"><span className="rv-lines">
          <span className="rv-main"><b>Overridden.</b> <span className="rv-pair">{by.name}: <UrgencyPill urgency={ai} />, You: <UrgencyPill urgency={current} /></span>{review.note && <span className={`rv-note ${NO_CAPTURE}`}>“{review.note}”</span>}</span>
          <span className="rv-keep">The original {by.lower} result stays in the record; your override is added beside it.{logged}</span>
        </span></div>
        <div className="rv-actions"><button type="button" className="btn btn-secondary" onClick={startReview} disabled={saving}>Change review</button></div>
      </div>
    )
  }
  return (
    <div className="review-bar" role="region" aria-label="Review" ref={barRef}>
      <p className="rv-text rv-plain">{by.name} call: <b>{URGENCY_LABEL[ai]}</b>, {categoryLabel(result)}</p>
      <div className="rv-actions">
        <button type="button" className="btn btn-secondary" onClick={startReview} disabled={saving}>Override urgency</button>
        {canAccept && <button type="button" className="btn btn-primary" onClick={() => void accept()} disabled={saving}>{saving ? 'Saving...' : 'Accept'}</button>}
      </div>
    </div>
  )
}
