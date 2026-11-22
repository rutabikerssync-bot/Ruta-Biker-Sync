# Fix: conservar el UUID registrado en backend tras login de Google (2026-11-22)

## Cambio

`ProfileRepository.setAuthenticated()` ya no sobrescribe `user_id` con el UUID
determinista cuando antes había un UUID válido guardado **para el mismo
email**. Se extrajo la decisión a una función pura y probetada:

```kotlin
fun resolveAuthenticatedUid(uid, email, existingUid, existingEmail): String
```

Reglas:

| Situación | Resultado |
|---|---|
| UUID de backend previo + mismo email | se conserva |
| Instalación nueva (sin `user_id`) | UUID determinista (comportamiento original) |
| UUID previo de otro email | UUID determinista del email nuevo |
| UID sintético (`u-…`) | UUID determinista |
| Email con mayúsculas/espacios | se normaliza antes de comparar |

No se tocó GATT, cifrado, `HeartbeatFrame`, PTT ni liderazgo. No se modificó el
backend.

## Verificación

- `:app:testStandardDebugUnitTest --tests '*ProfileRepositoryUidTest' --rerun-tasks` → BUILD SUCCESSFUL (5 pruebas nuevas).
- Suite completa `:app:testStandardDebugUnitTest --rerun-tasks` → **88 suites / 503 pruebas / 0 fallos / 0 errores**.
  - Una corrida intermedia falló en `VaultCryptoTest.6_2 deteccion de checksum BIP-0039 corrupto o palabra alterada`: es un test aleatorio preexistente (1/16 × 1/16). Aislado pasó y la repetición completa quedó verde. No se modificó código cripto.
- `assembleStandardDebug` → BUILD SUCCESSFUL.

## Estado

APK compilado en
`android/app/build/outputs/apk/standard/debug/app-standard-debug.apk`.
**Todavía no instalado en los móviles**; instalar con `adb install -r` conserva
las preferencias con los UUID reales ya apuntados (`b5d0228d…` y `e54bb490…`).
