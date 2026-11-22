<#
.SYNOPSIS
  Levanta el backend de piloto y un Cloudflare quick tunnel para trabajar
  desde el laptop sin Tailscale ni cuenta de Cloudflare.

.DESCRIPTION
  1. Valida fail-closed la configuración del piloto (claves y ADMIN_API_KEY).
  2. Arranca el backend en http://127.0.0.1:3000.
  3. Arranca `cloudflared tunnel --url` y espera a que publique la URL HTTPS.
  4. Guarda la URL efímera en docs/evidence/piloto_tunnel_url.txt (ignorado
     por git) y muestra cómo actualizar los móviles por deep link.

  No imprime claves. Los logs quedan en logs/ (ignorado por git).
#>
param(
  [int]$Port = 3000,
  [int]$WaitSeconds = 90
)

$ErrorActionPreference = 'Stop'

$repoRoot = Split-Path -Parent $PSScriptRoot
$backendDir = Join-Path $repoRoot 'backend'
$logsDir = Join-Path $repoRoot 'logs'
$evidenceDir = Join-Path $repoRoot 'docs\evidence'
$urlFile = Join-Path $evidenceDir 'piloto_tunnel_url.txt'
$backendLog = Join-Path $logsDir 'piloto_backend.out.log'
$backendErrorLog = Join-Path $logsDir 'piloto_backend.err.log'
$tunnelLog = Join-Path $logsDir 'piloto_cloudflared.log'
$tunnelOutLog = Join-Path $logsDir 'piloto_cloudflared.out.log'
$tunnelErrorLog = Join-Path $logsDir 'piloto_cloudflared.err.log'

New-Item -ItemType Directory -Force -Path $logsDir, $evidenceDir | Out-Null

$required = @('RBS_PILOT_ALLOWED_KEYS', 'ADMIN_API_KEY', 'AUTH_TOKEN_SECRET')
$missing = $required | Where-Object { [string]::IsNullOrWhiteSpace((Get-Item "Env:$_").Value) }
if ($missing.Count -gt 0) {
  throw "Faltan variables de entorno obligatorias: $($missing -join ', '). No se arranca nada."
}

$existing = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue
if ($existing) {
  throw "El puerto $Port ya está ocupado. Detén el proceso anterior antes de continuar."
}

$cloudflared = Get-Command cloudflared -ErrorAction SilentlyContinue
if (-not $cloudflared) {
  throw 'cloudflared no está en PATH. Instálalo antes de continuar.'
}

Write-Host "Backend: http://127.0.0.1:$Port/soporte  (salida: $backendLog)"

if ([string]::IsNullOrWhiteSpace((Get-Item Env:DATABASE_URL -ErrorAction SilentlyContinue).Value)) {
  throw 'Falta DATABASE_URL. El piloto necesita PostgreSQL para telemetría y reportes.'
}

# PowerShell 5.1 no soporta Start-Process -Environment: el hijo hereda el
# entorno del proceso actual. Se restaura al terminar.
$backup = @{}
$setEnv = @{
  PORT = "$Port"
  NODE_ENV = 'development'
  TRUST_PROXY = 'false'
  RBS_PILOT_MODE = 'true'
  RBS_PILOT_ALLOW_TRYCLOUDFLARE = 'true'
}
foreach ($name in @('RBS_PILOT_ALLOWED_KEYS','RBS_PILOT_REVOKED_KEYS','RBS_PILOT_PUBLIC_ORIGINS','ADMIN_API_KEY','AUTH_TOKEN_SECRET','DATABASE_URL')) {
  $item = Get-Item "Env:$name" -ErrorAction SilentlyContinue
  if ($item) { $setEnv[$name] = $item.Value }
}
foreach ($name in $setEnv.Keys) {
  $backup[$name] = (Get-Item "Env:$name" -ErrorAction SilentlyContinue).Value
  Set-Item "Env:$name" -Value $setEnv[$name]
}
function Restore-PilotEnv {
  foreach ($name in $backup.Keys) {
    if ($null -eq $backup[$name]) {
      Remove-Item "Env:$name" -ErrorAction SilentlyContinue
    } else {
      Set-Item "Env:$name" -Value $backup[$name]
    }
  }
}
trap { Write-Error $_; Restore-PilotEnv; throw }

