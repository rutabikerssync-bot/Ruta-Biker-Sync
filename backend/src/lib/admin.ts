/**
 * RBS Admin, Support & Analytics Service
 * Módulo de lógica de negocio para administración, moderación, soporte y métricas.
 */
import { randomUUID } from 'node:crypto';
import type { DbExecutor } from '../db/pool.js';

export interface AdminAnalyticsOverview {
  users: {
    total: number;
    active: number;
    suspended: number;
    ranks: {
      novato: number;
      rider: number;
      veterano: number;
      elite: number;
      leyenda: number;
    };
  };
  groups: {
    total: number;
    active: number;
    suspended: number;
    byTier: {
      rc: number;
      mg: number;
      gang: number;
      mc: number;
    };
    byCountry: Record<string, number>;
  };
  rides: {
    total: number;
    completed: number;
    active: number;
    planned: number;
    totalKm: number;
    completionRatePct: number;
  };
  safetySos: {
    totalAlerts: number;
    cancelledIn20s: number;
    confirmedEmergencies: number;
    falseAlarmRatePct: number;
    avgResponseMinutes: number;
  };
  meshOffline: {
    ridesOfflineCount: number;
    offlineRatioPct: number;
    totalHeartbeatsLogged: number;
  };
  subscriptions: {
    activeLeaders: number;
    activeMemberSlots: number;
    monthlyEstimatedRevenueUsd: number;
    inGracePeriod: number;
  };
}

export interface AdminGroupSummary {
  id: string;
  name: string;
  countryCode: string;
  tier: string;
  status: string;
  principalLeader: string;
  principalLeaderName: string | null;
  principalLeaderEmail: string;
  memberCount: number;
  createdAt: string;
  sponsorGroupId: string | null;
  subscriptionState: string | null;
}

export interface AdminUserSummary {
  id: string;
  email: string;
  displayName: string | null;
  countryCode: string;
  accountType: string;
  status: string;
  createdAt: string;
  currentClubName: string | null;
  currentClubRole: string | null;
  rankCode: string;
  totalXp: number;
  totalKm: number;
}

export interface SupportTicketItem {
  id: string;
  userId: string;
  userName: string | null;
  userEmail: string;
  groupId: string | null;
  groupName: string | null;
  subject: string;
  category: string;
  priority: string;
  status: string;
  createdAt: string;
  updatedAt: string;
  messagesCount: number;
}


/**
 * Valida si la solicitud contiene la clave de administrador autorizada
 */
export function verifyAdminAccess(
  providedKey: string | undefined,
  envKey: string | undefined = process.env.ADMIN_API_KEY
): boolean {
  const expectedKey = envKey?.trim();
  if (!expectedKey || !providedKey) return false;
  return providedKey === expectedKey;
}

/**
 * Registra una acción en la bitácora inmutable de auditoría administrativa
 */
export async function logAdminAction(
  db: DbExecutor,
  params: {
    adminEmail: string;
    action: string;
    targetType: string;
    targetId?: string;
    details?: Record<string, any>;
    ipAddress?: string;
  }
): Promise<void> {
  await db.query(
    `INSERT INTO admin_audit_logs (id, admin_email, action, target_type, target_id, details, ip_address, created_at)
     VALUES ($1, $2, $3, $4, $5, $6, $7, NOW())`,
    [
      randomUUID(),
      params.adminEmail,
      params.action,
      params.targetType,
      params.targetId ?? null,
      JSON.stringify(params.details ?? {}),
      params.ipAddress ?? '127.0.0.1'
    ]
  );
}

/**
 * Calcula el resumen analítico general de la plataforma
 */
