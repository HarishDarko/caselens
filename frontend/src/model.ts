import { ApiError, type ApiTicket, type FallbackReason, type LatestFeedback, type ReviewDecision, type TriageJob, type TriageResult, type Urgency } from './api'

export type { Urgency }

/** The latest review of a ticket. Reviews are append-only: a new one is added, an old one is never removed. */
export type Review = { decision: ReviewDecision; urgency: Urgency | null; note: string | null }

export type Ticket = {
  id: string
  backendId: string
  subject: string
  message: string
  channel: string
  scenarioKey: string | null
  result: TriageResult | null
  job: TriageJob | null
  review: Review | null
  /** Reviews this page has seen saved or loaded for the ticket, so "N reviews are logged" is never a guess. */
  reviewsLogged: number
}

export const URGENCIES: Urgency[] = ['LOW', 'MEDIUM', 'HIGH', 'CRITICAL']
export const URGENCY_LABEL: Record<Urgency, string> = { LOW: 'Low', MEDIUM: 'Medium', HIGH: 'High', CRITICAL: 'Critical' }

// The retry demo ticket fails on purpose. It belongs to Operations, never to the inbox (an older API still seeds it).
export const RETRY_DEMO_SCENARIO = 'provider-retry-demo'

// A worker whose SnapStart snapshot was dropped after 14 idle days rebuilds it before SQS redelivers the job (about
// 90 seconds in production), and a rate-limited triage is retried once after about 90 seconds, so the worst case is
// about 135 seconds (149 s at most on the backend). Wait 180 seconds and back off while waiting.
export const TRIAGE_WAIT_MS = 180_000
export const SLOW_TRIAGE_NOTICE_MS = 8_000
// A triage takes about 4 to 6 seconds, and the API allows 5 requests a second for all visitors together, so check
// once a second (every 2 seconds after 30 seconds). Checking four times a second only used up that shared budget.
export function triagePollDelay(elapsedMs: number) { return elapsedMs < 30_000 ? 1_000 : 2_000 }

// What the visitor reads while a ticket is triaged, for each state the job can be in.
export const TRIAGE_STAGE_TEXT = {
  SENDING: 'Sending the ticket to the triage queue.',
  QUEUED: 'Waiting for the triage worker to pick this up. After a quiet period it can take up to a minute to start.',
  PROCESSING: 'The AI model is reading the ticket. This usually takes a few seconds.',
  RETRYABLE_FAILURE: 'The AI model is busy. The worker tries again by itself in about a minute and a half.',
} as const
export type TriageStage = keyof typeof TRIAGE_STAGE_TEXT
export const WAKING_TEXT = 'Waking the triage worker. The first live run after a quiet spell can take up to a minute.'
export const TRIAGE_FAILED_TEXT = 'This ticket could not be triaged. Open Operations to retry it.'
export const RETRY_DEMO_STOPPED_TEXT = 'The retry demo stopped this triage on purpose. Open Operations to retry it.'
export const TRIAGE_STILL_RUNNING_TEXT = 'Triage is still running. Open the ticket again in a minute to see the result.'

export const SESSION_ENDED_TEXT = 'Your demo session has ended. Close the demo and open a new one.'
// The API answers every refused review with the same 409 (an unchanged result, a result that is not the latest, or one
// that belongs to another ticket), so the sentence covers all of them.
export const CORRECTION_CONFLICT_TEXT = 'This correction conflicts with the current triage result of this ticket, so it was not saved. Open the ticket again and check it.'
export const WORKSPACE_LIMIT_TEXT = 'This demo workspace has reached its limit. Start a new demo to keep going.'
export const SESSION_ENDED_BANNER_TEXT = 'Your demo session has ended. Start a new demo to keep exploring.'

/** A sentence written for the visitor, shown as it is. */
export class PlainError extends Error {}

