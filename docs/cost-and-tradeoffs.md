# Cost and operational trade-offs

## Local development

The reviewed local path uses Neon PostgreSQL, LocalStack SQS, and Groq. Tests
use disposable PostgreSQL/LocalStack containers and a deterministic provider,
which keeps routine verification repeatable.

## Deployed AWS shape

- S3 and CloudFront for the private static frontend;
- API Gateway and Java 21 Lambda for the API surface;
- SQS and a DLQ for asynchronous triage;
- EventBridge for a 30-minute outbox recovery sweep;
- CloudWatch logs and alarms;
- Secrets Manager for runtime values;
- Neon managed PostgreSQL.

This shape keeps the always-on footprint small, but it moves complexity into
deployment packaging, database connectivity, cold-start behavior, and
observability. The deployed environment uses versioned Lambda aliases while
Terraform keeps compute disabled by default for new environments.

## Cost guardrails

- Do not apply Terraform without reviewing the plan and enabling only the
  resources needed for the demo.
- Keep the frontend bucket private and delete the environment when it is no
  longer needed.
- Use deterministic mode for routine automated testing; use Groq for the
  reviewer demo and final integration checks.
- Set a small model request timeout and bounded retry count; provider failures
  must fall back instead of creating an unbounded bill.
- Add budget alerts and review CloudWatch retention before a public launch.

The repository intentionally does not claim a precise monthly total. The
managed database, traffic, model usage, AWS account pricing, and retention
choices determine that number.

## Idle compute trade-off

Immediate publication after the transaction commits is the normal queue path.
The scheduled relay is deliberately a 30-minute recovery check, not a frequent
poller. A previous once-per-minute check kept Neon active and exhausted its free
compute allowance by denying the database its five-minute idle window. The
observed exhaustion was not caused by reviewer traffic or storage growth.
