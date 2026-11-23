-- Recorded results and the reviewer's decision, expand step only. Safe across the deploy gap in both directions:
-- every new column has a constant default (a catalog change in Postgres 11+, no table rewrite), so the previous
-- application version keeps inserting results (recorded = false) and corrections (decision = CORRECTED) without
-- knowing the columns, and every row written before this migration reads the same way. Relaxed NOT NULLs only allow
-- more; the replaced feedback check accepts every row the old check accepted.
-- Fail fast instead of waiting behind a long transaction; Flyway then rolls this migration back and the new version
-- does not start, while the old version keeps serving.
SET LOCAL lock_timeout = '5s';

-- A recorded result is a real captured AI result seeded with the workspace: no outbox event, job or invocation.
ALTER TABLE triage_result ADD COLUMN recorded BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE triage_result ALTER COLUMN event_id DROP NOT NULL;
-- Only a recorded result may lack the event; a live result is always the outcome of one queued event.
ALTER TABLE triage_result ADD CONSTRAINT chk_triage_result_live_event CHECK (recorded OR event_id IS NOT NULL) NOT VALID;
ALTER TABLE triage_result VALIDATE CONSTRAINT chk_triage_result_live_event;

-- ACCEPTED keeps the AI's call and changes nothing; CORRECTED changes at least one field. A note is optional for both.
ALTER TABLE triage_feedback ADD COLUMN decision VARCHAR(16) NOT NULL DEFAULT 'CORRECTED';
ALTER TABLE triage_feedback ALTER COLUMN note DROP NOT NULL;
ALTER TABLE triage_feedback DROP CONSTRAINT chk_feedback_has_correction;
ALTER TABLE triage_feedback ADD CONSTRAINT chk_feedback_decision CHECK (
    (decision = 'ACCEPTED' AND corrected_category IS NULL AND corrected_urgency IS NULL AND corrected_sla_risk IS NULL)
    OR (decision = 'CORRECTED'
        AND (corrected_category IS NOT NULL OR corrected_urgency IS NOT NULL OR corrected_sla_risk IS NOT NULL))
) NOT VALID;
ALTER TABLE triage_feedback VALIDATE CONSTRAINT chk_feedback_decision;
