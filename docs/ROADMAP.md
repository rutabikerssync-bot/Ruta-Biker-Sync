# ROADMAP — Ruta Biker Sync

Versión 1.0 · 2026-10-16
Las duraciones son estimaciones iniciales para un equipo pequeño y deben ajustarse tras cada fase.

## Fase 0 — Validación (2 a 3 semanas)
**Meta:** eliminar los riesgos más grandes antes de construir.
- Prueba en consola de Google Play del cobro de cupos múltiples (P4).
- Prueba de campo de BLE: 3 a 4 teléfonos de marcas distintas, en moto y con casco.
- Entrevistas con 5 a 10 líderes de clubes sobre precio y reglas.
- Revisión legal inicial (privacidad, datos de salud, términos).
- Revisión previa de políticas de Google Play (ubicación en segundo plano, Bluetooth, salud) y plan de contingencia.
- **Criterios de salida, medibles:**
  - Cobro: dos cupos de invitado comprados a la vez por una cuenta de prueba, o decisión de pasar al pase por rodada.
  - Mesh: con 4–5 riders reales, % de heartbeats entregados y alcance real medidos por modelo de teléfono; se fija el mínimo aceptable (por ejemplo, la mesh se mantiene conectada a la distancia típica de un grupo en marcha) antes de comprometer el resto del desarrollo.
  - Si el BLE legacy no alcanza, evaluar exigir BLE extendido como requisito mínimo o adelantar LoRa.
- **Salida:** decisión de modelo de cobro y de transporte mesh, con números reales.

## Fase 1 — Cuentas y grupos (6 a 8 semanas)
- Registro e inicio de sesión, perfil con avatar.
- Grupos con nombre y logo únicos por país.
- Líderes (hasta 3), estatus, cargos, invitaciones con consentimiento.
- Backend base y base de datos.
- **Salida:** un club puede armarse completo en la app.

## Fase 2 — Suscripciones y credenciales (6 a 8 semanas)
- Play Billing: líder $15, cupos $2.50.
- Verificación de compras, RTDN, conciliación diaria.
- Credenciales firmadas con vencimiento, semáforo de pagos, alarmas.
- **Salida:** cobro funcionando con un piloto cerrado.

## Fase 3 — Rodada con mesh (8 a 10 semanas)
- Rodadas con lista de participantes, manifiesto firmado y dos grupos.
- Mesh BLE de posición, mapa en vivo con avatares.
- Pérdida y reconexión con voz sintética.
- Servicio en primer plano y manejo de batería.
- **Salida:** rodada real con un club piloto.

## Fase 4 — Rutas offline (6 a 8 semanas)
- Trazado con BRouter, importación GPX.
- Descarga de corredor de mapa, compartir ruta por mesh.
- Alerta de desvío.
- **Salida:** rodada en zona sin cobertura.

## Fase 5 — Voz y seguridad (6 a 8 semanas)
- Voz por clips que se borra tras reproducir.
- SOS y detección de caída.
- Ficha y paquete de emergencia con acceso sellado y registro.
- **Salida:** flujo de emergencia probado de extremo a extremo.

## Fase 6 — Gamificación y documentos (4 a 6 semanas)
- Rangos automáticos y validación anti-trampa de rodadas.
- Insignias (rango, logros, honor), roles de rodada.
- Bóveda de documentos con alarmas.
- Estadísticas y repetición de rodada.

## Fase 7 — Lanzamiento (4 semanas)
- Español, inglés y portugués.
- Política de privacidad, términos, borrado de cuenta.
- Ficha de Play Store, pruebas cerradas y abiertas.
- Soporte y proceso de denuncias.

## Después (backlog)
- Transferencia de grupo completa y moderación avanzada.
- Pase por rodada para invitados ocasionales.
- Plan anual.
- Integración LoRa/Meshtastic para zonas remotas.
- Voz en streaming continuo.
- iOS (si el negocio lo justifica).

## Hitos y criterios de paso
| Hito | Criterio |
|---|---|
| M0 | Cobro y mesh validados |
| M1 | Un club completo en la app |
| M2 | Primer pago real verificado |
| M3 | Rodada real con mesh |
| M4 | Rodada sin cobertura exitosa |
| M5 | SOS probado con acceso a emergencia |
| M6 | Rangos e insignias activos |
| M7 | Publicación en Play |
