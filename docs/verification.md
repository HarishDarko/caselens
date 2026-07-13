# Verification record

Verified locally on 2026-07-12 from `feat/production-proof`.

| Area | Evidence |
| --- | --- |
| Backend | 114 tests passed; 0 failures, errors, or skips; PostgreSQL and LocalStack Testcontainers included |
| Frontend | 11 Vitest tests passed; lint, typecheck, and production build passed |
| Browser | 2 Playwright journeys passed; 2 project-specific skips are intentional |
| Local API | Neon migrations V1-V5 applied; a LocalStack SQS round-trip persisted a result in Neon |
| Providers | Live Groq returned an `AI_VALIDATED` result with model `openai/gpt-oss-20b`; provider adapter tests cover Groq, Gemini, and deterministic mode |
| Failure proof | The fixed `provider-retry-demo` records one retryable timeout on the original job; policy and worker tests prove other scenarios, later attempts, and replay jobs cannot trigger it |
| Terraform | `fmt -check`, provider initialization with the lockfile, application and bootstrap `terraform validate`, read-only base/compute plans, the SnapStart alias test (1 passed), and bootstrap state-bucket/destroy-role plan passed; destroy workflow YAML and safety guards parsed locally |
| AWS deployment | Terraform converged after provisioning CloudFront/S3, API Gateway, three Java 21 Lambdas, SQS/DLQ, EventBridge, IAM, Secrets Manager, logs, and alarms in `ca-central-1`; API traffic uses the `live` alias for published SnapStart versions |
| Deployed smoke | CloudFront returned HTTP 200 with CSP and HSTS; API health returned `UP`; Neon PostgreSQL 16.14 connected and Flyway validated migrations V1-V5 |
| Deployed journey | A synthetic ticket completed through API Gateway, Neon, the transactional outbox, Amazon SQS, and Groq in one worker attempt; the stored decision was `AI_VALIDATED` with model `openai/gpt-oss-20b` |
| Login latency | SnapStart version 10 returned a real first restored login in 7.0 seconds and the next two in 183-215 ms client-observed; Lambda warm execution was about 64 ms, with stale checkpointed database connections eliminated |
| Containers | Backend and frontend multi-stage images built successfully; backend runs as UID 10001; frontend container smoke returned HTTP 200 |
| Privacy | Secret-pattern scan found only explicit synthetic test configuration; `.env`, history notes, continuation docs, Terraform state, and generated dependencies remain ignored |

Live reviewer URL: [https://d27d60ya5pvyzq.cloudfront.net](https://d27d60ya5pvyzq.cloudfront.net).
