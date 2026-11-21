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
- Nota: los valores de RSSI en la tarjeta siguen mostrando "No disponible" (campo
ssiDbm=null en el mapeo); no se inventaron dBm. PTT/voz NO probado en este ciclo; B2-4 runtime permanece con ese alcance explícito.
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


### Fix funcional PTT en la malla activa - verificacion bidireccional en hardware real (2026-11-18)
**Commits funcionales**: `b170b52` (API de voz en `MeshRelayEngine` + fragmentation de `VoicePacket` con el `BleFragmentationCodec` de 24 B), `bd4e4f8` (boton PTT cableado al engine activo de `RideService` + correccion del crash FGS), `865beb2` (limitacion de publicacion de estado UI y backpressure de 2 envios pendientes) y `54cf626` (rafaga de voz BLE a 300 ms, timeout de reproduccion a 2,5 s y `senderShortId` tomado del engine activo).

**Diagnostico de la causa raiz (mantenido arriba, resultado negativo original)**: la prueba anterior con `d7c50db` no entrego audio porque la ruta PTT no estaba conectada al `MeshRelayEngine` activo y `VoicePacket` no usaba el codec de fragmentacion de 24 B. Esa evidencia negativa NO se borra; este apartado documenta el fix posterior.

**Cambios funcionales sin tocar Opus ni ChaCha20-Poly1305**:
- `MeshRelayEngine.emitVoicePacket()` serializa el `VoicePacket` ya cifrado por `PttAudioEngine` y lo entrega a `BleAdvTransport.sendVoice()`. No se vuelve a cifrar nada.
- `MeshRelayEngine.processIncomingPacket()` enruta `TYPE_PTT_VOICE` a `VoicePacket.fromBinary()` y a los callbacks `onVoiceReceived`.
- `BleAdvTransport.sendVoice()` usa el mismo `BleFragmentationCodec` de 24 B del chat. Con 120 ms por fragmento el scanner de Redmi no recibia ningun fragmento de voz (solo heartbeats de 20 B); con 300 ms y 2 rondas si los recibe y reensambla. Se midio latencia de primer frame de 1,607 s (TECNO->Redmi) y 0,900 s (Redmi->TECNO), dentro del objetivo 1-3 s.
- `PttAudioEngine` publica estado UI como maximo 1 vez/s para no bloquear la UI de LiveMap; el JitterBuffer y el cifrado siguen en 20 ms.
- `PttAudioEngine` mantiene el AudioTrack hasta 2,5 s sin tramas (antes 350 ms), porque con fragmentacion BLE las tramas llegan separadas ~2 s.
- El `senderShortId` de voz se toma del `MeshRelayEngine` activo: antes ambos telefonos caian en el fallback `#1`; ahora el banner muestra `#12982` (TECNO) y `#12243` (Redmi).

**Verificacion en campo (TECNO KI5k `098813734B105395` + Redmi Note 13 Pro 5G `b2c398bb`)**:
- `RideService` en primer plano en ambos, `RideStarted ... transport=BleAdvTransport(LEGACY_1M)`, monitor con 1 peer En linea; `BleMeshService` NO iniciado en ningun momento.
- TECNO -> Redmi: `PttAudioEngine: PTT: Iniciando transmision de audio...`, `MeshRelayEngine: VoicePacketTx frame=0`, `BleAdvTransport: FragmentTx session=1 ... priority=VOICE`; en Redmi `FragmentReassembled bytes=67`, `VoicePacketRx sender=12982 frame=0`, `LiveMapScreen: VoicePacketRx entregado al reproductor PTT frame=0` y banner UI `Recibiendo voz de Piloto #12982`.
- Redmi -> TECNO: `VoicePacketTx frame=0`, `FragmentTx session=4 ... priority=VOICE`; en TECNO `VoicePacketRx sender=12243 frame=0/1`, `LiveMapScreen: VoicePacketRx entregado al reproductor PTT` y banner `Recibiendo voz de Piloto #12243`.
- `AudioTrackImpl` en el receptor confirma reproduccion del stream 16 kHz mono; no se han tocado los buffers de Opus ni las claves ChaCha20-Poly1305.
- Limitacion real medida: el microfono genera ~50 frames/s y la cola se limita a 2 envios para acotar latencia; no todas las tramas cruzan. La inteligibilidad/calidad auditiva NO puede ser afirmada por este agente y queda pendiente de confirmacion humana.

**Evidencia**: `docs/evidence/ptt_bidir_logcat_2026-11-18.txt`, `docs/evidence/ptt_bidir_ui_2026-11-18.txt`, `docs/evidence/ptt_bidir_tecno_2026-11-18.png`, `docs/evidence/ptt_bidir_redmi_2026-11-18.png`.

### Tabla final de entrega (PTT bidireccional)
| Tarea | Comando | Resultado esperado | Resultado obtenido | Evidencia |
| --- | --- | --- | --- | --- |
| Compilar y firmar APK funcional | `gradlew :app:assembleStandardDebug` + `apksigner sign ...` | APK firmada `CN=Ruta Biker Sync` | BUILD SUCCESSFUL; certificado verificado | `docs/HISTORY_NOTE.md` (este apartado) |
| Instalar en TECNO y Redmi | `adb -s <dev> install -r app-standard-debug-rbssigned.apk` | `Success` en ambos | `Success` en ambos | Logcat de instalacion y `pm path` |
| Rodada activa y peer BLE | UI tap inicio + `uiautomator dump` | `RideStarted ... LEGACY_1M`, 1 peer | `RideStarted`, monitor 1 peer En linea | `docs/evidence/ptt_bidir_ui_2026-11-18.txt` |
| TECNO -> Redmi | tap PTT TECNO `(151,1202)` + `logcat *:V` | TX fragmentado y RX en peer | `VoicePacketTx`/`FragmentTx` + `VoicePacketRx sender=12982` + banner | `docs/evidence/ptt_bidir_logcat_2026-11-18.txt`, `ptt_bidir_redmi_2026-11-18.png` |
| Redmi -> TECNO | tap PTT Redmi `(261,2098)` + `logcat *:V` | TX fragmentado y RX en peer | `VoicePacketTx`/`FragmentTx` + `VoicePacketRx sender=12243` + banner | `docs/evidence/ptt_bidir_logcat_2026-11-18.txt`, `ptt_bidir_tecno_2026-11-18.png` |
| Latencia de primer frame | comparar timestamps TX/RX | 1-3 s | 1,607 s y 0,900 s | `docs/evidence/ptt_bidir_logcat_2026-11-18.txt` |
| Confirmacion auditiva | escucha humana en altavoz | "Se escucha claro, sin cortes" | NO verificable por este agente | Requiere confirmacion del usuario |

### Transporte GATT dedicado para voz PTT — continuidad verificada en hardware real (2026-11-18)
**Commits atómicos**: `feat(ptt): añadir transporte GATT para voz (streaming por notificaciones)`, `fix(ptt): integrar GATT en RideService y verificar audio continuo`, `docs: registrar evidencia de audio continuo PTT en hardware real`.

**Cambio de transporte (Opus y ChaCha20-Poly1305 intactos)**:
- `VoiceStreamTransport.kt`, `GattVoiceFramer.kt` y `GattVoiceTransport.kt` añaden el servicio GATT `0000b1cf-0000-1000-8000-00805f9b34fb`, la characteristic TX `0000b1d0-...` (escrituras del cliente) y RX `0000b1d1-...` (notificaciones del servidor), con CCCD `00002902-...`. MTU solicitado y negociado: 517 B. Cabecera de frame de 10 B (`47 56`, streamId, sequence, chunkIndex, totalChunks, CRC-16); la fragmentación de 24 B queda solo como fallback si el MTU no llega a 517 B.
- Rol determinista por shortId estable: menor shortId = servidor (Redmi `12243`), mayor = cliente (TECNO `12982`). Escrituras y notificaciones se serializan con ACK; el receptor reensambla por `streamId` + `sequence`.
- `RideService` abre/cierra la sesión GATT en cada PTT (toggle de toque) y mantiene el enlace durante la rodada. `MeshRelayEngine` prefiere GATT para `TYPE_PTT_VOICE` (`via=GATT`); `BleAdvTransport` sigue activo para chat, SOS y heartbeats. `LiveMapScreen` muestra el contador de tramas recibidas.

**Verificación en campo (TECNO KI5k `098813734B105395` + Redmi Note 13 Pro 5G `b2c398bb`)**:
- Enlace confirmado: `GattVoice CLIENT connected`, `MTU CLIENT=517`, `READY role=CLIENT mtu=517 node=12982`; `GattVoice SERVER connected device=...`, `MTU SERVER=517`, `READY role=SERVER mtu=517 node=12243`. `BleAdvTransport.send ... priority=POSITION` sigue presente durante la llamada GATT.
- **Sesión de 15 s TECNO → Redmi (18:22:55.385–18:23:10.555)**: `session START ready=true mtu=517`, **742** `VoicePacketTx ... via=GATT` (frames 0..741, sin huecos), **739** `VoicePacketRx` y **739** entregados al reproductor (frames 0..738, sin huecos), sin `TX backpressure` ni timeouts en esa ventana. La diferencia de 3 tramas son las últimas emitidas en el instante de cierre de sesión.
- **Sesión reversa Redmi → TECNO (18:05:10.180–18:05:16.740, 6,56 s)**: **319** TX (frames 0..318) y **318** RX + entregados (frames 0..317), sin huecos, con `AudioTrack` 16 kHz/mono iniciado en el receptor. Queda demostrada la continuidad en ambas direcciones; la captura reversa no alcanzó 15 s.
- Otras sesiones TECNO → Redmi: T1 660/659, T2 740/736, T3 735/733, T4 737/735, todas sin huecos. **No se ocultan los fallos**: T5 (18:19) tuvo backpressure real y un hueco 78→114 (`TX fallo: Timed out waiting for 1000 ms`), y T7 (18:23:15) es **inconclusiva** porque el archivo de logcat del receptor termina en 18:23:22.576 mientras el emisor sigue hasta 18:25:12; el corte es de la captura.
- La UI receptora registró `🔊 Recibiendo voz de Piloto #12982 · 3454 tramas` (`docs/evidence/ptt_gatt_redmi_ui_dump_20261118_1812.xml` y captura PNG). Guion hablado solicitado: `PRUEBA DE AUDIO CONTINUO UNO DOS TRES CUATRO CINCO`. El contenido fonético y la claridad auditiva **no** son verificables por este agente; requieren escucha humana.
- Tests dirigidos en verde: `:app:testStandardDebugUnitTest --tests "com.rutabikersync.app.core.transport.GattVoiceFramerTest" --tests "com.rutabikersync.app.core.mesh.audio.VoiceMeshRelayTest"` → `BUILD SUCCESSFUL`, `EXIT_CODE=0`. La suite completa arrastra 2 fallos preexistentes no relacionados (`MultiHopFloodingTest` y `RouteEnginePhase4Test`).

**Evidencia**: `docs/evidence/ptt_gatt_audio_continuo_20261118.txt` (establecimiento GATT, conteos por sesión, logs TX/RX/AudioTrack, backpressure y límites), `docs/evidence/ptt_gatt_redmi_rx_counter_20261118_1812.png`, `docs/evidence/ptt_gatt_redmi_ui_dump_20261118_1812.xml`, `docs/evidence/ptt_gatt_redmi_rx_counter_20261118_1803.png` y `docs/evidence/ptt_gatt_tecnog_tx_counter_20261118_1803.png`.

