# MEMORY — Registro de decisiones de Ruta Biker Sync

Versión 1.2 · 2026-10-17
Memoria del proyecto: decisiones tomadas, su motivo y lo que sigue abierto. Se actualiza con cada decisión nueva (la más reciente arriba dentro de cada sección).

## Decisiones confirmadas

| # | Fecha | Decisión | Motivo |
|---|---|---|---|
| D41 | 2026-10-17 | Propuesta de actualización de Google Play Billing Library de `7.1.1` a **`8.0.0`** manteniendo AGP `8.7.3`, Kotlin `2.0.21`, KSP `2.0.21-1.0.28` y Compose BOM `2024.10.01` (ver tabla de cambios de API en `PLAY_TEST_SETUP.md` §14). No se edita `gradle/libs.versions.toml` hasta confirmación del dueño (`# ROL Y OBJETIVO.txt` §4.3). | Cumplir requisito obligatorio de Google Play Store vigente desde 31-ago-2026 sin romper compilación de Fase 1. |
| D40 | 2026-10-17 | Modelo de sesión en Android (`ClubApiClient` + almacenamiento seguro): `accessToken` de 30 min en memoria volátil y `refreshToken` de 30 días cifrado en reposo con Android Keystore (`MasterKey` AES256-GCM), con rotación automática vía `POST /v1/auth/refresh` y revocación vía `POST /v1/auth/logout`. | Evita persistir tokens de acceso en texto claro y permite revocar sesiones comprometidas o cerradas por el usuario (necesario también para `P4-14`). |
| D39 | 2026-10-17 | Incorporar al contrato REST (`BACKEND_LICENSING.md` §8) las rutas granulares `GET /v1/groups/{id}`, `DELETE /v1/groups/{id}/leaders/{leaderUid}`, `POST /v1/groups/{id}/transfer/{transferId}/accept\|cancel\|execute` y `PATCH /v1/users/me` (con alias `/v1/users/me/profile`). | Completa las operaciones de `RULES.md` R1.3, R4.3, R5.3 y R14.2/R14.3 sin ambigüedad entre cliente Android y backend. |
| D38 | 2026-10-17 | Incorporar `POST /v1/groups/{id}/imitation-reports` y tabla `imitation_reports` al contrato de `BACKEND_LICENSING.md` §4 y §8. | Da soporte de API y base de datos al botón de denuncia por imitación de nombre o logo exigido por `RULES.md` R5.1 y `BACKEND_LICENSING.md` §11. |
| D37 | 2026-10-17 | Incorporar `POST /v1/auth/register`, `POST /v1/auth/login`, `POST /v1/auth/refresh` y `POST /v1/auth/logout` a `BACKEND_LICENSING.md` §8. | Cierra el flujo completo de ciclo de vida de sesión y rotación/revocación de tokens exigido en §10. |
| D36 | 2026-10-17 | Añadir en `0001_init.sql` el trigger `trg_audit_log_immutable` (que impide `UPDATE`/`DELETE` sobre `audit_log`) y la tabla `refresh_tokens` (que almacena únicamente `token_hash` SHA-256, `expires_at`, `revoked_at` y `replaced_by_jti`). | Garantiza en motor PostgreSQL 16 real la inmutabilidad del registro de auditoría y la revocabilidad de sesiones sin guardar tokens en claro. |
| D35 | 2026-10-17 | Autenticación endurecida según OWASP en `backend/src/lib/auth.ts`: `scrypt` con **`N=131072` (`2^17`)**, `r=8`, `p=1`, `keylen=64` + sal CSPRNG de 16 bytes, `accessToken` de **30 min (`1800 s`)**, `refreshToken` rotativo/revocable de 30 d (`2592000 s`) hasheado con SHA-256 en BD, y límite de intentos fallidos combinado por `(correo, IP)` (5) y por `IP` (15) durante 15 min. | Cumple recomendaciones OWASP de almacenamiento de contraseñas, tokens de corta duración y prevención de DoS cruzado contra cuentas de terceros. |
| D34 | 2026-10-17 | Implementar sistema `UiState` + `StateHost` + `SkeletonScope` con temporización determinista (150 ms retraso, 400 ms mínimo visible, 3 s aviso, 8 s timeout) y Panel de Desarrollador exclusivo de Debug (`DEV_PANEL_ENABLED`). | Cumple `SKELETONS.md` §5–§11 y sección 2.2 permitiendo validar todos los estados (Loading, Stale, Empty, Error 500, Suscripción vencida, Offline) en un clic sin recompilar. |
| D33 | 2026-10-17 | Añadir columnas anulables (`password_hash`, `avatar_webp`, `bike_model`, `map_color` en `users`; `role_permissions` en `groups`; `title`, `route_hash` en `rides`) y tabla `imitation_reports` en `0001_init.sql` preservando intactas las 20 tablas de `BACKEND_LICENSING.md` §4, verificadas contra PostgreSQL 16 real (`trg_enforce_max_three_leaders`, `idx_group_leaders_one_principal`, `idx_memberships_one_full_or_prospect`). | Resuelve los huecos documentales detectados en Fase 0 sin romper compatibilidad con el esquema original y garantiza cumplimiento de reglas en motor PostgreSQL 16 real. |
| D32 | 2026-10-17 | Unificar `play_product_id` a `member_slot_1..5` en `BACKEND_LICENSING.md` §3, §7c y §13 (antes `guest_slot_1..N`). Dejar `guest_slot` libre para el futuro pase por rodada (P5). | Coherencia con `kind IN ('leader','member_slot')` de §4 y con D30; evita migración de productos en Play Console. |
| D31 | 2026-10-17 | Parametrizar P1, P2, P3 y P4 detrás de `BillingPolicyConfig` con las propuestas actuales activas por defecto | Permite cambiar criterio de negocio sin reescribir código de dominio ni pruebas |
| D30 | 2026-10-17 | Unificar IDs de producto de cupo en Play Console como `member_slot_1` .. `member_slot_5` (y `ride_pass_guest` como Plan B) | Alinea `BACKEND_LICENSING.md` §3 con el esquema SQL de §4 (`kind = 'member_slot'`) |
| D29 | 2026-10-17 | Empaquetado binario de `MeshPacket` de 20 bytes exactos con secuencia de 12 bits, `initialTtl` (bits 3..2 del byte 5) + `currentTtl` (bits 1..0 del byte 5) y HMAC-SHA256 truncado a 4 bytes calculado exclusivamente sobre los campos inmutables (`versionFlags`, `packetType`, `senderShortId`, `sequenceNumber`, `initialTtl`, `payload`), excluyendo `currentTtl`. | Permite que cada nodo intermedio decremente `currentTtl` al reenviar sin invalidar el HMAC extremo a extremo del emisor original y calcule saltos exactos (`initialTtl - currentTtl + 1`). En Fase 3 este HMAC de prueba de campo se reemplaza por cifrado autenticado ChaCha20-Poly1305 con clave de sesión de rodada (`DESIGN.md` §4). |
| D1 | 2026-10-16 | Solo Android, se descarta iOS | Menos restricciones de BLE y segundo plano |
| D2 | 2026-10-16 | Mesh de posición con BLE advertising y reenvío con TTL; Nearby Connections como respaldo | Más estable en movimiento que conexiones punto a punto |
| D3 | 2026-10-16 | Nombre: **Ruta Biker Sync** | Definido por el dueño |
| D4 | 2026-10-16 | App cerrada (no open source) y de pago | Definido por el dueño |
| D5 | 2026-10-16 | Líder $15/mes; miembro e invitado $2.50/mes por grupo | Definido por el dueño |
| D6 | 2026-10-16 | Hasta 3 líderes por grupo, cada uno paga $15 | Definido por el dueño |
| D7 | 2026-10-16 | Full member y prospecto: un solo grupo. Invitado: varios, con cuota por cada uno | Definido por el dueño |
| D8 | 2026-10-16 | Nadie entra a un grupo sin su consentimiento | Definido por el dueño |
| D9 | 2026-10-16 | Cada líder controla su grupo en rodadas conjuntas | Definido por el dueño |
| D10 | 2026-10-16 | Grupos únicos; nombre y logo únicos por país | Definido por el dueño |
| D11 | 2026-10-16 | Transferencia de grupo solo entre miembros del grupo | Definido por el dueño |
| D12 | 2026-10-16 | Voz por clips, solo en rodadas, se reproduce y se borra | Definido por el dueño |
| D13 | 2026-10-16 | Bóveda personal; líderes solo pueden abrir el paquete de emergencia, no la bóveda completa, y con condiciones | Propuesta de diseño para equilibrar emergencia y privacidad |
| D14 | 2026-10-16 | Cargos del club son etiquetas distintas de "líder" | Evita que cada cargo implique pagar $15 |
| D15 | 2026-10-16 | Rangos sin solapamiento; Fundador es marca, no rango | Corrección de la tabla original |
| D16 | 2026-10-16 | Cualquier líder del grupo puede asignar insignias de honor | Último criterio del dueño; reemplaza la regla "solo el líder principal" |
| D17 | 2026-10-16 | Backend mínimo: sin ubicación, voz, documentos ni salud | Privacidad y costo |
| D18 | 2026-10-16 | Mapas con MapLibre + OSM y ruteo con BRouter | Sin costo de licencias |
| D19 | 2026-10-16 | Venta mundial; idiomas es, en, pt | Definido por el dueño |
| D20 | 2026-10-16 | Cobro mediante Google Play Billing | Estándar de la tienda para bienes digitales |
| D21 | 2026-10-16 | Plan de contingencia si Play rechaza la app o el cobro de cupos múltiples; pase por rodada como plan B | Revisión del diseño; ver BACKEND_LICENSING.md 7c |
| D22 | 2026-10-16 | Anunciador único con retardo aleatorio ponderado por cercanía y cancelación al oír otro anuncio | Evita avisos duplicados; ver DESIGN.md 11 |
| D23 | 2026-10-16 | Consentimientos por grupo y por tipo, con borrado de cuenta que notifica a los líderes | RGPD y consentimiento explícito; ver RULES R15 |
| D24 | 2026-10-16 | Decisiones sensibles requieren mayoría de líderes (2 de 3) | Resolución de conflictos; ver RULES R14 |
| D25 | 2026-10-16 | Paquete de emergencia cifrado con clave dividida entre líderes y claves efímeras destruidas al cerrar | Evita acceso unilateral; ver DESIGN.md 12 |
| D26 | 2026-10-16 | Sincronización forzada al iniciar rodada y rechazo manual de credenciales por el líder anfitrión | Cubre el hueco de la revocación offline |
| D27 | 2026-10-16 | BLE extendido **no** se vuelve requisito obligatorio por ahora; se decide con los números de la prueba de campo | Evita excluir teléfonos de gama baja sin datos |
| D28 | 2026-10-16 | Métricas de mesh: log local y subida opcional de agregados anónimos | Medir sin comprometer privacidad |

