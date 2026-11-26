/**
 * Ruta Biker Sync — Cliente API y Generador de Datos Demo del Backoffice
 */

window.AdminApi = class AdminApi {
  constructor() {
    this.baseUrl = window.location.protocol === 'file:' ? 'http://localhost:3000' : window.location.origin;
    this.adminKey = localStorage.getItem('rbs_admin_key') || '';
    this.isLiveApi = true;
    this.lastError = null;
  }

  setAdminKey(key) {
    this.adminKey = key;
    localStorage.setItem('rbs_admin_key', key);
  }

  getHeaders() {
    const headers = {
      'Content-Type': 'application/json'
    };
    if (this.adminKey) {
      headers['x-admin-key'] = this.adminKey;
    }
    return headers;
  }

  ensureStatusBanner() {
    if (!document.body) return null;
    let banner = document.getElementById('rbs-api-status');
    if (!banner) {
      banner = document.createElement('div');
      banner.id = 'rbs-api-status';
      banner.setAttribute('role', 'alert');
      banner.style.cssText = 'position:fixed;top:0;left:0;right:0;z-index:99999;padding:10px 16px;background:#7f1d1d;color:#ffffff;font:600 13px/1.4 system-ui,sans-serif;text-align:center;display:none';
      document.body.appendChild(banner);
    }
    return banner;
  }

  setApiStatus(ok, message = '') {
    this.isLiveApi = ok;
    this.lastError = ok ? null : message;
    const banner = this.ensureStatusBanner();
    if (!banner) return;
    banner.textContent = ok ? '' : `SIN CONEXIÓN CON LA BD / DATOS NO DISPONIBLES — ${message}`;
    banner.style.display = ok ? 'none' : 'block';
  }

  async fetchWithFallback(endpoint, options = {}, emptyData = {}) {
    try {
      const res = await fetch(`${this.baseUrl}${endpoint}`, {
        ...options,
        headers: {
          ...this.getHeaders(),
          ...(options.headers || {})
        }
      });
      if (!res.ok) {
        throw new Error(res.status === 401 ? 'UNAUTHORIZED_ADMIN' : `HTTP ${res.status}`);
      }
      const data = await res.json();
      this.setApiStatus(true);
      return data;
    } catch (err) {
      const message = err instanceof Error ? err.message : String(err);
      console.warn(`[AdminApi] Backend no disponible en ${endpoint}:`, message);
      this.setApiStatus(false, message);
      return emptyData;
    }
  }

  async getAnalyticsOverview() {
    const emptyOverview = {
      users: { total: 0, active: 0, suspended: 0, ranks: { novato: 0, rider: 0, veterano: 0, elite: 0, leyenda: 0 } },
      groups: { total: 0, active: 0, suspended: 0, byTier: { rc: 0, mg: 0, gang: 0, mc: 0 }, byCountry: {} },
      rides: { total: 0, completed: 0, active: 0, planned: 0, totalKm: 0, completionRatePct: 0 },
      safetySos: { totalAlerts: 0, cancelledIn20s: 0, confirmedEmergencies: 0, falseAlarmRatePct: 0, avgResponseMinutes: 0 },
      meshOffline: { ridesOfflineCount: 0, offlineRatioPct: 0, totalHeartbeatsLogged: 0 },
      subscriptions: { activeLeaders: 0, activeMemberSlots: 0, monthlyEstimatedRevenueUsd: 0, inGracePeriod: 0 }
    };
    return await this.fetchWithFallback('/v1/admin/analytics/overview', { method: 'GET' }, emptyOverview);
  }

  async getGroups(query = {}) {
    const qs = new URLSearchParams(query).toString();
    return await this.fetchWithFallback(`/v1/admin/groups?${qs}`, { method: 'GET' }, { groups: [], total: 0 });
  }

  async updateGroupStatus(groupId, status, reason = '') {
    return await this.fetchWithFallback(`/v1/admin/groups/${groupId}/status`, {
      method: 'PATCH',
      body: JSON.stringify({ status, reason, adminEmail: 'admin@rutabikersync.com' })
    }, { success: false, groupId, status });
  }

  async extendGroupGrace(groupId, extraDays = 7, reason = '') {
    return await this.fetchWithFallback(`/v1/admin/groups/${groupId}/extend-grace`, {
      method: 'POST',
      body: JSON.stringify({ extraDays, reason, adminEmail: 'admin@rutabikersync.com' })
    }, { success: false, groupId, extraDaysGranted: extraDays });
  }

  async getUsers(query = {}) {
    const qs = new URLSearchParams(query).toString();
    return await this.fetchWithFallback(`/v1/admin/users?${qs}`, { method: 'GET' }, { users: [], total: 0 });
  }

  async updateUserStatus(userId, status, reason = '') {
    return await this.fetchWithFallback(`/v1/admin/users/${userId}/status`, {
      method: 'PATCH',
      body: JSON.stringify({ status, reason, adminEmail: 'admin@rutabikersync.com' })
    }, { success: false, userId, status });
  }

  async getEscalatedReports() {
    return await this.fetchWithFallback('/v1/admin/support/reports', { method: 'GET' }, { reports: [] });
  }

  async resolveReport(reportId, resolution, notes = '') {
    return await this.fetchWithFallback(`/v1/admin/support/reports/${reportId}`, {
      method: 'PATCH',
      body: JSON.stringify({ resolution, notes, adminEmail: 'admin@rutabikersync.com' })
    }, { success: false, reportId, resolution });
  }

  async getSupportTickets(query = {}) {
    const qs = new URLSearchParams(query).toString();
    return await this.fetchWithFallback(`/v1/admin/support/tickets?${qs}`, { method: 'GET' }, { tickets: [], total: 0 });
  }

  async getTicketDetails(ticketId) {
    return await this.fetchWithFallback(`/v1/admin/support/tickets/${ticketId}`, { method: 'GET' }, { ticket: null, messages: [] });
  }

  async sendTicketMessage(ticketId, message, nextStatus = 'waiting_user') {
    return await this.fetchWithFallback(`/v1/admin/support/tickets/${ticketId}/messages`, {
      method: 'POST',
      body: JSON.stringify({
        senderId: 'admin-console',
        isAdmin: true,
        message,
        nextStatus
      })
    }, { success: false, messageId: null });
  }

  async getSosAuditLogs() {
    return await this.fetchWithFallback('/v1/admin/audit/sos', { method: 'GET' }, { logs: [] });
  }

  async getPromoBanners() {
    return await this.fetchWithFallback('/v1/admin/promo-banners', { method: 'GET' }, { banners: [] });
  }

  async createPromoBanner(payload) {
    return await this.fetchWithFallback('/v1/admin/promo-banners', {
      method: 'POST',
      body: JSON.stringify(payload)
    }, null);
  }

  async updatePromoBanner(id, payload) {
    return await this.fetchWithFallback(`/v1/admin/promo-banners/${id}`, {
      method: 'PATCH',
      body: JSON.stringify(payload)
    }, null);
  }

  async deletePromoBanner(id) {
    return await this.fetchWithFallback(`/v1/admin/promo-banners/${id}`, {
      method: 'DELETE'
    }, { deleted: false });
  }
};