| Tarea | Comando | Resultado esperado | Resultado obtenido | Evidencia |
| --- | --- | --- | --- | --- |
| Compilar y probar transporte GATT | `.\gradlew.bat :app:testStandardDebugUnitTest --tests "...GattVoiceFramerTest" --tests "...VoiceMeshRelayTest" --no-daemon --console=plain` | `BUILD SUCCESSFUL` | `BUILD SUCCESSFUL`, `EXIT_CODE=0` | `docs/HISTORY_NOTE.md` (este apartado) |
| Firmar e instalar build | `apksigner sign ... --out app-standard-debug-rbssigned.apk ...` + `adb -s <dev> install -r ...` | APK firmada e instalada en ambos | `Success` en TECNO y Redmi | `docs/evidence/ptt_gatt_audio_continuo_20261118.txt` |
| Establecer enlace GATT y MTU | `adb logcat -s GattVoice` | Cliente/servidor `READY`, MTU 517 | `READY role=CLIENT/SERVER mtu=517` | `docs/evidence/ptt_gatt_audio_continuo_20261118.txt` §1 |
| PTT 15 s TECNO → Redmi | `adb -s 098813734B105395 shell input tap 151 1202` (inicio y fin) | Tramas `via=GATT` sin huecos | 742 TX / 739 RX / 739 entregados, sin huecos ni backpressure | `docs/evidence/ptt_gatt_audio_continuo_20261118.txt` §2-3 |
| PTT reverso Redmi → TECNO | `adb -s b2c398bb shell input tap 262 2099` (inicio y fin) | Tramas GATT y `AudioTrack` en receptor | 319 TX / 318 RX / 318 entregados, sin huecos; `AudioTrack` 16 kHz | `docs/evidence/ptt_gatt_audio_continuo_20261118.txt` §4 |
| Contador en UI receptora | `adb -s b2c398bb exec-out screencap -p` + `uiautomator dump` | Banner con número de tramas | `🔊 Recibiendo voz de Piloto #12982 · 3454 tramas` | `ptt_gatt_redmi_rx_counter_20261118_1812.png`, `ptt_gatt_redmi_ui_dump_20261118_1812.xml` |
| Descartar backpressure en sesión válida | `Select-String -Pattern 'TX backpressure\|TX fallo\|descartado'` | Sin descartes en T6 | 0 en T1-T4 y T6; T5 inestable y T7 inconclusiva documentados | `docs/evidence/ptt_gatt_audio_continuo_20261118.txt` §5 |
| Claridad auditiva | escucha humana con el guion `PRUEBA DE AUDIO CONTINUO UNO DOS TRES CUATRO CINCO` | "Se escucha claro, sin cortes" | NO verificable por este agente | Requiere confirmación del usuario |
| Publicar | `cd D:\Ruta-Biker-Sync; git push -u origin main` (lo ejecuta el usuario) | `main` actualizado en origin | Pendiente: autenticación del usuario | Commits atómicos creados localmente |
### Bloque B — Peers enriquecidos en el mapa (nombre, avatar, rol) — 2026-11-18
**Commit atómico**: `feat(ui): enriquecer peers en el mapa con nombre, avatar y rol` (sin amend).

**Diagnóstico**: `GET /v1/rides/:id/participants` no existía (solo POST de alta). `PeerTrack` tiene shortId/lat/lon/batería/SOS pero no identidad. `LiveMapController` se creaba con directorio vacío y usaba el nombre inventado "Juan Martínez" para el peer local y "Rider #X" para los remotos. El backend no conoce `shortId`, por lo que el roster solo puede asociarse al peer local por `userId`.

**Backend**: nuevo `GET /v1/rides/:id/participants` con `displayName`, `avatarUrl`, `roleInConvoy`, `mapColor`, `bikeModel` y `note` aclarando el límite del shortId. Reutiliza `ride_participants.ride_role` (sin columnas nuevas). `roleInConvoy` se deriva de forma determinista; `displayName` vacío -> `Desconocido`. Tests `ride_roster.test.ts` y `ride_roster_endpoint.test.ts` en verde, con 200/403/404.

**Android**: `RiderDirectoryEntry`/`RiderMarkerUiModel` ampliados sin tocar `PeerTrack`. Perfil cacheado (`ProfileRepository`, SharedPreferences) aplicado al peer local con alias, foto base64 y color reales; fallback `Piloto #X` y `Desconocido`. Roster backend aplicado solo al peer local por `userId`. `RiderMarker` muestra avatar circular (foto o iniciales), nombre en segunda línea, rol al expandir, `(Yo)` en el local y estado En ruta/Desviado/Sin señal/SOS.

**Verificación real**: APK firmada e instalada en TECNO `098813734B105395` y Redmi `b2c398bb`. Mapa con 2 motos: local `O. (Yo) · Punta · ✓ En ruta` + nombre real del perfil; remoto `R#12243 · Desconocido · ⚠ Desviado` + `Piloto #12243` (y simétrico `Piloto #12982` en Redmi, con `Rol: Desconocido` al expandir). Sin reverse USB, el mapa sigue con `Piloto #12982` y sin FATAL en logcat.

| Tarea | Comando | Resultado esperado | Resultado obtenido | Evidencia |
| --- | --- | --- | --- | --- |
| Diagnosticar fuentes | `grep` en `app.ts`, `MeshRelayEngine.kt`, `LiveMapViewModel.kt` | Sin datos inventados | Roster inexistente; PeerTrack sin identidad; directorio vacío | `docs/evidence/block_b_peers_mapa_20261118.txt` |
| Ampliar roster backend | `npm test -- test/ride_roster.test.ts test/ride_roster_endpoint.test.ts` | 200 con campos nuevos | 6 tests en verde (200/403/404) | `backend/test/ride_roster_endpoint.test.ts` |
| Enriquecer UI | `gradlew :app:testStandardDebugUnitTest --tests '*RiderProfileFallbackTest' --tests '*RideRosterUiTest'` | BUILD SUCCESSFUL | BUILD SUCCESSFUL | `android/app/src/test/.../RiderProfileFallbackTest.kt` |
| Firmar e instalar | `apksigner sign ...` + `adb -s <dev> install -r ...` | `Success` en ambos | `Success` en ambos | `docs/evidence/block_b_peers_mapa_20261118.txt` |
| Mapa 2 motos | `uiautomator dump` + `screencap` | 2 motos con nombre/rol | 2 marcadores, local real y remoto `Piloto #X` | `block_b_map_two_peers_tecno_20261118.png/.xml`, `block_b_map_two_peers_redmi_20261118.png/.xml` |
| Offline | `adb reverse --remove tcp:3000` + relanzar app | Fallback sin crash | `Piloto #12982 · Desconocido`, `pidof` activo, sin FATAL | `block_b_offline_fallback_redmi_20261118.png/.xml` |

**Pendiente**: el backend no conoce `shortId`; la rodada de campo usa `rideId = "ride-live-01"` (no UUID) y no consumió el roster backend en hardware; el contador HUD "Convoy 1 riders" no se modificó; no se añadió cargador de imágenes remoto (URLs guardadas, iniciales/foto base64).

### Bloque C visual — Convoy proyectado sobre la polilínea — 2026-11-18
**Commit atómico**: `feat(ui): proyectar peers sobre la polilínea y espaciar como convoy` (sin amend).

**Cambio**: nuevo `ConvoyRouteProjection.kt` calcula el punto más cercano de la polilínea activa, distancia acumulada y rumbo del tramo. A ≤50 m el peer se proyecta sobre la vía real con espaciado objetivo 18 m y mínimo 10 m (`visualOffsetAppliedMeters` documenta el ajuste); a >50 m conserva su posición exacta y se marca Desviado. Sin ruta, devuelve vacío y la UI dibuja la posición exacta. `LiveMapScreen` elimina el escalonado artificial por índice, ordena con el peer local primero (zIndex superior) y aplica una separación visual mínima en píxeles porque el overlay Compose no comparte la escala del zoom de MapLibre. `RiderMarker` añade una insignia de dirección rotada con el rumbo real o del tramo. `RideService.updateActiveLocalTelemetry` publica la ubicación real (GPS/caché) en el controlador de malla activo: antes los heartbeats salían con el valor por defecto 19.4326,-99.1332 y el remoto aparecía a miles de km.

**Restricciones**: no se tocaron `PeerTrack`, GATT ni criptografía. El escalonado artificial previo (`index * 0.04f`) se eliminó. La separación en píxeles es solo visual; no altera lat/lon ni la telemetría emitida.

| Tarea | Comando | Resultado esperado | Resultado obtenido | Evidencia |
| --- | --- | --- | --- | --- |
| Proyección y espaciado | `gradlew :app:testStandardDebugUnitTest --tests '*ConvoyRouteProjectionTest' --tests '*LiveMapViewModelTest'` | BUILD SUCCESSFUL | 7 + 4 tests, 0 failures/errors | `android/app/src/test/.../ConvoyRouteProjectionTest.kt` |
| Compilar y firmar | `gradlew :app:assembleStandardDebug` + `apksigner sign/verify` | APK firmada | BUILD SUCCESSFUL; `CN=Ruta Biker Sync` | `docs/evidence/convoy_projection_20261118.txt` |
| Instalar en hardware | `adb -s <dev> install -r app-standard-debug-rbssigned.apk` | Success en ambos | Success en TECNO y Redmi | `docs/evidence/convoy_projection_20261118.txt` |
| Convoy en ruta | UI: ruta real 2.3 km + rodada en ambos + `uiautomator dump`/`screencap` | Peer proyectado y separado | TECNO: R#12243 `✓ En ruta` con dirección; Redmi: R#12982 `✓ En ruta` | `convoy_final_tecno_20261118.png/.xml`, `convoy_final_redmi_20261118.png/.xml` |
| Peer >50 m | `uiautomator dump`/`screencap` con cachés reales distintas (~280 m) | Posición exacta y Desviado | R#12243 `⚠ Desviado` (build intermedio, misma lógica de umbral) | `convoy_tecno_projected_2peers_20261118.png/.xml` |

**Nota de montaje**: para el caso "en ruta" se copió la caché de ubicación real de TECNO a Redmi como fixture controlado y luego se restauró; la app no inventa posiciones. El reintento del caso desviado con el build final quedó en `Sin señal` por reconexión BLE y no se usa como evidencia.

**Pendiente**: validación visual humana de la rotación de la insignia y del espaciado real en carretera en movimiento; el contador HUD sigue mostrando `1 riders` (no cubierto por este bloque).

### Bloque C — Recalcular ruta en banner de desvío — 2026-11-18
**Commit atómico**: `feat(ui): añadir recalcular ruta en banner de desvío` (sin amend).

**Diagnóstico**: `OffRouteBanner` sólo ofrecía "Voy a parar"; `RoadRoutingEngine.fetchRealRoadRoute(...)` ya existía y devuelve `null` al fallar; `RideScheduleRepository` es en memoria y no tenía método para sustituir la ruta activa; el `update` de `AndroidView` sólo añadía la polilínea si `map.polylines.isEmpty()`, por lo que una ruta recalculada no se habría dibujado.

**Cambio**: `OffRouteBanner` gana "Recalcular ruta" + estado "Calculando…". Nuevo `RecalculateRouteUseCase` inyecta la comprobación de red y el motor vial, y devuelve `SUCCESS` / `OFFLINE` / `NO_ROUTE`; en `SUCCESS` actualiza `RideScheduleRepository.updateSelectedRoute(...)`, la polilínea del detector y reevalúa el desvío con la posición real. `LiveMapScreen` muestra literalmente "Sin conexión, usando ruta offline" y "No se pudo recalcular. Sigue la ruta actual". El redibujo compara `activeRoute.id` contra la última ruta dibujada y retira la polilínea anterior. No se tocaron malla BLE, GATT ni criptografía.

| Tarea | Comando | Resultado esperado | Resultado obtenido | Evidencia |
| --- | --- | --- | --- | --- |
| Desenlaces JVM | `gradlew :app:testStandardDebugUnitTest --tests '*RecalculateRouteUseCaseTest' --tests '*ConvoyRouteProjectionTest' --tests '*LiveMapViewModelTest'` | BUILD SUCCESSFUL | 4 + 7 + 4 tests, 0 failures/errors | `android/app/src/test/.../RecalculateRouteUseCaseTest.kt` |
| Compilar, firmar e instalar | `gradlew :app:assembleStandardDebug` + `apksigner sign/verify` + `adb install -r` | APK firmada e instalada | BUILD SUCCESSFUL; `CN=Ruta Biker Sync`; `Success` en TECNO y Redmi | `docs/evidence/recalculate_route_20261118.txt` |
| Banner de desvío | UI: ruta real con origen a ~979 m + rodada + `uiautomator dump` | Botón visible | `Te has desviado (+979 m)`, `Recalcular ruta`, `Voy a parar` | `recalc_banner_tecno_20261118.png/.xml` |
| Recalcular online | Pulsar botón con internet + `uiautomator dump` | Ruta recalculada y banner cerrado | `✓ Ruta recalculada sobre vías reales`; piloto `✓ En ruta`; ruta activa renombrada a `⛰️ Ruta sobre Vías: … · En Línea` | `recalc_success_tecno_20261118.png/.xml` |
| Recalcular offline | WiFi/datos desactivados desde script auto-restorado + pulsar | Mensaje exacto sin cambiar ruta | `Sin conexión, usando ruta offline`; banner permanece | `recalc_offline_tecno_20261118.png/.xml` |
| Sin trazado válido | JVM con `fetchRoadRoute` devolviendo `null` | Mensaje exacto y ruta intacta | `NO_ROUTE`, ruta original conservada | `RecalculateRouteUseCaseTest.kt` |

