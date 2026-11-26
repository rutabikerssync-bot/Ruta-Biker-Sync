# Plan de Implementación — Perfil (Piloto, Mi Moto y Garage)

> Documento maestro de seguimiento por fases para la reestructuración de **Perfil** (`Piloto` · `Mi Moto` · `Garage`) y el ajuste de contrastes/transparencias *Dark Elite*.

---

## Estado General

| Fase | Nombre | Estado | Commit / Evidencia |
| :--- | :--- | :--- | :--- |
| **Fase 1** | Base visual, `Piloto / Mi Moto / Garage`, motor de reglas y UI base | ✅ **Completada** | `5f1fd05` (`:app:testStandardDebugUnitTest` verde) |
| **Fase 2** | Edición detallada de subcampos por componente + suma automática de `MeshOdometer` | ⏳ **Pendiente** | Próximo paso |
| **Fase 3** | Sincronización / respaldo en Backend PostgreSQL (`/v1/users/me/garage`) | ⏳ **Opcional / Pendiente** | Por definir tras validar Fase 2 en hardware |

---

## Fase 1 — Base Visual, Mi Moto, Cerebro Garage y UI Base (✅ Completada)

### Qué quedó implementado y verificado
1. **Contrastes y transparencias (`ProfileScreen.kt`):**
   - Selector superior segmentado de 3 pestañas: `Piloto` | `Mi Moto` | `Garage` (con indicador `Garage •` cuando hay alertas).
   - Tarjetas translúcidas *Dark Elite* (`alpha = 0.82f–0.86f`) y etiquetas de metadatos con mayor contraste (`#CBD5E1` / `#94A3B8`).
2. **Sub-vista `Mi Moto` (`MotorcycleProfileSection.kt`):**
   - Foto de la moto con degradado inferior oscuro, datos técnicos y odómetro visible.
   - Selector de `CoolingType` (`Aire / Aceite` vs `Líquida`), que controla la visibilidad del módulo de **Líquido Refrigerante** en `Garage`.
3. **Modelos y Cerebro (`BikeGarageModels.kt` + `BikeGarageRepository.kt`):**
   - Semáforo (`OK` 🟢, `WARNING` 🟡 >= 80%, `CRITICAL` 🔴 >= 100%) evaluando siempre el criterio más severo entre **Km** (`kmRestantes`) y **Tiempo** (`diasRestantes`).
   - **Seguridad Crítica:** Neumáticos Del./Tras. (> 5 años `CRITICAL`, dibujo < 2 mm `WARNING`, <= 1 mm `CRITICAL`) y Pastillas de Freno Del./Tras. (80% `WARNING`, 100% o < 20% vida `CRITICAL`).
   - **Tren Motriz, Filtros y Transmisión:**
     - Aceite de Motor (Marca, Viscosidad, Base `Mineral`/`Semisintético`/`Sintético`, Norma `JASO MA`/`MA2`/`MB`, km/fecha).
     - **Filtro de Aceite** (`Cartucho/Papel`, `Blindado/Rosca`, `Malla Lavable`).
     - **Filtro de Aire** (`Papel/Seco`, `Espuma Aceitada`, `Alto Flujo Lavable`).
     - **Filtro de Gasolina** (`En línea/Externo`, `Sumergido/Bomba`, `Lavable`).
     - Kit de Arrastre (`O-Ring`, `X-Ring`, `Sin mantenimiento`, engrase cada `500 km`, diálogo *¿Resetear odómetro a 0 o mantener histórico?*).
     - Bujías (`Cobre`, `Platino`, `Iridio`) y Rodamientos (`Rueda Del.`, `Rueda Tras.`, `Dirección`).
   - **Fluidos y Eléctrico:** Líquido de Frenos (`DOT 3/4/5.1`, caducidad 2 años), Batería (`Plomo-Ácido`/`AGM`/`Litio`, 2 años `WARNING` / 4 años `CRITICAL`) y Líquido Refrigerante (condicional a `CoolingType.LIQUID`).
   - **Bitácora de Averías y Gastos:** Registro de fallas (`Abierta` / `En taller` / `Resuelta`), registro de gastos y cálculo automático de `costPerKmUsd`.
4. **Tests unitarios (`BikeGarageRepositoryTest.kt`):**
   - Cubre todas las reglas anteriores con fechas y km simulados (`BUILD SUCCESSFUL`).

---

## Fase 2 — Edición Detallada por Componente + Odómetro Automático (⏳ Pendiente — Siguiente Paso)

> **Nota de transparencia:** en Fase 1 los botones de acción rápida (*Cambiar*, *Renovar*, *Engrasar*, *Purgar*, *Servicio*) actualizan el último km y fecha al odómetro actual, pero **aún falta** la pantalla/diálogo para editar los subcampos específicos de cada pieza en UI y la conexión automática al cerrar una rodada.

### Tareas de Fase 2
1. **Edición de subcampos por componente (`BikeGarageEditDialogs.kt`):**
   - Al tocar la fila de cada componente en `BikeGarageSection.kt`, abrir su editor específico:
     - **Neumáticos:** Marca, Modelo, `Tubeless`/`Con cámara`, código `DOT`, PSI recomendado, profundidad actual (mm) y fecha de montaje.
     - **Pastillas de Freno:** Compuesto (`Orgánicas`, `Semimetálicas`, `Sinterizadas HH`, `Cerámicas`), % vida útil restante e intervalo (km).
     - **Aceite de Motor:** Marca, Viscosidad (`10W-30`, `10W-40`, `10W-50`, `15W-50`, `20W-50`), Base y Norma `JASO`, intervalo km/días.
     - **Filtros (Aceite / Aire / Gasolina):** Tipo de filtro, Marca/Referencia e intervalo km/días.
     - **Kit de Arrastre / Bujías / Rodamientos:** Tipo de cadena/bujía/ubicación e intervalos.
     - **Líquido de Frenos / Batería / Refrigerante:** Tipo `DOT`, tipo de batería + voltaje nominal, tecnología `OAT`/`HOAT`/`IAT`.
2. **Integración automática con `MeshOdometer` / Fin de Rodada:**
   - Al finalizar una rodada real con distancia > 0 km, invocar `BikeGarageRepository(context).addRideDistanceKm(state, kmRecorridos)` para actualizar el odómetro de la moto automáticamente (manteniendo la calibración manual disponible).
   - *Restricción:* no modificar `MeshOdometer.kt`, GATT, crypto ni voz; solo leer el resultado de distancia al cerrar sesión de rodada.
3. **Verificación de Fase 2:**
   - Tests unitarios para persistencia/edición de subcampos e incremento automático de odómetro al finalizar rodada.
   - Suite completa `:app:testStandardDebugUnitTest --no-daemon` verde y captura visual en dispositivo.

---

## Fase 3 — Respaldo en Backend PostgreSQL (⏳ Opcional / Futuro)

1. Migración SQL en `backend/src/db/` para almacenar el JSON/estado del Garage por usuario (`user_bike_garage`).
2. Endpoints autenticados `GET /v1/users/me/garage` y `PUT /v1/users/me/garage`.
3. Sincronización offline-first desde Android cuando haya conectividad.
