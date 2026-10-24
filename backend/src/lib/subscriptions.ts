import { existsSync } from 'node:fs';
import { join } from 'node:path';
import type { DbExecutor } from '../db/pool.js';
import { computePaymentSemaphore, type PaymentSemaphore } from './roles.js';

/**
 * Máquina de 6 estados de suscripción, alarmas de vencimiento, conciliación RTDN idempotente,
 * promoción automática del líder siguiente a los 7 días de impago (RULES R4.5) y suspensión de
 * grupo si caen los 3 líderes conservando el historial intacto (RULES R4.6).
 *
 * IMPORTANTE (Fase 2 — Decisión de alcance):
 * La integración contra Google Play Developer API real está bloqueada por diseño hasta que exista
 * `docs/PLAY_P4_RESULTS.md`. Mientras tanto opera mediante `FakePlayPurchaseVerifier` y `FakeRtdnClient`.
 */

export const SUBSCRIPTION_STATES = [
  'active',
  'grace',
  'on_hold',
  'canceled',
  'expired',
  'refunded'
] as const;

export type SubscriptionState = (typeof SUBSCRIPTION_STATES)[number];

export type SubscriptionKind = 'leader' | 'member_slot';

export const SUBSCRIPTION_POLICY = {
  GRACE_DAYS: Number(process.env.PAYMENT_GRACE_DAYS ?? 3),
  GRACE_MS: Number(process.env.PAYMENT_GRACE_DAYS ?? 3) * 24 * 60 * 60 * 1000,
  ON_HOLD_MAX_DAYS: 30,
  ON_HOLD_MAX_MS: 30 * 24 * 60 * 60 * 1000,
  LEADER_UNPAID_PROMOTION_DAYS: 7,
  LEADER_UNPAID_PROMOTION_MS: 7 * 24 * 60 * 60 * 1000,
  ALARM_THRESHOLDS_DAYS: [7, 3, 1] as const
};

export type SubscriptionAlarmCode =
  | 'expires_in_7d'
  | 'expires_in_3d'
  | 'expires_in_1d'
  | 'payment_failed_grace'
  | 'subscription_on_hold'
  | 'subscription_expired'
  | 'subscription_refunded';

export interface SubscriptionRowInput {
  id: string;
  userId: string;
  kind: SubscriptionKind;
  playProductId: string;
  playPurchaseToken: string;
  state: SubscriptionState;
  currentPeriodEnd: Date;
  graceUntil: Date | null;
  autoRenewing: boolean;
  assignedGroupId: string | null;
}

export interface EvaluatedSubscriptionStatus {
  effectiveState: SubscriptionState;
  accessGranted: boolean;
  alert: 'none' | 'payment_grace' | 'expiring_soon' | 'blocked';
  effectiveAccessUntil: Date | null;
  paymentSemaphore: PaymentSemaphore;
  alarms: SubscriptionAlarmCode[];
  daysRemaining: number | null;
}

/**
 * Evalúa el estado efectivo y acceso de una suscripción según BACKEND_LICENSING.md §5 y RULES R7.3:
 * - `active` o `canceled` con `now < currentPeriodEnd` -> Acceso (`accessGranted = true`).
 * - `grace` con `now < graceUntil` -> Acceso con alerta (`accessGranted = true`, semáforo amarillo).
 * - `on_hold`, `expired`, `refunded` -> Sin acceso (`accessGranted = false`, credencial no se renueva, semáforo rojo).
 */
