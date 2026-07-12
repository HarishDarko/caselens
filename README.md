# CaseLens

CaseLens is an explainable support-ticket triage platform for small support and
production-operations teams. It demonstrates trustworthy classification,
deterministic prioritization, human review, asynchronous processing, and
production-minded failure handling. All bundled and publicly stored ticket data
is synthetic.

The reviewer console is a working local product, not a static mockup. It talks
to the Spring Boot API, PostgreSQL, and LocalStack SQS. Groq and Gemini are
available providers, while the default local mode uses a deterministic mock so
the demo is repeatable and does not spend on model calls.

## Prerequisites

- Java 21 (newer installed JDKs must compile with the configured Java 21 target)
- Node.js 22 LTS or Node.js 24 LTS and npm 10 or later
- Docker Desktop with Docker Compose
- PowerShell 7

## Run the local product

Create a local environment file before starting services:

```powershell
Copy-Item .env.example .env
```

For a live Groq run, set `CASELENS_AI_PROVIDER=groq` and add the key to
`GROQ_API_KEY` in the ignored `.env`. The default model is
`openai/gpt-oss-20b`; leave the provider as `mock` for offline work.

Start or stop PostgreSQL 16 and LocalStack SQS:

```powershell
./scripts/dev-up.ps1
./scripts/dev-down.ps1
```

Start the backend in one PowerShell window and the built frontend preview in a
second:

```powershell
./scripts/backend-dev.ps1
Set-Location frontend
npm install
npm run build
npx vite preview --host 0.0.0.0 --port 4173
```

Open [http://localhost:4173/](http://localhost:4173/) and use the local demo
passcode from `.env` (the supplied example uses `reviewer`). The backend is
available at `http://localhost:8080`; its health endpoint is
`http://localhost:8080/actuator/health`.

The local UI supports:

- isolated reviewer sessions and workspace reset;
- a prioritized synthetic inbox and scenario launcher;
- asynchronous triage through the outbox and SQS worker;
- exact evidence, policies, explanations, recommended actions, and suggested replies;
- AI-validated results with deterministic rules fallback;
- human urgency corrections stored beside immutable original results;
- evaluation metrics derived from persisted results and feedback, with a recent
  event stream showing provider, model version, decision source, latency, and
  reviewer correction state;
- live operations telemetry for queued, processing, completed, retryable, and
  terminal jobs, provider failures, rules fallbacks, recent processing, and
  retry actions. Evaluation and Operations refresh every five seconds while
  their views are open.

## Verification commands

Run the backend and frontend checks:

```powershell
./scripts/test.ps1
```

Run each workspace directly when iterating:

```powershell
./scripts/backend-dev.ps1
Set-Location frontend
npm install
npm run dev
```

The backend helper loads the root `.env` file into its process before invoking Maven. Backend tests use an in-memory test profile and do not require PostgreSQL, LocalStack, or Docker.

The complete local verification also includes:

```powershell
Set-Location backend
./mvnw.cmd -B -ntp test
Set-Location ../frontend
npm test -- --run
npm run lint
npm run typecheck
npm run build
npm run test:e2e
Set-Location ../infra/terraform
terraform fmt -check -recursive
terraform init -backend=false -input=false
terraform validate
```

## Provider and data boundaries

The Groq adapter uses the OpenAI-compatible chat-completions API with bearer
authentication, strict JSON Schema for the default model, JSON Object Mode for
other explicitly configured models, bounded retry behavior, a configurable
request timeout, and policy IDs constrained by the versioned catalog. The
application still
validates the complete structured result and falls back to deterministic rules
when provider output is unavailable or invalid. Gemini remains available via
its separate adapter. Ticket text is redacted before provider submission.
Model invocation records store provider metadata, timing, status, and usage
counts—not API keys, raw tickets, request bodies, or raw provider responses.

The first release intentionally does not include customer authentication, RAG,
embeddings, Kubernetes, real OCPP traffic, real payment processing, or a live
helpdesk integration. The EV-charging examples are company-neutral synthetic
fixtures.

## Deployment preparation

Terraform under `infra/terraform` provisions the private frontend bucket,
CloudFront access control, SQS/DLQ, Secrets Manager placeholder, Lambda roles,
CloudWatch alarms, API Gateway, and optional Java 21 Lambda compute. GitHub
Actions validates the backend, frontend, Terraform, containers, and security
checks, and contains an OIDC-based publish path.

No AWS resources are created by the local commands above. A real deployment
still requires an AWS account bootstrap, an OIDC deploy role, a managed
PostgreSQL choice, and explicit runtime secret configuration.

See [docs/architecture.md](docs/architecture.md),
[docs/security-hardening.md](docs/security-hardening.md),
[docs/evaluation-metrics.md](docs/evaluation-metrics.md), and
[docs/reviewer-walkthrough.md](docs/reviewer-walkthrough.md) for the design,
operational boundaries, metric definitions, and a short demo script.
