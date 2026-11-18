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

## B5-4 — CERRADO: firma de release con keystore de producción verificado (2026-11-16)

- **Cambio:** `signingConfigs.release` parametrizada con precedencia `(a) -Prbs.keystore.path/-Prbs.keystore.password/-Prbs.key.alias/-Prbs.key.password`, `(b) RBS_KEYSTORE_PATH/RBS_KEYSTORE_PASSWORD/RBS_KEY_ALIAS/RBS_KEY_PASSWORD`. `buildTypes.release` asigna `signingConfig` de release (sin fallback a debug). Si falta cualquiera de las 4 o el keystore no existe, el build de release falla con mensaje claro (configuración cuando se solicita tarea release + `verifyReleaseSigningConfigured`).
- **Cadena:** `bundle/assembleStandardRelease` → `verifyStandardReleaseSecurity` → `verifyReleaseSigningConfigured` + `verifyReleaseSecurityScript` (B1-4 regla 5 sigue `FAIL_PLACEHOLDER` con fallback `.invalid`; no relajada).
- **Estado histórico:** `ANDAMIAJE_LISTO` (superado el 2026-11-16; ver cierre más abajo).

- **Cierre B5-4 (2026-11-16, keystore de producción):** `:app:assembleStandardRelease` → **`BUILD SUCCESSFUL` in 29s**; gates encadenados: `verifyReleaseSecurityScript` PASS reglas 1-6 (`PASS_REAL`), `verifyReleaseSigningConfigured` PASS (4 variables provistas y keystore existente), `verifyStandardReleaseSecurity` PASS (`ALLOW_DEFAULT_TEST_RIDE_KEY=false`, `DEV_PANEL_ENABLED=false`). APK: `android/app/build/outputs/apk/standard/release/app-standard-release.apk` (71,506,241 bytes; 2026-11-16 20:56). `apksigner verify --print-certs` **exit 0** (reejecutado de forma independiente). Certificado: `CN=Ruta Biker Sync, OU=Release, O=Ruta Biker Sync, L=Caracas, ST=Distrito Capital, C=VE`. **SHA-256:** `8502ba90bc42e4c321f97f406ea495aaa6c3cc7f81c6b10bdf4585a78c33dd4c`. **SHA-1:** `1fe6998dab44c89c78ed0b58b2b051e4f4d71482`. Keystore de producción en `C:\rbs-secrets\rbs-release.jks` (**fuera del repo**); contraseñas y keystore **no** se commitean. CI end-to-end (`bundleStandardRelease` en pipeline con secretos) queda como tarea operativa; B1-4 permanece `LISTO_PARA_URL_REAL`.

- **Confirmación puntual previa (prueba controlada, keystore temporal fuera del repo, eliminado):** `assembleStandardRelease` con `-Prbs.keystore.*` y `-Prbs.prod.url=https://api.staging.example.test` (solo lógica) → `BUILD SUCCESSFUL`; APK verificado `android/app/build/outputs/apk/standard/release/app-standard-release.apk`; `apksigner verify --print-certs` **exit 0**, `CN=RBS Test` (no `CN=Android Debug`), SHA-256 `33c445bd9af2c9096e49e17841241eef271598b44d297d47d011e8ac97d3308b`. Gates encadenados: `verifyReleaseSigningConfigured` PASS, `verifyReleaseSecurityScript` PASS 1-6 (`PASS_REAL`), `verifyStandardReleaseSecurity` PASS. La observación previa de `CN=Android Debug` correspondía a un artefacto distinto, no a un bug de `signingConfigs.release`. **B5-4 permanece `ANDAMIAJE_LISTO` (no `[x]`)**: cierre real pendiente de keystore de producción + CI + verificación con certificado/huella reales. Sin secretos, keystore ni rutas personales commiteados.

## B3-3/B3-4/B3-5 — Inventario de almacenamiento y reglas de backup/extracción (cierre estático)