## Pendientes (ver RULES.md, sección 12)
- P1: ¿líder pagando solo $15 es invitado gratis en otros grupos? Propuesta: paga $2.50.
- P2: ¿un líder de varios grupos paga $15 una vez o por grupo? Propuesta: una vez por persona.
- P3: retiro a mitad de mes; propuesta: acceso hasta fin del periodo pagado.
- P4: cómo cobrar varios cupos de invitado en Play; propuesta: cupos numerados (`member_slot_1..5`), validar en consola según `PLAY_TEST_SETUP.md`.
- P5: pase por rodada para invitados ocasionales (futuro / plan B de P4).
- P6: ¿límite de 2 grupos principales por cuenta de líder? Propuesta registrada en `RULES.md` §12 (`MAX_PRINCIPAL_GROUPS_PER_LEADER`, desactivado por defecto hasta confirmar).
- P7: ¿puede un invitado (`guest`) ostentar cargos honoríficos del club (`R3`)? Propuesta registrada en `RULES.md` §12 (`forbidGuestClubRoles = false` por defecto hasta confirmar).

## Supuestos que conviene verificar
- Alcance real de BLE en moto con casco (la teoría dice 100–300 m; el campo puede dar mucho menos).
- Soporte de BLE extendido y Coded PHY en los teléfonos de los usuarios.
- Tarifas y reglas vigentes de Google Play para suscripciones.
- Requisitos legales por país sobre datos de salud y ubicación.

