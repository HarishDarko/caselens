-- Validates the check V12 added NOT VALID, in its own transaction and apart from triage_result (V13): VALIDATE
-- CONSTRAINT scans the table but only takes SHARE UPDATE EXCLUSIVE, so reads and writes keep going.
-- Fail fast instead of waiting behind a long transaction.
SET LOCAL lock_timeout = '5s';

ALTER TABLE triage_feedback VALIDATE CONSTRAINT chk_feedback_decision;
