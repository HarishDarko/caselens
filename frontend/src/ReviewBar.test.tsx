import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { ReviewBar } from './ReviewBar'
import type { Ticket } from './model'

// A reviewer's override note is text a visitor typed. The input is masked in session recordings, but the saved note is
// shown again as plain text, so that element must carry the class that keeps it out of recordings.
describe('the saved override note', () => {
  it('is rendered with the ph-no-capture class', () => {
    const ticket = {
      id: 'T-1', backendId: 'b-1', subject: 's', message: 'm', channel: 'WEB', scenarioKey: null, job: null, reviewsLogged: 1,
      result: { id: 'r-1', urgency: 'LOW', decisionSource: 'AI_VALIDATED', recorded: false },
      review: { decision: 'CORRECTED', urgency: 'HIGH', note: 'typed by the visitor' },
    } as unknown as Ticket
    render(<ReviewBar ticket={ticket} saving={false} actions={{ onAccept: async () => true, onOverride: async () => true }} />)
    const note = screen.getByText(/typed by the visitor/)
    expect(note).toHaveClass('ph-no-capture')
  })
})