export function evaluateSubscriptionState(
  sub: Pick<
    SubscriptionRowInput,
    'state' | 'currentPeriodEnd' | 'graceUntil' | 'autoRenewing'
  >,
  now: Date = new Date()
): EvaluatedSubscriptionStatus {
  const nowMs = now.getTime();
  const periodEndMs = sub.currentPeriodEnd.getTime();
  const graceEndMs = sub.graceUntil ? sub.graceUntil.getTime() : null;

  let effectiveState: SubscriptionState = sub.state;

  // Transiciones temporales automáticas cuando vence el periodo o la gracia de 3 días
  if (effectiveState === 'active' && nowMs >= periodEndMs) {
    if (graceEndMs && nowMs < graceEndMs) {
      effectiveState = 'grace';
    } else if (sub.autoRenewing) {
      effectiveState = 'on_hold';
    } else {
      effectiveState = 'expired';
    }
  } else if (effectiveState === 'grace') {
    const effectiveGraceEnd = graceEndMs ?? periodEndMs + SUBSCRIPTION_POLICY.GRACE_MS;
    if (nowMs >= effectiveGraceEnd) {
      effectiveState =
        nowMs >= effectiveGraceEnd + SUBSCRIPTION_POLICY.ON_HOLD_MAX_MS ? 'expired' : 'on_hold';
    }
  } else if (effectiveState === 'canceled' && nowMs >= periodEndMs) {
    effectiveState = 'expired';
  } else if (effectiveState === 'on_hold') {
    const holdStartMs = graceEndMs ?? periodEndMs + SUBSCRIPTION_POLICY.GRACE_MS;
    if (nowMs >= holdStartMs + SUBSCRIPTION_POLICY.ON_HOLD_MAX_MS) {
      effectiveState = 'expired';
    }
  }

  const alarms: SubscriptionAlarmCode[] = [];
  let accessGranted = false;
  let alert: EvaluatedSubscriptionStatus['alert'] = 'none';
  let effectiveAccessUntil: Date | null = null;
  let daysRemaining: number | null = null;

  if (effectiveState === 'active' || effectiveState === 'canceled') {
    accessGranted = true;
    // Según BACKEND_LICENSING.md §7: `exp` nunca excede `current_period_end + gracia` (si autoRenewing)
    const graceBufferMs =
      effectiveState === 'active' && sub.autoRenewing ? SUBSCRIPTION_POLICY.GRACE_MS : 0;
    effectiveAccessUntil = new Date(periodEndMs + graceBufferMs);
    const msLeft = Math.max(0, periodEndMs - nowMs);
    daysRemaining = Math.ceil(msLeft / (24 * 60 * 60 * 1000));

    if (daysRemaining <= 1) {
      alarms.push('expires_in_1d');
      alert = 'expiring_soon';
    } else if (daysRemaining <= 3) {
      alarms.push('expires_in_3d');
      alert = 'expiring_soon';
    } else if (daysRemaining <= 7) {
      alarms.push('expires_in_7d');
      alert = 'expiring_soon';
    }
  } else if (effectiveState === 'grace') {
    const effectiveGraceDate =
      sub.graceUntil ?? new Date(periodEndMs + SUBSCRIPTION_POLICY.GRACE_MS);
    accessGranted = true;
    alert = 'payment_grace';
    effectiveAccessUntil = effectiveGraceDate;
    const msLeft = Math.max(0, effectiveGraceDate.getTime() - nowMs);
    daysRemaining = Math.ceil(msLeft / (24 * 60 * 60 * 1000));
    alarms.push('payment_failed_grace');
  } else if (effectiveState === 'on_hold') {
    accessGranted = false;
    alert = 'blocked';
    alarms.push('subscription_on_hold');
  } else if (effectiveState === 'expired') {
    accessGranted = false;
    alert = 'blocked';
    alarms.push('subscription_expired');
  } else if (effectiveState === 'refunded') {
    accessGranted = false;
    alert = 'blocked';
    alarms.push('subscription_refunded');
  }

  const paymentSemaphore: PaymentSemaphore = !accessGranted
    ? 'red'
    : effectiveState === 'grace'
      ? 'yellow'
      : computePaymentSemaphore(sub.currentPeriodEnd, sub.graceUntil, now);

  return {
    effectiveState,
    accessGranted,
    alert,
    effectiveAccessUntil,
    paymentSemaphore,
    alarms,
    daysRemaining
  };
}

/**
 * Eventos RTDN (Real-time Developer Notifications) de Google Play Billing (BACKEND_LICENSING.md §6.3).
 */
export type RtdnNotificationType =
  | 'SUBSCRIPTION_PURCHASED'
  | 'SUBSCRIPTION_RENEWED'
  | 'SUBSCRIPTION_RECOVERED'
  | 'SUBSCRIPTION_IN_GRACE_PERIOD'
  | 'SUBSCRIPTION_ON_HOLD'
  | 'SUBSCRIPTION_CANCELED'
  | 'SUBSCRIPTION_EXPIRED'
  | 'SUBSCRIPTION_REVOKED'; // Reembolso / revocación inmediata

