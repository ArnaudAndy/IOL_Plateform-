[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$backendDirectory = Split-Path -Parent $PSScriptRoot
$apiEnvPath = Join-Path $backendDirectory '.env'
$openHimEnvPath = Join-Path $backendDirectory 'openhim\.env'
$variableName = 'INTEROP_INTERNAL_SECRET'

function Read-EnvValue([string]$Path, [string]$Name) {
  if (-not (Test-Path -LiteralPath $Path)) { return $null }
  $line = Get-Content -LiteralPath $Path |
    Where-Object { $_ -match "^$([regex]::Escape($Name))=" } |
    Select-Object -Last 1
  if ($null -eq $line) { return $null }
  return ($line -split '=', 2)[1].Trim()
}

function Write-EnvValue([string]$Path, [string]$Name, [string]$Value) {
  $directory = Split-Path -Parent $Path
  if (-not (Test-Path -LiteralPath $directory)) {
    New-Item -ItemType Directory -Path $directory | Out-Null
  }

  $lines = [System.Collections.Generic.List[string]]::new()
  if (Test-Path -LiteralPath $Path) {
    Get-Content -LiteralPath $Path | ForEach-Object {
      $lines.Add([string]$_)
    }
  }
  $pattern = "^$([regex]::Escape($Name))="
  $updated = $false
  for ($index = 0; $index -lt $lines.Count; $index += 1) {
    if ($lines[$index] -match $pattern) {
      $lines[$index] = "$Name=$Value"
      $updated = $true
    }
  }
  if (-not $updated) { $lines.Add("$Name=$Value") }
  [System.IO.File]::WriteAllLines(
    $Path,
    $lines,
    [System.Text.UTF8Encoding]::new($false)
  )
}

$apiValue = Read-EnvValue $apiEnvPath $variableName
$openHimValue = Read-EnvValue $openHimEnvPath $variableName
$configuredValues = @(
  @($apiValue, $openHimValue) |
    Where-Object {
      -not [string]::IsNullOrWhiteSpace($_) -and
      $_ -ne 'change-me' -and
      $_.Length -ge 40
    } |
    Select-Object -Unique
)

if ($configuredValues.Count -gt 1) {
  throw 'The API Core and OpenHIM internal secrets differ; refusing an implicit rotation.'
}

if ($configuredValues.Count -eq 1) {
  $secret = $configuredValues[0]
} else {
  $bytes = [byte[]]::new(48)
  [System.Security.Cryptography.RandomNumberGenerator]::Fill($bytes)
  $secret = [Convert]::ToBase64String($bytes)
}

Write-EnvValue $apiEnvPath $variableName $secret
Write-EnvValue $openHimEnvPath $variableName $secret

Write-Host 'INTEROP_INTERNAL_SECRET is synchronized in both local .env files.'
