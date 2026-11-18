# Ruta Biker Sync

Plataforma integral para motoclubes y pilotos con mesh Bluetooth local, mapa offline, gestión de clubes, bóveda digital y sincronización backend con defensa en profundidad.

---

## 📁 Estructura del Proyecto

- `android/` — Aplicación nativa Android (Kotlin, Jetpack Compose, Clean Architecture, Material 3 + Heritage Elite Theme).
- `backend/` — Backend Fastify v5 + TypeScript + PostgreSQL 16 con defensa en profundidad de reglas de negocio (RULES.md).
- `infra/` — Infraestructura Docker, scripts de base de datos y despliegue.
- `docs/` — Documentación de arquitectura, reglas canónicas (`RULES.md`), checklist de fases (`CHECKLIST.md`) y bitácora técnica (`MEMORY.md`).
- `field-tests/` — Protocolo y métricas de pruebas de campo BLE (Fase 0).
- `tools/` — Utilidades auxiliares (enrutamiento, cartografía, scripts de automatización).

---

## 🚀 Guía Operativa de Desarrollo y Pruebas

### 1. Levantar PostgreSQL 16 (WSL2 Ubuntu)
En Windows con WSL2:
```bash
wsl -d Ubuntu -u root service postgresql start
```
*Verificar que PostgreSQL escuche en el puerto `5433` (o `5432` según configuración) con usuario `rbs` y base de datos `rbs`.*

### 2. Levantar el Backend Fastify v5
Desde WSL2 o terminal con acceso a la base de datos:
```bash
cd /mnt/d/Ruta-Biker-Sync/backend
PORT=3000 PG_TEST_URL=postgres://rbs:rbs_dev_password@localhost:5433/rbs node dist/index.js
```
*Verificar salud del servidor:*
```bash
curl http://localhost:3000/health
# Respuesta esperada: {"status":"ok"}
```

### 3. Conexión de Dispositivos Físicos Android (Reverse Port Forwarding)
Para que los dispositivos físicos alcancen el backend Fastify local vía cable USB / ADB:
```powershell
# TECNO Spark 10C:
adb -s 098813734B105395 reverse tcp:3000 tcp:3000

# Redmi Note 13 Pro:
adb -s b2c398bb reverse tcp:3000 tcp:3000
```
> **Nota Operativa:** Al reconectar el cable USB o reiniciar el daemon de ADB, el túnel se interrumpe y las peticiones al backend arrojarán `Failed to connect to /127.0.0.1:3000`. Ejecutar el script automatizado para restaurarlo en todos los dispositivos conectados:
> ```powershell
> .\scripts\setup-adb-reverse.ps1
> # En Linux / WSL:
> ./scripts/setup-adb-reverse.sh
> ```

### 4. Compilar y Ejecutar Tests en Android
```powershell
cd d:\Ruta-Biker-Sync\android

# Ejecutar batería de tests unitarios:
.\gradlew.bat testStandardDebugUnitTest

# Compilar e instalar APK debug en dispositivos:
.\gradlew.bat assembleStandardDebug
adb -s 098813734B105395 install -r app/build/outputs/apk/standard/debug/app-standard-debug.apk
adb -s b2c398bb install -r app/build/outputs/apk/standard/debug/app-standard-debug.apk
```

### 5. Comandos de Prueba y Diagnóstico de Malla BLE (Hito 3)
Comandos ADB útiles para forzar eventos y diagnosticar la red de malla multi-salto:

```powershell
# Forzar emisión de un paquete multi-salto con TTL específico (ej. TTL=5):
adb -s 098813734B105395 shell am start-foreground-service -a com.rutabikersync.app.mesh.ACTION_EMIT_MULTIHOP --ei extra_ttl 5 com.rutabikersync.app/.core.mesh.BleMeshService

# Resetear contadores de métricas direccionales y tabla LRU de deduplicación en caliente:
adb -s 098813734B105395 shell am start-foreground-service -a com.rutabikersync.app.mesh.ACTION_RESET_METRICS com.rutabikersync.app/.core.mesh.BleMeshService

# Monitorear decisiones de retransmisión, decremento de TTL y métricas de PLR en vivo:
adb -s b2c398bb logcat -s BleMeshService:D MeshRelayEngine:D PeerDirectionalTracker:D
```

### 6. Comandos de Diagnóstico de Emergencia SOS y Ficha Médica (Hito 4)
Comandos ADB para pruebas manuales y validación en radiofrecuencia de la capa de rescate:

