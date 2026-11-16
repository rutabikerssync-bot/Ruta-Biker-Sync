param()
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

function Fail($msg) { Write-Error "FAIL: $msg"; exit 1 }
function Ok($msg) { Write-Host "OK: $msg" }

# verify_release_security (.ps1) - T-01..T-08 (condición 5)
# Reglas de aceptación (explícitas):
# 1) manifest fusionado standardRelease SIN usesCleartextTraffic="true" -> PASS
# 2) manifest fusionado CON networkSecurityConfig="@xml/network_security_config" -> PASS
# 3) BleMeshService presente Y exported="false" -> PASS; si aparece con exported="true" o sin exported -> FAIL
# 4) EmergencyLockScreenActivity presente Y exported="false" -> PASS; ídem
# 5) BASE_URL release empieza por https:// Y no contiene example.invalid/TODO_EXTERNAL/localhost -> PASS
# 6) grep de StrongLeaderPass!2026/StrongMemberPass!2026 en src/main y en outputs/apk = 0 -> PASS
# Debe FALLAR si falta manifest o BuildConfig (sin falso verde por ausencia de artefacto).

function Get-MergedManifests {
  $found = @()
  $candidates = @(
    "android/app/build/intermediates/merged_manifest/standardRelease/processStandardReleaseMainManifest/AndroidManifest.xml",
    "android/app/build/intermediates/merged_manifests/standardRelease/processStandardReleaseManifest/AndroidManifest.xml",
    "android/app/build/intermediates/merged_manifest/standardRelease/AndroidManifest.xml",
    "android/app/build/intermediates/merged_manifests/standardRelease/AndroidManifest.xml"
  )
  foreach ($c in $candidates) { if (Test-Path $c) { $found += (Resolve-Path $c).Path } }
  if (Test-Path "android/app/build") {
    $extra = Get-ChildItem -Path "android/app/build" -Recurse -Filter "AndroidManifest.xml" -ErrorAction SilentlyContinue |
      Where-Object { $_.FullName -match "standardRelease" -and ($_.FullName -match "merged_manifest|merged_manifests") } |
      ForEach-Object { $_.FullName }
    foreach ($e in $extra) { if ($found -notcontains $e) { $found += $e } }
  }
  return $found | Select-Object -Unique
}

function Get-ComponentStatus {
  param(
    [string]$Xml,
    [string]$Tag,          # service|activity
    [string[]]$Names       # short and fqcn
  )
  $patternNames = ($Names | ForEach-Object { [regex]::Escape($_) }) -join '|'
  $blockPattern = "(?is)<$Tag\b(?:(?!</$Tag>).)*?\bandroid:name\s*=\s*`"($patternNames)`"(?:(?!</$Tag>).)*?</$Tag>"
  $matches = [regex]::Matches($Xml, $blockPattern)
  if ($matches.Count -eq 0) { return @{ found = $false; ok = $false; bad = $false } }
  $ok = $false; $bad = $false
  foreach ($m in $matches) {
    $b = $m.Value
    if ($b -match '(?is)\bandroid:exported\s*=\s*"false"') { $ok = $true }
    elseif ($b -match '(?is)\bandroid:exported\s*=\s*"true"' -or $b -notmatch '(?is)\bandroid:exported\s*=') { $bad = $true }
  }
  return @{ found = $true; ok = $ok; bad = $bad }
}

$merged = Get-MergedManifests
if (!$merged -or $merged.Count -eq 0) {
  Fail "falta manifest fusionado de standardRelease. Genera el build/manifest primero (artefacto requerido, sin falso verde)."
}

# 1,2) cleartext / networkSecurityConfig: deben cumplirse en todos los manifests fusionados analizados
foreach ($mf in $merged) {
  $xml = Get-Content $mf -Raw
  if ($xml -match 'android:usesCleartextTraffic\s*=\s*"true"') { Fail "cleartext habilitado en $mf" }
  if ($xml -notmatch 'android:networkSecurityConfig\s*=\s*"@xml/network_security_config"') { Fail "networkSecurityConfig ausente o incorrecto en $mf" }
}
Ok "sin cleartext y networkSecurityConfig presente en manifest(s) fusionado(s) standardRelease"

# 3,4) componentes: PASS si al menos un manifest fusionado contiene el componente con exported=false;
# FAIL si aparece exported=true o sin exported en los manifests donde se encuentra (no se acepta estado ambiguo inseguro).
$bleNames = @(".core.mesh.BleMeshService", "com.rutabikersync.app.core.mesh.BleMeshService")
$lockNames = @(".feature.sos.EmergencyLockScreenActivity", "com.rutabikersync.app.feature.sos.EmergencyLockScreenActivity")

function Assert-Component($display, $tag, $names) {
  $foundAny = $false; $okAny = $false; $badAny = $false
  foreach ($mf in $merged) {
    $xml = Get-Content $mf -Raw
    $st = Get-ComponentStatus -Xml $xml -Tag $tag -Names $names
    if ($st.found) { $foundAny = $true }
    if ($st.ok) { $okAny = $true }
    if ($st.bad) { $badAny = $true }
  }
  if (-not $foundAny) { Fail "$display no encontrado en manifests fusionados standardRelease" }
  if ($badAny) { Fail "$display aparece con exported=`"true`" o sin exported en manifest fusionado" }
  if (-not $okAny) { Fail "$display no está exported=`"false`" en manifests fusionados standardRelease" }
  Ok "$display presente y exported=false"
}

Assert-Component "BleMeshService" "service" $bleNames
Assert-Component "EmergencyLockScreenActivity" "activity" $lockNames

# 5) BASE_URL: debe existir BuildConfig de standardRelease y cumplir política estricta (placeholder NO permitido)
$bc = "android/app/build/generated/source/buildConfig/standard/release/com/rutabikersync/app/BuildConfig.java"
if (!(Test-Path $bc)) { Fail "falta BuildConfig de standardRelease ($bc). Artefacto requerido, sin falso verde." }
$bct = Get-Content $bc -Raw
if ($bct -notmatch 'String\s+BASE_URL\s*=\s*"https://') { Fail "BuildConfig standardRelease BASE_URL no empieza por https://" }
if ($bct -match 'example\.invalid|TODO_EXTERNAL|localhost') { Fail "BuildConfig standardRelease BASE_URL contiene placeholder/no permitido (example.invalid, TODO_EXTERNAL o localhost). Estado esperado mientras falte TODO_EXTERNAL:URL_PROD." }
Ok "BASE_URL release https sin placeholders"

# 6) seeds: 0 en src/main y en outputs/apk
$seedPatterns = @("StrongLeaderPass!2026", "StrongMemberPass!2026")
$seedHits = @()
$seedHits += Select-String -Path "android/app/src/main/**/*.kt","android/app/src/main/**/*.java","android/app/src/main/**/*.xml" -Pattern $seedPatterns -SimpleMatch -ErrorAction SilentlyContinue
if (Test-Path "android/app/build/outputs/apk") {
  $apkFiles = Get-ChildItem -Path "android/app/build/outputs/apk" -Recurse -File -ErrorAction SilentlyContinue
  foreach ($p in $seedPatterns) {
    $seedHits += Select-String -Path $apkFiles.FullName -Pattern $p -SimpleMatch -ErrorAction SilentlyContinue
  }
}
if ($seedHits -and $seedHits.Count -gt 0) { Fail "credenciales seed encontradas en src/main u outputs/apk" }
Ok "sin credenciales seed en src/main y outputs/apk"

Write-Host "verify_release_security: PASS"
exit 0
