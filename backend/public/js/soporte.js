/* Centro de Soporte — JS vanilla, sin dependencias. */
(function () {
  'use strict';

  // --- FAQ acordeón (solo esta página) -------------------------------------
  document.querySelectorAll('#faqList .faq-question').forEach(function (question) {
    question.addEventListener('click', function () {
      var item = question.parentElement;
      var wasActive = item.classList.contains('active');
      document.querySelectorAll('#faqList .faq-item').forEach(function (other) {
        other.classList.remove('active');
        var icon = other.querySelector('.faq-toggle-icon');
        if (icon) icon.textContent = '+';
      });
      if (!wasActive) {
        item.classList.add('active');
        var icon = question.querySelector('.faq-toggle-icon');
        if (icon) icon.textContent = '−';
      }
    });
  });

  // --- Formulario de reporte ------------------------------------------------
  var form = document.getElementById('supportForm');
  var status = document.getElementById('formStatus');
  var submitButton = document.getElementById('submitReport');
  var screenshotInput = document.getElementById('screenshot');
  var CATEGORY_LABELS = {
    mesh_sync: 'MESH',
    routes: 'RUTAS',
    account: 'CUENTA',
    safety_sos: 'SOS',
    billing: 'LICENCIAS',
    other: 'OTRO'
  };

  function setStatus(message, kind) {
    status.textContent = message;
    status.className = kind || '';
  }

  function compressImage(file) {
    return new Promise(function (resolve, reject) {
      if (!file) return resolve(null);
      if (!/^image\/(png|jpeg|webp)$/i.test(file.type)) {
        return reject(new Error('Formato no admitido. Usa PNG, JPEG o WebP.'));
      }
      var reader = new FileReader();
      reader.onerror = function () { reject(new Error('No se pudo leer la imagen.')); };
      reader.onload = function () {
        var image = new Image();
        image.onerror = function () { reject(new Error('No se pudo procesar la imagen.')); };
        image.onload = function () {
          var attempt = function (maxSide, quality) {
            var scale = Math.min(1, maxSide / Math.max(image.width, image.height));
            var canvas = document.createElement('canvas');
            canvas.width = Math.max(1, Math.round(image.width * scale));
            canvas.height = Math.max(1, Math.round(image.height * scale));
            var ctx = canvas.getContext('2d');
            ctx.drawImage(image, 0, 0, canvas.width, canvas.height);
            var mime = file.type === 'image/png' ? 'image/jpeg' : file.type;
            var dataUrl = canvas.toDataURL(mime, quality);
            if (dataUrl.length <= 2_600_000) return dataUrl;
            return null;
          };
          var dataUrl = attempt(1280, 0.78) || attempt(960, 0.55);
          if (!dataUrl) return reject(new Error('La captura sigue superando 2 MB. Recorta la imagen.'));
          resolve(dataUrl);
        };
        image.src = reader.result;
      };
      reader.readAsDataURL(file);
    });
  }

  form.addEventListener('submit', async function (event) {
    event.preventDefault();
    var subjectField = document.getElementById('subject');
    var messageField = document.getElementById('message');
    var categoryField = document.getElementById('category');
    subjectField.classList.remove('field-error');
    messageField.classList.remove('field-error');

    var subject = subjectField.value.trim();
    var message = messageField.value.trim();
    if (subject.length < 3) {
      subjectField.classList.add('field-error');
      return setStatus('Escribe un asunto de al menos 3 caracteres.', 'error');
    }
    if (message.length < 5) {
      messageField.classList.add('field-error');
      return setStatus('Escribe un mensaje de al menos 5 caracteres.', 'error');
    }

    var tag = CATEGORY_LABELS[categoryField.value] || 'OTRO';
    var payload = {
      subject: '[' + tag + '] ' + subject.slice(0, 190),
      message: message.slice(0, 5000)
    };

    submitButton.disabled = true;
    setStatus('Enviando…');
    try {
      payload.screenshotBase64 = await compressImage(screenshotInput.files && screenshotInput.files[0]);
      var response = await fetch('/v1/support/report', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(payload)
      });
      var body = await response.json().catch(function () { return {}; });
      if (!response.ok) {
        throw new Error(body.message || body.error || 'No se pudo enviar el reporte');
      }
      form.reset();
      setStatus('Reporte ' + body.reportId + ' recibido. Gracias.', 'ok');
    } catch (error) {
      setStatus(error.message || 'No se pudo enviar el reporte.', 'error');
    } finally {
      submitButton.disabled = false;
    }
  });

  screenshotInput.addEventListener('change', function () {
    var file = screenshotInput.files && screenshotInput.files[0];
    document.getElementById('screenshotInfo').textContent = file
      ? 'Captura seleccionada: ' + file.name
      : 'PNG, JPEG o WebP. Se comprime automáticamente en tu navegador.';
  });

  // ---Estado de dispositivos del piloto (vista ligera) ----------------------
  var keyInput = document.getElementById('pilotKey');
  var loadButton = document.getElementById('loadDevices');
  var deviceStatus = document.getElementById('deviceStatus');
  var table = document.getElementById('deviceTable');
  var tbody = document.getElementById('deviceTableBody');
  var refreshTimer = null;

  function cell(row, value) {
    var td = document.createElement('td');
    td.textContent = value == null || value === '' ? '—' : String(value);
    row.appendChild(td);
  }

  function formatDate(value) {
    if (!value) return '—';
    var date = new Date(value);
    return isNaN(date.getTime()) ? String(value) : date.toLocaleString();
  }

  async function loadDevices() {
    var key = keyInput.value.trim();
    if (key.length < 16) {
      deviceStatus.textContent = 'Introduce una clave de piloto válida.';
      deviceStatus.className = 'error';
      return;
    }
    sessionStorage.setItem('rbs_pilot_key', key);
    loadButton.disabled = true;
    deviceStatus.textContent = 'Consultando…';
    deviceStatus.className = '';
    try {
      var response = await fetch('/v1/support/devices/light', {
        headers: { 'x-rbs-pilot-key': key }
      });
      if (response.status === 401 || response.status === 403) {
        throw new Error('Clave no autorizada o revocada');
      }
      var body = await response.json();
      if (!response.ok) throw new Error(body.message || body.error || 'Error al consultar');

      tbody.textContent = '';
      body.devices.forEach(function (device) {
        var row = document.createElement('tr');
        cell(row, device.pilot_build);
        cell(row, [device.device_brand, device.device_model].filter(Boolean).join(' '));
        cell(row, device.battery_pct == null ? '—' : device.battery_pct + '%');
        cell(row, device.peer_count);
        cell(row, formatDate(device.last_seen_at));
        cell(row, device.last_error_short);
        tbody.appendChild(row);
      });
      table.hidden = body.devices.length === 0;
      deviceStatus.textContent = body.devices.length
        ? body.devices.length + ' dispositivo(s) · actualizado ' + formatDate(body.generatedAt)
        : 'Todavía no hay dispositivos reportados.';
      deviceStatus.className = 'ok';
      if (refreshTimer) clearInterval(refreshTimer);
      refreshTimer = setInterval(loadDevices, 30_000);
    } catch (error) {
      deviceStatus.textContent = error.message || 'No se pudo consultar el estado.';
      deviceStatus.className = 'error';
      if (refreshTimer) { clearInterval(refreshTimer); refreshTimer = null; }
    } finally {
      loadButton.disabled = false;
    }
  }

  keyInput.value = sessionStorage.getItem('rbs_pilot_key') || '';
  loadButton.addEventListener('click', loadDevices);
})();