- **Inventario:** `docs/STORAGE_INVENTORY_T08.md` (Room/DB, SharedPreferences, files/cache, DataStore no identificado) con evidencia archivo:línea, sensibilidad, cobertura y acciones. Almacenes sensibles identificados: `ruta_biker_sync.db`, `rbs_encrypted_session_store`, `rbs_biker_vault_local_store`, `rbs_vault_keystore_prefs`, `rbs_sos_crypto_prefs`, `rbs_auth_prefs`, `rbs_fcm_prefs`; datos sensibles en cache (`exports/`, clips voz) documentados (dominio cache no cubierto por reglas XML estándar).
- **Reglas XML (quirúrgicas, coherentes):** `backup_rules.xml` y `data_extraction_rules.xml` (`cloud-backup`/`device-transfer`) excluyen las rutas sensibles anteriores; **sin exclusión de dominios completos**.
- **Validación automatizada:** `scripts/verify_backup_rules.ps1` (y `.sh`) — PASS en estado actual; FAIL simulado comprobado (exclusión requerida faltante y exclusión de dominio completo `path="."`).
- **Permisos (B3-3):** `docs/PERMISSIONS_USAGE_REPORT_T08.md` actualizado con evidencia uso/no uso, clasificación y recomendación por permiso (sin eliminaciones). `docs/PERMISSIONS_MATRIX_T08.md` actualizado con almacén asociado y cobertura backup.
- **Estado:** `CERRADO_ESTATICO_PENDIENTE_RUNTIME` (no `[x]`): inventario/reglas/validación estáticos completos; pendiente validación runtime de backup/restore y device-transfer en dispositivo, y decisión de producto sobre permisos candidatos a revisar/eliminar.

## 2026-11-17 — Cabina/Malla: arranque de rodada (TECNO) y Monitor de Malla conectado a estado real (diagnóstico y corrección)

### Diagnóstico (aceptado)
- **Bug 1 (arranque en TECNO / UI sin radio):** en `MainAppScaffold.kt` el botón "Iniciar Rodada (Cabina Activa)" navegaba a la cabina (`onStartLiveMapPreview`) pero solo despachaba `RideService.dispatchStartRide` si `forceSimulated` era `true` o `RideService.isMeshViable(context)` era `true`. Si la malla no era viable en ese chequeo, la UI entraba en cabina sin iniciar sesión de rodada/radio ni mostrar causa clara.
- **Bug 2 (monitor desconectado):** en `LiveMapScreen.kt` el overlay del monitor invocaba `MeshConvoyStatusScreen` con valores por defecto (peers vacíos), sin leer `RideService.getActiveRelayEngine()?.peers` ni `RideService.sharedState`, por lo que no reflejaba el estado real.

### Correcciones aplicadas
- **Bug 1:** `startRideServiceNow` ahora captura la `RideServiceEligibility` devuelta por `dispatchStartRide` y, cuando se solicitó inicio no simulado pero no es posible promover a foreground (`!eligibility.shouldPromoteToForeground`), establece un mensaje claro en UI indicando que la malla BLE no es viable (permisos/Bluetooth) y que la rodada se inició en modo simulado dentro de la app sin notificación persistente en primer plano. El botón "Iniciar Rodada (Cabina Activa)" despacha siempre el inicio (`startRideServiceNow(useSimulated = forceSimulated)`), evitando entrar a cabina sin intento de arranque.
  - **Criterio "foreground + notificación persistente" en TECNO:** depende de que el dispositivo cumpla viabilidad real de malla (`computeEligibility`/permisos y condiciones de BLE). Si en el TECNO faltan permisos o Bluetooth/condiciones BLE, el código ahora lo informa explícitamente y arranca sesión simulada en app; no se fuerza foreground sin tipo/permiso (diseño existente de `RideService`). La causa exacta en dispositivo debe confirmarse con logcat y estado de permisos/Bluetooth al pulsar iniciar (pendiente de evidencia de ejecución en TECNO).
