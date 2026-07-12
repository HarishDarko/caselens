# Triage failure and DLQ runbook

CaseLens treats queue failures as observable states rather than silently
discarding tickets. The workflow below is for the synthetic demo deployment;
it does not authorize access to customer systems or production OCPP/payment
records.

## Signals

- `caselens.triage.terminal.failure` increases when a job cannot be completed
  after bounded retries.
- `caselens.triage.retryable.failure` indicates work that can be retried.
- The operations API exposes failed jobs for the signed demo workspace.
- The SQS dead-letter queue is the durable handoff for messages that exhaust
  the configured receive limit.

## Response

1. Confirm the alarm window and capture the correlation ID, job ID, and event
   ID. Never copy ticket bodies, tokens, API keys, or provider responses into
   an incident record.
2. Check whether the failure is provider, schema, database, or queue related.
   Use the sanitized error code and processing timeline, not raw payloads.
3. If the dependency is healthy, retry one representative synthetic job from
   the operations view and confirm that its status and attempt timeline move.
4. If the dependency is unhealthy, leave the job visible, stabilize the
   dependency, and retry only after the failure mode is understood.
5. Record the outcome and watch the terminal-failure and queue-depth signals
   return to baseline.

## Recovery guardrails

- Retries are workspace-scoped and idempotent.
- A failed original ticket remains readable while its job is recovered.
- Do not replay an unknown message by editing its payload.
- Do not disable validation, logging redaction, or the DLQ to make a demo
  green.
