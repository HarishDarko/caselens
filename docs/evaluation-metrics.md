# CaseLens evaluation metrics

The evaluation endpoint is workspace-scoped and reads persisted CaseLens
events. It does not use fabricated dashboard values.

- `categoryAgreementRate` and `urgencyAgreementRate` compare the original AI
  result with the seeded category and urgency for the synthetic scenario.
- `agreementRate` requires both fields to match the seeded ground truth.
- `correctionRate` is the share of live triage results (the same population as
  `triageResults`) whose latest feedback changes at least one supplied field.
  Recorded results are left out of both the numerator and the denominator.
  Same-value submissions are rejected.
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

Recorded results are real model answers captured earlier and seeded with each
workspace (three per industry, `recorded = true`, no event, job or model call).
They are measured and reviewed like any result, but they are not live runs:

- `recordedResults` counts them. `triageResults`, `medianLatencyMs`,
  `p95LatencyMs`, `correctionRate`, `failureRate`, `providerUsage` and
  `recentEvents` are live only, and the Operations queue, fallback and provider figures leave them out.
- `agreementRate` and its two parts include them (they are scored against the
  same ground truth), so a new workspace already has a benchmark figure.
- `reviewedResults` counts results with a review, `acceptedResults` those whose
  latest review is `ACCEPTED`, `acceptanceRate` is accepted divided by reviewed
  and `overrideRate` is corrected divided by reviewed.
- An event in `recentEvents` has `latencyMs` of `null` when it has no job to
  measure from.
- A figure with nothing to measure is `null`, never 0: no latency before a live
  run, no rate before a case or a review.

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
