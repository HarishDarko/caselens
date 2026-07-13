#!/usr/bin/env bash
set -euo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

"$repository_root/backend/mvnw" -f "$repository_root/backend/pom.xml" test
cd "$repository_root/frontend"
npm test
npm run lint
npm run typecheck