export async function getAdminAnalyticsOverview(db: DbExecutor): Promise<AdminAnalyticsOverview> {
  // 1. Usuarios y Rangos
  const usersRes = await db.query(
    `SELECT
       COUNT(*) AS total,
       COUNT(*) FILTER (WHERE status = 'active') AS active,
       COUNT(*) FILTER (WHERE status = 'suspended') AS suspended
     FROM users`
  );
  const totalUsers = parseInt(usersRes.rows[0]?.total ?? '0', 10);
  const activeUsers = parseInt(usersRes.rows[0]?.active ?? '0', 10);
  const suspendedUsers = parseInt(usersRes.rows[0]?.suspended ?? '0', 10);

  const ranksRes = await db.query(
    `SELECT
       COUNT(*) FILTER (WHERE xp < 100) AS novato,
       COUNT(*) FILTER (WHERE xp >= 100 AND xp < 500) AS rider,
       COUNT(*) FILTER (WHERE xp >= 500 AND xp < 2000) AS veterano,
       COUNT(*) FILTER (WHERE xp >= 2000 AND xp < 5000) AS elite,
       COUNT(*) FILTER (WHERE xp >= 5000) AS leyenda
     FROM rank_progress`
  );
  const rankRow = ranksRes.rows[0] ?? {};

  // 2. Clubes y Tiers
  const groupsRes = await db.query(
    `SELECT
       COUNT(*) AS total,
       COUNT(*) FILTER (WHERE status = 'active') AS active,
       COUNT(*) FILTER (WHERE status = 'suspended') AS suspended,
       COUNT(*) FILTER (WHERE tier = 'RC' OR tier IS NULL) AS tier_rc,
       COUNT(*) FILTER (WHERE tier = 'MG') AS tier_mg,
       COUNT(*) FILTER (WHERE tier = 'GANG') AS tier_gang,
       COUNT(*) FILTER (WHERE tier = 'MC') AS tier_mc
     FROM groups`
  );
  const gRow = groupsRes.rows[0] ?? {};

  const countriesRes = await db.query(
    `SELECT country_code, COUNT(*) as count FROM groups GROUP BY country_code`
  );
  const byCountry: Record<string, number> = {};
  for (const r of countriesRes.rows) {
    byCountry[r.country_code] = parseInt(r.count, 10);
  }

  // 3. Rodadas
  const ridesRes = await db.query(
    `SELECT
       COUNT(*) AS total,
       COUNT(*) FILTER (WHERE state = 'closed' OR state = 'validated') AS completed,
       COUNT(*) FILTER (WHERE state = 'active') AS active,
       COUNT(*) FILTER (WHERE state = 'planned') AS planned
     FROM rides`
  );
  const rRow = ridesRes.rows[0] ?? {};
  const totalRides = parseInt(rRow.total ?? '0', 10);
  const completedRides = parseInt(rRow.completed ?? '0', 10);
  const activeRides = parseInt(rRow.active ?? '0', 10);
  const plannedRides = parseInt(rRow.planned ?? '0', 10);

  const kmRes = await db.query(`SELECT COALESCE(SUM(total_km), 0) AS total_km FROM rank_progress`);
  const totalKm = parseFloat(kmRes.rows[0]?.total_km ?? '0');
  const completionRatePct = totalRides > 0 ? Math.round((completedRides / totalRides) * 100) : 0;

  // 4. Seguridad Vial y SOS
  const sosAuditRes = await db.query(
    `SELECT
       COUNT(*) AS total_accesses,
       COUNT(*) FILTER (WHERE reason = 'sos') AS sos_reason,
       COUNT(*) FILTER (WHERE reason = 'leader_declared') AS leader_reason
     FROM emergency_access_log`
  );
  const sosAlerts = parseInt(sosAuditRes.rows[0]?.sos_reason ?? '0', 10);
  // Solo se reportan valores observados: no hay columna de cancelación en 20s ni de tiempo de respuesta.
  const cancelledAlerts = 0;
  const confirmedAlerts = sosAlerts;
  const falseAlarmRatePct = 0;

  // 5. Mesh Offline
  const meshRes = await db.query(
    `SELECT
       COUNT(*) AS metrics_count,
       COALESCE(SUM(participants), 0) AS participants_sum
     FROM ride_mesh_metrics`
  );
  const metricsCount = parseInt(meshRes.rows[0]?.metrics_count ?? '0', 10);
  const offlineRatioPct = totalRides > 0 ? Math.min(100, Math.round((metricsCount / totalRides) * 100)) : 0;

  // 6. Suscripciones y Finanzas
  const subsRes = await db.query(
    `SELECT
       COUNT(*) FILTER (WHERE kind = 'leader' AND state = 'active') AS active_leaders,
       COUNT(*) FILTER (WHERE kind = 'member_slot' AND state = 'active') AS active_members,
       COUNT(*) FILTER (WHERE state = 'grace') AS in_grace
     FROM subscriptions`
  );
  const sRow = subsRes.rows[0] ?? {};
  const activeLeaders = parseInt(sRow.active_leaders ?? '0', 10);
  const activeMemberSlots = parseInt(sRow.active_members ?? '0', 10);
  const inGracePeriod = parseInt(sRow.in_grace ?? '0', 10);
  const monthlyRevenue = (activeLeaders * 15.0) + (activeMemberSlots * 2.5);

  return {
    users: {
      total: totalUsers,
      active: activeUsers,
      suspended: suspendedUsers,
      ranks: {
        novato: parseInt(rankRow.novato ?? '0', 10),
        rider: parseInt(rankRow.rider ?? '0', 10),
        veterano: parseInt(rankRow.veterano ?? '0', 10),
        elite: parseInt(rankRow.elite ?? '0', 10),
        leyenda: parseInt(rankRow.leyenda ?? '0', 10)
      }
    },
    groups: {
      total: parseInt(gRow.total ?? '0', 10),
      active: parseInt(gRow.active ?? '0', 10),
      suspended: parseInt(gRow.suspended ?? '0', 10),
      byTier: {
        rc: parseInt(gRow.tier_rc ?? '0', 10),
        mg: parseInt(gRow.tier_mg ?? '0', 10),
        gang: parseInt(gRow.tier_gang ?? '0', 10),
        mc: parseInt(gRow.tier_mc ?? '0', 10)
      },
      byCountry
    },
    rides: {
      total: totalRides,
      completed: completedRides,
      active: activeRides,
      planned: plannedRides,
      totalKm: Math.round(totalKm * 10) / 10,
      completionRatePct
    },
    safetySos: {
      totalAlerts: confirmedAlerts + cancelledAlerts,
      cancelledIn20s: cancelledAlerts,
      confirmedEmergencies: confirmedAlerts,
      falseAlarmRatePct,
      avgResponseMinutes: 0
    },
    meshOffline: {
      ridesOfflineCount: metricsCount,
      offlineRatioPct,
      totalHeartbeatsLogged: 0
    },
    subscriptions: {
      activeLeaders,
      activeMemberSlots,
      monthlyEstimatedRevenueUsd: Math.round(monthlyRevenue * 100) / 100,
      inGracePeriod
    }
  };
}

