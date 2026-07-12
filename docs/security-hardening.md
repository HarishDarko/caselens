# Security and operational hardening

CaseLens uses a signed, short-lived demo session tied to a persisted workspace.
Every ticket, triage job, result, feedback record, and recovery query is
scoped through that workspace. A valid signature alone is not enough: the
workspace must still exist and be active.

The public API contract also applies bounded input limits, a per-client demo
session rate limit, and a per-workspace triage rate limit. Responses use generic
problem details so authentication, storage, and provider internals are not
disclosed.

Ticket content is untrusted data. It is redacted before provider submission,
delimited in the prompt, and explicitly excluded from instructions. Structured
triage output is validated for schema, evidence traceability, policy IDs, and
deterministic priority rules. A rules fallback keeps the case observable when
the primary provider fails.

Operational logs contain identifiers and sanitized status/error codes, not raw
ticket text or secrets. Actuator liveness and readiness probes are separate,
and expired demo workspaces are cleaned on a scheduled transaction.
