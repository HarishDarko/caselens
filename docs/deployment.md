# Deployment preparation

## What is ready

- GitHub Actions validates the backend, frontend, browser journey, Terraform,
  containers, and high/critical image vulnerabilities.
- Terraform defines private S3/CloudFront frontend delivery, SQS/DLQ,
  Secrets Manager placeholder, IAM, CloudWatch, API Gateway, and optional
  Lambda resources.
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
5. A managed PostgreSQL provider, network path, migrations, and backup policy.
6. Runtime secret values in Secrets Manager, including the demo/session and
   provider configuration required by the selected environment.
7. A reviewed Lambda packaging path and handlers. `deploy_compute` remains
   `false` by default because the current Spring Boot artifact is verified as a
   long-running local service, not yet as a Lambda-native handler artifact.

The local AWS CLI currently has no credentials, so no Terraform plan, cloud
resource, paid action, or deployment was attempted. The first cloud action
should be a reviewed Terraform plan followed by explicit approval.
