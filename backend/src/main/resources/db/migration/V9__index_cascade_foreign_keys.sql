-- Indexes for the foreign keys that the expired-workspace purge cascades through and that had none. Without them,
-- each deleted workspace, ticket and job makes Postgres scan the whole referencing table, so the cost of one purge
-- batch grows with the square of the data, which is worst exactly when an abuser has filled the database.
-- Expand step only: new indexes, nothing dropped or changed, safe while the previous version serves traffic.
-- Plain CREATE INDEX (not CONCURRENTLY, which cannot run inside Flyway's transaction): the tables are small, so the
-- brief write lock is short. Fail fast instead of waiting behind a long transaction; Flyway then rolls this
-- migration back and the new version does not start, while the old version keeps serving.
SET LOCAL lock_timeout = '5s';

CREATE INDEX IF NOT EXISTS idx_outbox_event_workspace ON outbox_event (workspace_id);
CREATE INDEX IF NOT EXISTS idx_outbox_event_ticket ON outbox_event (ticket_id);
CREATE INDEX IF NOT EXISTS idx_triage_job_replayed_from ON triage_job (replayed_from_job_id)
    WHERE replayed_from_job_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_triage_feedback_ticket ON triage_feedback (ticket_id);
