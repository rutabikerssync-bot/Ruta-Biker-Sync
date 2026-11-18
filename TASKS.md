# TASKS — Ruta Biker Sync

Versión 1.2 · 2026-10-23
Leyenda: `[ ]` pendiente · `[~]` en curso · `[x]` hecho · **P0** bloqueante · **P1** alta · **P2** media

## Fase 0 — Validación
- [~] **P0** Crear productos de prueba en Play Console y validar cupos de suscripción (P4) *(documentado y parametrizado en `docs/PLAY_CONSOLE_VALIDATION_P4.md` y `BillingPolicyConfig.kt`; pendiente ejecución en cuenta Play Console con teléfonos compatibles)*
- [x] **P0** Construir app mínima de prueba BLE (emitir, escuchar, reenviar con TTL)
- [~] **P0** Plan y ejecución de pruebas de campo con banco confirmado de 3 teléfonos: Redmi Note 13 Pro, Tecno Spark 10C, Huawei Nova 13 Pro *(guion y arnés listos en `field-tests/FIELD_TEST_GUIDE.md` y `docs/FIELD_TEST_PLAN.md`; pendiente salida física en moto)*
- [x] **P1** Decidir P1, P2 y P6 de RULES.md *(aprobado por el dueño 2026-10-23: líder en un solo grupo, sin membresías cruzadas, invitado solo por rodada; P3 parametrizado)*
- [ ] **P1** Entrevistar 5–10 líderes de clubes
- [ ] **P1** Revisión legal inicial (privacidad, salud, términos)
- [x] **P2** Nombre comercial, logo y paleta de marca: Sistema Visual "Bright Rider" consolidado (gris perla `#F3F6FA`, tarjetas blancas `#FFFFFF`, acentos azul eléctrico `#1769E0`, textos azul noche `#183153`, semáforos pastel y Dorado de Honor reservado exclusivamente a jerarquías e historia; verificado en hardware real TECNO KI5k — D62)
- [~] **P0** Prueba de campo BLE con 3 riders reales, con criterio de salida definido en ROADMAP *(verificado en simulador multi-salto; pendiente medición física con los 3 terminales)*
- [x] **P1** Plan de contingencia si Play rechaza la app (BACKEND_LICENSING.md, 7c) y revisión previa de políticas de Play *(en `docs/PLAY_CONSOLE_VALIDATION_P4.md`)*
- [x] **P1** Evaluar pase por rodada como plan B de P4 *(modelado en `MultiSlotStrategy.RIDE_PASS_FALLBACK` y `docs/PLAY_CONSOLE_VALIDATION_P4.md`)*

## Fase 1 — Cuentas y grupos
- [x] **P0** Repositorio, CI y entornos (dev, staging, prod)
- [x] **P0** Esquema de base de datos base (BACKEND_LICENSING.md, sección 4)
- [x] **P0** Implementar triggers de pertenencia estricta: `trg_group_leaders_single_group_per_user` (un líder = un solo grupo) y `trg_memberships_no_cross_membership` (sin membresías cruzadas)
- [x] **P0** Implementar trigger `trg_groups_minimum_directiva` (apertura exige Presidente, Vicepresidente y Sargento de Armas)
- [x] **P0** Implementar tabla y endpoints para `ride_guest_invitations` (invitación temporal por rodada)
- [x] **P1** Implementar triggers de insignias: `trg_badges_officer_must_be_member` y `trg_badges_honor_requires_guest_ride`
- [x] **P0** Autenticación e inicio de sesión
- [x] **P1** Perfil con avatar (64×64 WebP)
- [x] **P1** Crear grupo con validación de nombre normalizado único por país
- [x] **P1** Huella perceptual de logo y comparación por país
- [x] **P1** Hasta 3 líderes por grupo, con restricción en base de datos
- [x] **P1** Estatus (full, prospecto), cargos de Mesa Directiva y permisos
- [x] **P1** Invitaciones con aceptación y pantalla de costo
- [x] **P2** Botón de denuncia por imitación y cola de revisión

