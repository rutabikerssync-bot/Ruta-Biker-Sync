# Inventario de almacenamiento local (T-08, B3-3/B3-4/B3-5)

**Alcance:** evidencia estática en `android/app/src/main` (grep/inspección de código y recursos). **No** se declaran almacenes no identificados como cubiertos.  
**Generado:** 2026-11-16 (análisis estático).  
**Relacionado:** `docs/PERMISSIONS_USAGE_REPORT_T08.md`, `docs/PERMISSIONS_MATRIX_T08.md`, `android/app/src/main/res/xml/backup_rules.xml`, `android/app/src/main/res/xml/data_extraction_rules.xml`.

## Metodología (búsquedas realizadas)
- Room/SQLite: `rg -n "@Database|Room\.databaseBuilder|RoomDatabase|openOrCreateDatabase|SQLiteOpenHelper|\.db\b" android/app/src/main`
- SharedPreferences: `rg -n "getSharedPreferences|getPreferences|SharedPreferences|rbs_|ruta_biker_gamification" android/app/src/main`
- Archivos/rutas: `rg -n "filesDir|cacheDir|getExternalFilesDir|getExternalCacheDir|openFileOutput|openFileInput|File\(|exports|club_logos|voice_clips|incoming_clips|uxtest" android/app/src/main`
- DataStore: `rg -n "DataStore|datastore|preferencesDataStore" android/app/src/main` → **sin coincidencias** (no identificado uso de Jetpack DataStore en `src/main`).
- Reglas XML: inspección de `backup_rules.xml` y `data_extraction_rules.xml`.

## Tabla de inventario (almacenes identificados)