/**
 * Retorna la lista de clubes para administración
 */
export async function listAdminGroups(
  db: DbExecutor,
  options: { search?: string; countryCode?: string; tier?: string; status?: string; limit?: number; offset?: number }
): Promise<{ groups: AdminGroupSummary[]; total: number }> {
  const limit = options.limit ?? 50;
  const offset = options.offset ?? 0;

  const conditions: string[] = [];
  const params: unknown[] = [];
  let paramIndex = 1;

  if (options.search) {
    conditions.push(`(g.name ILIKE $${paramIndex} OR u.email ILIKE $${paramIndex})`);
    params.push(`%${options.search}%`);
    paramIndex++;
  }
  if (options.countryCode) {
    conditions.push(`g.country_code = $${paramIndex}`);
    params.push(options.countryCode.toUpperCase());
    paramIndex++;
  }
  if (options.tier) {
    conditions.push(`g.tier = $${paramIndex}`);
    params.push(options.tier);
    paramIndex++;
  }
  if (options.status) {
    conditions.push(`g.status = $${paramIndex}`);
    params.push(options.status);
    paramIndex++;
  }

  const whereClause = conditions.length > 0 ? `WHERE ${conditions.join(' AND ')}` : '';

  const countRes = await db.query(
    `SELECT COUNT(*) AS total
     FROM groups g
     JOIN users u ON g.principal_leader = u.id
     ${whereClause}`,
    params
  );
  const total = parseInt(countRes.rows[0]?.total ?? '0', 10);

  const queryParams = [...params, limit, offset];
  const groupsRes = await db.query(
    `SELECT
       g.id,
       g.name,
       g.country_code,
       COALESCE(g.tier, 'RC') AS tier,
       g.status,
       g.principal_leader,
       u.display_name AS principal_leader_name,
       u.email AS principal_leader_email,
       g.created_at,
       g.sponsor_group_id,
       (SELECT COUNT(*) FROM memberships m WHERE m.group_id = g.id AND m.invite_state = 'accepted') AS member_count,
       (SELECT s.state FROM subscriptions s WHERE s.user_id = g.principal_leader AND s.kind = 'leader' ORDER BY s.created_at DESC LIMIT 1) AS subscription_state
     FROM groups g
     JOIN users u ON g.principal_leader = u.id
     ${whereClause}
     ORDER BY g.created_at DESC
     LIMIT $${paramIndex} OFFSET $${paramIndex + 1}`,
    queryParams
  );

  const groups: AdminGroupSummary[] = groupsRes.rows.map((r) => ({
    id: r.id,
    name: r.name,
    countryCode: r.country_code,
    tier: r.tier,
    status: r.status,
    principalLeader: r.principal_leader,
    principalLeaderName: r.principal_leader_name,
    principalLeaderEmail: r.principal_leader_email,
    memberCount: parseInt(r.member_count ?? '0', 10),
    createdAt: r.created_at,
    sponsorGroupId: r.sponsor_group_id,
    subscriptionState: r.subscription_state
  }));

  return { groups, total };
}