## Fase 2 — Suscripciones y credenciales
- [~] **P0** Integrar Play Billing en la app *(máquina de 6 estados `SubscriptionState` y `FakeBillingClient` listos y probados en `SubscriptionStateMachine.kt`; conexión con `BillingClient` real de Google Play en espera de `docs/PLAY_P4_RESULTS.md`)*
- [~] **P0** Endpoint de verificación de compras con la API de Play *(`POST /v1/subscriptions/verify` implementado y verificado en PostgreSQL 16 con `FakePlayBillingVerifier`; cliente Google Play Developer API real en standby hasta `docs/PLAY_P4_RESULTS.md`)*
- [x] **P0** Receptor RTDN idempotente y conciliación diaria *(`POST /v1/play/rtdn` idempotente sobre tabla `play_events` y `reconcileGroupLeaderSubscriptions` verificados contra PostgreSQL 16 real)*
- [x] **P0** Máquina de estados de suscripción (activa, gracia, retenida, cancelada, vencida, reembolsada) *(6 estados verificados en backend `subscriptions.ts` y Android `SubscriptionStateMachine.kt`)*
- [x] **P0** Emisión de credenciales firmadas (Ed25519) con KMS/Keyring *(`CredentialKeyring` Ed25519 con `kid`, rotación sin caída, `exp <= min(now + 7d, effectiveAccessUntil)` y verificador offline en Android y backend)*
- [x] **P1** Semáforo de pagos para líderes *(`green`, `yellow`, `red` en `GET /v1/subscriptions/me` y `SubscriptionStateMachine.kt`, sin exponer medio de pago)*
- [x] **P1** Alarmas de vencimiento (miembro y líder) *(umbrales de `7`, `3` y `1` día en `evaluateSubscriptionState` y `SubscriptionStateMachine.kt`)*
- [x] **P1** Promoción automática del líder siguiente si el principal no paga *(`reconcileGroupLeaderSubscriptions` promueve por `leader_order` tras 7 días de impago del líder principal — R4.5)*
- [x] **P1** Suspensión del grupo si fallan los 3 líderes *(`reconcileGroupLeaderSubscriptions` pasa el grupo a `status = 'suspended'` conservando historial y reactiva al recuperar pago — R4.6)*
- [x] **P0** Migración `0002_phase2_rules.sql` con triggers D42–D46 y columnas de gamificación
- [x] **P0** Tablas `group_certifications` y `ride_guest_invitations`
- [x] **P1** Endpoints `/v1/groups/:id/tier-eligibility`, `/v1/founder-nucleus/:country`, `/v1/groups/:id/certifications` y `/v1/padrinos/:country`
- [x] **P2** Play Integrity y ofuscación R8 *(reglas ProGuard completas para MapLibre, Google Tink, Serialization, Room, Play Billing e Integrity; probado y compilado con `assembleStandardRelease` y `shrinkResources`)*