## Descartado o aplazado
| Tema | Estado | Motivo |
|---|---|---|
| iOS | Descartado por ahora | Restricciones de BLE en segundo plano |
| Servidor sin cuentas y gratis para siempre | Descartado | Cambió el modelo a suscripción |
| Voz en streaming continuo | Aplazado | Muy exigente para mesh BLE con muchos nodos |
| LoRa/Meshtastic | Aplazado | Requiere hardware adicional |
| Google Nearby como mesh principal | Descartado como principal | No hace multi-salto por sí mismo |
| Mapbox | Descartado | Límites de licencia offline |

## Glosario
- **Líder:** permiso de administración (paga $15/mes). No es un cargo.
- **Cargo:** etiqueta del club (Presidente, Tesorero, etc.).
- **Estatus:** full member, prospecto o invitado.
- **Rango:** nivel por rodadas válidas (Novato a Leyenda).
- **Rol de rodada:** función temporal (Scout, Sweep, Capitán de rodada).
- **Rodada:** salida con lista cerrada de participantes; única ventana en que existe mesh, mapa compartido y voz.
- **Credencial:** certificado firmado, con vencimiento, que prueba pertenencia y pago, verificable sin internet.
- **Manifiesto de rodada:** lista firmada de grupos y participantes autorizados.
- **Paquete de emergencia:** datos mínimos de identificación, seguro, salud y contactos, cifrados para líderes.

## Cómo usar este archivo
1. Toda decisión nueva se agrega como fila en "Decisiones confirmadas" con fecha y motivo.
2. Si una decisión cambia otra, se anota cuál reemplaza (como D16).
3. Los pendientes se mueven a decisiones cuando se resuelven, y se refleja el cambio en RULES.md.