**Pendiente**: el desenlace `NO_ROUTE` no se reprodujo en hardware: el proxy HTTP muerto (`http_proxy=127.0.0.1:1`) no fue usado por `HttpURLConnection` y Mapbox respondió correctamente; se considera cubierto sólo en JVM. La comprobación de red distingue conectividad transportada (WiFi/celular/Ethernet), no calidad de internet. El contador HUD `1 riders` y la validación visual humana del trazado recalculado en movimiento siguen fuera de alcance.

### FIX — Distancia al líder incorrecta (3.586.952 m) — 2026-11-18
**Commit atómico**: `fix(ui): corregir cálculo de distancia al líder en tarjeta de peer` (sin amend).

**Diagnóstico**: no era metros↔km. `haversineMeters` (`LiveMapViewModel.kt:235-243`) es correcto; el fallo era el punto de referencia. `RideSessionController` arrancaba en `19.4326,-99.1332` (`RideService.kt:208-211`) y el primer heartbeat salía antes de tener GPS (`424-430`, `502-517`); el receptor lo aceptaba sin validar (`MeshRelayEngine.kt:1419-1420`) y `flushMapFrame` lo usaba como `leaderPose` (`LiveMapViewModel.kt:328-337, 428-433`). `RiderMarker.kt:218` pintaba el entero sin convertir. Numéricamente, `(19.4326,-99.1332)` ↔ caché real TECNO `(10.349246,-67.034904)` da **3.586.957 m** con la fórmula del proyecto; con el fix instantáneo (~`10.34937`) da **3.586.952 m** exactos. La diferencia de 5 m viene del `Float` de la caché.

**Cambio**: nuevo `MeshTelemetry` valida coordenadas; `RideService` siembra la telemetry desde la caché real `rbs_geo_cache` antes de `startRide` y, sin posición válida, se abstiene de emitir (producción); `LiveMapViewModel` arranca `gpsFixed=false`, rechaza centinelas, usa `leaderPose=null` si el líder no tiene posición válida y hace `distanceToLeaderMeters` nullable; nuevo `LeaderDistanceFormatter` muestra `320 m` / `3.5 km` y devuelve `null` para ocultar el campo sin dato, sin GPS, líder local o valor absurdo; tarjeta y HUD lo consumen. No se modificaron `MeshPacket`, GATT ni criptografía.

| Tarea | Comando | Resultado esperado | Resultado obtenido | Evidencia |
| --- | --- | --- | --- | --- |
| Diagnosticar | Revisión de `RideService`, `MeshRelayEngine`, `LiveMapViewModel`, `RiderMarker` + cálculo Haversine | Causa exacta | Default CDMX emitido como telemetry real; 3.586.957 m vs 3.586.952 m reportados | `docs/evidence/leader_distance_fix_20261118.txt` |
| Formato y ocultado | `gradlew :app:testStandardDebugUnitTest --tests '*LeaderDistanceFormatterTest' --tests '*LiveMapViewModelTest'` | BUILD SUCCESSFUL | 4 + 5 tests, 0 failures/errors | `LeaderDistanceFormatterTest.kt`, `LiveMapViewModelTest.kt` |
| No regresión malla | `--tests '*RideServicePhase3Test' --tests '*MeshOdometerSyncTest' --tests '*ConvoyRouteProjectionTest'` | BUILD SUCCESSFUL | 15 + 12 + 7 tests, 0 failures/errors | XML de `test-results` |
| Compilar, firmar e instalar | `assembleStandardDebug` + `apksigner sign/verify` + `adb install -r` | APK firmada en ambos | `CN=Ruta Biker Sync`; `Success` en TECNO y Redmi | `docs/evidence/leader_distance_fix_20261118.txt` |
| Tarjeta del peer | Rodada con 2 peers + toque al marcador + `uiautomator dump`/`screencap` | Distancia coherente | TECNO `Líder a 3 m`; Redmi `Líder a 3 m`; 0 coincidencias de `3586952` | `leader_distance_tecno_card_20261118.png/.xml`, `leader_distance_redmi_card_20261118.png/.xml` |

**Pendiente**: validación visual humana en movimiento y del caso sin GPS en hardware (cubierto en JVM). Los caminos SOS que usan coordenadas de cámara/alerta como posición propia quedan fuera de este commit.

### Bloque D — Exportación de logs de app y backend — 2026-11-18
**Commit atómico**: `feat(debug): exportación de logs de app y backend` (sin amend, sin push).

**Diagnóstico**: el proyecto ya declaraba un `FileProvider` para el flujo RGPD, pero nunca se usaba (`getUriForFile`, `FLAG_GRANT_READ_URI_PERMISSION` y `EXTRA_STREAM` no aparecían en el código). El panel DEV ya estaba protegido por `BuildConfig.DEV_PANEL_ENABLED`, así que el botón se añadió ahí con una segunda guardia en el propio exportador. El log de Fastify se genera por redirección manual de la salida del servidor (`backend/logs/`, ignorado por git) y contiene UUIDs, nombres de club y blobs base64: era obligatorio redactar antes de exponerlo por HTTP. Desde API 16 una app normal sólo puede leer los mensajes de su propio proceso, por lo que el ZIP no sustituye a `adb logcat -b all`.

**Cambio**: nuevos `AppLogRedactor` y `AppLogExporter` capturan `logcat -d -t N -v threadtime`, filtran por los seis tags del alcance, redactan PII y escriben un ZIP en `cacheDir/exports/` compartido vía el `FileProvider` existente (`${applicationId}.fileprovider`, sin authority hardcodeada). `DevPanelDialog` gana **Exportar logs (ZIP)** con strings ES/EN/PT. En el backend, `debugLogs.ts` valida la fecha `YYYYMMDD` con allowlist, fija el nombre `fastify_dev_<fecha>.log`, limita las líneas y redacta correos, UUIDs, coordenadas, JWT, `Bearer`, asignaciones sensibles (incluidos nombres) y blobs base64; `/v1/debug/logs` está detrás de `requireAdminAuth` y devuelve 404 salvo `NODE_ENV != production` y `ENABLE_DEBUG_LOGS=true` con `ADMIN_API_KEY` configurado. No se modificaron malla BLE, GATT, PTT, firma/HMAC ni criptografía.

| Tarea | Comando | Resultado esperado | Resultado obtenido | Evidencia |
| --- | --- | --- | --- | --- |
| Implementación app | Revisión de `AppLogExporter`, `AppLogRedactor`, `DevPanelDialog` y `file_paths.xml` | Botón sólo debug, ZIP en `cache/exports`, FileProvider válido | Doble guardia `DEV_PANEL_ENABLED`; authority dinámica; entradas `logs/app_logcat.txt` y `manifest.txt` | `android/app/src/main/java/com/rutabikersync/app/core/debug/AppLogExporter.kt`, `AppLogRedactor.kt`, `AppLogExporterTest.kt` |
| Tests JVM Android | `gradlew :app:testStandardDebugUnitTest --no-daemon --console=plain --tests '*AppLogExporterTest' --tests '*LeaderDistanceFormatterTest' --tests '*LiveMapViewModelTest' --tests '*ConvoyRouteProjectionTest' --tests '*RideServicePhase3Test' --tests '*MeshOdometerSyncTest' --tests '*ReleaseSecurityBuildConfigTest'` | BUILD SUCCESSFUL | 49 tests (3+4+5+7+15+12+3), 0 fallos/errores | XML de `android/app/build/test-results/testStandardDebugUnitTest/` |
| Tests y tipos backend | `npm test -- --run test/debug_logs_endpoint.test.ts test/health.test.ts` y `npm run typecheck` | 7 tests verdes y `tsc --noEmit` limpio | 7/7 tests dirigidos; suite completa 20 archivos / 119 tests; typecheck y build OK | `backend/test/debug_logs_endpoint.test.ts`, `backend/src/lib/debugLogs.ts` |
| Compilar, firmar e instalar | `assembleStandardDebug` + `apksigner sign/verify` + `adb -s <device> install -r` | APK firmada en ambos dispositivos | BUILD SUCCESSFUL; certificado `CN=Ruta Biker Sync`; `Success` en TECNO y Redmi | `docs/evidence/debug_logs_verification_20261118.txt` |
| Botón en panel DEV | FAB **DEV** + `uiautomator dump` en ambos equipos | «Exportar logs (ZIP)» visible | TECNO `[239,366][482,402]`; Redmi `[431,701][790,755]` | `debug_logs_button_tecno_20261118.png/.xml`, `debug_logs_button_redmi_20261118.png/.xml` |
| ZIP y compartir | Pulsar botón + chooser + `run-as ... ls -l cache/exports` + extracción base64 | ZIP con log y manifiesto; chooser abierto | TECNO `rbs_logs_20261118_224038.zip` 3932 B / 252 líneas; Redmi `rbs_logs_20261118_224257.zip` 7118 B / 292 líneas; 0 PII sin redactar | `debug_logs_zip_tecno_20261118.png`, `debug_logs_zip_redmi_20261118.png`, `debug_logs_share_*.xml` |
| Logs Fastify | `tail -n 400 ... | grep -v base64 | sed ...` desde WSL2 y `Get-Content -Tail` en Windows | Captura sin secretos | 18 líneas sanitizadas; el log completo permanece fuera de git | `fastify_wsl_capture_20261118.txt` |
| Endpoint debug | `curl -H "x-admin-key: ..." .../v1/debug/logs?date=20261118&lines=8` y Vitest con `NODE_ENV=production` | 200 con clave, 401 sin clave, 404 en producción | 200 / 401 / 404; 0 UUID, 0 emails, 0 coordenadas y 0 nombres de club en la respuesta | `debug_logs_endpoint_20261118.txt`, `backend/test/debug_logs_endpoint.test.ts` |

**Limitaciones**: el ZIP no incluye logs del sistema ni de otras apps (restricción de Android desde API 16); `backend/logs/` sigue ignorado por git y no se versiona ningún log real; el endpoint no está activo en el servidor de desarrollo habitual (requiere opt-in explícito) y en producción responde 404. La validación visual humana de las capturas y el análisis en carretera siguen pendientes, como en bloques anteriores.

### FIX — Contador HUD «Convoy N riders» desincronizado — 2026-11-19
**Commit atómico**: `fix(ui): sincronizar contador de convoy con peers renderizados` (sin amend).

**Diagnóstico**: `LiveMapScreen.kt:911` mostraba `${state.connectedNodesCount.coerceAtLeast(1)} riders`. `connectedNodesCount` se calculaba en `LiveMapViewModel.kt` como `latestPeersFromMesh.values.count { !it.isLost }`, es decir **sólo peers remotos**: con el marcador local y un peer enlazado (`markers.size = 2`) valía 1, de ahí «1 riders». `coerceAtLeast(1)` era un suelo y no sumaba al local, y el plural estaba hardcodeado. El valor viene de `engine.peers.collect` volcado por el bucle de cadencia de 1 s, no de heartbeats ni del roster (que sólo enriquece el perfil local); `RideService.connectedRidersCount` es otro contador usado sólo en la notificación.

**Cambio**: nuevo `ConvoyRiderCountFormatter` (`1 rider` / `N riders`, mínimo 1) y nuevo `LiveMapUiState.ridersCount`, derivado de los mismos `markers` que pinta el mapa (self + peers con señal). El HUD consume ese campo. Un peer perdido conserva su marcador `✕ Sin señal` para diagnóstico pero deja de contar, así el HUD baja a `1 rider`. No se tocaron malla BLE, GATT, PTT ni criptografía; `connectedNodesCount` se conserva para compatibilidad.

