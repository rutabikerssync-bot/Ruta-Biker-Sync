param(
  [string]$BackupRules = "android/app/src/main/res/xml/backup_rules.xml",
  [string]$DataExtractionRules = "android/app/src/main/res/xml/data_extraction_rules.xml"
)
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

function Fail($msg) { Write-Error "FAIL: $msg"; exit 1 }
function Ok($msg) { Write-Host "OK: $msg" }

# verify_backup_rules (B3-4/B3-5): valida exclusiones quirúrgicas de almacenes sensibles del inventario.
# - Falla si falta exclusión sensible requerida.
# - Falla si hay exclusión de dominio completo (path="." o path="" o path="*" o path=".*" o path="./*").
# - Falla si backup_rules y data_extraction_rules (cloud-backup/device-transfer) no cubren el mismo conjunto de (domain,path).

$required = @(
  @{ domain = "sharedpref"; path = "rbs_encrypted_session_store.xml" },
  @{ domain = "sharedpref"; path = "rbs_biker_vault_local_store.xml" },
  @{ domain = "sharedpref"; path = "rbs_vault_keystore_prefs.xml" },
  @{ domain = "sharedpref"; path = "rbs_sos_crypto_prefs.xml" },
  @{ domain = "sharedpref"; path = "rbs_auth_prefs.xml" },
  @{ domain = "sharedpref"; path = "rbs_fcm_prefs.xml" },
  @{ domain = "database";  path = "ruta_biker_sync.db" }
)

if (!(Test-Path $BackupRules)) { Fail "no existe $BackupRules" }
if (!(Test-Path $DataExtractionRules)) { Fail "no existe $DataExtractionRules" }

function Get-ExcludesFromText([string]$xmlText, [string]$sectionPattern = $null) {
  $text = $xmlText
  if ($sectionPattern) {
    $m = [regex]::Match($text, $sectionPattern, [System.Text.RegularExpressions.RegexOptions]::Singleline -bor [System.Text.RegularExpressions.RegexOptions]::IgnoreCase)
    if (!$m.Success) { return @() }
    $text = $m.Groups[1].Value
  }
  $items = @()
  foreach ($m in [regex]::Matches($text, '<exclude\s+([^>]+)/?>', [System.Text.RegularExpressions.RegexOptions]::IgnoreCase)) {
    $attrs = $m.Groups[1].Value
    $d = [regex]::Match($attrs, 'domain\s*=\s*"([^"]*)"', [System.Text.RegularExpressions.RegexOptions]::IgnoreCase)
    $p = [regex]::Match($attrs, 'path\s*=\s*"([^"]*)"', [System.Text.RegularExpressions.RegexOptions]::IgnoreCase)
    $domain = if ($d.Success) { $d.Groups[1].Value } else { "" }
    $path = if ($p.Success) { $p.Groups[1].Value } else { "" }
    $items += [PSCustomObject]@{ domain = $domain; path = $path; raw = $m.Value }
  }
  return $items
}

function Check-NoFullDomain([object[]]$items, [string]$label) {
  foreach ($it in $items) {
    $p = ($it.path).Trim()
    if ($p -eq "." -or $p -eq "" -or $p -eq "*" -or $p -eq ".*" -or $p -eq "./*" -or $p -eq "./") {
      Fail "$label contiene exclusión de dominio completo o inválida: $($it.raw)"
    }
  }
}

function Exclude-Key([string]$domain, [string]$path) { return ("$domain|$path").ToLowerInvariant() }

$backupText = Get-Content $BackupRules -Raw
$dataText = Get-Content $DataExtractionRules -Raw

$backupEx = Get-ExcludesFromText $backupText
$cloudEx = Get-ExcludesFromText $dataText '(?is)<cloud-backup\b[^>]*>(.*?)</cloud-backup>'
$deviceEx = Get-ExcludesFromText $dataText '(?is)<device-transfer\b[^>]*>(.*?)</device-transfer>'

if ($cloudEx.Count -eq 0) { Fail "data_extraction_rules.xml no contiene exclusiones en <cloud-backup>" }
if ($deviceEx.Count -eq 0) { Fail "data_extraction_rules.xml no contiene exclusiones en <device-transfer>" }

Check-NoFullDomain $backupEx "backup_rules.xml"
Check-NoFullDomain $cloudEx "data_extraction_rules.xml/cloud-backup"
Check-NoFullDomain $deviceEx "data_extraction_rules.xml/device-transfer"
Ok "sin exclusiones de dominio completo"

$backupSet = @($backupEx | ForEach-Object { Exclude-Key $_.domain $_.path } | Sort-Object -Unique)
$cloudSet = @($cloudEx | ForEach-Object { Exclude-Key $_.domain $_.path } | Sort-Object -Unique)
$deviceSet = @($deviceEx | ForEach-Object { Exclude-Key $_.domain $_.path } | Sort-Object -Unique)

foreach ($r in $required) {
  $k = Exclude-Key $r.domain $r.path
  if ($backupSet -notcontains $k) { Fail "backup_rules.xml falta exclusión requerida: domain=$($r.domain) path=$($r.path)" }
  if ($cloudSet -notcontains $k) { Fail "data_extraction_rules.xml/cloud-backup falta exclusión requerida: domain=$($r.domain) path=$($r.path)" }
  if ($deviceSet -notcontains $k) { Fail "data_extraction_rules.xml/device-transfer falta exclusión requerida: domain=$($r.domain) path=$($r.path)" }
}
Ok "todas las rutas sensibles requeridas del inventario están excluidas"

$diff1 = Compare-Object $backupSet $cloudSet
$diff2 = Compare-Object $cloudSet $deviceSet
if ($diff1 -or $diff2) {
  Fail "incoherencia de exclusiones: backup_rules, cloud-backup y device-transfer deben cubrir el mismo conjunto (domain,path). backup=[$($backupSet -join ',')] cloud=[$($cloudSet -join ',')] device=[$($deviceSet -join ',')]"
}
Ok "backup_rules.xml y data_extraction_rules.xml (cloud-backup/device-transfer) son coherentes"
Ok "verify_backup_rules: PASS"
