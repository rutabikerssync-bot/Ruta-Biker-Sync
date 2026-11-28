# Ficha de tienda — Google Play

**Estado:** BORRADOR listo para copiar en Play Console. **NO publicada.**
**Fecha:** 2026-11-27 · **Package:** `com.rutabikersync.app` · **Idioma:** es-ES

## 1. Textos

| Campo | Texto propuesto | Límite |
|---|---|---|
| Título de la app | `Ruta Biker Sync` | ≤ 30 caracteres (17) |
| Descripción corta | `Malla BLE, mapa y SOS para motoclubes.` | ≤ 80 caracteres (41) |
| Categoría | `Deportes` (secundaria: `Mapas y navegación`, si Console la permite) | — |
| Etiquetas | `motoclub`, `ruta`, `seguridad`, `BLE`, ` mapa offline` | ≤ 5 × 30 caracteres |
| Contacto de soporte | `soporte@rutabikersync.com` | — |
| Sitio web | `https://ruta-biker-sync.onrender.com` (backend verificado; la web pública y `/privacy.html` siguen pendientes de despliegue) | — |
| Política de privacidad | URL pública **PENDIENTE de despliegue**: `npm run build` ya copia `backend/public/` a `dist/public/` para que Fastify sirva `/privacy.html`; falta que el propietario haga push y Render redepliegue. La app abre el mismo texto desde Perfil → Ajustes → «Privacidad y términos». | URL HTTPS requerida |

### Descripción completa

```text
Ruta Biker Sync mantiene a tu motoclube conectado incluso sin cobertura.

• Malla Bluetooth local: comparte posición, avisos y estado del convoy entre motos cercanas, con saltos y reenvío automático.
• Mapa de la rodada: visualiza al grupo, el trazado y tu rumbo con cartografía offline.
• SOS y ficha médica: envía una alerta con tu ubicación y datos de emergencia cifrados a los participantes autorizados.
• Bóveda digital: guarda documentación, póliza y contactos de emergencia cifrados en tu dispositivo.
• Clubes y permisos: organiza rodadas, invita pilotos y controla quién ve tu perfil.
• Avisos push: recibe invitaciones, cambios de ruta y alertas del club.
• Voz PTT: habla con el convoy mediante el botón de transmisión (requiere permiso de micrófono).

La app usa ubicación en primer plano solo durante la rodada, con notificación persistente. No declara acceso a ubicación en segundo plano. Los datos de emergencia se cifran en el dispositivo y se comparten solo en un SOS.

¿Necesitas ayuda? Escríbenos a soporte@rutabikersync.com.
```

## 2. Capturas

No se generaron capturas nuevas para esta entrega: se proponen archivos ya existentes en el repositorio, tomados en dispositivos reales. **Revisar que no contengan datos personales ni overlays de depuración antes de subirlos.**

| Orden | Archivo | Qué muestra |
|---:|---|---|
| 1 | `docs/evidence/redmi_cabina_active_final.png` | Cabina activa de la rodada y HUD del convoy. |
| 2 | `docs/evidence/ux_ruta_libre_20261123/redmi_con_club.png` | Rodada libre con club y controles de ruta. |
| 3 | `docs/evidence/fase5_tecno_livemap_sos_ready.png` | Mapa en vivo con SOS listo. |
| 4 | `docs/evidence/ux_ficha_medica_20261123/tecno_ficha_medica.png` | Ficha médica / bóveda. |
| 5 | `docs/evidence/tecno_bufalo_miclub_20261119.png` | Gestión del club y miembros. |

**Requisitos de imagen:** mínimo 320 px, máximo 3840 px, lado largo ≥ lado corto, ratio máximo 2:1, PNG o JPEG de 24 bits, ≤ 15 MB. Las capturas existentes son PNG de dispositivo; verificar dimensiones y recortar si Console las rechaza.

**PENDIENTE:** icono 512×512 (PNG, sin transparencia) y gráfico promocional 1024×500. No se encontraron en el repositorio durante esta revisión (`NO ENCONTRADO`).

## 3. App access / credenciales de revisor

Google Play puede requerir acceso a todas las funciones. **NO ENCONTRADO:** cuenta de revisor. Antes de enviar, crear un usuario de prueba con club y rodada de ejemplo y anotarlo aquí (no versionar la contraseña):

```text
Correo de revisor: PENDIENTE
Club de prueba: PENDIENTE
Rodada de prueba: PENDIENTE
Notas: PENDIENTE
```

## 4. Pasos en Console

1. Store presence → Store listing → pegar título, descripción corta y completa.
2. Store presence → Store listing → subir las 5 capturas, icono y gráfico promocional.
3. App content → App access → marcar que la app requiere acceso y guardar las credenciales de prueba.
4. Guardar y adjuntar captura de la ficha en `docs/evidence/google_play_publicacion_20261127/`.
