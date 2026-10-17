# DESIGN — Ruta Biker Sync

Versión 1.0 · 2026-10-16

## 1. Principios de diseño
1. **Seguridad ante todo:** la app no debe distraer mientras se conduce.
2. **Modo guantes:** botones grandes (≥72 dp), alto contraste, pocas pantallas, vibración y audio como retroalimentación.
3. **Voz primero:** la información importante llega por audio.
4. **Funciona sin red:** todo lo esencial de una rodada vive en el teléfono.
5. **Claridad de roles:** estatus, cargo y rango se muestran siempre por separado.

## 2. Arquitectura

```
UI (Compose): Mapa · Modo rodada · Grupo · Perfil · Cuenta
        │
Servicios en foreground: Posición · Pérdida/Reconexión · Desvío · SOS · Voz
        │
Protocolo: mensajes con ID, TTL, prioridad, firma y deduplicación
        │
Transport (interfaz): BleAdvTransport · NearbyTransport · (futuro) LoRa
        │
Seguridad: credenciales firmadas · clave de rodada · cifrado sellado de emergencia
        │
Datos locales: Room cifrado · PMTiles/MBTiles · rutas · documentos cifrados
        │
Backend (solo licencias, grupos y auditoría): ver BACKEND_LICENSING.md
```

## 3. Mesh

**Transporte de posición y eventos:** BLE advertising "emitir y escuchar" con reenvío por inundación controlada (ID único + TTL; cada nodo retransmite una sola vez). Advertising extendido y Coded PHY cuando el teléfono lo soporta; modo legacy en caso contrario.

**Transporte de voz y datos pesados:** Nearby Connections o L2CAP, con reenvío de 2 saltos para audio.

**Heartbeat:** cada 3 s. Se declara pérdida tras 4 a 5 latidos perdidos (~12 s) con histéresis para evitar falsas alarmas.

**Prioridad:** SOS > pérdida/reconexión > posición > ruta > voz > estado.

**Paquete de posición (~20 bytes):** ID corto (2) · lat/lon (8) · velocidad y rumbo (2) · batería y flags (1) · secuencia + TTL (3) · etiqueta de autenticación (4).

**Límites:** hasta 12 a 15 nodos por mesh BLE. Pruebas de campo antes de fijar cifras.

**Pérdida de cobertura:** el nodo que detecta la pérdida emite `PEER_LOST` (~50 bytes). Un "anunciador" (último nodo con contacto o menor ID) la dice por TTS local; al volver se avisa "se reconectó".

## 4. Rutas
1. Trazar con BRouter (perfil moto) o importar GPX.
2. Descargar el corredor de la ruta (±10 km) en PMTiles.
3. Compartir solo la polilínea comprimida (20 a 100 KB) fragmentada con hash; cada miembro descarga sus propios tiles.
4. Alerta de desvío: >80 m de la ruta durante 8 s (tolerancia por velocidad); botón "voy a parar" para evitar falsas alarmas.

## 5. Voz por clips
- Mantén pulsado o botón del intercom, máx. 15 s, Opus 8–12 kbps (~10–15 KB).
- Cola ordenada con avatar de quien habla, ducking de música.
- Se reproduce y se borra; se descarta si la rodada no está activa.
- El audio llega al casco por el emparejamiento Bluetooth que ya tiene el teléfono (A2DP/HFP); no se sincronizan intercomunicadores.

## 6. Pantallas

| # | Pantalla | Contenido |
|---|---|---|
| 1 | Inicio de sesión | Entrar como miembro o activar cuenta líder |
| 2 | Mis grupos e invitaciones | Aceptar con costo visible ($2.50/mes) |
| 3 | Grupo | Miembros, estatus, cargos, semáforo de pagos, líderes |
| 4 | Perfil del grupo | Nombre, logo, país, transferencia |
| 5 | Rodadas | Crear, convocar, estado, coanfitrión |
| 6 | Mapa en vivo | Avatares, velocidad, estado, distancia al líder |
| 7 | Modo rodada | Hablar, SOS, señales, aviso de desvío, roles |
| 8 | Ruta | Trazar, importar, descargar, compartir |
| 9 | Perfil personal | Avatar, rango, insignias, ficha de emergencia |
| 10 | Bóveda | Documentos con alarmas de vencimiento |
| 11 | Suscripciones | Estado, vencimientos, enlace a Play |
| 12 | Insignias | Rango, logros, honor |

## 7. Perfil, avatar e insignias
- Avatar: foto o prediseñado, 64×64 WebP (~3 KB), se envía una vez al unirse y se guarda en caché.
- Rango visible junto al nombre; insignias de honor con el logo del grupo emisor.
- Ficha de emergencia: cifrada, con permisos por campo.

## 8. Emergencias
- SOS manual o detección de caída con cuenta regresiva de 20 s.
- Envío de posición a todo el grupo; el paquete de emergencia se desbloquea según RULES.md (sección 10).
- Pantalla de bloqueo con ficha para un auxiliador, solo si el usuario lo activó.

