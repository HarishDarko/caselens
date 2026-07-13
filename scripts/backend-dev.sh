#!/usr/bin/env bash
set -euo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
environment_file="$repository_root/.env"

if [[ ! -f "$environment_file" ]]; then
  echo "Missing $environment_file. Create it from .env.example before starting the backend." >&2
  exit 1
fi

while IFS= read -r line || [[ -n "$line" ]]; do
  line="${line%$'\r'}"
  [[ "$line" =~ ^[[:space:]]*$ || "$line" =~ ^[[:space:]]*# ]] && continue
  if [[ "$line" != *=* ]]; then
    echo "Invalid .env entry: $line" >&2
    exit 1
  fi

  name="${line%%=*}"
  value="${line#*=}"
  name="${name#"${name%%[![:space:]]*}"}"
  name="${name%"${name##*[![:space:]]}"}"
  if [[ ! "$name" =~ ^[A-Za-z_][A-Za-z0-9_]*$ ]]; then
    echo "Invalid .env variable name: $name" >&2
    exit 1
  fi
  export "$name=$value"
done < "$environment_file"

: "${SPRING_PROFILES_ACTIVE:=local}"
: "${AWS_ENDPOINT_URL:=http://localhost:4566}"
: "${CASELENS_TRIAGE_QUEUE_URL:=http://localhost:4566/000000000000/caselens-triage}"
: "${CASELENS_QUEUE_ENABLED:=true}"
export SPRING_PROFILES_ACTIVE AWS_ENDPOINT_URL CASELENS_TRIAGE_QUEUE_URL CASELENS_QUEUE_ENABLED

exec "$repository_root/backend/mvnw" -f "$repository_root/backend/pom.xml" spring-boot:run
