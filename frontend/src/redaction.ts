// A port of the backend's TicketRedactor (backend/.../triage/TicketRedactor.java) that also remembers, for every
// character of the redacted text, which characters of the original text it came from. The model only ever sees the
// redacted ticket, so a quote such as "payment reference [PAYMENT_ID_2]" is found in the redacted text exactly as the
// validator found it, then mapped back to the original identifier the visitor reads. The same order of passes (email,
// card, labelled identifiers, phone) and the same numbering (per type, per distinct value, subject before message)
// keep [EMAIL_2] pointing at the second email, not the first. Keep this in step with TicketRedactor.

/** A text, and for each of its characters the original range [start, end) it stands for. */
export type MappedText = { text: string; start: number[]; end: number[] }

const DESCRIPTOR_WORDS = 'tokens?|cards?|badges?|tags?|chips?|fobs?|ids?|numbers?|no\\.?|ref|reference|codes?|value'
const EMAIL = /[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}/gi
const CARD = /(?<!\d)(?:\d[ -]?){13,19}(?!\d)/g
const DESCRIPTOR = new RegExp(`^(?:${DESCRIPTOR_WORDS})$`, 'i')
const LABEL_WORD = /^(?:payment|transaction|rfid)$/i
const LABELLED_TOKEN = new RegExp(
  '\\b(payment\\s*id|payment\\s*ref(?:erence)?s?|transaction\\s*id|rfid)(?![A-Za-z0-9_])'
  + `((?:\\s*[:#,-]?\\s*(?:${DESCRIPTOR_WORDS})(?![A-Za-z0-9_])){0,2})`
  + '\\s*(?:([:#-])|(is)(?![A-Za-z0-9_]))?\\s*'
  + '([A-Za-z0-9_][A-Za-z0-9_-]{3,})', 'dgi')
const LIST_TAIL = /(?:\s*,\s*(?:(?:and|or)\s+)?|\s+(?:and|or|&)\s+)([A-Za-z0-9_][A-Za-z0-9_-]{3,})/dyi
const LEADING_LETTERS = /^[A-Za-z]*/
const LOWERCASE_HEX = /^[0-9a-f]{8,}$/
const PHONE = /(?<!\w)(?:\+?1[\s.-]?)?(?:\(?\d{3}\)?[\s.-]?)\d{3}[\s.-]?\d{4}(?!\w)/g
const DIGIT = /\p{Nd}/u
const LETTER = /\p{L}/u

/** Builds a mapped text piece by piece. */
class Builder {
  text = ''
  start: number[] = []
  end: number[] = []
  /** Copies [from, to) of a mapped text as it is. */
  copy(source: MappedText, from: number, to: number) {
    this.text += source.text.slice(from, to)
    for (let i = from; i < to; i++) { this.start.push(source.start[i]); this.end.push(source.end[i]) }
  }
  /** Writes new text that stands for [from, to) of a mapped text. */
  write(value: string, source: MappedText, from: number, to: number) {
    if (to <= from) { to = Math.min(from + 1, source.text.length); from = Math.min(from, to - 1) }
    const start = source.start[from] ?? 0
    const end = source.end[to - 1] ?? start
    this.text += value
    for (let i = 0; i < value.length; i++) { this.start.push(start); this.end.push(end) }
  }
  done(): MappedText { return { text: this.text, start: this.start, end: this.end } }
}

class State {
  private readonly placeholders = new Map<string, Map<string, string>>()
  placeholder(type: string, raw: string): string {
    let values = this.placeholders.get(type)
    if (!values) { values = new Map(); this.placeholders.set(type, values) }
    const key = raw.toLowerCase()
    let value = values.get(key)
    if (!value) { value = `[${type}_${values.size + 1}]`; values.set(key, value) }
    return value
  }
}

const digitsOnly = (value: string) => value.replace(/\D/g, '')

