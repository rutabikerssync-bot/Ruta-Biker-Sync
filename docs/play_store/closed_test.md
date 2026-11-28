# Prueba cerrada — 12 testers / 14 días

**Estado:** PROCESO DOCUMENTADO; **NO iniciado** (requiere Play Console y 12 personas).
**Fecha:** 2026-11-27 · **Package:** `com.rutabikersync.app`
**Fuente oficial consultada:** https://support.google.com/googleplay/android-developer/answer/14151465?hl=en

## 1. Regla aplicable

Las cuentas personales de desarrollador creadas después del 13 de noviembre de 2023 deben ejecutar una **prueba cerrada** antes de solicitar acceso a producción:

- Mínimo **12 testers inscritos** en el momento de solicitar acceso.
- Cada tester debe permanecer inscrito **continuamente durante al menos 14 días**.
- Si un tester sale y vuelve a entrar, el conteo de 14 días se reinicia.
- Después de cumplirlo, se solicita acceso a producción desde el Dashboard; la revisión suele tardar ≤ 7 días, pero puede prolongarse.
- En la solicitud se confirma cómo se reclutó a los testers, qué funciones usaron, qué feedback se recibió y qué cambios se aplicaron.

> Esta regla depende de la fecha de creación de la cuenta. Si la cuenta del propietario es anterior o es de organización, verificar en Console antes de aplicar el procedimiento.

## 2. Secuencia en Play Console

| Paso | Acción en Console | Responsable | Estado |
|---:|---|---|---|
| 1 | Completar *App setup* (Data Safety, content rating, declaración de anuncios, audiencia, permisos y política de privacidad) | Propietario | `PENDIENTE` |
| 2 | Crear grupo de Google (opcional, recomendado para invitaciones y comentarios) | Propietario | `PENDIENTE` |
| 3 | Testing → Closed testing → crear la prueba | Propietario | `PENDIENTE` |
| 4 | Añadir al menos 12 correos de testers al grupo y compartir el enlace de opt-in | Propietario | `PENDIENTE` |
| 5 | Subir el AAB firmado `standardRelease` a la pista cerrada | Propietario | `PENDIENTE` |
| 6 | Publicar la release de prueba y verificar que los 12 testers pueden instalar desde Play Store | Propietario | `PENDIENTE` |
| 7 | Mantener la inscripción ininterrumpida durante 14 días; recoger feedback en Console y por correo | Propietario | `PENDIENTE` |
| 8 | Corregir incidencias y, si aplica, subir una nueva versión a la pista cerrada | Propietario + Agente | `PENDIENTE` |
| 9 | Dashboard → *Apply for production*; responder las tres secciones | Propietario | `PENDIENTE` |
| 10 | Tras aprobación, subir y publicar en Production | Propietario | `PENDIENTE` |

## 3. Checklist de readiness de la app

| Requisito | Evidencia en este repositorio | Estado |
|---|---|---|
| `compileSdk`/`targetSdk` 36 | `android/app/build.gradle.kts` | `CUMPLE`; build verde y validado en dispositivo API 36 |
| AAB `standardRelease` firmado con keystore de producción | `android/app/build/outputs/bundle/standardRelease/` | `CUMPLE`; certificado B5:19…4F:36 |
| Gate sin `FAIL_PLACEHOLDER` | `scripts/verify_release_security.ps1` con `RBS_PROD_URL=https://ruta-biker-sync.onrender.com` | `CUMPLE`: `verify_release_security: PASS` |
| URL HTTPS de política de privacidad | `backend/public/privacy.html` existe; Render devuelve `404` para `/privacy.html` | `BLOQUEANTE`: falta despliegue (push/redeploy del propietario) |
| Política accesible desde la app | `ProfileScreen.kt` → Ajustes → «Privacidad y términos» → `LegalTermsDialog` pestaña Privacidad | `CUMPLE`: validado en dispositivo API 36 |
| Data Safety completada | `docs/play_store/data_safety.md` | `BORRADOR`; falta envío en Console |
| Clasificación IARC | `docs/play_store/iarc.md` | `BORRADOR`; Console emite la clasificación |
| Ficha y capturas | `docs/play_store/listing.md` | `BORRADOR`; faltan icono y gráfico promocional |
| Cuenta de acceso para revisores | `docs/play_store/listing.md` §3 | `NO ENCONTRADO` |
| Build estable | 131 suites / 714 tests Android en verde; 5 tests Node legales en verde; suite backend completa bloqueada por PostgreSQL `:5433` | `PARCIAL` |
| Instrucciones para testers | Texto inferior | Listo |
| Canal de comentarios | `soporte@rutabikersync.com` + testing feedback de Console | Listo |

## 4. Instrucciones para los testers (copiar/pegar)

```text
Gracias por probar Ruta Biker Sync.

1. Únete al enlace de prueba cerrada con tu cuenta de Google y mantente inscrito al menos 14 días seguidos.
2. Instala la app desde Play Store > Mi biblioteca > Pruebas.
3. Inicia sesión con la cuenta de prueba que te enviemos.
4. Prueba: perfil y bóveda, creación/unión a club, rodada con mapa, SOS (sin llamar a emergencias reales) y PTT con otro tester.
5. Reporta fallos a soporte@rutabikersync.com indicando móvil, Android, paso exacto y captura. También puedes dejar comentarios privados desde la ficha de prueba en Play Store.

No cierres sesión ni salgas del programa de prueba durante los 14 días: si sales y vuelves, el conteo vuelve a empezar.
```

## 5. Registro de prueba (completar el propietario)

```text
Fecha de inicio: PENDIENTE
Fecha de fin (≥14 días): PENDIENTE
Testers inscritos: PENDIENTE (mínimo 12)
Versiones probadas: PENDIENTE
Incidencias recibidas: PENDIENTE
Cambios aplicados: PENDIENTE
Fecha de solicitud de acceso a producción: PENDIENTE
Resultado de la revisión: PENDIENTE
```
