# CaseLens incident response

This runbook describes the smallest safe response loop for the CaseLens proof.
It is intentionally written around synthetic data and bounded demo resources.

## Severity guide

| Signal | Initial severity | First question |
| --- | --- | --- |
| Readiness is down | High | Is PostgreSQL reachable and migrated? |
| Queue depth or outbox failures rise | Medium | Can the relay publish without duplicating work? |
| Terminal triage failures rise | Medium | Is the provider response invalid or unavailable? |
| Rate-limit responses rise | Low | Is the demo being exercised beyond its safe budget? |

## Response loop

1. Establish the time window, affected component, and correlation identifiers.
2. Check liveness, readiness, queue/DLQ depth, outbox publication, and triage
   latency in that order.
3. Preserve the original ticket and failure timeline. CaseLens is designed to
   make a human decision possible while recovery is in progress.
4. Apply the narrowest reversible recovery: retry a bounded job, restore a
   dependency, or pause the demo worker. Avoid destructive data changes.
5. Verify the metric returned to baseline and write a short evidence note with
   synthetic IDs only.

## Privacy boundary

Logs and dashboards may contain correlation, workspace, ticket, job, and event
identifiers. They must not contain ticket bodies, customer contact data,
payment data, bearer tokens, API keys, or raw provider responses.
