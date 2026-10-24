# GAMIFICATION — Sistema de Rangos, Logros y Kilometraje de Ruta Biker Sync

**Versión:** 1.0
**Fecha:** 2026-10-23
**Estado:** Aprobado por el dueño del proyecto. Se implementará en la Fase 6.
**Referencias:** RULES.md (§8), BACKEND_LICENSING.md (§4), ICONS.md (§7), SCREENS.md (F2).

---

## 1. Principios de Gamificación

1. **Recompensa el esfuerzo real:** No solo importa cuántas veces ruedas, sino cuántos kilómetros haces.
2. **Progresión visible y alcanzable:** Insignias fáciles al principio (10 km) para enganchar, y desafíos épicos al final (100.000 km).
3. **Constancia:** La racha mensual premia al que rueda siempre, no solo al que hace una rodada épica al año.
4. **Roles de rodada:** Recompensa el compañerismo (ser Scout, Sweep, Capitán, ayudar a un caído).
5. **Privacidad:** Todos los kilómetros se calculan localmente en el teléfono y se envían como agregados (números), nunca como ubicaciones.

---

## 2. Familias de Insignias

| Familia | Quién las otorga | ¿Cruzable entre grupos? | Propósito |
|---|---|---|---|
| **Rango** | Automático (sistema) | No | Progreso personal del rider |
| **Logros (Kilometraje)** | Automático (sistema) | No (personales) | Recompensa la distancia recorrida |
| **Logros (Constancia)** | Automático (sistema) | No (personales) | Recompensa la racha mensual |
| **Logros (Experiencia)** | Automático (sistema) | No (personales) | Recompensa hitos únicos (noche, lluvia, SOS) |
| **Honor** | Líder del grupo | **Sí** (requiere invitación a rodada) | Reconocimiento entre clubes |
| **Mesa Directiva** | Líder del grupo | No | Cargo interno del club |

---

## 3. Logros de Kilometraje (Acumulativo)

*Se suman todos los km de rodadas válidas (RULES.md R6.6).*

| Insignia | Kilómetros | Dificultad | Cálculo estimado |
|---|---|---|---|
| 🥾 **Primeros Pasos** | 10 km | Muy fácil | 1 rodada corta |
| 🛵 **Rodador** | 50 km | Fácil | 2 rodadas cortas |
| 🏍️ **Caminante** | 100 km | Fácil | 4 rodadas cortas |
| 🛣️ **Viajero** | 500 km | Media | 10 rodadas medianas |
| 🌄 **Explorador** | 1.000 km | Media | 20 rodadas medianas |
| 🏔️ **Aventurero** | 5.000 km | Alta | 50 rodadas |
| 🌍 **Trotamundos** | 10.000 km | Alta | 100 rodadas |
| 🗺️ **Cartógrafo** | 25.000 km | Muy alta | 200 rodadas |
| 🌎 **Nómada** | 50.000 km | Épica | 500 rodadas |
| 🌌 **Legendario** | 100.000 km | Legendaria | Solo los más veteranos |

---

## 4. Logros de Constancia (Racha Mensual)

*Se pierde la racha si pasa un mes natural completo sin ninguna rodada válida. No se recupera.*

| Insignia | Requisito |
|---|---|
| 🔥 **Racha de 3** | Rodar al menos 1 vez al mes durante 3 meses consecutivos |
| 🔥🔥 **Racha de 6** | 6 meses consecutivos |
| 🔥🔥🔥 **Racha de 12** | 12 meses consecutivos |
| ⚡ **Imparable** | 24 meses consecutivos |
| 🌟 **Eterno** | 36 meses consecutivos |

---

## 5. Logros de Experiencia (Únicos)

| Insignia | Requisito |
|---|---|
| 🎉 **Primera Rodada** | 1 rodada válida |
| 🌙 **Rodada Nocturna** | 1 rodada válida que empiece después de las 20:00 o termine después de las 22:00 |
| 🌧️ **Bajo la Lluvia** | 1 rodada válida con lluvia registrada |
| 📡 **Sin Cobertura** | 1 rodada válida donde la mesh se mantuvo >30 min sin internet |
| 🏔️ **Rodada de Montaña** | 1 rodada válida con >500 m de desnivel acumulado |
| 🚴 **Tres Grupos** | Rodar con 3 grupos distintos (puede ser como invitado) |
| 🛠️ **Mecánico de Ruta** | Ayudar a un rider con una avería (marcado por el líder) |
| 🆘 **Primer SOS** | Recibir o responder a un SOS |
| 🧭 **Sweep de Honor** | Ser sweep 5 veces |
| 🎯 **Capitán de Rodada** | Ser capitán 10 veces |
| 🛡️ **Scout Confiable** | Ser scout 15 veces |

---

## 6. Insignias de Honor (Cruzables)

*Solo se pueden otorgar a miembros de otros grupos si participaron como invitados a una rodada (D43).*