- **Bug 2:** `MeshConvoyStatusScreen` acepta `isLpnToggleEnabled`/`lpnToggleDisabledReason` y muestra campos RF/identidad no expuestos por el stack como **"No disponible"** (RSSI/PLR/nombre/rol) sin inventar valores. Se añadió mapeo puro `mapPeerTrackToMeshPeerLiveState`/`mapPeerTracksToMeshPeerLiveStates` desde `MeshRelayEngine.PeerTrack` usando solo campos disponibles: `shortId`, `lastHops`, `lastBatteryPercent`, `lastSeenEpochMs`, `expectedBeats`/`receivedBeats` (PLR derivado de expected/received cuando aplica). `LiveMapScreen` pasa `peers` reales desde `getActiveRelayEngine()?.peers`, `localBatteryPercent` real y el switch de modo de radio queda **deshabilitado** con "No implementado aún" (sin efecto real conectado en stack). Estado vacío real: "Sin compañeros en rango" cuando no hay peers; tarjetas con datos reales/mapeados cuando existen.

### Verificación (evidencia disponible en repo/local)
- **Prueba unitaria (PASS):** `MeshConvoyStatusScreenTest` (`:app:testStandardDebugUnitTest --tests "com.rutabikersync.app.feature.mesh.MeshConvoyStatusScreenTest"`) — incluye `testMapeoPeersVacios` y `testMapeoPeerSimuladoConDatosRealesDisponibles` (mapeo con `PeerTrack` simulado y verificación de campos disponibles / "No disponible" para no expuestos). Ejecución local: `BUILD SUCCESSFUL`.
- **Logcat/notificación en TECNO:** no disponible en este entorno de cambios; pendiente de ejecución del usuario en dispositivo (sin falso verde). Si persiste ausencia de foreground en TECNO, documentar causa concreta observada (permisos denegados, Bluetooth apagado, adapter no habilitado, etc.) sin cambios de backend.

### Verificación de campo (2026-11-18, TECNO KI5k + Redmi Note 13 Pro 5G)

**Bug 1 — arranque con foreground (TECNO KI5k, Android 12/HiOS):**
- Tras pulsar "Iniciar Rodada (Cabina Activa)", `RideService` quedó en foreground (`isForeground=true`, `foregroundId=3201`, `fgsType=0x18`), notificación persistente "Rodada activa · 0 riders conectados", y `HeartbeatTx` incrementando (190+ paquetes).
- Build de prueba re-firmado con la clave de producción (`C:\rbs-secrets\rbs-release.jks`, SHA-256 `8502ba90bc42e4c321f97f406ea495aaa6c3cc7f81c6b10bdf4585a78c33dd4c`) para instalar sobre la app existente sin perder sesión.

**Bug 2 — monitor (TECNO, estado vacío real):**
- Monitor mostró `Pilotos Enlazados 0`, `PLR Promedio No disponible`, "Sin compañeros en rango", LPN `No implementado aún`. Coherente con la ausencia de peers reales.

**Enlace dispositivo-dispositivo:**
- Redmi (nodo 2) recibió heartbeats de TECNO (nodo 1) con modo EXTENDED_CODED_PHY: `valid=14`, `peer=1 hops=1 battery=87`, entrega ~70%.
- TECNO (nodo 1) en el mismo modo recibió 0 paquetes (valid=0 en 90 s con filtro diagnóstico amplio); la pila Bluetooth de TECNO reportó 0 resultados para la app en `dumpsys bluetooth_manager` (GATT Scanner Map, "Filter 0 results"). Se clasifica como limitación de pila/PHY del dispositivo, no de lógica de negocio.
- Con modo `LEGACY_1M` en ambos dispositivos, el enlace fue bidireccional y completo: TECNO vio nodo 2 (`valid=21, delivery=95.45%`) y Redmi vio nodo 1 (`valid=17, delivery=77.27%`).
- Consecuencia operativa: la selección automática `EXTENDED_CODED_PHY` deja el mesh unidireccional en TECNO KI5k. Requiere decisión de producto (fallback a `LEGACY_1M` o negociación de modo), fuera del alcance de Bug 1/2 y sin workaround en backend.
- PTT (PTT/voz) no se probó en este ciclo; no se marca B2-4 runtime como cerrado por esa evidencia.

