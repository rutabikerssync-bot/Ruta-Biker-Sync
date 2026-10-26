# Plan de Pruebas de Usabilidad (UX Test Plan) — Ruta Biker Sync

**Documento:** `docs/UX_TEST_PLAN.md`  
**Versión de App:** `uxTestDebug` (`ALLOW_DEFAULT_TEST_RIDE_KEY=true`)  
**Objetivo:** Evaluar la experiencia de usuario con 6 motociclistas reales en condiciones estáticas (Fase A) y en rodada controlada (Fase B), sin tocar el teléfono en marcha.

---

## 1. Gesto Único del Panel de Moderador

Para acceder a los controles de moderador en la versión `uxTest`:
* **Gesto oficial:** Tocar la **insignia ámbar `UX TEST · [Px]`** situada en la esquina superior derecha del encabezado de la app.
* **Funciones del diálogo de moderador:**
  1. **Selección de Participante:** Configura el ID activo (`P0` a `P6`). `P0` es el teléfono de apoyo del moderador para pruebas sin contaminar métricas.
  2. **Selector de Rol en Rodada (Fuente de Verdad):**
     * `Soy Líder (P1)`: Configura el dispositivo como líder (`leaderShortId = 1u`, `localShortId = 1u`).
     * `Soy Miembro (P2–P6)`: Configura el dispositivo como miembro (`leaderShortId = 1u`, `localShortId = 2u..6u`).
     * **Comportamiento de "Seguir Líder":** Al pulsar el botón "Seguir Líder" en el mapa, la cámara se centra y bloquea durante 10 segundos en las coordenadas BLE del líder (P1). Si el usuario es el líder, se centra en su propia posición.
  3. **Cronómetro de Tareas T1–T10:** Registro automático de tiempos y nivel de éxito (`Solo (2)`, `Con Ayuda (1)`, `Fallo (0)`).
  4. **Interruptor "SOS por malla (prueba)":** Desactivado por defecto. El botón SOS es 100% local a menos que el moderador active este switch.
  5. **Simular Compañero Perdido (T8):** Genera alerta local inmediata de rezago sin alterar la malla.
  6. **Exportar / Compartir JSON:** Guarda en almacenamiento local, copia al portapapeles y abre `Intent.ACTION_SEND` (Share Intent).

---

## 2. Invitación por Código QR y Permisos de Cámara

* **Regla R17 (Confirmada por Oscar, 2026-10-26):** El código QR para invitar y afiliar miembros a un grupo únicamente puede ser generado y compartido por el **Presidente** y el **Vicepresidente** del club (`ClubRole.PRESIDENT`, `ClubRole.VICE_PRESIDENT`). Los demás roles tienen la generación bloqueada.
* **Escaneo Real con Cámara:** Se utiliza `zxing-android-embedded:4.3.0` integrado mediante `ScanContract`.
  * **Permiso solicitado:** `android.permission.CAMERA` en tiempo de ejecución.
  * **Comportamiento si el usuario lo deniega o cancela:** La app no falla; muestra mensaje indicativo y permite ingresar el **código alfanumérico manual de respaldo** (ej. `RUTA-TEST-01`).
* **Botón de Simulación QR:** Oculto para los 6 testers. Únicamente visible cuando el participante activo es `P0` (teléfono de apoyo del moderador).

---

## 3. Cobertura de Cartografía Offline (`sample_corridor.pmtiles`)

* **Ubicación:** `app/src/main/assets/tiles/sample_corridor.pmtiles` y `dark_corridor_style.json`.
* **Área geográfica empaquetada (Bounding Box):**
  * Suroeste: Longitud `-99.2500`, Latitud `18.9000`
  * Noreste: Longitud `-99.0500`, Latitud `19.4500`
  * **Ciudad / Tramo:** Corredor Ciudad de México (CDMX) – Cuernavaca (México), con buffer de 10 km.
* **¿Qué ve el usuario fuera del corredor?**
  * MapLibre muestra el fondo oscuro de alto contraste (`#0B0E14`) y la atribución visible de OSM.
  * El motor de fallback de [LiveMapScreen.kt](file:///d:/Ruta-Biker-Sync/android/app/src/main/java/com/rutabikersync/app/feature/livemap/LiveMapScreen.kt) dibuja sobre un Canvas 2D los marcadores de los motociclistas, sus rumbos, vectores de distancia y estados de SOS.
* **Instrucciones para generar teselas de la zona real (Fase B):**
  1. Descargar extracto OSM de la región deseada (ej. desde Geofabrik o Protomaps).
  2. Generar el archivo PMTiles vectorial con Planetiler:
     ```bash
     java -jar planetiler.jar --osm-path=region.osm.pbf --output=corridor_custom.pmtiles --bounds=minLon,minLat,maxLon,maxLat
     ```
  3. O extraer con la CLI de Protomaps:
     ```bash
     pmtiles extract https://build.protomaps.com/20261026.pmtiles app/src/main/assets/tiles/sample_corridor.pmtiles --bbox=minLon,minLat,maxLon,maxLat
     ```

---

## 4. Tareas de Evaluación (T1 a T10)

| ID | Nombre de Tarea | Evento Registrado | Criterio de Éxito |
| :---: | :--- | :---: | :--- |
| **T1** | Permisos iniciales | `PERMISSIONS_GRANTED` | Acepta BLE y Ubicación |
| **T2** | Perfil y color de pin | `PROFILE_SAVE` | Nombre y color de pin elegidos |
| **T3** | Crear grupo del club (Solo Líder P1) | `CREATE_GROUP` | Grupo creado con éxito |
| **T4** | Invitar y unirse con QR | `SCAN_QR_SUCCESS` / `JOIN_CODE` | Escanea QR o ingresa código de respaldo |
| **T5** | Iniciar rodada activa | `START_RIDE` | Ingresa al mapa en vivo (C1) |
| **T6** | Seguir líder en mapa | `TAP_FOLLOW_LEADER` | Cámara se centra en el líder por 10 s |
| **T7** | Activar modo guantes | `TOGGLE_GLOVES` | Botones crecen a ≥ 72 dp |
| **T8** | Interpretar alerta de compañero perdido | `ALERT_PEER_LOST` | Comprende diálogo de rezago |
| **T9** | Activar SOS de prueba | `TAP_SOS` (detalles: `task=T9`) | Botón SOS pulsado, alerta local visible |
| **T10** | Detener rodada con pulsación larga (3 s) | `STOP_RIDE` | Retorno seguro a la pantalla de rodadas |

---

## 5. Tabla de Participantes (6 Usuarios + P0 Apoyo)

| Código | Rol de Club | Teléfono | Android | Experiencia Apps Grupo | Usa Guantes | Rol Convoys |
| :---: | :--- | :--- | :---: | :---: | :---: | :---: |
| **P0** | Moderador (Apoyo) | Dispositivo Moderador | Cualesquiera | Experto | No | Apoyo |
| **P1** | Presidente / Líder | Por definir en piloto | Por definir | Alta | Sí | Líder |
| **P2** | Vicepresidente / Cierre | Por definir en piloto | Por definir | Media | Sí | Miembro |
| **P3** | Miembro Directiva | Por definir en piloto | Por definir | Media | Sí | Miembro |
| **P4** | Miembro (Poca tecnología) | Por definir en piloto | Por definir | Baja | Sí | Miembro |
| **P5** | Miembro Regular | Por definir en piloto | Por definir | Media | No | Miembro |
| **P6** | Invitado / Prospecto | Por definir en piloto | Por definir | Baja | No | Miembro |
