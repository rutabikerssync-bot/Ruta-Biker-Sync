# SECURITY — Ruta Biker Sync

Versión 1.0 · 2026-10-31

Este documento describe el modelo de amenazas, la arquitectura criptográfica y los protocolos de seguridad implementados en **Ruta Biker Sync** para proteger a los motociclistas tanto en carretera como en reposo.

---

## 1. Política de Divulgación Responsable

La seguridad e integridad física de nuestros usuarios en carretera es la máxima prioridad. Si descubres una vulnerabilidad de seguridad en cualquier componente de Ruta Biker Sync (app Android, backend, red mesh BLE o esquemas de base de datos), te solicitamos reportarla de manera responsable:

- **Canal de reporte:** `security@rutabikersync.app`
- **Tiempo de respuesta inicial:** Menor a 48 horas.
- **Ventana de remediación coordinada:** Solicitamos un periodo de gracia de 30 días antes de cualquier divulgación pública para desplegar el parche correspondiente a los clubes y usuarios.
- **Compromiso:** No iniciaremos acciones legales contra investigadores que actúen de buena fe, respeten la privacidad de los usuarios y no interrumpan rodadas activas ni degraden el servicio.

---

## 2. Modelo de Amenazas en Carretera (Threat Model)

| Vector de Amenaza | Riesgo | Mitigación Implementada |
| :--- | :--- | :--- |
| **Inyección o falsificación de balizas BLE (Spoofing)** | Un atacante emite falsas alertas SOS o simula la posición de un rider en el convoy. | **Autenticación por paquete:** Cada paquete de la mesh incluye un `authTag` HMAC-SHA256 calculado con la clave simétrica de 256 bits (`rideSecretKey`) de la sesión. Adicionalmente, la pertenencia a la rodada está respaldada por credenciales offline firmadas con **Ed25519**. |
| **Ataques de repetición (Replay Attacks)** | Un tercero graba paquetes legítimos de telemetría o voz y los retransmite en diferido. | **Deduplicación e histéresis:** Ventana deslizante de deduplicación de 1024 entradas en `MeshRelayEngine`, contador de saltos (TTL máx. 2), identificador de secuencia (`seq`) y ventana de vigencia temporal de 15 segundos para paquetes efímeros. |
| **Intercepción de voz o telemetría (Eavesdropping)** | Espionaje de comunicaciones del club o rastreo de rutas por atacantes cercanos. | **Cifrado en tránsito y arquitectura efímera:** Paquetes cifrados con clave de rodada. Los clips de voz Opus se reproducen una sola vez en cola secuencial y se purgan de inmediato de memoria y disco. El backend **nunca** almacena audio, telemetría ni ubicación continua. |
| **Uso de claves fijas de prueba en producción** | Un atacante usa la clave fija pública de laboratorio para descifrar la mesh de una rodada real. | **Invariante D44 (Bloqueante):** El build `standardRelease` tiene `ALLOW_DEFAULT_TEST_RIDE_KEY = false`. La tarea Gradle `verifyStandardReleaseSecurity` impide compilar si este flag no es estricto. Si una sesión se inicia sin clave, genera una clave efímera aleatoria de 32 bytes (`SecureRandom`). |
| **Extracción de datos médicos / Bóveda en terminal extraviado** | Robo de identidad o acceso a datos sensibles de salud tras un accidente o hurto. | **Cifrado local AES-256-GCM:** Los documentos de la bóveda se cifran con claves protegidas por Android Keystore. El `EmergencyRescuePacket` expone únicamente datos vitales (sangre, alergias, ICE) solo ante SOS activo o caída confirmada, con acceso estricto de 60 minutos auditado y purga al terminar la rodada (RULES R10). |
| **Usurpación de Mesa Directiva o cargos oficiales** | Un usuario no autorizado intenta alterar cargos, crear rodadas oficiales o expulsar miembros. | **Firmas Ed25519 y triggers en PostgreSQL 16:** Validaciones criptográficas mediante llavero rotativo (`CredentialKeyring`). Triggers en base de datos (`trg_group_leaders_single_group_per_user`, `trg_groups_minimum_directiva`, etc.) que hacen matemáticamente imposible la usurpación. |
| **Ingeniería inversa y manipulación de binarios** | Desensamblado de la APK para alterar comprobaciones de integridad o sustraer lógica. | **Ofuscación R8 / ProGuard activa:** Ofuscación estricta de nombres, eliminación de código muerto (`isMinifyEnabled = true`, `isShrinkResources = true`) y Play Integrity API para validar la legitimidad del entorno de ejecución. |

---

## 3. Arquitectura Criptográfica y Claves

### 3.1. Clave de Rodada por Sesión (`rideSecretKey`)
- **Algoritmo:** Clave simétrica de 256 bits (32 bytes).
- **Generación:** Generada criptográficamente por el servidor (`crypto.randomBytes(32)`) al crear la rodada o emitir el manifiesto oficial firmado con Ed25519.
- **Distribución:** Distribuida de forma segura a los participantes autorizados dentro del manifiesto de rodada (`RideManifestPayload.rideSecretKeyHex`).
- **Ciclo de vida:** Efímera. Vence automáticamente con la expiración del manifiesto (máximo 24 horas) y se destruye de la memoria de los terminales al pulsar "Detener rodada".

### 3.2. Credenciales Offline y Llavero Ed25519
- **Algoritmo:** Ed25519 (RFC 8032).
- **Llavero rotativo (`CredentialKeyring`):** Permite rotar claves privadas en backend identificadas por `kid` sin desconectar a los riders que salieron a carretera con credenciales emitidas con claves anteriores válidas.
- **Lista de Revocación:** Endpoint firmado `GET /v1/revocations` para invalidar credenciales de miembros expulsados o en mora antes de iniciar el viaje.

### 3.3. Bóveda Personal y Ficha de Emergencia
- **Cifrado local:** AES-256-GCM con vector de inicialización (IV) de 96 bits generado aleatoriamente por registro.
- **Almacenamiento de clave de bóveda:** En hardware seguro (TEE / StrongBox) mediante `AndroidKeyStore`.
- **Esquema de Rescate Médico (RULES R10):** Separación tajante entre la Bóveda completa (que nunca sale del teléfono) y el Paquete de Emergencia (limitado a grupo sanguíneo, alergias, seguro y teléfonos ICE con marcado directo).

---

## 4. Auditoría y Cumplimiento Normativo (RGPD / LGPD / CCPA)

- **Minimización de Datos:** El servidor de Ruta Biker Sync desconoce las rutas reales recorridas, no almacena audio y no recibe coordenadas GPS fuera de la rodada.
- **Consentimientos Granulares (R15):** Switches independientes para baliza de ubicación (`location`), ficha médica (`emergency_package`) y avisos (`notifications`).
- **Derecho al Olvido (R15.3):** Procedimiento de borrado de cuenta en dos pasos que revoca membresías, anonimiza registros y purga cachés locales y remotas.
- **Portabilidad de Datos (R15.5):** Exportación completa en formato estándar JSON portátil descargable y compartible.
