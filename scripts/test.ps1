$ErrorActionPreference = 'Stop'
& "$PSScriptRoot\..\backend\mvnw.cmd" -f "$PSScriptRoot\..\backend\pom.xml" test
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
Push-Location "$PSScriptRoot\..\frontend"
try {
    npm test
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    npm run lint
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    npm run typecheck
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
} finally {
    Pop-Location
}