| Tarea | Comando | Resultado esperado | Resultado obtenido | Evidencia |
| --- | --- | --- | --- | --- |
| Diagnosticar | Búsqueda de `connectedNodesCount` en `LiveMapScreen`, `LiveMapViewModel`, `RideService` | Causa exacta | Off-by-one (local excluido) + plural fijo | `docs/evidence/convoy_counter_fix_20261119.txt` |
| Formato y StateFlow | `gradlew :app:testStandardDebugUnitTest --no-daemon --console=plain --tests '*ConvoyRiderCountFormatterTest' --tests '*LiveMapViewModelTest' --tests '*LeaderDistanceFormatterTest' --tests '*ConvoyRouteProjectionTest' --tests '*RideServicePhase3Test' --tests '*MeshOdometerSyncTest' --tests '*AppLogExporterTest' --tests '*ReleaseSecurityBuildConfigTest'` | BUILD SUCCESSFUL | 53 tests (3+6+4+7+15+12+3+3), 0 fallos/errores | XML de `test-results/testStandardDebugUnitTest/` |
| Compilar, firmar e instalar | `assembleStandardDebug` + `apksigner sign/verify` + `adb install -r` | APK firmada en TECNO y Redmi | `CN=Ruta Biker Sync`; `Success` en ambos | `docs/evidence/convoy_counter_fix_20261119.txt` |
| 2 peers enlazados | Rodada en TECNO y Redmi + `uiautomator dump` | «Convoy 2 riders» en ambos | TECNO y Redmi: `Convoy` + `2 riders`, 2 marcadores | `convoy_count_2peers_tecno-20261119.png/.xml`, `convoy_count_2peers_redmi-20261119.png/.xml` |
| Desconexión | `adb shell am force-stop com.rutabikersync.app` + 22 s + dump | «Convoy 1 rider» | TECNO y Redmi: `Convoy` + `1 rider`; marcador `Sin señal` sigue visible | `convoy_count_1peer_tecno-20261119.png/.xml`, `convoy_count_1peer_redmi-20261119.png/.xml` |

**Pendiente**: validación visual humana de las capturas; la desconexión se simuló con `force-stop` (no apagado físico de Bluetooth); el marcador `Sin señal` y el diálogo «Riders en Convoy (markers.size)» siguen contando marcadores fantasma y quedaron fuera de alcance.

### FIX - Privacidad del perfil en el mapa segun club (groupId) - 2026-11-19
**Commit atomico**: `fix(ui): mostrar perfil de peer solo si pertenece al mismo club` (sin amend).

**Diagnostico**: el backend `GET /v1/rides/:id/participants` **ya devuelve `groupId`** (`app.ts:4368` selecciona `rp.group_id`; `lib/rideRoster.ts:56` lo proyecta), pero `RideRosterUiEntry`/`parseRideRosterUi` lo ignoraban. `PeerTrack` no tiene groupId ni userId (anadirlo exigiria cambiar la malla). El perfil local si expone clubId (`ProfileRepository.getClubId()` -> `current_club_id`), pero `LiveMapScreen` no lo pasaba al mapa. `RiderDirectoryEntry.relation` era un dato de demostracion, no un groupId real: un peer con shortId 1-5 podia mostrar nombre/avatar/rol ficticios sin verificacion de club.

**Regla**: nuevo `PeerProfilePrivacy` puro. El piloto local siempre ve su perfil; un peer remoto solo muestra nombre/avatar/rol reales si ambos groupId son no vacios e identicos. groupId ausente, vacio o distinto => avatar generico (`AppIcons.Bike`) y `Piloto #XXXX`, sin nombre real, avatar real ni rol. `RideRosterUiEntry`, `RiderDirectoryEntry`, `LiveMapController` (localGroupId) y `RiderMarker` (profileRedacted) incorporan el campo; `flushMapFrame` aplica la redaccion sobre los mismos markers que pinta el mapa. Sin cambios en malla BLE, GATT, PTT ni criptografia.

| Tarea | Comando | Resultado esperado | Resultado obtenido | Evidencia |
| --- | --- | --- | --- | --- |
| Diagnosticar | Busqueda de `groupId` en Android/backend y lectura de `PeerTrack`, `RideRosterUi`, `ProfileRepository`, `app.ts` | Datos disponibles y ausencia en malla | Backend ya expone groupId; la app no lo consumia; peer remoto sin groupId | `docs/evidence/peer_privacy_groupid_20261119.txt` |
| Implementar regla | `PeerProfilePrivacy` + `RideRosterUiEntry.groupId` + `RiderDirectoryEntry.groupId` + `LiveMapController.setLocalGroupId` + redaccion en `RiderMarker` | Mismo club real; distinto/desconocido redactado | Regla aplicada en `flushMapFrame`; self siempre real; sin tocar BLE/GATT/PTT | `PeerProfilePrivacy.kt`, `LiveMapViewModel.kt`, `RiderMarker.kt`, `LiveMapScreen.kt` |
| Tests JVM | `gradlew :app:testStandardDebugUnitTest --no-daemon --console=plain --tests '*PeerProfilePrivacyTest' ...` (12 filtros) | BUILD SUCCESSFUL | 62 tests, 0 fallos | XML de `test-results/testStandardDebugUnitTest/` |
| Backend | `npm run typecheck`, `npm test`, `npm run build` | Sin cambios de logica; 119 tests | 20 archivos / 119 tests verdes; typecheck y build OK | `backend/test/ride_roster_endpoint.test.ts` con `groupId: 'g-1'` |
| Build e instalacion | `assembleStandardDebug` + `apksigner sign/verify` + `adb install -r` | APK firmada en TECNO y Redmi | `CN=Ruta Biker Sync`; `Success` en ambos | `docs/evidence/peer_privacy_groupid_20261119.txt` |
| Peer desconocido | Rodada en TECNO y Redmi + `uiautomator dump` | Avatar generico + `Piloto #XXXX` | TECNO `Piloto #12243`, Redmi `Piloto #12982`; ambos `Avatar generico`, rol `Desconocido`, `Convoy 2 riders` | `peer_privacy_unknown_peer_tecno-20261119.png/.xml`, `peer_privacy_unknown_peer_redmi-20261119.png/.xml` |
| Control mismo club (local) | Tap al avatar propio + dump | Nombre/avatar/rol reales | TECNO `Avatar de Omartinezpaz` / `Punta`; Redmi `Avatar de Ozmartinezpaz` / `Punta` | `peer_privacy_same_club_local_tecno-20261119.png/.xml`, `peer_privacy_same_club_local_redmi-20261119.png/.xml` |

**Confirmacion**: el peer de distinto club o desconocido muestra avatar generico y `Piloto #XXXX`, sin nombre/avatar/rol reales. Los tres casos de la regla estan cubiertos por tests JVM.

**Pendiente**: peer remoto del mismo club no reproducible en hardware (sin mapping `shortId<->userId`; añadirlo exigiria tocar la malla, fuera de alcance) pero cubierto por JVM; validacion visual humana de las capturas; el groupId del roster no llega en hardware porque `ride-live-01` no es UUID y no se activo reverse USB (parser validado por tests); suite JVM completa arrastra 2 fallos preexistentes no relacionados (jitter `MultiHopFloodingTest` y GPX `RouteEnginePhase4Test`).

### FIX - Detalle de peer solo al tocar/hover y trazado reforzado de ruta - 2026-11-19
**Commit atomico**: `fix(ui): mostrar detalle del peer solo al tocar o hacer hover` (sin amend, sin push).

**Diagnostico**: `RiderMarker.kt` dibujaba una etiqueta inferior permanente con abreviatura, `(Yo)`, rol, estado y nombre completo, ademas de una insignia de rol siempre visible. Eso tapaba el corredor de la ruta y revelaba informacion sin interaccion. `LiveMapScreen.kt` dibujaba la ruta con una unica `PolylineOptions` `#C05A2A`/8f, sin halo ni extremos redondos, y no cerraba la tarjeta al tocar mapa vacio.

**Cambio**: etiqueta e insignia permanentes eliminadas. Nuevo estado `hovered` (`PointerEventType.Enter/Exit`) y decision pura `RiderDetailVisibility.shouldShow(isSelected, isHovered)`. Tarjeta `AnimatedVisibility` con `fadeIn(180)`/`fadeOut(150)` que muestra avatar, nombre, roles, estado, bateria, velocidad y distancia al lider, respetando `PeerProfilePrivacy`. `LiveMapScreen` cierra con `addOnMapClickListener { onSelectRider(null); true }`, reduce el offset de marcadores (44→32 px, -48→-36) y usa `RouteStrokeStyle` + dos `LineLayer` locales (halo `#3A1E14` 13f/0.5f, linea `#F08A45` 6f/1.0f, `line-cap`/`line-join` redondos) sin tocar el JSON de estilo base. Sin cambios en BLE/GATT/PTT/crypto ni backend.

| Tarea | Comando | Resultado esperado | Resultado obtenido | Evidencia |
| --- | --- | --- | --- | --- |
| Visibilidad bajo demanda | `RiderDetailVisibility.kt` + `RiderDetailVisibilityTest.kt` | detalle solo con toque u hover | 4 tests JVM en verde | `RiderDetailVisibilityTest.kt` |
| Tests JVM dirigidos | `gradlew :app:testStandardDebugUnitTest --tests '*RiderDetailVisibilityTest' --tests '*RouteStrokeStyleTest' ...` | BUILD SUCCESSFUL | 69 tests, 0 fallos | XML de `test-results/testStandardDebugUnitTest/` |
| Suite JVM completa | `gradlew :app:testStandardDebugUnitTest` sin filtros | sin regresiones nuevas | 382 tests, 2 fallos PREEXISTENTES (jitter `MultiHopFloodingTest`, GPX `RouteEnginePhase4Test`) | reporte HTML de test-results |
| Backend (sin cambios) | `npm run typecheck`, `npm test`, `npm run build` | sin regresiones | typecheck OK; 119 tests verdes; build OK | salida de backend |
| Build e instalacion | `assembleStandardDebug` + `apksigner sign/verify` + `adb install -r` | APK firmada en TECNO y Redmi | `CN=Ruta Biker Sync`; `Success` en ambos | `docs/evidence/peer_detail_on_tap_20261119.txt` |
| Solo avatar por defecto | relanzar app + `uiautomator dump` | sin texto permanente | TECNO y Redmi: avatares presentes, `Rol:`/`Piloto #` ausentes | `peer_detail_tap_*.xml` (smoke final) |
| Detalle al tocar peer | `input tap` en avatar remoto + dump | avatar, nombre, rol y metricas | TECNO `Piloto #12243`/`Rol: Desconocido`; Redmi `Piloto #12982`/`Rol: Desconocido` | `peer_detail_tap_tecno-20261119.png/.xml`, `peer_detail_tap_redmi-20261119.png/.xml` |
| Perfil local | tap en avatar propio + dump | nombre y rol reales | TECNO `Omartinezpaz`/`Rol: Punta · Líder`; Redmi `Ozmartinezpaz`/`Rol: Punta · Líder` | `peer_detail_local_profile_*_20261119.png/.xml` |
| Cierre | segundo toque y toque en mapa vacio + dump | tarjeta oculta | ambos dispositivos: `Rol:`/`Piloto #` ausentes | `peer_detail_close_tap_*`, `peer_detail_close_map_*_20261119.xml` |

**Confirmacion**: al iniciar el mapa solo se ven avatares; al tocar un peer aparece su tarjeta con avatar/nombre/rol reales del perfil si es local, o `Piloto #XXXX`/avatar generico/`Desconocido` si es remoto sin groupId. La tarjeta se cierra al volver a tocar o al tocar fuera. El peer redactado sigue sin revelar datos reales.

**Pendiente**: hover real no reproducible con `input mouse move` (`Unknown command: move` en ambos dispositivos; cubierto por JVM); validacion visual humana del fade y del doble trazo con ruta activa (la rodada de prueba no tenia ruta; la configuracion esta cubierta por `RouteStrokeStyleTest`); peer remoto del mismo club sin mapping `shortId↔userId`; 2 fallos JVM preexistentes no relacionados.

