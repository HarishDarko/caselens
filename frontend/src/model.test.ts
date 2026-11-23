import { describe, expect, it } from 'vitest'
import { applyReview, type Ticket } from './model'

const ticket = (resultId: string | null): Ticket => ({
  id: 'CL-1', backendId: 'ticket-1', subject: 'Subject', message: 'Message', channel: 'EMAIL', scenarioKey: null, job: null, review: null, reviewsLogged: 0,
  result: resultId ? { id: resultId } as Ticket['result'] : null,
})

describe('applyReview', () => {
  it('records a review on the result it was made on', () => {
    const [updated] = applyReview([ticket('result-recorded')], 'ticket-1', 'result-recorded', { decision: 'ACCEPTED', urgency: null, note: null })
    expect(updated.review).toEqual({ decision: 'ACCEPTED', urgency: null, note: null })
    expect(updated.reviewsLogged).toBe(1)
  })

  it('leaves a newer result unreviewed when the save for the old one finishes late', () => {
    const [updated] = applyReview([ticket('result-live')], 'ticket-1', 'result-recorded', { decision: 'CORRECTED', urgency: 'HIGH', note: null })
    expect(updated.review).toBeNull()
    expect(updated.reviewsLogged).toBe(0)
  })
})
