# Plan de Implementación — Perfil (Piloto, Mi Moto y Garage)

> Documento maestro de seguimiento por fases para la reestructuración de **Perfil** (`Piloto` · `Mi Moto` · `Garage`) y el ajuste de contrastes/transparencias *Dark Elite*.

---

## Estado General

| Fase | Nombre | Estado | Commit / Evidencia |
| :--- | :--- | :--- | :--- |
| **Fase 1** | Base visual, `Piloto / Mi Moto / Garage`, motor de reglas y UI base | ✅ **Completada** | `5f1fd05` (`:app:testStandardDebugUnitTest` verde) |
| **Fase 2** | Edición detallada de subcampos por componente + suma automática de `MeshOdometer` | ✅ **Completada** | `:app:testStandardDebugUnitTest` verde (`1m 30s`) |
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

## Fase 2 — Edición Detallada por Componente + Odómetro Automático (✅ Completada)

### Qué quedó implementado y verificado
1. **Edición de subcampos por componente (`BikeGarageEditDialogs.kt` + `BikeGarageSection.kt`):**
   - Al tocar cualquier componente se abre su editor específico (`GarageComponentEditDialog`): neumáticos (marca, modelo, construcción, DOT, PSI, mm), frenos (compuesto, % vida, intervalo), aceite (marca, viscosidad, base, JASO, intervalos), filtros (tipo, referencia, intervalo), cadena/kit, bujías, rodamientos, líquido de frenos, batería y refrigerante.
2. **Integración automática con `MeshOdometer` (`RideService.kt`):**
   - Al detener una rodada real (`!isUsingSimulatedTransport`) con `odo.totalAccumulatedKm > 0`, `RideSessionController` invoca `onRideDistanceKmRecorded(rideKm)` conectado a `BikeGarageRepository.addRideDistanceKm(...)`, sin modificar `MeshOdometer.kt`, GATT, crypto ni voz.
3. **Tests (`BikeGarageRepositoryTest.kt`):**
   - Verificados `addRideDistanceKm`, evaluación de semáforos y subcampos editados (`BUILD SUCCESSFUL in 1m 30s`).

---

## Fase 3 — Respaldo en Backend PostgreSQL (⏳ Opcional / Futuro)

1. Migración SQL en `backend/src/db/` para almacenar el JSON/estado del Garage por usuario (`user_bike_garage`).
2. Endpoints autenticados `GET /v1/users/me/garage` y `PUT /v1/users/me/garage`.
3. Sincronización offline-first desde Android cuando haya conectividad.
