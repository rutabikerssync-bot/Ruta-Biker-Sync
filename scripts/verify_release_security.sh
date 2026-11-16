#!/usr/bin/env bash
set -euo pipefail
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

fail() { echo "FAIL: $*" >&2; exit 1; }
ok() { echo "OK: $*"; }

# verify_release_security (.sh) - T-01..T-08 (condición 5)
# Reglas de aceptación (explícitas):
# 1) manifest fusionado standardRelease SIN usesCleartextTraffic="true" -> PASS
# 2) manifest fusionado CON networkSecurityConfig="@xml/network_security_config" -> PASS
# 3) BleMeshService presente Y exported="false" -> PASS; si aparece con exported="true" o sin exported -> FAIL
# 4) EmergencyLockScreenActivity presente Y exported="false" -> PASS; ídem
# 5) BASE_URL: PASS_REAL (https y sin example.invalid/TODO_EXTERNAL/localhost/.invalid/pending), FAIL_PLACEHOLDER (marcadores), FAIL_INSEGURO (no https)
# 6) grep de StrongLeaderPass!2026/StrongMemberPass!2026 en src/main y en outputs/apk = 0 -> PASS
# Debe FALLAR si falta manifest o BuildConfig (sin falso verde por ausencia de artefacto).

mapfile -t MERGED < <(find android/app/build -type f \( -path "*standardRelease*merged_manifest*/AndroidManifest.xml" -o -path "*standardRelease*merged_manifests*/AndroidManifest.xml" \) 2>/dev/null | sort -u || true)
[ ${#MERGED[@]} -gt 0 ] || fail "falta manifest fusionado de standardRelease. Genera el build/manifest primero (artefacto requerido, sin falso verde)."

for mf in "${MERGED[@]}"; do
  grep -Eq 'android:usesCleartextTraffic[[:space:]]*=[[:space:]]*"true"' "$mf" && fail "cleartext habilitado en $mf"
  grep -Eq 'android:networkSecurityConfig[[:space:]]*=[[:space:]]*"@xml/network_security_config"' "$mf" || fail "networkSecurityConfig ausente o incorrecto en $mf"
done
ok "sin cleartext y networkSecurityConfig presente en manifest(s) fusionado(s) standardRelease"

check_component() {
  local display="$1" tag="$2"; shift 2
  local names=("$@")
  local found_any=0 ok_any=0 bad_any=0
  for mf in "${MERGED[@]}"; do
    for name in "${names[@]}"; do
      python - "$mf" "$tag" "$name" <<'PY'
import re,sys
path,tag,name=sys.argv[1],sys.argv[2],sys.argv[3]
xml=open(path,encoding='utf-8',errors='replace').read()
pat=r'<%s\b(?:(?!</%s>).)*?\bandroid:name\s*=\s*"%s"(?:(?!</%s>).)*?</%s>'%(tag,tag,re.escape(name),tag,tag)
m=re.findall(pat,xml,flags=re.I|re.S)
if not m:
    print('NONE')
    sys.exit(0)
for b in m:
    if re.search(r'android:exported\s*=\s*"false"',b,flags=re.I):
        print('OK')
    elif re.search(r'android:exported\s*=\s*"true"',b,flags=re.I) or not re.search(r'android:exported\s*=',b,flags=re.I):
        print('BAD')
    else:
        print('BAD')
PY
    done
  done | while read -r r; do
    [ "$r" = "NONE" ] || found_any=1
    [ "$r" = "OK" ] && ok_any=1
    [ "$r" = "BAD" ] && bad_any=1
    # communicate via files not possible in pipe easily; use markers
    echo "$found_any $ok_any $bad_any $r"
  done >/tmp/_rbs_comp_$$.txt 2>/dev/null || true
  # simpler python-based aggregate
  python - "$display" "$tag" "${MERGED[@]}" -- "${names[@]}" <<'PY'
import re,sys
display=sys.argv[1]; tag=sys.argv[2]
args=sys.argv[3:]
sep=args.index('--')
mfs=args[:sep]; names=args[sep+1:]
found=ok=bad=False
for mf in mfs:
    xml=open(mf,encoding='utf-8',errors='replace').read()
    for name in names:
        pat=r'<%s\b(?:(?!</%s>).)*?\bandroid:name\s*=\s*"%s"(?:(?!</%s>).)*?</%s>'%(tag,tag,re.escape(name),tag,tag)
        for b in re.findall(pat,xml,flags=re.I|re.S):
            found=True
            if re.search(r'android:exported\s*=\s*"false"',b,flags=re.I): ok=True
            elif re.search(r'android:exported\s*=\s*"true"',b,flags=re.I) or not re.search(r'android:exported\s*=',b,flags=re.I): bad=True
if not found:
    print('NF'); sys.exit(10)
if bad:
    print('BAD'); sys.exit(11)
if not ok:
    print('NOK'); sys.exit(12)
print('OK')
PY
  rc=$?
  case $rc in
    0) ok "$display presente y exported=false" ;;
    10) fail "$display no encontrado en manifests fusionados standardRelease" ;;
    11) fail "$display aparece con exported=\"true\" o sin exported en manifest fusionado" ;;
    12) fail "$display no está exported=\"false\" en manifests fusionados standardRelease" ;;
    *) fail "$display: error de verificación ($rc)" ;;
  esac
}

check_component "BleMeshService" "service" ".core.mesh.BleMeshService" "com.rutabikersync.app.core.mesh.BleMeshService"
check_component "EmergencyLockScreenActivity" "activity" ".feature.sos.EmergencyLockScreenActivity" "com.rutabikersync.app.feature.sos.EmergencyLockScreenActivity"

BC="android/app/build/generated/source/buildConfig/standard/release/com/rutabikersync/app/BuildConfig.java"
[ -f "$BC" ] || fail "falta BuildConfig de standardRelease ($BC). Artefacto requerido, sin falso verde."
BASE_URL_VAL=$(sed -n 's/.*String[[:space:]]\+BASE_URL[[:space:]]*=[[:space:]]*"\([^"]*\)".*/\1/p' "$BC" | head -n1)
[ -n "$BASE_URL_VAL" ] || fail "FAIL_INSEGURO: BASE_URL no encontrada en BuildConfig standardRelease"
echo "$BASE_URL_VAL" | grep -Eq '^https://' || fail "FAIL_INSEGURO: BASE_URL release no empieza por https:// (esquema no TLS). Actual=$BASE_URL_VAL"
if echo "$BASE_URL_VAL" | grep -Eiq 'example\.invalid|TODO_EXTERNAL|localhost|\.invalid|pending'; then
  fail "FAIL_PLACEHOLDER: BASE_URL release es placeholder, falta URL_PROD real (.invalid/pending/TODO_EXTERNAL/example.invalid/localhost no permitidos). Actual=$BASE_URL_VAL"
fi
ok "PASS_REAL: BASE_URL release https sin placeholders"

if grep -RIn --exclude-dir=build --include='*.kt' --include='*.java' --include='*.xml' -e "StrongLeaderPass!2026" -e "StrongMemberPass!2026" android/app/src/main >/dev/null 2>&1; then
  fail "credenciales seed encontradas en src/main"
fi
if [ -d "android/app/build/outputs/apk" ] && grep -Rla -e "StrongLeaderPass!2026" -e "StrongMemberPass!2026" android/app/build/outputs/apk >/dev/null 2>&1; then
  fail "credenciales seed encontradas en outputs/apk"
fi
ok "sin credenciales seed en src/main y outputs/apk"

echo "verify_release_security: PASS"