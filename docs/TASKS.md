# TASKS — Ruta Biker Sync

Versión 1.1 · 2026-10-17
Leyenda: `[ ]` pendiente · `[~]` en curso · `[x]` hecho · **P0** bloqueante · **P1** alta · **P2** media

## Fase 0 — Validación
- [~] **P0** Crear productos de prueba en Play Console y validar cupos múltiples de invitado (P4) *(documentado y parametrizado en `docs/PLAY_CONSOLE_VALIDATION_P4.md` y `BillingPolicyConfig.kt`; pendiente ejecución en cuenta Play Console)*
- [x] **P0** Construir app mínima de prueba BLE (emitir, escuchar, reenviar con TTL)
- [~] **P0** Plan y ejecución de pruebas de campo (3–4 teléfonos, moto, casco) *(guion y arnés listos en `field-tests/FIELD_TEST_GUIDE.md`; pendiente salida física en moto)*
- [~] **P1** Decidir P1, P2 y P3 de RULES.md *(propuestas actuales implementadas detrás de flags en `BillingPolicyConfig.kt`)*
- [ ] **P1** Entrevistar 5–10 líderes de clubes
- [ ] **P1** Revisión legal inicial (privacidad, salud, términos)
- [ ] **P2** Nombre comercial, logo y paleta de marca *(tokens provisionales marcados con `TODO(marca)` en `Theme.kt`)*
- [~] **P0** Prueba de campo BLE con 4–5 riders reales, con criterio de salida definido en ROADMAP (antes de la Fase 1) *(verificado en simulador multi-salto de 5 nodos; pendiente medición física)*
- [x] **P1** Plan de contingencia si Play rechaza la app (BACKEND_LICENSING.md, 7c) y revisión previa de políticas de Play *(en `docs/PLAY_CONSOLE_VALIDATION_P4.md`)*
- [x] **P1** Evaluar pase por rodada como plan B de P4 *(modelado en `MultiSlotStrategy.RIDE_PASS_FALLBACK` y `docs/PLAY_CONSOLE_VALIDATION_P4.md`)*

## Fase 1 — Cuentas y grupos
- [x] **P0** Repositorio, CI y entornos (dev, staging, prod)
- [x] **P0** Esquema de base de datos (BACKEND_LICENSING.md, sección 4)
- [x] **P0** Autenticación e inicio de sesión
- [x] **P1** Perfil con avatar (64×64 WebP)
- [x] **P1** Crear grupo con validación de nombre normalizado único por país
- [x] **P1** Huella perceptual de logo y comparación por país
- [x] **P1** Hasta 3 líderes por grupo, con restricción en base de datos
- [x] **P1** Estatus (full, prospecto, invitado), cargos y permisos
- [x] **P1** Invitaciones con aceptación y pantalla de costo
- [x] **P2** Botón de denuncia por imitación y cola de revisión

## Fase 2 — Suscripciones y credenciales
- [ ] **P0** Integrar Play Billing en la app
- [ ] **P0** Endpoint de verificación de compras con la API de Play
- [ ] **P0** Receptor RTDN idempotente y conciliación diaria
- [ ] **P0** Máquina de estados de suscripción (activa, gracia, retenida, vencida, reembolsada)
- [ ] **P0** Emisión de credenciales firmadas (Ed25519) con KMS
- [ ] **P1** Semáforo de pagos para líderes
- [ ] **P1** Alarmas de vencimiento (miembro y líder)
- [ ] **P1** Promoción automática del líder siguiente si el principal no paga
- [ ] **P1** Suspensión del grupo si fallan los 3 líderes
- [ ] **P2** Play Integrity y ofuscación R8

## Fase 3 — Rodada y mesh
- [ ] **P0** Capa `Transport` abstracta
- [ ] **P0** Mesh BLE de posición con TTL, deduplicación y prioridad
- [ ] **P0** Servicio en primer plano y permisos de ubicación y Bluetooth
- [ ] **P0** Manifiesto firmado de rodada (uno o dos grupos)
- [ ] **P1** Mapa MapLibre con avatares y estado
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
- [ ] **P1** Rangos y roles de rodada con excepción de líderes
- [ ] **P1** Insignias de rango, logros y honor (límite mensual y registro)
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
