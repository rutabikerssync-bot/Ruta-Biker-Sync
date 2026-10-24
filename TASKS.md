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
- [ ] **P2** Nombre comercial, logo y paleta de marca *(tokens provisionales marcados con `TODO(marca)` en `Theme.kt`)*
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
- [x] **P1** Tests de integración `phase2_rules.test.ts` en PostgreSQL 16 (7 nuevos, total 24 en 4 suites)
- [ ] **P2** Play Integrity y ofuscación R8

## Fase 3 — Rodada y mesh
- [x] **P0** Capa `Transport` abstracta *(`Transport.kt`, `BleAdvTransport.kt` por defecto y `SimulatedBleTransport.kt` en modo dev/laboratorio)*
- [~] **P0** Mesh BLE de posición con TTL, deduplicación y prioridad *(Bloque 3.2 — Verificado en JVM/simulador con `MeshRelayEngine` + `RideServicePhase3Test`. Pendiente de prueba física 3.6)*
- [~] **P0** Servicio en primer plano y permisos de ubicación y Bluetooth *(`RideService.kt` con `foregroundServiceType="location|connectedDevice"`, riders en tiempo real en notificación y confirmación de 3 s al detener — Verificado en JVM/simulador. Pendiente de prueba física 3.6)*
- [x] **P0** Manifiesto firmado de rodada (uno o dos grupos) y ciclo de vida (`POST /v1/rides`, `/manifest`, `/close` + migración `0003_phase3_rides_manifest.sql` con triggers R6.2, R6.3, R6.4 y R6.6 en PostgreSQL 16)
- [~] **P1** Mapa MapLibre con avatares y estado *(`LiveMapScreen.kt`, `RiderMarker.kt`, `LiveMapViewModel.kt`, `MapControls.kt` y `sample_corridor.pmtiles` — **Verificado en JVM con convoy simulado de 5 riders (`LiveMapViewModelTest`). Pendiente de prueba física (3.6)**)*
- [ ] **P1** Heartbeat, pérdida y reconexión con anunciador único
- [ ] **P1** Voz sintética local para avisos
- [ ] **P1** Guía de ahorro de batería por fabricante
- [ ] **P2** Fallback con Nearby Connections

## Fase 4 — Rutas offline
- [ ] **P0** Integrar BRouter y perfil de moto
- [ ] **P1** Importar y exportar GPX
- [ ] **P1** Descarga del corredor en PMTiles/MBTiles
- [ ] **P1** Compartir polilínea por mesh con fragmentación y hash
- [ ] **P1** Alerta de desvío con "voy a parar"

## Fase 5 — Voz y seguridad
- [ ] **P0** Grabación y codificación Opus por clips
- [ ] **P0** Envío, reenvío de 2 saltos, cola de reproducción y borrado
- [ ] **P0** SOS manual y detección de caída con cuenta regresiva
- [ ] **P0** Paquete de emergencia con cifrado sellado para líderes
- [ ] **P0** Desbloqueo condicionado, límite de 60 min y registro
- [ ] **P1** Aviso posterior al usuario sobre accesos
- [ ] **P2** Pantalla de bloqueo con ficha

## Fase 6 — Gamificación y documentos
- [ ] **P1** Contador de rodadas válidas con reglas anti-trampa
- [ ] **P1** Rangos y roles de rodada (Scout, Sweep, Capitán de rodada, Road Marshal)
- [ ] **P1** Insignias de Mesa Directiva (exclusivas del club) y de Honor (cruzables con rodada previa)
- [ ] **P1** Sistema de Jerarquía de Clubes RC → MG → Gang → MC (`groups.tier`, `groups.sponsor_group_id` y reglas de transición)
- [ ] **P1** Logros de kilometraje acumulativo (10 km a 100.000 km) calculados localmente en Android (`GAMIFICATION.md`)
- [ ] **P1** Logros de constancia (racha mensual) y experiencia única (noche, lluvia, offline >30 min, SOS, montaña) (`GAMIFICATION.md`)
- [ ] **P1** Progresión de rangos por XP (Novato a Leyenda) y triggers de actualización (`rank_progress`, `rides`) (`GAMIFICATION.md`)
- [ ] **P1** Pantalla F2 (Rango e insignias) con barras de progreso XP/Km, racha y rejilla por familias (`SCREENS.md`)
- [ ] **P1** Bóveda de documentos cifrada con alarmas 30/7/1 días
- [ ] **P2** Estadísticas y repetición de rodada
- [ ] **P2** Tarjeta de rodada compartible

## Fase 7 — Lanzamiento
- [ ] **P0** Política de privacidad, términos y consentimientos
- [ ] **P0** Borrado y exportación de cuenta
- [ ] **P1** Traducciones (es, en, pt)
- [ ] **P1** Pruebas cerradas con clubes piloto
- [ ] **P1** Ficha de Play Store y cumplimiento de políticas
- [ ] **P2** Soporte: canal de ayuda y proceso de denuncias

## Moderación, consentimientos y seguridad (añadido tras la revisión)
- [ ] **P1** Tablas `consents`, `moderation_reports`, `leader_votes` y sus endpoints
- [ ] **P1** Pantalla de consentimientos por grupo y borrado de cuenta con aviso a líderes
- [ ] **P1** Votación de líderes (2 de 3) para decisiones sensibles
- [ ] **P1** Botón "Reportar rider", silenciar localmente y bandeja de reportes
- [ ] **P1** Algoritmo de anunciador único con retardo aleatorio (DESIGN.md, sección 11)
- [ ] **P1** Cifrado sellado con partes de clave (DESIGN.md, sección 12) y prueba de extremo a extremo
- [ ] **P1** Sincronización forzada al iniciar la rodada y rechazo manual de credenciales
- [ ] **P1** Log local de heartbeats y subida opcional de métricas agregadas
- [ ] **P1** Detección de servicio muerto con reinicio automático y modo "conducción activa"
- [ ] **P2** Onboarding del miembro y del líder, con video corto
- [ ] **P2** Documento SECURITY.md con modelo de amenazas

## Transversales
- [ ] **P1** Pruebas automatizadas de las reglas de RULES.md
- [ ] **P1** Monitoreo, alertas y respaldos del backend
- [ ] **P2** Registro de decisiones en MEMORY.md