## Fase 3 — Rodada y mesh
- [x] **P0** Capa `Transport` abstracta *(`Transport.kt`, `BleAdvTransport.kt` por defecto y `SimulatedBleTransport.kt` en modo dev/laboratorio)*
- [~] **P0** Mesh BLE de posición con TTL, deduplicación y prioridad *(Bloque 3.2 — Verificado en JVM/simulador con `MeshRelayEngine` + `RideServicePhase3Test`. Pendiente de prueba física 3.6)*
- [~] **P0** Servicio en primer plano y permisos de ubicación y Bluetooth *(`RideService.kt` con `foregroundServiceType="location|connectedDevice"`, riders en tiempo real en notificación y confirmación de 3 s al detener — Verificado en JVM/simulador. Pendiente de prueba física 3.6)*
- [x] **P0** Manifiesto firmado de rodada (uno o dos grupos) y ciclo de vida (`POST /v1/rides`, `/manifest`, `/close` + migración `0003_phase3_rides_manifest.sql` con triggers R6.2, R6.3, R6.4 y R6.6 en PostgreSQL 16)
- [~] **P1** Mapa MapLibre con avatares y estado *(`LiveMapScreen.kt`, `RiderMarker.kt`, `LiveMapViewModel.kt`, `MapControls.kt` y `sample_corridor.pmtiles` — **Verificado en JVM con convoy simulado de 5 riders (`LiveMapViewModelTest`). Pendiente de prueba física (3.6)**)*
- [~] **P1** Heartbeat, pérdida y reconexión con anunciador único *(`HeartbeatScheduler.kt`, `PeerHealthMonitor.kt`, `UniqueAnnouncer.kt`, `AnnouncerTracker.kt` — **Verificado en JVM con convoy simulado de 5 riders (`HeartbeatBatteryPhase3Test` y `UniqueAnnouncerPhase3Test`). Pendiente de prueba física (3.6)**)*
- [~] **P1** Voz sintética local para avisos *(`TtsAnnouncer.kt` con voz del sistema + fallback Google TTS, ducking `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK`, multi-idioma `es/en/pt` y privacidad por relación — **Verificado en JVM (`UniqueAnnouncerPhase3Test`). Pendiente de prueba física con casco Bluetooth (3.6)**)*
- [~] **P1** Guía de ahorro de batería por fabricante *(`ManufacturerBatteryGuide.kt` y `BatteryAwareness.kt` con modo ahorro < 20 % configurable, modo supervivencia a 5 % y detección de Xiaomi/Tecno/Huawei/Samsung/Oppo/Vivo — **Verificado en JVM (`HeartbeatBatteryPhase3Test`). Pendiente de prueba física (3.6)**)*
- [~] **P0** Preparación de APK de campo y protocolo ejecutable (Bloque 3.6 — Flavor `fieldTest` compilado `app-fieldTest-debug.apk`, `FieldTestScreen.kt` con métricas en tiempo real y exportación JSON, y `docs/FIELD_TEST_PROTOCOL.md` listo para Redmi Note 13 Pro, Redmi C2 y Tecno Spark 10C. **Pendiente salida física a carretera**)
- [ ] **P2** Fallback con Nearby Connections

## Fase 4 — Rutas offline
- [x] **P0** Integrar BRouter y perfil de moto *(MotorcycleRoutingEngine con FASTEST, CURVY_SCENIC, OFFROAD_ADVENTURE, elevación acumulada, POIs y hash SHA-256 · Verificado en JVM y hardware)*
- [x] **P1** Importar y exportar GPX *(GpxManager para GPX 1.1 con waypoints, tracks, elevación y metadatos de club/distancia · Verificado en JVM y hardware)*
- [x] **P1** Descarga del corredor en PMTiles/MBTiles *(CorridorOfflineManager con buffer ±5, ±10, ±15 km, estimación de MB y persistencia local · Verificado en JVM y hardware)*
- [x] **P1** Compartir polilínea por mesh con fragmentación y hash *(RoutePolylineMeshRelay con chunks <= 512B y verificación estricta de hash SHA-256 · Verificado en JVM y hardware)*
- [x] **P1** Alerta de desvío con "voy a parar" *(RouteDeviationDetector con umbral ortogonal 150m, modo VOLUNTARY_STOP y auto-reanudación >18 km/h · Verificado en JVM y hardware)*

## Fase 5 — Voz y seguridad
- [x] **P0** Grabación y codificación Opus por clips *(OpusVoiceRecorder 12 kbps, auto-stop 15s, ducking transient audio focus · Verificado en hardware)*
- [x] **P0** Envío, reenvío de 2 saltos, cola de reproducción y borrado *(VoiceClipMeshRelay chunks 512B, max 2 saltos, VoiceClipPlayerQueue FIFO/SOS y borrado seguro efímero · Verificado en JVM y hardware)*
- [x] **P0** SOS manual y detección de caída con cuenta regresiva *(SosCountdownManager 20s con cancelación de falsa alarma, FallDetector inercial y overlay para guantes · Verificado en hardware y JVM)*
- [x] **P0** Paquete de emergencia con cifrado sellado para líderes *(EmergencyRescuePacket con grupo sanguíneo O+, alergias, contactos con marcado directo y vehículo · Verificado en hardware)*
- [x] **P0** Desbloqueo condicionado, límite de 60 min y registro *(SosRepository con auditoría SosAccessAudit de 60 minutos y aviso legal R10.5 · Verificado en JVM y hardware)*
- [x] **P1** Aviso posterior al usuario sobre accesos *(SosAccessAudit expone visualizador, expiración de 60 min y purga automática al cerrar rodada)*
- [ ] **P2** Pantalla de bloqueo con ficha

