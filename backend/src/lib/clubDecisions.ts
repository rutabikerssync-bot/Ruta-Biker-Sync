import { randomUUID } from 'node:crypto';
import { z } from 'zod';
import type { DbExecutor } from '../db/pool.js';

export const DECISION_AUDIENCES = ['DIRECTIVA', 'FOUNDERS', 'FULL_MEMBERS', 'PROSPECTS'] as const;
export type DecisionAudience = (typeof DECISION_AUDIENCES)[number];

export const CreateCustomRoleSchema = z.object({
  title: z.string().trim().min(2).max(40),
  badgeIcon: z.string().trim().min(1).max(32).default('shield'),
  canCreateRide: z.boolean().default(false),
  canModerate: z.boolean().default(false)
});

export const AssignCustomRoleSchema = z.object({
  customRoleId: z.string().uuid().nullable()
});

export const CreateDecisionSchema = z.object({
  title: z.string().trim().min(3).max(120),
  description: z.string().trim().max(1000).default(''),
  category: z.enum(['route', 'membership', 'finance', 'governance', 'general']).default('general'),
  options: z.array(z.string().trim().min(1).max(80)).min(2).max(4).default(['A favor', 'En contra', 'Abstención']),
  allowedAudiences: z.array(z.enum(DECISION_AUDIENCES)).min(1),
  minQuorumPercent: z.number().int().min(0).max(100).default(0),
  durationHours: z.number().int().min(1).max(168).default(48)
});

export const CastDecisionVoteSchema = z.object({
  optionIndex: z.number().int().min(0).max(3)
});

export class ClubDecisionError extends Error {
  constructor(
    public readonly statusCode: number,
    public readonly code: string,
    message: string
  ) {
    super(message);
  }
}

export async function isGroupLeader(
  db: DbExecutor,
  groupId: string,
  userId: string
): Promise<boolean> {
  const res = await db.query(
    `SELECT 1 FROM group_leaders WHERE group_id = $1 AND user_id = $2`,
    [groupId, userId]
  );
  return res.rows.length > 0;
}

export async function getUserAudiencesInGroup(
  db: DbExecutor,
  groupId: string,
  userId: string
): Promise<Set<DecisionAudience>> {
  const audiences = new Set<DecisionAudience>();
  const leaderRes = await db.query<{ is_principal: boolean }>(
    `SELECT is_principal FROM group_leaders WHERE group_id = $1 AND user_id = $2`,
    [groupId, userId]
  );
  if (leaderRes.rows.length > 0) {
    audiences.add('DIRECTIVA');
    if (leaderRes.rows[0]?.is_principal) {
      audiences.add('FOUNDERS');
    }
  }
  const groupRes = await db.query<{ principal_leader: string }>(
    `SELECT principal_leader FROM groups WHERE id = $1`,
    [groupId]
  );
  if (groupRes.rows[0]?.principal_leader === userId) {
    audiences.add('DIRECTIVA');
    audiences.add('FOUNDERS');
  }
  const memberRes = await db.query<{ status_kind: string }>(
    `SELECT status_kind
     FROM memberships
     WHERE group_id = $1 AND user_id = $2 AND invite_state = 'accepted'`,
    [groupId, userId]
  );
  const statusKind = memberRes.rows[0]?.status_kind;
  if (statusKind === 'full') audiences.add('FULL_MEMBERS');
  if (statusKind === 'prospect') audiences.add('PROSPECTS');
  return audiences;
}

export async function countEligibleVoters(
  db: DbExecutor,
  groupId: string,
  allowedAudiences: readonly DecisionAudience[]
): Promise<number> {
  const res = await db.query<{ total: string }>(
    `WITH eligible AS (
       SELECT gl.user_id
       FROM group_leaders gl
       WHERE gl.group_id = $1
         AND ('DIRECTIVA' = ANY($2::text[]) OR (gl.is_principal = true AND 'FOUNDERS' = ANY($2::text[])))
       UNION
       SELECT g.principal_leader AS user_id
       FROM groups g
       WHERE g.id = $1
         AND ('DIRECTIVA' = ANY($2::text[]) OR 'FOUNDERS' = ANY($2::text[]))
       UNION
       SELECT m.user_id
       FROM memberships m
       WHERE m.group_id = $1
         AND m.invite_state = 'accepted'
         AND (
           (m.status_kind = 'full' AND 'FULL_MEMBERS' = ANY($2::text[]))
           OR (m.status_kind = 'prospect' AND 'PROSPECTS' = ANY($2::text[]))
         )
     )
     SELECT COUNT(*)::text AS total FROM eligible`,
    [groupId, allowedAudiences]
  );
  return Number(res.rows[0]?.total ?? 0);
}

