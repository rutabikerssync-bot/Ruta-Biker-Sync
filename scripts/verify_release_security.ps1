param()
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

function Fail($msg) { Write-Error "FAIL: $msg"; exit 1 }
function Ok($msg) { Write-Host "OK: $msg" }

$merged = $null
$candidates = @(
  "android/app/build/intermediates/merged_manifests/standardRelease/AndroidManifest.xml",
  "android/app/build/intermediates/merged_manifest/standardRelease/AndroidManifest.xml"
)
foreach ($c in $candidates) { if (Test-Path $c) { $merged = $c; break } }
if (!$merged -and (Test-Path "android/app/build")) {
  $m = Get-ChildItem -Path "android/app/build" -Recurse -Filter "AndroidManifest.xml" -ErrorAction SilentlyContinue | Where-Object { $_.FullName -match "standardRelease" -and $_.FullName -match "merged" } | Select-Object -First 1
  if ($m) { $merged = $m.FullName }
}
$artifact = $null
if (Test-Path "android/app/build/outputs") {
  $artifact = Get-ChildItem -Path "android/app/build/outputs" -Recurse -File -ErrorAction SilentlyContinue | Where-Object { $_.Name -match "standardRelease" -and ($_.Name -like "*.apk" -or $_.Name -like "*.aab") } | Select-Object -First 1
}
if (!$merged -and !$artifact) { Fail "falta artefacto de standardRelease (merged manifest o APK/AAB). Genera el build primero." }

$manifestSrc = "android/app/src/main/AndroidManifest.xml"
$checkManifest = if ($merged) { $merged } else { $manifestSrc }
$xml = Get-Content $checkManifest -Raw
if ($xml -match 'android:usesCleartextTraffic="true"') { Fail "cleartext habilitado en $checkManifest" }
if ($xml -notmatch 'networkSecurityConfig') { Fail "networkSecurityConfig ausente en $checkManifest" }
Ok "sin cleartext y networkSecurityConfig presente ($checkManifest)"

$block = [regex]::Match($xml, '(?s)<service[^>]*android:name="\.core\.mesh\.BleMeshService"[^>]*>.*?</service>')
if (!$block.Success) { Fail "BleMeshService no encontrado en $checkManifest" }
if ($block.Value -notmatch 'android:exported="false"') { Fail "BleMeshService no está exported=`"false`"" }
Ok "BleMeshService exported=false"

$bc = "android/app/build/generated/source/buildConfig/standard/release/com/rutabikersync/app/BuildConfig.java"
if (Test-Path $bc) {
  $bct = Get-Content $bc -Raw
  if ($bct -notmatch 'String BASE_URL = "https://') { Fail "BuildConfig standardRelease BASE_URL no es https" }
  Ok "BASE_URL https en BuildConfig generado"
} else {
  $gradle = Get-Content "android/app/build.gradle.kts" -Raw
  if ($gradle -notmatch 'TODO_EXTERNAL_URL_PROD' -or $gradle -notmatch 'buildConfigField\("String", "BASE_URL", "https://') { Fail "BASE_URL release no configurado como https (y BuildConfig generado ausente)" }
  Ok "BASE_URL release configurado como https (placeholder TODO_EXTERNAL:URL_PROD)"
}

$seedHits = Select-String -Path "android/app/src/main/**/*.kt","android/app/src/main/**/*.java","android/app/src/main/**/*.xml" -Pattern "StrongLeaderPass!2026","StrongMemberPass!2026" -SimpleMatch -ErrorAction SilentlyContinue
if ($seedHits) { Fail "credenciales seed encontradas en android/app/src/main" }
Ok "sin credenciales seed en src/main"

Write-Host "verify_release_security: PASS"
exit 0