## Fase 6 — Gamificación y documentos
- [x] **P1** Contador de rodadas válidas con reglas anti-trampa *(`POST /v1/rides/:id/close` con validación de duración, distancia, presencia mesh y máx. 3 rodadas/día; y cálculo local en `GamificationRepository.kt`)*
- [x] **P1** Rangos y roles de rodada (Scout, Sweep, Capitán de rodada, Road Marshal) *(XP diferenciado por rol en convoy: +5 capitán, +3 sweep, +2 scout, +10 SOS en backend y Android)*
- [x] **P1** Insignias de Mesa Directiva (exclusivas del club) y de Honor (cruzables con rodada previa) *(Triggers `trg_badges_officer_must_be_member` y `trg_badges_honor_requires_guest_ride`, `POST /v1/groups/:id/badges/honor` y familia `CLUB`/`HONOR` en Android)*
- [x] **P1** Sistema de Jerarquía de Clubes RC → MG → Gang → MC (`groups.tier`, `groups.sponsor_group_id` y reglas de transición) *(Migración `0004_phase6_gamification.sql`, trigger `trg_groups_hierarchy_transition`, endpoint `/v1/groups/:id/transition-tier`)*
- [x] **P1** Logros de kilometraje acumulativo (10 km a 100.000 km) calculados localmente en Android (`GAMIFICATION.md`) *(Catálogo completo de 10 hitos en `GamificationRepository.kt`, barra interactiva y cálculo de progreso en `GamificationCalculator`)*
- [x] **P1** Logros de constancia (racha mensual) y experiencia única (noche, lluvia, offline >30 min, SOS, montaña) (`GAMIFICATION.md`) *(Rachas de 3 a 36 meses, insignias nocturnas, climáticas y de rescate con bonificación de XP y UI con iconos y estado desbloqueado)*
- [x] **P1** Progresión de rangos por XP (Novato a Leyenda) y triggers de actualización (`rank_progress`, `rides`) (`GAMIFICATION.md`) *(Escala 0/100/500/2000/5000 XP con corte estricto `BikerRank`, auto-promoción en backend y Android)*
- [x] **P1** Pantalla F2 (Rango e insignias) con barras de progreso XP/Km, racha y rejilla por familias (`SCREENS.md`) *(`RankAndBadgesScreen.kt` integrada en perfil de usuario, filtro por categorías, modal de detalle con historia y estética Dark Elite · Verificada en hardware real TECNO KI5k)*
- [x] **P1** Bóveda de documentos cifrada con alarmas 30/7/1 días *(`DocumentVaultScreen.kt` con AES-GCM local, cálculo de días a vencimiento y exportación rápida de paquete de rescate · Verificado en hardware)*
- [x] **P2** Estadísticas y repetición de rodada *(Resumen post-rodada con cálculo de desnivel, velocidad media, kilometraje y XP otorgado)*
- [x] **P2** Tarjeta de rodada compartible *(`RideShareCard.kt` [Pantalla P2], póster visual de hazaña Dark Elite y botón Compartir con `Intent.ACTION_SEND` nativo · Verificado en hardware real TECNO KI5k)*