/**
 * Obtiene el detalle completo de un club para auditoría administrativa
 */
export async function getAdminGroupDetails(db: DbExecutor, groupId: string) {
  const gRes = await db.query(
    `SELECT g.*, u.display_name as leader_name, u.email as leader_email
     FROM groups g
     JOIN users u ON g.principal_leader = u.id
     WHERE g.id = $1`,
    [groupId]
  );
  if (gRes.rows.length === 0) return null;
  const group = gRes.rows[0];

  const leadersRes = await db.query(
    `SELECT gl.user_id, gl.is_principal, gl.club_role, u.display_name, u.email,
            s.state as sub_state, s.grace_until
     FROM group_leaders gl
     JOIN users u ON gl.user_id = u.id
     LEFT JOIN subscriptions s ON s.user_id = gl.user_id AND s.kind = 'leader'
     WHERE gl.group_id = $1`,
    [groupId]
  );

  const membersRes = await db.query(
    `SELECT m.user_id, m.status_kind, m.club_role, m.invite_state, m.created_at,
            u.display_name, u.email, rp.xp, rp.total_km
     FROM memberships m
     JOIN users u ON m.user_id = u.id
     LEFT JOIN rank_progress rp ON rp.user_id = m.user_id
     WHERE m.group_id = $1
     ORDER BY m.created_at DESC`,
    [groupId]
  );

  const ridesRes = await db.query(
    `SELECT r.id, r.title, r.state, r.starts_at, r.closed_at,
            (SELECT COUNT(*) FROM ride_participants rp WHERE rp.ride_id = r.id) as participants_count
     FROM rides r
     WHERE r.host_group = $1
     ORDER BY r.starts_at DESC LIMIT 10`,
    [groupId]
  );

  return {
    group,
    leaders: leadersRes.rows,
    members: membersRes.rows,
    recentRides: ridesRes.rows
  };
}

/**
 * Actualiza el estado de un club (activar o suspender)
 */
export async function updateAdminGroupStatus(
  db: DbExecutor,
  groupId: string,
  status: 'active' | 'suspended',
  adminEmail: string,
  reason: string
): Promise<boolean> {
  const res = await db.query(
    `UPDATE groups SET status = $1 WHERE id = $2 RETURNING id`,
    [status, groupId]
  );
  if (res.rows.length === 0) return false;

  await logAdminAction(db, {
    adminEmail,
    action: status === 'suspended' ? 'suspend_group' : 'activate_group',
    targetType: 'group',
    targetId: groupId,
    details: { reason }
  });

  return true;
}

/**
 * Extiende el periodo de gracia para una suscripción de líder de club
 */
