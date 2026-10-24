# FIELD_TEST_PLAN — Plan de prueba de campo BLE de Ruta Biker Sync

Versión 1.2 · 2026-10-23
Complementa `field-tests/FIELD_TEST_GUIDE.md` y `FIELD_TEST_REPORT.md` (generados en la Fase 0). Objetivo: medir con teléfonos reales, en moto y con casco, si la mesh BLE sirve para un grupo en marcha, y decidir con números el transporte (legacy, extendido, LoRa).

> **Todos los umbrales de este documento son propuestas** para calibrar con la primera ronda de datos. Ninguno es un resultado medido.

## 1. Preguntas que debe responder

| # | Pregunta | Decide |
|---|---|---|
| F1 | ¿Hasta qué distancia llega el heartbeat con casco y cuerpo en medio? | Alcance real |
| F2 | ¿Se mantiene la mesh en un grupo en marcha (30 a 100 km/h)? | Viabilidad del MVP |
| F3 | ¿Cambia mucho entre advertising **legacy** y **extendido/Coded PHY**? | Si se exige BLE extendido como mínimo |
| F4 | ¿Cuántos saltos útiles hay y cuánta latencia por salto? | TTL por defecto |
| F5 | ¿Cuántas falsas alarmas de "pérdida de cobertura" hay? | Umbral e histéresis |
| F6 | ¿Sobrevive el servicio en primer plano con pantalla apagada en cada marca? | Guía de batería |
| F7 | ¿Cuánta batería consume por hora? | Modo ahorro |
| F8 | ¿Interfiere el intercomunicador (Bluetooth clásico) del casco? | Diseño de audio |
| F9 | ¿Funciona el anunciador único con 5 o más nodos reales? | Algoritmo DESIGN 11 |

## 2. Seguridad primero

- **Nadie manipula el teléfono mientras conduce.** Las pruebas se inician y detienen antes y después del recorrido.
- Un **acompañante no conductor** o un registro automático hace la anotación; si no, se graba y se analiza después.
- Rutas de **poco tráfico**, sin competir ni superar los límites de velocidad. Los 100 km/h solo donde sea legal y seguro.
- Casco homologado, equipo de protección y reglas de formación del grupo acordadas antes.
- Plan de contingencia: punto de reunión, teléfono de emergencia, un vehículo de apoyo si es posible.
- Seguro y permisos de la ruta, si aplican.

## 3. Equipo

### 3.1 Teléfonos del banco de pruebas (Fase 0)

Banco confirmado para la prueba de campo inicial (3 marcas distintas, abarcando gama media-alta, entrada y procesador sin GMS):

| # | Marca y modelo | Sistema Operativo | RAM | ¿BLE 5? | ¿Advertising extendido? | ¿Coded PHY? | Ahorro de batería de fábrica | Rol en la prueba |
|---|---|---|---|---|---|---|---|---|
| 1 | **Xiaomi Redmi Note 13 Pro** | Android 13/14 (HyperOS) | 8/12 GB | Sí | Sí | Sí | Optimización MIUI/HyperOS (desactivar) | Nodo central / líder de prueba |
| 2 | **Tecno Spark 10C** | Android 12 (HiOS / Go) | 4/8 GB | Sí | _por medir en app_ | _por medir en app_ | HiOS Power Marathon (agresivo) | Nodo de gama de entrada |
| 3 | **Huawei Nova 13 Pro** | HarmonyOS / EMUI | 12 GB | Sí | Sí | Sí | Gestión agresiva de EMUI | Nodo mesh / radio BLE |

> ⚠️ **ALERTA CRÍTICA — Google Play Services (GMS) y Play Billing (P4):**
> El **Huawei Nova 13 Pro** carece de servicios de Google Play (GMS). **NO sirve para probar Play Billing (`P4`) ni compras en Play Console.** Su participación queda reservada **exclusivamente para pruebas de radio mesh BLE** (alcance, reenvío TTL multi-salto y supervivencia de servicio en segundo plano bajo EMUI/HarmonyOS).
>
> 📌 **Alcance de la muestra:** La prueba física se ejecutará con estos **3 teléfonos disponibles** (el dueño cuenta además con un **Redmi C2** Android de gama de entrada disponible inmediatamente junto al **Redmi Note 13 Pro** para pruebas locales en par gama media-alta / gama baja). El informe final de pruebas (`FIELD_TEST_REPORT.md`) documentará explícitamente esta limitación metodológica inicial frente a la meta teórica de 5 dispositivos, centrando los resultados en la interoperabilidad cruzada de chipsets (Qualcomm Snapdragon, Unisoc/MediaTek y Kirin).

