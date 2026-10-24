# FIELD_TEST_PROTOCOL — Protocolo Operativo de Prueba de Campo BLE (Bloque 3.6)

Versión 1.0 · 2026-10-24  
Referencia: `ROADMAP.md` (Fase 0 y Fase 3), `DESIGN.md` (§3, §4, §11), `CHECKLIST.md` (E1–E5) y `docs/FIELD_TEST_PLAN.md`.

---

## 1. Objetivo y Criterios Medibles de Aprobación

Validar en condiciones reales de rodada (motos en marcha, cascos puestos, teléfonos en soporte de manillar o bolsillo y coexistencia con intercomunicador Bluetooth) si la mesh BLE de **Ruta Biker Sync** mantiene conectado un convoy de motos, mide los saltos extremo a extremo y entrega las métricas necesarias para decidir si el BLE legacy es suficiente o si se exige BLE extendido / Coded PHY.

| Criterio | Umbral Mínimo Aceptable | Decisión de Ingeniería |
|---|---|---|
| **% Heartbeats Entregados** (en convoy) | **≥ 85.0 %** extremo a extremo (#3 Sweep ↔ #1 Líder) | Si es ≥ 85 %, el transporte BLE Legacy es viable para producción. Si es < 85 %, exigir BLE Extendido / Coded PHY o fallback híbrido Nearby Connections. |
| **Reenvío Multi-Salto con TTL** | **Saltos promedio ~ 2.0** en convoy de 3 nodos (#3 → #2 → #1) | Confirma que el nodo intermedio (#2 Redmi C2) retransmite con decremento de TTL sin bucles ni duplicados. |
| **Detección de Pérdida** | Pérdida registrada tras **5 latidos sin señal (~15 s)** | Evita falsas alarmas en curvas cerradas o túneles breves. |
| **Histéresis de Reconexión** | Regreso a `ONLINE` tras **2 latidos consecutivos (~6 s)** | Evita parpadeo e interrupciones en la locución TTS. |
| **Supervivencia en Segundo Plano** | 0 cierres de `RideService` con pantalla apagada durante 2 h | Valida la guía de batería OEM (HyperOS, HiOS y MIUI). |

---

## 2. Asignación del Banco de Teléfonos (3 Terminales Confirmados)

| # | Dispositivo | Hardware / Chipset | Sistema Operativo | Rol en la Prueba | ShortID Asignado | Posición en Marcha |
|---|---|---|---|---|---|---|
| **1** | **Xiaomi Redmi Note 13 Pro** | Qualcomm Snapdragon (BLE 5 Extendido + Coded PHY) | HyperOS (Android 13/14) | **Líder / Punta** | **ID 1** | Primera moto (cabeza del grupo) |
| **2** | **Redmi C2** | Procesador de gama de entrada (BLE Legacy 1M) | Android 10/11 (MIUI Go) | **Centro / Relevo** | **ID 2** | Segunda moto (nodo puente intermedio) |
| **3** | **Tecno Spark 10C** | Unisoc T606 (BLE 5 Legacy) | HiOS 8.6 (Android 12) | **Sweep / Cierre** | **ID 3** | Tercera moto (cola del grupo) |

---

## 3. Preparación Previa en Casa/Taller (30 minutos)

Antes de salir a la carretera, completar obligatoriamente esta lista de verificación:

### 3.1 Instalación del APK de Campo
- Generar el APK del flavor específico de pruebas con logs y botón de exportación:
  ```powershell
  cd d:\Ruta-Biker-Sync\android
  .\gradlew.bat assembleFieldTestDebug
  ```
- El instalador se genera en:
  `android/app/build/outputs/apk/fieldTest/debug/app-fieldTest-debug.apk`
- Instalar el **mismo archivo APK** en los 3 teléfonos mediante cable USB (`adb install`) o transfiriéndolo por almacenamiento local.

### 3.2 Permisos de Sistema
Al abrir la app por primera vez en cada teléfono, autorizar:
- **Dispositivos cercanos / Bluetooth** (Escanear y Anunciar sin restricciones).
- **Ubicación**: Seleccionar **"Permitir todo el tiempo"** (Ubicación en segundo plano).
- **Notificaciones**: Permitir (necesario para el Foreground Service persistente).

### 3.3 Configuración de Batería OEM (Crítico)
Pulsar el botón **"OEM"** en la cabecera de `FieldTestScreen` en cada teléfono y aplicar los pasos:
- **Redmi Note 13 Pro (HyperOS):**
  1. Ajustes -> Aplicaciones -> Ruta Biker Sync -> Batería -> **"Sin restricciones"**.
  2. Activar **"Inicio automático"** (Autostart).
  3. En la vista de aplicaciones recientes, poner el **candado** a la app.
- **Redmi C2 (MIUI / Android Go):**
  1. Batería -> Ahorro de energía -> Seleccionar app -> **"Sin restricciones"**.
  2. Permitir ejecución en segundo plano sin suspensión.
- **Tecno Spark 10C (HiOS / Power Marathon):**
  1. Abrir aplicación Phone Master -> Gestión de inicio automático -> Autorizar Ruta Biker Sync.
  2. Ajustes -> Batería -> Optimización -> Seleccionar **"No optimizar"**.
  3. Desactivar bloqueo de Bluetooth en reposo.

### 3.4 Sincronización y Carga
- Cargar los 3 teléfonos al **100 %**.
- Asegurar que la hora esté configurada en **"Automática / Proporcionada por la red"** (para correlación exacta de timestamps).
- En cada teléfono, seleccionar su ID correspondiente en la pantalla (`#1 Punta`, `#2 Medio` o `#3 Cierre`).

---

## 4. Secuencia de Pruebas de Campo (2 horas)

> ⚠️ **REGLA DE ORO DE SEGURIDAD:** Nadie manipula el teléfono mientras conduce. Todas las configuraciones y toques en pantalla se realizan con las motos detenidas en lugar seguro.

```
       [#1 Punta]             [#2 Medio]             [#3 Cierre]
  Redmi Note 13 Pro            Redmi C2            Tecno Spark 10C
      (ID = 1)                 (ID = 2)               (ID = 3)
         │                        │                      │
         │◄──── 40–60 m ─────────►│◄──── 40–60 m ───────►│
         │                        │                      │
         └───────────── Distancia total: 80–120 m ───────┘
```

---

### B1 — Alcance Estático en Línea de Vista (25 min)
*Objetivo: Medir el alcance máximo por salto directo sin obstáculos ni motos.*
1. Colocar el teléfono #1 (**Redmi Note 13 Pro**) fijo sobre un soporte o banco.
2. La persona con el teléfono #3 (**Tecno Spark 10C**) se aleja a pie con cinta o GPS:
   - **25 metros** (esperar 2 minutos = 40 latidos de 3 s).
   - **50 metros** (esperar 2 minutos).
   - **100 metros** (esperar 2 minutos).
   - **150 metros** (esperar 2 minutos).
3. Verificar en la tarjeta de métricas en vivo el `% Entrega Heartbeats`.
4. Anotar a qué distancia el porcentaje cae por debajo del 85 %.

---

### B2 — Alcance Estático con Moto y Cuerpo Humano (20 min)
*Objetivo: Medir la atenuación que causan el cuerpo del rider, el casco y el chasis metálico.*
1. Rider #1 sentado en su moto con casco puesto y teléfono en el **soporte de manillar**.
2. Rider #3 sentado en su moto con casco puesto y teléfono en el **bolsillo de la chamarra**.
3. Medir a **50 metros** y **100 metros** durante 3 minutos cada distancia.
4. Repetir invirtiendo la orientación de las motos (espalda con espalda para medir bloqueo corporal directo).

---

### B3 — Coexistencia con Intercomunicador Bluetooth (15 min)
*Objetivo: Comprobar si el audio A2DP/HFP en la banda de 2.4 GHz degrada la mesh BLE.*
1. Emparejar el intercomunicador de casco (Sena, Cardo u otro) al Redmi Note 13 Pro.
2. Iniciar reproducción de música continua hacia el casco.
3. Observar si el contador de `Paquetes Recibidos` en la pantalla sigue incrementando al ritmo de 1 latido cada 3 s.
4. Simular un evento para verificar que la locución TTS de alerta atenúa la música (*audio ducking*) sin cortar la conexión.

---

### B4 — Movimiento con 2 Motos en Carretera (30 min)
*Objetivo: Validar la estabilidad del enlace BLE bajo desplazamiento y vibración.*
1. Salen las motos #1 y #3 en una vía de poco tráfico y velocidad controlada.
2. Mantener separación visual de seguridad (~40 a 60 metros).
3. Realizar tres pasadas de 5 minutos cada una:
   - Pasada 1: **30 km/h** (urbano/tránsito lento).
   - Pasada 2: **60 km/h** (vía secundaria/curvas).
   - Pasada 3: **90 km/h** (carretera abierta, donde sea legal y seguro).
4. Comprobar que no se disparen falsas alarmas de pérdida durante curvas o cambios de rasante.

---

### B5 — Convoy de 3 Motos con Reenvío Multi-Salto (30 min)
*Objetivo: Verificar el reenvío con TTL a través del nodo intermedio y la recuperación de islas.*
1. Salen las 3 motos en formación escalonada:
   - Moto #1 (**Líder**, Redmi Note 13 Pro) en cabeza.
   - Moto #2 (**Centro**, Redmi C2) a 40–50 m del Líder.
   - Moto #3 (**Sweep**, Tecno Spark 10C) a 40–50 m del Centro (separación total Líder ↔ Sweep: 80–100 m).
2. Rodar durante 15 minutos en convoy continuo.
3. **Comprobación de Saltos:** En el teléfono #1 (**Líder**), el campo `Saltos Promedio` debe registrar valores entre **1.8 y 2.0**, confirmando que los paquetes del Sweep están saltando a través del Redmi C2.
4. **Prueba de Isla y Reconexión (Minuto 10):**
   - El Sweep (Moto #3) reduce la marcha y se queda atrás deliberadamente (>250 m) durante **45 segundos**.
   - En los teléfonos #1 y #2 debe registrarse la pérdida pasados los 15 segundos (5 latidos) e incrementarse `Islas Detectadas: 1`.
   - El Sweep acelera y se reintegra al convoy (<50 m del Centro).
   - Comprobar que tras **2 latidos consecutivos (~6 s)** el estado vuelve automáticamente a verde (`✓ En contacto mesh`) sin duplicar locuciones de voz.

---

## 5. Post-Prueba y Extracción de Datos (20 minutos)

Al concluir el recorrido y con las motos detenidas:
1. En cada uno de los 3 teléfonos, pulsar el botón rojo **"DETENER PRUEBA BLE"** y confirmar en los 3 segundos.
2. Pulsar el botón azul **"EXPORTAR MÉTRICAS (JSON)"**.
3. La app copiará el JSON automáticamente al portapapeles y abrirá el menú de compartir.
4. Enviar los 3 archivos/textos (vía WhatsApp, Telegram, correo o Bluetooth) a tu estación de trabajo.
5. Pegar los bloques JSON en el chat para el análisis automático de ingeniería.

---

## 6. Formato del Reporte JSON Exportado

Cada teléfono genera un bloque con esta estructura exacta:

```json
{
  "timestampEpochMs": 1729768500000,
  "timestampIso": "2026-10-24T11:15:00Z",
  "deviceManufacturer": "Xiaomi",
  "deviceModel": "23090RA98G",
  "androidRelease": "14",
  "androidSdkInt": 34,
  "assignedRole": "Punta (#1 Líder)",
  "localNodeShortId": 1,
  "testDurationSeconds": 1820,
  "testDurationFormatted": "30:20",
  "transportName": "BleAdvTransport",
  "isSimulatedTransport": false,
  "connectedPeersCount": 2,
  "lostPeersCount": 0,
  "totalPacketsEmitted": 605,
  "totalPacketsReceivedValid": 1184,
  "heartbeatsDeliveredPct": 94.2,
  "averageHops": 1.94,
  "islandEventsDetected": 1,
  "duplicatesDropped": 342,
  "invalidAuthDropped": 0
}
```

---

## 7. Criterio de Decisión Técnica para el Cierre de Fase 0 / Fase 3

Tras analizar los 3 JSON:
- **Si `% Entrega` ≥ 85 % y `Saltos Promedio` ~ 2.0:**  
  ✅ **Transporte BLE Legacy APROBADO.** Se mantiene `BleAdvTransport` como estándar base compatible con todos los terminales sin exigir hardware especializado.
- **Si `% Entrega` < 85 % en carretera abierta:**  
  ⚠️ **Transporte CONDICIONADO.** Se configurará la app para exigir **BLE 5 Extended Advertising / Coded PHY** en dispositivos compatibles y habilitar el fallback híbrido de **Nearby Connections** en teléfonos con BLE 4.2/Legacy.
