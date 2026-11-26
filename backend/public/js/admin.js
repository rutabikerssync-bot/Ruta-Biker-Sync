(() => {
  'use strict';
  /**
   * Ruta Biker Sync — Controlador Principal de Interfaz del Backoffice
   */

  const api = new (window.AdminApi || class {})();
  let currentOverviewData = null;

function startApp() {
  initNavigation();
  initSettings();
  initPromoBannersForm();
  loadOverviewAnalytics();

  let resizeTimeout;
  window.addEventListener('resize', () => {
    clearTimeout(resizeTimeout);
    resizeTimeout = setTimeout(() => {
      renderCharts();
    }, 200);
  });
}

if (document.readyState === 'loading') {
  document.addEventListener('DOMContentLoaded', startApp);
} else {
  startApp();
}

  window.loadTabContent = function(targetTab) {
    if (targetTab === 'analytics') loadOverviewAnalytics();
    if (targetTab === 'groups') loadGroupsTable();
    if (targetTab === 'users') loadUsersTable();
    if (targetTab === 'support') loadSupportSection();
    if (targetTab === 'audit') loadSosAuditTable();
  };

  /**
   * Gestión de Pestañas de Navegación
   */
  function initNavigation() {
    const navItems = document.querySelectorAll('.nav-item');
    navItems.forEach((item) => {
      item.addEventListener('click', () => {
        const targetTab = item.dataset.tab;
        if (targetTab && window.switchTab) {
          window.switchTab(targetTab);
        }
      });
    });
  }

/**
 * Inicialización de ajustes y autenticación de Admin
 */
function initSettings() {
  const keyInput = document.getElementById('input-admin-key');
  const saveKeyBtn = document.getElementById('btn-save-key');
  const testConnBtn = document.getElementById('btn-test-connection');
  const connStatus = document.getElementById('connection-status-text');

  if (keyInput && api.adminKey) keyInput.value = api.adminKey;

  if (saveKeyBtn) {
    saveKeyBtn.addEventListener('click', () => {
      api.setAdminKey(keyInput.value.trim());
      alert('Clave de administrador guardada con éxito.');
      loadOverviewAnalytics();
    });
  }

  if (testConnBtn) {
    testConnBtn.addEventListener('click', async () => {
      if (connStatus) connStatus.textContent = 'Verificando enlace...';
      const res = await api.getAnalyticsOverview();
      if (api.isLiveApi && res && res.users) {
        if (connStatus) {
          connStatus.innerHTML = '🟢 Conectado con éxito a la API del servidor.';
          connStatus.style.color = 'var(--emerald-success)';
        }
      } else {
        if (connStatus) {
          connStatus.innerHTML = '🔴 Sin conexión con la BD / datos no disponibles.';
          connStatus.style.color = '#ef4444';
        }
      }
    });
  }
}

/**
 * 1. Métricas y KPIs de Analítica
 */
async function loadOverviewAnalytics() {
  try {
    const data = await api.getAnalyticsOverview();
    currentOverviewData = data;

    // Actualizar KPIs
    const elTotalUsers = document.getElementById('kpi-total-users');
    const elActiveRiders = document.getElementById('kpi-active-riders');
    const elTotalGroups = document.getElementById('kpi-total-groups');
    const elTotalKm = document.getElementById('kpi-total-km');
    const elMonthlyRev = document.getElementById('kpi-monthly-revenue');
    const elSosAccuracy = document.getElementById('kpi-sos-accuracy');
    const elMeshRatio = document.getElementById('kpi-mesh-ratio');

    if (data?.users) {
      if (elTotalUsers) elTotalUsers.textContent = (data.users.total || 0).toLocaleString();
      if (elActiveRiders) elActiveRiders.textContent = `${(data.users.active || 0).toLocaleString()} en ruta`;
    }
    if (data?.groups) {
      if (elTotalGroups) elTotalGroups.textContent = (data.groups.total || 0).toLocaleString();
    }
    if (data?.rides) {
      if (elTotalKm) elTotalKm.textContent = `${Math.round(data.rides.totalKm || 0).toLocaleString()} km`;
    }
    if (data?.subscriptions) {
      if (elMonthlyRev) elMonthlyRev.textContent = `$${(data.subscriptions.monthlyEstimatedRevenueUsd || 0).toLocaleString()} USD`;
    }
    if (data?.safetySos) {
      if (elSosAccuracy) elSosAccuracy.textContent = `${data.safetySos.falseAlarmRatePct || 0}%`;
    }
    if (data?.meshOffline) {
      if (elMeshRatio) elMeshRatio.textContent = `${data.meshOffline.offlineRatioPct || 0}%`;
    }

    renderCharts();
  } catch (err) {
    console.warn('[Admin] Error cargando analytics overview:', err);
  }
}

function renderCharts() {
  if (!currentOverviewData || !window.BikerCharts) return;

  // Gráfica 1: Actividad de Rodadas
  window.BikerCharts.renderRidesActivityChart('chart-rides-activity', [14, 28, 22, 45, 68, 112, 134]);

  // Gráfica 2: Embudo de Rangos Comunitarios
  if (currentOverviewData?.users?.ranks) {
    window.BikerCharts.renderRanksDonutChart('chart-ranks-donut', currentOverviewData.users.ranks);
  }

  // Gráfica 3: Efectividad SOS
  if (currentOverviewData?.safetySos) {
    window.BikerCharts.renderSosMetricsChart('chart-sos-metrics', {
      confirmed: currentOverviewData.safetySos.confirmedEmergencies || 4,
      cancelled: currentOverviewData.safetySos.cancelledIn20s || 11
    });
  }
}

/**
 * 2. Gestión de Clubes / Grupos
 */
async function loadGroupsTable() {
  const searchInput = document.getElementById('search-groups');
  const tierFilter = document.getElementById('filter-group-tier');
  const tableBody = document.getElementById('groups-table-body');
  if (!tableBody) return;

  tableBody.innerHTML = `<tr><td colspan="6" style="text-align:center; padding: 30px; color: var(--text-subtle);">Cargando registro de clubes...</td></tr>`;

  const query = {};
  if (searchInput && searchInput.value.trim()) query.search = searchInput.value.trim();
  if (tierFilter && tierFilter.value) query.tier = tierFilter.value;

  const data = await api.getGroups(query);
  tableBody.innerHTML = '';

  if (!data?.groups || data.groups.length === 0) {
    tableBody.innerHTML = `<tr><td colspan="6" style="text-align:center; padding: 30px; color: var(--text-subtle);">No se encontraron clubes registrados.</td></tr>`;
    return;
  }

  data.groups.forEach((g) => {
    const tr = document.createElement('tr');

    let tierBadgeClass = 'pill-rc';
    if (g.tier === 'MG') tierBadgeClass = 'pill-mg';
    else if (g.tier === 'GANG') tierBadgeClass = 'pill-gang';
    else if (g.tier === 'MC') tierBadgeClass = 'pill-mc';

    let statusPillClass = 'pill-active';
    let statusLabel = 'AL DÍA';
    if (g.status === 'suspended') {
      statusPillClass = 'pill-suspended';
      statusLabel = 'SUSPENDIDO';
    } else if (g.subscriptionState === 'grace') {
      statusPillClass = 'pill-grace';
      statusLabel = 'EN GRACIA';
    }

    tr.innerHTML = `
      <td>
        <div style="display:flex; align-items:center; gap:10px;">
          <div style="width:34px; height:34px; border-radius:50%; background: #1F283E; border:1px solid var(--gold-primary); display:flex; align-items:center; justify-content:center; font-size:14px;">🏍️</div>
          <div>
            <div style="font-weight:700; color:var(--text-main); font-size:0.9rem;">${escapeHtml(g.name)}</div>
            <div style="font-size:0.75rem; color:var(--text-subtle);">${g.countryCode} · ID: ${g.id}</div>
          </div>
        </div>
      </td>
      <td><span class="pill ${tierBadgeClass}">${g.tier}</span></td>
      <td>
        <div style="font-weight:600;">${escapeHtml(g.principalLeaderName || 'Sin asignar')}</div>
        <div style="font-size:0.75rem; color:var(--text-subtle);">${escapeHtml(g.principalLeaderEmail)}</div>
      </td>
      <td><strong>${g.memberCount}</strong> pilotos</td>
      <td><span class="pill ${statusPillClass}">${statusLabel}</span></td>
      <td>
        <div style="display:flex; gap:6px;">
          <button class="btn-secondary btn-grace" data-id="${g.id}" title="Otorgar prórroga">+7d Gracia</button>
          <button class="btn-secondary btn-toggle-group" data-id="${g.id}" data-status="${g.status}">
            ${g.status === 'active' ? '⏸️ Suspender' : '▶️ Reactivar'}
          </button>
        </div>
      </td>
    `;
    tableBody.appendChild(tr);
  });

  // Event Listeners en botones de acción
  document.querySelectorAll('.btn-grace').forEach((b) => {
    b.addEventListener('click', async (e) => {
      const gid = e.target.dataset.id;
      const days = prompt('¿Cuántos días de prórroga deseas otorgar a este club?', '7');
      if (days) {
        await api.extendGroupGrace(gid, parseInt(days, 10), 'Prórroga administrativa');
        alert(`Prórroga de ${days} días otorgada exitosamente.`);
        loadGroupsTable();
      }
    });
  });

  document.querySelectorAll('.btn-toggle-group').forEach((b) => {
    b.addEventListener('click', async (e) => {
      const gid = e.target.dataset.id;
      const current = e.target.dataset.status;
      const next = current === 'active' ? 'suspended' : 'active';
      const confirmAction = confirm(`¿Estás seguro de cambiar el estado de este club a "${next.toUpperCase()}"?`);
      if (confirmAction) {
        await api.updateGroupStatus(gid, next, 'Acción de moderador');
        loadGroupsTable();
      }
    });
  });

  // Filtros interactivos
  if (searchInput) {
    searchInput.onkeyup = debounce(() => loadGroupsTable(), 300);
  }
  if (tierFilter) {
    tierFilter.onchange = () => loadGroupsTable();
  }
}

/**
 * 3. Gestión de Usuarios
 */
async function loadUsersTable() {
  const searchInput = document.getElementById('search-users');
  const tableBody = document.getElementById('users-table-body');
  if (!tableBody) return;

  tableBody.innerHTML = `<tr><td colspan="6" style="text-align:center; padding: 30px; color: var(--text-subtle);">Cargando directorio de pilotos...</td></tr>`;

  const query = {};
  if (searchInput && searchInput.value.trim()) query.search = searchInput.value.trim();

  const data = await api.getUsers(query);
  tableBody.innerHTML = '';

  if (!data?.users || data.users.length === 0) {
    tableBody.innerHTML = `<tr><td colspan="6" style="text-align:center; padding: 30px; color: var(--text-subtle);">No se encontraron pilotos registrados.</td></tr>`;
    return;
  }

  data.users.forEach((u) => {
    const tr = document.createElement('tr');
    tr.innerHTML = `
      <td>
        <div style="font-weight:700;">${escapeHtml(u.displayName || 'Piloto Anónimo')}</div>
        <div style="font-size:0.75rem; color:var(--text-subtle);">${escapeHtml(u.email)}</div>
      </td>
      <td>${u.countryCode}</td>
      <td>
        <span style="font-weight:700; color:var(--gold-primary); text-transform:uppercase; font-size:0.8rem;">
          ${u.rankCode} (${u.totalXp} XP)
        </span>
      </td>
      <td>
        <div>${escapeHtml(u.currentClubName || 'Sin Club')}</div>
        <div style="font-size:0.75rem; color:var(--text-subtle);">${u.currentClubRole || 'Miembro'}</div>
      </td>
      <td>${Math.round(u.totalKm || 0).toLocaleString()} km</td>
      <td>
        <button class="btn-secondary btn-toggle-user" data-id="${u.id}" data-status="${u.status}">
          ${u.status === 'active' ? '🚫 Suspender' : '✅ Activar'}
        </button>
      </td>
    `;
    tableBody.appendChild(tr);
  });

  document.querySelectorAll('.btn-toggle-user').forEach((b) => {
    b.addEventListener('click', async (e) => {
      const uid = e.target.dataset.id;
      const current = e.target.dataset.status;
      const next = current === 'active' ? 'suspended' : 'active';
      if (confirm(`¿Cambiar estado del usuario a ${next}?`)) {
        await api.updateUserStatus(uid, next, 'Acción administrativa');
        loadUsersTable();
      }
    });
  });

  if (searchInput) {
    searchInput.onkeyup = debounce(() => loadUsersTable(), 300);
  }
}

/**
 * 4. Soporte y Moderación Escalada (R13.5)
 */
async function loadSupportSection() {
  loadEscalatedReports();
  loadTicketsList();
  loadPromoBannersAdmin();
}

async function loadEscalatedReports() {
  const container = document.getElementById('escalated-reports-list');
  if (!container) return;

  const data = await api.getEscalatedReports();
  container.innerHTML = '';

  if (!data?.reports || data.reports.length === 0) {
    container.innerHTML = `<div style="padding: 20px; color: var(--text-subtle); background: var(--bg-card); border-radius: 8px;">No hay reportes de convivencia pendientes de escalación.</div>`;
    return;
  }

  data.reports.forEach((r) => {
    const card = document.createElement('div');
    card.className = 'ticket-item';
    card.innerHTML = `
      <div class="ticket-top">
        <span class="pill pill-suspended">REPORTE ESCALADO</span>
        <span style="font-size:0.75rem; color:var(--text-subtle);">${new Date(r.created_at).toLocaleString()}</span>
      </div>
      <div class="ticket-subject">Motivo: ${escapeHtml((r.reason || '').toUpperCase())}</div>
      <div style="font-size:0.85rem; color:var(--text-muted); background:var(--bg-main); padding:10px; border-radius:6px;">
        ${escapeHtml(r.notes || 'Conducta infractora denunciada por miembros en carretera.')}
      </div>
      <div class="ticket-meta">
        <span><strong>Denunciante:</strong> ${escapeHtml(r.reporter_name || r.reporter_email)}</span>
        <span><strong>Reportado:</strong> ${escapeHtml(r.reported_name || r.reported_email)}</span>
        <span><strong>Club:</strong> ${escapeHtml(r.group_name)}</span>
      </div>
      <div style="display:flex; gap:8px; margin-top:6px;">
        <button class="btn-primary btn-resolve-report" data-id="${r.id}" data-action="warning">Emitir Advertencia</button>
        <button class="btn-secondary btn-resolve-report" data-id="${r.id}" data-action="platform_suspended" style="color:var(--crimson-danger); border-color:var(--crimson-danger);">Suspender Cuenta</button>
      </div>
    `;
    container.appendChild(card);
  });

  document.querySelectorAll('.btn-resolve-report').forEach((b) => {
    b.addEventListener('click', async (e) => {
      const repId = e.target.dataset.id;
      const action = e.target.dataset.action;
      const notes = prompt('Escribe el motivo o nota de resolución oficial:', 'Resolución de convivencia emitida');
      if (notes !== null) {
        await api.resolveReport(repId, action, notes);
        alert('Reporte resuelto y archivado en auditoría.');
        loadEscalatedReports();
      }
    });
  });
}

let activeTicketId = null;

async function loadTicketsList() {
  const listContainer = document.getElementById('support-tickets-list');
  if (!listContainer) return;

  const data = await api.getSupportTickets();
  listContainer.innerHTML = '';

  if (!data?.tickets || data.tickets.length === 0) {
    listContainer.innerHTML = `<div style="padding: 20px; color: var(--text-subtle);">No hay tickets de soporte registrados.</div>`;
    return;
  }

  data.tickets.forEach((t) => {
    const item = document.createElement('div');
    item.className = `ticket-item ${t.id === activeTicketId ? 'active' : ''}`;
    item.dataset.id = t.id;
    item.innerHTML = `
      <div class="ticket-top">
        <span class="pill pill-mc">${t.category}</span>
        <span class="pill ${t.status === 'resolved' ? 'pill-active' : 'pill-grace'}">${t.status}</span>
      </div>
      <div class="ticket-subject">${escapeHtml(t.subject)}</div>
      <div class="ticket-meta">
        <span>👤 ${escapeHtml(t.userName || t.userEmail)}</span>
        <span>💬 ${t.messagesCount} msgs</span>
      </div>
    `;
    item.addEventListener('click', () => {
      activeTicketId = t.id;
      document.querySelectorAll('#support-tickets-list .ticket-item').forEach((i) => i.classList.remove('active'));
      item.classList.add('active');
      openTicketChat(t.id);
    });
    listContainer.appendChild(item);
  });

  if (data.tickets.length > 0 && !activeTicketId) {
    activeTicketId = data.tickets[0].id;
    openTicketChat(activeTicketId);
  }
}

async function openTicketChat(ticketId) {
  const chatSubject = document.getElementById('chat-ticket-subject');
  const chatMeta = document.getElementById('chat-ticket-meta');
  const messagesBox = document.getElementById('chat-messages-container');
  const inputMessage = document.getElementById('input-ticket-reply');
  const sendBtn = document.getElementById('btn-send-reply');

  if (!chatSubject || !messagesBox) return;

  const data = await api.getTicketDetails(ticketId);
  if (!data || !data.ticket) return;

  chatSubject.textContent = data.ticket.subject;
  chatMeta.textContent = `${data.ticket.user_name || data.ticket.userName || 'Usuario'} (${data.ticket.group_name || data.ticket.groupName || 'Sin club'}) · Categoría: ${data.ticket.category}`;

  messagesBox.innerHTML = '';
  if (data.messages) {
    data.messages.forEach((m) => {
      const bubble = document.createElement('div');
      bubble.className = `chat-bubble ${m.is_admin ? 'admin' : 'user'}`;
      bubble.innerHTML = `
        <div class="chat-bubble-author">${m.is_admin ? '🛡️ Soporte Ruta Biker Sync' : escapeHtml(m.sender_name || 'Piloto')}</div>
        <div>${escapeHtml(m.message)}</div>
        <div style="font-size:0.68rem; color:var(--text-subtle); text-align:right; margin-top:4px;">${new Date(m.created_at).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })}</div>
      `;
      messagesBox.appendChild(bubble);
    });
  }
  messagesBox.scrollTop = messagesBox.scrollHeight;

  if (sendBtn) {
    sendBtn.onclick = async () => {
      const msg = inputMessage ? inputMessage.value.trim() : '';
      if (!msg) return;
      await api.sendTicketMessage(ticketId, msg, 'waiting_user');
      if (inputMessage) inputMessage.value = '';
      openTicketChat(ticketId);
      loadTicketsList();
    };
  }
}

/**
 * 5. Auditoría Forense de Paquetes SOS
 */
async function loadSosAuditTable() {
  const tableBody = document.getElementById('sos-audit-table-body');
  if (!tableBody) return;

  tableBody.innerHTML = `<tr><td colspan="5" style="text-align:center; padding:30px; color:var(--text-subtle);">Cargando registros forenses de SOS...</td></tr>`;

  const data = await api.getSosAuditLogs();
  tableBody.innerHTML = '';

  if (!data?.logs || data.logs.length === 0) {
    tableBody.innerHTML = `<tr><td colspan="5" style="text-align:center; padding:30px; color:var(--text-subtle);">No hay aperturas de emergencia registradas.</td></tr>`;
    return;
  }

  data.logs.forEach((l) => {
    const tr = document.createElement('tr');
    tr.innerHTML = `
      <td><span style="font-family:monospace; color:var(--text-gold);">${new Date(l.created_at).toLocaleString()}</span></td>
      <td><strong>${escapeHtml(l.ride_title || 'Rodada en vivo')}</strong></td>
      <td>${escapeHtml(l.subject_name || l.subject_email)}</td>
      <td>${escapeHtml(l.accessor_name || l.accessor_email)}</td>
      <td><span class="pill ${l.reason === 'sos' ? 'pill-suspended' : 'pill-gang'}">${(l.reason || '').toUpperCase()}</span></td>
    `;
    tableBody.appendChild(tr);
  });
}

// Gestión de Banners Promocionales desde Soporte
function resetPromoBannerForm() {
  const idEl = document.getElementById('promo-banner-id');
  if (!idEl) return;
  idEl.value = '';
  document.getElementById('promo-title').value = '';
  document.getElementById('promo-badge').value = '';
  document.getElementById('promo-subtitle').value = '';
  document.getElementById('promo-image-url').value = '';
  document.getElementById('promo-color').value = '#E5B869';
  document.getElementById('promo-cta-label').value = 'Ver más';
  document.getElementById('promo-cta-target').value = '';
  document.getElementById('promo-frequency').value = 'once_per_day';
  document.getElementById('promo-priority').value = '10';
  document.getElementById('promo-active').checked = true;
}

function initPromoBannersForm() {
  const saveBtn = document.getElementById('btn-save-promo-banner');
  const resetBtn = document.getElementById('btn-reset-promo-banner');
  if (resetBtn) resetBtn.addEventListener('click', resetPromoBannerForm);
  if (!saveBtn) return;

  saveBtn.addEventListener('click', async () => {
    const id = document.getElementById('promo-banner-id')?.value.trim();
    const title = document.getElementById('promo-title')?.value.trim() || '';
    if (title.length < 2) {
      alert('Ingresa un título válido para el banner.');
      return;
    }
    const payload = {
      title,
      badgeText: document.getElementById('promo-badge')?.value.trim() || '',
      subtitle: document.getElementById('promo-subtitle')?.value.trim() || '',
      imageUrl: document.getElementById('promo-image-url')?.value.trim() || '',
      accentColorHex: document.getElementById('promo-color')?.value.trim() || '#E5B869',
      ctaLabel: document.getElementById('promo-cta-label')?.value.trim() || 'Ver más',
      ctaTarget: document.getElementById('promo-cta-target')?.value.trim() || '',
      frequency: document.getElementById('promo-frequency')?.value || 'once_per_day',
      priority: Number(document.getElementById('promo-priority')?.value || 0),
      active: Boolean(document.getElementById('promo-active')?.checked)
    };
    const res = id
      ? await api.updatePromoBanner(id, payload)
      : await api.createPromoBanner(payload);
    if (!res) {
      alert('No se pudo guardar el banner. Verifica tu clave admin y conexión.');
      return;
    }
    resetPromoBannerForm();
    loadPromoBannersAdmin();
  });
}

async function loadPromoBannersAdmin() {
  const container = document.getElementById('promo-banners-list');
  if (!container) return;
  const data = await api.getPromoBanners();
  const banners = data?.banners || [];
  container.innerHTML = '';

  if (banners.length === 0) {
    container.innerHTML = '<div style="color: var(--text-subtle); font-size: 0.85rem;">No hay banners promocionales configurados todavía.</div>';
    return;
  }

  banners.forEach((b) => {
    const row = document.createElement('div');
    row.className = 'ticket-item';
    row.style.borderLeft = `4px solid ${escapeHtml(b.accentColorHex || '#E5B869')}`;
    row.innerHTML = `
      <div class="ticket-top">
        <span class="pill ${b.active ? 'pill-active' : 'pill-suspended'}">${b.active ? 'ACTIVO' : 'PAUSADO'}</span>
        <span style="font-size:0.75rem; color:var(--text-subtle);">Prioridad ${b.priority} · ${escapeHtml(b.frequency)}</span>
      </div>
      <div class="ticket-subject">${escapeHtml(b.title)} ${b.badgeText ? `· [${escapeHtml(b.badgeText)}]` : ''}</div>
      <div style="font-size:0.82rem; color:var(--text-muted);">${escapeHtml(b.subtitle || 'Sin subtítulo')}</div>
      <div style="display:flex; gap:8px; margin-top:8px; flex-wrap:wrap;">
        <button class="btn-secondary btn-edit-promo" data-id="${b.id}">Editar</button>
        <button class="btn-secondary btn-toggle-promo" data-id="${b.id}" data-active="${!b.active}">${b.active ? 'Pausar' : 'Activar'}</button>
        <button class="btn-secondary btn-del-promo" data-id="${b.id}" style="color:var(--crimson-danger); border-color:var(--crimson-danger);">Eliminar</button>
      </div>
    `;
    row.querySelector('.btn-edit-promo')?.addEventListener('click', () => {
      document.getElementById('promo-banner-id').value = b.id;
      document.getElementById('promo-title').value = b.title || '';
      document.getElementById('promo-badge').value = b.badgeText || '';
      document.getElementById('promo-subtitle').value = b.subtitle || '';
      document.getElementById('promo-image-url').value = b.imageUrl || '';
      document.getElementById('promo-color').value = b.accentColorHex || '#E5B869';
      document.getElementById('promo-cta-label').value = b.ctaLabel || 'Ver más';
      document.getElementById('promo-cta-target').value = b.ctaTarget || '';
      document.getElementById('promo-frequency').value = b.frequency || 'once_per_day';
      document.getElementById('promo-priority').value = String(b.priority ?? 0);
      document.getElementById('promo-active').checked = Boolean(b.active);
    });
    row.querySelector('.btn-toggle-promo')?.addEventListener('click', async () => {
      await api.updatePromoBanner(b.id, { active: !b.active });
      loadPromoBannersAdmin();
    });
    row.querySelector('.btn-del-promo')?.addEventListener('click', async () => {
      await api.deletePromoBanner(b.id);
      loadPromoBannersAdmin();
    });
    container.appendChild(row);
  });
}

// Utilidades
function escapeHtml(str) {
  if (!str) return '';
  return String(str)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#039;');
}

function debounce(func, wait) {
  let timeout;
  return function (...args) {
    clearTimeout(timeout);
    timeout = setTimeout(() => func.apply(this, args), wait);
  };
}

})();
