# Deployment preparation

## What is ready

- GitHub Actions validates the backend, frontend, browser journey, Terraform,
  containers, and high/critical image vulnerabilities.
- Terraform defines private S3/CloudFront frontend delivery, SQS/DLQ,
  Secrets Manager placeholder, IAM, CloudWatch, API Gateway, and optional
  Lambda resources.
- The static frontend build accepts `VITE_API_BASE_URL`, and Lambda API CORS
  accepts the configured `web_origins` list.

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

No cloud resource or paid deployment action is performed by local verification.
The first apply should be a reviewed Terraform plan followed by an explicit
approval.
