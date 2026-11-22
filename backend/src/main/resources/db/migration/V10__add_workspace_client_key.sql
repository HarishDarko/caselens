-- Per-client share of the daily AI budget, expand step only. Safe while the previous application version still
-- serves traffic: the column is nullable with no default, so the old code's inserts still work and nothing is
-- rewritten. Workspaces from before this change keep a null key and count against the global budget only.
-- Fail fast instead of waiting behind a long transaction; Flyway then rolls this migration back and the new version
-- does not start, while the old version keeps serving.
SET LOCAL lock_timeout = '5s';

-- An HMAC of the opener's network (see ClientAddresses.clientKey), never the address itself.
ALTER TABLE demo_workspaces ADD COLUMN client_key VARCHAR(64);
