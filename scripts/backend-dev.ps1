$ErrorActionPreference = 'Stop'

$repositoryRoot = (Resolve-Path "$PSScriptRoot\..").Path
$environmentFile = Join-Path $repositoryRoot '.env'

if (-not (Test-Path -LiteralPath $environmentFile)) {
    throw "Missing $environmentFile. Create it from .env.example before starting the backend."
}

foreach ($line in Get-Content -LiteralPath $environmentFile) {
    $trimmed = $line.Trim()
    if (-not $trimmed -or $trimmed.StartsWith('#')) {
        continue
    }

    $separator = $trimmed.IndexOf('=')
    if ($separator -lt 1) {
        throw "Invalid .env entry: $line"
    }

    $name = $trimmed.Substring(0, $separator).Trim()
    $value = $trimmed.Substring($separator + 1)
    [Environment]::SetEnvironmentVariable($name, $value, 'Process')
}

& "$repositoryRoot\backend\mvnw.cmd" -f "$repositoryRoot\backend\pom.xml" spring-boot:run
exit $LASTEXITCODE
