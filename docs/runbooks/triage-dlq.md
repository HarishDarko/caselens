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

## Inspect and redrive the DLQ

The queue sends a message to the DLQ after 5 failed receives (90 s visibility
timeout each, so about 7.5 minutes). Messages carry identifiers only (event,
workspace and ticket IDs plus a correlation ID), never ticket text. Get the
queue URLs with `terraform output triage_queue_url` and
`terraform output triage_dlq_url`; run these with an operator role, not the
CI role.

1. Depth: `aws sqs get-queue-attributes --queue-url <dlq-url> --attribute-names ApproximateNumberOfMessages`.
   The `dlq-depth` alarm fires on the same number.
2. Peek without deleting: `aws sqs receive-message --queue-url <dlq-url> --max-number-of-messages 10 --visibility-timeout 30`.
   Note the event and correlation IDs, then let the messages become visible
   again. Do not delete them by hand.
3. Fix the cause first (database reachable, provider healthy). Then move the
   messages back to the triage queue:
   `aws sqs start-message-move-task --source-arn <dlq-arn> --destination-arn <triage-queue-arn>`,
   and follow it with `aws sqs list-message-move-tasks --source-arn <dlq-arn>`.
4. Each redriven message is safe to process again. The worker claims a job
   that is still QUEUED, RETRYABLE_FAILURE or PROCESSING with a lapsed lease.
   It acknowledges a message as a duplicate, before any provider call, when
   the job is COMPLETED, TERMINAL_FAILURE or already replayed. A message for an
   expired workspace ends as `INVALID_EVENT`.

## Stuck jobs recover from the operations view

A job whose message reached the DLQ can stay in QUEUED (the database was down
before the claim) or PROCESSING (the worker timed out or lost the database
after the claim). Once such a job has not been updated for the full redelivery
window (10 minutes), the operations failures list shows it and its retry
button works, with no DLQ access needed. The retry locks the ticket, closes the
old job and any open attempt as `TERMINAL_FAILURE` with
`SQS_DELIVERY_EXHAUSTED`, and queues one replacement. A double click returns
that replacement. If the original message comes back later (a redrive, or the
outbox relay publishing a row that missed its first publish), the worker
acknowledges it as a duplicate, so the ticket is triaged once.

Use the redrive for a backlog across many workspaces, and the retry button for
one reviewer's ticket. Doing both for the same job is safe: whichever runs
second finds the job finished or replaced and does nothing.
