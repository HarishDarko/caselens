// Finds each evidence quote inside the ticket the visitor reads, so the quote can be highlighted in place.
//
// The backend accepts a quote when it is a verbatim substring of the redacted subject or message (SemanticValidator,
// after line endings are normalised). The page shows the original ticket, so a quote that contains a redaction
// placeholder such as [PAYMENT_ID_1] is found in a redacted copy made the same way (redaction.ts) and mapped back to
// the original identifier. Quotes can overlap; overlapping quotes share one highlight that carries every number. A
// quote that cannot be placed is reported, never dropped.
import { redactTicket, type MappedText } from './redaction'

export type Field = 'subject' | 'message'
export type EvidenceMark = { start: number; end: number; refs: number[] }
/** placed: every quote found; redacted: the ones found only in the redacted text (not word for word in what is shown). */
export type EvidencePlacement = { subject: EvidenceMark[]; message: EvidenceMark[]; placed: Set<number>; redacted: Set<number> }

const HAS_PLACEHOLDER = /\[(?:EMAIL|CARD|PHONE|PAYMENT_ID|TRANSACTION_ID|RFID)_\d+\]/

const escapeRegExp = (value: string) => value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
const normalize = (value: string) => value.replace(/\r\n?/g, '\n')

/** Where a quote sits in a text: exactly, or with any run of whitespace matching any run of whitespace. */
function find(source: string, wanted: string): { start: number; end: number } | null {
  const exact = source.indexOf(wanted)
  if (exact >= 0) return { start: exact, end: exact + wanted.length }
  if (!/\s/.test(wanted)) return null
  const match = new RegExp(wanted.split(/\s+/).map(escapeRegExp).join('\\s+')).exec(source)
  return match ? { start: match.index, end: match.index + match[0].length } : null
}

type Located = { start: number; end: number; redacted: boolean }

/** A quote without placeholders is looked up in the original; one with placeholders in the redacted copy, mapped back. */
function locateIn(original: string, redacted: MappedText, quote: string): Located | null {
  const wanted = normalize(quote).trim()
  if (!wanted) return null
  if (!HAS_PLACEHOLDER.test(wanted)) {
    const at = find(original, wanted)
    if (at) return { ...at, redacted: false }
  }
  const at = find(redacted.text, wanted)
  if (!at || at.end <= at.start) return null
  // Found only in the redacted text, so not word for word in what the visitor reads, with or without a placeholder.
  return { start: redacted.start[at.start], end: redacted.end[at.end - 1], redacted: true }
}

/** Where a quote sits in one text, allowing for redaction placeholders and spacing. */
export function locateQuote(text: string, quote: string): { start: number; end: number } | null {
  const source = normalize(text)
  const at = locateIn(source, redactTicket('', source).message, quote)
  return at && { start: at.start, end: at.end }
}

/** Joins overlapping or touching spans into one highlight that keeps every quote number, in order. */
function merge(spans: Array<{ start: number; end: number; ref: number }>): EvidenceMark[] {
  const sorted = [...spans].sort((a, b) => a.start - b.start || b.end - a.end)
  const marks: EvidenceMark[] = []
  for (const span of sorted) {
    const last = marks[marks.length - 1]
    if (last && span.start < last.end) {
      last.end = Math.max(last.end, span.end)
      if (!last.refs.includes(span.ref)) last.refs.push(span.ref)
      last.refs.sort((a, b) => a - b)
    } else {
      marks.push({ start: span.start, end: span.end, refs: [span.ref] })
    }
  }
  return marks
}

/**
 * Places quotes (numbered from 1) in the message first, where the visitor reads them, and in the subject when the
 * message does not hold them (rules fallback always quotes the subject).
 */
export function placeEvidence(subject: string, message: string, quotes: string[]): EvidencePlacement {
  const original = { subject: normalize(subject), message: normalize(message) }
  const redacted = redactTicket(original.subject, original.message)
  const spans: Record<Field, Array<{ start: number; end: number; ref: number }>> = { subject: [], message: [] }
  const placed = new Set<number>()
  const afterRedaction = new Set<number>()
  quotes.forEach((quote, index) => {
    const ref = index + 1
    for (const field of ['message', 'subject'] as const) {
      const at = locateIn(original[field], redacted[field], quote)
      if (!at) continue
      spans[field].push({ start: at.start, end: at.end, ref })
      placed.add(ref)
      if (at.redacted) afterRedaction.add(ref)
      break
    }
  })
  return { subject: merge(spans.subject), message: merge(spans.message), placed, redacted: afterRedaction }
}

/** "3 of 3 quotes found word for word in the ticket", or "2 of 3 quotes found word for word, 1 matched after redaction". */
export function quoteSummary(total: number, placement: EvidencePlacement): string {
  const matchedAfterRedaction = placement.redacted.size
  const wordForWord = placement.placed.size - matchedAfterRedaction
  return matchedAfterRedaction
    ? `${wordForWord} of ${total} quotes found word for word, ${matchedAfterRedaction} matched after redaction`
    : `${wordForWord} of ${total} quotes found word for word in the ticket`
}

export type Segment = { text: string; refs?: number[] }

/** Cuts a text into plain runs and highlighted runs. */
export function segments(text: string, marks: EvidenceMark[]): Segment[] {
  const source = normalize(text)
  const out: Segment[] = []
  let at = 0
  for (const mark of marks) {
    if (mark.start < at) continue
    if (mark.start > at) out.push({ text: source.slice(at, mark.start) })
    out.push({ text: source.slice(mark.start, mark.end), refs: mark.refs })
    at = mark.end
  }
  if (at < source.length) out.push({ text: source.slice(at) })
  return out
}

/** The quote text of an evidence item; an old result stores a plain string. */
export function quoteOf(item: { quote: string; meaning: string } | string): string {
  return typeof item === 'string' ? item : item.quote
}

export function meaningOf(item: { quote: string; meaning: string } | string): string {
  return typeof item === 'string' ? '' : item.meaning
}
