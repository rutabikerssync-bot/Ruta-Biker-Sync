# Matriz pantalla ↔ permiso ↔ degradación (T-08, B3-1/B3-3/B3-4/B3-5)

**Fuente:** `android/app/src/main/AndroidManifest.xml` y `docs/PERMISSIONS_USAGE_REPORT_T08.md` (evidencia estática). Inventario de almacenamiento: `docs/STORAGE_INVENTORY_T08.md`.  
**Alcance:** análisis estático; **no se eliminan permisos** sin decisión de producto/evidencia funcional. Validación runtime/UI y backup/restore en dispositivo pendientes.

## Resumen de postura
- Permisos de red, ubicación, Bluetooth, cámara, micrófono, notificaciones, biometría, vibración, wake lock y foreground services sustentan mapa/rodada, mesh/PTT, SOS, QR y bóveda (ver uso en `docs/PERMISSIONS_USAGE_REPORT_T08.md`).
- Solicitud en contexto y degradación deben verificarse por pantalla en dispositivos reales.
- B3-3: sin eliminaciones aplicadas; reporte de uso/no uso con recomendaciones disponible para producto.

## Matriz (evidencia estática + flujo esperado)

| Pantalla / Flujo | Permisos implicados | Justificación funcional (esperada) | Degradación esperada ante denegación | Almacén local asociado (si aplica) | Cobertura backup/extracción (inventario) | Evidencia / estado |
|---|---|---|---|---|---|---|
| Mapa en vivo / Cabina / Rodada | `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION`, `FOREGROUND_SERVICE_LOCATION`, `INTERNET`, `ACCESS_NETWORK_STATE` | Geolocalización/tracking y visualización de ruta/convoy | Sin posición precisa o tracking limitado; rodada en vivo degradada | `rbs_geo_cache` (caché última ubicación, prefs, media), `ruta_biker_sync.db` (eventos/SOS retry según flujo) | `rbs_geo_cache`: **NO excluido** (preferencia/caché, documentado). `ruta_biker_sync.db`: **excluido** (quirúrgico, sensible) | `LiveMapScreen`, `ScheduleRideMapScreen`, `RideService`, `MainActivity`, `MainAppScaffold`; runtime pendiente |
| Mesh BLE / Descubrimiento cercano / PTT | `BLUETOOTH_SCAN`, `BLUETOOTH_ADVERTISE`, `BLUETOOTH_CONNECT`, `BLUETOOTH`/`BLUETOOTH_ADMIN` (<=30), `FOREGROUND_SERVICE_CONNECTED_DEVICE`, `POST_NOTIFICATIONS`, `WAKE_LOCK` | Descubrimiento/enlace BLE, servicio foreground, alertas | Mesh/PTT no disponible o parcial; modo limitado/offline | `rbs_mesh_prefs` (baja), `rbs_sos_crypto_prefs` (alta, claves SOS), `ruta_biker_sync.db` (SOS retry) | `rbs_mesh_prefs`: **NO excluido**. `rbs_sos_crypto_prefs` y `ruta_biker_sync.db`: **excluidos** | `BleMeshService` (`exported=false`), `RideService`, transporte BLE; runtime pendiente |
| PTT / Audio en malla | `RECORD_AUDIO`, `FOREGROUND_SERVICE_MICROPHONE`, `MODIFY_AUDIO_SETTINGS`, `WAKE_LOCK` | Captura/transmisión de voz y audio focus/ducking | PTT deshabilitado; resto usable | `cache/voice_clips`, `cache/incoming_clips` (audio efímero, alta sensibilidad) | **PARCIAL/no aplica dominio estándar** (cacheDir no cubierto por dominios XML estándar; efímero) | `OpusVoiceRecorder`, `PttAudioEngine`, `AudioDuckingManager`/`AudioFocusController`; hardware pendiente |
| SOS / Emergencia / Lock screen | `ACCESS_FINE_LOCATION` (contexto), `POST_NOTIFICATIONS`, `WAKE_LOCK`, `VIBRATE` | Alertas SOS, notificaciones, vibración, pantalla de emergencia | Sin ubicación precisa/notificaciones/vibración según permiso | `ruta_biker_sync.db` (reintentos SOS con lat/lon), `rbs_sos_crypto_prefs` (claves) | **Excluidos** (quirúrgico) | `SosCountdownManager`, SOS/mesh, `EmergencyLockScreenActivity` (`exported=false`); runtime pendiente |
| Escaneo QR (invites/unirse) | `CAMERA` | Lectura de códigos QR | Escaneo no disponible; alternativa manual si existe | — | N/A | `QrScannerHelper`, `GroupInviteQrHelper`; QA pendiente |
| Bóveda / Documentos / Biometría | `USE_BIOMETRIC`, `INTERNET` (sync/API según flujo) | Desbloqueo biométrico y gestión de bóveda/documentos | Fallback a credencial de dispositivo o acceso denegado a funciones protegidas | `rbs_biker_vault_local_store`, `rbs_vault_keystore_prefs` (alta), posible contenido asociado en flujos | **Excluidos** (quirúrgico) | `VaultBiometricGate`, `VaultRepository`, `VaultCrypto`; runtime pendiente |
| Perfil / Cuenta / Identidad | `INTERNET` (API) | Perfil, auth, membresía/club | Sin sincronización remota; funciones limitadas offline | `rbs_auth_prefs` (PII/salud emergente), `rbs_encrypted_session_store` (tokens), `files/club_logos/*` (media) | `rbs_auth_prefs`, `rbs_encrypted_session_store`: **excluidos**. `club_logos`: **NO excluido** | `ProfileRepository`, `SecureTokenStore`, `ClubApiClient`, `Phase1ViewModel`; runtime pendiente |
| Notificaciones (FCM/estado) | `POST_NOTIFICATIONS`, `INTERNET` | Push y avisos operativos/seguridad | Sin notificaciones visibles; solo in-app | `rbs_fcm_prefs` (token FCM) | **Excluido** (quirúrgico) | `RutaBikerMessagingService`, `App`, servicios; Android 13+ pendiente |
| Exportación/compartición de datos (RGPD) | (ninguno dedicado; comparte vía intents/FileProvider) | Exportar y compartir datos del usuario | Función no disponible si flujo falla | `cache/exports/rbs_export_*.json` (alta) | **PARCIAL/no aplica dominio estándar** (cacheDir, efímero/compartido) | `Phase1ViewModel`, `file_paths.xml`; revisar borrado/retención |

