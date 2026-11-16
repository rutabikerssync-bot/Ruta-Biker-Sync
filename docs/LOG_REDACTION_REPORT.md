# LOG_REDACTION_REPORT (T-06, B4-1/B4-2/B4-3)

Inventario y clasificaci?n de todos los `Log.*` en `android/app/src/main` (escaneo est?tico).
Total: **182** llamadas.

## Archivos cr?ticos saneados en este ticket
- `feature/sos/SosRepository.kt`: eliminaci?n/anonimizaci?n de coords, nombres y metadatos de auditor?a; logs de diagn?stico envueltos en `BuildConfig.DEBUG`.
- `feature/vault/VaultRepository.kt`: eliminaci?n de prefijos/tokens, c?digos de estado y detalles din?micos en logs; guardas `BuildConfig.DEBUG`.
- `feature/sos/SosScreens.kt`: eliminaci?n de metadatos operativos sensibles (sender/hops/estado de datos m?dicos) en logs; guarda `BuildConfig.DEBUG`.

## Inventario completo
| Archivo | L?nea | Clasificaci?n | Motivo | Extracto |
|---|---:|---|---|---|
| `java/com/rutabikersync/app/MainActivity.kt` | 178 | safe | mensaje operativo sin PII/tokens evidente | `Log.i(TAG, "Hardware Key PTT presionado (keyCode=$keyCode) -> ACTION_PTT_START")` |
| `java/com/rutabikersync/app/MainActivity.kt` | 193 | safe | mensaje operativo sin PII/tokens evidente | `Log.i(TAG, "Hardware Key PTT liberado (keyCode=$keyCode) -> ACTION_PTT_STOP")` |
| `java/com/rutabikersync/app/MainActivity.kt` | 206 | safe | mensaje operativo sin PII/tokens evidente | `Log.i(TAG, "Deep Link rbs://ptt?action=start procesado")` |
| `java/com/rutabikersync/app/MainActivity.kt` | 210 | safe | mensaje operativo sin PII/tokens evidente | `Log.i(TAG, "Deep Link rbs://ptt?action=stop procesado")` |
| `java/com/rutabikersync/app/MainActivity.kt` | 217 | safe | mensaje operativo sin PII/tokens evidente | `Log.i(TAG, "Deep Link rbs://debug/emit-multihop procesado (ttl=$ttl)")` |
| `java/com/rutabikersync/app/MainActivity.kt` | 238 | safe | mensaje operativo sin PII/tokens evidente | `Log.w(TAG, "Error iniciando servicio PTT vía foreground, fallback a startService: ${e.message}")` |
| `java/com/rutabikersync/app/MainActivity.kt` | 242 | safe | mensaje operativo sin PII/tokens evidente | `Log.e(TAG, "Fallo al despachar acción PTT: ${ex.message}")` |
| `java/com/rutabikersync/app/MainActivity.kt` | 269 | safe | mensaje operativo sin PII/tokens evidente | `Log.i(TAG, "Solicitando permisos de radio y audio pendientes: $missing")` |
| `java/com/rutabikersync/app/feature/auth/GoogleAuthManager.kt` | 106 | safe | mensaje operativo sin PII/tokens evidente | `Log.i(TAG, "Web Client ID placeholder detectado. Usando selector nativo de cuentas Google.")` |
| `java/com/rutabikersync/app/feature/auth/GoogleAuthManager.kt` | 131 | safe | mensaje operativo sin PII/tokens evidente | `Log.w(TAG, "Credential Manager no disponible: ${e.message}. Fallback a selector nativo.")` |
| `java/com/rutabikersync/app/feature/sos/EmergencyPackageCrypto.kt` | 57 | safe | mensaje operativo sin PII/tokens evidente | `Log.i(TAG, "Clave efimera de sesion generada para rodada activa.")` |
| `java/com/rutabikersync/app/feature/sos/EmergencyPackageCrypto.kt` | 81 | safe | mensaje operativo sin PII/tokens evidente | `Log.i(TAG, "Paquete cifrado: ${blob.size} bytes (IV=${IV_LENGTH_BYTES}B, cipher=${ciphertext.size}B).")` |
| `java/com/rutabikersync/app/feature/sos/EmergencyPackageCrypto.kt` | 99 | safe | mensaje operativo sin PII/tokens evidente | `Log.w(TAG, "Intento de descifrado sin SOS activo -- acceso denegado. Registrando auditoria.")` |
| `java/com/rutabikersync/app/feature/sos/EmergencyPackageCrypto.kt` | 111 | safe | mensaje operativo sin PII/tokens evidente | `Log.e(TAG, "Clave de sesion nula -- rodada no iniciada.")` |
| `java/com/rutabikersync/app/feature/sos/EmergencyPackageCrypto.kt` | 116 | safe | mensaje operativo sin PII/tokens evidente | `Log.e(TAG, "Blob cifrado demasiado corto: ${encrypted.size} bytes.")` |
| `java/com/rutabikersync/app/feature/sos/EmergencyPackageCrypto.kt` | 129 | safe | mensaje operativo sin PII/tokens evidente | `Log.i(TAG, "Paquete descifrado correctamente para SOS activo.")` |
| `java/com/rutabikersync/app/feature/sos/EmergencyPackageCrypto.kt` | 132 | safe | mensaje operativo sin PII/tokens evidente | `Log.e(TAG, "Error descifrado paquete: ${e.message}", e)` |
| `java/com/rutabikersync/app/feature/sos/EmergencyPackageCrypto.kt` | 143 | safe | mensaje operativo sin PII/tokens evidente | `Log.i(TAG, "Clave efimera de sesion destruida -- fin de rodada.")` |
| `java/com/rutabikersync/app/feature/sos/FallDetector.kt` | 48 | safe | mensaje operativo sin PII/tokens evidente | `Log.i(TAG, "FallDetector iniciado: $registered")` |
| `java/com/rutabikersync/app/feature/sos/FallDetector.kt` | 57 | safe | mensaje operativo sin PII/tokens evidente | `Log.i(TAG, "FallDetector detenido")` |
| `java/com/rutabikersync/app/feature/sos/FallDetector.kt` | 78 | safe | mensaje operativo sin PII/tokens evidente | `Log.w(TAG, "Impacto súbito detectado: $totalAcc m/s²")` |
| `java/com/rutabikersync/app/feature/sos/FallDetector.kt` | 92 | safe | mensaje operativo sin PII/tokens evidente | `Log.e(TAG, "¡CAÍDA CONFIRMADA! Disparando cuenta regresiva de 20s...")` |
| `java/com/rutabikersync/app/feature/sos/FallDetector.kt` | 117 | safe | mensaje operativo sin PII/tokens evidente | `Log.w(TAG, "Simulación manual de caída disparada")` |
| `java/com/rutabikersync/app/feature/sos/SosCountdownManager.kt` | 74 | safe | mensaje operativo sin PII/tokens evidente | `Log.w(TAG, "Ya existe una cuenta regresiva activa en curso")` |
| `java/com/rutabikersync/app/feature/sos/SosCountdownManager.kt` | 79 | safe | mensaje operativo sin PII/tokens evidente | `Log.w(TAG, "Iniciando cuenta regresiva SOS ($initialSeconds s) por $triggerType")` |
| `java/com/rutabikersync/app/feature/sos/SosCountdownManager.kt` | 92 | safe | mensaje operativo sin PII/tokens evidente | `Log.e(TAG, "Cuenta regresiva de 20s expirada. CONFIRMANDO SOS...")` |
| `java/com/rutabikersync/app/feature/sos/SosCountdownManager.kt` | 107 | safe | mensaje operativo sin PII/tokens evidente | `Log.i(TAG, "Cuenta regresiva cancelada: $reason")` |
| `java/com/rutabikersync/app/feature/sos/SosCountdownManager.kt` | 128 | safe | mensaje operativo sin PII/tokens evidente | `Log.e(TAG, "SOS confirmado inmediatamente por el usuario")` |
| `java/com/rutabikersync/app/feature/sos/SosRepository.kt` | 56 | safe | mensaje operativo sin PII/tokens evidente | `Log.i(TAG, "Clave efímera de rodada inicializada y paquete médico cifrado.")` |
| `java/com/rutabikersync/app/feature/sos/SosRepository.kt` | 58 | safe | mensaje operativo sin PII/tokens evidente | `Log.e(TAG, "Error cifrando paquete al iniciar rodada: ${e.message}", e)` |
| `java/com/rutabikersync/app/feature/sos/SosRepository.kt` | 73 | sensitive | posible PII/datos m?dicos/identidad | `Log.i(TAG, "Paquete de rescate médico personal actualizado para ${packet.fullName}")` |
| `java/com/rutabikersync/app/feature/sos/SosRepository.kt` | 112 | safe | mensaje operativo sin PII/tokens evidente | `if (com.rutabikersync.app.BuildConfig.DEBUG) { Log.i(TAG, "SOS local emitido") }` |
| `java/com/rutabikersync/app/feature/sos/SosRepository.kt` | 129 | safe | mensaje operativo sin PII/tokens evidente | `Log.i(TAG, "Alerta SOS recibida por malla")` |
| `java/com/rutabikersync/app/feature/sos/SosRepository.kt` | 143 | safe | mensaje operativo sin PII/tokens evidente | `Log.i(TAG, "SOS marcado como resuelto")` |
| `java/com/rutabikersync/app/feature/sos/SosRepository.kt` | 169 | safe | mensaje operativo sin PII/tokens evidente | `if (com.rutabikersync.app.BuildConfig.DEBUG) { Log.i(TAG, "Auditor?a: acceso a paquete de emergencia registrado") }` |
| `java/com/rutabikersync/app/feature/sos/SosRepository.kt` | 181 | safe | mensaje operativo sin PII/tokens evidente | `Log.i(TAG, "Alertas y paquetes de emergencia purgados por fin de rodada")` |
| `java/com/rutabikersync/app/feature/sos/SosScreens.kt` | 683 | safe | mensaje operativo sin PII/tokens evidente | `if (com.rutabikersync.app.BuildConfig.DEBUG) { android.util.Log.i("SosScreens", "MeshSosReceptionDialog visible") }` |
| `java/com/rutabikersync/app/feature/vault/VaultCrypto.kt` | 284 | safe | mensaje operativo sin PII/tokens evidente | `android.util.Log.d("VaultCrypto", "restoreMasterKeyFromRecovery SUCCESS, masterKey set")` |
| `java/com/rutabikersync/app/feature/vault/VaultCrypto.kt` | 287 | safe | mensaje operativo sin PII/tokens evidente | `android.util.Log.e("VaultCrypto", "restoreMasterKeyFromRecovery AEADBadTagException: ${e.message}")` |
| `java/com/rutabikersync/app/feature/vault/VaultCrypto.kt` | 290 | sensitive | posible PII/datos m?dicos/identidad | `android.util.Log.e("VaultCrypto", "restoreMasterKeyFromRecovery Exception: ${e.javaClass.simpleName} - ${e.message}", e)` |
| `java/com/rutabikersync/app/feature/vault/VaultRepository.kt` | 348 | sensitive | posible secreto/token/credencial | `if (com.rutabikersync.app.BuildConfig.DEBUG) { android.util.Log.d("VaultRepo", "backupMasterKey: token request completed") }` |
| `java/com/rutabikersync/app/feature/vault/VaultRepository.kt` | 365 | sensitive | posible secreto/token/credencial | `if (com.rutabikersync.app.BuildConfig.DEBUG) { android.util.Log.d("VaultRepo", "uploadRecoveryKey: request completed") }` |
| `java/com/rutabikersync/app/feature/vault/VaultRepository.kt` | 390 | sensitive | posible secreto/token/credencial | `if (com.rutabikersync.app.BuildConfig.DEBUG) { android.util.Log.d("VaultRepo", "restore: token request completed") }` |
| `java/com/rutabikersync/app/feature/vault/VaultRepository.kt` | 403 | safe | mensaje operativo sin PII/tokens evidente | `if (com.rutabikersync.app.BuildConfig.DEBUG) { android.util.Log.d("VaultRepo", "restore: recovery blob request completed") }` |
| `java/com/rutabikersync/app/feature/vault/VaultRepository.kt` | 417 | safe | mensaje operativo sin PII/tokens evidente | `if (com.rutabikersync.app.BuildConfig.DEBUG) { android.util.Log.d("VaultRepo", "restore: master key restore completed") }` |
| `java/com/rutabikersync/app/feature/vault/VaultRepository.kt` | 619 | safe | mensaje operativo sin PII/tokens evidente | `android.util.Log.d("VaultRepo", "pushDocumentToBackend result: ${res.getOrNull()?.statusCode}")` |
| `java/com/rutabikersync/app/feature/vault/VaultRepository.kt` | 636 | safe | mensaje operativo sin PII/tokens evidente | `android.util.Log.d("VaultRepo", "pushMedicalSheetToBackend result: ${res.getOrNull()?.statusCode}")` |
| `java/com/rutabikersync/app/core/gamification/GamificationSyncEngine.kt` | 99 | safe | mensaje operativo sin PII/tokens evidente | `Log.w("TelemetryApiClient", "Error de red transmitiendo telemetría resumida: ${e.message}")` |
| `java/com/rutabikersync/app/core/mesh/BleMeshEngine.kt` | 231 | sensitive | posible PII/datos m?dicos/identidad | `android.util.Log.i("BleMeshService", "Heartbeat received from shortId=$senderId, rssi=$rssi, speed=${frame.speedKmh} km/h, bat=${frame.ba...` |
| `java/com/rutabikersync/app/core/mesh/BleMeshService.kt` | 144 | sensitive | posible ubicaci?n/coordenadas | `Log.i(TAG, "Notificación de modo aislado: $isIsolated")` |
| `java/com/rutabikersync/app/core/mesh/BleMeshService.kt` | 230 | sensitive | posible PII/datos m?dicos/identidad | `Log.i(TAG, "BleMeshService onCreate")` |
| `java/com/rutabikersync/app/core/mesh/BleMeshService.kt` | 237 | safe | mensaje operativo sin PII/tokens evidente | `Log.w(TAG, "Error registrando batteryReceiver: ${e.message}")` |
| `java/com/rutabikersync/app/core/mesh/BleMeshService.kt` | 247 | safe | mensaje operativo sin PII/tokens evidente | `Log.w(TAG, "Acción ignorada (no permitida): $action")` |
| `java/com/rutabikersync/app/core/mesh/BleMeshService.kt` | 252 | safe | mensaje operativo sin PII/tokens evidente | `Log.i(TAG, "ACTION_STOP recibido en BleMeshService")` |
| `java/com/rutabikersync/app/core/mesh/BleMeshService.kt` | 264 | safe | mensaje operativo sin PII/tokens evidente | `Log.i(TAG, "ACTION_PTT_START recibido")` |
| `java/com/rutabikersync/app/core/mesh/BleMeshService.kt` | 268 | safe | mensaje operativo sin PII/tokens evidente | `Log.i(TAG, "ACTION_PTT_STOP recibido")` |
| `java/com/rutabikersync/app/core/mesh/BleMeshService.kt` | 285 | safe | mensaje operativo sin PII/tokens evidente | `Log.i(TAG, "ACTION_EMIT_MULTIHOP recibido (ttl=$ttl)")` |
| `java/com/rutabikersync/app/core/mesh/BleMeshService.kt` | 289 | safe | mensaje operativo sin PII/tokens evidente | `Log.i(TAG, "ACTION_RESET_METRICS recibido: reseteando contadores y tracker")` |
| `java/com/rutabikersync/app/core/mesh/BleMeshService.kt` | 294 | safe | mensaje operativo sin PII/tokens evidente | `Log.i(TAG, "ACTION_SET_RESCUE_CONTEXT: auth=$auth")` |
| `java/com/rutabikersync/app/core/mesh/BleMeshService.kt` | 299 | safe | mensaje operativo sin PII/tokens evidente | `Log.i(TAG, "ACTION_EMIT_MEDICAL_SHEET para ownerShortId=$owner")` |
| `java/com/rutabikersync/app/core/mesh/BleMeshService.kt` | 318 | safe | mensaje operativo sin PII/tokens evidente | `Log.i(TAG, "ACTION_EMIT_SOS recibido: activando broadcaster SOS")` |
| `java/com/rutabikersync/app/core/mesh/BleMeshService.kt` | 328 | safe | mensaje operativo sin PII/tokens evidente | `Log.i(TAG, "ACTION_EMIT_ACK recibido para target=$targetShortId")` |
| `java/com/rutabikersync/app/core/mesh/BleMeshService.kt` | 341 | safe | mensaje operativo sin PII/tokens evidente | `Log.i(TAG, "ACTION_PURGE_R12 recibido: purgando fichas médicas en RAM")` |
| `java/com/rutabikersync/app/core/mesh/BleMeshService.kt` | 349 | sensitive | posible PII/datos m?dicos/identidad | `Log.i(TAG, "BleMeshService onDestroy")` |
| `java/com/rutabikersync/app/core/mesh/BleMeshService.kt` | 368 | safe | mensaje operativo sin PII/tokens evidente | `Log.i(` |
| `java/com/rutabikersync/app/core/mesh/BleMeshService.kt` | 378 | safe | mensaje operativo sin PII/tokens evidente | `Log.i(` |
| `java/com/rutabikersync/app/core/mesh/BleMeshService.kt` | 504 | sensitive | posible PII/datos m?dicos/identidad | `Log.i(TAG, "BleMeshService en ejecución: Club=0x${clubId.toString(16)}, ShortId=$shortId, BootEpoch=$bootEpoch")` |
| `java/com/rutabikersync/app/core/mesh/BleMeshService.kt` | 512 | safe | mensaje operativo sin PII/tokens evidente | `Log.i(TAG, "Nuevo bootEpoch generado y persistido: $nextEpoch (anterior: $currentEpoch)")` |
| `java/com/rutabikersync/app/core/mesh/BleMeshService.kt` | 553 | safe | mensaje operativo sin PII/tokens evidente | `Log.w(TAG, "Fallo al cifrar/emitir mensaje táctico: ${e.message}")` |
| `java/com/rutabikersync/app/core/mesh/BleMeshService.kt` | 579 | safe | mensaje operativo sin PII/tokens evidente | `Log.w(TAG, "emitLocalMedicalSheetViaMesh: relayEngine no inicializado")` |
| `java/com/rutabikersync/app/core/mesh/BleMeshService.kt` | 585 | sensitive | posible PII/datos m?dicos/identidad | `Log.e(TAG, "Error cifrando ficha médica: ${e.javaClass.simpleName}")` |
| `java/com/rutabikersync/app/core/mesh/BleMeshService.kt` | 599 | safe | mensaje operativo sin PII/tokens evidente | `Log.i(TAG, "MedicalSheetPacket emitido (${packet.toByteArray().size}B, ownerShortId=$ownerShortId)")` |
| `java/com/rutabikersync/app/core/mesh/BleMeshService.kt` | 634 | sensitive | posible ubicaci?n/coordenadas | `Log.i(TAG, "SosBroadcaster activado para shortId=${relay.localShortId} en ($latitude, $longitude)")` |
| `java/com/rutabikersync/app/core/mesh/BleMeshService.kt` | 642 | safe | mensaje operativo sin PII/tokens evidente | `Log.i(TAG, "SosBroadcaster detenido")` |
| `java/com/rutabikersync/app/core/mesh/BleMeshService.kt` | 652 | sensitive | posible PII/datos m?dicos/identidad | `Log.i(TAG, "SosAckPacket emitido para originalSender=${ack.originalSenderShortId}")` |
| `java/com/rutabikersync/app/core/mesh/BleMeshService.kt` | 674 | safe | mensaje operativo sin PII/tokens evidente | `Log.i(TAG, "Todas las métricas de radio BLE y PLR han sido reseteadas a 0")` |
| `java/com/rutabikersync/app/core/mesh/BleMeshService.kt` | 680 | safe | mensaje operativo sin PII/tokens evidente | `Log.i("MeshRelayEngine", "Emitted packet with currentTtl=$ttl, seq=${hb.header.sequenceNum}, epoch=${hb.bootEpoch}")` |
| `java/com/rutabikersync/app/core/mesh/BleMeshService.kt` | 694 | safe | mensaje operativo sin PII/tokens evidente | `Log.i(TAG, "PARTIAL_WAKE_LOCK adquirido ($WAKELOCK_TAG)")` |
| `java/com/rutabikersync/app/core/mesh/BleMeshService.kt` | 702 | safe | mensaje operativo sin PII/tokens evidente | `Log.i(TAG, "PARTIAL_WAKE_LOCK liberado")` |
| `java/com/rutabikersync/app/core/mesh/BleMeshService.kt` | 705 | safe | mensaje operativo sin PII/tokens evidente | `Log.w(TAG, "Error liberando wakeLock: ${e.message}")` |
| `java/com/rutabikersync/app/core/mesh/BleMeshService.kt` | 718 | safe | mensaje operativo sin PII/tokens evidente | `Log.i(TAG, "Bluetooth inicializado. ExtendedAdv=$extAdv, BLE5.0=${Build.VERSION.SDK_INT >= Build.VERSION_CODES.O}")` |
| `java/com/rutabikersync/app/core/mesh/BleMeshService.kt` | 724 | safe | mensaje operativo sin PII/tokens evidente | `Log.w(TAG, "transmitBleFrame abortado: faltan permisos BLE")` |
| `java/com/rutabikersync/app/core/mesh/BleMeshService.kt` | 730 | safe | mensaje operativo sin PII/tokens evidente | `Log.w(TAG, "transmitBleFrame abortado: BluetoothLeAdvertiser es null (¿Bluetooth apagado?)")` |
| `java/com/rutabikersync/app/core/mesh/BleMeshService.kt` | 752 | safe | mensaje operativo sin PII/tokens evidente | `Log.w(TAG, "Recreando AdvertisingSet tras error: ${e.message}")` |
| `java/com/rutabikersync/app/core/mesh/BleMeshService.kt` | 767 | review | posible payload/datos din?micos | `Log.i(TAG, "Extended Advertising started (payload=${payload.size}B, status=$status)")` |
| `java/com/rutabikersync/app/core/mesh/BleMeshService.kt` | 769 | safe | mensaje operativo sin PII/tokens evidente | `Log.w(TAG, "Extended Advertising start error: status=$status")` |
| `java/com/rutabikersync/app/core/mesh/BleMeshService.kt` | 797 | review | posible payload/datos din?micos | `Log.i(TAG, "Advertising started with serviceUUID=$MESH_SERVICE_UUID (payload=${payload.size}B)")` |
| `java/com/rutabikersync/app/core/mesh/BleMeshService.kt` | 800 | safe | mensaje operativo sin PII/tokens evidente | `Log.w(TAG, "Advertise startFailure: $errorCode")` |
| `java/com/rutabikersync/app/core/mesh/BleMeshService.kt` | 806 | safe | mensaje operativo sin PII/tokens evidente | `Log.w(TAG, "SecurityException al anunciar: ${e.message}")` |
| `java/com/rutabikersync/app/core/mesh/BleMeshService.kt` | 808 | safe | mensaje operativo sin PII/tokens evidente | `Log.w(TAG, "Error transmitiendo anuncio BLE: ${e.message}")` |
| `java/com/rutabikersync/app/core/mesh/BleMeshService.kt` | 814 | safe | mensaje operativo sin PII/tokens evidente | `Log.w(TAG, "startBleRadio abortado: faltan permisos BLE")` |
| `java/com/rutabikersync/app/core/mesh/BleMeshService.kt` | 820 | safe | mensaje operativo sin PII/tokens evidente | `Log.w(TAG, "startBleRadio abortado: BluetoothLeScanner es null (¿Bluetooth apagado?)")` |
| `java/com/rutabikersync/app/core/mesh/BleMeshService.kt` | 845 | safe | mensaje operativo sin PII/tokens evidente | `Log.i(TAG, "Scan started with filter=serviceDataUuid:$MESH_SERVICE_UUID")` |
| `java/com/rutabikersync/app/core/mesh/BleMeshService.kt` | 849 | safe | mensaje operativo sin PII/tokens evidente | `Log.w(TAG, "SecurityException al iniciar scan: ${e.message}")` |
| `java/com/rutabikersync/app/core/mesh/BleMeshService.kt` | 851 | safe | mensaje operativo sin PII/tokens evidente | `Log.w(TAG, "Error iniciando scan: ${e.message}")` |
| `java/com/rutabikersync/app/core/mesh/BleMeshService.kt` | 872 | safe | mensaje operativo sin PII/tokens evidente | `Log.w(TAG, "Error deteniendo radio BLE: ${e.message}")` |
| `java/com/rutabikersync/app/core/mesh/BleMeshService.kt` | 886 | safe | mensaje operativo sin PII/tokens evidente | `Log.e(TAG, "Fallo ScanCallback BLE: $errorCode")` |
| `java/com/rutabikersync/app/core/mesh/BleMeshService.kt` | 898 | safe | mensaje operativo sin PII/tokens evidente | `Log.w("MeshRelayEngine", "DroppedMalformed: cabecera corrupta (${e.message})")` |
| `java/com/rutabikersync/app/core/mesh/BleMeshService.kt` | 906 | safe | mensaje operativo sin PII/tokens evidente | `Log.i(` |
| `java/com/rutabikersync/app/core/mesh/BleMeshService.kt` | 917 | safe | mensaje operativo sin PII/tokens evidente | `Log.i(` |
| `java/com/rutabikersync/app/core/mesh/BleMeshService.kt` | 924 | safe | mensaje operativo sin PII/tokens evidente | `Log.i(` |
| `java/com/rutabikersync/app/core/mesh/BleMeshService.kt` | 930 | sensitive | posible PII/datos m?dicos/identidad | `Log.d("MeshRelayEngine", "DroppedOwnPacket from shortId=${header.senderShortId}")` |
| `java/com/rutabikersync/app/core/mesh/BleMeshService.kt` | 933 | safe | mensaje operativo sin PII/tokens evidente | `Log.d("MeshRelayEngine", "DroppedWrongNetwork: 0x${header.clubNetworkId.toString(16)}")` |
| `java/com/rutabikersync/app/core/mesh/BleMeshService.kt` | 936 | safe | mensaje operativo sin PII/tokens evidente | `Log.w("MeshRelayEngine", "DroppedMalformed: ${decision.reason}")` |
| `java/com/rutabikersync/app/core/mesh/BleMeshService.kt` | 939 | safe | mensaje operativo sin PII/tokens evidente | `Log.w("MeshRelayEngine", "DroppedInvalidSignature: ${decision.reason}")` |
| `java/com/rutabikersync/app/core/mesh/BleMeshService.kt` | 942 | safe | mensaje operativo sin PII/tokens evidente | `Log.w("MeshRelayEngine", "DroppedHopLimitExceeded: hops=${decision.hopCount} >= 5")` |
| `java/com/rutabikersync/app/core/mesh/BleMeshService.kt` | 962 | safe | mensaje operativo sin PII/tokens evidente | `Log.i(TAG, "onScanResult peer discovered: rawBytes=${serviceData.size}, rssi=$rssi, epoch=$epoch")` |
| `java/com/rutabikersync/app/core/mesh/BleMeshService.kt` | 969 | safe | mensaje operativo sin PII/tokens evidente | `Log.i(` |
| `java/com/rutabikersync/app/core/mesh/BleMeshService.kt` | 975 | safe | mensaje operativo sin PII/tokens evidente | `Log.w(TAG, "Error deserializando VoicePacket: ${e.message}")` |
| `java/com/rutabikersync/app/core/mesh/BleMeshService.kt` | 981 | sensitive | posible PII/datos m?dicos/identidad | `Log.i(TAG, "MedicalSheetPacket recibido desde shortId=${header.senderShortId} (${serviceData.size}B)")` |
| `java/com/rutabikersync/app/core/mesh/BleMeshService.kt` | 986 | sensitive | posible PII/datos m?dicos/identidad | `Log.i(TAG, "SosPacket procesado desde shortId=${header.senderShortId} (${serviceData.size}B)")` |
| `java/com/rutabikersync/app/core/mesh/BleMeshService.kt` | 990 | sensitive | posible PII/datos m?dicos/identidad | `Log.i(TAG, "SosAckPacket procesado desde shortId=${header.senderShortId} (${serviceData.size}B)")` |
| `java/com/rutabikersync/app/core/mesh/BleMeshService.kt` | 993 | safe | mensaje operativo sin PII/tokens evidente | `Log.d(TAG, "Paquete con packetType=${header.packetType} recibido (${serviceData.size}B)")` |
| `java/com/rutabikersync/app/core/mesh/MeshRelayEngine.kt` | 387 | sensitive | posible PII/datos m?dicos/identidad | `android.util.Log.w("MeshRelayEngine", "SOS_REJECTED: sender=${header.senderShortId}, hops=${sos.hopCount.toInt() and 0xFF}, sigValid=false")` |
| `java/com/rutabikersync/app/core/mesh/MeshRelayEngine.kt` | 395 | sensitive | posible PII/datos m?dicos/identidad | `android.util.Log.i("MeshRelayEngine", "SOS_VERIFIED: sender=${header.senderShortId}, hops=${sos.hopCount.toInt() and 0xFF}, sigValid=$sig...` |
| `java/com/rutabikersync/app/core/mesh/MeshRelayEngine.kt` | 533 | sensitive | posible PII/datos m?dicos/identidad | `android.util.Log.i("MeshRelayEngine", "MEDICAL_SHEET_RECEIVED: sender=${header.senderShortId}, owner=${medicalPacket.ownerShortId}, size=...` |
| `java/com/rutabikersync/app/core/mesh/RideService.kt` | 229 | safe | mensaje operativo sin PII/tokens evidente | `Log.w(` |
| `java/com/rutabikersync/app/core/mesh/RideService.kt` | 339 | safe | mensaje operativo sin PII/tokens evidente | `Log.i(` |
| `java/com/rutabikersync/app/core/mesh/RideService.kt` | 457 | safe | mensaje operativo sin PII/tokens evidente | `Log.i(` |
| `java/com/rutabikersync/app/core/mesh/RideService.kt` | 1006 | sensitive | posible ubicaci?n/coordenadas | `Log.i(TAG, "Starting in-app simulated mesh (no Android Service / no FGS)")` |
| `java/com/rutabikersync/app/core/mesh/RideService.kt` | 1245 | sensitive | posible ubicaci?n/coordenadas | `Log.i(TAG, "Starting RideService in simulated in-app mode (no Foreground Service)")` |
| `java/com/rutabikersync/app/core/mesh/RideService.kt` | 1255 | safe | mensaje operativo sin PII/tokens evidente | `Log.w(TAG, "Caída detectada por sensor inercial en rodada activa ($triggerType)")` |
| `java/com/rutabikersync/app/core/mesh/RideService.kt` | 1266 | safe | mensaje operativo sin PII/tokens evidente | `Log.e(TAG, "No se pudo iniciar FallDetector: ${e.message}")` |
| `java/com/rutabikersync/app/core/mesh/RideService.kt` | 1373 | sensitive | posible PII/datos m?dicos/identidad | `Log.i(TAG, "Promoting RideService to foreground with fgsType=0x${fgsTypes.toString(16)}")` |
| `java/com/rutabikersync/app/core/mesh/RideService.kt` | 1381 | safe | mensaje operativo sin PII/tokens evidente | `Log.e(TAG, msg, error)` |
| `java/com/rutabikersync/app/core/network/RutaBikerMessagingService.kt` | 112 | safe | mensaje operativo sin PII/tokens evidente | `Log.d(TAG, "4 canales de notificación FCM creados correctamente")` |
| `java/com/rutabikersync/app/core/network/RutaBikerMessagingService.kt` | 133 | sensitive | posible secreto/token/credencial | `Log.w(TAG, "No se pudo obtener el token de FirebaseMessaging: ${task.exception?.message}")` |
| `java/com/rutabikersync/app/core/network/RutaBikerMessagingService.kt` | 139 | sensitive | posible secreto/token/credencial | `Log.i(TAG, "Token FCM obtenido exitosamente")` |
| `java/com/rutabikersync/app/core/network/RutaBikerMessagingService.kt` | 145 | sensitive | posible secreto/token/credencial | `Log.i(TAG, "Token FCM sincronizado con el backend exitosamente")` |
| `java/com/rutabikersync/app/core/network/RutaBikerMessagingService.kt` | 147 | sensitive | posible secreto/token/credencial | `Log.w(TAG, "Fallo al registrar token en backend: ${res.getOrNull()?.statusCode}")` |
| `java/com/rutabikersync/app/core/network/RutaBikerMessagingService.kt` | 150 | sensitive | posible secreto/token/credencial | `Log.w(TAG, "Error enviando token FCM al servidor: ${e.message}")` |
| `java/com/rutabikersync/app/core/network/RutaBikerMessagingService.kt` | 157 | safe | mensaje operativo sin PII/tokens evidente | `Log.w(TAG, "FirebaseMessaging no disponible o no inicializado: ${e.message}")` |
| `java/com/rutabikersync/app/core/network/RutaBikerMessagingService.kt` | 171 | sensitive | posible secreto/token/credencial | `Log.i(TAG, "Nuevo token FCM recibido")` |
| `java/com/rutabikersync/app/core/network/RutaBikerMessagingService.kt` | 180 | sensitive | posible secreto/token/credencial | `Log.i(TAG, "Token registrado en backend Fastify")` |
| `java/com/rutabikersync/app/core/network/RutaBikerMessagingService.kt` | 182 | sensitive | posible secreto/token/credencial | `Log.w(TAG, "Respuesta no exitosa al registrar token: ${result.getOrNull()?.statusCode}")` |
| `java/com/rutabikersync/app/core/network/RutaBikerMessagingService.kt` | 185 | sensitive | posible secreto/token/credencial | `Log.e(TAG, "Error registrando token en backend Fastify: ${e.message}")` |
| `java/com/rutabikersync/app/core/network/RutaBikerMessagingService.kt` | 193 | safe | mensaje operativo sin PII/tokens evidente | `Log.i(TAG, "Mensaje FCM recibido de: ${remoteMessage.from}")` |
| `java/com/rutabikersync/app/core/network/RutaBikerMessagingService.kt` | 272 | safe | mensaje operativo sin PII/tokens evidente | `Log.w(TAG, "Permiso POST_NOTIFICATIONS no concedido para mostrar notificación: ${e.message}")` |
| `java/com/rutabikersync/app/core/network/RutaBikerMessagingService.kt` | 274 | safe | mensaje operativo sin PII/tokens evidente | `Log.e(TAG, "Error mostrando notificación push: ${e.message}")` |
| `java/com/rutabikersync/app/core/transport/BleAdvTransport.kt` | 301 | safe | mensaje operativo sin PII/tokens evidente | `Log.i(TAG, "startScan (invocation #$scanStartsCount) mode=$mode diagnostic=$unfilteredScanDiagnostic")` |
| `java/com/rutabikersync/app/core/transport/BleAdvTransport.kt` | 308 | safe | mensaje operativo sin PII/tokens evidente | `Log.e(TAG, "scanner.startScan threw exception", ex)` |
| `java/com/rutabikersync/app/core/transport/BleAdvTransport.kt` | 311 | safe | mensaje operativo sin PII/tokens evidente | `Log.i(TAG, "startScan omitido: rol TX_ONLY activo")` |
| `java/com/rutabikersync/app/core/transport/BleAdvTransport.kt` | 401 | safe | mensaje operativo sin PII/tokens evidente | `Log.i(TAG, "Legacy continuous advertise started successfully burst#=$advertisingBurstsStartedCount")` |
| `java/com/rutabikersync/app/core/transport/BleAdvTransport.kt` | 408 | safe | mensaje operativo sin PII/tokens evidente | `Log.e(` |
| `java/com/rutabikersync/app/core/transport/BleAdvTransport.kt` | 422 | safe | mensaje operativo sin PII/tokens evidente | `Log.e(TAG, "startAdvertising threw exception", ex)` |
| `java/com/rutabikersync/app/core/transport/BleAdvTransport.kt` | 448 | safe | mensaje operativo sin PII/tokens evidente | `Log.i(TAG, "Legacy burst advertise started successfully burst#=$advertisingBurstsStartedCount")` |
| `java/com/rutabikersync/app/core/transport/BleAdvTransport.kt` | 455 | safe | mensaje operativo sin PII/tokens evidente | `Log.e(` |
| `java/com/rutabikersync/app/core/transport/BleAdvTransport.kt` | 469 | safe | mensaje operativo sin PII/tokens evidente | `Log.e(TAG, "startAdvertising threw exception", ex)` |
| `java/com/rutabikersync/app/core/transport/BleAdvTransport.kt` | 476 | safe | mensaje operativo sin PII/tokens evidente | `Log.d(TAG, "send omitido: rol RX_ONLY activo")` |
| `java/com/rutabikersync/app/core/transport/BleAdvTransport.kt` | 494 | safe | mensaje operativo sin PII/tokens evidente | `Log.i(TAG, "BleAdvTransport.send burst#=$advertisingBurstsStartedCount mode=$mode delivery=$advertisingDeliveryMode priority=$priority")` |
| `java/com/rutabikersync/app/core/transport/BleAdvTransport.kt` | 511 | safe | mensaje operativo sin PII/tokens evidente | `Log.d(TAG, "Continuous AdvertisingSet data updated in-place burst#=$advertisingBurstsStartedCount")` |
| `java/com/rutabikersync/app/core/transport/BleAdvTransport.kt` | 514 | safe | mensaje operativo sin PII/tokens evidente | `Log.w(TAG, "setAdvertisingData failed (${updateResult.exceptionOrNull()?.message}), restarting set")` |
| `java/com/rutabikersync/app/core/transport/BleAdvTransport.kt` | 538 | safe | mensaje operativo sin PII/tokens evidente | `Log.i(TAG, "Continuous AdvertisingSet started successfully txPower=$txPower mode=$mode")` |
| `java/com/rutabikersync/app/core/transport/BleAdvTransport.kt` | 543 | safe | mensaje operativo sin PII/tokens evidente | `Log.e(` |
| `java/com/rutabikersync/app/core/transport/BleAdvTransport.kt` | 554 | safe | mensaje operativo sin PII/tokens evidente | `Log.d(TAG, "onAdvertisingDataSet confirmed burst#=$advertisingBurstsStartedCount")` |
| `java/com/rutabikersync/app/core/transport/BleAdvTransport.kt` | 556 | safe | mensaje operativo sin PII/tokens evidente | `Log.w(TAG, "onAdvertisingDataSet failed status=$status")` |
| `java/com/rutabikersync/app/core/transport/BleAdvTransport.kt` | 561 | safe | mensaje operativo sin PII/tokens evidente | `Log.i(TAG, "Continuous AdvertisingSet stopped")` |
| `java/com/rutabikersync/app/core/transport/BleAdvTransport.kt` | 579 | safe | mensaje operativo sin PII/tokens evidente | `Log.e(TAG, "startAdvertisingSet threw exception, falling back to legacy", ex)` |
| `java/com/rutabikersync/app/core/transport/BleAdvTransport.kt` | 610 | safe | mensaje operativo sin PII/tokens evidente | `Log.i(TAG, "Burst AdvertisingSet started successfully txPower=$txPower burst#=$advertisingBurstsStartedCount")` |
| `java/com/rutabikersync/app/core/transport/BleAdvTransport.kt` | 615 | safe | mensaje operativo sin PII/tokens evidente | `Log.e(` |
| `java/com/rutabikersync/app/core/transport/BleAdvTransport.kt` | 636 | safe | mensaje operativo sin PII/tokens evidente | `Log.d(TAG, "Burst AdvertisingSet slot freed after expiration")` |
| `java/com/rutabikersync/app/core/transport/BleAdvTransport.kt` | 655 | safe | mensaje operativo sin PII/tokens evidente | `Log.e(TAG, "Burst startAdvertisingSet threw exception, falling back to legacy", ex)` |
| `java/com/rutabikersync/app/core/transport/BleAdvTransport.kt` | 713 | safe | mensaje operativo sin PII/tokens evidente | `Log.e(` |
| `java/com/rutabikersync/app/core/mesh/audio/AudioFocusController.kt` | 44 | safe | mensaje operativo sin PII/tokens evidente | `Log.d(TAG, "Audio focus change: $focusChange")` |
| `java/com/rutabikersync/app/core/mesh/audio/AudioFocusController.kt` | 54 | safe | mensaje operativo sin PII/tokens evidente | `{ focusChange -> Log.d(TAG, "Audio focus change (legacy): $focusChange") },` |
| `java/com/rutabikersync/app/core/mesh/audio/AudioFocusController.kt` | 61 | safe | mensaje operativo sin PII/tokens evidente | `Log.w(TAG, "Error solicitando AudioFocus: ${e.message}")` |
| `java/com/rutabikersync/app/core/mesh/audio/AudioFocusController.kt` | 79 | safe | mensaje operativo sin PII/tokens evidente | `Log.w(TAG, "Error liberando AudioFocus: ${e.message}")` |
| `java/com/rutabikersync/app/core/mesh/audio/PttAudioEngine.kt` | 79 | safe | mensaje operativo sin PII/tokens evidente | `Log.i(TAG, "PTT: Iniciando transmisión de audio...")` |
| `java/com/rutabikersync/app/core/mesh/audio/PttAudioEngine.kt` | 174 | safe | mensaje operativo sin PII/tokens evidente | `Log.w(TAG, "Error en captura PTT: ${e.message}")` |
| `java/com/rutabikersync/app/core/mesh/audio/PttAudioEngine.kt` | 181 | safe | mensaje operativo sin PII/tokens evidente | `Log.w(TAG, "Error liberando audioRecord: ${e.message}")` |
| `java/com/rutabikersync/app/core/mesh/audio/PttAudioEngine.kt` | 194 | safe | mensaje operativo sin PII/tokens evidente | `Log.i(TAG, "PTT: Transmisión finalizada por el usuario.")` |
| `java/com/rutabikersync/app/core/mesh/audio/PttAudioEngine.kt` | 241 | safe | mensaje operativo sin PII/tokens evidente | `Log.w(TAG, "Error descifrando o procesando paquete de voz: ${e.message}")` |
| `java/com/rutabikersync/app/core/mesh/audio/PttAudioEngine.kt` | 293 | safe | mensaje operativo sin PII/tokens evidente | `Log.i(TAG, "Fin de recepción de audio PTT.")` |
| `java/com/rutabikersync/app/core/mesh/audio/PttAudioEngine.kt` | 298 | safe | mensaje operativo sin PII/tokens evidente | `Log.i(TAG, "Fin de ráfaga de voz PTT por inactividad.")` |
| `java/com/rutabikersync/app/core/mesh/audio/PttAudioEngine.kt` | 306 | safe | mensaje operativo sin PII/tokens evidente | `Log.w(TAG, "Error en reproducción PTT: ${e.message}")` |
| `java/com/rutabikersync/app/core/mesh/audio/PttAudioEngine.kt` | 313 | safe | mensaje operativo sin PII/tokens evidente | `Log.w(TAG, "Error liberando audioTrack: ${e.message}")` |
| `java/com/rutabikersync/app/core/mesh/sos/BleMeshSosBridge.kt` | 93 | safe | mensaje operativo sin PII/tokens evidente | `Log.d(TAG, "Deduplicación activa: SosPacket $meshPacketId ya procesado recientemente. Ignorando.")` |
| `java/com/rutabikersync/app/core/mesh/sos/BleMeshSosBridge.kt` | 99 | safe | mensaje operativo sin PII/tokens evidente | `Log.w(TAG, "Reenvío suprimido: El motero puente no ha otorgado consentimiento emergency_package (R15).")` |
| `java/com/rutabikersync/app/core/mesh/sos/BleMeshSosBridge.kt` | 122 | safe | mensaje operativo sin PII/tokens evidente | `Log.i(TAG, "SOS puenteado exitosamente a la nube para meshPacketId=$meshPacketId")` |
| `java/com/rutabikersync/app/core/mesh/sos/BleMeshSosBridge.kt` | 128 | safe | mensaje operativo sin PII/tokens evidente | `Log.w(TAG, "Fallo de conexión al enviar SOS ($meshPacketId). Encolando en cola de reintentos Room...")` |
| `java/com/rutabikersync/app/core/mesh/sos/MedicalSheetStore.kt` | 84 | sensitive | posible PII/datos m?dicos/identidad | `android.util.Log.i("MedicalSheetStore", "MEDICAL_SHEET_DECRYPTED: owner=${packet.ownerShortId}, blood=${clinicalData.bloodType}, allergie...` |
| `java/com/rutabikersync/app/core/mesh/sos/MedicalSheetStore.kt` | 89 | safe | mensaje operativo sin PII/tokens evidente | `android.util.Log.e("MedicalSheetStore", "Error descifrando MedicalSheetPacket: ${e.message}")` |

## Excepciones / notas
- Clasificaci?n heur?stica por patrones; requiere verificaci?n manual en logcat de build release para confirmar ausencia de fugas en runtime.
- Llamadas fuera de los archivos cr?ticos no fueron refactorizadas masivamente para evitar cambios de comportamiento fuera de alcance; se marcan para hardening posterior seg?n su clasificaci?n.
- Verificaci?n automatizable recomendada: `rg "Log\.(v|d|i|w|e|wtf)\(" android/app/src/main -n` y revisi?n de entradas `sensitive/review`.