export async function listCustomRoles(db: DbExecutor, groupId: string) {
  const res = await db.query(
    `SELECT id, group_id AS "groupId", title, badge_icon AS "badgeIcon",
            can_create_ride AS "canCreateRide", can_moderate AS "canModerate",
            created_by AS "createdBy", created_at AS "createdAt"
     FROM club_custom_roles
     WHERE group_id = $1
     ORDER BY created_at ASC`,
    [groupId]
  );
  return res.rows;
}

export async function createCustomRole(
  db: DbExecutor,
  groupId: string,
  actorUserId: string,
  input: z.infer<typeof CreateCustomRoleSchema>
) {
  if (!(await isGroupLeader(db, groupId, actorUserId))) {
    throw new ClubDecisionError(403, 'LEADER_REQUIRED', 'Solo la directiva puede crear cargos');
  }
  const id = randomUUID();
  const res = await db.query(
    `INSERT INTO club_custom_roles (id, group_id, title, badge_icon, can_create_ride, can_moderate, created_by)
     VALUES ($1, $2, $3, $4, $5, $6, $7)
     RETURNING id, group_id AS "groupId", title, badge_icon AS "badgeIcon",
               can_create_ride AS "canCreateRide", can_moderate AS "canModerate",
               created_by AS "createdBy", created_at AS "createdAt"`,
    [id, groupId, input.title, input.badgeIcon, input.canCreateRide, input.canModerate, actorUserId]
  );
  return res.rows[0];
}

export async function deleteCustomRole(
  db: DbExecutor,
  groupId: string,
  roleId: string,
  actorUserId: string
): Promise<boolean> {
  if (!(await isGroupLeader(db, groupId, actorUserId))) {
    throw new ClubDecisionError(403, 'LEADER_REQUIRED', 'Solo la directiva puede eliminar cargos');
  }
  const res = await db.query(
    `DELETE FROM club_custom_roles WHERE id = $1 AND group_id = $2`,
    [roleId, groupId]
  );
  return (res.rowCount ?? 0) > 0;
}

export async function assignCustomRoleToMember(
  db: DbExecutor,
  groupId: string,
  targetUserId: string,
  actorUserId: string,
  customRoleId: string | null
) {
  if (!(await isGroupLeader(db, groupId, actorUserId))) {
    throw new ClubDecisionError(403, 'LEADER_REQUIRED', 'Solo la directiva puede asignar cargos');
  }
  const memberRes = await db.query<{ status_kind: string; invite_state: string }>(
    `SELECT status_kind, invite_state FROM memberships WHERE group_id = $1 AND user_id = $2`,
    [groupId, targetUserId]
  );
  const member = memberRes.rows[0];
  if (!member || member.invite_state !== 'accepted') {
    throw new ClubDecisionError(404, 'MEMBER_NOT_FOUND', 'Miembro activo no encontrado en el club');
  }
  // Regla P7: invitados (guest) nunca reciben cargos del club
  if (customRoleId && member.status_kind === 'guest') {
    throw new ClubDecisionError(403, 'GUEST_CANNOT_HOLD_ROLE', 'Un invitado (guest) no puede recibir cargos del club (P7)');
  }
  if (customRoleId) {
    const roleCheck = await db.query(
      `SELECT 1 FROM club_custom_roles WHERE id = $1 AND group_id = $2`,
      [customRoleId, groupId]
    );
    if (roleCheck.rows.length === 0) {
      throw new ClubDecisionError(404, 'CUSTOM_ROLE_NOT_FOUND', 'Cargo personalizado no encontrado');
    }
  }
  await db.query(
    `UPDATE memberships SET custom_role_id = $1 WHERE group_id = $2 AND user_id = $3`,
    [customRoleId, groupId, targetUserId]
  );
  return { groupId, userId: targetUserId, customRoleId };
}