**Limpieza:** arnés temporal `RideStartHarnessTest.kt` y APK de test eliminados; no se commitearon.

### Actualización 2026-11-18 (manufacturer data 0x5242)
- Cambio en BleAdvTransport.kt: se reemplazó ddServiceData(MESH_SERVICE_UUID, payload) por ddManufacturerData(0x5242, payload) (company ID "RB" = Ruta Biker). Esto evita el problema de compresión/truncamiento del UUID de 128 bits en el límite de 31 bytes de BLE Legacy. Manufacturer data usa solo 2 bytes de company ID, dejando 27 bytes para el payload de 20 bytes.
- Scan callback actualizado para usar getManufacturerSpecificData(0x5242) en ambos paths (onScanResult y onBatchScanResults).
- Build firmada con clave de producción e instalada en TECNO (098813734B105395) con éxito.
- Xiaomi (b2c398bb) bloqueado por MIUI (INSTALL_FAILED_USER_RESTRICTED); requiere habilitar "Instalar vía USB" en Ajustes → Opciones de desarrollador.
- Commit: 17cfee (fix(ble): usar company ID 0x5242 para manufacturer data).

### Verificación final de campo (2026-11-18, capturas en TECNO KI5k + Redmi Note 13 Pro 5G)
**Enlace bidireccional REAL verificado en UI (evidencia reportada por el usuario en 4 capturas):**
- Build con ManufacturerData(0x5242) + localShortId derivado de Build.MODEL:ANDROID_ID instalada en ambos dispositivos.
- Ambos equipos se detectan mutuamente en el mapa en tiempo real (Los Teques) y en el Monitor de Malla BLE:
  - Dispositivo A ve al compañero R#12982 (Convoy: 1 riders), 1 piloto enlazado, En línea, 1 salto, batería 87%, 44/84 paquetes (PLR inicial 47.6% por arranque más tardío del par).
  - Dispositivo B ve al compañero R#12243, 🟢 MALLA ACTIVA, 1 piloto enlazado, En línea, 1 salto, batería 87%, 95/99 paquetes (PLR 4.0%, entrega RF real ~96%).
- IDs derivados son distintos entre dispositivos (sin choque de identidad); se descartó el localShortId=1 fijo que causaba el descarte de ecos.
- Nota: los valores de RSSI en la tarjeta siguen mostrando "No disponible" (campo ssiDbm=null en el mapeo); no se inventaron dBm. PTT/voz NO probado en este ciclo; B2-4 runtime permanece con ese alcance explícito.
- Commit funcional: c624b1c (fix(mesh): derivar localShortId de ANDROID_ID para evitar colisiones entre dispositivos).

### Mejora RSSI real en tarjeta (2026-11-18, commit 7501ff5)
- MeshRelayEngine.PeerTrack incorpora lastRssiDbm: Int?, alimentado desde IncomingPacket.rssi (valor real del stack BLE) en onRawPacketReceived → updatePeerTrackLocked, con retención del último valor si un paquete posterior no trae RSSI.
- mapPeerTrackToMeshPeerLiveState propaga 	rack.lastRssiDbm a MeshPeerLiveState.rssiDbm; la tarjeta del monitor muestra ahora los dBm reales y las barras de señal calculadas por RssiSignalLevel.fromDbm (datos reales, sin inventar).
- Prueba unitaria nueva 	estMapeoPropagaRssiRealDelStack (PASS): track con lastRssiDbm=-45 → peer.rssiDbm=-45, nivel EXCELLENT, 4 barras. Suite MeshConvoyStatusScreenTest completa en verde.
- Build firmada e instalada en TECNO (098813734B105395) y Redmi (b2c398bb) con Success; force-stop aplicado en ambos.