export async function extendGroupGracePeriod(
  db: DbExecutor,
  groupId: string,
  extraDays: number,
  adminEmail: string,
  reason: string
): Promise<boolean> {
  const leaderRes = await db.query(
    `SELECT principal_leader FROM groups WHERE id = $1`,
    [groupId]
  );
  if (leaderRes.rows.length === 0) return false;
  const leaderId = leaderRes.rows[0].principal_leader;

  const updateSub = await db.query(
    `UPDATE subscriptions
     SET state = 'grace',
         grace_until = COALESCE(grace_until, NOW()) + ($1 || ' days')::interval
     WHERE user_id = $2 AND kind = 'leader'
     RETURNING id, grace_until`,
    [extraDays, leaderId]
  );

  await logAdminAction(db, {
    adminEmail,
    action: 'extend_grace_period',
    targetType: 'group',
    targetId: groupId,
    details: { leaderId, extraDays, reason }
  });

  return updateSub.rows.length > 0;
}

/**
 * Listado de usuarios para administración
 */
export async function listAdminUsers(
  db: DbExecutor,
  options: { search?: string; status?: string; limit?: number; offset?: number }
): Promise<{ users: AdminUserSummary[]; total: number }> {
  const limit = options.limit ?? 50;
  const offset = options.offset ?? 0;

  const conditions: string[] = [];
  const params: unknown[] = [];
  let paramIndex = 1;

  if (options.search) {
    conditions.push(`(u.email ILIKE $${paramIndex} OR u.display_name ILIKE $${paramIndex})`);
    params.push(`%${options.search}%`);
    paramIndex++;
  }
  if (options.status) {
    conditions.push(`u.status = $${paramIndex}`);
    params.push(options.status);
    paramIndex++;
  }

  const whereClause = conditions.length > 0 ? `WHERE ${conditions.join(' AND ')}` : '';

  const countRes = await db.query(`SELECT COUNT(*) AS total FROM users u ${whereClause}`, params);
  const total = parseInt(countRes.rows[0]?.total ?? '0', 10);

  const queryParams = [...params, limit, offset];
  const usersRes = await db.query(
    `SELECT
       u.id,
       u.email,
       u.display_name,
       u.country_code,
       u.account_type,
       u.status,
       u.created_at,
       (SELECT g.name FROM memberships m JOIN groups g ON g.id = m.group_id WHERE m.user_id = u.id AND m.invite_state = 'accepted' LIMIT 1) AS club_name,
       (SELECT m.club_role FROM memberships m WHERE m.user_id = u.id AND m.invite_state = 'accepted' LIMIT 1) AS club_role,
       COALESCE(rp.xp, 0) AS total_xp,
       COALESCE(rp.total_km, 0) AS total_km
     FROM users u
     LEFT JOIN rank_progress rp ON rp.user_id = u.id
     ${whereClause}
     ORDER BY u.created_at DESC
     LIMIT $${paramIndex} OFFSET $${paramIndex + 1}`,
    queryParams
  );

  const users: AdminUserSummary[] = usersRes.rows.map((r) => {
    const xp = parseInt(r.total_xp ?? '0', 10);
    let rankCode = 'novato';
    if (xp >= 5000) rankCode = 'leyenda';
    else if (xp >= 2000) rankCode = 'elite';
    else if (xp >= 500) rankCode = 'veterano';
    else if (xp >= 100) rankCode = 'rider';

    return {
      id: r.id,
      email: r.email,
      displayName: r.display_name,
      countryCode: r.country_code,
      accountType: r.account_type,
      status: r.status,
      createdAt: r.created_at,
      currentClubName: r.club_name,
      currentClubRole: r.club_role,
      rankCode,
      totalXp: xp,
      totalKm: parseFloat(r.total_km ?? '0')
    };
  });

  return { users, total };
}

/**
 * Actualiza el estado de un usuario (activar o suspender)
 */
export async function updateAdminUserStatus(
  db: DbExecutor,
  userId: string,
  status: 'active' | 'suspended',
  adminEmail: string,
  reason: string
): Promise<boolean> {
  const res = await db.query(`UPDATE users SET status = $1 WHERE id = $2 RETURNING id`, [status, userId]);
  if (res.rows.length === 0) return false;

  await logAdminAction(db, {
    adminEmail,
    action: status === 'suspended' ? 'suspend_user' : 'activate_user',
    targetType: 'user',
    targetId: userId,
    details: { reason }
  });

  return true;
}

