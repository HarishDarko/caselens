-- Recorded results, expand step only (the triage_result half; the feedback half is V12, so that no one transaction
-- holds locks on both tables). Safe across the deploy gap in both directions: the new column has a constant default
-- (a catalog change in Postgres 11+, no table rewrite), so the previous application version keeps inserting results
-- (recorded = false) without knowing it, and every row written before this migration reads as a live result. A relaxed
-- NOT NULL only allows more.
-- Fail fast instead of waiting behind a long transaction; Flyway then rolls this migration back and the new version
-- does not start, while the old version keeps serving.
SET LOCAL lock_timeout = '5s';

-- A recorded result is a real captured AI result seeded with the workspace: no outbox event, job or invocation.
ALTER TABLE triage_result ADD COLUMN recorded BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE triage_result ALTER COLUMN event_id DROP NOT NULL;
-- Only a recorded result may lack the event; a live result is always the outcome of one queued event. Added NOT VALID
-- so this step does not scan the table under its lock; V13 validates it.
ALTER TABLE triage_result ADD CONSTRAINT chk_triage_result_live_event CHECK (recorded OR event_id IS NOT NULL) NOT VALID;
