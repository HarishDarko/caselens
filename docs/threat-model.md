# CaseLens threat model

## Assets

- demo session tokens and workspace identifiers;
- synthetic ticket text, triage results, and reviewer feedback;
- provider credentials and deployment credentials;
- queue messages, failure timelines, and operational metrics.

## Main abuse paths and controls

| Abuse path | Control | Residual risk |
| --- | --- | --- |
| Read another reviewer’s tickets | Signed token includes workspace; every repository lookup is workspace-scoped | A future endpoint must preserve the same repository pattern |
| Reuse an expired demo token | Expiry and live workspace existence are checked during authentication | Token revocation is intentionally limited to workspace cleanup |
| Flood session or triage endpoints | Per-IP session and per-workspace triage limits with `Retry-After` | In-memory limits reset on process restart and are not a distributed limiter |
| Put instructions in ticket text | Delimited untrusted-data prompt, boundary escaping, redaction, exact-evidence validation | Provider behavior can still be wrong; the rules fallback remains authoritative |
| Exfiltrate secrets through logs | API keys and raw provider payloads are never logged; structured values are sanitized and length-capped | Infrastructure access to application logs still requires normal IAM controls |
| Publish a poisoned queue message | Queue payload contains identifiers only; schema and workspace checks happen before processing | A compromised queue role remains an infrastructure incident |
| Retry the same job twice | Conditional claims, active-job uniqueness, and persisted attempt state | External provider side effects are avoided because triage calls are read-only |
| Leave abandoned demo data forever | Scheduled cleanup deletes expired workspaces and cascaded data | Cleanup cadence is eventual, not immediate |

## Out of scope for the demo

CaseLens does not accept real customer authentication, payment data, OCPP
traffic, or production helpdesk webhooks. Those integrations would require a
separate identity model, data-retention policy, secret rotation, audit design,
and provider-specific threat review.
