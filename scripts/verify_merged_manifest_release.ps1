param(
  [string]$Variant = "standardRelease"
)
$ErrorActionPreference = "Stop"
$manifest = "android/app/build/intermediates/merged_manifests/$Variant/AndroidManifest.xml"
if (!(Test-Path $manifest)) {
  $manifest = "android/app/build/intermediates/merged_manifest/$Variant/AndroidManifest.xml"
}
if (!(Test-Path $manifest)) {
  # try process manifest output
  $candidates = Get-ChildItem -Path "android/app/build" -Recurse -Filter "AndroidManifest.xml" -ErrorAction SilentlyContinue | Where-Object { $_.FullName -match "merged" -and $_.FullName -match $Variant }
  if ($candidates) { $manifest = $candidates[0].FullName }
}
if (!(Test-Path $manifest)) { Write-Error "FAIL: merged manifest for $Variant not found. Build the variant first."; exit 1 }
$xml = Get-Content $manifest -Raw
if ($xml -match 'android:usesCleartextTraffic="true"') { Write-Error "FAIL: merged manifest contains usesCleartextTraffic=true"; exit 1 }
if ($xml -notmatch 'android:networkSecurityConfig="@xml/network_security_config"' -and $xml -notmatch 'network_security_config') { Write-Error "FAIL: merged manifest missing networkSecurityConfig"; exit 1 }
Write-Host "OK: $Variant merged manifest has no cleartext and includes networkSecurityConfig ($manifest)"