/**
 * Lista de reportes de moderación escalados al equipo de soporte (R13.5)
 */
export async function listEscalatedReports(db: DbExecutor, limit = 50) {
  const reportsRes = await db.query(
    `SELECT
       r.id,
       r.reporter,
       r.reported,
       r.group_id,
       r.ride_id,
       r.reason,
       r.state,
       r.resolution,
       r.created_at,
       u1.email AS reporter_email,
       u1.display_name AS reporter_name,
       u2.email AS reported_email,
       u2.display_name AS reported_name,
       g.name AS group_name
     FROM moderation_reports r
     JOIN users u1 ON r.reporter = u1.id
     JOIN users u2 ON r.reported = u2.id
     JOIN groups g ON r.group_id = g.id
     WHERE r.state = 'escalated' OR r.state = 'open'
     ORDER BY r.created_at DESC
     LIMIT $1`,
    [limit]
  );
  return reportsRes.rows;
}

/**
 * Resuelve un reporte de moderación escalado
 */
export async function resolveEscalatedReport(
  db: DbExecutor,
  reportId: string,
  resolution: 'warning' | 'muted' | 'ride_removed' | 'group_removed' | 'platform_suspended',
  adminEmail: string,
  notes: string
): Promise<boolean> {
  const updateRes = await db.query(
    `UPDATE moderation_reports
     SET state = 'actioned', resolution = $1
     WHERE id = $2
     RETURNING id, reported, group_id`,
    [resolution, reportId]
  );
  if (updateRes.rows.length === 0) return false;

  const reportedId = updateRes.rows[0].reported;
  if (resolution === 'platform_suspended') {
    await db.query(`UPDATE users SET status = 'suspended' WHERE id = $1`, [reportedId]);
  }

  await logAdminAction(db, {
    adminEmail,
    action: 'resolve_report',
    targetType: 'report',
    targetId: reportId,
    details: { resolution, notes, reportedId }
  });

  return true;
}

/**
 * Lista de tickets de soporte
 */
export async function listSupportTickets(
  db: DbExecutor,
  options: { status?: string; category?: string; priority?: string; limit?: number; offset?: number }
): Promise<{ tickets: SupportTicketItem[]; total: number }> {
  const limit = options.limit ?? 50;
  const offset = options.offset ?? 0;

  const conditions: string[] = [];
  const params: unknown[] = [];
  let paramIndex = 1;

  if (options.status) {
    conditions.push(`st.status = $${paramIndex}`);
    params.push(options.status);
    paramIndex++;
  }
  if (options.category) {
    conditions.push(`st.category = $${paramIndex}`);
    params.push(options.category);
    paramIndex++;
  }
  if (options.priority) {
    conditions.push(`st.priority = $${paramIndex}`);
    params.push(options.priority);
    paramIndex++;
  }

  const whereClause = conditions.length > 0 ? `WHERE ${conditions.join(' AND ')}` : '';

  const countRes = await db.query(`SELECT COUNT(*) AS total FROM support_tickets st ${whereClause}`, params);
  const total = parseInt(countRes.rows[0]?.total ?? '0', 10);

  const queryParams = [...params, limit, offset];
  const ticketsRes = await db.query(
    `SELECT
       st.id,
       st.user_id,
       st.group_id,
       st.subject,
       st.category,
       st.priority,
       st.status,
       st.created_at,
       st.updated_at,
       u.display_name AS user_name,
       u.email AS user_email,
       g.name AS group_name,
       (SELECT COUNT(*) FROM support_ticket_messages stm WHERE stm.ticket_id = st.id) AS messages_count
     FROM support_tickets st
     JOIN users u ON st.user_id = u.id
     LEFT JOIN groups g ON st.group_id = g.id
     ${whereClause}
     ORDER BY st.updated_at DESC
     LIMIT $${paramIndex} OFFSET $${paramIndex + 1}`,
    queryParams
  );

  const tickets: SupportTicketItem[] = ticketsRes.rows.map((r) => ({
    id: r.id,
    userId: r.user_id,
    userName: r.user_name,
    userEmail: r.user_email,
    groupId: r.group_id,
    groupName: r.group_name,
    subject: r.subject,
    category: r.category,
    priority: r.priority,
    status: r.status,
    createdAt: r.created_at,
    updatedAt: r.updated_at,
    messagesCount: parseInt(r.messages_count ?? '0', 10)
  }));

  return { tickets, total };
}

