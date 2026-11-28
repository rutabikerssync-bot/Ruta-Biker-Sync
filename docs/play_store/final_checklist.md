# Checklist final — acciones externas de Google Play

**Estado:** PREPARADO para ejecución por el propietario. Ninguna casilla se marca desde el agente: requieren Play Console / Render.
**Fecha:** 2026-11-27 · **Commit local:** `6650eb6` · **URL real:** `https://ruta-biker-sync.onrender.com`

- [ ] Push a Render y verificar `/privacy.html` — ver `render_deploy.md`; esperar ~1–2 min y confirmar `HTTP=200`.
- [ ] Data Safety completado — ver `data_safety_form.md`; marcar cifrado en tránsito y borrado a solicitud.
- [ ] Cuenta de revisor creada — ver `reviewer_account.md`; rellenar `<REVIEWER_EMAIL>` / `<REVIEWER_PASSWORD>` solo en Console.
- [ ] IARC completado — ver `iarc_answers.md`; no marcar violencia, sexo, drogas ni apuestas; sí interacción social, ubicación en primer plano y compras.
- [ ] Icono 512×512 subido — `assets/icon_512.png`.
- [ ] Gráfico 1024×500 subido — `assets/feature_graphic_1024x500.png`.
- [ ] Capturas subidas — 4 archivos en `assets/screenshots/` (mínimo 2, máximo 8).
- [ ] Prueba cerrada iniciada — ver `closed_test.md`; 12 testers inscritos y 14 días continuos antes de *Apply for production*.

## Notas de ejecución

1. El push exacto es `git push origin main`; no se hizo desde este agente.
2. La política pública sigue en `404` hasta que Render despliegue el commit; no pegar la URL en Console hasta ver `HTTP=200`.
3. Las credenciales de revisor y los correos de testers no se versionan.
4. La clasificación IARC definitiva, el código asignado y la fecha de aprobación los emite Play Console; este checklist no los inventa.
