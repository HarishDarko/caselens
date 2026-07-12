# Technical discussion notes

## Why use an outbox?

The API commits the ticket and triage job together with an outbox event. A
separate relay publishes that event to SQS and records publication afterward.
This makes a database commit durable even if the queue is temporarily
unavailable, and it leaves recovery evidence instead of silently losing work.

## Why is priority deterministic?

Priority is an operational decision, so it is calculated from explicit urgency,
SLA risk, impact, repeat-contact, and general-request rules. The model can
explain and recommend, but it cannot directly choose the numeric score.

## How is bad model output handled?

Ticket text is redacted before submission. The response must match a closed
schema, use known policy IDs, and cite exact ticket substrings. Invalid output,
transport failures, and provider timeouts are recorded and routed to a
rules-based fallback with low reliability.

## What does idempotency protect?

Workers conditionally claim a job, persist attempt state, and keep the original
result immutable. Duplicate queue delivery therefore does not produce two
competing completed results.

## What would change for production?

Use a real identity provider, a managed PostgreSQL topology, distributed rate
limits, secret rotation, database connectivity planning for Lambda, stronger
audit retention, and provider-specific data-processing controls. Those are
deliberately not implied by this synthetic portfolio demo.
