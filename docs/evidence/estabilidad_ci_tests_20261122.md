# Estabilidad del CI de Android (2026-11-22)

## 1. Los runs #19–#26 no son la incidencia actual

Los runs citados (`#19`–`#26`, 21/11 20:51–21:06 GMT-4) pertenecen a la rama
`ci/fix-actions-postgres-gradlew` y fallaban por infraestructura del workflow:

- `44969052334`: `ERROR: permission denied to alter role` al ajustar RLS/Postgres.
- `44969866270`: fallo en el paso de Android (`./gradlew`, fixture de
  `google-services.json`).

Esa serie se cerró con `fix(ci): aislar clases de tests Android para evitar fuga de
corrutinas` (run `44971878676`, verde) y el merge de PR #1 (run `44972627864`,
verde). Por tanto no son regresiones de logo, RODADAS, geometría vial ni cabina.

## 2. Incidencia actual: flake, no regresión

Runs rojos recientes: `45006110201`, `45032309692`, `45033694832`.
El mismo SHA tiene runs verdes: `45006114358`, `45032293014`, `45033690096` y
`45034121123` (PR #3).

Los tres rojos fallan en el mismo sitio:

```
Phase1RulesTest > Hito 2 optimistic expulsion rolls back when backend rejects or is unreachable FAILED
java.lang.AssertionError at Phase1RulesTest.kt:484
491 tests completed, 1 failed
```

La línea 484 es la aserción «0 ms» de la expulsión optimista, no la del rollback.

### Causa

`Phase1RulesTest` usaba `UnconfinedTestDispatcher` como `Dispatchers.Main`. La
continuación del rollback volvía del hilo IO y se ejecutaba *inline* en ese mismo
hilo; con DNS inmediato (NXDOMAIN) el rollback ganaba la carrera y el miembro ya
estaba restaurado antes de leer el `StateFlow`.

Además, `Phase1ViewModel.init` mantiene un ciclo infinito de sincronización
(`while (isActive)` + `delay(1500L)`). Sin cancelar el scope en el teardown, una
continuación podía seguir usando `Dispatchers.Main` mientras `resetMain()`
cambiaba el delegado, produciendo `Dispatchers.Main is used concurrently with
setting it` y `UncaughtExceptionsBeforeTest` en la clase siguiente.

## 3. Cambio (sólo tests)

Archivo: `android/app/src/test/java/com/rutabikersync/app/feature/groups/Phase1RulesTest.kt`.

- `UnconfinedTestDispatcher` → `StandardTestDispatcher`.
- `awaitRollback(...)`: avanza el scheduler en cada sondeo para publicar la
  continuación del rollback en `Main` sin alterar la aserción de 0 ms.
- `@After`: cancela el `viewModelScope` de los VMs creados en el test, drena el
  scheduler y sólo entonces llama a `Dispatchers.resetMain()`.

**No se tocó código de producción**: ni liderazgo, ni expulsión real, ni GATT,
cifrado, PTT, malla, mapa ni backend.

## 4. Verificación

Tres corridas consecutivas con el mismo aislamiento que GitHub Actions
(`-I rbs-test-isolation.gradle`, `forkEvery = 1`), `--rerun-tasks`:

| Corrida | Resultado | Pruebas | Fallos | Errores | Duración |
|---|---|---:|---:|---:|---:|
| 1 | BUILD SUCCESSFUL | 498 | 0 | 0 | 2m36s |
| 2 | BUILD SUCCESSFUL | 498 | 0 | 0 | 2m45s |
| 3 | BUILD SUCCESSFUL | 498 | 0 | 0 | 2m50s |

87 archivos XML de resultados. `Phase1RulesTest` completa en ~0.2 s.

## 5. Pendiente

- Commit local atómico; **sin push** hasta instrucción explícita.
- Confirmar en el próximo run de PR #3 que el flake no reaparece.