export interface SimulatedPlayPurchaseRecord {
  purchaseToken: string;
  productId: string;
  kind: SubscriptionKind;
  state: SubscriptionState;
  currentPeriodEnd: Date;
  graceUntil: Date | null;
  autoRenewing: boolean;
}

export function isPlayP4ResultsDocumentPresent(repoRootDir: string = join(process.cwd(), '..')): boolean {
  return (
    existsSync(join(repoRootDir, 'docs', 'PLAY_P4_RESULTS.md')) ||
    existsSync(join(process.cwd(), 'docs', 'PLAY_P4_RESULTS.md'))
  );
}

/**
 * Cliente simulado determinista de Google Play Billing (`FakeBillingClient` en backend) para Fase 2
 * mientras Play Console está en standby a la espera de `docs/PLAY_P4_RESULTS.md`.
 */
export class FakePlayBillingVerifier {
  private readonly purchases = new Map<string, SimulatedPlayPurchaseRecord>();

  seedPurchase(record: SimulatedPlayPurchaseRecord): void {
    this.purchases.set(record.purchaseToken, { ...record });
  }

  verifyPurchaseToken(
    productId: string,
    purchaseToken: string,
    now: Date = new Date()
  ): SimulatedPlayPurchaseRecord {
    const existing = this.purchases.get(purchaseToken);
    if (existing) {
      return existing;
    }

    const kind: SubscriptionKind =
      productId === 'leader_monthly'
        ? 'leader'
        : /^member_slot_[1-5]$/.test(productId)
          ? 'member_slot'
          : (() => {
              throw new Error(`INVALID_PLAY_PRODUCT_ID:${productId}`);
            })();

    const defaultPeriodEnd = new Date(now.getTime() + 30 * 24 * 60 * 60 * 1000);
    const created: SimulatedPlayPurchaseRecord = {
      purchaseToken,
      productId,
      kind,
      state: 'active',
      currentPeriodEnd: defaultPeriodEnd,
      graceUntil: null,
      autoRenewing: true
    };
    this.purchases.set(purchaseToken, created);
    return created;
  }

  applyRtdnTransition(
    purchaseToken: string,
    productId: string,
    notificationType: RtdnNotificationType,
    now: Date = new Date(),
    customPeriodEnd?: Date
  ): SimulatedPlayPurchaseRecord {
    const base = this.verifyPurchaseToken(productId, purchaseToken, now);
    const periodEnd = customPeriodEnd ?? base.currentPeriodEnd;

    switch (notificationType) {
      case 'SUBSCRIPTION_PURCHASED':
      case 'SUBSCRIPTION_RENEWED':
      case 'SUBSCRIPTION_RECOVERED': {
        const nextEnd =
          customPeriodEnd ?? new Date(Math.max(now.getTime(), periodEnd.getTime()) + 30 * 24 * 60 * 60 * 1000);
        const updated: SimulatedPlayPurchaseRecord = {
          ...base,
          state: 'active',
          currentPeriodEnd: nextEnd,
          graceUntil: null,
          autoRenewing: true
        };
        this.purchases.set(purchaseToken, updated);
        return updated;
      }
      case 'SUBSCRIPTION_IN_GRACE_PERIOD': {
        const graceUntil = new Date(now.getTime() + SUBSCRIPTION_POLICY.GRACE_MS);
        const updated: SimulatedPlayPurchaseRecord = {
          ...base,
          state: 'grace',
          currentPeriodEnd: periodEnd.getTime() > now.getTime() ? now : periodEnd,
          graceUntil,
          autoRenewing: true
        };
        this.purchases.set(purchaseToken, updated);
        return updated;
      }
      case 'SUBSCRIPTION_ON_HOLD': {
        const updated: SimulatedPlayPurchaseRecord = {
          ...base,
          state: 'on_hold',
          graceUntil: null,
          autoRenewing: true
        };
        this.purchases.set(purchaseToken, updated);
        return updated;
      }
      case 'SUBSCRIPTION_CANCELED': {
        // P3: Conserva acceso hasta el fin del periodo ya pagado (`current_period_end`)
        const updated: SimulatedPlayPurchaseRecord = {
          ...base,
          state: 'canceled',
          autoRenewing: false
        };
        this.purchases.set(purchaseToken, updated);
        return updated;
      }
      case 'SUBSCRIPTION_EXPIRED': {
        const updated: SimulatedPlayPurchaseRecord = {
          ...base,
          state: 'expired',
          currentPeriodEnd: periodEnd.getTime() > now.getTime() ? now : periodEnd,
          graceUntil: null,
          autoRenewing: false
        };
        this.purchases.set(purchaseToken, updated);
        return updated;
      }
      case 'SUBSCRIPTION_REVOKED': {
        // Reembolso: revoca acceso e invalida credencial de inmediato
        const updated: SimulatedPlayPurchaseRecord = {
          ...base,
          state: 'refunded',
          currentPeriodEnd: now,
          graceUntil: null,
          autoRenewing: false
        };
        this.purchases.set(purchaseToken, updated);
        return updated;
      }
    }
  }
}

