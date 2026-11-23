# AWS deployment

The CaseLens demo is deployed in AWS `ca-central-1`. The primary reviewer URL
is [https://caselens.harishdarko.com](https://caselens.harishdarko.com). The
CloudFront distribution remains available as a fallback at
[https://d27d60ya5pvyzq.cloudfront.net](https://d27d60ya5pvyzq.cloudfront.net).

Cloudflare provides a DNS-only CNAME to the existing CloudFront distribution;
it is not acting as a proxy. The CloudFront custom-domain certificate is issued
by ACM in `us-east-1`, which is the required certificate region for CloudFront.

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
- The SQS event source invokes a separately pinned worker `live` alias. The
  worker resolves its Spring dependencies when Lambda constructs the handler,
  so the initialized application is captured in the SnapStart snapshot instead
  of being built during the first queue delivery.
- The 30-minute recovery schedule invokes a third pinned `live` alias for the
  relay. Like the worker, the relay starts Spring when Lambda constructs the
  handler, so each sweep restores from the snapshot instead of starting the
  application inside the request.
- Immediate outbox publication remains the normal path after the database
  transaction commits. EventBridge is only a 30-minute recovery sweep for
  unpublished outbox rows; it is not the primary delivery mechanism.

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
2. GitHub Actions OIDC provider and a deploy role scoped to this repository's
   protected `production` environment.
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
     "CASELENS_SESSION_SECRET": "<long-random-secret>",
     "CASELENS_AI_PROVIDER": "groq",
     "GROQ_API_KEY": "<groq-key>"
   }
   ```

   Never put real values in Git, task reports, screenshots, or chat. The Lambda
   runtime loads only an explicit allowlist from this secret.

   `POST /api/demo/session` is anonymous: it creates an isolated 24-hour
   workspace and returns a bearer token signed with `CASELENS_SESSION_SECRET`.
   Every non-session API remains protected by that signed token and is scoped
   to its workspace.
7. The repository variable `CASELENS_ARTIFACT_BUCKET` for the CI publication
   job, alongside the existing frontend bucket, CloudFront distribution, API
   base URL, and AWS region variables.
   The deploy role can read the exact versioned artifact it publishes, allowing
   Lambda updates to pin an immutable S3 object version.
   Set `CASELENS_LAMBDA_DEPLOY_ENABLED` to `true` only after the Lambda
   functions exist, and set `CASELENS_LAMBDA_FUNCTIONS` to their exact comma-
   separated names. The manual workflow then updates each function to the
   versioned object; leaving the flag unset safely publishes only the artifact.
8. For the first compute deployment only, run a reviewed plan with
   `deploy_compute=true`, the artifact bucket, and
   `bootstrap_live_aliases=true` while leaving all three live-version inputs unset.
   Terraform creates the functions and bootstraps each alias from the version
   created in that apply. Leave bootstrap mode disabled for every later release
   or rollback, and set `api_live_version`, `worker_live_version` and
   `relay_live_version` explicitly
   after AWS reports SnapStart optimization as `On`. The explicit CI runtime-
   update step owns subsequent code
   versions, while S3 versioning preserves rollback targets. Compute remains
   disabled by default until Neon connectivity and the secret are verified.
9. Before any alias move, check every newly published version. For each of the
   api, worker and relay functions, run
   `aws lambda get-function-configuration --function-name <fn> --qualifier <version>`
   and confirm `State` is `Active` and `SnapStart.OptimizationStatus` is `On`.
   A version whose init failed (for example a database migration or schema
   validation error) ends `Failed` and must never become a `live` target.
   Record the current `live` versions first
   (`aws lambda get-alias --function-name <fn> --name live`), then move all three
   with one Terraform plan that changes only the three alias `function_version`
   values. A `Failed` version can mean a migration did not apply; read its init
   log before republishing.

   Database migrations run against the shared production database when a new
   version is published, during its SnapStart init, before any alias moves. The
   versions behind the `live` aliases keep serving on the changed schema until
   the aliases move, and keep serving if a release is rolled back. So every
   migration must be expand-only: add columns, tables or rows, or relax a
   constraint. Never drop or rename something the running version still uses.

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

The worker cold path was verified separately on 2026-07-12. Before the fix,
SQS invoked `$LATEST` and the first job initialized Spring inside the request,
taking 28-40 seconds in live measurements. Worker version 6 initializes Spring
before its snapshot and is reached through the pinned worker alias. Its first
real synthetic Groq journey completed in 7.27 seconds end to end; the next
journey completed in 2.69 seconds. Both were `AI_VALIDATED` in one attempt.

API throttle and CORS

The HTTP API stage allows 5 requests a second with a burst of 10 for all
visitors together, which also caps Lambda and Neon spend if the API is
flooded. Spring answers CORS for every request that reaches the api function.
The API has no `cors_configuration`: AWS documents that HTTP API CORS adds the
configured headers "to the response from an integration", and a throttled 429
(or a gateway 5xx) never reaches the integration, so the setting is not
documented to help there. It would also make API Gateway ignore Spring's CORS
headers, moving origin ownership into a second place. The browser therefore
sees a gateway 429 as a failed fetch (TypeError), and the frontend sends the
request once more after a TypeError, the same way it retries a 429 or 503.
That covers every GET and every POST that is safe to receive twice: the triage
POST returns the ticket's existing job, a failure retry returns the replacement
it already queued, reset seeds the same set again, and the scenario and
feedback POSTs carry a `requestId` that the API uses as the new row's id, so a
resend returns the row the first one saved (the same id with a different body
is a 409). Creating a session is never resent, because a second request would
open an extra workspace; the reviewer starts the demo again instead.
Raising the throttle is a cost decision, not a fix for this.

## Neon compute guardrail

The original once-per-minute recovery schedule prevented Neon from reaching its
five-minute idle window, exhausting the free compute allowance. This was not
caused by visitor traffic or database storage growth. The recovery cadence is
now 30 minutes, leaving the immediate publisher responsible for normal outbox
delivery and allowing an idle demo database to scale down. No Neon plan upgrade
is required by this change.

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

The destroy workflow runs in its own GitHub environment, `production-destroy`,
not in `production`. The destroy role trusts only that environment's OIDC
subject (`repo:<owner>/<repo>:environment:production-destroy`), so the deploy
job, which runs in `production`, cannot assume it. Create the environment with
a required reviewer and `main` as its only deployment branch, then give it the
variables and the destroy role ARN the workflow reads:

```bash
repo=HarishDarko/caselens
reviewer_id="$(gh api users/HarishDarko --jq .id)"
gh api -X PUT "repos/$repo/environments/production-destroy" --input - <<EOF
{"reviewers":[{"type":"User","id":$reviewer_id}],"prevent_self_review":false,
 "deployment_branch_policy":{"protected_branches":false,"custom_branch_policies":true}}
EOF
gh api -X POST "repos/$repo/environments/production-destroy/deployment-branch-policies" -f name=main -f type=branch
for name in AWS_REGION CASELENS_TERRAFORM_STATE_BUCKET; do
  gh variable set "$name" --env production-destroy --repo "$repo" \
    --body "$(gh variable get "$name" --env production --repo "$repo")"
done
gh secret set AWS_DESTROY_ROLE_ARN --env production-destroy --repo "$repo" \
  --body "$(terraform -chdir=infra/terraform/bootstrap output -raw github_destroy_role_arn)"
gh secret delete AWS_DESTROY_ROLE_ARN --env production --repo "$repo"
```

`prevent_self_review` stays false because the owner is the only reviewer and
also starts the workflow. The role ARN does not change when the bootstrap
stack is re-applied, so the secret can be set before or after that apply.

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
`AWS_DESTROY_ROLE_ARN` secret lives only in the `production-destroy`
environment, is used only by the teardown workflow, and names a role created by
the bootstrap stack, outside the application state that it destroys.

After the demo, open **Actions → CaseLens Terraform Destroy**, run it on
`main`, and type `DESTROY_CASELENS`. The job waits for the required reviewer
of the `production-destroy` environment to approve it before it receives AWS
credentials. The destroy role trusts only jobs in that environment, so a
bootstrap apply is needed after any change to its trust or policy.
The workflow then refuses the wrong branch, wrong confirmation, missing state
configuration, or empty state. It first applies a plan targeted at the two
versioned buckets that only sets `force_destroy=true` in state (the AWS
provider deletes a bucket with the value saved in state, so the variable alone
is not enough), then prints the destroy plan and applies only that exact saved
plan. Current and versioned demo bucket objects are therefore removed before
bucket deletion. It removes the application resources but preserves the state bucket
for recovery. Delete the bootstrap stack separately only when the project and
its Terraform history are no longer needed.