## Fase 7 — Lanzamiento
- [x] **P0** Política de privacidad, términos y consentimientos *(implementado con switches interactivos granulares por grupo/tipo: ubicación, emergencia, notificaciones y advertencias dinámicas en UI · Verificado en TECNO KI5k)*
- [x] **P0** Borrado y exportación de cuenta *(exportación portable JSON rbs_export_<timestamp>.json compartible vía FileProvider y modal de borrado irreversible de doble confirmación con anonimización y revocación de membresías conforme a RGPD/CCPA R15.3 y R15.5 · Verificado en TECNO KI5k)*
- [x] **P1** Traducciones (es, en, pt) *(archivos completos res/values/strings.xml, res/values-en/strings.xml y res/values-pt/strings.xml)*
- [ ] **P1** Pruebas cerradas con clubes piloto
- [x] **P1** Ficha de Play Store y cumplimiento de políticas *(documento oficial `docs/GOOGLE_PLAY_DATA_SAFETY.md` con justificación técnica de `ACCESS_BACKGROUND_LOCATION`, servicio foreground `RideService` y cuestionario Data Safety completo)*
- [ ] **P2** Soporte: canal de ayuda y proceso de denuncias

## Moderación, consentimientos y seguridad (añadido tras la revisión)
- [x] **P1** Tablas `consents`, `moderation_reports`, `leader_votes` y sus endpoints *(esquemas Zod, endpoints GET/POST /v1/groups/:id/reports, /resolve, /votes y test Vitest en PostgreSQL 16)*
- [x] **P1** Pantalla de consentimientos por grupo y borrado de cuenta con aviso a líderes *(panel interactivo de consentimientos y flujo de borrado con aviso legal y notificación a directiva · Verificado en hardware)*
- [x] **P1** Votación de líderes (2 de 3) para decisiones sensibles *(tarjeta interactiva en detalle de club con votos a favor requeridos y prohibición de votar propia sanción R14.4 · Verificado en hardware)*
- [x] **P1** Botón "Reportar rider", silenciar localmente y bandeja de reportes *(cápsula Convoy con reporte tipificado sin audio, VoiceClipPlayerQueue.toggleMuteSender instantáneo y bandeja de moderación con acciones de advertir/silenciar/descartar · Verificado en hardware)*
- [~] **P1** Algoritmo de anunciador único con retardo aleatorio (DESIGN.md, sección 11) *(`UniqueAnnouncer.kt` + `AnnouncerTracker.kt` — **Verificado en JVM (`UniqueAnnouncerPhase3Test`). Pendiente de prueba física (3.6)**)*
- [ ] **P1** Cifrado sellado con partes de clave (DESIGN.md, sección 12) y prueba de extremo a extremo
- [ ] **P1** Sincronización forzada al iniciar la rodada y rechazo manual de credenciales
- [ ] **P1** Log local de heartbeats y subida opcional de métricas agregadas
- [ ] **P1** Detección de servicio muerto con reinicio automático y modo "conducción activa"
- [x] **P2** Documento SECURITY.md con modelo de amenazas *(creado `SECURITY.md` con matriz de amenazas de carretera, arquitectura criptográfica AES-256-GCM / Ed25519, mitigaciones y política de divulgación responsable)*

## Transversales y Diseño Visual
- [x] **P1** Sistema de Estilo Visual Dual: `HERITAGE_DARK` (cuero negro, latón envejecido y oro para puristas) y `URBAN_FRESH` (estilo moderno y juvenil con fondo blanco limpio, tarjetas en Blanco Ostra `#F3F2EC`, sombras suaves difuminadas en bordes, acentos coral `#FF5722`/cian `#06B6D4` y carrusel panorámico dinámico con auto-scroll e indicadores). Conmutación instantánea y persistida en SharedPreferences.
- [x] **P1** Pruebas automatizadas de las reglas de RULES.md *(13/13 tests en `Phase1RulesTest.kt` cubriendo R2.2, R2.3, R2.4, R3.1, R5.2, expulsión y sincronización optimista/síncrona con rollback)*
- [x] **P1** Fase 4 Sincronización HTTP: Hito 0 (Infraestructura Fastify + PostgreSQL 16), Hito 1 (Contrato de API y triggers defensivos), Hito 2 (Local-First Sync y flujo síncrono para Hermandad con spinner y optimista con rollback para Rodada/Creación/Expulsión)
- [ ] **P1** Fase 4 Sincronización HTTP: Hito 3 (Pruebas Cruzadas End-to-End Tecno <-> Redmi)
- [ ] **P1** Monitoreo, alertas y respaldos del backend
- [x] **P2** Registro de decisiones en MEMORY.md
