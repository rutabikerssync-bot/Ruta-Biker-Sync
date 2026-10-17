# CHECKLIST — Ruta Biker Sync

Versión 1.1 · 2026-10-17
Listas de verificación para revisar antes de cada hito o lanzamiento.

## A. Antes de empezar a programar
- [~] Cobro de cupos múltiples probado en Play Console (P4) *(guion operativo de 15 secciones en `PLAY_TEST_SETUP.md`; pendiente ejecución en cuenta Play Console del dueño)*
- [~] Decisiones P1, P2 y P3 de RULES.md cerradas *(parametrizadas en `BillingPolicyConfig.kt` con propuestas actuales por defecto)*
- [~] Prueba de campo BLE con al menos 3 marcas de teléfono *(app de prueba, simulador de 5 nodos y protocolo operativo de 14 secciones listos en `FIELD_TEST_PLAN.md`; pendiente medición física en carretera)*
- [ ] Revisión legal inicial hecha
- [ ] Nombre comercial verificado (marca, dominios, tienda)

## B. Cuentas y grupos
- [x] Un usuario no puede entrar a un grupo sin aceptar
- [x] Full member y prospecto solo en un grupo
- [x] Invitado en varios grupos con cuota por cada uno
- [x] Máximo 3 líderes por grupo (comprobado en base de datos)
- [x] Nombre único por país, incluso con acentos, símbolos y sustituciones leet
- [x] Logo único por país, incluso con cambios mínimos o de color
- [x] Transferencia solo entre miembros, con espera de 7 días y cancelación
- [x] Solo los líderes cambian cargos y estatus

## C. Cobros
- [ ] Compra, renovación, cancelación y reembolso probados
- [ ] Periodo de gracia de 3 días funciona
- [ ] Notificaciones RTDN idempotentes y conciliación diaria
- [ ] El líder ve semáforo, nunca datos de pago
- [ ] Alarmas de vencimiento (7, 3 y 1 día) llegan
- [ ] Pago vencido implica credencial no renovada y rechazo en la mesh
- [ ] Líder principal sin pago: rol pasa al siguiente en 7 días
- [ ] Tres líderes sin pago: grupo suspendido, historial intacto

## D. Credenciales y seguridad
- [ ] Credenciales firmadas y verificadas sin internet
- [ ] Rotación de claves de firma probada
- [ ] Credencial caducada rechazada en rodada
- [ ] Lista de revocación sincronizada antes de salir
- [ ] Manifiesto de rodada con dos grupos validado
- [ ] Play Integrity y ofuscación activos
- [ ] Mensajes de la mesh cifrados y firmados

## E. Mesh y rodada
- [~] Reenvío con TTL sin bucles ni duplicados *(verificado en simulador unitario `MeshRelayEngine` + `MeshValidationPhase0Test`; pendiente prueba física B5 de `FIELD_TEST_PLAN.md` §5.5)*
- [ ] Pérdida de cobertura detectada sin falsas alarmas en curvas o túneles
- [ ] Un solo anunciador por pérdida
- [ ] Reconexión automática y aviso de regreso
- [ ] Servicio en primer plano sobrevive en Samsung, Xiaomi y otros
- [ ] Modo ahorro de batería bajo 20 %
- [ ] Prueba con 12 a 15 nodos
- [ ] Mesh y voz solo funcionan durante una rodada activa

## F. Rutas
- [ ] Ruta importada o trazada se guarda offline
- [ ] Mapa del corredor funciona sin señal
- [ ] Ruta compartida llega íntegra (hash verificado)
- [ ] Alerta de desvío sin molestar a quien para a propósito
- [ ] Atribución de OpenStreetMap visible

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
- [ ] Rangos sin solapamiento (0–4, 5–19, 20–49, 50–99, 100+)
- [ ] Reglas anti-trampa de rodada validadas
- [ ] Insignias de honor con límite mensual y registro
- [ ] Honor no suma para el rango
- [ ] Fundador es marca permanente, no rango

## J. Privacidad y legal
- [ ] Política de privacidad y términos publicados
- [ ] Consentimientos separados (ubicación, emergencia, notificaciones)
- [ ] Exportación y borrado de cuenta
- [ ] Backend sin ubicación, voz, documentos ni salud
- [ ] Cumplimiento GDPR, LGPD, CCPA revisado por un abogado
- [ ] Declaración de datos de Google Play completada
- [ ] Permiso de ubicación en segundo plano justificado

## K. Calidad y lanzamiento
- [ ] Modo guantes y botones ≥72 dp
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
