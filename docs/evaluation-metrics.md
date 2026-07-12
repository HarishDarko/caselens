# CaseLens evaluation metrics

The evaluation endpoint is workspace-scoped and reads persisted CaseLens
events. It does not use fabricated dashboard values.

- `categoryAgreementRate` and `urgencyAgreementRate` compare the original AI
  result with the seeded category and urgency for the synthetic scenario.
- `agreementRate` requires both fields to match the seeded ground truth.
- `correctionRate` is the share of stored triage results whose latest feedback
  changes at least one supplied field. Same-value submissions are rejected.
- `medianLatencyMs` and `p95LatencyMs` measure end-to-end persisted processing
  time from triage-job creation to result creation. Provider-call latency is
  separately persisted in `model_invocations` for operational analysis.
- `failureRate` is terminal failures divided by final jobs (`COMPLETED` plus
  `TERMINAL_FAILURE`). Queued, processing, and retryable jobs are in flight and
  are excluded until they reach a final state.
- `providerUsage` aggregates every persisted model invocation for the scoped
  result tickets, including provider retries. Null token counts are treated as
  zero; the summary is cumulative for the workspace rather than a cost quote.
- `recentEvents` is capped at 20 and ordered newest-first. Each event exposes
  the result timestamp, persisted latency, provider/model metadata, decision
  source, whether ground truth exists, and whether the latest reviewer
  feedback changed the result.

The Operations view uses a separate workspace-scoped overview endpoint:

- queue counts are derived from persisted triage jobs;
- fallback results are counted from `RULES_FALLBACK` results;
- provider failures are counted from non-success model invocations;
- `recent` is capped at 20 and ordered newest-first, with job status, attempts,
  provider/model metadata, error code, and latency;
- provider health aggregates requests, successes, failures, token counts, and
  average invocation latency.

Neither observability response includes ticket subject/message content or raw
provider payloads.

Ground truth is seeded by Flyway from the eight synthetic scenario definitions.
Feedback rows reference the original result and are appended; the API has no
mutation path for either the result or prior feedback.
