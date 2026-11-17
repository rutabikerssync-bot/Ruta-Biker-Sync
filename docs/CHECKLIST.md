# CHECKLIST — Ruta Biker Sync

Versión 1.2 · 2026-10-23
Listas de verificación para revisar antes de cada hito o lanzamiento.

## A. Antes de empezar a programar
- [~] Cobro de cupos múltiples probado en Play Console (P4) *(guion operativo de 15 secciones en `PLAY_TEST_SETUP.md`; pendiente ejecución en cuenta Play Console del dueño)*
- [x] Decisiones P1, P2 y P6 de RULES.md cerradas por el dueño *(2026-10-23: líder en un solo grupo, invitado solo por rodada, sin multi-grupo; P3 parametrizado)*
- [~] Prueba de campo BLE con banco de 3 teléfonos confirmados *(Redmi Note 13 Pro, Tecno Spark 10C, Huawei Nova 13 Pro; alerta GMS y protocolo en `FIELD_TEST_PLAN.md`; pendiente salida física)*
- [ ] Revisión legal inicial hecha
- [ ] Nombre comercial verificado (marca, dominios, tienda)

## B. Cuentas y grupos
- [x] Un usuario no puede entrar a un grupo sin aceptar
- [x] Full member, prospecto y líder solo en un grupo (sin membresías cruzadas, `trg_memberships_no_cross_membership`)
- [x] Invitados participan únicamente por rodada específica (`ride_guest_invitations`, sin cuota recurrente fija)
- [x] Máximo 3 líderes por grupo y un usuario solo puede liderar 1 grupo (`trg_group_leaders_single_group_per_user`)
- [x] Requisito mínimo de apertura: 3 cargos de Mesa Directiva (Presidente, Vicepresidente, Sargento de Armas) comprobado en BD (`trg_groups_minimum_directiva`)
- [x] Nombre único por país, incluso con acentos, símbolos y sustituciones leet
- [x] Logo único por país, incluso con cambios mínimos o de color
- [x] Transferencia solo entre miembros, con espera de 7 días y cancelación
- [x] Solo los líderes cambian cargos y estatus
- [x] Insignias de Mesa Directiva exclusivas para miembros oficiales; honor cruzable condicionado a rodada previa
- [x] Jerarquía de clubes (RC/MG/Gang/MC) con apadrinamiento incorporada al modelo

## C. Cobros
- [~] Compra, renovación, cancelación y reembolso probados *(verificados contra PostgreSQL 16 real y JUnit con `FakePlayBillingVerifier` / `FakeBillingClient`; **NO VERIFICADO EN DISPOSITIVO / PLAY CONSOLE REAL** hasta contar con `docs/PLAY_P4_RESULTS.md`)*
- [x] Periodo de gracia de 3 días funciona *(verificado en `phase2_subscriptions_credentials.test.ts` y `BillingPolicyConfigTest.kt`)*
- [x] Notificaciones RTDN idempotentes y conciliación diaria *(verificadas en `POST /v1/play/rtdn` con clave única `event_id` en `play_events` en PostgreSQL 16 real)*
- [x] El líder ve semáforo, nunca datos de pago *(verificado: `paymentSemaphore` expone solo `green`/`yellow`/`red`)*
- [x] Alarmas de vencimiento (7, 3 y 1 día) llegan *(verificadas en backend y Android)*
- [x] Pago vencido implica credencial no renovada y rechazo en la mesh *(verificado: `GET /v1/credentials` devuelve `402 SUBSCRIPTION_INACTIVE` en `on_hold`/`expired`/`refunded` y el verificador offline rechaza credenciales caducadas o revocadas)*
- [x] Líder principal sin pago: rol pasa al siguiente en 7 días *(verificado en PostgreSQL 16 real según `RULES.md` R4.5)*
- [x] Tres líderes sin pago: grupo suspendido, historial intacto *(verificado en PostgreSQL 16 real según `RULES.md` R4.6, incluyendo reactivación al volver a pagar)*

## D. Credenciales y seguridad
- [x] Credenciales firmadas y verificadas sin internet *(Ed25519 verificadas en `credentials.ts` y `OfflineCredentialVerifier.kt`; nota sobre almacén de sesión Android: `KeystoreEncryptedTokenStore` usa sustituto JCE en memoria en tests JVM locales y cuenta con prueba instrumentada `KeystoreEncryptedTokenStoreInstrumentedTest.kt` marcada como **NO VERIFICADO EN DISPOSITIVO**)*
- [x] Rotación de claves de firma probada *(verificada con `kid` antiguo y nuevo coexistiendo en `CredentialKeyring` y `OfflineCredentialVerifier`)*
- [x] Credencial caducada rechazada en rodada *(verificada con `EXPIRED_CREDENTIAL` en backend y Android)*
- [x] Lista de revocación sincronizada antes de salir *(verificada en `GET /v1/revocations` firmada con Ed25519 y aplicada en `OfflineCredentialVerifier`)*
- [x] Manifiesto de rodada con dos grupos validado *(verificado con firma Ed25519 en `POST /v1/rides/:id/manifest`, `verifyRideManifest` en TypeScript y Kotlin, y triggers `trg_ride_groups_max_two` + `trg_ride_participants_authorize_entry` en PostgreSQL 16 real)*
- [ ] Play Integrity y ofuscación activos
- [ ] Mensajes de la mesh cifrados y firmados
- [ ] [BLOQUEANTE DE LANZAMIENTO] Clave de rodada (`rideSecretKey`) dinámica por sesión: prohibido usar valor por defecto fijo (`"rbs-phase3-default-ride-key-32b!"`) en producción/release. Debe ser generada y distribuida por rodada mediante manifiesto y credenciales firmadas. El build `standardRelease` ignora/rechaza la clave por defecto (`ALLOW_DEFAULT_TEST_RIDE_KEY=false` genera clave efímera aleatoria).