## Almacenamiento sensible y reglas de backup/data extraction (B3-4/B3-5)

**Inventario fuente:** `docs/STORAGE_INVENTORY_T08.md` (tabla completa, evidencias y clasificación).

**Exclusiones quirúrgicas aplicadas (coherentes en `backup_rules.xml`, `data_extraction_rules.xml` → `cloud-backup` y `device-transfer`):**
| Dominio | Ruta | Motivo |
|---|---|---|
| `sharedpref` | `rbs_encrypted_session_store.xml` | Sesión/refresh token cifrado (no portable) |
| `sharedpref` | `rbs_biker_vault_local_store.xml` | Estado local de bóveda/datos médicos |
| `sharedpref` | `rbs_vault_keystore_prefs.xml` | Material criptográfico de bóveda (wrapped key/salt) |
| `sharedpref` | `rbs_sos_crypto_prefs.xml` | Material criptográfico SOS (claves/identidad) |
| `sharedpref` | `rbs_auth_prefs.xml` | PII y datos sensibles de perfil/emergencia/moto |
| `sharedpref` | `rbs_fcm_prefs.xml` | Token FCM (identificador push) |
| `database` | `ruta_biker_sync.db` | SOS pendientes (ubicación/salud/notas) y eventos gamificación |

**No excluido (decisión documentada):** dominios completos **no** excluidos (prohibido). Prefs/cachés no sensibles o de preferencia (`rbs_settings_prefs`, `rbs_theme_prefs`, `rbs_mesh_prefs`, `ruta_biker_gamification`) respaldables; `rbs_geo_cache` no excluido (caché de ubicación, revisable por producto); `files/club_logos` no excluido. `cache/exports` y clips de voz en `cacheDir` son sensibles pero están fuera de dominios estándar de estas reglas (cache no respaldada por defecto; exclusión XML quirúrgica no aplicable).

