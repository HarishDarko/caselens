-- Industry packs, expand step only. Safe while the previous application version still serves traffic:
-- nothing is dropped or renamed except two CHECK constraints, and dropping a CHECK only allows more values.
-- Fail fast instead of waiting behind a long transaction; Flyway then rolls this migration back and the new
-- version does not start, while the old version keeps serving.
SET LOCAL lock_timeout = '5s';

-- Every workspace has an industry. Existing rows, and inserts from the old code that do not name the column,
-- get 'ev-charging', the only industry the old code knows. A constant default does not rewrite the table.
ALTER TABLE demo_workspaces ADD COLUMN industry VARCHAR(32) NOT NULL DEFAULT 'ev-charging';

-- Categories are now checked against the workspace's industry pack in the application, so the database no longer
-- limits these two columns to the EV category names.
ALTER TABLE triage_feedback DROP CONSTRAINT IF EXISTS chk_feedback_category;
ALTER TABLE evaluation_ground_truth DROP CONSTRAINT IF EXISTS chk_ground_truth_category;