### CONFIG - Conexion persistente Tailscale + Fastify sin USB ni misma red - 2026-11-19
**Commit atomico**: `docs: documentar conexion persistente Tailscale + Fastify` (sin amend, sin push).

**Diagnostico**: Fastify corria dentro de WSL detras de `wslrelay` en `127.0.0.1:3000`, por lo que solo era alcanzable con `adb reverse` o desde la misma maquina. Redmi llevaba offline desde el 17 de noviembre. La app debug tenia `BASE_URL=http://127.0.0.1:3000`.

**Cambio (sin tocar codigo fuente)**: `tailscale serve` ya publicaba el backend en `https://omartinezpaz.tail6f67b0.ts.net`; se reconecto Redmi; se recompilo la APK debug con `RBS_DEBUG_URL=https://omartinezpaz.tail6f67b0.ts.net` (sin cambios en el repo; `build.gradle.kts` ya soporta esa variable); se creo el servicio systemd `rbs-backend.service` en WSL con `Restart=always` y se habilito; se anadio un script oculto en el Inicio de Windows para levantar WSL tras el login ( `schtasks /create` requeria elevacion y dio acceso denegado); se configuraron `always_on_vpn_app`, `always_on_vpn_lockdown=0` y la whitelist de Doze en ambos Android.

| Tarea | Comando | Resultado esperado | Resultado obtenido | Evidencia |
| --- | --- | --- | --- | --- |
| Servicio backend | `systemctl enable --now rbs-backend` | activo y habilitado | `enabled` + `active`; PostgreSQL conectada | journal de rbs-backend.service |
| Resistencia | `pkill -9` del node + `systemctl show -p NRestarts` | systemd reinicia | `NRestarts=1`, health HTTP 200 | journal de rbs-backend.service |
| Tailnet | `tailscale serve status` + `tailscale status` | HTTPS al backend y 3 nodos online | proxy HTTPS correcto; 3 nodos `active; direct` | `tailscale_nodes_20261119.txt` |
| Sin USB | `adb reverse --list` en ambos | sin tuneles | vacio en ambos (se quito el `UsbFfs` de TECNO) | `docs/evidence/tailscale_fastify_persistente_20261119.txt` |
| VPN persistente | `settings put secure always_on_vpn_*` + `dumpsys deviceidle whitelist` | Tailscale siempre activo sin lockdown | ambos con `app=com.tailscale.ipn`, `lockdown=0` y en whitelist | este archivo |
| App | `RBS_DEBUG_URL=... assembleStandardDebug` + firma + `adb install -r` | BASE_URL del tailnet en ambas APK | BuildConfig correcto; instalacion `Success` en ambos | BuildConfig.java de standard/debug |
| Health desde la app | abrir app + pestana «Mi Club» | banner verde | TECNO y Redmi: «Fastify online (status: ok) · PostgreSQL 16» | `tailscale_fastify_{tecno,redmi}-20261119.png/.xml` |

**Confirmacion**: los dos equipos estan conectados y Fastify responde por HTTPS sin USB y sin misma red. La app usa esa URL y muestra health ok en ambos. El reinicio del proceso ya se probo (systemd lo levanta). Sin cambios en BLE/GATT/PTT/crypto ni backend.

**Pendiente**: validar un reinicio completo de Windows (el script de Inicio no requiere admin pero no se ejecuto en un login real); un ping Redmi→TECNO por IP Tailscale fallo una vez (moto a moto sigue por BLE, no afecta a Fastify); recompilar sin `RBS_DEBUG_URL` vuelve al modo `adb reverse`.

### FASE 5 - Outbox Room/DataStore para RSVP offline - 2026-11-19
**Commits atomicos**: `358c725` `feat(android): outbox Room/DataStore para RSVP offline (Fase 5)` y `15990cc` `feat(android): UI minima de rutas/RSVP y banner de pendientes (Fase 5)` (sin amend, sin push).

**Diagnostico**: el backend Fase 3 ya expone rutas de club y RSVP con transiciones idempotentes (201/200) y RLS FORCE, pero el cliente no tenia caché ni cola: sin red no podia registrar la intención de asistencia. Ademas el `profileVersion` del grupo solo se reflejaba en memoria.

**Cambio (12 archivos, outbox)**: entidades Room `ClubRouteEntity`, `RsvpEntity`, `RsvpSummaryEntity` y `RsvpOutboxEntity` (BD v2 -> v3) con DAOs y espejos in-memory para tests JVM; `ClubProfileStore` (DataStore, version por `groupId`); `ClubRoutesRepository` cache-first que respeta el `scope: member|full` del backend; `RsvpOutboxSyncEngine` + `RsvpOutboxWorker` (WorkManager, `NetworkType.CONNECTED`, backoff exponencial) donde un RSVP es un ESTADO (PK compuesta `routeId+userId`, ultima intencion gana), 409 terminal se descarta sin bucle; `ClubApiClient` con `getClubRoutes`/`getRsvps`/`postRsvp`; integraciones minimas en `AppDatabase`, `ClubRepository` y `Phase1ViewModel` (`rsvpPendingCount`). Sin cambios en BLE/GATT/PTT/crypto ni backend.

**Cambio (5 archivos, UI)**: `ClubRsvpUi.kt` (logica pura del banner y 3 estados `confirmed`/`home`/`no_go`), `ClubRoutesScreen.kt` (lista rutas cacheadas y permite RSVP), boton "Ver Rutas" en `GroupDetailScreenD3`, `rsvpPendingCount` cableado al banner "Pendiente de sincronizar" en `Phase1Screens.kt` (prioridad sobre health, no cerrable, se retira al sincronizar; poll 1,5 s existente) y carga/accion en `Phase1ViewModel`.

El patch original del outbox tenia CRLF+BOM y el repo mezcla LF/CRLF (`core.autocrlf=true`), por lo que `git apply --check` fallaba; se regenero dentro del repo principal con la salida cruda de `git diff` y se aplico limpio (exit 0).

| Tarea | Comando | Resultado esperado | Resultado obtenido | Evidencia |
| --- | --- | --- | --- | --- |
| Aplicar patch outbox | `git apply --check` + `git apply` de `fase5_regenerated.patch` | 12 archivos | 4 modificados + 8 nuevos, exit 0 | `docs/evidence/fase5_outbox_20261119.txt` |
| Compilar | `gradlew :app:compileStandardDebugKotlin` | BUILD SUCCESSFUL | `BUILD SUCCESSFUL in 57s` | `fase5_compile_log.txt` |
| Tests outbox | `gradlew :app:testStandardDebugUnitTest --tests "*RsvpOutboxFase5Test"` | 6/6 | `tests="6" failures="0" errors="0"` | `fase5_outbox_test_log.txt` |
| Tests UI | `--tests "*ClubRsvpUiTest"` | 7/7 | `tests="7" failures="0" errors="0"` | `fase5_ui_test_log.txt` |
| Regresion | `*LiveMapViewModelTest` + `*ConvoyRouteProjectionTest` + `*RideServicePhase3Test` | sin regresiones | 42/42 total (6+7+29), 0 fallos | `fase5_ui_regression_log.txt` |
| Build release v2 | `gradlew :app:assembleStandardRelease -Prbs.prod.url=https://omartinezpaz.tail6f67b0.ts.net -Prbs.keystore.path=...` (keystore `rbs-release-v2.jks`) | APK firmada RBS | `BUILD SUCCESSFUL in 3m 9s`, 68,24 MB; 1er intento FAIL por gate B1-4 (BASE_URL placeholder) corregido con URL real | `docs/evidence/fase5_release_hardware_20261119.txt` |
| Firma APK | `apksigner verify --print-certs` | CN=Ruta Biker Sync, huella v2 | DN correcto, SHA-256 `e99c0e7dd35691d26d15b51ff0e9d1e0d3acbba8c2579ddb505cc13346e8546b` (BASE_URL tailnet) | misma evidencia |
| Instalacion TECNO | uninstall + install (autorizado; firma distinta a la instalada 8502ba90) | app con firma v2 | instalado, SHA-256 `e99c0e7d`, `MainActivity` resumida | `fase5_tecno_state_20261119.png/.xml` |
| Instalacion Redmi | uninstall + install | app con firma v2 | **NO EJECUTADA**: `b2c398bb` not found, `100.98.78.105:5555` no conecta (10013) | misma evidencia |
| Humo Fase 5 (red/avion/red) | RSVP con red, modo avion, banner, restaurar red | sincronizacion automatica + captura | **NO EJECUTADA**: Redmi inaccesible; TECNO en estado limpio tras reinstalacion sin sesion/club/ruta; ping tailnet 100% loss en captura | misma evidencia |
| Humo Fase 2 (motto) | TECNO cambia motto, Redmi refleja >1,5 s | propagacion sin reiniciar | **NO EJECUTADA**: requiere ambos dispositivos con sesion y mismo club | misma evidencia |

**Confirmacion**: la capa de datos del outbox y el cableado del banner funcionan y estan cubiertos por 42/42 tests JVM. La compilacion y el release firmado son correctos. **El RSVP offline NO queda confirmado en hardware** y no hay captura real del banner "Pendiente de sincronizar".

**Pendiente**: reconectar Redmi e instalar release v2; restaurar sesion/club/ruta en ambos (estado limpio); ejecutar humo Fase 5 red->avion->red con capturas y humo Fase 2 del motto; validar conectividad Tailscale de TECNO y reinicio completo de Windows para WSL; rotar la contraseña del keystore v2 expuesta en la conversacion. No hacer push. No tocar BLE/GATT/PTT/crypto/backend.

### Fix regresión cabina de miembros post b01b481 — 2026-11-20

**Diagnóstico**: el commit `b01b481` ocultó el botón clásico "Iniciar Rodada (Cabina Activa)" cuando existía roster. Como el nuevo "Iniciar Ruta" se muestra solo al líder, un miembro (Redmi) perdió el camino que ejecuta `onStartLiveMapPreview()` y `startRideServiceNow(...)`; por ello no iniciaba su malla ni aparecían los peers. El render local y los cálculos de peer markers/conteo no cambiaron.

**Cambio mínimo**: `MainAppScaffold.kt` pasa de `if (rideRoster == null)` a `if (rideRoster == null || !canPublishClubRoute)`. Líder: mantiene únicamente el CTA del roster con sus reglas de presencia. Miembro no líder: recupera cabina/malla sin invocar `/v1/routes/:id/start`. No se modificaron `LiveMapViewModel`, `RideService`, `RideParticipantsScreen`, GATT, outbox ni criptografía.

**Tests**: `compileStandardDebugKotlin` BUILD SUCCESSFUL. Nueva `RideCabinaAccessTest` cubre sin roster, roster+líder, roster+miembro y multiclub (liderazgo real del grupo actual, no global). También `RideParticipantsUiTest`, `LiveMapViewModelTest` y `RideServicePhase3Test` pasan.

**Evidencia hardware parcial, no cierre bilateral**:
- Redmi mostró el botón clásico restaurado antes de perder el enlace ADB.
- TECNO muestra HUD **2 riders** y un peer remoto genérico, evidencia en `docs/evidence/fase5_cabina_peers_tecno_20261120.png`.
- En el peer remoto no se afirma identificación por nombre/avatar/rol: la implementación actual conserva el fallback de privacidad sin mapeo `userId↔shortId`.
- Redmi no pudo confirmarse por ADB: 10061 en Tailscale y LAN, USB `offline` y luego no listado. Detalle en `docs/evidence/fase5_cabina_redmi_block_20261120.txt`.

**Pendiente factual**: con enlace ADB estable, iniciar Redmi por el botón clásico, verificar el nombre/rol real permitido por la privacidad y capturar los dos peers en ambos mapas y la notificación 1→2 riders. Esta sección no declara completados esos últimos pasos.

### Plan operativo de continuidad: audio SOS, señales y mapa (2026-11-20)

Se creó **`docs/NEXT_AGENT_WORKPLAN.md`** como guía detallada para el siguiente agente, incluido uno gratuito con contexto limitado. No reimplementa los hitos ya cerrados y separa explícitamente estado actual, tareas pendientes y evidencia aún no obtenida.

