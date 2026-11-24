-- Validates the check V11 added NOT VALID, in its own transaction: VALIDATE CONSTRAINT scans the table but only takes
-- SHARE UPDATE EXCLUSIVE, so reads and writes (including the previous application version's) keep going.
-- Fail fast instead of waiting behind a long transaction.
SET LOCAL lock_timeout = '5s';

ALTER TABLE triage_result VALIDATE CONSTRAINT chk_triage_result_live_event;
