$ErrorActionPreference = 'Stop'
& "$PSScriptRoot\..\backend\mvnw.cmd" -f "$PSScriptRoot\..\backend\pom.xml" test
Push-Location "$PSScriptRoot\..\frontend"
try {
    npm test
    npm run lint
    npm run typecheck
} finally {
    Pop-Location
}
