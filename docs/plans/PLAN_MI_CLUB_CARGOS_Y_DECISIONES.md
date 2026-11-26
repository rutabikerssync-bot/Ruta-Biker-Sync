# Plan de Implementación — Mi Club: Nuevos Cargos y Módulo de Decisiones (Votación Secreta)

> Documento maestro por fases para incorporar en **Mi Club**:
> 1. **Creación y asignación de nuevos cargos personalizados** (respetando regla P7: nunca a `guest`).
> 2. **Botón «Decisiones» con votación secreta real** y selección granular de quién vota (`Directiva`, `Fundadores`, `Full Members`, `Prospectos`).

---

## Estado General

| Fase | Nombre | Estado | Alcance |
| :--- | :--- | :--- | :--- |
| **Fase 1** | Backend SQL + Endpoints de Cargos Personalizados y Decisiones Secretas | ✅ **Completada** | `0025`, `clubDecisions.ts`, `app.ts`, `185/185` tests verdes (`npm test`) |
| **Fase 2** | Dominio Android + Repositorio + `ClubApiClient` | ✅ **Completada** | `ClubDecisionsModels`, `ClubDecisionsCoordinator`, `ClubApiClient`, `Phase1ViewModel`, `:app:testStandardDebugUnitTest` verde |
| **Fase 3** | UI en **Mi Club**: botón «Agregar Cargo» y módulo «Decisiones» | ⏳ **Pendiente** | Pantallas/hojas *Dark Elite*, plantillas rápidas, votación secreta, resultados y APK |

---

## Diseño Técnico

### 1. Nuevos Cargos Personalizados en Mi Club
Actualmente [ClubRole](file:///d:/Ruta-Biker-Sync/android/app/src/main/java/com/rutabikersync/app/feature/groups/ClubDomain.kt#L89-L108) solo admite 7 valores fijos.
- **Tabla `club_custom_roles`:** `id`, `group_id`, `title` (ej. *Fotógrafo de Ruta*, *Mecánico*, *Relaciones Públicas*), `badge_icon`, `can_create_ride`, `can_moderate`, `created_by`, `created_at`.
- **Asignación:** los miembros `FULL` o `PROSPECT` (nunca `GUEST` por regla P7) pueden recibir un cargo oficial o un cargo personalizado del club.
- **Endpoints:**
  - `GET /v1/groups/:id/custom-roles`
  - `POST /v1/groups/:id/custom-roles` (solo Directiva/Fundador)
  - `DELETE /v1/groups/:id/custom-roles/:roleId`
  - `PATCH /v1/groups/:id/members/:userId/custom-role`

### 2. Módulo «Decisiones» (Votación Secreta)
Separado del módulo `leader_votes` (R14.2 sanciones entre líderes) para no romper las pruebas ni las reglas R14 existentes.

- **¿Quién vota y quién ve la votación? (Visibilidad estricta):**
  Al crear una decisión se marca uno o más grupos habilitados (`allowed_audiences`):
  - `DIRECTIVA` (líderes / mesa directiva)
  - `FOUNDERS` (fundadores del club)
  - `FULL_MEMBERS` (miembros full)
  - `PROSPECTS` (prospectos)
  *(Los invitados `GUEST` nunca participan ni ven decisiones del club).*
  - **Regla de visibilidad:** el backend filtra `GET /v1/groups/:id/decisions` por membresía/rol real; quienes no pertenecen a `allowed_audiences` **no ven** esa votación en su lista.
- **Regla de aprobación y quórum:**
  - **Mayoría simple por defecto** (gana la opción con más votos válidos al cierre `state = 'closed'`).
  - Campo opcional `min_quorum_percent` (`0`–`100`, defecto `0`): si es `> 0`, exige participación mínima sobre el padrón elegible.
- **4 plantillas rápidas en UX (Fase 3):** `Ruta de salida`, `Ascenso de miembro`, `Fondo / Evento`, `Interna Directiva`.

- **Secreto real del voto (en PostgreSQL):**
  Para garantizar que **ni la base de datos ni la directiva** puedan saber qué votó cada piloto:
  - `club_decisions`: `id`, `group_id`, `title`, `description`, `options JSONB` (ej. `["A favor", "En contra", "Abstención"]` o hasta 4 opciones), `allowed_audiences TEXT[]`, `state` (`open` | `closed`), `created_by`, `closes_at`, `closed_at`.
  - `club_decision_receipts`: `(decision_id, user_id, voted_at)` → **únicamente** registra que el usuario ya ejerció su voto (evita voto doble), **sin columna de opción elegida**.
  - `club_decision_tallies`: `(decision_id, option_index, votes_count)` → **únicamente** acumula contadores anónimos en transacción SQL.

- **Endpoints:**
  - `GET /v1/groups/:id/decisions` (lista decisiones + indica si el usuario actual es elegible y si ya votó, sin exponer votos individuales).
  - `POST /v1/groups/:id/decisions` (crear nueva decisión con audiencias seleccionadas; solo Directiva/Fundador).
  - `POST /v1/decisions/:decisionId/cast` (emitir voto secreto: verifica elegibilidad en `allowed_audiences`, inserta en `club_decision_receipts` e incrementa `club_decision_tallies` atómicamente).
  - `POST /v1/decisions/:decisionId/close` (cerrar decisión y consolidar opción ganadora).

---

## Desglose por Fases

### Fase 1 — Backend (`0025_club_custom_roles_and_decisions.sql` + API + Vitest)
1. Crear migración SQL `backend/src/db/migrations/0025_club_custom_roles_and_decisions.sql`.
2. Crear `backend/src/lib/clubDecisions.ts` con validación Zod, chequeo de elegibilidad por rol/estatus (`DIRECTIVA`, `FOUNDERS`, `FULL_MEMBERS`, `PROSPECTS`) y transacción anónima (`receipts` + `tallies`).
3. Exponer endpoints en `backend/src/app.ts`.
4. Tests en `backend/test/club_custom_roles_decisions.test.ts`:
   - Creación de cargo personalizado y rechazo de asignación a `guest` (P7).
   - Creación de decisión solo para `DIRECTIVA`, otra para `FULL_MEMBERS + PROSPECTS`, etc.
   - Verificación de voto secreto: un no-elegible recibe `403`, un elegible no puede votar dos veces (`409`), y el `GET` devuelve conteos sin `user_id` por opción.

### Fase 2 — Android: Dominio, Red y ViewModel
1. Modelos `CustomClubRole`, `DecisionAudience`, `ClubDecision`, `ClubDecisionOption` en `ClubDomain.kt`.
2. Métodos en `ClubApiClient.kt` + `ClubRepository.kt` + `Phase1ViewModel.kt`.
3. Tests unitarios en Android para reglas de elegibilidad y creación/cierre de decisiones.

### Fase 3 — Android UI en **Mi Club**
1. Botón **"Agregar Cargo"** en gestión del club (solo visible/habilitado para Directiva/Fundador) + selector al asignar cargo a un miembro.
2. Botón **"Decisiones"** en **Mi Club** → pantalla/modal *Dark Elite*:
   - Lista de decisiones abiertas y cerradas con badge de audiencia (`Solo Directiva`, `Fundadores y Full`, `Todo el Club`, etc.).
   - Diálogo **"+ Nueva Decisión"** con checkboxes para `Directiva`, `Fundadores`, `Full Members`, `Prospectos` y opciones de respuesta.
   - Tarjeta de voto secreto con aviso `"🔒 Voto secreto"` y resultado final al cerrar.
3. Compilación APK y pruebas unitarias verdes.