## E. Mesh y rodada
- [~] Reenvío con TTL sin bucles ni duplicados *(verificado en simulador unitario `MeshRelayEngine` + `MeshValidationPhase0Test`; pendiente prueba física B5 de `FIELD_TEST_PLAN.md` §5.5)*
- [~] Pérdida de cobertura detectada sin falsas alarmas en curvas o túneles *(Bloque 3.5 implementado en `PeerHealthMonitor.kt` + `MeshRelayEngine.kt` con umbral de 5 latidos = 15 s, histéresis de reconexión de 2 latidos consecutivos y anuncio TTS de islas solo si duran > 60 s; **Verificado en JVM (`HeartbeatBatteryPhase3Test`). Pendiente de prueba física (3.6)**)*
- [~] Un solo anunciador por pérdida *(Bloque 3.4 implementado en `UniqueAnnouncer.kt` con retardo ponderado por cercanía, desempate por menor `shortId`, recordatorio único a 60 s y agrupación de 3 pérdidas en 10 min como `"conexión inestable"`; **Verificado en JVM (`UniqueAnnouncerPhase3Test`). Pendiente de prueba física (3.6)**)*
- [~] Reconexión automática y aviso de regreso *(Bloque 3.4 y 3.5 implementados en `UniqueAnnouncer.kt`, `AnnouncerTracker.kt` y `PeerHealthMonitor.kt`: cancela anuncios pendientes de pérdida y pronuncia `"X se reconectó"` con anunciador único tras histéresis de 2 latidos; **Verificado en JVM (`UniqueAnnouncerPhase3Test` y `HeartbeatBatteryPhase3Test`). Pendiente de prueba física (3.6)**)*
- [~] Servicio en primer plano sobrevive en Samsung, Xiaomi y otros *(Bloques 3.2 y 3.5 implementados en `RideService.kt` + `ManufacturerBatteryGuide.kt` con `PARTIAL_WAKE_LOCK`, notificación en tiempo real, confirmación de 3 s y guía OEM para Xiaomi/Tecno/Huawei/Samsung/Oppo/Vivo; **Verificado en JVM/simulador. Pendiente de prueba física 3.6**)*
- [~] Modo ahorro de batería bajo 20 % *(Bloque 3.5 implementado en `BatteryAwareness.kt` y `HeartbeatScheduler.kt`: automático pero configurable a < 20 % con heartbeat cada 5 s y mapa cada 3 s, y modo supervivencia a <= 10 % / 5 % con heartbeat cada 10 s, mapa congelado y solo SOS; **Verificado en JVM (`HeartbeatBatteryPhase3Test`). Pendiente de prueba física (3.6)**)*
- [ ] Prueba con 12 a 15 nodos
- [x] Mesh y voz solo funcionan durante una rodada activa *(verificado en `RideSessionController` y `RideServicePhase3Test`: `stopAll()` detiene transporte/mesh y purga notas de voz y accesos temporales de emergencia — R6.1 y R6.5)*

## F. Rutas
- [ ] Ruta importada o trazada se guarda offline
- [~] Mapa del corredor funciona sin señal *(Bloque 3.3 implementado con `sample_corridor.pmtiles` y `dark_corridor_style.json` en `LiveMapScreen.kt`; **Verificado en JVM. Pendiente de prueba física (3.6)**)*
- [ ] Ruta compartida llega íntegra (hash verificado)
- [ ] Alerta de desvío sin molestar a quien para a propósito
- [x] Atribución de OpenStreetMap visible *(verificada en `LiveMapScreen.kt` y `LiveMapViewModelTest`: `"© OpenStreetMap contributors"` siempre visible)*

## G. Voz
- [ ] Clip máximo 15 s, Opus, llega en 1 a 3 s por salto
- [ ] Cola de reproducción sin voces pisadas
- [ ] Clip se borra tras reproducir y al cerrar la rodada
- [ ] No se guarda audio en servidor ni en historial
- [ ] Salida correcta al casco por Bluetooth

## H. Emergencias y datos de salud
- [ ] Consentimiento separado y explícito para el paquete de emergencia
- [ ] Solo el paquete de emergencia, nunca la bóveda completa
- [ ] Desbloqueo solo por SOS o declaración de líder con confirmación
- [ ] Acceso limitado a 60 min y con registro
- [ ] Borrado al cerrar la rodada
- [ ] El afectado recibe aviso de quién accedió
- [ ] Aviso visible de que no sustituye servicios de emergencia
- [ ] Detección de caída con cancelación de 20 s