La app de pruebas debe **mostrar y registrar** las capacidades reales de cada teléfono (extendido, Coded PHY, PHY 2M, máximo de datos anunciados), no solo las del fabricante.

### 3.2 Otros
- Mismo **APK** instalado en todos (misma versión y misma clave de grupo).
- Soporte de manillar o bolsa de depósito; funda; cable de carga.
- Intercomunicadores de al menos 2 marcas si los riders los usan (Sena, Cardo u otros), emparejados con el teléfono.
- Un teléfono "testigo" con GPS en un lugar fijo para la verdad de terreno (opcional).
- Cinta métrica o medidor láser para las pruebas estáticas, y una aplicación de GPS con registro de pista (GPX) en un teléfono aparte de los que miden.

## 4. Variables a controlar

| Variable | Valores |
|---|---|
| Transporte | Legacy · Extendido · Extendido + Coded PHY (si el teléfono lo soporta) |
| Ubicación del teléfono | Bolsillo de chaqueta · Bolsa de depósito · Soporte de manillar · Mochila |
| Casco / intercomunicador | Sin intercom · Con intercom conectado · Con música |
| Velocidad | Parado · 30 · 60 · 90 km/h |
| Entorno | Campo abierto · Urbano · Autopista · Curvas de montaña · Túnel · Lluvia ligera (si se da) |
| Nodos | 2 · 3 · 5 |
| Formación | Fila · Escalonada · Grupo disperso |

No se cruzan todas las variables: se sigue el orden de la sección 5 y se repite solo lo necesario.

## 5. Secuencia de pruebas

### B0. Banco (30 min)
Teléfonos sobre una mesa, sin moto. Comprueba que el APK, los permisos y los registros funcionan; que los relojes de los teléfonos están sincronizados; y que cada teléfono muestra sus capacidades BLE.
**Salida:** tabla 3.1 completa.

### B1. Alcance estático al aire libre
Dos teléfonos, sin moto ni casco. Se alejan en pasos: **25, 50, 100, 150, 200, 300 m**. En cada punto, 2 minutos de registro. Repetir con legacy y extendido.
**Mide:** % de heartbeats recibidos (se esperan 40 en 2 min con latido de 3 s), RSSI medio.
**Salida:** curva distancia → % por teléfono y transporte.

### B2. Alcance estático con moto y cuerpo
Igual que B1, con el rider sentado en la moto, casco puesto y el teléfono en cada una de las 4 ubicaciones. Solo hasta 100 y 150 m.
**Salida:** pérdida de señal por ubicación del teléfono (F1).

### B3. Intercomunicador (F8)
Igual que B2, con el intercom conectado y reproduciendo audio vs sin intercom.
**Salida:** diferencia en % de entrega.

### B4. Movimiento, 2 motos
Recorrido de ida y vuelta, 30, 60 y 90 km/h (según legalidad y seguridad), a separación de 25, 50 y 100 m medidas por GPS.
**Mide:** % de entrega, latencia, pérdidas.

### B5. Convoy de 5 motos (F2, F4, F9)
Fila y formación escalonada, 15 a 30 minutos por modo. Orden fijo con el líder primero y el cierre último. El teléfono del cierre emite `heartbeat` y `posición`; se miden **saltos** y latencia de extremo a extremo.
**Mide:** % de entrega, saltos promedio, islas (veces que la mesh se parte), pérdidas y reconexiones.

### B6. Pérdida y reconexión forzadas (F5, F9)
En el convoy, una moto se detiene o se aleja a propósito durante 1 minuto y vuelve. Repetir 5 veces.
**Mide:** tiempo hasta detectar la pérdida, **número de anuncios** por evento (debe ser 1), falsas alarmas durante curvas y túneles.

