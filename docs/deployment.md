# AWS deployment

The CaseLens demo is deployed in AWS `ca-central-1`. The reviewer URL is
[https://d27d60ya5pvyzq.cloudfront.net](https://d27d60ya5pvyzq.cloudfront.net).

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
- The API Lambda publishes immutable SnapStart versions behind a Terraform-
  managed `live` alias. API Gateway invokes only that alias. Operators wait for
  AWS optimization, verify the version, and then pass its number through
  `api_live_version`; publishing alone cannot move production traffic.
- The Lambda database pool is suspended and its connections are evicted before
  a SnapStart checkpoint. It resumes without reusing a checkpointed Neon
  socket, avoiding stale-connection failures after restore.

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

## Deployment configuration

The deployed environment uses the following configuration:

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
8. A reviewed Terraform plan with `deploy_compute=true`, the artifact bucket,
   and `api_live_version` set to the published version after AWS reports its
   SnapStart optimization as `On`. Terraform creates/configures
   the functions; the explicit CI runtime-update step owns subsequent code
   versions, while S3 versioning preserves rollback targets. Compute remains
   disabled by default until Neon connectivity and the secret are verified.

The base plan added 26 resources with no updates or deletions. The compute plan
added 13 resources, updated one CloudFront setting, and deleted nothing. Final
Terraform verification reported no pending changes. The public health check and
an authenticated synthetic Groq/SQS journey passed after deployment.

On 2026-07-12, the live API alias was advanced to SnapStart-optimized version
10 with a Terraform plan of `0 to add, 1 to change, 0 to destroy`. A real login
measured 7.0 seconds on the first restored request and 178-213 milliseconds on
the next two client-observed requests. Lambda reported 1.1 seconds of restore
time and about 64 milliseconds for each warm invocation. The remaining first-
request time is the fresh Neon connection/query after the checkpointed pool is
discarded; removing it completely would require an always-warm paid compute or
database choice, so it is retained as an explicit cost/latency trade-off.

## Terraform state and teardown

Terraform state is stored in a separate private, versioned S3 bucket so local
runs and GitHub Actions share the same resource inventory. The state bucket is
bootstrapped separately and is deliberately not destroyed with the CaseLens
application stack.

Create the state bucket once with the bootstrap stack, using a globally unique
name:

```powershell
terraform -chdir=infra/terraform/bootstrap init
terraform -chdir=infra/terraform/bootstrap plan `
  -var="bucket_name=<globally-unique-state-bucket>"
terraform -chdir=infra/terraform/bootstrap apply `
  -var="bucket_name=<globally-unique-state-bucket>"
```

Read the bootstrap output and save the destroy role ARN as the protected
GitHub **environment** secret `AWS_DESTROY_ROLE_ARN`:

```powershell
terraform -chdir=infra/terraform/bootstrap output -raw github_destroy_role_arn
```

Then initialize the application stack with that bucket before planning or
applying it:

```powershell
terraform -chdir=infra/terraform init `
  -backend-config="bucket=<globally-unique-state-bucket>" `
  -backend-config="region=ca-central-1"
```

Set the same bucket name as the GitHub repository variable
`CASELENS_TERRAFORM_STATE_BUCKET`. The existing `AWS_DEPLOY_ROLE_ARN` secret
continues to serve normal artifact publication. The separate
`AWS_DESTROY_ROLE_ARN` environment secret is used only by the protected
teardown workflow and is created by the bootstrap stack, outside the
application state that it destroys.

After the demo, open **Actions → CaseLens Terraform Destroy**, run it on
`feat/production-proof`, and type `DESTROY_CASELENS`. The protected `production`
environment approves the workflow before it receives AWS credentials. The
workflow then refuses the wrong branch, wrong confirmation, missing state
configuration, or empty state; it prints the destroy plan and applies only that
exact saved plan. It sets `force_destroy_storage=true` for this explicit
teardown so current and versioned demo bucket objects are removed before bucket
deletion. It removes the application resources but preserves the state bucket
for recovery. Delete the bootstrap stack separately only when the project and
its Terraform history are no longer needed.