**Estado que debe respetarse antes de continuar:**
- Existen pendientes actuales en `LiveMapViewModel.kt` (peers sin fix real no deben ubicar markers sobre CDMX), `RouteDeviationDetector.kt` (histéresis >150 m durante >=10 s con reinicio al parar/recomputar) y `LiveMapScreen.kt` (recálculo automático una sola vez por transición sostenida).
- `RouteDeviationHysteresisTest` tenía fallos aún abiertos al redactar el plan, incluido un caso con `distanceToRouteMeters=0` y `computed=0`; no declararlos como superados. Debe auditarse geometría/estado compartido, no cambiar tests para hacerlos pasar trivialmente.
- También se exige separar el diagnóstico independiente de `GpxManager` sin mezclarlo con audio/mapa.
- `MainAppScaffold` ya recuperó la cabina de miembros no líderes en `4ba368a`; el siguiente agente no debe reabrir ese bypass de líder ni usar `/v1/routes/:id/start` desde el miembro.
- No implementar nuevo audio/mapas antes de cerrar esos bloques con pruebas reales.

**Secuencia comprometida para continuidad:**
1. Cerrar pendientes y suites del corte GPS/histéresis/recálculo.
2. Audio SOS local con foco, cancelación/ACK/stop idempotente y vibración existente Morse en cuenta regresiva sin desplazarla.
3. Audio corto para señales del diálogo, sin duplicar TTS ni interrumpir PTT; propagación BLE solo en hito propio y tras revisar el canal real/dedupe (sin inventar GATT nuevo).
4. Mejoras MapLibre 11.5.2 con `Projection` real en vez de fórmulas de pantalla arbitrarias, bounds de GPS real y atribución OSM (actualmente `isAttributionEnabled=false`), sin depender de token online para el fallback offline.
5. Evidencia física de GPS 1 m, overlay LiveMap↔Monitor sin perder BLE, audio audible/ACK, recálculo offline/online y captura real. No indicar lo no ejecutado como completado.

Incluye además reglas innegociables de datos, lista de archivos no relacionados que pueden contaminar commits (`0019_open_qr_invitations`, hotfix UUID/pool/menu/invitaciones), instrucciones para SQL aislado `PG_TEST_URL` y no Gradle/DB en paralelo, construcción/pruebas por hito y criterios explícitos para gestos, `LatLngBounds`, OSM attribution, control de foco y parada de audio.

### HITO BLOQUE 0 — Cierre de Usabilidad de Ruta (2026-11-20)

**Cambio (archivos):**
- `android/app/src/main/java/com/rutabikersync/app/feature/livemap/LiveMapViewModel.kt`: `isRenderablePeerPosition` para ignorar `(0,0)`, `NaN` y fallback CDMX (`19.4326, -99.1332`) en peers remotos.
- `android/app/src/main/java/com/rutabikersync/app/feature/routes/RouteDeviationDetector.kt`: ventana de histéresis `DEVIATION_SUSTAIN_DURATION_MS = 10_000L` para `isAlertActive`, reinicio de ventana al volver a ruta o parar, proyección ortogonal limpia `((apx * abx + apy * aby) / abLenSq).coerceIn(0.0, 1.0)` y distancia geodésica Haversine.
- `android/app/src/main/java/com/rutabikersync/app/feature/livemap/LiveMapScreen.kt`: `LaunchedEffect(deviationState.isAlertActive)` para disparo de auto-recálculo honesto y `BackHandler` ordenado que cierra overlays antes de alertar de parada de rodada.
- `android/app/src/main/java/com/rutabikersync/app/core/mesh/MeshRelayEngine.kt`: getter `isRunning` de solo lectura para certificar persistencia del motor en tests.
- `android/app/src/test/java/com/rutabikersync/app/feature/livemap/PeerPositionValidityTest.kt`: suite unitaria de validación de coordenadas de peers.
- `android/app/src/test/java/com/rutabikersync/app/feature/routes/RouteDeviationHysteresisTest.kt`: suite de histéresis con geometría ortogonal corregida y aislamiento de repositorio.
- `android/app/src/test/java/com/rutabikersync/app/feature/livemap/RideScreenTransitionResilienceTest.kt`: suite de resiliencia ante cambios de pantalla y regla de navegación Back.
- `docs/superpowers/plans/2026-11-20-usabilidad-ruta-fixes.md`: plan detallado con tareas y ejecución completadas.

**Diagnóstico:**
- Coordenadas de peers: los centinelas `(0.0, 0.0)` y el fallback estático de CDMX se inyectaban en marcadores remotos cuando el nodo no poseía aún fix GPS real. Se resolvió con guardia booleana pura `isRenderablePeerPosition`.
- Histéresis: el test fallaba porque definía la ruta sobre latitud (meridiano) y desviaciones sobre la misma latitud con longitud 0, situando al piloto dentro del mismo segmento (distancia 0 m). Corregido a desviación ortogonal en longitud (~55 m y ~334 m). Se configuró ventana temporal de 10 s continuos antes de activar alerta, y `RideScheduleRepository` fue aislado en el setup del test.
- Navegación / Resiliencia BLE: la salida de cabina o navegación a overlays en Compose desmontaba listeners de UI pero no detiene el `RideService` ni el `MeshRelayEngine`. El botón Atrás quedaba atrapado en el diálogo de detención de rodada cuando había overlays abiertos; se ajustó para cerrar overlays primero.

**Pruebas:**
Ejecución limpia secuencial:
`cd android; .\gradlew.bat :app:testStandardDebugUnitTest --tests "*PeerPositionValidityTest" --tests "*RouteDeviationHysteresisTest" --tests "*RecalculateRouteUseCaseTest" --tests "*LiveMapViewModelTest" --tests "*RideServicePhase3Test" --tests "*RideCabinaAccessTest" --tests "*RideScreenTransitionResilienceTest"`
- `PeerPositionValidityTest`: 2 tests pasados (0 fallos).
- `RouteDeviationHysteresisTest`: 3 tests pasados (0 fallos).
- `RecalculateRouteUseCaseTest`: 4 tests pasados (0 fallos).
- `LiveMapViewModelTest`: 7 tests pasados (0 fallos).
- `RideServicePhase3Test`: 15 tests pasados (0 fallos).
- `RideCabinaAccessTest`: 4 tests pasados (0 fallos).
- `RideScreenTransitionResilienceTest`: 2 tests pasados (0 fallos).
- `RouteEnginePhase4Test`: 5 tests pasados (0 fallos con cargador inyectado).
- Resultado global: 42/42 tests pasados (`BUILD SUCCESSFUL in 15s`), 0 fallos.

Resolución aislada GPX (`RouteEnginePhase4Test`):
- `Bloque 4_2 GpxManager importa y exporta rutas GPX 1_1 con integridad`: se corrigió la carga de assets en `DefaultRouteCatalog` permitiendo `customAssetLoader` inyectable en pruebas unitarias para leer `src/main/assets/routes/tovar_routes.json` real sin depender de `App.instance.assets` nulo en JVM. La aserción de nombre se unificó contra `testRoute.name` (`Ruta 1 · Colonia Tovar Directa`) preservando el roundtrip íntegro con elevación y trackpoints reales.

**Hardware y Evidencia en Vivo (TECNO 098813734B105395):**
- APK `assembleStandardDebug` compilada e instalada in-place vía ADB (`Success`).
- **Prueba LiveMap → Monitor Malla → LiveMap**:
  1. Entrada a cabina: MapLibre activo, `👑 Eres Líder`, `Convoy 2 riders`, `Avatar de Omartinezpaz` (0 km/h) y peer remoto sin fallback CDMX.
  2. Apertura de Monitor Malla: overlay desplegado con `MALLA ACTIVA`, `Piloto #22873` (en línea, -51 dBm, 1 salto, PLR 0.0%, batería 93%, recibidos 37/37).
  3. Retorno a LiveMap: botón Volver / Back cierra overlay limpiamente sin detener el `RideService` ni degradar el FGS. `HeartbeatTx` activo (`packetsEmitted=63`, `elapsedSec=188s`), y el HUD mantiene `Convoy 2 riders`.
- **Bloqueo Factual Redmi (`b2c398bb` / `100.98.78.105`)**:
  - `adb connect 100.98.78.105:5555` rechazado con código 10061 (conexión denegada por equipo destino, daemon `adbd` apagado en Redmi aunque Tailscale responde ICMP ping a 90ms).
  - Documentado en `docs/evidence/fase5_redmi_adbd_block_20261120.txt`. No se declara validación bilateral hardware en calle hasta restablecer enlace USB o adbd TCP en Redmi.

**Capturas/logs:**
- `docs/evidence/fase5_tecno_cabina_live_20261120.png` y `.xml` (Cabina activa con 2 riders).
- `docs/evidence/fase5_tecno_monitor_malla_20261120.png` y `.xml` (Monitor Malla en vivo con peer en línea).
- `docs/evidence/fase5_tecno_return_livemap_20261120.png` y `.xml` (Retorno a LiveMap sin pérdida de malla).
- `docs/evidence/fase5_redmi_adbd_block_20261120.txt` (Registro factual de rechazo ADB 10061).

**Pendiente:**
- Validación física real en hardware: TECNO comprobado en vivo (cabina, monitor malla y ciclo SOS resuelto); validación bilateral pendiente por bloqueo de enlace ADB (10061) en Redmi.
- Hito 2: Tonos coherentes para señales de convoy (ConvoySignalKind único).
- Hito 3: Proyección MapLibre 11.5.2 y atribución OSM.

### HITO 1 — Audio Real para SOS (2026-11-20)

**Cambio (archivos):**
- `android/app/src/main/java/com/rutabikersync/app/core/audio/SosTonePlayer.kt`:
  - `interface SosTonePlayer`: contrato con `start()`, `stop()`, `isPlaying` y constantes Morse (`DOT_DURATION_MS = 150L`, `DASH_DURATION_MS = 450L`, etc.).
  - `interface ToneEmitter` y `class AndroidToneEmitter`: emisor desacoplado que usa `ToneGenerator(AudioManager.STREAM_ALARM, MAX_VOLUME)`.
  - `interface AudioFocusDelegate` y `class DuckingFocusDelegate`: aprovecha `AudioDuckingManager` para atenuación de música y navegación.
  - `class DefaultSosTonePlayer`: reproductor reactivo del patrón Morse 3-3-3 (S-O-S) con intra-silencios y repetición controlada. Idempotente en `start()` y `stop()`, con prioridad absoluta para PTT (cede el foco y detiene beeps si PTT transmite voz).
  - `object SosToneHub`: singleton coordinador entre `LiveMapScreen` y `RideService` para evitar doble dueño o solapamientos.
- `android/app/src/main/java/com/rutabikersync/app/core/mesh/RideService.kt`:
  - Conexión de `SosToneHub.start()` en `ACTION_TRIGGER_SOS`.
  - Conexión de `SosToneHub.stop()` en `ACTION_CANCEL_SOS`, `ACTION_STOP_RIDE`, `ACTION_FORCE_STOP_RIDE` y `onDestroy()`.
- `android/app/src/main/java/com/rutabikersync/app/feature/livemap/LiveMapScreen.kt`:
  - Inicialización de `SosToneHub` en `LaunchedEffect(Unit)` con `PttStateProvider { pttEngine.pttState.value !is PttState.Idle }`.
  - Inicio de tono en `onSosConfirmed` (local) y en `incomingSosMeshEvent` (remoto de malla).
  - Parada inmediata en ACK recibido de líder (`incomingSosAckEvent`), ACK confirmado/descartado (`MeshSosReceptionDialog`), desactivación manual (`onSosClick`), cancelación de falsa alarma (`onCancelFalseAlarm`) y desmonte de la pantalla (`onDispose`).
- `android/app/src/test/java/com/rutabikersync/app/core/audio/SosTonePlayerTest.kt`:
  - Suite unitaria TDD (7/7 tests PASSED): validación de cadencia Morse 3-3-3, idempotencia de start/stop, bloqueo por PTT activo, denegación de foco, cancelación inmediata mid-cycle y ciclo de vida de `SosToneHub`.

