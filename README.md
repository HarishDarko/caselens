# CaseLens

CaseLens is an explainable AI support-ticket triage platform built to demonstrate trustworthy classification, deterministic prioritization, human review, and production-minded failure handling.

Implementation is in progress. The repository currently provides the executable application foundation only. All bundled and publicly stored ticket data is synthetic.

## Prerequisites

- Java 21 (newer installed JDKs must compile with the configured Java 21 target)
- Node.js 22.13 LTS or Node.js 24+ and npm 10 or later
- Docker Desktop with Docker Compose
- PowerShell 7

## Foundation commands

Create a local environment file before starting services:

```powershell
Copy-Item .env.example .env
```

Start or stop PostgreSQL 16 and LocalStack SQS:

```powershell
./scripts/dev-up.ps1
./scripts/dev-down.ps1
```

Run the backend and frontend checks:

```powershell
./scripts/test.ps1
```

Run each workspace directly:

```powershell
./backend/mvnw.cmd -f ./backend/pom.xml spring-boot:run
Set-Location frontend
npm install
npm run dev
```

The backend reads runtime configuration from the environment variables listed in `.env.example`. Backend tests use an in-memory test profile and do not require PostgreSQL, LocalStack, or Docker.
