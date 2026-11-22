/**
 * Ruta Biker Sync — Cliente API del Backoffice.
 *
 * Sin credenciales hardcodeadas y sin datos demo: si no hay datos reales, la
 * interfaz lo indica. La clave admin y la clave de piloto se guardan solo en
 * sessionStorage (se pierden al cerrar la pestaña).
 */

window.AdminApi = class AdminApi {
  constructor() {
    this.baseUrl = window.location.protocol === 'file:' ? 'http://localhost:3000' : window.location.origin;
    this.adminKey = sessionStorage.getItem('rbs_admin_key') || '';
    this.pilotKey = sessionStorage.getItem('rbs_pilot_key') || '';
    this.isLiveApi = false;
  }

  setAdminKey(key) {
    this.adminKey = key.trim();
    if (this.adminKey) sessionStorage.setItem('rbs_admin_key', this.adminKey);
    else sessionStorage.removeItem('rbs_admin_key');
  }

  setPilotKey(key) {
    this.pilotKey = key.trim();
    if (this.pilotKey) sessionStorage.setItem('rbs_pilot_key', this.pilotKey);
    else sessionStorage.removeItem('rbs_pilot_key');
  }

  isConfigured() {
    return Boolean(this.adminKey);
  }

  getHeaders() {
    const headers = { 'Content-Type': 'application/json' };
    if (this.adminKey) headers['x-admin-key'] = this.adminKey;
    if (this.pilotKey) headers['x-rbs-pilot-key'] = this.pilotKey;
    return headers;
  }

  /** Toda llamada al backoffice falla en claro si falta la clave o el backend. */
  async request(endpoint, options = {}) {
    if (!this.adminKey) throw new Error('ADMIN_KEY_REQUIRED');
    const res = await fetch(`${this.baseUrl}${endpoint}`, {
      ...options,
      headers: { ...this.getHeaders(), ...(options.headers || {}) }
    });
    if (res.status === 401 || res.status === 403 || res.status === 429) {
      throw new Error(res.status === 429 ? 'TOO_MANY_REQUESTS' : 'UNAUTHORIZED_ADMIN');
    }
    const body = await res.json().catch(() => ({}));
    if (!res.ok) throw new Error(body.message || body.error || `HTTP ${res.status}`);
    this.isLiveApi = true;
    return body;
  }

  async getAnalyticsOverview() {
    return this.request('/v1/admin/analytics/overview');
  }

  async getGroups(query = {}) {
    return this.request(`/v1/admin/groups?${new URLSearchParams(query)}`);
  }

  async updateGroupStatus(groupId, status, reason = '') {
    return this.request(`/v1/admin/groups/${encodeURIComponent(groupId)}/status`, {
      method: 'PATCH',
      body: JSON.stringify({ status, reason })
    });
  }

  async extendGroupGrace(groupId, extraDays = 7, reason = '') {
    return this.request(`/v1/admin/groups/${encodeURIComponent(groupId)}/extend-grace`, {
      method: 'POST',
      body: JSON.stringify({ extraDays, reason })
    });
  }

  async getUsers(query = {}) {
    return this.request(`/v1/admin/users?${new URLSearchParams(query)}`);
  }

  async updateUserStatus(userId, status, reason = '') {
    return this.request(`/v1/admin/users/${encodeURIComponent(userId)}/status`, {
      method: 'PATCH',
      body: JSON.stringify({ status, reason })
    });
  }

  async getEscalatedReports() {
    return this.request('/v1/admin/support/reports');
  }

  async resolveReport(reportId, resolution, notes = '') {
    return this.request(`/v1/admin/support/reports/${encodeURIComponent(reportId)}`, {
      method: 'PATCH',
      body: JSON.stringify({ resolution, notes })
    });
  }

  async getSupportTickets(query = {}) {
    return this.request(`/v1/admin/support/tickets?${new URLSearchParams(query)}`);
  }

  async getTicketDetails(ticketId) {
    return this.request(`/v1/admin/support/tickets/${encodeURIComponent(ticketId)}`);
  }

  async sendTicketMessage(ticketId, message, nextStatus = 'waiting_user') {
    return this.request(`/v1/admin/support/tickets/${encodeURIComponent(ticketId)}/messages`, {
      method: 'POST',
      body: JSON.stringify({ senderId: 'backoffice', isAdmin: true, message, nextStatus })
    });
  }

  async getSosAuditLogs() {
    return this.request('/v1/admin/audit/sos');
  }

  async getDeviceTelemetry() {
    return this.request('/v1/support/devices');
  }

  async getDeviceEvents(fingerprint) {
    return this.request(`/v1/support/devices/${encodeURIComponent(fingerprint)}/events`);
  }
};
