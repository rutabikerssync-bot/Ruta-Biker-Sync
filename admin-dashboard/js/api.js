/**
 * Ruta Biker Sync — Cliente API y Generador de Datos Demo del Backoffice
 */

window.AdminApi = class AdminApi {
  constructor() {
    this.baseUrl = window.location.protocol === 'file:' ? 'http://localhost:3000' : window.location.origin;
    this.adminKey = localStorage.getItem('rbs_admin_key') || 'rbs-dev-admin-secret-key-2026';
    this.isLiveApi = true;
  }

  setAdminKey(key) {
    this.adminKey = key;
    localStorage.setItem('rbs_admin_key', key);
  }

  getHeaders() {
    return {
      'Content-Type': 'application/json',
      'x-admin-key': this.adminKey
    };
  }

  async fetchWithFallback(endpoint, options = {}, fallbackData = {}) {
    try {
      const res = await fetch(`${this.baseUrl}${endpoint}`, {
        ...options,
        headers: {
          ...this.getHeaders(),
          ...(options.headers || {})
        }
      });
      if (res.ok) {
        return await res.json();
      }
      if (res.status === 401) {
        throw new Error('UNAUTHORIZED_ADMIN');
      }
      return fallbackData;
    } catch (err) {
      console.warn(`[AdminApi] Conexión en vivo con backend falló en ${endpoint}, utilizando datos locales de demostración:`, err);
      this.isLiveApi = false;
      return fallbackData;
    }
  }

  async getAnalyticsOverview() {
    const fallback = {
      users: {
        total: 1420,
        active: 1388,
        suspended: 32,
        ranks: { novato: 420, rider: 580, veterano: 280, elite: 112, leyenda: 28 }
      },
      groups: {
        total: 84,
        active: 81,
        suspended: 3,
        byTier: { rc: 46, mg: 24, gang: 10, mc: 4 },
        byCountry: { MX: 52, CO: 18, AR: 8, CL: 6 }
      },
      rides: {
        total: 2180,
        completed: 2094,
        active: 14,
        planned: 72,
        totalKm: 148560.4,
        completionRatePct: 96
      },
      safetySos: {
        totalAlerts: 34,
        cancelledIn20s: 26,
        confirmedEmergencies: 8,
        falseAlarmRatePct: 76,
        avgResponseMinutes: 3.8
      },
      meshOffline: {
        ridesOfflineCount: 914,
        offlineRatioPct: 42,
        totalHeartbeatsLogged: 128400
      },
      subscriptions: {
        activeLeaders: 84,
        activeMemberSlots: 680,
        monthlyEstimatedRevenueUsd: 2960.00,
        inGracePeriod: 4
      }
    };
    return await this.fetchWithFallback('/v1/admin/analytics/overview', { method: 'GET' }, fallback);
  }

  async getGroups(query = {}) {
    const qs = new URLSearchParams(query).toString();
    const fallback = {
      total: 5,
      groups: [
        {
          id: 'g-001',
          name: 'Halcones del Asfalto MC',
          countryCode: 'MX',
          tier: 'MC',
          status: 'active',
          principalLeader: 'u-001',
          principalLeaderName: 'Comandante Águila',
          principalLeaderEmail: 'aguila@halconesmc.mx',
          memberCount: 24,
          createdAt: '2026-04-18T10:00:00Z',
          sponsorGroupId: null,
          subscriptionState: 'active'
        },
        {
          id: 'g-002',
          name: 'Ángeles de la Sierra MG',
          countryCode: 'CO',
          tier: 'MG',
          status: 'active',
          principalLeader: 'u-002',
          principalLeaderName: 'Carlos Montoya',
          principalLeaderEmail: 'carlos@sierramg.co',
          memberCount: 16,
          createdAt: '2026-06-12T14:30:00Z',
          sponsorGroupId: 'g-001',
          subscriptionState: 'active'
        },
        {
          id: 'g-003',
          name: 'Riders del Valle RC',
          countryCode: 'MX',
          tier: 'RC',
          status: 'active',
          principalLeader: 'u-003',
          principalLeaderName: 'Diana Morales',
          principalLeaderEmail: 'diana@ridersvalle.com',
          memberCount: 12,
          createdAt: '2026-08-01T12:00:00Z',
          sponsorGroupId: null,
          subscriptionState: 'grace'
        },
        {
          id: 'g-004',
          name: 'Espartanos de la Ruta GANG',
          countryCode: 'AR',
          tier: 'GANG',
          status: 'active',
          principalLeader: 'u-004',
          principalLeaderName: 'Mariano Rossi',
          principalLeaderEmail: 'mariano@espartanos.ar',
          memberCount: 18,
          createdAt: '2026-09-10T16:20:00Z',
          sponsorGroupId: 'g-001',
          subscriptionState: 'active'
        },
        {
          id: 'g-005',
          name: 'Lobos Solitarios RC',
          countryCode: 'CL',
          tier: 'RC',
          status: 'suspended',
          principalLeader: 'u-005',
          principalLeaderName: 'Ignacio Vega',
          principalLeaderEmail: 'ignacio@lobos.cl',
          memberCount: 6,
          createdAt: '2026-10-02T11:15:00Z',
          sponsorGroupId: null,
          subscriptionState: 'expired'
        }
      ]
    };
    return await this.fetchWithFallback(`/v1/admin/groups?${qs}`, { method: 'GET' }, fallback);
  }

  async updateGroupStatus(groupId, status, reason = '') {
    return await this.fetchWithFallback(`/v1/admin/groups/${groupId}/status`, {
      method: 'PATCH',
      body: JSON.stringify({ status, reason, adminEmail: 'admin@rutabikersync.com' })
    }, { success: true, groupId, status });
  }

  async extendGroupGrace(groupId, extraDays = 7, reason = '') {
    return await this.fetchWithFallback(`/v1/admin/groups/${groupId}/extend-grace`, {
      method: 'POST',
      body: JSON.stringify({ extraDays, reason, adminEmail: 'admin@rutabikersync.com' })
    }, { success: true, groupId, extraDaysGranted: extraDays });
  }

  async getUsers(query = {}) {
    const qs = new URLSearchParams(query).toString();
    const fallback = {
      total: 4,
      users: [
        {
          id: 'u-001',
          email: 'aguila@halconesmc.mx',
          displayName: 'Comandante Águila',
          countryCode: 'MX',
          accountType: 'leader',
          status: 'active',
          createdAt: '2026-04-18T10:00:00Z',
          currentClubName: 'Halcones del Asfalto MC',
          currentClubRole: 'Presidente',
          rankCode: 'leyenda',
          totalXp: 5850,
          totalKm: 14200.5
        },
        {
          id: 'u-002',
          email: 'carlos@sierramg.co',
          displayName: 'Carlos Montoya',
          countryCode: 'CO',
          accountType: 'leader',
          status: 'active',
          createdAt: '2026-06-12T14:30:00Z',
          currentClubName: 'Ángeles de la Sierra MG',
          currentClubRole: 'Presidente',
          rankCode: 'veterano',
          totalXp: 1840,
          totalKm: 4200.0
        },
        {
          id: 'u-006',
          email: 'pedro@rider.mx',
          displayName: 'Pedro Méndez',
          countryCode: 'MX',
          accountType: 'member',
          status: 'active',
          createdAt: '2026-07-22T09:10:00Z',
          currentClubName: 'Halcones del Asfalto MC',
          currentClubRole: 'Sargento de Armas',
          rankCode: 'rider',
          totalXp: 450,
          totalKm: 890.2
        },
        {
          id: 'u-007',
          email: 'lucas@spartans.ar',
          displayName: 'Lucas Benítez',
          countryCode: 'AR',
          accountType: 'member',
          status: 'suspended',
          createdAt: '2026-09-15T18:00:00Z',
          currentClubName: null,
          currentClubRole: null,
          rankCode: 'novato',
          totalXp: 50,
          totalKm: 45.0
        }
      ]
    };
    return await this.fetchWithFallback(`/v1/admin/users?${qs}`, { method: 'GET' }, fallback);
  }

  async updateUserStatus(userId, status, reason = '') {
    return await this.fetchWithFallback(`/v1/admin/users/${userId}/status`, {
      method: 'PATCH',
      body: JSON.stringify({ status, reason, adminEmail: 'admin@rutabikersync.com' })
    }, { success: true, userId, status });
  }

  async getEscalatedReports() {
    const fallback = {
      reports: [
        {
          id: 'rep-001',
          reporter: 'u-006',
          reporter_name: 'Pedro Méndez',
          reporter_email: 'pedro@rider.mx',
          reported: 'u-005',
          reported_name: 'Ignacio Vega',
          reported_email: 'ignacio@lobos.cl',
          group_id: 'g-005',
          group_name: 'Lobos Solitarios RC',
          reason: 'dangerous',
          state: 'escalated',
          resolution: null,
          created_at: '2026-10-30T19:40:00Z',
          notes: 'Conducción temeraria reiterada durante rodada interclubes'
        }
      ]
    };
    return await this.fetchWithFallback('/v1/admin/support/reports', { method: 'GET' }, fallback);
  }

  async resolveReport(reportId, resolution, notes = '') {
    return await this.fetchWithFallback(`/v1/admin/support/reports/${reportId}`, {
      method: 'PATCH',
      body: JSON.stringify({ resolution, notes, adminEmail: 'admin@rutabikersync.com' })
    }, { success: true, reportId, resolution });
  }

  async getSupportTickets(query = {}) {
    const qs = new URLSearchParams(query).toString();
    const fallback = {
      total: 3,
      tickets: [
        {
          id: 'tk-001',
          userId: 'u-001',
          userName: 'Comandante Águila',
          userEmail: 'aguila@halconesmc.mx',
          groupId: 'g-001',
          groupName: 'Halcones del Asfalto MC',
          subject: 'Consulta sobre prórroga de cupos Google Play',
          category: 'billing',
          priority: 'high',
          status: 'in_progress',
          createdAt: '2026-10-29T14:00:00Z',
          updatedAt: '2026-10-31T11:20:00Z',
          messagesCount: 3
        },
        {
          id: 'tk-002',
          userId: 'u-002',
          userName: 'Carlos Montoya',
          userEmail: 'carlos@sierramg.co',
          groupId: 'g-002',
          groupName: 'Ángeles de la Sierra MG',
          subject: 'Sincronización de audio Mesh en túneles largos',
          category: 'mesh_sync',
          priority: 'medium',
          status: 'waiting_user',
          createdAt: '2026-10-30T09:15:00Z',
          updatedAt: '2026-10-30T16:45:00Z',
          messagesCount: 2
        },
        {
          id: 'tk-003',
          userId: 'u-003',
          userName: 'Diana Morales',
          userEmail: 'diana@ridersvalle.com',
          groupId: 'g-003',
          groupName: 'Riders del Valle RC',
          subject: 'Solicitud de apadrinamiento para ascenso a MG',
          category: 'club_dispute',
          priority: 'low',
          status: 'open',
          createdAt: '2026-10-31T08:00:00Z',
          updatedAt: '2026-10-31T08:00:00Z',
          messagesCount: 1
        }
      ]
    };
    return await this.fetchWithFallback(`/v1/admin/support/tickets?${qs}`, { method: 'GET' }, fallback);
  }

  async getTicketDetails(ticketId) {
    const fallback = {
      ticket: {
        id: ticketId,
        user_id: 'u-001',
        user_name: 'Comandante Águila',
        user_email: 'aguila@halconesmc.mx',
        group_name: 'Halcones del Asfalto MC',
        subject: 'Consulta sobre prórroga de cupos Google Play',
        category: 'billing',
        priority: 'high',
        status: 'in_progress',
        created_at: '2026-10-29T14:00:00Z'
      },
      messages: [
        {
          id: 'm-1',
          sender_id: 'u-001',
          sender_name: 'Comandante Águila',
          is_admin: false,
          message: 'Saludos mesa técnica. Nuestro banco en México reporta una intermitencia con la tarjeta corporativa de cobro de los 5 cupos de miembros. ¿Es posible congelar la suspensión 5 días?',
          created_at: '2026-10-29T14:00:00Z'
        },
        {
          id: 'm-2',
          sender_id: 'adm-1',
          sender_name: 'Soporte Ruta Biker Sync',
          is_admin: true,
          message: 'Buenas tardes Comandante. Se ha verificado su historial impecable de 18 meses. Hemos activado una prórroga de gracia de 7 días adicionales en su panel.',
          created_at: '2026-10-30T10:15:00Z'
        },
        {
          id: 'm-3',
          sender_id: 'u-001',
          sender_name: 'Comandante Águila',
          is_admin: false,
          message: 'Excelente servicio hermanos. La rodada a La Marquesa sigue en pie sin interrupciones. Buenas rutas.',
          created_at: '2026-10-31T11:20:00Z'
        }
      ]
    };
    return await this.fetchWithFallback(`/v1/admin/support/tickets/${ticketId}`, { method: 'GET' }, fallback);
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
    }, { success: true, messageId: 'm-new' });
  }

  async getSosAuditLogs() {
    const fallback = {
      logs: [
        {
          id: '1',
          ride_id: 'r-001',
          ride_title: 'Rodada de Altura: Los Teques ➔ Colonia Tovar',
          subject: 'u-006',
          subject_name: 'Pedro Méndez',
          subject_email: 'pedro@rider.mx',
          accessed_by: 'u-001',
          accessor_name: 'Comandante Águila',
          accessor_email: 'aguila@halconesmc.mx',
          reason: 'sos',
          created_at: '2026-10-31T17:15:22Z'
        },
        {
          id: '2',
          ride_id: 'r-002',
          ride_title: 'Circuito Cañón del Sumidero',
          subject: 'u-002',
          subject_name: 'Carlos Montoya',
          subject_email: 'carlos@sierramg.co',
          accessed_by: 'u-001',
          accessor_name: 'Comandante Águila',
          accessor_email: 'aguila@halconesmc.mx',
          reason: 'leader_declared',
          created_at: '2026-10-25T11:04:10Z'
        }
      ]
    };
    return await this.fetchWithFallback('/v1/admin/audit/sos', { method: 'GET' }, fallback);
  }
};