**Validación automatizada:** `scripts/verify_backup_rules.ps1` (y `.sh`) comprueba rutas requeridas, ausencia de exclusión de dominio completo y coherencia entre archivos/secciones.

## Validación pendiente (runtime/QA)
- Solicitud en contexto, rationale y degradación por pantalla (Android 12+/13+/14).
- Backup/restore real y device-transfer: confirmar no restauración de rutas excluidas y ausencia de regresión funcional.
- Revisión de producto: `ACCESS_NETWORK_STATE` y permisos legacy BT (candidatos a revisar), y política para `rbs_geo_cache`/`club_logos`.

## Eliminaciones propuestas (no aplicadas)
- **Ninguna eliminación automática en este cambio.** Requiere evidencia de no uso por flujo/flavor (incl. `fieldTest/uxTest`) y pruebas de regresión.
- Candidatos a revisar con evidencia adicional: permisos de almacenamiento/medios legados si la app usa exclusivamente APIs modernas/scoped storage; permisos Bluetooth heredados en minSdk/targets actuales.

## Almacenamiento local sensible y exclusiones de backup/data extraction (T-08)

**Rutas conocidas excluidas (quir?rgico, basado en inventario est?tico):**
| Dominio | Ruta | Contenido sensible esperado | Regla |
|---|---|---|---|
| `sharedpref` | `rbs_encrypted_session_store.xml` | Tokens/sesi?n cifrada (no transferible entre dispositivos) | Excluido en `backup_rules.xml` y en `data_extraction_rules.xml` (cloud-backup y device-transfer) |
| `sharedpref` | `rbs_biker_vault_local_store.xml` | Estado/local store de b?veda y datos m?dicos/recuperaci?n (seg?n `VaultRepository`) | Excluido en `backup_rules.xml` y en `data_extraction_rules.xml` (cloud-backup y device-transfer) |

**No excluido (sin evidencia de rutas adicionales en inventario):** dominios `database` y `file` completos ya no se excluyen para evitar p?rdida funcional innecesaria. No se identificaron nombres concretos de BD/archivos sensibles adicionales por grep est?tico en este ticket.

**P?rdida funcional / impacto:**
- Al excluir los SharedPreferences sensibles, **no se respaldan ni transfieren** tokens de sesi?n y datos locales de b?veda/m?dicos asociados a esas rutas: tras restauraci?n/reinstalaci?n/cambio de dispositivo, el usuario puede requerir **re-autenticaci?n**, reconfiguraci?n de b?veda y/o **re-descarga/restauraci?n** de informaci?n seg?n flujos existentes.
- Al no excluir `database`/`file` completos, se preserva la posibilidad de restaurar otros datos de app no identificados como sensibles, pero existe riesgo residual si aparecen nuevas rutas sensibles no inventariadas (requiere revisi?n en futuras iteraciones).
- `SharedPreferences`: `rbs_encrypted_session_store.xml` (sesión/tokens cifrados), `rbs_biker_vault_local_store.xml` (bóveda/estado médico local cifrado según implementación).
- Reglas de backup/extracción actualizadas para excluir estos prefs y, de forma conservadora, dominios `database` y `file` en cloud-backup/device-transfer hasta completar inventario fino (puede afectar restauración de datos no sensibles; decisión de producto/UX pendiente).

## Validación pendiente (manual/QA)
- Confirmar solicitud en contexto, rationale y comportamiento ante denegación en cada pantalla.
- Probar degradación en Android 12+/13+/14 (Bluetooth, ubicación, notificaciones, cámara, micrófono).
- Inventariar bases de datos/archivos exactos y ajustar exclusiones de backup con granularidad si se requiere restauración selectiva.
