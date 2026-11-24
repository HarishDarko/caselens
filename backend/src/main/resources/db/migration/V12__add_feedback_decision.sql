-- The reviewer's decision, expand step only (the triage_feedback half of what V11 started). Safe across the deploy gap
-- in both directions: the new column has a constant default, so the previous application version keeps inserting
-- corrections (decision = CORRECTED) without knowing it, and every row written before this migration reads as a
-- correction. The replaced check accepts every row the old check accepted.
-- Fail fast instead of waiting behind a long transaction; Flyway then rolls this migration back and the new version
-- does not start, while the old version keeps serving.
SET LOCAL lock_timeout = '5s';

-- ACCEPTED keeps the AI's call and changes nothing; CORRECTED changes at least one field. A note is optional for both.
ALTER TABLE triage_feedback ADD COLUMN decision VARCHAR(16) NOT NULL DEFAULT 'CORRECTED';
ALTER TABLE triage_feedback ALTER COLUMN note DROP NOT NULL;
ALTER TABLE triage_feedback DROP CONSTRAINT chk_feedback_has_correction;
-- Added NOT VALID so this step does not scan the table under its lock; V14 validates it.
ALTER TABLE triage_feedback ADD CONSTRAINT chk_feedback_decision CHECK (
    (decision = 'ACCEPTED' AND corrected_category IS NULL AND corrected_urgency IS NULL AND corrected_sla_risk IS NULL)
    OR (decision = 'CORRECTED'
        AND (corrected_category IS NOT NULL OR corrected_urgency IS NOT NULL OR corrected_sla_risk IS NOT NULL))
) NOT VALID;