| Almacén | Tipo | Ruta/Nombre | Sensibilidad | Evidencia (archivo:línea) | Cubierto por backup/data-extraction (SÍ/NO/PARCIAL) | Acción |
|---|---|---|---|---|---|---|
| `ruta_biker_sync.db` (+ archivos Room/WAL/SHM asociados en runtime) | Room/SQLite (`database`) | `databases/ruta_biker_sync.db` | **Alta**: `PendingSosRetryEntity` almacena `latitude`, `longitude`, `hasMedicalSheet`, `notes`, `meshPacketId` (SOS/reintentos, potencial salud/ubicación); `PendingGamificationEvent` almacena métricas/eventos por `rideId` | `core/gamification/AppDatabase.kt:14,22,33-37` (DB/entities); `core/mesh/sos/PendingSosRetryEntity.kt:17-31` (lat/lon/medical/notes); `core/gamification/PendingGamificationEvent.kt:27-36` | **SÍ** (tras ajuste T2): excluido quirúrgicamente en `backup_rules.xml` y en `data_extraction_rules.xml` (`cloud-backup` y `device-transfer`) como `domain="database" path="ruta_biker_sync.db"` | Excluir archivo de BD específico (quirúrgico). No excluir dominio `database` completo. |
| `rbs_encrypted_session_store.xml` | SharedPreferences | `shared_prefs/rbs_encrypted_session_store.xml` | **Alta**: refresh token cifrado (AES-GCM) / estado de sesión (no portable, credenciales de acceso) | `core/network/SecureTokenStore.kt:47-49,78,91,107,124` (prefs y cifrado; claves en Keystore) | **SÍ**: excluido quirúrgicamente (ya existente, mantenido) en ambos XML y en ambos modos de extracción | Mantener exclusión quirúrgica. |
| `rbs_biker_vault_local_store.xml` | SharedPreferences | `shared_prefs/rbs_biker_vault_local_store.xml` | **Alta**: estado local de bóveda/documentos médicos/recuperación (datos sensibles/salud según implementación) | `feature/vault/VaultRepository.kt:70,76-77,102,178,309,369,423,465,536,559,643,649` | **SÍ**: excluido quirúrgicamente (ya existente, mantenido) | Mantener exclusión quirúrgica. |
| `rbs_vault_keystore_prefs.xml` | SharedPreferences | `shared_prefs/rbs_vault_keystore_prefs.xml` | **Alta**: material criptográfico de bóveda (`wrapped_master_key_v1`, `vault_user_salt_v1`) envuelto con KEK AndroidKeyStore (`rbs_vault_kek_v1`); no portable entre dispositivos/instalaciones seguras | `feature/vault/VaultCrypto.kt:37,44-46,55,97-99,176,181,307` | **SÍ** (tras ajuste T2): excluido quirúrgicamente en ambos XML y en ambos modos | Añadir exclusión quirúrgica (material de claves/salt). |
| `rbs_sos_crypto_prefs.xml` | SharedPreferences | `shared_prefs/rbs_sos_crypto_prefs.xml` | **Alta**: claves/estado criptográfico SOS (clave privada envuelta `wrapped_sos_priv_key_v1`, pública SPKI, IV; identidad/firmas SOS) | `core/mesh/sos/Ed25519SosCrypto.kt:45-50,128,239,247` | **SÍ** (tras ajuste T2): excluido quirúrgicamente en ambos XML y en ambos modos | Añadir exclusión quirúrgica (material de claves SOS). |
| `rbs_auth_prefs.xml` | SharedPreferences | `shared_prefs/rbs_auth_prefs.xml` | **Alta (PII/sensible)**: `user_id`, email, display name, foto URL/base64, tipo de cuenta/club, contacto de emergencia, tipo de sangre, datos y foto de moto (incl. serial/base64), visibilidad de perfil/moto | `feature/auth/ProfileRepository.kt:32-36,89,159,215,231,233,238` y claves en mismo archivo; lecturas/escrituras también en `core/network/ClubApiClient.kt:102,122,143`, `feature/groups/ClubRepository.kt:696,702`, `feature/groups/Phase1ViewModel.kt:673,1000,1027,1055-1056`, `feature/vault/VaultRepository.kt:102`, `feature/auth/WelcomeAuthScreen.kt:87` | **SÍ** (tras ajuste T2): excluido quirúrgicamente en ambos XML y en ambos modos | Añadir exclusión quirúrgica (PII y datos sensibles de perfil/salud emergente). |
| `rbs_fcm_prefs.xml` | SharedPreferences | `shared_prefs/rbs_fcm_prefs.xml` | **Media-Alta**: token FCM cacheado (`cached_fcm_token`, identificador de mensajería push vinculado al dispositivo/instancia) | `core/network/RutaBikerMessagingService.kt:45-47,117,122-123` | **SÍ** (tras ajuste T2): excluido quirúrgicamente en ambos XML y en ambos modos | Añadir exclusión quirúrgica (token no portable/renovable). |
| `rbs_settings_prefs.xml` | SharedPreferences | `shared_prefs/rbs_settings_prefs.xml` | **Baja**: preferencias de usuario (idioma, notificaciones habilitadas) | `core/util/LocaleHelper.kt:31,71,94-95`; `feature/profile/ProfileScreen.kt:119,1123` | **NO** (excluido intencionalmente no aplicado): datos de preferencia no sensibles, restaurables | Mantener respaldable/transferible. |
| `rbs_theme_prefs.xml` | SharedPreferences | `shared_prefs/rbs_theme_prefs.xml` | **Baja**: preferencia de tema (modo oscuro) | `MainActivity.kt:45,101,147` | **NO** | Mantener respaldable/transferible. |
| `rbs_geo_cache.xml` | SharedPreferences | `shared_prefs/rbs_geo_cache.xml` | **Media (ubicación aproximada cacheada)**: última ubicación conocida (`last_known_lat/lon`) como caché de conveniencia para mapa/planificación | `feature/livemap/LiveMapScreen.kt:434,437-440`; `feature/routes/ScheduleRideMapScreen.kt:142,697` | **NO** (no excluido en este ajuste): caché regenerable; exclusión quirúrgica posible si producto lo requiere. Riesgo residual documentado. | **REVISAR_PRODUCTO** (no excluido para preservar UX; no es secreto criptográfico). Documentado como caché de ubicación. |
| `rbs_mesh_prefs.xml` | SharedPreferences | `shared_prefs/rbs_mesh_prefs.xml` | **Baja**: contador/epoch de arranque mesh (`mesh_boot_epoch`) | `core/mesh/BleMeshService.kt:508,511` | **NO** | Mantener respaldable/transferible (no sensible). |
| `ruta_biker_gamification` | SharedPreferences | `shared_prefs/ruta_biker_gamification.xml` | **Baja-Media**: métricas agregadas de gamificación (progreso/badges locales según implementación) | `feature/badges/GamificationRepository.kt:14,18,32,372-373,406,437,501` | **NO** | Mantener respaldable/transferible (sin PII directa/secretos identificados en esta revisión). |
| `rbs_ux_test_prefs.xml` | SharedPreferences | `shared_prefs/rbs_ux_test_prefs.xml` | **Baja**: estado/IDs de pruebas UX (builds `uxTest`) | `core/uxtest/UxTestTracker.kt:90,146,154-155` | **NO** | No excluido (no sensible). |
| `exports/` (RGPD/privacidad) | Archivos (`cacheDir`) | `cache/exports/rbs_export_<timestamp>.json` | **Alta**: exportación de datos personales del usuario (RGPD R15.5) generada para compartir | `feature/groups/Phase1ViewModel.kt:1269-1271` (crea en `cacheDir/exports`); `res/xml/file_paths.xml:3` (`<cache-path name="exports" path="exports/"/>` para FileProvider) | **PARCIAL (no aplica dominio estándar):** `cacheDir` no es dominio estándar de Auto Backup/full-backup-content ni de data-extraction-rules (`file/database/sharedpref/external/root`); en la práctica no se respalda como `filesDir`. Exclusión explícita quirúrgica vía estos XML **no aplicable** al dominio cache. | Documentar: datos sensibles en cache efímera/compartida; riesgo de backup bajo por dominio. Revisar ciclo de vida/borrado en runtime. |
| `voice_clips/`, `incoming_clips/` | Archivos (`cacheDir`) | `cache/voice_clips/*`, `cache/incoming_clips/*` | **Alta (audio potencialmente sensible/PII)**: clips PTT grabados/recibidos (efímeros, se eliminan en reproducción/flujo) | `core/audio/OpusVoiceRecorder.kt:76-78,100`; `core/audio/VoiceClipMeshRelay.kt:23,27,132,195-196`; `core/audio/VoiceClipModel.kt:43-45`; `core/audio/VoiceClipPlayerQueue.kt:80,112,170,193,199` | **PARCIAL (no aplica dominio estándar):** en `cacheDir`, no cubierto por dominios estándar de backup/extraction XML. | Documentar: efímero en cache; no excluible quirúrgicamente vía `full-backup-content/data-extraction-rules` por dominio. Validar borrado en runtime. |
| `club_logos/` | Archivos (`filesDir`) | `files/club_logos/logo_<groupId>.jpg` | **Media**: imagen/logo de club (contenido de usuario/grupo) | `feature/groups/Phase1ViewModel.kt:759-771`; lectura en `feature/groups/Phase1Screens.kt:1247-1249,2291-2293` | **NO** (no excluido): no identificado como secreto/PII sensible crítica; preserva restauración funcional. | Mantener respaldable/transferible (revisable por producto). |
| Reportes UX test (`uxtest_*.json`) | Archivos (`getExternalFilesDir(null)` o `filesDir`) | `files/` o `external-files/` (nombre `uxtest_<participant>_<ts>.json`) | **Baja-Media**: métricas de prueba UX, identificador de participante de test | `core/uxtest/UxTestTracker.kt:309,313,321-328,600` (solo builds/flujos `uxTest`) | **NO** (no excluido): artefactos de prueba; ruta variable (`external`/`file`) y nombre dinámico dificultan exclusión quirúrgica sin excluir dominio. | Documentado; no se excluye dominio completo (prohibido). Revisar en contexto de `uxTest`. |