export async function createDecision(
  db: DbExecutor,
  groupId: string,
  actorUserId: string,
  input: z.infer<typeof CreateDecisionSchema>
) {
  if (!(await isGroupLeader(db, groupId, actorUserId))) {
    throw new ClubDecisionError(403, 'LEADER_REQUIRED', 'Solo la directiva puede crear decisiones');
  }
  const id = randomUUID();
  const closesAt = new Date(Date.now() + input.durationHours * 3600_000).toISOString();
  const uniqueAudiences = Array.from(new Set(input.allowedAudiences));

  await db.query('BEGIN');
  try {
    await db.query(
      `INSERT INTO club_decisions
         (id, group_id, title, description, category, options, allowed_audiences, min_quorum_percent, state, created_by, closes_at)
       VALUES ($1, $2, $3, $4, $5, $6::jsonb, $7::text[], $8, 'open', $9, $10)`,
      [
        id,
        groupId,
        input.title,
        input.description,
        input.category,
        JSON.stringify(input.options),
        uniqueAudiences,
        input.minQuorumPercent,
        actorUserId,
        closesAt
      ]
    );
    for (let i = 0; i < input.options.length; i += 1) {
      await db.query(
        `INSERT INTO club_decision_tallies (decision_id, option_index, votes_count) VALUES ($1, $2, 0)`,
        [id, i]
      );
    }
    await db.query('COMMIT');
  } catch (err) {
    await db.query('ROLLBACK').catch(() => {});
    throw err;
  }

  return {
    id,
    groupId,
    title: input.title,
    description: input.description,
    category: input.category,
    options: input.options,
    allowedAudiences: uniqueAudiences,
    minQuorumPercent: input.minQuorumPercent,
    state: 'open',
    closesAt
  };
}

export async function listVisibleDecisionsForUser(
  db: DbExecutor,
  groupId: string,
  userId: string
) {
  const userAudiences = await getUserAudiencesInGroup(db, groupId, userId);
  if (userAudiences.size === 0) return [];

  const res = await db.query<{
    id: string;
    group_id: string;
    title: string;
    description: string;
    category: string;
    options: string[];
    allowed_audiences: DecisionAudience[];
    min_quorum_percent: number;
    state: 'open' | 'closed';
    created_at: Date | string;
    closes_at: Date | string;
    closed_at: Date | string | null;
    has_voted: boolean;
  }>(
    `SELECT d.id, d.group_id, d.title, d.description, d.category, d.options,
            d.allowed_audiences, d.min_quorum_percent, d.state, d.created_at,
            d.closes_at, d.closed_at,
            EXISTS(
              SELECT 1 FROM club_decision_receipts r
              WHERE r.decision_id = d.id AND r.user_id = $2
            ) AS has_voted
     FROM club_decisions d
     WHERE d.group_id = $1
       AND d.allowed_audiences && $3::text[]
     ORDER BY d.created_at DESC`,
    [groupId, userId, Array.from(userAudiences)]
  );

  const items = [];
  for (const row of res.rows) {
    const talliesRes = await db.query<{ option_index: number; votes_count: number }>(
      `SELECT option_index, votes_count
       FROM club_decision_tallies
       WHERE decision_id = $1
       ORDER BY option_index ASC`,
      [row.id]
    );
    const receiptsRes = await db.query<{ count: string }>(
      `SELECT COUNT(*)::text AS count FROM club_decision_receipts WHERE decision_id = $1`,
      [row.id]
    );
    const totalVotes = Number(receiptsRes.rows[0]?.count ?? 0);
    const eligibleCount = await countEligibleVoters(db, groupId, row.allowed_audiences);
    const quorumMet =
      row.min_quorum_percent === 0 ||
      (eligibleCount > 0 && totalVotes * 100 >= eligibleCount * row.min_quorum_percent);

    const tallies = talliesRes.rows.map((t) => t.votes_count);
    const winningOptionIndex =
      row.state === 'closed' && totalVotes > 0 && quorumMet
        ? tallies.reduce((bestIdx, val, idx, arr) => (val > arr[bestIdx] ? idx : bestIdx), 0)
        : null;

    items.push({
      id: row.id,
      groupId: row.group_id,
      title: row.title,
      description: row.description,
      category: row.category,
      options: row.options,
      allowedAudiences: row.allowed_audiences,
      minQuorumPercent: row.min_quorum_percent,
      state: row.state,
      hasVoted: row.has_voted,
      totalVotes,
      eligibleCount,
      quorumMet,
      tallies: row.state === 'closed' ? tallies : null,
      winningOptionIndex,
      closesAt: row.closes_at instanceof Date ? row.closes_at.toISOString() : String(row.closes_at),
      closedAt: row.closed_at
        ? row.closed_at instanceof Date
          ? row.closed_at.toISOString()
          : String(row.closed_at)
        : null
    });
  }
  return items;
}