```powershell
# 1. Configurar contexto de rescate médico en Líder (autorizado para descifrar AES-256-GCM):
adb -s b2c398bb shell am start-foreground-service -a com.rutabikersync.app.mesh.ACTION_SET_RESCUE_CONTEXT --ez extra_is_authorized true com.rutabikersync.app/.core.mesh.BleMeshService

# 2. Forzar emisión de Ficha Médica cifrada (228B) desde un nodo miembro (ej. Tecno):
adb -s 098813734B105395 shell am start-foreground-service -a com.rutabikersync.app.mesh.ACTION_EMIT_MEDICAL_SHEET com.rutabikersync.app/.core.mesh.BleMeshService

# 3. Disparar alerta SOS de emergencia prioritaria (88B, Ed25519) con broadcaster continuo:
adb -s 098813734B105395 shell am start-foreground-service -a com.rutabikersync.app.mesh.ACTION_EMIT_SOS com.rutabikersync.app/.core.mesh.BleMeshService

# 4. Responder con Acuse de Recibo (SosAckPacket, 83B) desde el Líder hacia el accidentado:
adb -s b2c398bb shell am start-foreground-service -a com.rutabikersync.app.mesh.ACTION_EMIT_ACK --ei extra_target_short_id 2 com.rutabikersync.app/.core.mesh.BleMeshService

# 5. Ejecutar Purga Criptográfica R12 en RAM al cerrar la rodada (sobrescritura con ceros):
adb -s b2c398bb shell am start-foreground-service -a com.rutabikersync.app.mesh.ACTION_PURGE_R12 com.rutabikersync.app/.core.mesh.BleMeshService

# Monitorear eventos de rescate, verificación Ed25519 y purga R12 en tiempo real:
adb -s b2c398bb logcat -s MeshRelayEngine:I MedicalSheetStore:I BleMeshService:I SosScreens:I
```

### 7. Comandos de Diagnóstico de Mensajería Off-Grid y Gamificación (Hito 5)
Comandos ADB para pruebas de mensajería táctica cifrada, Nodos Amigos, LPN y telemetría de odómetro:

```powershell
# 1. Enviar mensaje de texto broadcast cifrado (ChaCha20-Poly1305, <=120 caracteres UTF-8):
adb -s 098813734B105395 shell am start-foreground-service -a com.rutabikersync.app.mesh.ACTION_EMIT_TEXT_MESSAGE --ei extra_target_short_id 65535 --es extra_message_text "Parada tecnica en 2 km" com.rutabikersync.app/.core.mesh.BleMeshService

# 2. Enviar mensaje de texto directo (unicast) hacia un compañero específico (ej. shortId=2):
adb -s 098813734B105395 shell am start-foreground-service -a com.rutabikersync.app.mesh.ACTION_EMIT_TEXT_MESSAGE --ei extra_target_short_id 2 --es extra_message_text "Todo bien adelante" com.rutabikersync.app/.core.mesh.BleMeshService

# 3. Consultar estado y métricas del Odómetro de Malla y eventos de gamificación pendientes:
adb -s 098813734B105395 shell am start-foreground-service -a com.rutabikersync.app.mesh.ACTION_DUMP_ODOMETER com.rutabikersync.app/.core.mesh.BleMeshService

# 4. Forzar sincronización inmediata de la cola Room de telemetría hacia el backend:
adb -s 098813734B105395 shell am start-foreground-service -a com.rutabikersync.app.mesh.ACTION_FORCE_SYNC_TELEMETRY com.rutabikersync.app/.core.mesh.BleMeshService

# Monitorear eventos de texto cifrado, Friend Nodes, odómetro y reglas anti-cheat:
adb -s b2c398bb logcat -s MeshRelayEngine:I BleMeshService:I MeshOdometer:D GamificationSyncEngine:I AntiCheatValidator:I
```

---

## 🏛️ Gobernanza y Reglas de Negocio (RULES.md)

1. **R2.2 (Hermandad Única):** Un piloto solo puede pertenecer a un club motero (Full Patch / Prospecto) a la vez. La violación se previene localmente y es rechazada en servidor con `HTTP 409 Conflict (ERR_DUAL_MEMBERSHIP_PROHIBITED)`.
2. **R2.3 (Rodada Libre):** Un piloto puede participar libremente en rodadas organizadas por otros clubes como `guest` sin que esto modifique ni contamine su membresía en su club de origen.
3. **R14.3 (Protección del Fundador):** El líder principal (Fundador) no puede ser expulsado de su club bajo ninguna circunstancia.
4. **Doble Fuente de Miembros:** El Fundador reside en `groups.principal_leader` y el resto de los miembros en `memberships`. Toda consulta de miembros combina ambas fuentes (`UNION`).

---

Licencia: Código cerrado, todos los derechos reservados.
