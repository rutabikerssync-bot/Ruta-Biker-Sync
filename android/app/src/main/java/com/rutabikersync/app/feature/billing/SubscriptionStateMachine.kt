package com.rutabikersync.app.feature.billing

import com.rutabikersync.app.core.util.BillingPolicyConfig
import com.rutabikersync.app.feature.groups.PaymentSemaphore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/**
 * Máquina de 6 estados de suscripción (BACKEND_LICENSING.md §5 y RULES R4.4, R4.5, R4.6, R7.1, R7.2, R7.3)
 * y cliente simulado determinista `FakeBillingClient` mientras Play Console está en standby
 * a la espera de `docs/PLAY_P4_RESULTS.md`.
 */
enum class SubscriptionState(val apiValue: String) {
    ACTIVE("active"),
    GRACE("grace"),
    ON_HOLD("on_hold"),
    CANCELED("canceled"),
    EXPIRED("expired"),
    REFUNDED("refunded");

    companion object {
        fun fromApiValue(raw: String): SubscriptionState =
            entries.firstOrNull { it.apiValue.equals(raw, ignoreCase = true) } ?: EXPIRED
    }
}

enum class SubscriptionKind(val apiValue: String) {
    LEADER("leader"),
    MEMBER_SLOT("member_slot")
}

enum class SubscriptionAlarm {
    EXPIRES_IN_7_DAYS,
    EXPIRES_IN_3_DAYS,
    EXPIRES_IN_1_DAY,
    PAYMENT_FAILED_IN_GRACE,
    SUBSCRIPTION_ON_HOLD,
    SUBSCRIPTION_EXPIRED,
    SUBSCRIPTION_REFUNDED
}

data class SubscriptionRecord(
    val id: String,
    val userId: String,
    val kind: SubscriptionKind,
    val productId: String,
    val purchaseToken: String,
    val state: SubscriptionState,
    val currentPeriodEndEpochMs: Long,
    val graceUntilEpochMs: Long? = null,
    val autoRenewing: Boolean = true,
    val assignedGroupId: String? = null
)

data class EvaluatedSubscription(
    val effectiveState: SubscriptionState,
    val accessGranted: Boolean,
    val effectiveAccessUntilEpochMs: Long?,
    val paymentSemaphore: PaymentSemaphore,
    val alarms: List<SubscriptionAlarm>,
    val daysRemaining: Int?
)

object SubscriptionStateMachine {
    private const val DAY_MS = 24L * 60L * 60L * 1000L
    val GRACE_PERIOD_MS: Long = BillingPolicyConfig.DEFAULT_PAYMENT_GRACE_DAYS * DAY_MS
    const val ON_HOLD_MAX_MS: Long = 30L * DAY_MS
    const val LEADER_UNPAID_PROMOTION_MS: Long = 7L * DAY_MS

    /**
     * Evalúa el estado efectivo, acceso a rodadas, semáforo de pagos (nunca expone montos ni medios de pago, R7.2)
     * y alarmas locales de vencimiento (7, 3 y 1 día antes, BACKEND_LICENSING.md §9).
     */
    fun evaluate(
        record: SubscriptionRecord,
        nowEpochMs: Long = System.currentTimeMillis()
    ): EvaluatedSubscription {
        var effectiveState = record.state
        val periodEnd = record.currentPeriodEndEpochMs
        val graceEnd = record.graceUntilEpochMs

        if (effectiveState == SubscriptionState.ACTIVE && nowEpochMs >= periodEnd) {
            effectiveState = when {
                graceEnd != null && nowEpochMs < graceEnd -> SubscriptionState.GRACE
                record.autoRenewing -> SubscriptionState.ON_HOLD
                else -> SubscriptionState.EXPIRED
            }
        } else if (effectiveState == SubscriptionState.GRACE) {
            val effectiveGraceEnd = graceEnd ?: (periodEnd + GRACE_PERIOD_MS)
            if (nowEpochMs >= effectiveGraceEnd) {
                effectiveState = if (nowEpochMs >= effectiveGraceEnd + ON_HOLD_MAX_MS) {
                    SubscriptionState.EXPIRED
                } else {
                    SubscriptionState.ON_HOLD
                }
            }
        } else if (effectiveState == SubscriptionState.CANCELED && nowEpochMs >= periodEnd) {
            effectiveState = SubscriptionState.EXPIRED
        } else if (effectiveState == SubscriptionState.ON_HOLD) {
            val holdStart = graceEnd ?: (periodEnd + GRACE_PERIOD_MS)
            if (nowEpochMs >= holdStart + ON_HOLD_MAX_MS) {
                effectiveState = SubscriptionState.EXPIRED
            }
        }

        val alarms = mutableListOf<SubscriptionAlarm>()
        val accessGranted: Boolean
        val effectiveAccessUntil: Long?
        val daysRemaining: Int?

        when (effectiveState) {
            SubscriptionState.ACTIVE, SubscriptionState.CANCELED -> {
                accessGranted = true
                val graceBuffer =
                    if (effectiveState == SubscriptionState.ACTIVE && record.autoRenewing) GRACE_PERIOD_MS else 0L
                effectiveAccessUntil = periodEnd + graceBuffer
                val msLeft = (periodEnd - nowEpochMs).coerceAtLeast(0L)
                daysRemaining = kotlin.math.ceil(msLeft.toDouble() / DAY_MS.toDouble()).toInt()
                when {
                    daysRemaining <= 1 -> alarms.add(SubscriptionAlarm.EXPIRES_IN_1_DAY)
                    daysRemaining <= 3 -> alarms.add(SubscriptionAlarm.EXPIRES_IN_3_DAYS)
                    daysRemaining <= 7 -> alarms.add(SubscriptionAlarm.EXPIRES_IN_7_DAYS)
                }
            }
            SubscriptionState.GRACE -> {
                val effectiveGraceEnd = graceEnd ?: (periodEnd + GRACE_PERIOD_MS)
                accessGranted = true
                effectiveAccessUntil = effectiveGraceEnd
                val msLeft = (effectiveGraceEnd - nowEpochMs).coerceAtLeast(0L)
                daysRemaining = kotlin.math.ceil(msLeft.toDouble() / DAY_MS.toDouble()).toInt()
                alarms.add(SubscriptionAlarm.PAYMENT_FAILED_IN_GRACE)
            }
            SubscriptionState.ON_HOLD -> {
                accessGranted = false
                effectiveAccessUntil = null
                daysRemaining = null
                alarms.add(SubscriptionAlarm.SUBSCRIPTION_ON_HOLD)
            }
            SubscriptionState.EXPIRED -> {
                accessGranted = false
                effectiveAccessUntil = null
                daysRemaining = null
                alarms.add(SubscriptionAlarm.SUBSCRIPTION_EXPIRED)
            }
            SubscriptionState.REFUNDED -> {
                accessGranted = false
                effectiveAccessUntil = null
                daysRemaining = null
                alarms.add(SubscriptionAlarm.SUBSCRIPTION_REFUNDED)
            }
        }

        val semaphore = when {
            !accessGranted -> PaymentSemaphore.RED
            effectiveState == SubscriptionState.GRACE -> PaymentSemaphore.YELLOW
            (daysRemaining ?: 0) < 7 -> PaymentSemaphore.YELLOW
            else -> PaymentSemaphore.GREEN
        }

        return EvaluatedSubscription(
            effectiveState = effectiveState,
            accessGranted = accessGranted,
            effectiveAccessUntilEpochMs = effectiveAccessUntil,
            paymentSemaphore = semaphore,
            alarms = alarms,
            daysRemaining = daysRemaining
        )
    }
}

