#!/usr/bin/env bash
set -euo pipefail
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

BACKUP_RULES="${1:-android/app/src/main/res/xml/backup_rules.xml}"
DATA_RULES="${2:-android/app/src/main/res/xml/data_extraction_rules.xml}"

fail() { echo "FAIL: $*" >&2; exit 1; }
ok() { echo "OK: $*"; }

[ -f "$BACKUP_RULES" ] || fail "no existe $BACKUP_RULES"
[ -f "$DATA_RULES" ] || fail "no existe $DATA_RULES"

python - "$BACKUP_RULES" "$DATA_RULES" <<'PY'
import re,sys
backup_path,data_path=sys.argv[1],sys.argv[2]
with open(backup_path,encoding='utf-8',errors='replace') as f: btxt=f.read()
with open(data_path,encoding='utf-8',errors='replace') as f: dtxt=f.read()

required=[
 ("sharedpref","rbs_encrypted_session_store.xml"),
 ("sharedpref","rbs_biker_vault_local_store.xml"),
 ("sharedpref","rbs_vault_keystore_prefs.xml"),
 ("sharedpref","rbs_sos_crypto_prefs.xml"),
 ("sharedpref","rbs_auth_prefs.xml"),
 ("sharedpref","rbs_fcm_prefs.xml"),
 ("database","ruta_biker_sync.db"),
]

def excludes(text):
 out=set(); raw=[]
 for m in re.finditer(r'<exclude\s+([^>]+)/?>', text, flags=re.I):
  attrs=m.group(1)
  dm=re.search(r'domain\s*=\s*"([^"]*)"', attrs, flags=re.I)
  pm=re.search(r'path\s*=\s*"([^"]*)"', attrs, flags=re.I)
  d=(dm.group(1) if dm else "")
  p=(pm.group(1) if pm else "")
  out.add((d.lower(),p.lower())); raw.append((d,p,m.group(0)))
 return out,raw

def section(text,tag):
 m=re.search(r'<%s\b[^>]*>(.*?)</%s>'%(tag,tag), text, flags=re.I|re.S)
 return m.group(1) if m else ""

bset,braw=excludes(btxt)
cset,craw=excludes(section(dtxt,'cloud-backup'))
dset,draw=excludes(section(dtxt,'device-transfer'))
if not cset: print('FAIL: data_extraction_rules.xml no contiene exclusiones en <cloud-backup>'); sys.exit(1)
if not dset: print('FAIL: data_extraction_rules.xml no contiene exclusiones en <device-transfer>'); sys.exit(1)

def bad_path(p):
 return p.strip() in ['.','','*','.*','./*','./']

for label,raw in [('backup_rules.xml',braw),('data_extraction_rules.xml/cloud-backup',craw),('data_extraction_rules.xml/device-transfer',draw)]:
 for d,p,r in raw:
  if bad_path(p):
   print(f'FAIL: {label} contiene exclusión de dominio completo o inválida: {r}')
   sys.exit(1)
print('OK: sin exclusiones de dominio completo')
for d,p in required:
 k=(d.lower(),p.lower())
 if k not in bset: print(f'FAIL: backup_rules.xml falta exclusión requerida: domain={d} path={p}'); sys.exit(1)
 if k not in cset: print(f'FAIL: data_extraction_rules.xml/cloud-backup falta exclusión requerida: domain={d} path={p}'); sys.exit(1)
 if k not in dset: print(f'FAIL: data_extraction_rules.xml/device-transfer falta exclusión requerida: domain={d} path={p}'); sys.exit(1)
print('OK: todas las rutas sensibles requeridas del inventario están excluidas')
if bset!=cset or cset!=dset:
 print(f'FAIL: incoherencia de exclusiones: backup={sorted(bset)} cloud={sorted(cset)} device={sorted(dset)}')
 sys.exit(1)
print('OK: backup_rules.xml y data_extraction_rules.xml (cloud-backup/device-transfer) son coherentes')
print('OK: verify_backup_rules: PASS')
PY
