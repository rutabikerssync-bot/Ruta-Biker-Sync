# PROJECT — Ruta Biker Sync

Versión 1.0 · 2026-10-16

## Visión
Una app para grupos de moteros que crea una red mesh entre sus teléfonos durante las rodadas, para verse en un mapa en tiempo real, comunicarse por clips de voz, seguir rutas sin cobertura y recibir ayuda rápida en una emergencia.

## Problema
- Los grupos se estiran y pierden gente en carretera.
- En zonas sin señal las apps de mapas y mensajería dejan de funcionar.
- No hay un control claro de quién pertenece al grupo ni de los datos de emergencia de cada rider.
- Los clubes no tienen una forma de reconocer la trayectoria de sus miembros.

## Público objetivo
Clubes y grupos moteros (MC, rodadas turísticas, grupos de aventura), con líderes que organizan salidas y miembros que las siguen. Mercado mundial; idiomas iniciales: español, inglés y portugués.

## Propuesta de valor
1. Mesh entre teléfonos sin depender de la red celular.
2. Mapa en vivo con avatares, y aviso por voz cuando alguien sale de la mesh.
3. Ruta trazada con antelación, guardada offline y compartida con el grupo, con alerta de desvío.
4. Clips de voz solo durante rodadas, que se reproducen y se borran.
5. Perfil con avatar, ficha de emergencia y bóveda de documentos personal.
6. Estructura de club: cargos, estatus, rangos e insignias.

## Alcance

**Dentro:** Android, grupos, cuentas líder y miembro, rodadas, mesh BLE, mapa offline, ruteo offline, voz por clips, SOS, rangos e insignias, suscripciones.

**Fuera (por ahora):** iOS, voz en streaming continuo, backend con datos de ubicación, red social abierta, marketplace de rutas, integración LoRa (opcional futura).

## Principios
1. **Privacidad primero:** la ubicación y la voz solo existen durante una rodada y no se guardan en servidor.
2. **Consentimiento explícito** para entrar a un grupo y para compartir datos de emergencia.
3. **Funciona sin internet** durante la rodada.
4. **Diseñado para no distraer:** modo guantes, voz y botones grandes.
5. **Datos mínimos** en el backend.
6. **Pagos claros:** cada quien paga lo suyo y el líder ve solo el estado, no datos de pago.

## Stack
| Área | Elección |
|---|---|
| App | Android nativo, Kotlin + Jetpack Compose |
| Mesh de posición | BLE advertising (extendido si hay soporte), reenvío con TTL |
| Voz y transferencias | Nearby Connections / L2CAP, clips Opus 8–12 kbps |
| Mapas | MapLibre + tiles de OpenStreetMap (PMTiles/MBTiles) |
| Ruteo offline | BRouter |
| Voz sintética | TextToSpeech de Android |
| Datos locales | Room/SQLite cifrado |
| Backend | API + PostgreSQL + Play Billing (ver BACKEND_LICENSING.md) |
| Cifrado | Libsodium/Tink, Ed25519 para credenciales |

Código **cerrado** (no open source). Mapas OSM requieren atribución.

## Actores
| Actor | Descripción |
|---|---|
| Miembro | Descarga gratis; paga $2.50/mes por grupo |
| Líder | Paga $15/mes; administra grupo y rodadas; hasta 3 por grupo |
| Invitado | Acepta ser agregado por un líder; paga $2.50/mes por grupo |
| Full member / Prospecto | Pertenecen a un solo grupo; $2.50/mes |

## Métricas de éxito
- Activación: % de líderes que crean grupo y convocan una rodada en 14 días.
- Retención: % de suscripciones renovadas al mes 3.
- Uso: rodadas completadas por grupo al mes.
- Seguridad: tiempo hasta que el grupo recibe un SOS.
- Calidad mesh: % de heartbeats entregados en pruebas de campo.
- Ingreso mensual recurrente (MRR) y costo por cupo.

## Riesgos principales
| Riesgo | Mitigación |
|---|---|
| Alcance BLE real menor al esperado | Pruebas de campo tempranas, capa de transporte abstracta |
| Cobro de cupos múltiples en Google Play | Validar en consola antes de la Fase 2 |
| Datos de salud y privacidad | Consentimiento, cifrado, asesoría legal |
| Ahorro de batería de fabricantes | Foreground service, guía de configuración |
| Clonación de nombres y logos | Unicidad por país y revisión manual |
| Piratería por ser app de pago | Credenciales firmadas, Play Integrity, ofuscación |

## Documentos relacionados
BUSINESS_MODEL.md · DESIGN.md · RULES.md · ROADMAP.md · TASKS.md · CHECKLIST.md · MEMORY.md · BACKEND_LICENSING.md
