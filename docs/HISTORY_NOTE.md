# HISTORY NOTE — Contenido preexistente en commits de seguridad (2026-11-16)

## Contexto

Durante la implementación de los tickets T-01…T-08 (auditoría de seguridad
release, 2026-11-16) el working tree contenía modificaciones preexistentes no
commiteadas en varios archivos. Al crear commits atómicos por ticket se usó
`git add <archivo>`, que stagea el **estado completo** del archivo, no solo el
cambio del ticket. Como resultado, tres commits contienen, además del cambio de
seguridad, contenido preexistente del working tree.

**No se reescribió el historial.** El repositorio no tiene remoto configurado
(`git remote -v` vacío, `origin/master` inexistente), por lo que la reescritura
sería local y de riesgo cero, pero se decidió no hacerlo para evitar
inestabilidad y porque el cambio de seguridad real está identificado y es
verificable por separado. Este documento deja la trazabilidad para auditoría
futura.

## Commits afectados

### `9e41f42` — fix(sec): T-05 remove hardcoded seed credentials from main (B4-4)

- **Cambio de seguridad real:** retirar `StrongLeaderPass!2026` /
  `StrongMemberPass!2026` de `src/main`; `executeRealBackendFlow` pasa a recibir
  credenciales por parámetro; `Phase1RulesTest` las inyecta desde env
  (`RBS_E2E_LEADER_PASS` / `RBS_E2E_MEMBER_PASS`).
- **Contenido preexistente arrastrado:** modificaciones previas en
  `ClubRepository.kt` (~+1240/−210) y `Phase1RulesTest.kt` (~+248) no
  relacionadas con T-05, incluidas por staging de archivo completo.
- **Verificación del cambio real:** grep de seeds sobre `src/main` = 0;
  `Phase1RulesTest` en verde.

### `4301edc` — fix(sec): T-04 restrict BleMeshService to non-exported (B2-4)

- **Cambio de seguridad real:** `android:exported="false"` en `BleMeshService`
  del manifest; allowlist defensiva de acciones en `onStartCommand`; scripts
  `verify_ble_mesh_posture.{sh,ps1}`.
- **Contenido preexistente arrastrado:** `BleMeshService.kt` aparece como
  +1126 líneas (estado completo del archivo), y `AndroidManifest.xml` con delta
  amplio (+61/−1), incluyendo cambios previos del working tree.
- **Verificación del cambio real:** `verify_ble_mesh_posture.ps1` → OK,
  `exported=false`.

### `b043fcc` — fix(sec): T-06+T-07 redact sensitive logs and restrict lock screen activity

- **Cambio de seguridad real:** saneo de logs en `SosRepository.kt`,
  `VaultRepository.kt`, `SosScreens.kt`; `EmergencyLockScreenActivity`
  `exported="false"` con validación de lanzamiento interno; inventario en
  `docs/LOG_REDACTION_REPORT.md`.
- **Contenido preexistente arrastrado:** los cuatro archivos fuente aparecen
  con contenido completo de gran tamaño (~2572 líneas agregadas), consistente
  con staging de versiones que ya contenían cambios previos.
- **Verificación del cambio real:** `docs/LOG_REDACTION_REPORT.md` con 12
  saneados / 170 pendientes justificados / 0 sin justificar.

## Qué NO incluye este documento

- No revisa ni valida el contenido preexistente arrastrado (fuera de alcance
  de la auditoría de seguridad).
- No reescribe historial ni corrige los commits.
- No cierra ningún gate de release: T-01/T-02/T-03/T-04/T-05/T-06/T-07/T-08
  siguen con verificación pendiente según CHECKLIST.md.

## Recomendación para revisión futura

Si en algún momento se hace `git revert` o `cherry-pick` de estos commits,
revisar el diff funcional de seguridad contra el estado previo conocido, no el
diff completo del commit. Para cambios de seguridad futuros, preferir
`git add -p` o `git stash` del working tree previo antes de iniciar tickets.
