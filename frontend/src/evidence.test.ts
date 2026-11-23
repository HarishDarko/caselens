import { describe, expect, it } from 'vitest'
import { locateQuote, placeEvidence, segments } from './evidence'
import examples from './landing-examples.json'

const marked = (text: string, placement: ReturnType<typeof placeEvidence>['message']) =>
  segments(text, placement).filter((segment) => segment.refs).map((segment) => ({ text: segment.text, refs: segment.refs }))

describe('evidence highlighter', () => {
  it('highlights a quote in the message where the visitor reads it', () => {
    const message = 'Since this morning none of our 40 users can sign in. We are blocked from all work.'
    const placement = placeEvidence('Whole team locked out', message, ['none of our 40 users can sign in', 'We are blocked from all work'])

    expect(marked(message, placement.message)).toEqual([
      { text: 'none of our 40 users can sign in', refs: [1] },
      { text: 'We are blocked from all work', refs: [2] },
    ])
    expect(placement.subject).toEqual([])
    expect([...placement.placed]).toEqual([1, 2])
  })

  it('highlights a quote taken from the subject, as the rules fallback always does', () => {
    // A real capture: the model quoted the subject as evidence 1 (ev-charging, charger-offline-site-wide, 2026-11-23).
    const subject = 'Every charger at the demo site appears offline'
    const message = 'All six chargers at synthetic site SITE-DEMO-TOR-07 show Offline since 09:18 UTC.'
    const placement = placeEvidence(subject, message, [subject, 'show Offline since 09:18 UTC'])

    expect(marked(subject, placement.subject)).toEqual([{ text: subject, refs: [1] }])
    expect(marked(message, placement.message)).toEqual([{ text: 'show Offline since 09:18 UTC', refs: [2] }])
  })

  it('prefers the message when the same words are in both', () => {
    const placement = placeEvidence('Refund missing', 'My refund missing since May.', ['efund missing'])
    expect(placement.message).toHaveLength(1)
    expect(placement.subject).toEqual([])
  })

  it('joins overlapping quotes into one highlight that carries both numbers', () => {
    const message = 'Every call to your REST API has returned 503 for the past 20 minutes.'
    const placement = placeEvidence('', message, ['Every call to your REST API has returned 503', 'returned 503 for the past 20 minutes'])

    expect(marked(message, placement.message)).toEqual([{ text: 'Every call to your REST API has returned 503 for the past 20 minutes', refs: [1, 2] }])
    expect([...placement.placed]).toEqual([1, 2])
  })

  it('keeps a quote that sits inside another one, and the same quote twice, without dropping either number', () => {
    const message = 'All webhook deliveries to our endpoint have failed with a 401 response since yesterday.'
    const placement = placeEvidence('', message, ['failed with a 401 response', 'All webhook deliveries to our endpoint have failed with a 401 response', 'failed with a 401 response'])

    expect(marked(message, placement.message)).toEqual([{ text: 'All webhook deliveries to our endpoint have failed with a 401 response', refs: [1, 2, 3] }])
  })

  it('matches a quote with a redacted payment reference back to the original identifier', () => {
    // Real captures: the model saw [PAYMENT_ID_1] where the ticket says PAY-DEMO-7319.
    const message = 'At charger CHG-CA-1042, connector 2 accepted payment reference PAY-DEMO-7319 for session SESSION-DEMO-88421 at 14:06 UTC.'
    const placement = placeEvidence('Payment accepted but charging did not start', message, ['connector 2 accepted payment reference [PAYMENT_ID_1]'])

    expect(marked(message, placement.message)).toEqual([{ text: 'connector 2 accepted payment reference PAY-DEMO-7319', refs: [1] }])
    expect([...placement.redacted]).toEqual([1])
  })

  it('matches two redacted identifiers in one quote', () => {
    const message = 'the fictional card statement contains payment references PAY-DEMO-1004 and PAY-DEMO-1005 for the same CAD 18.40 amount. The driver is requesting a refund.'
    const quote = 'the fictional card statement contains payment references [PAYMENT_ID_1] and [PAYMENT_ID_2] for the same CAD 18.40 amount.'

    expect(marked(message, placeEvidence('', message, [quote]).message)[0]?.text).toBe('the fictional card statement contains payment references PAY-DEMO-1004 and PAY-DEMO-1005 for the same CAD 18.40 amount.')
  })

  it('allows for the separator the redactor drops, and for emails, cards and phone numbers', () => {
    expect(locateQuote('Transaction id: TX-55AA failed twice', 'Transaction id [TRANSACTION_ID_1] failed')).toEqual({ start: 0, end: 30 })
    const text = 'Write to ana@example.com or call (416) 555-0100 about card 4111 1111 1111 1111 today.'
    const at = locateQuote(text, 'Write to [EMAIL_1] or call [PHONE_1] about card [CARD_1] today')
    expect(at && text.slice(at.start, at.end)).toBe('Write to ana@example.com or call (416) 555-0100 about card 4111 1111 1111 1111 today')
  })

  it('reports a quote it cannot find instead of dropping it silently', () => {
    const placement = placeEvidence('Subject', 'The charger is fine today.', ['The charger is fine', 'words that are not in the ticket'])

    expect([...placement.placed]).toEqual([1])
    expect(placement.placed.has(2)).toBe(false)
    expect(marked('The charger is fine today.', placement.message)).toEqual([{ text: 'The charger is fine', refs: [1] }])
  })

  it('does not match a redacted quote to unrelated text', () => {
    expect(locateQuote('payment reference was declined', 'payment reference [PAYMENT_ID_1] for session')).toBeNull()
  })

  it('treats Windows line endings like the server does, and tolerates spacing differences', () => {
    const message = 'First line.\r\nSecond  line with the quote.'
    const placement = placeEvidence('', message, ['First line.\nSecond line'])

    expect(marked(message, placement.message)).toEqual([{ text: 'First line.\nSecond  line', refs: [1] }])
  })

  it('leaves a text without quotes as one plain run, and never highlights an empty quote', () => {
    expect(segments('Nothing to mark.', [])).toEqual([{ text: 'Nothing to mark.' }])
    expect(placeEvidence('Subject', 'Message', ['', '   ']).placed.size).toBe(0)
  })

  it('places every quote of every landing example (real captured results)', () => {
    for (const example of examples) {
      const quotes = example.result.evidence.map((item) => item.quote)
      expect(placeEvidence(example.subject, example.message, quotes).placed.size, example.scenarioKey).toBe(quotes.length)
    }
  })
})
