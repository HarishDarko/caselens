import { describe, expect, it } from 'vitest'
import { redactTicket } from './redaction'

// Every expected output below is copied from TicketRedactorTest.java, so the page redacts exactly as the server does.
const message = (text: string) => redactTicket('Subject x', text).message.text

describe('redaction port matches TicketRedactor', () => {
  it.each([
    ['My RFID card 04A1B2C3D4 is rejected', 'My RFID card [RFID_1] is rejected'],
    ['RFID token 04A1B2C3 does not work', 'RFID token [RFID_1] does not work'],
    ['payment id number PAY12345 failed', 'payment id number [PAYMENT_ID_1] failed'],
    ['transaction id value TX-998877 failed', 'transaction id value [TRANSACTION_ID_1] failed'],
    ['Driver badge: RFID badge #77AB12 was declined', 'Driver badge: RFID badge [RFID_1] was declined'],
    ['Transaction ID reference code TXN-5512 is missing', 'Transaction ID reference code [TRANSACTION_ID_1] is missing'],
    ['My RFID tag 9F8E7D6C stopped working', 'My RFID tag [RFID_1] stopped working'],
    ['The RFID chip AB12CD34 and RFID fob 77FF0011 fail', 'The RFID chip [RFID_1] and RFID fob [RFID_2] fail'],
    ['RFID id 0011AABB is blocked', 'RFID id [RFID_1] is blocked'],
    ['My payment id is 12345678 and it failed', 'My payment id is [PAYMENT_ID_1] and it failed'],
    ['Transaction id: TX-55AA failed', 'Transaction id [TRANSACTION_ID_1] failed'],
    ['Card RFID ABCDEFGH was declined', 'Card RFID [RFID_1] was declined'],
    ['See transaction id ABCD-EFGH for details', 'See transaction id [TRANSACTION_ID_1] for details'],
    ['Stripe payment id pi_3NxYzAbCdEf failed', 'Stripe payment id [PAYMENT_ID_1] failed'],
    ['The payment id missing from the receipt was never sent.', 'The payment id missing from the receipt was never sent.'],
    ['Your RFID card is rejected and the RFID token expired.', 'Your RFID card is rejected and the RFID token expired.'],
    ['payment id: ref PAY-99821 failed', 'payment id ref [PAYMENT_ID_1] failed'],
    ['RFID, card 04A1B2C3D4 was declined', 'RFID card [RFID_1] was declined'],
    ['RFID: deadbeef was declined', 'RFID [RFID_1] was declined'],
    ['payment id: abcd1234 was declined', 'payment id [PAYMENT_ID_1] was declined'],
    ['My RFID deadbeef01 stopped', 'My RFID [RFID_1] stopped'],
    ['RFID: token RFID-DEMO-A19 failed', 'RFID token [RFID_1] failed'],
    ['connector 2 accepted payment reference PAY-DEMO-7319 for session SESSION-DEMO-88421 at 14:06 UTC', 'connector 2 accepted payment reference [PAYMENT_ID_1] for session SESSION-DEMO-88421 at 14:06 UTC'],
    ['the statement contains payment references PAY-DEMO-1004 and PAY-DEMO-1005 for the same CAD 18.40 amount.', 'the statement contains payment references [PAYMENT_ID_1] and [PAYMENT_ID_2] for the same CAD 18.40 amount.'],
    ['payment ref PAY-1001, PAY-1002 or PAY-1001 at CHG-CA-1042', 'payment ref [PAYMENT_ID_1], [PAYMENT_ID_2] or [PAYMENT_ID_1] at CHG-CA-1042'],
    ['payment reference PAY-DEMO-7319 and CHG-CA-1042 failed', 'payment reference [PAYMENT_ID_1] and CHG-CA-1042 failed'],
    ['RFID: declined at every charger', 'RFID: declined at every charger'],
    ['RFID: blocked', 'RFID: blocked'],
    ['payment id: failed again', 'payment id: failed again'],
    ['payment id: duplicate charge', 'payment id: duplicate charge'],
    ['Transaction ID: refunded twice', 'Transaction ID: refunded twice'],
    ['payment reference: pending', 'payment reference: pending'],
    ['RFID #declined', 'RFID #declined'],
    ['RFID: blocked again', 'RFID: blocked again'],
    ['RFID card 04A1B2C3D4 and 2024 sessions', 'RFID card [RFID_1] and 2024 sessions'],
    ['transaction id 55123, 2026-11-22', 'transaction id [TRANSACTION_ID_1], 2026-11-22'],
    ['transaction id 55123, 55124 and 55125 failed', 'transaction id [TRANSACTION_ID_1], [TRANSACTION_ID_2] and [TRANSACTION_ID_3] failed'],
    ['payment ref PAY-1001 and CHG-1042 failed', 'payment ref [PAYMENT_ID_1] and CHG-1042 failed'],
    ['payment ref PAY-1001 was retried, then PAY-1002 posted', 'payment ref [PAYMENT_ID_1] was retried, then PAY-1002 posted'],
    ['RFID: card rejected at the site', 'RFID: card rejected at the site'],
    ['The payment reference was missing', 'The payment reference was missing'],
    ['My RFID payment reference PAY-DEMO-7319 was declined', 'My RFID payment reference [PAYMENT_ID_1] was declined'],
    ['RFID: payment id PAY-99821', 'RFID: payment id [PAYMENT_ID_1]'],
    ['RFID transaction id TX-55AA failed', 'RFID transaction id [TRANSACTION_ID_1] failed'],
    ['Payment reference: RFID 04A1B2C3D4', 'Payment reference: RFID [RFID_1]'],
    ['PAYMENT ID: TRANSACTION ID TX-1234', 'PAYMENT ID: TRANSACTION ID [TRANSACTION_ID_1]'],
    ['payment id is missing and RFID 04A1B2C3D4 fails', 'payment id is missing and RFID [RFID_1] fails'],
  ])('%s', (input, expected) => {
    expect(message(input)).toBe(expected)
  })

  it('numbers placeholders across subject and message and keeps ordinary references', () => {
    const redacted = redactTicket('Contact reviewer@example.test about payment ID PAY-99821',
      'reviewer@example.test called +1 555-010-0188. Card 4111 1111 1111 1111. Payment ID PAY-99821 and RFID RFID-DEMO-A19 were rejected.')
    expect(redacted.subject.text).toBe('Contact [EMAIL_1] about payment ID [PAYMENT_ID_1]')
    for (const placeholder of ['[EMAIL_1]', '[PHONE_1]', '[CARD_1]', '[PAYMENT_ID_1]', '[RFID_1]']) expect(redacted.message.text).toContain(placeholder)
    expect(message('Charger CHG-CA-1042 did not start SESSION-DEMO-88421 on connector 2.')).toBe('Charger CHG-CA-1042 did not start SESSION-DEMO-88421 on connector 2.')
  })

  it('keeps the seeded RFID ticket readable and redacts its token once', () => {
    const redacted = message('Demo account ACCT-DEMO-882 uses RFID token RFID-DEMO-A19. The token worked yesterday but authorization was rejected at CHG-CA-6009 today with status Invalid, while mobile-app authorization still works.')
    expect(redacted.startsWith('Demo account ACCT-DEMO-882 uses RFID token [RFID_1]. The token worked')).toBe(true)
    expect(redacted).not.toContain('[RFID_2]')
  })
})
