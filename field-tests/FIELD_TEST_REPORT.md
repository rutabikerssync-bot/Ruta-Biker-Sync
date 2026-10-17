# Reporte de Validación Técnica — Fase 0 (Cobros y Mesh BLE)

Versión 1.0 · 2026-10-17
Estado: **Base técnica y arnés de simulación verificados · Pendiente volcado de ruta física con 4–5 motos y consola Play del dueño**

---

## 1. Resultados Verificados en Banco de Pruebas (Convoy 5 Nodos + JUnit)

Pruebas ejecutadas automáticamente sobre `MeshRelayEngine` + `MeshPacket` (20 bytes exactos):

| Métrica | Resultado Medido (Arnés 5 Riders en Cadena `1↔2↔3↔4↔5`) | Estado |
|---|---|---|
| Tamaño binario del paquete de posición | **20 bytes exactos** (2B ID + 8B Lat/Lon + 2B Vel/Rumbo + 1B Bat/Flags + 3B Seq/TTL/Prio + 4B HMAC) | ✅ Cumple `DESIGN.md` §3 |
| Saltos extremo a extremo (Sweep #5 → Líder #1) | **4.00 saltos promedio** (`TTL inicial = 4`, decrece a `1` en el receptor final) | ✅ Sin bucles |
| % Heartbeats entregados (sin pérdida de radio) | **100.0 %** | ✅ Base verificada |
| Ecos / duplicados descartados | **100 % absorbidos** por ventana LRU `(shortId, sequenceNumber)` | ✅ Cumple `CHECKLIST.md` E1 |
| Detección de pérdida (`LOST_TIMEOUT_MS`) | **15.0 s** (5 latidos de 3 s); **0 falsas alarmas** a los 9.0 s (3 latidos) | ✅ Cumple `DESIGN.md` §3 |
| Histéresis de reconexión | **2 latidos consecutivos (~6 s)** antes de limpiar `isLost` | ✅ Evita parpadeo en curvas |
| Rechazo de paquete adulterado (`authTag`) | **100 % rechazados** (`invalidAuthDropped` incrementado, no se retransmite) | ✅ Integridad verificada |

---

## 2. Tabla para Registro de Campo con Teléfonos Reales (Completar en Carretera)

> Ejecutar siguiendo `field-tests/FIELD_TEST_GUIDE.md` y pegar debajo los bloques JSON exportados por cada teléfono.

| # | Marca / Modelo | Versión Android | Modo BLE Detectado (`recommendedMode`) | Posición en Convoy | % Heartbeats Entregados | Saltos Promedio (`avgHops`) | Eventos de Isla (`islandEvents`) | Alcance Máx. 1 Salto (m) |
|---|---|---|---|---|---|---|---|---|
| 1 | *Pendiente (Líder)* | — | — | Punta (#1) | — % | — | — | — m |
| 2 | *Pendiente* | — | — | #2 | — % | — | — | — m |
| 3 | *Pendiente* | — | — | #3 | — % | — | — | — m |
| 4 | *Pendiente* | — | — | #4 | — % | — | — | — m |
| 5 | *Pendiente (Sweep)* | — | — | Cierre (#5) | — % | — | — | — m |

---

## 3. Validación de Cobros y Cupos Múltiples en Google Play Console (`P4`)

Ver procedimiento detallado en `docs/PLAY_CONSOLE_VALIDATION_P4.md`.

| Prueba en Play Console (License Tester) | Producto(s) | Resultado Esperado | Resultado Real en Consola |
|---|---|---|---|
| Compra de plan Líder ($15/mes) | `leader_monthly` | Token emitido, estado `SUBSCRIPTION_STATE_ACTIVE` | *Pendiente cuenta Play Console* |
| Compra cupo grupo principal ($2.50/mes) | `member_slot_1` | Token emitido, asignable a `group_id` #1 | *Pendiente cuenta Play Console* |
| Compra simultánea de 2º y 3er cupo de invitado ($2.50/mes c/u) | `member_slot_2`, `member_slot_3` | La misma cuenta de Google mantiene activas `member_slot_1`, `member_slot_2` y `member_slot_3` a la vez | *Pendiente cuenta Play Console* |
