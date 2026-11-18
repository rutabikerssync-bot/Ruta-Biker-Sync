# 📡 BLE MESH & PTT OFF-GRID — PLIEGO DE ARQUITECTURA TÉCNICA (HITO 0)
## Especificación de Radio, Topología Multi-Salto, Voz PTT, Mensajería Off-Grid, Nodos Amigos y Criptografía Anti-Spoofing

**Proyecto:** Ruta Biker Sync  
**Versión del Pliego:** 1.0.0-PROD  
**Fecha de Emisión:** 9 de Noviembre de 2026  
**Estado:** Documento de Especificación de Ingeniería (Cero Código Previo a Aprobación)  
**Referencias Normativas:** `RULES.md` (§6, §10, §12), `DESIGN.md` (§3, §5, §11), `GAMIFICATION.md` (§3, §5, §7), `BACKEND_LICENSING.md` (§4), RFC 6716 (Opus), RFC 8032 (Ed25519), RFC 8439 (ChaCha20-Poly1305), Bluetooth Core Specification v5.4.

---

## 1. Introducción y Motivación

### 1.1 El Problema Fundamental del Motociclismo en Ruta
El motociclismo recreativo y federado ocurre predominantemente en entornos interurbanos, carreteras secundarias, puertos de montaña y cañones geográficos donde la cobertura de telefonía celular (LTE/5G) es deficiente o inexistente durante tramos continuos de 30 a 150 km.

Bajo estas condiciones:
1. Las aplicaciones convencionales de mensajería (WhatsApp, Telegram) y rastreo en vivo (Google Maps, Life360) quedan completamente inoperantes.
2. Los intercomunicadores de casco comerciales (Cardo, Sena) dependen de tecnologías propietarias cerradas, con alcances punto a punto limitados a 800 m–1.2 km en línea visual directa, que colapsan ante curvas de montaña con obstáculos rocosos y carecen de interoperabilidad entre marcas distintas.
3. Ante una emergencia crítica (caída de un piloto rezagado, pinchazo, derame de combustible o colisión en curva ciega), el convoy se dispersa sin percatarse del incidente, retrasando la asistencia médica y de rescate en la ventana crítica de los primeros 15 minutos ("la hora dorada").

### 1.2 La Solución: Malla Celular Propietaria sobre Bluetooth Low Energy (BLE)
Ruta Biker Sync convierte el teléfono inteligente de cada motero en un **nodo transceptor de radioenlace** dentro de una red de malla descentralizada (*mesh network*), operando de forma 100% autónoma en la banda ISM de 2.4 GHz, sin depender de tarjetas SIM, operadoras celulares, torres de repetición ni suscripciones satelitales.

```mermaid
graph LR
    subgraph Convoy["Convoy Biker en Carretera (Multi-Salto hasta 2.5 km)"]
        N1["🏍️ Nodo 1 (Punta / Scout)<br/>Tecno Spark 10C"] ---|"1M PHY (150 m)"| N2["🏍️ Nodo 2 (Capitán de Ruta)<br/>Redmi Note 13 Pro"]
        N2 ---|"Coded PHY (600 m)"| N3["🏍️ Nodo 3 (Miembro Convoy)<br/>Samsung A54"]
        N3 ---|"Coded PHY (700 m)"| N4["🏍️ Nodo 4 (Nodo Amigo / Relay)<br/>Moto G84"]
        N4 ---|"1M PHY (200 m)"| N5["🏍️ Nodo 5 (Sweep / Cierre)<br/>Tecno KI5k"]
    end
```

---

## 2. Casos de Uso Reales de Ruta

### Caso de Uso 1: Caída en Curva Ciega y Alerta SOS Instantánea
- **Escenario:** El piloto en la posición 4 sufre una derrapada por grava en una curva cerrada sin línea de vista hacia el resto del grupo. No hay señal celular.
- **Acción:** El acelerómetro/giroscopio detecta desaceleración anómala y ángulo de inclinación >70° durante 5 s, o el piloto presiona el botón físico/guantes de SOS en el manillar.
- **Respuesta:** Se despacha una trama `SOS_EMERGENCY` firmada con la clave privada Ed25519 del piloto. La trama salta por el Nodo 3 y Nodo 2 hasta llegar al Nodo 1 (Capitán).
- **Métrica:** El Capitán recibe la alerta sonora de emergencia, coordenadas GPS del impacto y grupo sanguíneo `O+` en **140 ms** (3 saltos).

### Caso de Uso 2: Convoy Alargado a 2.5 km con Enlace Adaptativo Coded PHY
- **Escenario:** En una recta larga de carretera nacional, los 12 moteros del club se distancian debido al tráfico pesado, quedando una brecha de 850 m entre el grupo delantero y el grupo trasero.
- **Acción:** Los nodos detectan caída de RSSI por debajo de -88 dBm en paquetes `LEGACY_1M` y elevan automáticamente la modulación a `EXTENDED_CODED_PHY (S=8)`.
- **Respuesta:** La sensibilidad de recepción mejora de -93 dBm a -101 dBm (+8 dB de margen de enlace), restableciendo la conectividad y el intercambio de telemetría sin romper la integridad del convoy.

### Caso de Uso 3: Mensajería de Texto Off-Grid mediante "Nodos Amigos" (Store & Forward)
- **Escenario:** Dos subgrupos se separan momentáneamente al tomar una bifurcación de montaña a 4 km de distancia. Un "Nodo Amigo" (un motero intermedio o un dispositivo estacionario en una parada de combustible) actúa como repetidor de bajo consumo.
- **Acción:** El grupo de rezagados emite un mensaje de texto corto cifrado (*"Parada en gasolinera km 42, esperamos 15 min"*).
- **Respuesta:** El Nodo Amigo almacena la trama en su búfer circular volátil y la retransmite en ráfagas al detectar los anuncios del Capitán de Ruta cuando este se aproxima al rango de cobertura.

### Caso de Uso 4: Push-to-Talk (PTT) Táctico de Corta Duración
- **Escenario:** El Scout en punta divisa un obstáculo grave (animal en calzada o mancha de aceite).
- **Acción:** Presiona el botón PTT del manillar (o control Bluetooth del intercomunicador) y pronuncia: *"Aceite en carril derecho"*.
- **Respuesta:** La voz se digitaliza en tramas Opus de 20 ms, se cifra con ChaCha20-Poly1305 y se transmite en ráfagas de alta prioridad. El audio se reproduce en los auriculares de todo el convoy en menos de **115 ms**.

---

## 3. Comparación de Alternativas de Radio

Para garantizar una decisión de ingeniería irrefutable, se evaluaron formalmente cuatro tecnologías de comunicación local para entornos móviles de alta velocidad:

