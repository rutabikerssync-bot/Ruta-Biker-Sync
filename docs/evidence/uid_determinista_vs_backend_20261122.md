# Por qué el móvil vuelve siempre al UUID `1ec5e592…` (2026-11-22)

## Respuesta corta

Ese UUID **no viene de PostgreSQL ni de una instalación anterior**: se calcula
en el propio móvil a partir del email de Google. Limpiar la BD o reinstalar no
lo cambia, porque mientras siga existiendo la cuenta
`omartinezpaz@gmail.com`, el algoritmo devuelve siempre el mismo valor.

## Código que lo genera

`ProfileRepository.kt:62-65`:

```kotlin
fun generateDeterministicUuid(email: String): String {
    val cleanEmail = email.trim().lowercase()
    return UUID.nameUUIDFromBytes("rbs:google:$cleanEmail".toByteArray(Charsets.UTF_8)).toString()
}
```

Para `omartinezpaz@gmail.com` eso es exactamente
`1ec5e592-5b2d-3737-9820-1107fa43bcf8`.

`MainActivity.kt:102-108` entrega al login de Google un uid que **no es un
UUID**:

```kotlin
onGoogleAuthSuccess = { email, displayName ->
    authViewModel.onGoogleLoginSuccess(
        uid = "google-${email.lowercase()}",
        ...
    )
}
```

`ProfileRepository.kt:76-95` intenta parsearlo; como `google-…` no es UUID,
cae en `generateDeterministicUuid(email)` y **sobrescribe** `user_id` en
`rbs_auth_prefs.xml`:

```kotlin
private fun ensureValidUuid(uid: String, email: String): String = try {
    UUID.fromString(uid); uid
} catch (_: Exception) {
    generateDeterministicUuid(email)
}
...
.putString(KEY_USER_ID, validUid)
```

El disparador es el selector nativo de cuentas de Google
(`WelcomeAuthScreen.kt:101-110`) y su fallback (`:114-121`). Cada vez que el
tester inicia sesión con Google, se recalcula y se escribe el UUID
determinista.

## Por qué rompe el piloto

`POST /v1/auth/login` del backend **no usa** ese UUID determinista: genera un
UUID aleatorio propio (`b5d0228d…` para el líder, `e54bb490…` para el
miembro). Ese es el que queda en `users` y en `group_leaders`.

| Origen | Líder | Miembro |
|---|---|---|
| UUID determinista local (Google) | `1ec5e592-…` | `f2850920-…` |
| UUID real registrado en Fastify | `b5d0228d-…` | `e54bb490-…` |

Cuando el móvil vuelve al UUID determinista, el backend no encuentra fila en
`users` y el móvil deja de ser reconocido como líder / miembro. Eso explica,
con la misma causa, el 403 inicial, el 23503 al aceptar y la desaparición del
club en la UI del TECNO.

**No hay datos demo ni residuos de BD implicados.** Es un desajuste de diseño
entre identidad local determinista e identidad backend aleatoria.

## Corrección duradera (pendiente de autorización)

Hacer que `setAuthenticated()` conserve el `user_id` ya registrado en backend
para el mismo email, en lugar de sustituirlo por el determinista. Es un cambio
en código de autenticación/identidad, fuera del alcance original autorizado;
por eso no se ha aplicado.
