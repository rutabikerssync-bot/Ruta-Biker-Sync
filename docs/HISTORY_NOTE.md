# HISTORY_NOTE - trazabilidad de commits de hardening (auditoría 2026-11-16)

Este archivo documenta incidencias de historial detectadas en los commits de seguridad para evitar ambigüedad en auditorías futuras. **No se reescribe historial.**

## Commits con contenido no relacionado arrastrado en archivos modificados

Los siguientes commits mezclaron (en **archivos que ya estaban siendo modificados por el ticket**) cambios de hardening con contenido previo/no relacionado presente en el working tree al momento de stagear/commitear archivos completos:

- `9e41f42` — `fix(sec): remove seed credentials from main and stabilize Phase1RulesTest (T-05)`
  - **Archivos del ticket:** `android/app/src/androidTest/java/com/rutabikersync/app/feature/groups/Phase1RulesTest.kt`, `android/app/src/main/java/com/rutabikersync/app/feature/groups/ClubRepository.kt`
  - **Contenido adicional arrastrado (no relacionado con T-05, preexistente en esos archivos en el working tree):** en `ClubRepository.kt` se incluyeron cambios amplios de sincronización backend/UI, RGPD/exportación, mapeos y ajustes de lógica de grupos/perfil ajenos al alcance de "retirar seeds de `main` y estabilizar `Phase1RulesTest`". En `Phase1RulesTest.kt` se incluyeron ajustes adicionales de test más allá del aislamiento de seeds.
  - **Evidencia del diff:** `git show --name-status 9e41f42` y revisión de `git show 9e41f42 -- android/app/src/main/java/com/rutabikersync/app/feature/groups/ClubRepository.kt`.

- `4301edc` — `fix(sec): restrict BleMeshService to internal intents only (T-04)`
  - **Archivo del ticket:** `android/app/src/main/AndroidManifest.xml`
  - **Contenido adicional arrastrado:** en el mismo `AndroidManifest.xml` (archivo completo stageado) se incluyeron modificaciones no relacionadas con la restricción de `BleMeshService` (atributos/ajustes generales del manifest presentes en el working tree) junto con el cambio objetivo `exported="false"`.

- `b043fcc` — `fix(sec): restrict EmergencyLockScreenActivity and sanitize critical logs (T-06,T-07)`
  - **Archivos del ticket:** `android/app/src/main/AndroidManifest.xml`, `android/app/src/main/java/com/rutabikersync/app/feature/sos/SosRepository.kt`, `android/app/src/main/java/com/rutabikersync/app/feature/sos/SosScreens.kt`, `android/app/src/main/java/com/rutabikersync/app/feature/vault/VaultRepository.kt`, `docs/LOG_REDACTION_REPORT.md`
  - **Contenido adicional arrastrado:** en `SosRepository.kt`, `SosScreens.kt` y `VaultRepository.kt` se committearon cambios funcionales/de lógica preexistentes en el working tree además del saneo de logs y hardening de actividad; el alcance del ticket era restringir exposición y sanear logging en archivos críticos.

## Nota operativa

- No existe remoto configurado en este repositorio local; los commits anteriores no fueron reescritos (`--amend` solo se usó localmente en `7c4c815` antes de cualquier push, y no aplica a los tres commits listados arriba).
- Para correcciones futuras: stagear por hunk/paths mínimos del ticket, evitar `git add` de archivos completos con cambios mixtos, y separar commits de saneo documental/funcional no relacionado.
- Si en el futuro se requiere historial limpio para auditoría externa, evaluar una rama de saneo (sin reescribir `master` compartida) documentando la correspondencia con estos SHAs.

## B1-4 — BASE_URL parametrizada (post-revisión)

- **Cambio:** `BASE_URL` de release parametrizada con precedencia `(1) -Prbs.prod.url`, `(2) RBS_PROD_URL`, `(3) fallback controlado `https://rbs-pending.invalid` (TLD `.invalid`, contiene `pending`; bloquea release a propósito, no publicable).
- **Validación:** si se provee (1)/(2), debe ser `https://` y no contener `example.invalid`/`TODO_EXTERNAL`/`localhost`/`.invalid`/`pending`; de lo contrario el build falla en configuración con `FAIL_INSEGURO` o `FAIL_PLACEHOLDER`.
- **Gate regla 5 (`verify_release_security`):** distingue `PASS_REAL` / `FAIL_PLACEHOLDER` / `FAIL_INSEGURO`. El fallback `https://rbs-pending.invalid` es rechazado como `FAIL_PLACEHOLDER` esperado (no es `PASS_REAL`).
- **Estado:** `LISTO_PARA_URL_REAL` (gate pasa regla 5 en cuanto se provea `-Prbs.prod.url` o `RBS_PROD_URL` https real; fallback `.invalid` bloquea release). No marcar B1-4 como CERRADO hasta URL real.