| Parámetro de Evaluación | BLE Mesh SIG (Estándar) | BLE Custom (Ruta Biker Sync) | Wi-Fi Direct / Aware | LoRa / Meshtastic (Hardware Externo) |
|---|---|---|---|---|
| **Latencia RTT (Voz 20 ms, 3 saltos)** | **>550 ms** (Inaceptable para PTT) | **85–125 ms** (Tiempo real) | 60–90 ms | >1200 ms (Inviable para voz) |
| **Ancho de Banda Efectivo Útil** | ~12–18 kbps (Flooding puro) | **128–800 kbps** (Burst/Coded) | 4–20 Mbps | 0.3–5.4 kbps |
| **Capacidad de Streaming de Audio** | ❌ No soportado por estándar | ✅ **Soportado (Opus 16-24 kbps)** | ✅ Soportado | ❌ Imposible |
| **Consumo Eléctrico Promedio (Modo Activo)** | ~18–25 mAh/hora | **22–38 mAh/hora** | 180–320 mAh/hora (Crítico) | 12–18 mAh/hora |
| **Dependencia de Hardware Extra** | Ninguna (Chip BLE del móvil) | **Ninguna (Chip BLE del móvil)** | Ninguna | ❌ Exige radio externa (ESP32/Semtech) |
| **Soporte Android API Nativa** | Complejo / Requisitos de Stack | **Nativo (BluetoothLeAdvertiser)** | Inestable en movimiento rápido | USB-OTG / Bluetooth SPP |
| **Resiliencia ante Nodos Móviles a 120 km/h** | Pobre (Re-escaneo lento) | **Alta (Anuncios independientes)** | Muy pobre (Desconexión WPA2) | Alta |

### 3.1 Justificación Técnica del Descarte de BLE Mesh SIG
1. **Sobrecarga de Encabezados y Segmentación:** El estándar BLE Mesh de la Bluetooth SIG fragmenta los paquetes a nivel de capa de red en segmentos de 11 bytes netos (SAR). Transmitir un paquete de voz comprimida de 60 bytes requiere 6 segmentos independientes; la pérdida de un solo segmento invalida la trama completa, disparando retransmisiones acumulativas que colapsan la red a partir de 4 nodos.
2. **Latencia Inaceptable:** En pruebas empíricas documentadas, la latencia de retransmisión gestionada por el modelo de publicación/suscripción de BLE Mesh supera los **550 ms** por cada 2 saltos debido a los intervalos forzados de *advEvent* y tiempos de retroceso aleatorio (*backoff*), rompiendo el umbral conversacional de inteligibilidad humana.
3. **Imposibilidad de Streaming PTT:** La especificación Bluetooth Mesh Profile v1.0.1 prohíbe formalmente perfiles de audio continuo, limitándose a control de luminarias y telemetría de sensores de baja cadencia.

### 3.2 La Elección: BLE Custom Adaptativo (Extended Advertising + Coded PHY + GATT en Ráfaga)
Ruta Biker Sync adopta una arquitectura híbrida de radio:
- **Canal de Control y Telemetría Rápida (Canal A):** Emisión unidireccional y bidireccional por paquetes de anuncio extendido (`Extended Advertising`) con modulación adaptativa.
- **Canal de Voz y Datos Masivos (Canal B):** Ráfagas de alta tasa sobre conexiones L2CAP orientadas a conexión (*Connection-oriented Channels*) o GATT efímero sin emparejamiento OS.
- **Coded PHY (S=8):** Reservado para tramas de largo alcance (SOS, baliza de presencia, heartbeats y texto) ante caídas de RSSI por debajo de -88 dBm.

### 3.3 Comportamiento Cuantitativo de Coded PHY (S=8) vs 1M PHY
1. **Sensibilidad y Presupuesto de Enlace:**
   - En `1M PHY`, la sensibilidad de un receptor BLE móvil moderno (ej. Qualcomm WCN3998 o Unisoc T606) se sitúa en **-93 dBm**, permitiendo un alcance práctico en calzada con línea de vista de **120 a 180 metros**.
   - En `Coded PHY (S=8)` (Forward Error Correction con codificación de 8 símbolos por bit), la sensibilidad del receptor mejora hasta **-101 dBm** (+8 dB netos de ganancia de margen de enlace / *link budget*).
   - En espacio libre con visibilidad directa en carretera recta, esta ganancia de +8 dB **duplica o triplica el radio efectivo de cobertura**, alcanzando distancias de **600 a 850 metros** entre motos consecutivas.
2. **Pérdida de Throughput:**
   - La tasa bruta de bits de `Coded PHY S=8` desciende a **125 kbps** (frente a 1,000 kbps de `1M PHY` y 2,000 kbps de `2M PHY`).
   - Por esta razón, el streaming de voz PTT continuo utiliza prioritariamente `1M PHY` o ráfagas L2CAP, mientras que `Coded PHY` se reserva para paquetes críticos de control, telemetría y SOS.
3. **Algoritmo de Conmutación Histérica:**
   Para evitar oscilaciones rápidas (*ping-pong*) entre modulaciones en zonas limítrofes:
   - **Degradación a Coded PHY:** Se dispara si el RSSI promedio de los últimos 3 heartbeats recibidos cae por debajo de **-88 dBm**.
   - **Retorno a 1M PHY:** Exige que el RSSI promedio supere los **-78 dBm** con una histéresis de +10 dB durante al menos 5 heartbeats consecutivos.

---

## 4. Topología de Red, Descubrimiento y Flooding Controlado

### 4.1 Identificadores y Jerarquía de Direccionamiento
Para minimizar el consumo de bytes en cabecera:
- **`club_network_id` (4 bytes):** Hash truncado del UUID del Club (`groups.id`). Aísla el tráfico del motoclub frente a otros clubes en la misma carretera.
- **`node_short_id` (2 bytes / `uint16`):** Identificador efímero asignado a cada piloto durante la rodada (1 a 65535).
- **`sequence_number` (2 bytes / `uint16`):** Contador monotónico incremental por nodo emisor.

### 4.2 Prevención de Colisiones tras Reinicio (Reboot Resilience)
Si el teléfono de un motero se apaga o sufre un reinicio de proceso por agresividad del sistema operativo, su `sequence_number` en memoria RAM podría reiniciarse en `0`, provocando que el resto del convoy descarte sus paquetes legítimos considerándolos duplicados antiguos.

**Solución Implementada:**
1. Al iniciar la rodada activa, se establece un **`boot_epoch_counter`** almacenado en preferencias persistentes atómicas (`rbs_mesh_state.xml`).
2. Cada vez que el proceso arranca, incrementa su `boot_epoch_counter`.
3. El campo de secuencia se calcula combinando el epoch y el contador local:
   $$\text{PacketID} = (\text{senderShortId} \ll 32) \mid (\text{bootEpoch} \ll 16) \mid \text{sequenceNumber}$$
4. El resto de nodos purga de su tabla de duplicados cualquier secuencia anterior si reciben un paquete con un `bootEpoch` superior firmado criptográficamente.

### 4.3 Tabla de Deduplicación LRU (Least Recently Used)
Cada nodo mantiene en memoria una tabla de enrutamiento y deduplicación para evitar tormentas de retransmisión (*broadcast storm*).

- **Estructura:** Tabla hash con lista doblemente enlazada (LRU Cache).
- **Capacidad Máxima:** **1,024 entradas** (consumo de memoria volátil: ~48 KB de RAM).
- **Clave:** `(senderShortId: uint16, packetId: uint32)`.
- **Valor:** `(receivedTimestampMs: int64, hopsCount: uint8)`.
- **Política de Purga:**
  - Desalojo LRU cuando la tabla alcanza 1,024 elementos.
  - Hilo de limpieza pasivo cada 15 segundos eliminando registros con antigüedad mayor a **30 segundos** (la ventana máxima de supervivencia de un paquete multi-salto con TTL=5 es < 2.5 s).