function replace(source: MappedText, pattern: RegExp, replacement: (match: RegExpExecArray) => string): MappedText {
  const out = new Builder()
  let at = 0
  pattern.lastIndex = 0
  for (let match = pattern.exec(source.text); match; match = pattern.exec(source.text)) {
    if (match[0].length === 0) { pattern.lastIndex += 1; continue }
    out.copy(source, at, match.index)
    out.write(replacement(match), source, match.index, match.index + match[0].length)
    at = match.index + match[0].length
  }
  out.copy(source, at, source.text.length)
  return out.done()
}

export function looksLikeIdentifier(candidate: string): boolean {
  if (DESCRIPTOR.test(candidate)) return false
  if (LABEL_WORD.test(candidate)) return false
  if ([...candidate].some((c) => DIGIT.test(c) || c === '_' || c === '-')) return true
  if (LOWERCASE_HEX.test(candidate)) return true
  return candidate === candidate.toUpperCase()
}

const shape = (value: string) => [...value].map((c) => LETTER.test(c) ? 'A' : DIGIT.test(c) ? '9' : c).join('')

function sameFamily(redacted: string, listed: string): boolean {
  if (![...listed].some((c) => DIGIT.test(c))) return false
  if (shape(redacted) !== shape(listed)) return false
  const letters = LEADING_LETTERS.exec(redacted)?.[0] ?? ''
  return listed.slice(0, letters.length).toLowerCase() === letters.toLowerCase()
}

function redactLabelled(source: MappedText, state: State): MappedText {
  const input = source.text
  const out = new Builder()
  let position = 0
  while (position < input.length) {
    LABELLED_TOKEN.lastIndex = position
    const match = LABELLED_TOKEN.exec(input)
    if (!match?.indices) break
    const [labelStart, labelEnd] = match.indices[1]
    const [candidateStart, candidateEnd] = match.indices[5]
    const candidate = match[5]
    out.copy(source, position, match.index)
    if (!looksLikeIdentifier(candidate)) {
      out.copy(source, match.index, candidateStart)
      position = candidateStart
      continue
    }
    const lower = match[1].toLowerCase()
    const type = lower.startsWith('transaction') ? 'TRANSACTION_ID' : lower.startsWith('payment') ? 'PAYMENT_ID' : 'RFID'
    const words = match[2].replace(/[:#,-]/g, ' ').trim().replace(/\s+/g, ' ')
    const descriptor = words ? ` ${words}` : ''
    const is = match[4] === undefined ? '' : ` ${match[4]}`
    out.copy(source, labelStart, labelEnd)
    out.write(`${descriptor}${is} `, source, labelEnd, candidateStart)
    out.write(state.placeholder(type, candidate), source, candidateStart, candidateEnd)
    position = match.index + match[0].length
    for (;;) {
      if (position >= input.length) break
      LIST_TAIL.lastIndex = position
      const tail = LIST_TAIL.exec(input)
      if (!tail?.indices || !sameFamily(candidate, tail[1])) break
      const [listedStart, listedEnd] = tail.indices[1]
      out.copy(source, position, listedStart)
      out.write(state.placeholder(type, tail[1]), source, listedStart, listedEnd)
      position = tail.index + tail[0].length
    }
  }
  out.copy(source, position, input.length)
  return out.done()
}

function identity(text: string): MappedText {
  return { text, start: Array.from({ length: text.length }, (_, i) => i), end: Array.from({ length: text.length }, (_, i) => i + 1) }
}

function redactField(value: string, state: State): MappedText {
  let result = replace(identity(value), EMAIL, (match) => state.placeholder('EMAIL', match[0]))
  result = replace(result, CARD, (match) => state.placeholder('CARD', digitsOnly(match[0])))
  result = redactLabelled(result, state)
  return replace(result, PHONE, (match) => state.placeholder('PHONE', digitsOnly(match[0])))
}

/** Redacts subject then message with one numbering, as the backend does before the model sees them. */
export function redactTicket(subject: string, message: string): { subject: MappedText; message: MappedText } {
  const state = new State()
  return { subject: redactField(subject, state), message: redactField(message, state) }
}
