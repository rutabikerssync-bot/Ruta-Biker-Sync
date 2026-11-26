# Plan de Implementación — Banners / Modales Promocionales (Carrusel)

> Módulo de anuncios y promociones en formato modal con carrusel deslizable (inspirado en el formato de modal emergente con indicador `••`, botón `X` y degradado *Dark Elite*), administrado en vivo desde el Backoffice sin requerir nueva APK.

---

## Estado General

| Fase | Nombre | Estado | Alcance |
| :--- | :--- | :--- | :--- |
| **Fase 1** | Backend PostgreSQL + Endpoints de Anuncios y Admin | ✅ **Completada** | `0026_promo_banners.sql`, `promoBanners.ts`, `app.ts`, `promo_banners.test.ts` (`6/6` verdes) |
| **Fase 2** | Página de Soporte / Backoffice (`admin-dashboard` + `backend/public`) | ⏳ **Pendiente** | Agregar, editar/cambiar, activar/pausar y eliminar banners desde la página web de Soporte |
| **Fase 3** | Android Compose (`PromoBannerModal` + frecuencia local) | ⏳ **Pendiente** | `HorizontalPager`, degradado *Dark Elite*, filtro `once_per_campaign` / `once_per_day`, bloqueo en rodada activa y tests JVM |

---

## Reglas de Diseño y Seguridad

1. **Nunca interrumpir la conducción:** el modal **solo** puede abrirse en pestañas de reposo (`Rodadas` / `Mi Club` / `Perfil`); queda bloqueado cuando existe una rodada activa (`RideService` / `LiveMapScreen`).
2. **Control anti-spam (`frequency`):**
   - `once_per_campaign`: se muestra 1 sola vez por `id` de campaña (al cerrarlo con `X` o tocar el CTA, se guarda en `SharedPreferences`).
   - `once_per_day`: máximo 1 aparición cada 24 h mientras esté vigente.
3. **Estética *Dark Elite*:**
   - Tarjeta redondeada sobre fondo atenuado, botón `X` arriba a la derecha, imagen/arte superior con degradado hacia el color de acento (`#E5B869` oro o `#34D399` esmeralda), texto de vigencia, CTA (`→`) y puntos de paginación (`••`) en `HorizontalPager`.

---

## Desglose por Fases

### Fase 1 — Backend (`0026_promo_banners.sql` + API + Vitest)
- **Tabla `promo_banners`:** `id`, `title`, `subtitle`, `badge_text`, `image_url`, `accent_color_hex`, `cta_label`, `cta_target`, `frequency` (`once_per_campaign` | `once_per_day`), `priority`, `active`, `starts_at`, `ends_at`, `created_at`.
- **Rutas:**
  - `GET /v1/announcements/active` (público autenticado: retorna banners con `active = true` y `now() BETWEEN starts_at AND ends_at`).
  - `GET/POST/PATCH/DELETE /v1/admin/promo-banners` (protegidas con `requireAdminAuth`).

### Fase 2 — Backoffice Admin
- Pestaña/tarjeta **Anuncios y Banners** en `admin-dashboard/` y `backend/public/` para crear y activar campañas en caliente.

### Fase 3 — Android Compose + Tests
- `PromoBannerRepository.kt`: fetch + filtrado por frecuencia local (`SharedPreferences`).
- `PromoBannerModal.kt`: carrusel `HorizontalPager` con botón `X`, CTA y soporte de enlace/navegación.
- Tests unitarios JVM para filtrado de vigencia y frecuencia (`once_per_campaign` / `once_per_day`).