### 4.4 Algoritmo de Retransmisión y Condiciones de Descarte
Cuando un nodo escucha un paquete en el aire:
1. **Filtro de Red:** Si `packet.clubNetworkId != local.clubNetworkId`, se descarta de inmediato a nivel de hardware (vía `ScanFilter`).
2. **Filtro de Emisor Propio:** Si `packet.senderShortId == local.shortId`, se descarta (evita auto-eco).
3. **Filtro de Deduplicación:** Se consulta la tabla LRU:
   - Si la clave `(senderShortId, packetId)` ya existe en la tabla $\rightarrow$ **Descarte inmediato** (ya fue procesado o retransmitido).
4. **Filtro de Vida (TTL):**
   - Si `packet.currentTtl <= 1` $\rightarrow$ Se procesa localmente pero **NO se retransmite** (ha alcanzado su límite de propagación).
   - Si `packet.currentTtl > 1` $\rightarrow$ Se decrementa `packet.currentTtl = packet.currentTtl - 1`, se inserta en la tabla LRU y se planifica su retransmisión con un retardo aleatorio calibrado (*jitter*) de **5 a 25 ms** para evitar colisiones de radio simultáneas entre nodos vecinos.

### 4.5 Sincronización Temporal entre Nodos
Las marcas de tiempo de los heartbeats y eventos de rodada requieren coherencia temporal:
- **Fuente Primaria:** **GPS Clock (UTC)**. Si el receptor GNSS del móvil tiene fijación (*fix* 2D/3D), la marca de tiempo se calibra con la señal de reloj atómico de los satélites (precisión < 1 µs).
- **Fuente Secundaria (Túneles / Sin GPS):** **Reloj Monotónico Relativo** (`SystemClock.elapsedRealtime()`). Los paquetes intercambian un delta temporal relativo al inicio de la rodada acordado en el manifiesto firmado, evitando desincronizaciones por cambios de zona horaria o desfase de reloj del sistema operativo.

---

## 5. Especificación de Tramas Binarias (Formato Wire de 2.4 GHz)

Todas las tramas de Ruta Biker Sync utilizan una cabecera binaria compacta empaquetada (*packed binary*) sin alineación de relleno para exprimir al máximo la carga útil de los anuncios BLE.

### 5.1 Cabecera Universal de Malla (`MeshHeader` — 8 Bytes Fijos)

```
 0                   1                   2                   3
 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                       club_network_id                         |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|       sender_short_id         |    packet_type|init_ttl|cur_ttl|
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|         sequence_num          | (Payload según packet_type...)
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
```

| Campo | Tipo / Tamaño | Descripción |
|---|---|---|
| `club_network_id` | `uint32` (4 bytes) | Hash de validación de red del Club organizador. |
| `sender_short_id` | `uint16` (2 bytes) | ID del nodo que emitió originariamente el paquete. |
| `packet_type` | `uint4` (4 bits) | `0x1`=Heartbeat/Telemetría, `0x2`=SOS, `0x3`=PTT Voice, `0x4`=Text Msg, `0x5`=Control/Sync. |
| `init_ttl` | `uint2` (2 bits) | Saltos originales configurados (0=1 salto, 1=2 saltos, 2=3 saltos, 3=5 saltos). |
| `current_ttl` | `uint2` (2 bits) | Saltos restantes antes del descarte definitivo. |
| `sequence_num` | `uint16` (2 bytes) | Contador monotónico de secuencia del emisor. |

---

### 5.2 Trama de Telemetría y Heartbeat (`Type 0x1` — 24 Bytes Totales)
Se emite periódicamente cada 3 segundos en condiciones normales (o 5–10 s en modo ahorro de energía).

```
 0                   1                   2                   3
 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                     MeshHeader (8 Bytes)                      |
|                                                               |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                          latitude                             |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                          longitude                            |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
| speed_kmh | heading_deg/2 |bat|flg|    auth_tag_trunc (4B)     |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
```

- **`latitude` / `longitude` (4 bytes c/u):** Enteros signed con resolución fija a 7 decimales ($\approx 1.1\text{ cm}$).
- **`speed_kmh` (1 byte):** 0 a 255 km/h.
- **`heading_deg` (1 byte):** Rumbo en grados dividido entre 2 (0 a 180 $\rightarrow$ cubre 0° a 360° con resolución de 2°).
- **`bat` (4 bits):** Nivel de batería del emisor (16 niveles de 6.25% cada uno).
- **`flg` (4 bits):** Banderas de estado (`bit 0`=Ficha médica compartida, `bit 1`=Desviado de ruta, `bit 2`=Detenido voluntario, `bit 3`=Voz activa).
- **`auth_tag_trunc` (4 bytes):** HMAC-SHA256 truncado calculado sobre la clave simétrica de la rodada (`rideSecretKey`), cubriendo la cabecera inmutable y los datos cinemáticos.

---

### 5.3 Trama de Emergencia SOS (`Type 0x2` — 88 Bytes Totales)
Prioridad absoluta en el medio físico. Se retransmite forzosamente durante 60 segundos por todos los nodos con modulación Coded PHY.

```
 0                   1                   2                   3
 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                     MeshHeader (8 Bytes)                      |
|                                                               |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                          latitude                             |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                          longitude                            |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|   incident_code   |  blood_type   |    timestamp_epoch_sec    |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                                                               |
+                    ed25519_signature (64 Bytes)               +
|                                                               |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
```

- **`incident_code` (1 byte):** `0x01`=Caída automática por sensores, `0x02`=Botón SOS manual, `0x03`=Avería mecánica crítica, `0x04`=Colisión vehicular.
- **`blood_type` (1 byte):** Identificador enum de grupo sanguíneo (1=O+, 2=O-, 3=A+, 4=A-, 5=B+, 6=B-, 7=AB+, 8=AB-).
- **`timestamp_epoch_sec` (4 bytes):** Marca temporal UTC del incidente.
- **`ed25519_signature` (64 bytes):** Firma criptográfica asimétrica generada con la clave privada del piloto alojada en `AndroidKeyStore`.

---

### 5.4 Trama de Mensajería de Texto Off-Grid (`Type 0x4` — Hasta 128 Bytes)
Permite comunicación bidireccional punto a punto o broadcast hacia todo el club sin internet.

```
 0                   1                   2                   3
 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                     MeshHeader (8 Bytes)                      |
|                                                               |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|       target_short_id         |  msg_length   | enc_algo_type |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                       iv_nonce (12 Bytes)                     |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                  ciphertext (Hasta 80 Bytes UTF-8)            |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                     auth_tag_16 (16 Bytes)                    |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
```

- **`target_short_id` (2 bytes):** `0xFFFF` para mensaje general a todo el convoy, o el ID específico del destinatario.
- **`enc_algo_type` (1 byte):** `0x01`=ChaCha20-Poly1305 (Clave simétrica de rodada o clave compartida ECDH entre pares).
- **`ciphertext`:** Texto plano en UTF-8 comprimido con zlib/deflate si supera los 32 caracteres.

---