export async function castSecretVote(
  db: DbExecutor,
  decisionId: string,
  userId: string,
  optionIndex: number
) {
  await db.query('BEGIN');
  try {
    const dRes = await db.query<{
      id: string;
      group_id: string;
      options: string[];
      allowed_audiences: DecisionAudience[];
      state: string;
      closes_at: Date | string;
    }>(
      `SELECT id, group_id, options, allowed_audiences, state, closes_at
       FROM club_decisions
       WHERE id = $1
       FOR UPDATE`,
      [decisionId]
    );
    const decision = dRes.rows[0];
    if (!decision) {
      throw new ClubDecisionError(404, 'DECISION_NOT_FOUND', 'Decisión no encontrada');
    }
    if (decision.state !== 'open' || new Date(decision.closes_at).getTime() <= Date.now()) {
      throw new ClubDecisionError(409, 'DECISION_CLOSED', 'La votación ya está cerrada');
    }
    if (optionIndex < 0 || optionIndex >= decision.options.length) {
      throw new ClubDecisionError(400, 'INVALID_OPTION', 'Opción de voto fuera de rango');
    }

    const userAudiences = await getUserAudiencesInGroup(db, decision.group_id, userId);
    const isEligible = decision.allowed_audiences.some((a) => userAudiences.has(a));
    if (!isEligible) {
      throw new ClubDecisionError(403, 'FORBIDDEN_AUDIENCE', 'No perteneces a la audiencia habilitada para esta votación');
    }

    const receiptRes = await db.query(
      `INSERT INTO club_decision_receipts (decision_id, user_id)
       VALUES ($1, $2)
       ON CONFLICT (decision_id, user_id) DO NOTHING
       RETURNING decision_id`,
      [decisionId, userId]
    );
    if (receiptRes.rows.length === 0) {
      throw new ClubDecisionError(409, 'ALREADY_VOTED', 'Ya ejerciste tu voto secreto en esta decisión');
    }

    await db.query(
      `UPDATE club_decision_tallies
       SET votes_count = votes_count + 1
       WHERE decision_id = $1 AND option_index = $2`,
      [decisionId, optionIndex]
    );

    await db.query('COMMIT');
    return { decisionId, recorded: true };
  } catch (err) {
    await db.query('ROLLBACK').catch(() => {});
    throw err;
  }
}

export async function closeDecision(
  db: DbExecutor,
  decisionId: string,
  actorUserId: string
) {
  const dRes = await db.query<{ id: string; group_id: string; state: string }>(
    `SELECT id, group_id, state FROM club_decisions WHERE id = $1`,
    [decisionId]
  );
  const decision = dRes.rows[0];
  if (!decision) {
    throw new ClubDecisionError(404, 'DECISION_NOT_FOUND', 'Decisión no encontrada');
  }
  if (!(await isGroupLeader(db, decision.group_id, actorUserId))) {
    throw new ClubDecisionError(403, 'LEADER_REQUIRED', 'Solo la directiva puede cerrar decisiones');
  }
  await db.query(
    `UPDATE club_decisions
     SET state = 'closed', closed_at = COALESCE(closed_at, now())
     WHERE id = $1`,
    [decisionId]
  );
  return { id: decisionId, state: 'closed' };
}