### B7. Servicio en segundo plano (F6)
Cada teléfono, con la app en rodada y **pantalla apagada**, 2 horas en el bolsillo o la moto. Revisar si el servicio siguió vivo.
**Salida:** tabla marca → sobrevive sí/no, con qué ajustes.

### B8. Batería (F7)
Medir el porcentaje al inicio y al final de B5 y B7. Normalizar a %/hora, con GPS y mesh activos.

### B9. Entornos difíciles
Túnel, zona urbana densa, curvas de montaña, lluvia ligera si se da. 10 minutos cada una.

## 6. Datos a registrar

La app de pruebas debe guardar por cada paquete: marca de tiempo (UTC), ID del emisor, secuencia, TTL, saltos, RSSI, transporte, y por cada teléfono sus capacidades y batería. Exportar a JSON y a CSV.

**Plantilla de resultados por prueba**

| Prueba | Fecha | Teléfono(s) | Transporte | Ubicación | Velocidad | Entorno | % entrega | RSSI medio | Saltos prom. | Latencia p95 | Islas | Falsas alarmas | Notas |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|

Guardar los archivos crudos en `field-tests/data/AAAA-MM-DD/` (sin ubicaciones personales si se publican).

## 7. Criterios de aceptación (propuestas)

| Métrica | Umbral propuesto |
|---|---|
| % de heartbeats entre vecinos a 50 m con casco y movimiento | ≥ 90 % |
| % a 100 m | ≥ 70 % |
| La mesh de 5 motos se mantiene unida en fila a 60 km/h | ≥ 90 % del tiempo |
| Latencia de extremo a extremo con 4 saltos | ≤ 3 s (p95) |
| Falsas alarmas de pérdida | ≤ 1 por 30 min |
| Anuncios duplicados por evento de pérdida | 0 |
| Consumo de batería con mesh y GPS | ≤ 12 %/h en un teléfono medio |
| Servicio vivo tras 2 h con pantalla apagada | En todas las marcas probadas, o con ajustes documentados |

## 8. Cómo se decide con los resultados

| Resultado | Decisión |
|---|---|
| Legacy cumple los umbrales | Seguir con legacy como base; extendido como mejora |
| Legacy no cumple, extendido sí | **Exigir BLE extendido** como requisito mínimo o degradar funciones en teléfonos sin él (decisión de producto: se excluye gama baja) |
| Ninguno cumple a 50 m | Revisar ubicación recomendada del teléfono, potencia de transmisión y frecuencia de heartbeat; si no basta, **adelantar LoRa/Meshtastic** |
| Un fabricante mata el servicio | Guía específica y detección de servicio muerto; valorar excluirlo del soporte oficial |
| El intercom degrada la mesh | Ajustar la separación de canales y documentar casos incompatibles |

## 9. Lista de verificación antes de salir

- [ ] Mismo APK, misma versión y misma clave de grupo en todos.
- [ ] Permisos concedidos (ubicación, Bluetooth, notificaciones) y **optimización de batería desactivada** según la marca.
- [ ] Relojes sincronizados y zona horaria igual.
- [ ] Baterías al 100 % o con carga conocida.
- [ ] Capacidades BLE de cada teléfono registradas.
- [ ] Ruta, hora de salida, punto de reunión y teléfono de contacto repartidos.
- [ ] Un responsable de seguridad y un responsable de datos.
- [ ] Pista GPX de verdad de terreno activada en un teléfono aparte.
- [ ] Reglas de conducción acordadas (nadie toca el teléfono en marcha).

## 10. Informe