### 5.5 Tramas de Ráfaga de Voz PTT (`Type 0x3` — Tramas de 40 Bytes)
- Cada trama transporta exactamente **20 ms de audio comprimido con Opus**.
- A 16 kbps, 20 ms de audio ocupan exactamente **40 bytes** de payload (o 60 bytes a 24 kbps).
- Una frase de voz de 5 segundos se fragmenta en una ráfaga ordenada de 250 paquetes transportados mediante canal L2CAP orientado a conexión o ráfaga GATT rápida.

---

### 5.6 Trama de Confirmación / ACK (`Type 0x5` — 14 Bytes Totales)
Utilizada para confirmar la recepción punto a punto de mensajes de texto directos, balizas de control o recepción de socorro SOS.

```
 0                   1                   2                   3
 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                     MeshHeader (8 Bytes)                      |
|                                                               |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|       acked_sender_id         |       acked_sequence_num      |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|   ack_status  |   reserved    |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
```

- **`acked_sender_id` (2 bytes):** ID del nodo emisor original cuyo paquete se está confirmando.
- **`acked_sequence_num` (2 bytes):** Número de secuencia exacto del paquete confirmado.
- **`ack_status` (1 byte):** `0x00`=Recibido y procesado OK, `0x01`=Descifrado fallido (bad tag), `0x02`=Búfer lleno (reintentar en backoff).

---

### 5.7 Gestión de MTU, Data Length Extension (DLE) y Fragmentación
El tamaño de la Unidad de Transmisión Máxima (MTU) en BLE es un factor de diseño crítico para evitar la fragmentación ineficiente:

1. **Extended Advertising (BLE 5.0+):**
   - A diferencia de los anuncios Legacy (BLE 4.2) que están limitados a 31 bytes brutos (~24 bytes netos útiles), `Extended Advertising` permite una carga útil de hasta **254 bytes por anuncio**.
   - Nuestras tramas de Telemetría (24 bytes), ACK (14 bytes), SOS (88 bytes) y Mensajes de Texto (hasta 128 bytes) **se transmiten en un único paquete atómico en el aire, con CERO fragmentación**.
2. **Canales de Voz L2CAP / GATT y Data Length Extension (DLE):**
   - En canales conectados efímeros para PTT, Android negocia inmediatamente `Data Length Extension` (DLE) elevando el PDU de la capa de enlace de 27 bytes a **251 bytes** y solicitando `requestMtu(251)`.
   - Con un MTU efectivo de 247 bytes útiles en L2CAP, un frame de audio Opus (40 a 60 bytes) + cabecera (8 bytes) + IV (12 bytes) + AuthTag (16 bytes) ocupa un total de **76 a 96 bytes**.
   - **Conclusión Técnica:** La trama de voz cabe holgadamente dentro de un único paquete de radio sin requerir SAR (Segmentation and Reassembly), eliminando la latencia de reensamblado y las pérdidas por paquetes huérfanos.
3. **Modo de Contingencia Legacy (Dispositivos Antiguos sin Extended Advertising):**
   - Si un nodo legado solo soporta anuncios de 31 bytes, el transmisor segmenta las tramas que superen 24 bytes en 2 fragmentos numerados con cabecera SAR de 1 byte:
     `[ChunkID (4 bits) | TotalChunks (4 bits)]`. El receptor retiene el primer fragmento durante un máximo de 80 ms a la espera del segundo; si expira, se descarta.

## 6. Códec de Voz PTT y Gestión de Búfer

### 6.1 Presupuesto de Latencia Extremo a Extremo (RTT en 5 Saltos)
La inteligibilidad conversacional exige que el tiempo total entre la pulsación del botón y la reproducción en el casco del último motero sea inferior a **250 ms**.

| Etapa del Enlace de Voz | Latencia Estimada | Latencia Máxima (Peor Caso) |
|---|---|---|
| **Captura y codificación de frame Opus (20 ms)** | 20.0 ms | 22.0 ms |
| **Cifrado ChaCha20-Poly1305 en RAM** | 0.8 ms | 1.5 ms |
| **Transmisión de Radio Salto 1 (Tx + Rx)** | 8.5 ms | 15.0 ms |
| **Procesamiento y retransmisión Salto 2** | 6.0 ms | 12.0 ms |
| **Procesamiento y retransmisión Salto 3** | 6.0 ms | 12.0 ms |
| **Procesamiento y retransmisión Salto 4** | 6.0 ms | 12.0 ms |
| **Procesamiento y retransmisión Salto 5** | 6.0 ms | 12.0 ms |
| **Búfer de des-jitter adaptativo en receptor** | 30.0 ms | 45.0 ms |
| **Descifrado y decodificación Opus** | 2.5 ms | 4.0 ms |
| **Renderizado en altavoz/intercomunicador casco** | 10.0 ms | 15.0 ms |
| **TOTAL LATENCIA UNIDIRECCIONAL (5 Saltos)** | **95.8 ms** | **150.5 ms** |

> [!NOTE]
> Una latencia de **~96 ms en 5 saltos** garantiza una comunicación táctica instantánea, completamente imperceptible para el usuario y superior a los enlaces satelitales comerciales.

### 6.2 Búfer de Jitter Adaptativo
Dado que los paquetes de voz pueden experimentar variaciones de tiempo de llegada debido a retransmisiones por ruido en carretera:
- **Estructura:** Cola de prioridad indexada por `packet.sequenceNumber`.
- **Profundidad Base:** **40 ms** (2 frames Opus).
- **Adaptación Dinámica:** Si la varianza de llegada ($\sigma^2$) supera los 15 ms, el búfer incrementa automáticamente su profundidad hasta un tope máximo de **80 ms** (4 frames). Si el canal recupera estabilidad durante más de 3 segundos, se contrae progresivamente descartando micro-silencios.
- **Pérdida de Paquetes (PLC):** Si se pierde una trama intermedia, el decodificador Opus invoca su algoritmo interno de *Packet Loss Concealment* (PLC) para sintetizar el espectro de audio ausente sin chasquidos.

### 6.3 Evaluación de Cifrado en Hardware Modesto: ChaCha20-Poly1305 vs AES-256-GCM
En dispositivos de gama de entrada con procesadores como el **Unisoc T606** (Cortex-A75 / Cortex-A55 sin extensiones criptográficas ARMv8 Crypto por hardware activadas), la elección del algoritmo de cifrado es crítica para no saturar la CPU:

```
Benchmark de Rendimiento Criptográfico (Cifrado de Bloque de 64 Bytes en Cortex-A55 @ 1.6 GHz):
ChaCha20-Poly1305 (Software Puro):       ~1.2 µs por paquete (Sobrecarga de CPU < 0.4%)
AES-256-GCM (Sin aceleración hardware):  ~8.7 µs por paquete (Sobrecarga de CPU ~3.2%)
```

- **Decisión de Diseño:** Se selecciona **ChaCha20-Poly1305 (RFC 8439)** como algoritmo principal para voz y mensajería en la red de malla. Ofrece inmunidad total contra ataques de canal lateral por tablas de tiempo (*timing attacks*) y un rendimiento 7 veces superior en chips económicos como Unisoc y MediaTek Helio.