### Fix UI Chat TÃ¡ctico â€” contraste y layout (2026-11-18, commit 70da790)
- Problema detectado en campo: el preset "â›½ Gasolinera a 2 km" aparecÃ­a ilegible sobre el acento naranja; el chip Broadcast seleccionado usaba blanco sobre `HeritageCopper` (#C86D44) con ratio real 3.67:1 (por debajo de WCAG AA 4.5:1).
- SoluciÃ³n: nueva paleta centralizada `TacticalChatPalette.kt`:
  - Burbuja saliente Broadcast: fondo naranja oscuro `HeritageCopperDark` (#8B3A1A) + texto blanco â†’ 7.10:1 (AA).
  - Burbuja saliente Unicast: fondo verde oscuro `HeritageGreenLight` (#064E3B) + blanco â†’ 8.93:1 (AA), distinto tambiÃ©n en el fondo, no solo en el badge.
  - Burbuja entrante: `DarkCardElevated` + blanco â†’ 14.00:1 (AA).
  - Cabecera dorada 4.77:1, metadatos 5.20â€“6.55:1, badge 10.15:1.
  - Chip Broadcast seleccionado: `HeritageCopperDark` + `TextWhite` â†’ 7.10:1 (antes 3.67:1).
- Layout: padding del mensaje 14.dp horizontal / 10.dp vertical (antes 10.dp uniforme) y ancho mÃ¡ximo `fillMaxWidth(0.8f)`; los 6 presets siguen renderizÃ¡ndose como botones y ahora sus mensajes usan la paleta AA.
- Tests: `TacticalChatContrastTest` nuevo (5 tests: WCAG AA de cuerpo/cabecera/meta/badge, fondos Broadcast distinto de Unicast, 6 presets AA, sanity 21:1) mas `TacticalChatScreenTest` existente â†’ `:app:testStandardDebugUnitTest` BUILD SUCCESSFUL.
- Sin cambios en envÃ­o/recepciÃ³n, crypto ni backend. Build firmada e instalada en TECNO y Redmi con Success; force-stop aplicado.
- Pendiente de verificaciÃ³n visual en campo: captura del chat legible con mensaje enviado/recibido (no disponible en este entorno; no se marca como verificado hasta evidencia real).


### Captura de evidencia del chat con contraste corregido (2026-11-18)
- Captura tomada en TECNO KI5k (098813734B105395) con la build firmada del fix: `docs/evidence/chat_tactico_contraste_2026-11-18.png`.
- Navegacion real: rodada activa -> boton "Senal de ruta" -> dialogo "Senales Rapidas de Convoy" -> "CHAT TACTICO OFF-GRID".
- Se envio el preset "â›½ Gasolinera a 2 km". El dump de UI (`uiautomator dump`) confirma la burbuja saliente con cabecera "TÃš", badge "BROADCAST", texto del mensaje, hora 13:41 y estado "Enviado" â€” mismo caso que antes era ilegible.
- Evidencia objetiva: ratios WCAG AA verificados por `TacticalChatContrastTest` (cuerpo 7.10:1 sobre broadcast, 8.93:1 sobre unicast, 14.00:1 entrante). La revision visual final de la captura queda al usuario; no se marca como "verificado visualmente" sin inspeccion humana.
- `main...origin/main [ahead 2]`: commits `70da790` (fix) y `6674020` (docs) pendientes de push.

### Chat Táctico bidireccional sobre el BLE Mesh activo de RideService (2026-11-18)
**Commits funcionales**: `834be73` (conectar envío/recepción al `MeshRelayEngine` activo), `ddcd1d4` (etiqueta Unicast acortada), `44167d3` (límite real de manufacturer data 24 B), `ef48fa0` (esperar ráfaga y repetir fragmentos).

**Diagnóstico (causa raíz, sin false green)**:
1. El envío desde la UI no llegaba al engine activo: `dispatchSendTextMessage` no usaba `getActiveRelayEngine()`.
2. El engine no tenía cableado `onTextMessageReceived` hacia `TacticalChatStore`, así que los mensajes descifrados nunca aparecían en la bandeja.
3. `TextMessagePacket` mide 42–161 B y no cabe en `ManufacturerData` legacy. El límite real es **24 B** (31 B PDU − 3 B flags − 1 B length − 1 B AD type − 2 B company ID `0x5242`), no 29 B; con 29 B se producía `ADVERTISE_FAILED_DATA_TOO_LARGE`.
4. No se modificó crypto (`TextMessageCrypto`) ni flooding (`MeshRelayEngine`); la fragmentación vive en la capa de transporte (`BleFragmentationCodec.kt`, magic `A5 5A`, cabecera 8 B con sesión/índice/total/CRC16, limpieza de sesiones viejas y pass-through transparente de paquetes de 20 B).
5. En campo, los fragmentos se solapaban: `sendFrame` inicia la ráfaga y retorna enseguida, por lo que con `FRAGMENT_GAP_MS=120` los 4 fragmentos se emitían en ~400 ms y el scanner solo capturaba 2 de 4. Corrección en `ef48fa0`: esperar `BURST_MS + GAP_MS` entre fragmentos y repetir cada mensaje 2 rondas.

**Verificación de campo con build final (TECNO KI5k `098813734B105395` + Redmi Note 13 Pro 5G `b2c398bb`)**:
- `RideService` en primer plano en ambos: `isForeground=true`, `foregroundId=3201`, `types=0x00000018`, notificación persistente y `RideStarted ... transport=BleAdvTransport(LEGACY_1M)`; mismo build/mode en ambos extremos.
- **Redmi → TECNO**: `FragmentTx session=1 bytes=48 fragments=3 maxBytes=24` (pid `17993`); la UI de TECNO muestra `VIAJEOK` recibido de `Piloto #12243` con badge `BROADCAST`.
- **TECNO → Redmi**: dos intentos (`RUTAOK`, `SI`) transmitieron (`FragmentTx session=1 bytes=47` y `session=2 bytes=43`) pero no se entregaron por pérdida RF de fragmentos en ese instante — **no se marcó como éxito**. El reintento `PRUEBADIR` (`FragmentTx session=3 bytes=50 fragments=4`) sí entregó: `RX_RAW len=24 hdr=A5,5A idx=0/4,1/4,2/4`, `RX_FULL len=50`, `TEXT_RX_ENTER size=50 target=65535 local=12243` y `TEXT_RX_DELIVER text=PRUEBADIR target=65535 cb=true`; la UI de Redmi muestra `PRUEBADIR` de `Piloto #12982`.
- Los logs `RX_RAW` / `RX_FULL` / `TEXT_RX_*` / `FRAG_TX` fueron diagnósticos temporales y **ya están retirados**; `FragmentTx`, `RideStarted` y `HeartbeatTx` son logs permanentes de la build final. La última instalación final (sin diagnósticos) fue verificada por UI en ambas direcciones.
- Evidencia visual: `docs/evidence/chat_bidir_tecno_2026-11-18.png` y `docs/evidence/chat_bidir_redmi_2026-11-18.png`.
- Tests dirigidos en verde: `BleFragmentationCodecTest`, `TacticalChatMeshIntegrationTest`, `TacticalChatScreenTest`, `TacticalChatContrastTest` → `BUILD SUCCESSFUL`. La suite completa arrastra 2 fallos preexistentes no relacionados (`LiveMapViewModelTest` y `RouteEnginePhase4Test`), documentados sin ocultarlos.
- `BleMeshService` **no** fue iniciado en ningún momento: todo el tráfico pasó por el `MeshRelayEngine` de `RideService`. PTT/voz sigue sin prueba de audio en este ciclo.

### Prueba de PTT/voz en hardware real (2026-11-18) — RESULTADO NEGATIVO, sin marcar como verificado
**Dispositivos**: TECNO KI5k `098813734B105395` y Redmi Note 13 Pro 5G `b2c398bb`, build final `d7c50db` instalada en ambos. `RideService isForeground=true` en los dos; `BleMeshService` NO activo; `RECORD_AUDIO granted=true` y volumen `STREAM_MUSIC` sin silenciar (TECNO 12/15, Redmi 127/150).

**Prueba realizada**: rodada iniciada en ambos, pulsado "Hablar (PTT)" en TECNO («PRUEBA DE VOZ TECNO», 8 s) y en Redmi («PRUEBA DE VOZ REDMI», 7 s), con logcat limitado a `PttAudioEngine|VoicePacket|OpusVoiceRecorder|VoiceClipPlayerQueue|JitterBuffer|AudioRecord|AudioTrack|AudioFocusController|BleMeshService|PLC|FATAL`.

**Resultado**: **el audio NO cruza en ninguna dirección**. La UI del emisor muestra «TRANSMITIENDO PTT AL CONVOY (… Opus)» y aparecen logs del sistema `AudioRecord` (pid `965`, uid `10379`) y `c2.android.opus.encoder`, pero **no aparece ni un solo log de `PttAudioEngine`, `VoicePacket`, `VoiceClipMeshRelay`, `VoiceClipPlayerQueue`, `JitterBuffer` ni `AudioFocusController` en ninguno de los dos equipos**; el receptor nunca muestra «Recibiendo voz». No se puede confirmar calidad auditiva porque no hay recepción.

**Causa exacta (auditoría estática + logcat)**: hay tres rutas PTT desconectadas.
1. `PttAudioEngine` (AudioRecord → Opus → ChaCha20 → `VoicePacket`) solo se instancia en `BleMeshService.kt:412-421` y envía por `transmitBleFrame()` de ese servicio, que no se inicia durante la rodada (`RideService.kt:1120-1124`). `MeshRelayEngine` no tiene API de voz (`emitVoicePacket`/`onVoiceReceived` no existen).
2. El botón visible usa `OpusVoiceRecorder` + `VoiceClipMeshRelay` + `VoiceClipPlayerQueue` (`LiveMapScreen.kt:159-171, 1287-1333`). `processIncomingChunk` y `_outgoingPackets` no tienen colector en `src/main`: la grabación es local, no sale por radio. No usa `VoicePacket` ni `MeshRelayEngine`. `PttButton.kt` con `onStartTalking/onStopTalking` no tiene ningún call site.
3. La ruta de tecla física (`VOLUME_DOWN` / `rbs://ptt`) envía `ACTION_PTT_START` a `BleMeshService` sin `ACTION_START` previo, por lo que `pttAudioEngine` queda en `null` (`BleMeshService.kt:148-149, 257-269`). En la ejecución 15:07:05–15:07:15 provocó además `FATAL EXCEPTION: main — android.app.ForegroundServiceDidNotStartInTimeException` (`Context.startForegroundService() did not then call Service.startForeground()`), con crash confirmado por `am_crash` y `TABE.CrashHandler`. En un reintento (15:08:02) no se reprodujo el crash, pero tampoco hubo audio ni logs de `PttAudioEngine`.
4. `VoicePacket` mide 80–100 B y `BleMeshService.transmitBleFrame` **no** aplica el `BleFragmentationCodec` de 24 B usado por la malla activa; es un obstáculo adicional para migrar la ruta, no la causa de este fallo (el fallo es que la ruta nunca se activa).

**Evidencia**: `docs/evidence/ptt_diagnostico_logcat_2026-11-18.txt`, `docs/evidence/ptt_tecno_tx_sin_rx_2026-11-18.png` (indicador local de transmisión) y `docs/evidence/ptt_redmi_sin_recepcion_2026-11-18.png` (sin indicador de recepción). Dumps `uiautomator` con «Grabando voz PTT» / «TRANSMITIENDO PTT AL CONVOY» en el emisor y ausencia de «Recibiendo voz» en el receptor.

**No se marcan casillas de PTT como verificado.** No se modificó codec, criptografía ni flooding.Para corregirlo haría falta cablear `PttAudioEngine` al `getActiveRelayEngine()` de `RideService` (añadiendo `emitVoicePacket`/`onVoiceReceived` al engine) o arrancar `BleMeshService` con `ACTION_START` y `startForeground()` correcto, y sustituir `voiceMeshRelay.broadcastLocalClip` por el envío fragmentado por `BleAdvTransport`; pendiente de confirmación antes de tocar audio/crypto.

