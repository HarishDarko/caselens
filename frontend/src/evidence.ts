// Finds each evidence quote inside the ticket the visitor reads, so the quote can be highlighted in place.
//
// The backend accepts a quote when it is a verbatim substring of the redacted subject or message (SemanticValidator,
// after line endings are normalised). The page shows the original ticket, so a quote that contains a redaction
// placeholder such as [PAYMENT_ID_1] is matched back to the original identifier. Quotes can overlap; overlapping
// quotes share one highlight that carries every number. A quote that cannot be placed is reported, never dropped.

export type Field = 'subject' | 'message'
export type EvidenceMark = { start: number; end: number; refs: number[] }
export type EvidencePlacement = { subject: EvidenceMark[]; message: EvidenceMark[]; placed: Set<number>; redacted: Set<number> }

const PLACEHOLDER = /\[(EMAIL|CARD|PHONE|PAYMENT_ID|TRANSACTION_ID|RFID)_\d+\]/g
const HAS_PLACEHOLDER = /\[(?:EMAIL|CARD|PHONE|PAYMENT_ID|TRANSACTION_ID|RFID)_\d+\]/

// What each placeholder stood for in the original text (TicketRedactor). A labelled identifier also loses the ":", "#"
// or "-" between its label and the value, so the gap before it is matched loosely.
const ORIGINAL: Record<string, string> = {
  EMAIL: '[^\\s@]+@[^\\s@]+\\.[A-Za-z]{2,}',
  CARD: '\\d(?:[ -]?\\d){12,18}',
  PHONE: '(?:\\+?1[\\s.-]?)?\\(?\\d{3}\\)?[\\s.-]?\\d{3}[\\s.-]?\\d{4}',
  PAYMENT_ID: '[A-Za-z0-9_][A-Za-z0-9_-]{3,}',
  TRANSACTION_ID: '[A-Za-z0-9_][A-Za-z0-9_-]{3,}',
  RFID: '[A-Za-z0-9_][A-Za-z0-9_-]{3,}',
}

const escapeRegExp = (value: string) => value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
const normalize = (value: string) => value.replace(/\r\n?/g, '\n')

// Literal text with any run of whitespace matching any run of whitespace.
function literal(value: string): string {
  return value.split(/\s+/).map(escapeRegExp).join('\\s+')
}

/** A pattern for a quote that carries redaction placeholders, or that differs from the ticket only in whitespace. */
function loosePattern(quote: string): RegExp | null {
  const parts: string[] = []
  let at = 0
  let hasPlaceholder = false
  for (const match of quote.matchAll(PLACEHOLDER)) {
    hasPlaceholder = true
    const before = quote.slice(at, match.index)
    const trimmed = before.replace(/\s+$/, '')
    if (trimmed) parts.push(literal(trimmed))
    // The separator the redactor dropped ("id: PAY-1" became "id [PAYMENT_ID_1]") or the space that stayed.
    if (trimmed !== before || (trimmed && match.index > 0)) parts.push('\\s*[:#-]?\\s*')
    parts.push(`(?:${ORIGINAL[match[1]]})`)
    at = (match.index ?? 0) + match[0].length
  }
  const rest = quote.slice(at)
  if (rest) parts.push(literal(rest))
  if (!hasPlaceholder && !/\s/.test(quote)) return null
  try {
    return new RegExp(parts.join(''))
  } catch {
    return null
  }
}

/** Where a quote sits in a text: an exact match first, then a match that allows for redaction and spacing. */
export function locateQuote(text: string, quote: string): { start: number; end: number } | null {
  const source = normalize(text)
  const wanted = normalize(quote).trim()
  if (!wanted) return null
  const exact = source.indexOf(wanted)
  if (exact >= 0) return { start: exact, end: exact + wanted.length }
  const pattern = loosePattern(wanted)
  const match = pattern ? pattern.exec(source) : null
  return match && match[0].length > 0 ? { start: match.index, end: match.index + match[0].length } : null
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
  const spans: Record<Field, Array<{ start: number; end: number; ref: number }>> = { subject: [], message: [] }
  const placed = new Set<number>()
  const redacted = new Set<number>()
  quotes.forEach((quote, index) => {
    const ref = index + 1
    for (const field of ['message', 'subject'] as const) {
      const at = locateQuote(field === 'message' ? message : subject, quote)
      if (!at) continue
      spans[field].push({ ...at, ref })
      placed.add(ref)
      if (HAS_PLACEHOLDER.test(quote)) redacted.add(ref)
      break
    }
  })
  return { subject: merge(spans.subject), message: merge(spans.message), placed, redacted }
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