## Almacenes NO identificados / no concluyentes (sin inventar)

| Búsqueda / posible almacén | Resultado | Qué se buscó / notas |
|---|---|---|
| Otras bases Room/SQLite | **No identificadas** además de `ruta_biker_sync.db` | `@Database`, `Room.databaseBuilder`, `.db` en `src/main` → solo `AppDatabase`. |
| Jetpack DataStore / Proto DataStore | **No identificado** | `rg DataStore|datastore|preferencesDataStore` en `src/main` → sin coincidencias. |
| `getExternalCacheDir` / descargas externas persistentes sensibles | **No identificado** | Búsquedas de `getExternalCacheDir`/`getExternalFilesDir` no muestran otros usos sensibles además de reportes UX (`UxTestTracker`). |
| Otras `SharedPreferences` con nombres no `rbs_*`/`ruta_biker_gamification` | **No identificadas** | Revisión de `getSharedPreferences(...)` en `src/main` cubre los nombres listados. |

## Reglas XML: estado, diff aplicado y justificación

### Antes (estado previo)
- `backup_rules.xml`: excluía solo `sharedpref` `rbs_encrypted_session_store.xml` y `rbs_biker_vault_local_store.xml`.
- `data_extraction_rules.xml`: mismas 2 exclusiones en `cloud-backup` y `device-transfer`.
- Sin exclusiones de `database` específicas; sin exclusiones para otros prefs sensibles identificados.