Al terminar, completar `field-tests/FIELD_TEST_REPORT.md` con:
1. Equipos y condiciones.
2. Tablas por prueba (sección 6).
3. Gráficas distancia → % de entrega por transporte y ubicación.
4. Respuesta a F1 a F9.
5. **Decisión** de transporte y requisitos mínimos de teléfono.
6. Cambios en `DESIGN.md`, `MEMORY.md` (nueva fila D#) y `ROADMAP.md`.

Las cifras del simulador de 5 nodos **no cuentan** para este informe; se etiquetan como "SIMULADO".

## 11. Decisiones abiertas
1. Cantidad real de riders y teléfonos disponibles (completar tabla 3.1).
2. Lugar y fecha de la prueba y qué clubes participan.
3. Si se acepta excluir teléfonos sin BLE extendido (se decide tras B1 a B5).
4. Si se incluye una segunda ronda en lluvia y de noche.

## 12. Decisión de producto que desbloquea (enlace con Fase 3)

Esta prueba debe ejecutarse y cerrarse **antes de iniciar la Fase 3 (Rodada y mesh)**. Según la combinación de resultados obtenidos en B1–B8, se aplica la siguiente matriz de decisión sobre los documentos rectores:

| Combinación de resultados | Impacto en `MEMORY.md` (D27) | Impacto en `PROJECT.md` (Público objetivo) | Impacto en `BUSINESS_MODEL.md` (Precio) | Impacto en `DESIGN.md` (Arquitectura mesh) |
|---|---|---|---|---|
| **A. BLE Legacy cumple los umbrales de §7** | Se **confirma D27** (BLE extendido no es obligatorio; se usa como mejora oportunista). | Sin cambios: incluye teléfonos Android 8.0+ de gama baja/media en Latinoamérica y el mundo. | Sin cambios ($15/mes líder, $2.50/mes cupo). | `MeshPacket` de 20 bytes sobre Legacy Advertising (31 B) se mantiene como transporte primario en Fase 3. |
| **B. BLE Legacy NO cumple, pero BLE Extendido / Coded PHY SÍ cumple** | D27 se **reemplaza** por nueva decisión: *"BLE 5 Advertising Extendido obligatorio para participar como nodo retransmisor; teléfonos solo-legacy operan en modo degradado (solo escucha o alcance corto advertido)"* o exclusión total. | Se restringe el público objetivo a teléfonos con soporte real de `leExtendedAdvertisingSupported` (o se documenta modo degradado). | Se evalúa si la reducción del mercado potencial en gama baja exige ajuste comercial o advertencia previa al cobro. | `DESIGN.md` §3 pasa a exigir tramas extendidas (hasta 255 B) / Coded PHY como modo base de la Fase 3. |
| **C. Ninguno cumple a 50 m en movimiento** | D27 y el descarte de LoRa en `MEMORY.md` se **reabren**. | Se replantea el alcance del MVP sin hardware externo vs accesorio opcional. | Si requiere dongle LoRa/Meshtastic, cambia la estructura de costo de entrada del club. | Se adelanta un puente BLE ↔ LoRa/Meshtastic en la capa `Transport` abstracta de `DESIGN.md` antes de Fase 3. |

## 13. Enlace con `CHECKLIST.md` A.3 y `ROADMAP.md` Fase 0

La ejecución física de este plan es el requisito obligatorio para cerrar el criterio de salida de Fase 0:
1. **`CHECKLIST.md` — Sección A, ítem 3** (*"Prueba de campo BLE con al menos 3 marcas de teléfono"*): permanece en `[~]` (guion y simulador listos, hardware pendiente) hasta completar `field-tests/FIELD_TEST_REPORT.md` con datos reales de al menos 3 marcas; solo entonces pasa a `[x]`.
2. **`CHECKLIST.md` — Sección E, ítem 1** (*"Reenvío con TTL sin bucles ni duplicados"*): verificado en simulador unitario (`MeshValidationPhase0Test`), pasa a `[x]` definitivo en campo tras la prueba B5 (convoy de 5 motos).
3. **`MEMORY.md` — Decisión D27**: se actualiza con una nueva fila (`D#`) referenciando los números medidos en `field-tests/FIELD_TEST_REPORT.md`.
4. **`ROADMAP.md` — Criterio de salida de Fase 0**: queda formalmente cumplido al firmar el informe de la sección 10 y cerrar P4 en `PLAY_TEST_SETUP.md`.

## 14. Qué NO se prueba aquí

Este plan de campo evalúa **exclusivamente** el transporte físico BLE, el alcance en moto con casco, la supervivencia del servicio en segundo plano y el algoritmo del anunciador único. **No valida**:
- **Cobros y suscripciones con Google Play Billing** (se validan en `PLAY_TEST_SETUP.md` y Fase 2).
- **Emisión, firma y verificación offline de credenciales Ed25519 y listas de revocación** (se validan en Fase 2 y Fase 3).
- **Grabación, codificación Opus (8–12 kbps) y envío de clips de voz por Nearby Connections / L2CAP** (se validan en Fase 5).
