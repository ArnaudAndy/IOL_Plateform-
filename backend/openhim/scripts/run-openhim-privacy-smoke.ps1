[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$openHimDirectory = Split-Path -Parent $PSScriptRoot
$composeFile = Join-Path $openHimDirectory 'docker-compose.openhim.yml'
$smokeScript = Join-Path $PSScriptRoot 'openhim-privacy-smoke.js'
$auditScript = Join-Path $PSScriptRoot 'audit-transaction-privacy.js'

# Rebuilding both images is intentional: the audit must exercise the exact
# mediator source and the OpenHIM privacy patch that are about to be released.
docker compose -f $composeFile up -d --build openhim-core iol-mediator
if ($LASTEXITCODE -ne 0) { throw 'Unable to deploy OpenHIM Core and the IOL mediator.' }

docker cp $smokeScript iol-mediator:/tmp/openhim-privacy-smoke.js
if ($LASTEXITCODE -ne 0) { throw 'Unable to copy the OpenHIM smoke test.' }

$rawResult = docker exec iol-mediator node /tmp/openhim-privacy-smoke.js
if ($LASTEXITCODE -ne 0) {
  throw "OpenHIM did not accept the synthetic transaction: $rawResult"
}
$result = $rawResult | ConvertFrom-Json
if (-not $result.routed) {
  $details = $result.probes | ConvertTo-Json -Compress
  throw "At least one OpenHIM privacy probe failed: $details"
}

docker cp $auditScript iol-openhim-mongo:/tmp/audit-transaction-privacy.js
if ($LASTEXITCODE -ne 0) { throw 'Unable to copy the privacy audit.' }

docker exec iol-openhim-mongo mongo openhim --quiet /tmp/audit-transaction-privacy.js
if ($LASTEXITCODE -ne 0) {
  throw 'At least one OpenHIM transaction retains a body or permits rerun.'
}

$summary = ($result.probes | ForEach-Object {
  "$($_.name)=HTTP $($_.httpStatus)"
}) -join ', '
Write-Host "OpenHIM privacy smoke test passed: $summary."
