# Fix crypto: IV generado por AndroidKeyStore en `persistKeyPair` (2026-11-23)

## 1. Reproducción

En TECNO `098813734B105395`, con la APK `41A859…` (idéntica a la compilada desde `HEAD`), al tocar **“Abrir mapa (Retorno)”** en el roster:

```text
java.security.InvalidAlgorithmParameterException: Caller-provided IV not permitted
  at android.security.keystore2.AndroidKeyStoreCipherSpiBase.engineInit(...)
  at com.rutabikersync.app.core.mesh.sos.Ed25519SosCrypto.persistKeyPair(Ed25519SosCrypto.kt:247)
  at com.rutabikersync.app.core.mesh.sos.Ed25519SosCrypto.getOrCreateLocalKeyPair(Ed25519SosCrypto.kt:150)
  at com.rutabikersync.app.core.mesh.sos.SosSigningHub.localPublicKeyRawBase64(SosSigningHub.kt:39)
  at com.rutabikersync.app.core.crypto.RideManifestKeyProvider.publishLocalSosKey(RideManifestKeyProvider.kt:91)
  at com.rutabikersync.app.ui.navigation.MainAppScaffoldKt$RidesReleaseHomeScreen$startRideServiceNow$1(...)
Process com.rutabikersync.app has died: fg TOP
```

Stack completo: `tecno_stack_antes.txt`.

**Verificación de procedencia:** `git status/diff` de `Ed25519SosCrypto.kt` estaban limpios y el hash de la APK instalada coincidía con el documento en `f2a7de0` (`41a859…`). El bug estaba en `main`, no en un archivo local modificado; revertir a `HEAD` no habría cambiado nada.

## 2. Causa y corrección

`getOrCreateKek()` crea la KEK AES-256-GCM en `AndroidKeyStore` sin desactivar `randomizedEncryptionRequired` (default `true`). `persistKeyPair()` pasaba un `GCMParameterSpec` con un IV propio a `cipher.init(ENCRYPT_MODE, ...)`, lo cual el Keystore rechaza.

Cambio aplicado en `Ed25519SosCrypto.persistKeyPair()`:

```kotlin
cipher.init(Cipher.ENCRYPT_MODE, kek)
val iv = cipher.iv
check(iv.size == GCM_IV_BYTES)
val wrappedPriv = cipher.doFinal(pair.private.encoded)
// se persiste el IV generado por Keystore
```

El descifrado (`unwrapPrivateKey`) mantiene `GCMParameterSpec` con el IV guardado, que sí es válido en `DECRYPT_MODE`. No se debilitó la política de cifrado aleatorio.

## 3. Tests

| Verificación | Resultado |
| --- | --- |
| `.\gradlew.bat :app:testStandardDebugUnitTest :app:assembleStandardDebug :app:assembleStandardDebugAndroidTest --no-daemon --console=plain` | **BUILD SUCCESSFUL** (584 tests JVM, 0 fallos) |
| Instrumentación en TECNO `098813734B105395`: `am instrument -w -e class ...Ed25519SosCryptoKeystoreInstrumentedTest ...` | **OK (1 test)** — genera IV en Keystore, persiste el par, lo recarga y vuelve a firmar/verificar |
| APK debug nueva | SHA-256 `46C5E85EE2025E2CA52B363C7B9D53D7C8EEBCAA89973C950005AF48143C25E7`, instalada en TECNO |

Se añadió `androidx.test:runner:1.6.2` a `androidTestImplementation` porque `testInstrumentationRunner` apuntaba a `AndroidJUnitRunner` y el APK de tests no lo contenía.

Redmi `b2c398bb` se desconectó durante la corrida instrumentada; queda pendiente repetir la instalación y el test al reconectarlo. No se hizo `push` ni `amend`. No se tocaron GATT, `HeartbeatFrame`, PTT ni liderazgo.