/** Waits, and stops waiting with the signal's reason when the signal aborts. */
export function pause(ms: number, signal: AbortSignal): Promise<void> {
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
export function plainError(cause: unknown, fallback: string, conflict = 'That change does not apply to this ticket any more. Open the ticket again and check it.'): string {
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

function reviewFrom(feedback: LatestFeedback | null | undefined): Review | null {
  if (!feedback) return null
  return { decision: feedback.decision === 'ACCEPTED' ? 'ACCEPTED' : 'CORRECTED', urgency: feedback.urgency ?? null, note: feedback.note ?? null }
}

export function mapApiTicket(ticket: ApiTicket): Ticket {
  const review = reviewFrom(ticket.latestFeedback)
  return {
    id: ticket.displayId, backendId: ticket.id, subject: ticket.subject, message: ticket.message, channel: ticket.channel,
    scenarioKey: ticket.scenarioKey, result: ticket.latestResult ?? null, job: null, review, reviewsLogged: review ? 1 : 0,
  }
}

// Words kept in capitals when an id is turned into a label for an API that sends no label.
const ACRONYMS = new Set(['SSO', 'MFA', 'API', 'OCPP', 'RFID', 'EV', 'SLA', 'SAML', 'OIDC', 'ID'])
/** "LOGIN_ACCESS" -> "Login access", "OCPP_CONNECTIVITY" -> "OCPP connectivity". */
export function humanize(id: string): string {
  return id.split(/[_\s-]+/).filter(Boolean).map((word, index) => {
    const upper = word.toUpperCase()
    if (ACRONYMS.has(upper)) return upper
    const lower = word.toLowerCase()
    return index === 0 ? lower.charAt(0).toUpperCase() + lower.slice(1) : lower
  }).join(' ')
}

export const categoryLabel = (result: TriageResult) => result.categoryLabel || humanize(result.category)

/** The policies a result cites, with label and guidance when the API sends them. */
export function policiesOf(result: TriageResult): Array<{ id: string; label: string; guidance: string | null }> {
  if (result.policies?.length) return result.policies.map((policy) => ({ id: policy.id, label: policy.label || humanize(policy.id), guidance: policy.guidance || null }))
  return (result.policyIds ?? []).map((id) => ({ id, label: humanize(id), guidance: null }))
}

/** The urgency the reviewer sees: their override when the latest review is one, otherwise the AI's. */
export function shownUrgency(ticket: Ticket): Urgency | null {
  if (!ticket.result) return null
  return ticket.review?.decision === 'CORRECTED' && ticket.review.urgency ? ticket.review.urgency : ticket.result.urgency
}

export const isOverridden = (ticket: Ticket) => ticket.review?.decision === 'CORRECTED' && ticket.review.urgency !== null && ticket.review.urgency !== ticket.result?.urgency

/**
 * An API that sends `recorded` on a result also takes decision ACCEPTED and an override without a note. An older one
 * records corrections only, each with a note, so the page asks for a reason and offers no Accept there.
 */
export const supportsAccept = (result: TriageResult | null) => typeof result?.recorded === 'boolean'

/**
 * Who made the call a reviewer accepts or overrides: the AI, or the pack's rules when the AI's answer was not used.
 * A rules-fallback ticket never says "AI call".
 */
export type Decider = { name: 'AI' | 'Rules'; lower: 'AI' | 'rules'; possessive: "AI's" | "rules'"; Possessive: "AI's" | "Rules'" }
export function deciderOf(result: TriageResult): Decider {
  return result.decisionSource === 'RULES_FALLBACK'
    ? { name: 'Rules', lower: 'rules', possessive: "rules'", Possessive: "Rules'" }
    : { name: 'AI', lower: 'AI', possessive: "AI's", Possessive: "AI's" }
}

export type Provenance = 'recorded' | 'verified' | 'fallback'
export function provenanceOf(result: TriageResult): Provenance {
  if (result.decisionSource === 'RULES_FALLBACK') return 'fallback'
  return result.recorded ? 'recorded' : 'verified'
}

// The plain reason a ticket fell back to the pack's rules. An API from before fallbackReason gives none.
const FALLBACK_TEXT: Record<FallbackReason, string> = {
  PROVIDER_UNAVAILABLE: 'The AI provider did not answer.',
  PROVIDER_RATE_LIMITED: 'The AI provider was busy and turned the request away.',
  BUDGET_SPENT: "Today's live AI budget for this demo is spent.",
  ANSWER_FAILED_CHECKS: "The AI's answer failed a check, for example a quote that is not in the ticket.",
  ANSWER_UNREADABLE: "The AI's answer was not in the required format.",
}
export function fallbackText(result: TriageResult): string {
  const reason = result.fallbackReason ? FALLBACK_TEXT[result.fallbackReason] : undefined
  return reason ?? 'The live AI was unavailable or its answer failed a check.'
}

/**
 * Records a saved review on its ticket, but only while the ticket still shows the result the review was made on:
 * a newer result (a re-run that finished first) starts unreviewed.
 */
export function applyReview(tickets: Ticket[], ticketId: string, resultId: string, review: Review): Ticket[] {
  return tickets.map((item) => item.backendId === ticketId && item.result?.id === resultId ? { ...item, review, reviewsLogged: item.reviewsLogged + 1 } : item)
}

const SLA_RISK_RANK: Record<string, number> = { HIGH: 3, MEDIUM: 2, LOW: 1 }
// Triaged tickets first, the highest priority score first and then the higher SLA risk. Tickets not triaged yet come
// after them in the order the API sent (newest first); the sort is stable, so equal tickets keep that order too.
// The score is the AI's original one: an override is logged beside it and never rescored.
export function reviewOrder(tickets: Ticket[]): Ticket[] {
  return [...tickets].sort((a, b) => {
    if (!a.result || !b.result) return Number(!a.result) - Number(!b.result)
    return b.result.priorityScore - a.result.priorityScore || (SLA_RISK_RANK[b.result.slaRisk] ?? 0) - (SLA_RISK_RANK[a.result.slaRisk] ?? 0)
  })
}

/** "Whole team locked out" -> "whole team locked out", but "SSO change" stays as written. */
export function lowerFirst(value: string) {
  return /^[A-Z][A-Z0-9]/.test(value) ? value : value.charAt(0).toLowerCase() + value.slice(1)
}

export function formatTime(value: string | null | undefined) {
  if (!value) return null
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? null : date.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' })
}

export function seconds(from: string | null | undefined, to: string | null | undefined): string | null {
  if (!from || !to) return null
  const ms = Date.parse(to) - Date.parse(from)
  return Number.isFinite(ms) && ms >= 0 ? `${(ms / 1000).toFixed(1)} s` : null
}

export function isPhone(): boolean {
  return typeof window.matchMedia === 'function' && window.matchMedia('(max-width: 699px)').matches
}

const CHANNEL_LABEL: Record<string, string> = { EMAIL: 'Email', WEB: 'Web', CHAT: 'Chat', PHONE: 'Phone' }
export const channelLabel = (channel: string) => CHANNEL_LABEL[channel] ?? channel.charAt(0) + channel.slice(1).toLowerCase()