/**
 * Cliente simulado determinista (`FakeBillingClient`) para Fase 2 mientras no exista `docs/PLAY_P4_RESULTS.md`.
 */
class FakeBillingClient {
    private val _subscriptions = MutableStateFlow<Map<String, SubscriptionRecord>>(emptyMap())
    val subscriptions: StateFlow<Map<String, SubscriptionRecord>> = _subscriptions.asStateFlow()

    fun purchaseSubscription(
        userId: String,
        productId: String,
        assignedGroupId: String? = null,
        nowEpochMs: Long = System.currentTimeMillis()
    ): Result<SubscriptionRecord> {
        val kind = when {
            productId == BillingPolicyConfig.PRODUCT_LEADER_MONTHLY -> SubscriptionKind.LEADER
            productId.matches(Regex("^member_slot_[1-5]$")) -> SubscriptionKind.MEMBER_SLOT
            else -> return Result.failure(IllegalArgumentException("INVALID_PLAY_PRODUCT_ID:$productId"))
        }
        val token = "fake-purchase-${productId}-${UUID.randomUUID().toString().take(8)}"
        val record = SubscriptionRecord(
            id = "sub-${UUID.randomUUID().toString().take(8)}",
            userId = userId,
            kind = kind,
            productId = productId,
            purchaseToken = token,
            state = SubscriptionState.ACTIVE,
            currentPeriodEndEpochMs = nowEpochMs + 30L * 24L * 60L * 60L * 1000L,
            graceUntilEpochMs = null,
            autoRenewing = true,
            assignedGroupId = assignedGroupId
        )
        _subscriptions.value = _subscriptions.value + (token to record)
        return Result.success(record)
    }

    fun transitionState(
        purchaseToken: String,
        nextState: SubscriptionState,
        nowEpochMs: Long = System.currentTimeMillis()
    ): Result<SubscriptionRecord> {
        val existing = _subscriptions.value[purchaseToken]
            ?: return Result.failure(IllegalArgumentException("PURCHASE_TOKEN_NOT_FOUND"))

        val updated = when (nextState) {
            SubscriptionState.ACTIVE -> existing.copy(
                state = SubscriptionState.ACTIVE,
                currentPeriodEndEpochMs = nowEpochMs + 30L * 24L * 60L * 60L * 1000L,
                graceUntilEpochMs = null,
                autoRenewing = true
            )
            SubscriptionState.GRACE -> existing.copy(
                state = SubscriptionState.GRACE,
                currentPeriodEndEpochMs = nowEpochMs,
                graceUntilEpochMs = nowEpochMs + SubscriptionStateMachine.GRACE_PERIOD_MS,
                autoRenewing = true
            )
            SubscriptionState.ON_HOLD -> existing.copy(
                state = SubscriptionState.ON_HOLD,
                graceUntilEpochMs = null,
                autoRenewing = true
            )
            SubscriptionState.CANCELED -> existing.copy(
                state = SubscriptionState.CANCELED,
                autoRenewing = false
            )
            SubscriptionState.EXPIRED -> existing.copy(
                state = SubscriptionState.EXPIRED,
                currentPeriodEndEpochMs = nowEpochMs,
                graceUntilEpochMs = null,
                autoRenewing = false
            )
            SubscriptionState.REFUNDED -> existing.copy(
                state = SubscriptionState.REFUNDED,
                currentPeriodEndEpochMs = nowEpochMs,
                graceUntilEpochMs = null,
                autoRenewing = false
            )
        }
        _subscriptions.value = _subscriptions.value + (purchaseToken to updated)
        return Result.success(updated)
    }
}
