# Cost and operational trade-offs

## Local development

The normal local path uses PostgreSQL and LocalStack in Docker plus the
deterministic mock provider. This avoids cloud spend and model calls while
still exercising persistence, the outbox, SQS semantics, retries, feedback,
and evaluation. The Groq and Gemini paths are opt-in through `.env` and should
be used for occasional integration verification only.

## Proposed AWS shape

- S3 and CloudFront for the private static frontend;
- API Gateway and Java 21 Lambda for the API surface;
- SQS and a DLQ for asynchronous triage;
- EventBridge for relay recovery;
- CloudWatch logs and alarms;
- Secrets Manager for runtime values;
- a managed PostgreSQL provider selected before compute is enabled.

This shape keeps the always-on footprint small, but it moves complexity into
deployment packaging, database connectivity, cold-start behavior, and
observability. The Terraform defaults keep compute disabled until the Lambda
artifacts and database path are ready.

## Cost guardrails

- Do not apply Terraform without reviewing the plan and enabling only the
  resources needed for the demo.
- Keep the frontend bucket private and delete the environment when it is no
  longer needed.
- Use the mock provider for screenshots and routine testing.
- Set a small model request timeout and bounded retry count; provider failures
  must fall back instead of creating an unbounded bill.
- Add budget alerts and review CloudWatch retention before a public launch.

The repository intentionally does not claim a precise monthly total. The
managed database, traffic, model usage, AWS account pricing, and retention
choices determine that number.