export const defaultFakePlayVerifier = new FakePlayBillingVerifier();

export interface GroupGovernanceReconciliationResult {
  groupId: string;
  previousStatus: string;
  newStatus: string;
  previousPrincipalLeader: string;
  newPrincipalLeader: string;
  promotedLeaderId: string | null;
  suspendedAllLeadersUnpaid: boolean;
  reactivated: boolean;
}

/**
 * Ejecuta las reglas R4.5 y R4.6 sobre un grupo específico o sobre todos los grupos donde participa un líder:
 * - R4.5: Si el líder principal lleva >= 7 días sin suscripción de líder con acceso efectivo (`on_hold`, `expired`, `refunded`),
 *   el rol de líder principal (`is_principal = true`, `groups.principal_leader`) pasa automáticamente al siguiente líder
 *   con suscripción activa en orden de antigüedad (`added_at ASC`).
 * - R4.6: Si los 3 líderes (o todos los líderes del grupo) caen en impago simultáneamente, el grupo pasa a
 *   `status = 'suspended'`, conservando su historial, miembros e insignias intactos. Al volver a pagar cualquiera
 *   de ellos, el grupo se reactiva (`status = 'active'`).
 */
export async function reconcileGroupLeaderSubscriptions(
  db: DbExecutor,
  groupId: string,
  now: Date = new Date()
): Promise<GroupGovernanceReconciliationResult | null> {
  const grpRes = await db.query(
    `SELECT id, principal_leader, status FROM groups WHERE id = $1 AND status <> 'deleted'`,
    [groupId]
  );
  const group = grpRes.rows[0];
  if (!group) return null;

  const leadersRes = await db.query(
    `SELECT gl.user_id, gl.is_principal, gl.added_at,
            s.state AS sub_state, s.current_period_end, s.grace_until, s.auto_renewing
     FROM group_leaders gl
     LEFT JOIN subscriptions s ON s.user_id = gl.user_id AND s.kind = 'leader'
     WHERE gl.group_id = $1
     ORDER BY gl.is_principal DESC, gl.added_at ASC`,
    [groupId]
  );

  if (leadersRes.rows.length === 0) return null;

  interface LeaderEval {
    userId: string;
    isPrincipal: boolean;
    addedAt: Date;
    hasAccess: boolean;
    unpaidDurationMs: number;
  }

  const evaluatedLeaders: LeaderEval[] = leadersRes.rows.map((r) => {
    if (!r.sub_state || !r.current_period_end) {
      return {
        userId: String(r.user_id),
        isPrincipal: Boolean(r.is_principal),
        addedAt: new Date(r.added_at),
        hasAccess: false,
        unpaidDurationMs: Number.MAX_SAFE_INTEGER
      };
    }
    const periodEnd = new Date(r.current_period_end);
    const graceUntil = r.grace_until ? new Date(r.grace_until) : null;
    const evalStatus = evaluateSubscriptionState(
      {
        state: r.sub_state as SubscriptionState,
        currentPeriodEnd: periodEnd,
        graceUntil,
        autoRenewing: Boolean(r.auto_renewing)
      },
      now
    );
    const unpaidSinceMs = graceUntil ? graceUntil.getTime() : periodEnd.getTime();
    const unpaidDurationMs = evalStatus.accessGranted
      ? 0
      : Math.max(0, now.getTime() - unpaidSinceMs);

    return {
      userId: String(r.user_id),
      isPrincipal: Boolean(r.is_principal),
      addedAt: new Date(r.added_at),
      hasAccess: evalStatus.accessGranted,
      unpaidDurationMs
    };
  });

  const activeLeaders = evaluatedLeaders
    .filter((l) => l.hasAccess)
    .sort((a, b) => a.addedAt.getTime() - b.addedAt.getTime());

  const currentPrincipal =
    evaluatedLeaders.find((l) => l.isPrincipal) ?? evaluatedLeaders[0];

  let newStatus = String(group.status);
  let newPrincipalLeader = String(group.principal_leader);
  let promotedLeaderId: string | null = null;
  let suspendedAllLeadersUnpaid = false;
  let reactivated = false;

  if (activeLeaders.length === 0) {
    // R4.6: Todos los líderes están sin pago -> suspender el grupo conservando su historial intacto
    if (group.status !== 'suspended') {
      newStatus = 'suspended';
      suspendedAllLeadersUnpaid = true;
      await db.query(`UPDATE groups SET status = 'suspended' WHERE id = $1`, [groupId]);
      await db.query(
        `INSERT INTO audit_log (actor, action, target_type, target_id, detail)
         VALUES ($1, 'GROUP_SUSPENDED_ALL_LEADERS_UNPAID', 'group', $2, $3)`,
        [
          null,
          groupId,
          JSON.stringify({
            reason: 'ALL_LEADERS_UNPAID_R4_6',
            leadersCount: evaluatedLeaders.length,
            evaluatedAt: now.toISOString()
          })
        ]
      );
    }
  } else {
    // Al menos un líder tiene suscripción activa
    if (group.status === 'suspended') {
      newStatus = 'active';
      reactivated = true;
      await db.query(`UPDATE groups SET status = 'active' WHERE id = $1`, [groupId]);
    }

    // R4.5: Si el líder principal lleva >= 7 días sin pago (o el grupo se reactiva porque otro líder pagó),
    // promover automáticamente al siguiente líder con suscripción activa.
    const principalUnpaid7Days =
      !currentPrincipal.hasAccess &&
      (currentPrincipal.unpaidDurationMs >= SUBSCRIPTION_POLICY.LEADER_UNPAID_PROMOTION_MS ||
        reactivated);

    if (principalUnpaid7Days) {
      const candidate = activeLeaders.find((l) => l.userId !== currentPrincipal.userId) ?? activeLeaders[0];
      if (candidate && candidate.userId !== currentPrincipal.userId) {
        promotedLeaderId = candidate.userId;
        newPrincipalLeader = candidate.userId;

        await db.query(`UPDATE group_leaders SET is_principal = false WHERE group_id = $1`, [
          groupId
        ]);
        await db.query(
          `UPDATE group_leaders SET is_principal = true WHERE group_id = $1 AND user_id = $2`,
          [groupId, candidate.userId]
        );
        await db.query(`UPDATE groups SET principal_leader = $1 WHERE id = $2`, [
          candidate.userId,
          groupId
        ]);
        await db.query(
          `INSERT INTO audit_log (actor, action, target_type, target_id, detail)
           VALUES ($1, 'PRINCIPAL_LEADER_AUTO_PROMOTED', 'group', $2, $3)`,
          [
            candidate.userId,
            groupId,
            JSON.stringify({
              previousPrincipalLeader: currentPrincipal.userId,
              newPrincipalLeader: candidate.userId,
              unpaidDurationMs: currentPrincipal.unpaidDurationMs,
              rule: 'R4.5'
            })
          ]
        );
      }
    }

    if (reactivated) {
      await db.query(
        `INSERT INTO audit_log (actor, action, target_type, target_id, detail)
         VALUES ($1, 'GROUP_REACTIVATED_LEADER_PAID', 'group', $2, $3)`,
        [
          newPrincipalLeader,
          groupId,
          JSON.stringify({
            principalLeader: newPrincipalLeader,
            rule: 'R4.6'
          })
        ]
      );
    }
  }

  return {
    groupId,
    previousStatus: String(group.status),
    newStatus,
    previousPrincipalLeader: String(group.principal_leader),
    newPrincipalLeader,
    promotedLeaderId,
    suspendedAllLeadersUnpaid,
    reactivated
  };
}
