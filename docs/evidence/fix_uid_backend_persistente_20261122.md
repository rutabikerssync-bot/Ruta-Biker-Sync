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

## Instalación y verificación en hardware

APK instalado con `adb install -r` en ambos dispositivos (preferencias
conservadas):

| Dispositivo | UID antes | UID después de instalar y reiniciar |
|---|---|---|
| TECNO `098813734B105395` | `b5d0228d-…` | `b5d0228d-…` |
| Redmi `b2c398bb` | `e54bb490-…` | `e54bb490-…` |

Tras reiniciar, el TECNO muestra `RUTAS DEMO BIKERS`,
`Editar Identidad y Perfil del Club` y `PRESIDENTE`; el Redmi muestra
`RUTAS DEMO BIKERS` con `Miembros (2)`. Los logs del backend confirman
`GET /v1/groups` con ambos UUID reales. Capturas:
`cabina_paso4_tecno_20261122/club_tras_fix_uid.png` y
`cabina_paso4_redmi_20261122/club_tras_fix_uid.png`.

Estado final en BD: club `e3988eba-…`, líder `b5d0228d-…`, invitación
`57a33bfa-…` aceptada y membresía activa para `e54bb490-…`. Los usuarios/groupos
adicionales visibles en el conteo son E2E (`Centauros E2E …`) creados por
pruebas en paralelo; no se insertaron datos manualmente.