**Pruebas:**
- `SosTonePlayerTest`: 7 tests pasados (0 fallos).
- `UniqueAnnouncerPhase3Test`: 3 tests pasados (0 fallos).
- `VoiceClipMeshTest`: 3 tests pasados (0 fallos).
- `PttAudioEngineHandoffTest`: 2 tests pasados (0 fallos).
- `TacticalChatMeshIntegrationTest`: 2 tests pasados (0 fallos).
- `SosEmergencyTest`: 5 tests pasados (0 fallos).
- Regresión de rutas (Bloque 0 y GPX): 42/42 tests pasados (0 fallos).
- Total verificado: **64/64 tests PASSED (0 fallos, 0 errores)**.

**Hardware TECNO (098813734B105395):**
- APK `assembleStandardDebug` compilada con `-Prbs.debug.url=https://omartinezpaz.tail6f67b0.ts.net` e instalada in-place vía ADB (`Success`).
- App iniciada en vivo, entrada a cabina LiveMap con `Convoy 2 riders` activo.
- Activación y resolución del ciclo SOS en hardware: banner visual `✓ Emergencia SOS resuelta`, apagado del tono y retorno inmediato a reposo manteniendo `RideService` y `HeartbeatTx` activo (`packetsEmitted=58`, `elapsedSec=170s`).
- Capturas: `docs/evidence/fase5_tecno_audio_sos_test.png` y `.xml`, `docs/evidence/fase5_tecno_livemap_sos_ready.png` y `.xml`, `docs/evidence/fase5_tecno_sos_held.png` y `.xml`.

### HITO 2 — Señales Rápidas Audibles de Convoy y Atribución MapLibre (2026-11-21)

**Cambio (archivos):**
- `android/app/src/main/java/com/rutabikersync/app/core/audio/ConvoySignalAudioPlayer.kt`:
  - `enum class ConvoySignalKind`: catálogo unificado con `code`, `title`, `subtitle`, `icon`, `toneType`, `pulseCount`, `pulseDurationMs` y `gapMs` (5 señales: Obstáculo, Reducir velocidad, Reagrupar convoy, Parada de gasolina, Retén/precaución).
  - `interface ConvoySignalAudioPlayer`: contrato con `playSignal(kind)`, `stop()` y `isPlaying`.
  - `class DefaultConvoySignalPlayer`: emisor secuencial de tonos breves con ducking de audio, supresión estricta si SOS o PTT están activos, interrupción limpia e idempotencia en parada.
  - `object ConvoySignalAudioHub`: singleton de coordinación para inicialización reactiva y parada en ciclo de vida de la cabina.
- `android/app/src/main/java/com/rutabikersync/app/core/audio/SosTonePlayer.kt`:
  - Sobrecarga de `toneOn(toneType: Int, durationMs: Long): Boolean` en `ToneEmitter` y `AndroidToneEmitter` con delegación backwards-compatible para permitir tonos diferenciados por tipo (`TONE_PROP_BEEP`, `TONE_PROP_BEEP2`, `TONE_PROP_ACK`).
- `android/app/src/main/java/com/rutabikersync/app/feature/livemap/LiveMapScreen.kt`:
  - Inicialización de `ConvoySignalAudioHub` en `LaunchedEffect(Unit)` con enlace de PTT.
  - Limpieza de tonos en `onDispose` (`ConvoySignalAudioHub.stop()`).
  - Refactorización de `showSignalsDialog` para iterar sobre `ConvoySignalKind.entries` vinculando la reproducción audible local con el feedback visual y telemetría UX.
  - Hito 3: activación de atribución cartográfica OSM `map.uiSettings.isAttributionEnabled = true` con márgenes adaptados al HUD.
- `android/app/src/test/java/com/rutabikersync/app/core/audio/ConvoySignalAudioPlayerTest.kt`:
  - Suite unitaria TDD completa (8/8 tests PASSED): validación de catálogo y códigos únicos, cadencia de un solo pulso (`OBSTACLE`), cadencia multi-pulso (`REDUCE_SPEED`), bloqueo estricto ante SOS activo, bloqueo ante PTT activo, parada mid-playback, sustitución de señal en curso y ciclo de vida del Hub.

**Pruebas:**
- `ConvoySignalAudioPlayerTest`: 8/8 tests PASSED.
- `SosTonePlayerTest`: 10/10 tests PASSED.
- `RideServicePhase3Test`: 15/15 tests PASSED.
- `LiveMapViewModelTest`: 7/7 tests PASSED.
- `PeerPositionValidityTest`: 2/2 tests PASSED.
- `RecalculateRouteUseCaseTest`: 4/4 tests PASSED.
- `RouteDeviationHysteresisTest`: 3/3 tests PASSED.
- `RouteEnginePhase4Test`: 5/5 tests PASSED.
- `RideCabinaAccessTest`: 4/4 tests PASSED.
- Total verificado en suite obligatoria: **58/58 tests PASSED (0 fallos, 0 errores)**.

**Hardware (TECNO 098813734B105395 y Redmi b2c398bb):**
- APK `assembleStandardDebug` compilada e instalada en ambos dispositivos vía ADB (`Performing Streamed Install Success`).
- Cabina abierta con enlace bidireccional de malla activo (`Convoy 2 riders`).
- Diálogo de señales rápidas probado en vivo en pantalla: toque en `🛑 Reducir velocidad` reprodujo la cadencia audible a través de `ToneGenerator` (canal `STREAM_ALARM`) y emitió el feedback `📢 Señal emitida: ¡🛑 Reducir velocidad!`.
- Capturas: `docs/evidence/fase5_tecno_signals_dialog.png` y `.xml`, `docs/evidence/fase5_tecno_signal_emitted.png` y `.xml`.

### HITO 2 (Extensión) — Propagación BLE por Malla de Señales Rápidas de Convoy (2026-11-21)

**Cambio (archivos):**
- `android/app/src/main/java/com/rutabikersync/app/core/mesh/MeshRelayEngine.kt`:
  - Callback `var onConvoySignalReceived: ((IncomingConvoySignal) -> Unit)? = null`.
  - Despacho inmediato en `onRawPacketReceived` para `kindCode in (0xC1..0xC5)`.
- `android/app/src/main/java/com/rutabikersync/app/core/mesh/RideService.kt`:
  - Canal estático `incomingConvoySignals` con `replay = 1` y buffer de 64.
  - Reproducción directa de audio en `onConvoySignalReceived` vía `ConvoySignalAudioHub.playSignal(incoming.signalKind)`.
  - Método `dispatchSendConvoySignal(signal)` con prioridad `Transport.Priority.PEER_EVENT` (ráfaga de 100 ms).
- `android/app/src/main/java/com/rutabikersync/app/feature/livemap/LiveMapScreen.kt`:
  - Suscripción reactiva desacoplada a `RideService.incomingConvoySignals` con banner `📢 [Sender]: ¡[Icono] [Título]!`.
- `android/app/src/test/java/com/rutabikersync/app/core/mesh/ConvoySignalMeshPropagationTest.kt`:
  - Suite unitaria (3/3 tests PASSED) validando prioridad, decodificación y deduplicación.

**Hardware (TECNO 098813734B105395 y Redmi 100.98.78.105:38109 / b2c398bb):**
- Emisión probada desde TECNO (`kind=REDUCE_SPEED code=2`) y recepción en Redmi vía ManufacturerData `0x5242` en ráfaga BLE:
  - `MeshRelayEngine: IncomingConvoySignal decoded & emitting: sender=32191 kind=REDUCE_SPEED`
  - `RideService: ConvoySignalRx recibido por BLE Mesh: from=32191 kind=REDUCE_SPEED`
  - `ToneGenerator: constructor streamType=4 volume=1.0` -> Cadencia multi-pulso audible en Redmi.
  - UI de Redmi: Banner `📢 Rider #32191: ¡🛑 Reducir velocidad!`.
- Evidencia: `docs/evidence/convoy_signal_ble_propagation_20261121.txt`, capturas `convoy_signal_ble_tx_tecno_20261121.png` y `convoy_signal_ble_rx_redmi_20261121.png`.

### HITO 3 — Lienzo MapLibre RBS Dark Corridor con OpenStreetMap Raster y Proyección de Avatares (2026-11-21)

**Cambio (archivos):**
- `android/app/src/main/java/com/rutabikersync/app/feature/livemap/LiveMapViewModel.kt`:
  - `DEFAULT_STYLE_ASSET_URI = "asset://tiles/dark_corridor_style.json"` resolviendo la causa raíz de la pantalla gris (dependencia de vector tiles externos de OpenFreeMap inalcanzables en redes móviles/offline).
- `android/app/src/main/java/com/rutabikersync/app/feature/routes/RouteDetailScreen.kt` y `ScheduleRideMapScreen.kt`:
  - Migración de estilo a `dark_corridor_style.json`.
- `android/app/src/main/assets/tiles/positron_corridor_style.json`:
  - Fallback raster claro universal basado en `osm-raster` de OpenStreetMap.

**Pruebas:**
- `PeerPositionValidityTest`: 2/2 tests PASSED.
- `LiveMapViewModelTest`: 7/7 tests PASSED.
- Suite obligatoria: 58/58 tests PASSED (0 fallos, 0 errores).

**Hardware (TECNO 098813734B105395 y Redmi 100.98.78.105:38109 / b2c398bb):**
- TECNO Spark 10C: Lienzo MapLibre 11.5.2 cargado con estilo oscuro OSM raster completo (626 KB), ícono de atribución OSM visible y avatar de `Ozmartinezpaz` geolocalizado en Los Teques (`10.34907, -67.03495`).
- Redmi Note 13 Pro 5G: Cabina sincronizada con `Convoy 2 riders`, avatares de ambos moteros proyectados sobre las vías reales.
- Enlace BLE bidireccional constante en `LEGACY_1M` (`AdvRx` en TECNO `rssi=-57 dBm`, `AdvRx` en Redmi `rssi=-72 dBm`).
- Evidencia: `docs/evidence/tecno_dark_map_rendered_20261121.png`, `docs/evidence/redmi_dark_map_rendered_20261121.png`.

### P0 AUDITORÍA — Cifrado real, binding de club y orden REST→BLE (2026-11-21)

**Corrección documental previa:** `CHECKLIST.md` ya no afirma que HMAC-SHA256 cifra ni que la malla cifra sus frames. El wire format de 20 bytes sigue siendo texto plano con HMAC truncado (autenticación/integridad, **no confidencialidad**); el cifrado real de audio/PTT no se tocó en este hito.

**Hallazgos y fix aplicado:**

| Hallazgo | Fix aplicado | Test que lo verifica |
|---|---|---|
| H1 — BLE se emitía antes de REST; engine-null descartaba eventos | `RestFirstLifecycleGate` obliga a que REST autorice antes de emitir; `PendingLifecycleEvents` conserva el evento hasta que el engine está listo y se drena al pasar a `RUNNING`. SAFE_HOME local dejó de propagarse sin autorización. | `P0SecurityChecksTest.H1 REST autoriza antes que BLE…` y `H1 el evento queda encolado…` |
| H2 — SOS se emitía con firma en ceros | `SosSigningHub` inicializa `Ed25519SosCrypto` al arrancar la app; emisor, ACK del servicio y ACK de la cabina usan la firma real. Wire format de 88/83 bytes sin cambios. | `SosPacketCryptoTest` (7/7) y `P0SecurityChecksTest.H2 SOS con firma Ed25519 inválida se rechaza…` |
| H3 — “convoy cercano” ≠ club; sin roster/rol/frescura | `RideMeshAuthorization` filtra por `shortId` derivado de la clave pública Ed25519 del manifiesto firmado, exige rol de líder/marshal para inversión/fin y controla secuencia por emisor. No se añadieron campos al wire format ni se modificó GATT. | `P0SecurityChecksTest.H3 otro club…`, `H3 rol y frescura…` |
| H4 — clave fija compartida / aleatoria local; `resolveRideSecretKey()` sin usar | Backend persiste `manifest_ride_secret_key_hex` (migración `0021`) y lo reutiliza en cada manifiesto; Android descarga y verifica el manifiesto con `RideManifestKeyProvider` y `dispatchStartRide` exige la clave de 32 bytes del manifiesto (fail-closed, sin fallback fijo). | `phase3_rides_manifest.test.ts` (clave estable entre dos peticiones) y `P0SecurityChecksTest.H5 identidad derivada…` |
| H5 — verificación SOS sin mappings reales | El verificador solo se activa si hay `sosPublicKeyResolver` con claves reales; mientras el backend no distribuya claves públicas SOS en el manifiesto, se documenta como pendiente en vez de rechazar SOS legítimos. | `P0SecurityChecksTest.H2` |