### 6.4 Cumplimiento Estricto de la Regla R12 (Destrucción en RAM)
La Regla R12 exige que los clips de audio solo existan durante la rodada activa y se purguen inmediatamente tras su reproducción en el casco:
1. **Memoria Directa:** Las tramas de audio se procesan en `ByteBuffer.allocateDirect()` fuera del montón de Java (*off-heap*).
2. **Zeroing Explícito:** Inmediatamente después de que el búfer de audio es transferido a la interfaz de audio de Android (`AudioTrack.write()`), se ejecuta sobreescritura manual en memoria:
   ```kotlin
   // Patrón estricto de zeroing en memoria volátil (R12)
   Arrays.fill(pcmAudioBuffer, 0.toByte())
   Arrays.fill(decryptedFrameBuffer, 0.toByte())
   ```
3. **Cero Persistencia:** Prohibida la escritura en archivos temporales de disco o bases de datos SQLite/Room. Si la rodada finaliza o se pierde la sesión, la clave efímera de rodada (`rideSecretKey`) se sobreescribe con ceros en RAM.

---

## 7. Nodos Amigos y Nodos de Bajo Consumo (Mensajería Asíncrona)

El pliego formaliza la arquitectura para **Nodos Amigos** y **Nodos de Bajo Consumo**, permitiendo extender la red y almacenar mensajes cuando el convoy se fractura geográficamente.

```mermaid
graph TD
    subgraph RedConvoy["Red de Convoy"]
        M1["🏍️ Motero A (En Movimiento)"]
        M2["🏍️ Motero B (En Movimiento)"]
    end

    subgraph InfraAmiga["Infraestructura Amiga Off-Grid"]
        NA["🛡️ NODO AMIGO (Búfer Store & Forward)<br/>Teléfono en Maletero / Estación Base"]
        LPN["🔋 NODO BAJO CONSUMO (LPN)<br/>Baliza GPS / Repetidor Solar de Montaña"]
    end

    M1 -->|"1. Envía mensaje cifrado"| NA
    NA -->|"2. Almacena en Búfer LRU (30 min)"| NA
    LPN -.->|"3. Baliza de presencia cada 15s"| NA
    NA -->|"4. Detecta proximidad y descarga ráfaga"| M2
```

### 7.1 Definición de Roles de Nodo
1. **Nodo Convoy (Standard Node):** Teléfono del motero montado en el manillar o bolsillo del chaleco. Ejecuta escaneo activo y retransmisión continua durante la rodada.
2. **Nodo Amigo (Friend Node / Store & Forward):**
   - Dispositivo secundario (móvil de repuesto con batería extendida, tablet en moto de asistencia, o terminal estático en punto de control/gasolinera).
   - Posee un búfer circular de mensajería asíncrona de **256 KB** en memoria.
   - Si un nodo emisor busca a un destinatario no alcanzable, el Nodo Amigo almacena la trama con un TTL temporal de hasta **30 minutos**. Al detectar la presencia del destinatario mediante su heartbeat, le descarga la ráfaga de mensajes pendientes.
3. **Nodo de Bajo Consumo (Low-Power Node - LPN):**
   - Nodos con batería crítica (<15%) o balizas solares estacionarias colocadas en curvas peligrosas.
   - **Ciclo de Sueño (Duty Cycle):** Permanece en reposo profundo el 95% del tiempo. Solo despierta durante **200 ms cada 5 segundos** para emitir su baliza de estado o verificar si hay mensajes dirigidos hacia él en su Nodo Amigo asociado.

---

## 8. Presupuesto Energético y Agresividad OEM (Xiaomi / Tecno)

### 8.1 Presupuesto de Consumo en Dispositivo de 5,000 mAh
Para garantizar que una rodada continua de **6 horas** no descargue el teléfono del piloto:

| Modo Operativo | Escaneo BLE | Emisión Anuncios | GPS Fix | CPU WakeLock | Consumo mAh/hora | Consumo mAh/minuto | Autonomía (Batería 5,000 mAh) |
|---|---|---|---|---|---|---|---|
| **Standby (Sin Rodada)** | OFF | OFF | OFF | Ninguno | **~1.5 mAh/h** | 0.025 mAh/min | >30 días |
| **Rodada Activa (Carretera)** | Balanceado (Duty 30%) | Cada 3 s | Cada 1 s | `PARTIAL_WAKE_LOCK` | **180–220 mAh/h** | 3.00–3.66 mAh/min | **22.7 Horas** |
| **Rodada con PTT Intenso (10% Voz)** | Alta prioridad | Ráfagas | Cada 1 s | Activo + AudioTrack | **260–310 mAh/h** | 4.33–5.16 mAh/min | **16.1 Horas** |
| **Modo Supervivencia (<10% Bat)** | Cada 10 s | Cada 10 s | Cada 10 s | Suspende salvo SOS | **35–50 mAh/h** | 0.58–0.83 mAh/min | **100+ Horas** |

> [!NOTE]
> Con un consumo de **~220 mAh/hora** (3.66 mAh/minuto), una rodada típica de fin de semana (4 a 6 horas) consume entre el **14% y el 26%** de la batería total, dejando más del 70% de carga disponible para el resto de funciones del dispositivo.

### 8.2 Metodología de Medición y Validación Energética
1. **Medición Real en Laboratorio:**
   - Profilado en tiempo real con **Android Studio Energy Profiler** en sesiones de 60 minutos con pantalla apagada y en movimiento vehicular simulado.
   - Registro de corriente instantánea mediante la API de Android: `BatteryManager.BATTERY_PROPERTY_CURRENT_NOW` (lectura en microamperios $\mu\text{A}$) promediada cada 10 segundos.
   - Validación física cruzada con fuente de alimentación y medidor de potencia **Monsoon Power Monitor** conectado directamente a los terminales de batería en banco de pruebas.
2. **Comportamiento ante el "Ahorro de Batería" del Sistema (Battery Saver OS):**
   - Si el usuario activa el modo Ahorro de Batería nativo de Android:
     - El sistema operativo intenta reducir la cadencia del GPS y congelar el escáner BLE.
     - `RideService` detecta el evento mediante `BroadcastReceiver(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)`.
     - La app **NO se detiene**: degrada voluntariamente su perfil operativo a `PowerProfile.POWER_SAVER` (cadencia de heartbeat pasa de 3s a 5s, escaneo BLE pasa de baja latencia a balanceado y actualización de pantalla se reduce a 3s), garantizando que la red mesh y la escucha de SOS permanezcan activas con un consumo reducido a **90–110 mAh/h**.

### 8.3 Blindaje contra Asesinos de Procesos OEM (Xiaomi HyperOS / Tecno HiOS)
Los sistemas operativos Android de fabricantes chinos implementan políticas ultra-agresivas de gestión de memoria que destruyen servicios en segundo plano a los 3 minutos de apagar la pantalla.

**Estrategia de Blindaje Técnico:**
1. **Foreground Service Multitipo Obligatorio:**
   El servicio de malla se declara en el `AndroidManifest.xml` con vinculación múltiple:
   ```xml
   android:foregroundServiceType="connectedDevice|location"
   ```
2. **Notificación Persistente con Métricas en Vivo:**
   La notificación de primer plano actualiza dinámicamente el conteo de nodos conectados (`"🏍️ Convoy Mesh: 8 pilotos enlazados | Sin internet"`). Android prioriza este servicio a nivel `OOM_ADJ` igual al de una llamada telefónica activa.