## B5-4 — Firma de release parametrizada (ANDAMIAJE_LISTO)

- **Cambio:** `signingConfigs.release` parametrizada con precedencia `(a) -Prbs.keystore.path/-Prbs.keystore.password/-Prbs.key.alias/-Prbs.key.password`, `(b) RBS_KEYSTORE_PATH/RBS_KEYSTORE_PASSWORD/RBS_KEY_ALIAS/RBS_KEY_PASSWORD`. `buildTypes.release` asigna `signingConfig` de release (sin fallback a debug). Si falta cualquiera de las 4 o el keystore no existe, el build de release falla con mensaje claro (configuración cuando se solicita tarea release + `verifyReleaseSigningConfigured`).
- **Cadena:** `bundle/assembleStandardRelease` → `verifyStandardReleaseSecurity` → `verifyReleaseSigningConfigured` + `verifyReleaseSecurityScript` (B1-4 regla 5 sigue `FAIL_PLACEHOLDER` con fallback `.invalid`; no relajada).
- **Estado:** `ANDAMIAJE_LISTO` (no CERRADO). Cierre real pendiente de keystore de producción + CI + `bundleStandardRelease` firmado y `apksigner`/`jarsigner verify` con evidencia real.

- **Confirmación puntual (prueba controlada, keystore temporal fuera del repo, eliminado):** `assembleStandardRelease` con `-Prbs.keystore.*` y `-Prbs.prod.url=https://api.staging.example.test` (solo lógica) → `BUILD SUCCESSFUL`; APK verificado `android/app/build/outputs/apk/standard/release/app-standard-release.apk`; `apksigner verify --print-certs` **exit 0**, `CN=RBS Test` (no `CN=Android Debug`), SHA-256 `33c445bd9af2c9096e49e17841241eef271598b44d297d47d011e8ac97d3308b`. Gates encadenados: `verifyReleaseSigningConfigured` PASS, `verifyReleaseSecurityScript` PASS 1-6 (`PASS_REAL`), `verifyStandardReleaseSecurity` PASS. La observación previa de `CN=Android Debug` correspondía a un artefacto distinto, no a un bug de `signingConfigs.release`. **B5-4 permanece `ANDAMIAJE_LISTO` (no `[x]`)**: cierre real pendiente de keystore de producción + CI + verificación con certificado/huella reales. Sin secretos, keystore ni rutas personales commiteados.

## B3-3/B3-4/B3-5 — Inventario de almacenamiento y reglas de backup/extracción (cierre estático)

- **Inventario:** `docs/STORAGE_INVENTORY_T08.md` (Room/DB, SharedPreferences, files/cache, DataStore no identificado) con evidencia archivo:línea, sensibilidad, cobertura y acciones. Almacenes sensibles identificados: `ruta_biker_sync.db`, `rbs_encrypted_session_store`, `rbs_biker_vault_local_store`, `rbs_vault_keystore_prefs`, `rbs_sos_crypto_prefs`, `rbs_auth_prefs`, `rbs_fcm_prefs`; datos sensibles en cache (`exports/`, clips voz) documentados (dominio cache no cubierto por reglas XML estándar).
- **Reglas XML (quirúrgicas, coherentes):** `backup_rules.xml` y `data_extraction_rules.xml` (`cloud-backup`/`device-transfer`) excluyen las rutas sensibles anteriores; **sin exclusión de dominios completos**.
- **Validación automatizada:** `scripts/verify_backup_rules.ps1` (y `.sh`) — PASS en estado actual; FAIL simulado comprobado (exclusión requerida faltante y exclusión de dominio completo `path="."`).
- **Permisos (B3-3):** `docs/PERMISSIONS_USAGE_REPORT_T08.md` actualizado con evidencia uso/no uso, clasificación y recomendación por permiso (sin eliminaciones). `docs/PERMISSIONS_MATRIX_T08.md` actualizado con almacén asociado y cobertura backup.
- **Estado:** `CERRADO_ESTATICO_PENDIENTE_RUNTIME` (no `[x]`): inventario/reglas/validación estáticos completos; pendiente validación runtime de backup/restore y device-transfer en dispositivo, y decisión de producto sobre permisos candidatos a revisar/eliminar.