### Después (ajuste quirúrgico T2)
**`backup_rules.xml`** y **`data_extraction_rules.xml`** (cloud-backup y device-transfer coherentes) excluyen de forma **quirúrgica**:
- `sharedpref`: `rbs_encrypted_session_store.xml`, `rbs_biker_vault_local_store.xml`, `rbs_vault_keystore_prefs.xml`, `rbs_sos_crypto_prefs.xml`, `rbs_auth_prefs.xml`, `rbs_fcm_prefs.xml`.
- `database`: `ruta_biker_sync.db` (único archivo de BD identificado).

**Justificación por ruta añadida:**
- `rbs_vault_keystore_prefs.xml`: wrapped master key/salt de bóveda (material criptográfico ligado a Keystore/dispositivo).
- `rbs_sos_crypto_prefs.xml`: clave privada SOS envuelta y metadatos de clave pública (identidad/firmas SOS).
- `rbs_auth_prefs.xml`: PII y datos sensibles (email, nombre, fotos/base64, emergencia, sangre, datos de moto/serial, club, visibilidad).
- `rbs_fcm_prefs.xml`: token FCM (identificador de push no portable).
- `ruta_biker_sync.db`: SOS pendientes con lat/lon/medical/notes y eventos de gamificación por ride (datos sensibles/operativos).

**No se excluye:**
- Dominios completos (`database`, `file`, `sharedpref`) — **prohibido** y no aplicado.
- Prefs de preferencia/caché no sensibles (`rbs_settings_prefs`, `rbs_theme_prefs`, `rbs_mesh_prefs`, `ruta_biker_gamification`) y `rbs_geo_cache` (cache de última ubicación, decisión documentada).
- `filesDir/club_logos` (contenido de usuario no clasificado como secreto/PII crítica en este inventario).

### Impacto funcional esperado
- Tras restauración/cambio de dispositivo: puede requerirse re-autenticación, re-registro de FCM, reconfiguración/restauración de bóveda y claves SOS, y se pierde cola local de SOS/gamificación pendiente (datos efímeros/operativos). Preferencias no sensibles y archivos no excluidos pueden restaurarse.

## Validación automatizada (T3)
- Script: `scripts/verify_backup_rules.ps1` (y equivalente `.sh` si se añade) valida: presencia de exclusiones sensibles requeridas, ausencia de exclusiones de dominio completo (`path="."`/vacío o patrones equivalentes), y coherencia de rutas entre `backup_rules.xml` y secciones `cloud-backup`/`device-transfer` de `data_extraction_rules.xml`.
- Ejecución y resultados PASS/FAIL simulado: ver entregable del ticket / salida adjunta en commit.

## Pendiente (runtime/dispositivo)
- Validación real de backup/restore y device-transfer en dispositivo (Android 12+/13+/14) confirmando que rutas sensibles no se restauran y que no hay regresión funcional indebida.
- Revisión de `rbs_geo_cache`, `club_logos` y artefactos `uxtest` según política de producto.
- Confirmar que no aparecen nuevas BD/archivos/prefs sensibles en futuros cambios (re-ejecutar inventario/validación).