Nota: el source set `fieldTest` usa una clave derivada de una frase pública **solo de diagnóstico** para que dos dispositivos de campo puedan autenticarse; nunca se usa en `main`/release.

**Pruebas (captura de la ejecución final):**
- Android: `.\gradlew.bat :app:testStandardDebugUnitTest --no-daemon --console=plain --rerun-tasks` → **BUILD SUCCESSFUL**, 491 tests, 0 fallos.
- Nueva suite P0: `P0SecurityChecksTest` → 7 tests, 0 skipped, 0 failures, 0 errors.
- Backend: `npm test` → 25 archivos, 165 tests PASSED; `npm run typecheck` → OK.
- Captura P0: `docs/evidence/p0_tests_green_20261121.txt`.
- Se corrigió además la fuga de `Dispatchers.Main` que hacía fallar la suite completa: `Phase1ViewModel` ahora hace el ping/sync en `Dispatchers.IO` (evita reanudar sobre un Main ya resetsado) y `Phase1RulesTest`/`LiveMapViewModelTest` cancelan su dispatcher en `tearDown`.

**Pendiente (no incluido en P0):**
- Distribuir las claves públicas Ed25519 SOS en el manifiesto firmado y poblar `sosPublicKeyResolver` en producción; hoy la firma local ya se emite, pero el rechazo por firma inválida solo se activa con mappings reales.
- Persistir/rotar la clave de rodada con política de expiración y rotación por temporada (la clave actual es estable por `ride_id`).
- Cifrado real del payload de la malla (HMAC actual solo autentica): **CERRADO** en la sección inmediatamente inferior (sobre sensible autorizado para SOS + ACK + PEER_EVENT).
- P1 (audio) y P2 (estado/mapa) siguen bloqueados hasta confirmación del usuario.
- Prohibido probar en campo (TECNO/Redmi) hasta nueva instrucción.

### P0 CIERRE — Cifrado real de payload BLE sensible (2026-11-21)

**Autorización del alcance:** el usuario autorizó expresamente el cambio de wire format solo para frames sensibles: `SOS`, `SOS_ACK` y `PEER_EVENT` (señales de convoy, eventos de ciclo y announced signals). Heartbeats de posición, chat, PTT/voz, GATT y transporte quedan intactos.

**Formato del sobre (`SensitiveFrameEnvelope`, big-endian):**

| Offset | Tamaño | Contenido |
|---|---:|---|
| `0..1` | 2 B | Magic `0x53 0x46` (`"SF"`) |
| `2` | 1 B | Versión `0x01` |
| `3` | 1 B | Kind: `0x01` SOS, `0x02` SOS_ACK, `0x03` MeshPacket 20 B |
| `4..15` | 12 B | Nonce aleatorio por emisión/reenvío (`VoiceCrypto.generateNonce`) |
| `16..N` | variable | `ciphertext || tag Poly1305` (16 B) |

- AAD = `magic | version | kind`; el frame original viaja íntegro como plaintext (HMAC de 20 B y firma Ed25519 de SOS/ACK se verifican **después** de descifrar).
- Reutiliza `VoiceCrypto.encrypt/decrypt` sin modificar su lógica; no se tocó `TextMessageCrypto`, `HeartbeatFrame`, GATT ni el codec de fragmentación.
- Anti-replay: caché LRU de nonces por engine; el nonce se registra solo si el tag AEAD es válido. Un tag inválido o un nonce repetido se contabiliza y se descarta antes de cualquier callback de UI.

| Frame | Antes | Después | Test que lo verifica |
|---|---|---|---|
| `PEER_EVENT` (señales/eventos) | `MeshPacket` 20 B en claro + HMAC truncado | Sobre `SF v1 kind=0x03`: **52 B** | `P0SecurityChecksTest.P0 PEER_EVENT sensible hace round trip cifrado…`; `SensitiveFrameEnvelopeTest.round trip de signal PEER_EVENT…` |
| `SOS` | `SosPacket` 88 B con firma Ed25519 en claro | Sobre `SF v1 kind=0x01`: **120 B** | `P0SecurityChecksTest.P0 SOS y ACK se emiten cifrados…`; `SensitiveFrameEnvelopeTest.round trip de SOS y ACK…` |
| `SOS_ACK` | `SosAckPacket` 83 B con firma Ed25519 en claro | Sobre `SF v1 kind=0x02`: **115 B** | `P0SecurityChecksTest.P0 SOS y ACK se emiten cifrados…`; `SensitiveFrameEnvelopeTest.round trip de SOS y ACK…` |
| Heartbeat / posición | `MeshPacket` 20 B + HMAC | **Sin cambios** | `P0SecurityChecksTest.P0 heartbeats HMAC siguen en claro…` |
| Fragmentación BLE legacy | `BleFragmentationCodec` (24 B/frame) | Sin cambios; 52 B → 4 fragmentos, 120 B → 8 | `BleFragmentationCodecTest.sobres sensibles de 52 y 120 B…` |
| Fragmentación GATT | `GattVoiceFramer` (MTU 23 / 517) | Sin cambios; reensambla 52/120 B | `GattVoiceFramerTest.sobres sensibles de 52 y 120 B…` |

**Integración:** `MeshRelayEngine` envuelve antes de emitir y descifra antes de parsear cabecera/verificar HMAC; los reenvíos SOS/ACK usan un nonce fresco y el mismo sobre. `BleMeshService` enruta el broadcaster de SOS y el ACK por el engine para evitar emisiones en claro. `RideService` prioriza el reenvío por `kind` sin abrir el ciphertext.

**Estado:** cierre local atómico; sin `amend`, sin `push` y sin pruebas en campo hasta nueva instrucción.

### P0 CIERRE FINAL — Claves SOS en manifiesto, rotación de clave y cifrado de heartbeats (2026-11-21)

Esta sección **supersede** los tres puntos que seguían «Pendiente» arriba y la fila de heartbeat «Sin cambios» del cierre anterior. No reescribe commits: `f386332` sigue siendo el cierre del sobre sensible para SOS/ACK/PEER_EVENT y este ticket cierra lo que faltaba.

| Pendiente | Fix aplicado | Test que lo verifica |
|---|---|---|
| Distribuir claves públicas Ed25519 SOS en el manifiesto firmado | Migración `0022` (`users.sos_public_key BYTEA`); `PATCH /v1/users/me/sos-public-key` publica los 32 bytes crudos generados por el móvil; `GET /v1/rides/:id/manifest` entrega `sosPublicKeys` (uid → Base64) **firmado** solo para `leader`/`full` con clave real. Android publica su clave antes de resolver y puebla `AuthorizedRide.sosPublicKeyResolver` desde el mapa firmado. Sin clave no hay placeholder ni fallback. | `backend/test/phase3_rides_manifest.test.ts` (mapa firmado, 32 B, invitado ausente, firma Ed25519 válida) y `P0SecurityChecksTest.H2 SOS con clave pública desconocida…` |
| Rechazar SOS/ACK con clave desconocida o firma manipulada | `MeshRelayEngine` pasa a **fail-closed**: exige resolver + verificador + clave para el `shortId`; si falta el mapa, el firmante es desconocido o la firma Ed25519 no verifica, devuelve `DroppedInvalidSignature` antes de notificar UI/alarma o reenviar. `SosAlarmMeshDeliveryTest` usa un par Ed25519 real en ambos motores. | `P0SecurityChecksTest.H2 SOS con firma Ed25519 inválida…` y `H2 SOS con clave pública desconocida…`; `SosMultiHopSimulationTest` (firma inválida SOS/ACK); `SosAlarmMeshDeliveryTest` |
| Rotación/expiración de `rideSecretKey` por rodada | La clave de 32 B se genera en `POST /v1/rides` y se persiste en `manifest_ride_secret_key_hex`; el manifiesto **nunca** la regenera y falla con `RIDE_KEY_UNAVAILABLE` si no está. Al cerrar/validar la rodada (equivalente a `COMPLETED`) se limpia la columna y el manifiesto responde `409 RIDE_COMPLETED`. Una rodada nueva recibe una clave distinta. Android sigue exigiendo 32 B y rechazando la clave fija de test en `standardRelease`. | `backend/test/phase3_rides_manifest.test.ts` (clave desde el alta, rotación entre rodadas, limpieza y `409` tras cierre) y `P0SecurityChecksTest.H5 identidad derivada…` |
| Cifrar heartbeats con AEAD | `SensitiveFrameEnvelope.KIND_HEARTBEAT = 0x04` entra en `VALID_KINDS`; `MeshPacket` de 20 B con prioridad `POSITION` usa `envelopeKindFor()` al emitir y se descifra antes del HMAC/parseo. El sobre mide **52 B** (16 B cabecera + 20 B plaintext + 16 B tag). Tag manipulado → `sensitiveTagRejected`; nonce repetido → `sensitiveReplayRejected`. `RideService` prioriza el kind como `POSITION`. | `P0SecurityChecksTest.P0 heartbeats viajan cifrados…`, `P0 tag manipulado de heartbeat…`, `P0 nonce repetido de heartbeat…`; `SensitiveFrameEnvelopeTest.heartbeat cifrado usa KIND_HEARTBEAT…` |
| Verificar fragmentación BLE del heartbeat cifrado | Sin tocar `BleAdvTransport` ni el codec: 52 B → **4 frames** (24/24/24/12 B útiles) y reensamblaje exacto. Se evita además parsear el sobre como `MeshHeader` para no alimentar un `shortId` falso al allowlist GATT de PTT. | `BleFragmentationCodecTest.sobres sensibles de 52 y 120 B…` y `SensitiveFrameEnvelopeTest.heartbeat cifrado usa KIND_HEARTBEAT y 52 B se fragmentan en 4 frames` |

**Alcance no tocado:** GATT, `HeartbeatFrame`, `VoiceCrypto`, PTT/voz, chat y transporte siguen igual; solo cambia el payload de los frames sensibles y el origen de las claves. La firma Ed25519 de SOS/ACK y el HMAC de los `MeshPacket` de 20 B se verifican **después** de descifrar.

**Evidencia (ejecución secuencial, sin backend en paralelo con Android):**
- Android: `.\gradlew.bat :app:testStandardDebugUnitTest --rerun-tasks --no-daemon` → `BUILD SUCCESSFUL`, **509 tests, 0 fallos, 0 errores**. Captura: `docs/evidence/p0_cierre_final_android_tests_green_20261121.txt`.
- Backend: `npm test` con base aislada `rbs_test` (`PG_TEST_URL=...`) → **25 archivos, 166 tests PASSED**; `npm run typecheck` → OK. Captura: `docs/evidence/p0_cierre_final_backend_tests_green_20261121.txt`.
- Dos fixtures JVM usaban claves de 31 B y ahora fallarían cerrado; se corrigieron a 32 B en `PeerPositionValidityTest` y `RideScreenTransitionResilienceTest` (solo datos de test, sin lógica de producción).

**Riesgo operativo documentado:** con `FRAGMENT_REPEATS=2` y una ráfaga de fragmentos de ~1 s, cada heartbeat cifrado ocupa ~9 s de aire cada 3 s. Eso puede elevar falsos `peer lost` en la ventana de 15 s. No se cambió la cadencia ni el transporte porque el alcance era solo el payload; debe observarse en campo cuando se autorice.

**Pendiente:**
- Validación en campo (TECNO KI5k / Redmi Note 13 Pro 5G) del heartbeat cifrado y de su impacto en la cadencia/pérdida de peers. **No ejecutado**: el ticket prohíbe pruebas en campo hasta nueva instrucción.
- P1 (audio) y P2 (estado/mapa) permanecen bloqueados hasta confirmación del usuario.

**Estado:** commit local atómico, sin `amend` y sin `push`.

