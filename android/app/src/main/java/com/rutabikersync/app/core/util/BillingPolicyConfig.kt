package com.rutabikersync.app.core.util

/**
 * Parametrización centralizada de los puntos abiertos de negocio de RULES.md (Sección 12: P1..P4)
 * y de las tarifas base de BUSINESS_MODEL.md y BACKEND_LICENSING.md.
 *
 * IMPORTANTE: No se dan por cerrados definitivamente; cualquier cambio de criterio del dueño
 * se refleja cambiando estos flags sin reescribir la lógica de dominio.
 */
object BillingPolicyConfig {
    const val LEADER_MONTHLY_USD: Double = 15.00
    const val MEMBER_SLOT_MONTHLY_USD: Double = 2.50
    const val MAX_LEADERS_PER_GROUP: Int = 3
    const val DEFAULT_PAYMENT_GRACE_DAYS: Int = 3
    const val CREDENTIAL_TTL_DAYS: Int = 7
    const val MAX_NUMBERED_MEMBER_SLOTS: Int = 5

    /**
     * P1 (RULES.md §12): ¿Un líder pagando solo $15 es invitado gratis en otros grupos?
     * Propuesta actual: paga $2.50 como invitado para evitar evasión (true).
     */
    var p1LeaderPaysGuestInOtherGroups: Boolean = true

    /**
     * P2 (RULES.md §12): ¿Un líder de varios grupos paga $15 una vez o por grupo?
     * Propuesta actual: $15 por persona una sola vez (true).
     */
    var p2LeaderSubscriptionPerPerson: Boolean = true

    /**
     * P3 (RULES.md §12): Retiro o reembolso a mitad de mes.
     * Propuesta actual: acceso hasta el fin del periodo pagado (true).
     */
    var p3AccessUntilPeriodEndOnLeave: Boolean = true

    /**
     * P4 (RULES.md §12 / BACKEND_LICENSING.md §3): Estrategia de cobro de varios cupos en Google Play.
     * Propuesta actual: productos de cupo numerados (member_slot_1 .. member_slot_5),
     * con Plan B de pase por rodada (RIDE_PASS) si Play rechaza o limita cupos múltiples.
     */
    var p4MultiSlotStrategy: MultiSlotStrategy = MultiSlotStrategy.NUMBERED_SLOTS

    enum class MultiSlotStrategy {
        NUMBERED_SLOTS,
        SUBSCRIPTION_ADDONS,
        TIERED_QUANTITY,
        RIDE_PASS_FALLBACK
    }

    const val PRODUCT_LEADER_MONTHLY: String = "leader_monthly"

    fun memberSlotProductId(slotIndex: Int): String {
        require(slotIndex in 1..MAX_NUMBERED_MEMBER_SLOTS) {
            "Slot index must be between 1 and $MAX_NUMBERED_MEMBER_SLOTS"
        }
        return "member_slot_$slotIndex"
    }

    /**
     * Calcula la cuota mensual total en USD para un usuario según RULES.md:
     * - R4.4: El líder paga $15/mes y NO paga $2.50 en su propio grupo.
     * - P1: Si es líder pero entra como invitado a otros grupos, paga $2.50 por cada grupo ajeno si p1LeaderPaysGuestInOtherGroups = true.
     * - P2: Si lidera varios grupos, paga $15 una sola vez si p2LeaderSubscriptionPerPerson = true, o $15 por grupo liderado si es false.
     * - R2.2: Miembro (full/prospecto, máx 1 grupo) o invitado paga $2.50 por cada grupo donde no es líder.
     */
    fun calculateMonthlyUserTotalUsd(
        ledGroupsCount: Int,
        memberOrProspectGroupsCount: Int,
        guestGroupsCount: Int
    ): Double {
        require(ledGroupsCount >= 0 && memberOrProspectGroupsCount >= 0 && guestGroupsCount >= 0)
        require(memberOrProspectGroupsCount <= 1) {
            "R2: Un Full member o Prospecto solo puede pertenecer a un grupo con ese estatus."
        }

        val isLeader = ledGroupsCount > 0
        val leaderCost = when {
            !isLeader -> 0.0
            p2LeaderSubscriptionPerPerson -> LEADER_MONTHLY_USD
            else -> ledGroupsCount * LEADER_MONTHLY_USD
        }

        val memberSlotCost = memberOrProspectGroupsCount * MEMBER_SLOT_MONTHLY_USD
        val guestSlotCost = when {
            guestGroupsCount == 0 -> 0.0
            isLeader && !p1LeaderPaysGuestInOtherGroups -> 0.0
            else -> guestGroupsCount * MEMBER_SLOT_MONTHLY_USD
        }

        return leaderCost + memberSlotCost + guestSlotCost
    }
}