$backendArgs = @('tsx', 'src/index.ts')
$backendProc = Start-Process -FilePath 'npx.cmd' -ArgumentList $backendArgs `
  -WorkingDirectory $backendDir `
  -RedirectStandardOutput $backendLog -RedirectStandardError $backendErrorLog `
  -PassThru -WindowStyle Hidden

Write-Host "Backend iniciado (PID $($backendProc.Id)). Esperando /health…"
$deadline = (Get-Date).AddSeconds($WaitSeconds)
$healthy = $false
while ((Get-Date) -lt $deadline) {
  if ($backendProc.HasExited) { throw "El backend terminó. Revisa $backendLog" }
  try {
    $response = Invoke-WebRequest -Uri "http://127.0.0.1:$Port/health" -TimeoutSec 2 -UseBasicParsing
    if ($response.StatusCode -eq 200) { $healthy = $true; break }
  } catch { Start-Sleep -Seconds 1 }
}
if (-not $healthy) { throw "El backend no respondió /health en $WaitSeconds s. Revisa $backendLog" }
Write-Host 'Backend OK.'

Write-Host 'Abriendo Cloudflare quick tunnel…'
# El log es acumulativo: si no se trunca, el regex puede capturar la URL de una
# ejecución anterior, que ya no resuelve y deja los móviles en "Fastify offline".
foreach ($log in @($tunnelLog, $tunnelOutLog, $tunnelErrorLog)) {
  Set-Content -LiteralPath $log -Value '' -Encoding UTF8 -ErrorAction SilentlyContinue
}
$tunnelProc = Start-Process -FilePath $cloudflared.Source `
  -ArgumentList @('tunnel', '--url', "http://127.0.0.1:$Port", '--logfile', $tunnelLog) `
  -WorkingDirectory $repoRoot -RedirectStandardOutput $tunnelOutLog `
  -RedirectStandardError $tunnelErrorLog -PassThru -WindowStyle Hidden

$deadline = (Get-Date).AddSeconds($WaitSeconds)
$tunnelUrl = $null
while ((Get-Date) -lt $deadline) {
  if ($tunnelProc.HasExited) { throw "cloudflared terminó. Revisa $tunnelLog" }
  foreach ($log in @($tunnelLog, $tunnelOutLog, $tunnelErrorLog)) {
    if (-not (Test-Path -LiteralPath $log)) { continue }
    $text = Get-Content -LiteralPath $log -Raw -ErrorAction SilentlyContinue
    if ([string]::IsNullOrEmpty($text)) { continue }
    $matches = [regex]::Matches($text, 'https://[a-z0-9-]+\.trycloudflare\.com')
    if ($matches.Count -gt 0) {
      $tunnelUrl = $matches[$matches.Count - 1].Value
      break
    }
  }
  if ($tunnelUrl) { break }
  Start-Sleep -Seconds 1
}
if (-not $tunnelUrl) { throw "cloudflared no publicó URL en $WaitSeconds s. Revisa $tunnelLog" }

Set-Content -LiteralPath $urlFile -Value $tunnelUrl -Encoding UTF8

Write-Host ''
Write-Host "URL pública: $tunnelUrl"
Write-Host "Soporte:     $tunnelUrl/soporte"
Write-Host "Backoffice:  $tunnelUrl/admin"
Write-Host "URL guardada en $urlFile (ignorada por git)."
Write-Host ''
Write-Host 'Para actualizar un móvil sin recompilar (solo builds debug/piloto):'
Write-Host '  adb shell am start -a android.intent.action.VIEW \'
Write-Host "    -d 'rbs://debug/set-base-url?url=$tunnelUrl&key=<CLAVE_DEL_PROBADOR>&build=<ETIQUETA>' \"
Write-Host '    com.rutabikersync.app/.MainActivity'
Write-Host ''
Write-Host 'Para detener: Stop-Process -Id' $backendProc.Id ',' $tunnelProc.Id
Restore-PilotEnv