## 9. Sistema visual
- Tema oscuro por defecto, alto contraste, legible al sol.
- Colores por rango y por rol de rodada, nunca como único indicador (también iconos y texto).
- Tipografía grande; en modo rodada máximo 3 acciones en pantalla.
- Textos en español, inglés y portugués (recursos de traducción desde el inicio).
- Atribución de OpenStreetMap visible en el mapa.

## 10. Accesibilidad y seguridad de uso
- Control por voz para acciones comunes ("reportar parada", "¿quién va atrás?").
- Advertencias de uso responsable: configurar antes de salir.
- Gestión de batería: modo ahorro automático <20 %; guía por fabricante para evitar que maten el proceso.

## 11. Algoritmo del anunciador único

Objetivo: que solo un teléfono diga "X salió de la mesh", aunque varios lo detecten a la vez.

```
al detectar PEER_LOST(x, eventId):
    retardo = aleatorio(0, 1.0 s) + 0.5 s × posición_por_cercanía_reciente   # el último en tener contacto va primero
    retardo = min(retardo, 3.0 s)
    esperar retardo
    si ya recibí ANNOUNCED(eventId): callar y terminar
    emitir ANNOUNCED(eventId, mi_id)     # mensaje corto con prioridad alta
    decir por TTS
    contador[eventId] = 1
    si x sigue perdido a los 60 s y contador < 2: repetir una sola vez

al recibir ANNOUNCED(eventId, otro): cancelar mi anuncio pendiente
al recibir PEER_BACK(x): cancelar anuncios pendientes y decir "x se reconectó" (también con un único anunciador)
```

- Si dos nodos anuncian a la vez, gana el de menor ID y el otro corta su TTS en cuanto lo detecta.
- Máximo 2 anuncios por evento; si se pierde el mismo rider más de 3 veces en 10 min, se agrupa en un aviso ("Juan con conexión inestable").
- La mesh puede partirse en islas: cada isla elige su propio anunciador y al reunirse se unifican los eventos por `eventId`.

## 12. Cifrado sellado del paquete de emergencia

Objetivo: los líderes pueden abrir el paquete solo bajo las condiciones de RULES R10.3, y el servidor nunca lo ve.

1. Al unirse a una rodada (con consentimiento R15.1c), el teléfono del participante crea una clave simétrica aleatoria `Kp` y cifra el paquete (XChaCha20-Poly1305).
2. `Kp` se **divide en partes (Shamir)** entre los teléfonos de los líderes de la rodada, cada parte sellada con la clave pública **efímera** de ese líder, creada al iniciar la rodada y publicada en el manifiesto. Umbral: 2 partes si hay 2 o más líderes (coincide con "mayoría" de RULES R14), 1 si solo hay uno.
3. El paquete cifrado se replica en los teléfonos de los líderes; sin las partes de `Kp` no se puede abrir.
4. **SOS:** el teléfono del afectado añade al mensaje de SOS una parte propia de `Kp`, lo que completa el umbral con un solo líder.
5. **Declaración de emergencia:** un líder propone, otro confirma (R14.2) y se reúnen las partes.
6. Al abrirse, se registra el acceso (quién, cuándo, motivo), se limita a 60 min y se avisa al afectado.
7. Al cerrar la rodada, todas las claves efímeras y partes se destruyen, y el paquete cifrado se borra de los teléfonos de los líderes.
8. Limitación honesta: si un líder es el único presente y el afectado no emite SOS, el control es el de la política de la app, no solo matemático. Por eso la declaración exige confirmación y queda en auditoría.

## 13. Flujo de onboarding

**Miembro nuevo (4 pantallas):** (1) qué es una rodada y cuándo se comparte la ubicación, (2) permisos de ubicación y Bluetooth con explicación, (3) perfil y avatar, (4) consentimientos separados y ficha de emergencia opcional.

**Primera vez como líder (5 pantallas):** (1) qué incluye el plan de $15/mes, (2) cómo se cobra cada miembro ($2.50/mes) y cómo ve el semáforo, (3) crear el grupo con nombre y logo únicos, (4) invitar miembros y asignar estatus y cargos, (5) crear la primera rodada. Incluye un video corto de unos 60 s en cada flujo.

**Guía de batería por fabricante:** pantalla obligatoria antes de la primera rodada, según marca detectada.

## 14. Moderación en la app
- Botón **Reportar rider** en el perfil durante y después de una rodada.
- Silenciar localmente a un rider con un toque.
- Bandeja de reportes para líderes con acciones: advertir, silenciar, expulsar de la rodada, proponer retiro del grupo (según RULES R13 y R14).
- Reglas de convivencia visibles y aceptadas al registrarse.

## 15. Decisiones de diseño abiertas
1. Umbral exacto de pérdida de cobertura tras pruebas de campo.
2. Estrategia de cobro de cupos múltiples en Play (P4 en RULES.md).
3. Estética de marca (logo, paleta) de Ruta Biker Sync.