/**
 * Crea un nuevo ticket de soporte
 */
export async function createSupportTicket(
  db: DbExecutor,
  params: {
    userId: string;
    groupId?: string | null;
    subject: string;
    category: string;
    priority?: string;
    initialMessage: string;
  }
): Promise<string> {
  const ticketId = randomUUID();
  await db.query(
    `INSERT INTO support_tickets (id, user_id, group_id, subject, category, priority, status, created_at, updated_at)
     VALUES ($1, $2, $3, $4, $5, $6, 'open', NOW(), NOW())`,
    [
      ticketId,
      params.userId,
      params.groupId ?? null,
      params.subject,
      params.category,
      params.priority ?? 'medium'
    ]
  );

  await db.query(
    `INSERT INTO support_ticket_messages (id, ticket_id, sender_id, is_admin, message, created_at)
     VALUES ($1, $2, $3, false, $4, NOW())`,
    [randomUUID(), ticketId, params.userId, params.initialMessage]
  );

  return ticketId;
}

/**
 * Agrega un mensaje o respuesta a un ticket existente
 */
export async function addTicketMessage(
  db: DbExecutor,
  params: {
    ticketId: string;
    senderId: string;
    isAdmin: boolean;
    message: string;
    nextStatus?: string;
  }
): Promise<string> {
  const msgId = randomUUID();
  await db.query(
    `INSERT INTO support_ticket_messages (id, ticket_id, sender_id, is_admin, message, created_at)
     VALUES ($1, $2, $3, $4, $5, NOW())`,
    [msgId, params.ticketId, params.senderId, params.isAdmin, params.message]
  );

  const newStatus = params.nextStatus ?? (params.isAdmin ? 'waiting_user' : 'in_progress');
  await db.query(
    `UPDATE support_tickets SET status = $1, updated_at = NOW() WHERE id = $2`,
    [newStatus, params.ticketId]
  );

  return msgId;
}

/**
 * Obtiene el detalle de un ticket con su hilo de mensajes
 */
export async function getSupportTicketDetails(db: DbExecutor, ticketId: string) {
  const tRes = await db.query(
    `SELECT st.*, u.display_name AS user_name, u.email AS user_email, g.name AS group_name
     FROM support_tickets st
     JOIN users u ON st.user_id = u.id
     LEFT JOIN groups g ON st.group_id = g.id
     WHERE st.id = $1`,
    [ticketId]
  );
  if (tRes.rows.length === 0) return null;

  const msgsRes = await db.query(
    `SELECT stm.*, u.display_name as sender_name, u.email as sender_email
     FROM support_ticket_messages stm
     JOIN users u ON stm.sender_id = u.id
     WHERE stm.ticket_id = $1
     ORDER BY stm.created_at ASC`,
    [ticketId]
  );

  return {
    ticket: tRes.rows[0],
    messages: msgsRes.rows
  };
}

/**
 * Auditoría forense de accesos a paquetes de emergencia SOS
 */
export async function listSosAccessAudit(db: DbExecutor, limit = 50) {
  const logsRes = await db.query(
    `SELECT
       l.id,
       l.ride_id,
       l.subject,
       l.accessed_by,
       l.reason,
       l.created_at,
       u1.display_name AS subject_name,
       u1.email AS subject_email,
       u2.display_name AS accessor_name,
       u2.email AS accessor_email,
       r.title AS ride_title
     FROM emergency_access_log l
     JOIN users u1 ON l.subject = u1.id
     JOIN users u2 ON l.accessed_by = u2.id
     LEFT JOIN rides r ON l.ride_id = r.id
     ORDER BY l.created_at DESC
     LIMIT $1`,
    [limit]
  );
  return logsRes.rows;
}

/**
 * Histórico de auditoría administrativa
 */
export async function listAdminAuditLogs(db: DbExecutor, limit = 50) {
  const logsRes = await db.query(
    `SELECT * FROM admin_audit_logs ORDER BY created_at DESC LIMIT $1`,
    [limit]
  );
  return logsRes.rows;
}