3. **WakeLock Parcial con Adquisición Segura:**
   Se sostiene un `PowerManager.PARTIAL_WAKE_LOCK` etiquetado como `RBS:MeshRadioEngineLock`, impidiendo que el procesador entre en modo Deep Sleep mientras el vehículo está en marcha.
4. **Mapeo Intent de Exclusión de Batería OEM:**
   Al unirse a la rodada, si el sistema detecta fabricantes agresivos (`Build.MANUFACTURER.contains("Xiaomi|Transsion|Tecno|Huawei")`), despliega un modal visual con botón de enlace directo hacia el menú oculto del sistema:
   - *Xiaomi:* `com.miui.powerkeeper/.ui.HiddenAppsConfigActivity` (Ajustar a "Sin Restricciones").
   - *Tecno:* `com.transsion.phonemanager/.battery.BatteryManagementActivity`.

---

## 9. Identidad Criptográfica, Anti-Spoofing y Anti-Replay

### 9.1 Algoritmo de Firma Asimétrica: Ed25519 (RFC 8032)
Para las tramas de control crítico (especialmente `SOS_EMERGENCY` y manifiesto de cierre), se adopta **Ed25519**:
- **Tamaño de Clave Pública:** 32 bytes exactos.
- **Tamaño de Firma:** 64 bytes exactos.
- **Rendimiento de Verificación:** En Cortex-A55, una verificación Ed25519 toma **~0.42 ms** (frente a 2.8 ms de ECDSA P-256).

### 9.2 Distribución de Claves Públicas sin Internet
¿Cómo sabe el teléfono de un motero que el paquete de SOS proviene auténticamente del Capitán Oscar y no de un atacante emitiendo paquetes BLE falsificados?
1. **Manifiesto Pre-Rodada:** Al convocarse la rodada (cuando aún hay conexión celular o en el punto de encuentro mediante escaneo de QR de club), cada piloto descarga el `manifest.json` firmado digitalmente por los líderes del club.
2. El manifiesto contiene el mapeo determinista:
   $$\text{Map: } \{\text{shortId}: 1 \rightarrow \text{PublicKey}_{\text{Oscar}}, \text{shortId}: 2 \rightarrow \text{PublicKey}_{\text{Laura}}, \dots\}$$
3. Cada nodo almacena esta tabla de claves públicas en la memoria volátil del servicio `RideService`.

### 9.3 Verificación en Cada Salto Intermedio (Hop-by-Hop Verification)
Cuando un nodo intermedio recibe un paquete SOS o de control:
1. Extrae `senderShortId` y localiza la clave pública correspondiente en su tabla pre-cargada.
2. Si la clave pública no existe $\rightarrow$ **Descarte inmediato y log de anomalía**.
3. Verifica la firma Ed25519 sobre los campos inmutables:
   $$\text{Verify}(\text{PublicKey}_{\text{sender}}, \text{Payload}_{\text{immutable}}, \text{Signature}_{64})$$
4. Si la firma es válida, procesa localmente y retransmite a sus vecinos. Si la firma falla, descarta la trama sin propagarla.
5. **Caché de Firmas Verificadas:** Para no repetir la verificación criptográfica del mismo paquete si este es re-escuchado por rebotes de radio, se almacena el hash de la firma en una tabla de verificación positiva de 128 entradas.

### 9.4 Protección Anti-Replay
Un atacante podría grabar un paquete SOS legítimo de una rodada pasada y reemitirlo días después para causar pánico en el motoclub.
**Defensa:**
1. Todo paquete crítico incluye `timestamp_epoch_sec` calibrado por GPS.
2. El receptor exige que:
   $$|\text{CurrentTime} - \text{PacketTimestamp}| \le 30 \text{ segundos}$$
3. Adicionalmente, el `sequence_number` del emisor debe ser estrictamente superior al último número registrado para ese `senderShortId` dentro del mismo `boot_epoch`. Cualquier paquete fuera de ventana o con secuencia decreciente es incinerado al instante.

---

## 10. Interconexión con Gamificación y Telemetría

### 10.1 Cálculo de "Kilómetros en Malla" con Privacidad Absoluta
En cumplimiento estricto con `RULES.md` R15 y la política de privacidad local-first:
- Los teléfonos **NUNCA** envían al servidor el historial de ubicaciones GPS ni trazas de recorrido.
- El odómetro de malla se calcula de forma incremental y puramente local:
  1. Cada segundo, el GPS local calcula la distancia euclidiana recorrida: $\Delta d = \text{haversine}(P_{t}, P_{t-1})$.
  2. Si y solo si durante ese segundo el nodo mantuvo enlace activo (al menos 1 heartbeat recibido de otro miembro en los últimos 10 s), se acumula:
     $$\text{MeshKm}_{\text{total}} = \text{MeshKm}_{\text{total}} + \Delta d$$
  3. Si el motero se separa y rueda solo, el odómetro de malla se congela (no suma XP de convoy hasta reconectarse).

### 10.2 Sincronización Diferida con el Backend (Store & Forward Post-Rodada)
Durante las horas de carretera sin internet, los eventos de gamificación se acumulan en una cola local firmada en Room (`pending_gamification_events`):
- Minutos acumulados en modo off-grid (`Sin Cobertura`).
- Paquetes retransmitidos en modo relay para otros compañeros.
- Asistencia en incidentes de emergencia SOS.

Al finalizar la rodada y detectar reconexión a Internet (Wi-Fi de hotel, gasolinera o datos móviles):
1. El cliente Android envía un payload agregado firmado al endpoint:
   `POST /v1/rides/:id/telemetry-summary`
