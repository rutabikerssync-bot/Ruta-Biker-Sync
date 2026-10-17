# Guion de Pruebas de Campo BLE — Fase 0 (Ruta Biker Sync)

Versión 1.0 · 2026-10-17
Referencia: `ROADMAP.md` (Fase 0), `DESIGN.md` (§3), `BACKEND_LICENSING.md` (§7b), `CHECKLIST.md` (A3, E1–E3).

---

## 1. Objetivo y Criterios de Salida Medibles

Validar en condiciones reales de rodada (teléfono en soporte de manillar o bolsillo de chamarra, casco puesto con intercomunicador Bluetooth activo A2DP/HFP) el alcance, porcentaje de entrega de heartbeats (cada 3 s) y comportamiento de reenvío multi-salto con TTL de la app mínima de prueba BLE (`BleFieldTestScreen`).

| Criterio (`ROADMAP.md` Fase 0) | Umbral mínimo aceptable | Acción si no se cumple |
|---|---|---|
| **% de heartbeats entregados** (formación normal, 30–80 m entre motos) | **≥ 85 %** extremo a extremo (Líder ↔ Cierre/Sweep con 4–5 riders) | Ajustar ventana de advertising/scan o exigir BLE Extended / Coded PHY |
| **Alcance por salto individual (1 hop)** en carretera abierta | **≥ 80 m** en BLE Legacy (1M PHY) · **≥ 180 m** en BLE Extended / Coded PHY | Aumentar `DEFAULT_TTL` de 4 a 5 y activar fallback híbrido con Nearby Connections |
| **Detección de pérdida sin falsas alarmas** | Alerta tras **4–5 latidos perdidos (~12–15 s)** y reconexión con histéresis de **2 latidos** | Ajustar `LOST_THRESHOLD_BEATS` en `MeshRelayEngine.kt` |
| **Sin bucles de inundación** | `duplicatesDropped` absorbe ecos; cada nodo retransmite **máximo 1 vez** por `(shortId, seq)` | Verificado por test unitario y contador en vivo |

---

## 2. Equipamiento Requerido para la Prueba de Campo

1. **4 a 5 teléfonos Android** de al menos **3 marcas distintas** (ej. Samsung Galaxy, Xiaomi/POCO/Redmi, Motorola o Google Pixel) con Android 8.0+ (`minSdk 26`).
2. **Motos y cascos reales** con al menos 2 intercomunicadores Bluetooth emparejados por A2DP/HFP para comprobar coexistencia en la banda de 2.4 GHz.
3. APK Debug de Fase 0 instalada en todos los teléfonos (`.\gradlew.bat assembleDebug`).

---

## 3. Protocolo Paso a Paso

### Escenario A — Prueba Estática de Alcance por Salto (Línea de Vista)
1. Abrir la app en 2 teléfonos, seleccionar **`Hardware BLE Real`** y **`TTL 2`**.
2. Pulsar **`INICIAR PRUEBA MESH`** en ambos.
3. Medir `% Heartbeats entregados` durante **3 minutos (60 latidos)** a:
   - **50 m**
   - **100 m**
   - **200 m**
   - **300 m**
4. Al terminar cada distancia, pulsar **`DETENER PRUEBA`** y **`EXPORTAR REPORTE JSON`**, copiando el bloque JSON en `field-tests/FIELD_TEST_REPORT.md`.

### Escenario B — Convoy de 4 a 5 Motos en Movimiento (Reenvío Multi-Salto con TTL)
1. Asignar posiciones de marcha:
   - **Moto #1 (Líder / Punta)**
   - **Moto #2, #3, #4 (Cuerpo del grupo, separadas 40–60 m entre sí)**
   - **Moto #5 (Sweep / Cierre, a 160–250 m del Líder)**
2. En los 5 teléfonos:
   - Activar **`Hardware BLE Real`** y **`TTL 4`**.
   - Verificar que el modo de ahorro de batería agresivo del fabricante esté desactivado para la app.
   - Pulsar **`INICIAR PRUEBA MESH`**.
3. Rodar durante **15 minutos** a velocidad constante (60–90 km/h) incluyendo curvas o desniveles.
4. En el minuto 10, el rider #5 (**Sweep**) pulsa **`SOS`** (botón de ≥72 dp con guantes puestos) y verifica en cuántos segundos se refleja en el teléfono #1 (**Líder**).

### Escenario C — Partición en Islas y Reconexión (Histéresis)
1. Durante la marcha, hacer que las motos #4 y #5 se retrasen deliberadamente (>400 m) durante **45 segundos** hasta que los teléfonos #1–#3 registren pérdida (>15 s sin latidos) e incrementen `islandEvents`.
2. Reagrupar el convoy a <60 m entre motos y comprobar que tras **2 latidos consecutivos (~6 s)** el estado vuelve a `✓ Mesh conectada`.
3. Al finalizar la rodada, pulsar **`DETENER PRUEBA`** y **`EXPORTAR REPORTE JSON`** en los 5 teléfonos.