## I. Rangos e insignias
- [ ] Rangos sin solapamiento (0–4, 5–19, 20–49, 50–99, 100+) y progresión por XP en Fase 6 (`GAMIFICATION.md`)
- [x] Reglas anti-trampa de rodada validadas *(verificadas en PostgreSQL 16 real mediante `trg_ride_participants_enforce_r66` y `POST /v1/rides/:id/close`: ≥2 nodos con contacto mesh, >15 km, >30 min, ≥70 % presencia, cierre por líder y máx. 3 rodadas válidas/día — R6.6)*
- [ ] Insignias de honor con límite mensual y registro (requiere haber sido invitado a una rodada del grupo)
- [ ] Honor no suma para el rango
- [ ] Fundador es marca permanente, no rango
- [ ] Insignias de Mesa Directiva exclusivas para miembros oficiales del club (`trg_badges_officer_must_be_member`)
- [ ] Logros de kilometraje acumulativo (10 km a 100.000 km) calculados localmente en Android (`GAMIFICATION.md`)
- [ ] Logros de constancia (racha mensual ininterrumpida) y experiencia (noche, lluvia, offline, SOS, montaña) (`GAMIFICATION.md`)

## J. Privacidad y legal
- [ ] Política de privacidad y términos publicados
- [ ] Consentimientos separados (ubicación, emergencia, notificaciones)
- [ ] Exportación y borrado de cuenta
- [ ] Backend sin ubicación, voz, documentos ni salud
- [ ] Cumplimiento GDPR, LGPD, CCPA revisado por un abogado
- [ ] Declaración de datos de Google Play completada
- [ ] Permiso de ubicación en segundo plano justificado

## K. Calidad y lanzamiento
- [~] Modo guantes y botones ≥72 dp *(Bloque 3.3 implementado en `MapControls.kt` con `GlovesMinTouchTarget = 72.dp`, vibración háptica y máximo 3 acciones flotantes en `LiveMapScreen.kt`; **Verificado en JVM (`LiveMapViewModelTest`). Pendiente de prueba física (3.6)**)*
- [ ] Textos en español, inglés y portugués
- [ ] Pruebas con al menos 2 clubes piloto
- [ ] Monitoreo, alertas y respaldos activos
- [ ] Canal de soporte y proceso de denuncias
- [ ] Ficha de tienda, capturas y clasificación de contenido

## L. Moderación y convivencia
- [ ] Reglas de convivencia aceptadas al registrarse
- [ ] Botón de reportar rider durante y después de la rodada
- [ ] Silenciar localmente a un rider funciona al instante
- [ ] Reportes llegan a los líderes del grupo correcto y no contienen audio
- [ ] Reporte contra un líder lo revisan los otros líderes o soporte
- [ ] Decisiones sensibles requieren mayoría de líderes (2 de 3) y quedan en auditoría

## M. Consentimientos por grupo
- [ ] Cada consentimiento es por grupo y por tipo, nunca global
- [ ] El usuario ve y revoca sus consentimientos
- [ ] Revocar ubicación saca al usuario de las rodadas de ese grupo
- [ ] Borrar la cuenta notifica a los líderes de los grupos afectados
- [ ] La exportación de datos incluye membresías, suscripciones y consentimientos

## N. Plan de contingencia y métricas
- [x] Políticas de Play revisadas antes de la Fase 2 *(documentado en `PLAY_TEST_SETUP.md` §14 y `docs/PLAY_CONSOLE_VALIDATION_P4.md` §5)*
- [x] Respaldo de distribución y cobro definido si Play rechaza la app *(`BACKEND_LICENSING.md` §7c y `PLAY_TEST_SETUP.md` §13)*
- [x] Pase por rodada evaluado como plan B de cupos múltiples *(`MultiSlotStrategy.RIDE_PASS_FALLBACK` + `ride_pass_guest` en `PLAY_TEST_SETUP.md` §8 y §13)*
- [ ] Log local de heartbeats y subida de agregados anónimos con consentimiento
- [ ] Anunciador único probado con 5 o más nodos detectando la misma pérdida
- [ ] Cifrado sellado probado: SOS abre con un líder, declaración exige confirmación y todo se destruye al cerrar
- [ ] Sincronización forzada y rechazo manual de credenciales probados sin internet

---

## ⚠️ NOTA — Archivo obsoleto (2026-11-16)

Este `docs/CHECKLIST.md` **ya no se mantiene**. La lista de verificación
canónica está en [`CHECKLIST.md`](../CHECKLIST.md) en la raíz del repositorio,
con las secciones D, K y §P actualizadas con los gates de seguridad de la
auditoría 2026-11-16 (B1-1…B5-4, B3-3/4/5, B5-2) y las notas de estado de
cada hallazgo.

**No editar este archivo.** Cualquier actualización futura debe ir a
`CHECKLIST.md` (raíz).

