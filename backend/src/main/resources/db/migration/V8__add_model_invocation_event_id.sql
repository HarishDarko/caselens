-- Model invocations name the triage job (by its outbox event id) they were made for, so a replayed job keeps its
-- own provider telemetry instead of relabelling the job it replaced. Expand step only: one nullable column with no
-- default and no backfill. In Postgres that is a catalog change, not a table rewrite. The previous application
-- version keeps inserting rows without the column, and rows written before this migration stay NULL; the
-- operations view matches those by time window.
-- Fail fast instead of waiting behind a long transaction; Flyway then rolls this migration back and the new
-- version does not start, while the old version keeps serving.
SET LOCAL lock_timeout = '5s';

ALTER TABLE model_invocations ADD COLUMN event_id UUID;