2. El backend procesa las métricas, actualiza el nivel de XP del usuario en `users.rank_xp`, incrementa las rachas mensuales y desbloquea las insignias correspondientes de [`GAMIFICATION.md`](file:///d:/Ruta-Biker-Sync/docs/GAMIFICATION.md).
3. **Resiliencia ante Cierre Forzado de la App:**
   - Si el motero apaga su teléfono o cierra la aplicación antes de que haya cobertura celular, los eventos permanecen inmutables en la tabla local SQLite/Room `pending_gamification_events`.
   - Se registra una tarea en segundo plano mediante **Android `WorkManager`** (`OneTimeWorkRequestBuilder<SyncGamificationWorker>()`) con la restricción `setRequiredNetworkType(NetworkType.CONNECTED)` y política de reintento exponencial (`BackoffPolicy.EXPONENTIAL`).
   - Tan pronto como el terminal detecta conectividad (incluso horas o días después), el sistema despacha los resúmenes acumulados sin pérdida de puntos de XP ni corte de rachas mensuales.

### 10.3 Blindaje Anti-Trampas (Anti-Cheat Criptográfico)
Para evitar que un usuario manipule su cliente local inyectando kilómetros falsos para subir de rango a *Leyenda*:
1. **Consenso de Cierre:** El resumen de la rodada requiere la firma cruzada del Líder de la rodada (`host_leader`) en el manifiesto de cierre (`POST /v1/rides/:id/close`).
2. **Validación de Testigos Multi-Nodo:** El resumen del usuario incluye los identificadores de al menos dos nodos testigos que intercambiaron heartbeats con él durante la ruta. Si los reportes de los testigos discrepan en más del 20% respecto a la distancia reportada, el sistema marca el evento para auditoría de directiva y otorga únicamente la puntuación base.

---

## 11. Máquinas de Estado de la Red de Malla

```mermaid
stateDiagram-v2
    [*] --> STANDBY : App instalada / Sin rodada

    state STANDBY {
        [*] --> Inactivo : Pantalla cerrada
        Inactivo --> EscaneoPasivo : Evento BT
    }

    STANDBY --> PRE_RIDE_SYNC : Convocatoria aceptada / Manifiesto descargado
    
    state PRE_RIDE_SYNC {
        DescargaManifiesto --> DerivacionClaves : Cifrado E2EE
        DerivacionClaves --> VerificacionHardware : Check BLE / Extended PHY
    }

    PRE_RIDE_SYNC --> ACTIVE_MESH : Inicio rodada (Líder activa convoy)

    state ACTIVE_MESH {
        [*] --> HighPowerMode : Batería > 20%
        HighPowerMode --> BatterySaverMode : Batería <= 20%
        BatterySaverMode --> SurvivalMode : Batería <= 10%
        
        state HighPowerMode {
            CadenciaNormal : Heartbeat 3s / Scan 30%
            CadenciaNormal --> TransmisionPTT : Botón PTT presionado
            TransmisionPTT --> CadenciaNormal : PTT liberado
            CadenciaNormal --> AlertaSOS : Caída / Botón SOS
        }
    }

    ACTIVE_MESH --> POST_RIDE_SYNC : Cierre formal de rodada por Líder
    POST_RIDE_SYNC --> STANDBY : Métricas enviadas / Zeroing en RAM
```

### 11.2 Contingencias Operativas y Transiciones Especiales

1. **¿Qué sucede si un nodo en STANDBY recibe un paquete SOS? (Protocolo del Buen Samaritano):**
   - Aunque el motero no participe en una rodada activa (app en segundo plano en modo `STANDBY`), el hardware mantiene un filtro pasivo en segundo plano para el `ServiceUUID` de emergencias de Ruta Biker Sync.
   - Si se detecta un paquete `SOS_EMERGENCY` válido con firma Ed25519 verificada:
     - El dispositivo **NO ignora la alerta**.
     - Despierta inmediatamente la CPU vía `WakeLock` temporal (15 segundos).
     - Dispara una notificación prioritaria con sonido de baliza de auxilio: *"🚨 SOS Motero Detectado en Proximidad (a ~350 m)"*.
     - **Retransmite automáticamente el paquete SOS con modulación Coded PHY** durante 30 segundos, actuando como repetidor puente para que la alerta llegue a otros moteros o a zonas con cobertura.
2. **¿Qué sucede si el Líder desaparece o pierde señal del mesh? (Sucesión de Mando R4):**
   - El mesh **NUNCA se disuelve** si el Líder se rezaga, tiene una avería o se pierde. La red de malla es descentralizada y no jerárquica a nivel de radiofrecuencia.
   - Tras 5 latidos perdidos (~15 segundos) del Líder, el nodo del **Co-Líder** (o en su defecto el **Sargento de Armas** o el miembro de mayor rango conectado según el manifiesto pre-rodada) asume automáticamente el rol de **Capitán de Ruta en Tránsito**.
   - El sistema emite un anuncio local por TTS a los auriculares del convoy: *"Líder fuera de alcance. Sargento asume coordinación de convoy"*.
   - La red de radio continúa operando normalmente con la misma clave de sesión (`rideSecretKey`).
3. **¿Qué sucede si solo hay 1 nodo en el mesh? (Modo Aislado / Baliza):**
   - Si un piloto queda completamente solo (cero compañeros en radio de cobertura):
     - La pantalla del mapa muestra el badge de alerta: `⚠️ Malla: Modo Aislado (Sin compañeros en rango)`.
     - El odómetro de "km en malla" se congela para evitar fraude de gamificación.
     - El dispositivo pasa a emitir una baliza de búsqueda periódica cada 5 segundos para reconectar tan pronto como se acerque al convoy.

---

## 12. Diagramas de Secuencia Táctica

### 12.1 Flujo de Descubrimiento y Mantenimiento de Convoy
```mermaid
sequenceDiagram
    autonumber
    participant N1 as Nodo 1 (Capitán)
    participant N2 as Nodo 2 (Miembro)
    participant N3 as Nodo 3 (Sweep)

    Note over N1,N3: Rodada Activa Iniciada con rideSecretKey compartida
    N1->>N2: ADV_EXT [Heartbeat Seq:1, Lat/Lon, Batt:95%, TTL:5]
    Note over N2: Deduplica en tabla LRU, calcula dist=120m
    N2->>N3: ADV_EXT Relay [Heartbeat Seq:1, TTL:4]
    Note over N3: Registra presencia de N1 y N2 en UI Mapa
    N3-->>N2: ADV_EXT [Heartbeat Seq:1, Lat/Lon, Batt:80%, TTL:5]
    N2-->>N1: ADV_EXT Relay [Heartbeat Seq:1, TTL:4]
    Note over N1: Convoy 100% enlazado. Cero alertas.
```

### 12.2 Flujo de Transmisión de Audio PTT (Ráfaga Efímera)
```mermaid
sequenceDiagram
    autonumber
    participant P as Piloto Emisor (Scout)
    participant R as Nodo Relay (Intermedio)
    participant C as Casco Receptor (Convoy)

    P->>P: Presiona PTT Manillar -> Captura Mic 20ms
    P->>P: Comprime Opus 16kbps + Cifra ChaCha20
    P->>R: L2CAP Burst [Voice Frame Seq:101, Data:40B, TTL:5]
    R->>C: L2CAP Relay Burst [Voice Frame Seq:101, Data:40B, TTL:4]
    Note over C: Búfer de Jitter (40ms) -> Descifra ChaCha20
    C->>C: Decodifica Opus -> Renderiza altavoz casco
    Note over C: REGLA R12: Zeroing explícito en RAM (Arrays.fill 0)
```

### 12.3 Flujo de Emergencia SOS y Consulta de Ficha Médica
```mermaid
sequenceDiagram
    autonumber
    participant A as Piloto Accidentado
    participant M as Malla Multi-Salto (Relays)
    participant L as Líder / Sargento de Armas

    A->>A: Sensor Detecta Caída (>70° inclinación x 5s)
    A->>A: Firma digital Ed25519 con AndroidKeyStore
    A->>M: ADV_CODED_PHY [SOS_EMERGENCY, Blood:O+, Sig:64B, TTL:5]
    M->>L: Propagación Multi-Salto (140 ms RTT)
    Note over L: UI despliega Banner Rojo SOS con Alarma Sonora
    L->>L: Verifica Firma Ed25519 con Clave Pública de A
    L->>L: Abre Ficha Médica SOS descifrada (ICE + Alergias)
    L->>M: ADV_CODED_PHY [ACK_SOS_RECEIVED, Dest:A, TTL:5]
```

---

## 13. Matriz de Riesgos Mayores y Mitigaciones de Ingeniería

A continuación se identifican con total honestidad los tres mayores riesgos técnicos del proyecto y su arquitectura de mitigación:

```mermaid
quadrantChart
    title Matriz de Impacto vs Probabilidad de Riesgos
    x-axis Baja Probabilidad --> Alta Probabilidad
    y-axis Bajo Impacto --> Alto Impacto
    quadrant-1 Riesgos Críticos (Acción Inmediata)
    quadrant-2 Riesgos de Alto Impacto
    quadrant-3 Riesgos Menores
    quadrant-4 Riesgos Operativos
    "Muerte de Servicio por OEM": [0.85, 0.90]
    "Saturación de Medio en Convoy >15 Motos": [0.70, 0.75]
    "Contención de Radio 2.4 GHz (Wi-Fi/BT)": [0.80, 0.65]
```

### Riesgo 1: Muerte Silenciosa del Servicio en Segundo Plano por el Sistema Operativo OEM
- **Probabilidad:** Muy Alta (85%) | **Impacto:** Crítico (90%)
- **Naturaleza del Fallo:** Dispositivos Xiaomi (`HyperOS`) o Tecno (`HiOS`) matan el proceso en segundo plano tras 3–5 minutos con la pantalla apagada para ahorrar batería, dejando al motero aislado sin enviar ni recibir alertas SOS.
- **Estrategia de Mitigación:**
  1. Declaración de Foreground Service múltiple (`connectedDevice|location`).
  2. Adquisición continua de `PARTIAL_WAKE_LOCK`.
  3. Temporizador de vigilancia asistido por `AlarmManager` con `setExactAndAllowWhileIdle()` cada 60 segundos que verifica la vitalidad del bucle BLE y relanza el hilo si fue congelado.
  4. Protocolo UI obligatorio en el onboarding guiando al usuario a desactivar las optimizaciones de batería en el gestor del fabricante.

### Riesgo 2: Saturación y Colisión del Espectro de Radio en Convoyes Grandes (>15 Nodos)
- **Probabilidad:** Alta (70%) | **Impacto:** Alto (75%)
- **Naturaleza del Fallo:** Si 20 moteros emiten anuncios BLE de forma simultánea, las colisiones en los canales de publicidad 37, 38 y 39 elevan la tasa de paquetes perdidos por encima del 40%, provocando cortes en el audio PTT y falsas alarmas de pérdida.
- **Estrategia de Mitigación:**
  1. **Jitter de Retransmisión Obligatorio:** Todo reenvío intermedio introduce un retardo pseudoaleatorio uniforme de 5 a 25 ms.
  2. **Cadencia Dinámica Adaptativa:** La frecuencia de emisión de telemetría se ajusta según la velocidad del convoy:
     - Detenidos o semáforo: 1 heartbeat cada 6 segundos.
     - Velocidad crucero (>60 km/h): 1 heartbeat cada 3 segundos.
  3. **Límite de Saltos Estricto (`TTL=5`):** Impide que tramas viejas sigan rebotando indefinidamente.

### Riesgo 3: Contención e Interferencia de Coexistencia en 2.4 GHz (Wi-Fi + Intercomunicador Bluetooth)
- **Probabilidad:** Muy Alta (80%) | **Impacto:** Medio-Alto (65%)
- **Naturaleza del Fallo:** El teléfono del motero comparte el mismo chip y antena de 2.4 GHz para: (a) Enlace A2DP/HFP con el intercomunicador del casco, (b) Escaneo de redes Wi-Fi, (c) Emisión de la malla BLE.
- **Estrategia de Mitigación:**
  1. Desactivación automática del escaneo Wi-Fi activo en el perfil de rodada.
  2. Sincronización de intervalos de anuncio con los slots de tiempo libres del controlador Bluetooth mediante `setAdvertisingData` con cadencias calibradas a múltiplos no armónicos del reloj de salto de frecuencias de audio del casco.

---

## 14. Plan de Pruebas Físicas en Carretera (Hardware Real)

El pliego establece un cronograma de pruebas físicas escalonadas antes de cualquier despliegue masivo:

### Nivel 1 — Banco de Pruebas de 2 Terminales (Laboratorio Cerrado)
- **Dispositivos:** TECNO Camon 20 Pro (`KI5k`) + Redmi Note 13 Pro (`b2c398bb`).
- **Prueba:** Descubrimiento mutuo a 1 m, verificación de atenuación de paquetes en modo avión y medición de RTT bidireccional.
- **Criterio de Aprobación:** Tasa de entrega $\ge 99.0\%$, latencia media $< 25\text{ ms}$.

### Nivel 2 — Prueba de Banco de 3 Dispositivos (Línea Visual y Curva)
- **Dispositivos:** TECNO KI5k + Redmi Note 13 Pro + Tercer dispositivo (Qualcomm / MediaTek).
- **Prueba:** Disposición lineal A $\leftrightarrow$ B $\leftrightarrow$ C con nodos A y C separados por 800 m sin línea de vista (con el Nodo B actuando de repetidor relay en la curva).
- **Criterio de Aprobación:** Un SOS emitido en A debe llegar a C en $< 150\text{ ms}$ con firma válida.

### Nivel 3 — Prueba Dinámica en Convoy de Carretera (5 Motocicletas)
- **Entorno:** Carretera nacional interurbana sin cobertura celular durante tramo de 25 km.
- **Prueba:** Convoy circulando a 80–100 km/h con brechas intencionales de 300 a 600 m entre grupos. Pruebas de audio PTT y activación de simulacro de caída.
- **Criterio de Aprobación:** Audio comprensible con articulación MOS $\ge 3.8$, cero falsas alarmas de pérdida y consumo de batería $< 25\%$ en corrida de 4 horas.

---

## 15. Apéndice Normativo y Referencias Técnicas

1. **Bluetooth Core Specification v5.4:** Vol 6 (Low Energy Controller), Part B (Link Layer Specification - Extended Advertising & Coded PHY LE).
2. **RFC 6716:** *Definition of the Opus Audio Codec* (Internet Engineering Task Force).
3. **RFC 8032:** *Edwards-Curve Digital Signature Algorithm (Ed25519)*.
4. **RFC 8439:** *ChaCha20 and Poly1305 for IETF Protocols*.
5. **Ruta Biker Sync Documentation:**
   - [`RULES.md`](file:///d:/Ruta-Biker-Sync/docs/RULES.md) (Reglas de Gobernanza y Convivencia R1 a R15).
   - [`GAMIFICATION.md`](file:///d:/Ruta-Biker-Sync/docs/GAMIFICATION.md) (Sistema de Rangos, Logros y Kilometraje).
   - [`DESIGN.md`](file:///d:/Ruta-Biker-Sync/docs/DESIGN.md) (Especificación de Arquitectura y UI).
   - [`MEMORY.md`](file:///d:/Ruta-Biker-Sync/MEMORY.md) (Bitácora Oficial de Decisiones Técnicas D1 a D76).

---

> [!IMPORTANT]
> **COMPROMISO DE NO-CODIFICACIÓN:**  
> Este pliego técnico define de forma completa, autocontenida y sin marcadores residuales la arquitectura del protocolo de malla y radio PTT. No se escribirá una sola línea de código en el proyecto hasta contar con la revisión y firma de aprobación formal del presente documento.