| Insignia | Condición |
|---|---|
| 🏅 **Novato** | 0–4 rodadas válidas |
| 🏅 **Rider** | 5–19 rodadas válidas |
| 🏅 **Veterano** | 20–49 rodadas válidas |
| 🏅 **Élite** | 50–99 rodadas válidas |
| 🏅 **Leyenda** | 100+ rodadas válidas |
| 🔥 **Fundador** | Creador del grupo (marca permanente) |
| 🎖️ **Honor Genérico** | Otorgado por un líder por méritos varios |
| 🌙 **Rodada Nocturna** | 1 rodada nocturna como invitado |
| 🛣️ **500 km** | 500 km acumulados como invitado |
| 📡 **Sin Cobertura** | 1 rodada sin cobertura como invitado |
| 🚴 **3 Grupos** | 3 grupos distintos como invitado |

---

## 7. Sistema de XP (P9.3 — Aprobado para Fase 6)

*El rango sube por XP, no solo por número de rodadas.*

| Acción | XP |
|---|---|
| Rodada válida (base) | 10 XP |
| Por cada 50 km | +1 XP |
| Por ser capitán de rodada | +5 XP |
| Por ser sweep | +3 XP |
| Por ser scout | +2 XP |
| Por responder a un SOS | +10 XP |
| Por racha de 3 meses | +20 XP |
| Por insignia de honor recibida | +15 XP |

**Niveles por XP:**
- 🥉 Novato: 0 XP
- 🥈 Rider: 100 XP
- 🥇 Veterano: 500 XP
- 💎 Élite: 2.000 XP
- 👑 Leyenda: 5.000 XP

---

## 8. Cambios en la Base de Datos (Fase 2.2 y Fase 6)

### 8.1 Tabla `rides`
- `distance_km REAL` (calculado localmente)
- `elevation_gain_m REAL`
- `started_at_night BOOLEAN`
- `rained BOOLEAN`
- `offline_minutes INT`

### 8.2 Tabla `rank_progress`
- `total_km REAL DEFAULT 0`
- `streak_months INT DEFAULT 0`
- `last_ride_month CHAR(7)` (formato YYYY-MM)
- `xp INT DEFAULT 0`

### 8.3 Tabla `badges`
- Se mantiene `family` (`rank`, `achievement`, `honor`).
- Los logros de km, racha y experiencia son `achievement`.
- Los de honor siguen siendo `honor`.
- Se agrega trigger para evitar insignias de Directiva a externos.

### 8.4 Triggers Nuevos (Fase 6)
- `trg_rides_update_km_and_badges`: al cerrar una rodada válida, actualiza `rank_progress.total_km` y otorga insignias de km si corresponde.
- `trg_update_streak`: al cambiar de mes, evalúa y actualiza la racha.
- `trg_badges_honor_requires_guest_ride`: valida que solo se den insignias de honor a invitados de rodada.
- `trg_badges_officer_must_be_member`: valida que las insignias de Directiva sean solo a miembros del grupo.
- `trg_groups_minimum_directiva`: valida que un grupo tenga al menos Presidente, Vicepresidente y Sargento de Armas.

---

## 9. Cambios en Pantallas (F2)

La pantalla de **Rango e Insignias** (`SCREENS.md` F2) debe mostrar:
1. **Cabecera:** Rango actual (Novato, Rider, etc.) con barra de XP hacia el siguiente.
2. **Progreso de Km:** Próxima insignia de kilometraje (ej. "A 250 km de Viajero") con barra.
3. **Racha actual:** "🔥 3 meses consecutivos".
4. **Rejilla de insignias:** Organizada por familia (Rango, Km, Racha, Experiencia, Honor), con las bloqueadas en gris y las desbloqueadas a color.
5. **Expediente cruzado:** Insignias de honor recibidas de otros grupos, visibles para todos los miembros del grupo propio.

---

## 10. Tareas de Implementación (Fase 6)

- [ ] Agregar columnas `distance_km`, `elevation_gain_m`, `started_at_night`, `rained`, `offline_minutes` a `rides`.
- [ ] Agregar columnas `total_km`, `streak_months`, `last_ride_month`, `xp` a `rank_progress`.
- [ ] Implementar triggers de kilometraje, racha y validación de insignias.
- [ ] Implementar cálculo local de distancia en Android (Room + cierre de rodada).
- [ ] Implementar la UI de F2 con barras de progreso y rejilla de insignias.
- [ ] Diseñar el arte final de las insignias (marcadas `TODO(diseño)` en `ICONS.md` §7).
- [ ] Pruebas JUnit de la lógica de desbloqueo de insignias.
- [ ] Pruebas de integración con PostgreSQL para los triggers.

---

## 11. Decisiones Registradas

| # | Decisión | Fecha |
|---|---|---|
| **D48** | Sistema de logros de kilometraje, constancia y experiencia aprobado (`GAMIFICATION.md`). | 2026-10-23 |
| **D49** | Sistema de XP y progresión de rangos aprobado para Fase 6 (P9.3). | 2026-10-23 |
| **P9.1** | Curva de kilometraje aprobada (10 km a 100.000 km). | 2026-10-23 |
| **P9.2** | Logros de km y racha son personales, no cruzables. | 2026-10-23 |
| **P9.3** | XP aprobado para Fase 6. | 2026-10-23 |
