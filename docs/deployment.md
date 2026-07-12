# Deployment preparation

## What is ready

- GitHub Actions validates the backend, frontend, browser journey, Terraform,
  containers, and high/critical image vulnerabilities.
- Terraform defines private S3/CloudFront frontend delivery, SQS/DLQ,
  Secrets Manager placeholder, IAM, CloudWatch, API Gateway, and optional
  Lambda resources. The shared Java 21 Lambda artifact contains stream handlers
  for API Gateway HTTP API v2, SQS worker batches, and the scheduled outbox
  relay.
- The Lambda profile is reproducible with `backend\\mvnw.cmd -B -ntp -Plambda
  -DskipTests package` on Windows or `./mvnw -B -ntp -Plambda -DskipTests
  package` on Linux. It produces `backend/target/caselens-lambda.jar`.
- Lambda uses an atomic PostgreSQL rate-limit bucket in Neon, while local
  development keeps the in-memory limiter for a dependency-light workflow.
- The static frontend build accepts `VITE_API_BASE_URL`, and Lambda API CORS
  accepts the configured `web_origins` list.

## Local readiness evidence

Verified on 2026-07-12 from `feat/production-proof`:

- Terraform format check, provider initialization with the committed lockfile,
  and `terraform validate` passed.
- Backend and frontend production images built successfully.
- The backend image declares non-root UID `10001`.
- The frontend image served the built reviewer console with HTTP 200 from a
  temporary local container.
- Backend regression, frontend unit, lint, typecheck, build, and Playwright
  checks passed; see `docs/verification.md` for the current counts.

## Required external setup

Before a production apply, configure all of the following:

1. AWS account and `ca-central-1` region bootstrap.
2. GitHub Actions OIDC provider and a repository/branch-scoped deploy role.
3. Repository variable `CASELENS_API_BASE_URL` for the frontend publish job.
4. Terraform `web_origins` containing the deployed CloudFront/custom origin.
5. A Neon PostgreSQL project in the selected region/plan, with TLS enabled,
   connection pooling where appropriate, migrations, and a documented backup/
   restore policy.
6. Runtime secret values in the Terraform-created Secrets Manager secret. Store
   a JSON object with only the application keys required by the active provider,
   for example:

   ```json
   {
     "SPRING_DATASOURCE_URL": "jdbc:postgresql://<neon-host>/<database>?sslmode=require",
     "SPRING_DATASOURCE_USERNAME": "<neon-role>",
     "SPRING_DATASOURCE_PASSWORD": "<neon-password>",
     "CASELENS_DEMO_PASSCODE": "<local-generated-passcode>",
     "CASELENS_SESSION_SECRET": "<long-random-secret>",
     "CASELENS_AI_PROVIDER": "groq",
     "GROQ_API_KEY": "<groq-key>"
   }
   ```

   Never put real values in Git, task reports, screenshots, or chat. The Lambda
   runtime loads only an explicit allowlist from this secret.
7. The repository variable `CASELENS_ARTIFACT_BUCKET` for the CI publication
   job, alongside the existing frontend bucket, CloudFront distribution, API
   base URL, and AWS region variables.
   Set `CASELENS_LAMBDA_DEPLOY_ENABLED` to `true` only after the Lambda
   functions exist, and set `CASELENS_LAMBDA_FUNCTIONS` to their exact comma-
   separated names. The manual workflow then updates each function to the
   versioned object; leaving the flag unset safely publishes only the artifact.
8. A reviewed Terraform plan with `deploy_compute=true` and the artifact
   bucket after the artifact has been published. Terraform creates/configures
   the functions; the explicit CI runtime-update step owns subsequent code
   versions, while S3 versioning preserves rollback targets. Compute remains
   disabled by default until Neon connectivity and the secret are verified.

No cloud resource has been created by this deployment task. The first cloud
mutation should be a reviewed Terraform plan followed by explicit approval.
