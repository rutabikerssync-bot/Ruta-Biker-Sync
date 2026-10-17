package com.rutabikersync.app.core.util

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class BillingPolicyConfigTest {

    @After
    fun resetDefaults() {
        BillingPolicyConfig.p1LeaderPaysGuestInOtherGroups = true
        BillingPolicyConfig.p2LeaderSubscriptionPerPerson = true
        BillingPolicyConfig.p3AccessUntilPeriodEndOnLeave = true
        BillingPolicyConfig.p4MultiSlotStrategy = BillingPolicyConfig.MultiSlotStrategy.NUMBERED_SLOTS
    }

    @Test
    fun `business model club revenue examples match RULES R1 R2 R4`() {
        // BUSINESS_MODEL.md §2:
        // 1 líder ($15, no paga $2.50 en su grupo según R4.4) + 10 miembros ($2.50 c/u) = $40.00
        val leaderCost = BillingPolicyConfig.calculateMonthlyUserTotalUsd(
            ledGroupsCount = 1,
            memberOrProspectGroupsCount = 0,
            guestGroupsCount = 0
        )
        val singleMemberCost = BillingPolicyConfig.calculateMonthlyUserTotalUsd(
            ledGroupsCount = 0,
            memberOrProspectGroupsCount = 1,
            guestGroupsCount = 0
        )
        assertEquals(15.00, leaderCost, 0.001)
        assertEquals(2.50, singleMemberCost, 0.001)
        assertEquals(40.00, leaderCost + 10 * singleMemberCost, 0.001)

        // 2 líderes + 20 miembros = $80.00
        assertEquals(80.00, (2 * leaderCost) + (20 * singleMemberCost), 0.001)

        // 3 líderes + 40 miembros + 5 invitados = $157.50
        val singleGuestCost = BillingPolicyConfig.calculateMonthlyUserTotalUsd(
            ledGroupsCount = 0,
            memberOrProspectGroupsCount = 0,
            guestGroupsCount = 1
        )
        assertEquals(157.50, (3 * leaderCost) + (40 * singleMemberCost) + (5 * singleGuestCost), 0.001)

        // Un invitado en 3 grupos genera $7.50/mes (R2.2)
        val guestInThreeGroups = BillingPolicyConfig.calculateMonthlyUserTotalUsd(
            ledGroupsCount = 0,
            memberOrProspectGroupsCount = 0,
            guestGroupsCount = 3
        )
        assertEquals(7.50, guestInThreeGroups, 0.001)
    }

    @Test
    fun `open business rules P1 and P2 switch behavior cleanly via config flags`() {
        // P1 por defecto (true): un líder que entra como invitado a 2 grupos ajenos paga $15 + 2 * $2.50 = $20.00
        BillingPolicyConfig.p1LeaderPaysGuestInOtherGroups = true
        assertEquals(
            20.00,
            BillingPolicyConfig.calculateMonthlyUserTotalUsd(
                ledGroupsCount = 1,
                memberOrProspectGroupsCount = 0,
                guestGroupsCount = 2
            ),
            0.001
        )

        // Si el dueño decide P1 = false (líder invitado gratis en otros grupos): paga solo $15.00
        BillingPolicyConfig.p1LeaderPaysGuestInOtherGroups = false
        assertEquals(
            15.00,
            BillingPolicyConfig.calculateMonthlyUserTotalUsd(
                ledGroupsCount = 1,
                memberOrProspectGroupsCount = 0,
                guestGroupsCount = 2
            ),
            0.001
        )

        // P2 por defecto (true): líder de 2 grupos paga $15 una sola vez por persona
        BillingPolicyConfig.p2LeaderSubscriptionPerPerson = true
        assertEquals(
            15.00,
            BillingPolicyConfig.calculateMonthlyUserTotalUsd(
                ledGroupsCount = 2,
                memberOrProspectGroupsCount = 0,
                guestGroupsCount = 0
            ),
            0.001
        )

        // Si P2 = false (pago por cada grupo liderado): 2 * $15 = $30.00
        BillingPolicyConfig.p2LeaderSubscriptionPerPerson = false
        assertEquals(
            30.00,
            BillingPolicyConfig.calculateMonthlyUserTotalUsd(
                ledGroupsCount = 2,
                memberOrProspectGroupsCount = 0,
                guestGroupsCount = 0
            ),
            0.001
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `full member or prospect cannot belong to more than 1 group`() {
        BillingPolicyConfig.calculateMonthlyUserTotalUsd(
            ledGroupsCount = 0,
            memberOrProspectGroupsCount = 2,
            guestGroupsCount = 0
        )
    }

    @Test
    fun `D40 KeystoreEncryptedTokenStore encrypts refreshToken with AES256-GCM and never stores plaintext`() {
        val store = com.rutabikersync.app.core.network.KeystoreEncryptedTokenStore(context = null)
        val rawToken = "eyJ0eXAiOiJyZWZyZXNoIiwia2lkIjoidjEifQ.secret_signature_payload"
        store.saveRefreshToken(rawToken)

        val storedCiphertext = store.rawStoredCiphertextForAudit()
        org.junit.Assert.assertNotNull(storedCiphertext)
        org.junit.Assert.assertNotEquals(rawToken, storedCiphertext)
        org.junit.Assert.assertFalse(storedCiphertext!!.contains("secret_signature_payload"))
        assertEquals(rawToken, store.loadRefreshToken())

        store.clear()
        org.junit.Assert.assertNull(store.loadRefreshToken())
    }

    @Test
    fun `D41 Play Billing 8_1_plus and 9_1_0 exposes includeSuspendedSubscriptions and Purchase isSuspended`() {
        val paramsBuilderClass = com.android.billingclient.api.QueryPurchasesParams.Builder::class.java
        val includeSuspendedMethod = paramsBuilderClass.methods.firstOrNull {
            it.name == "includeSuspendedSubscriptions"
        }
        org.junit.Assert.assertNotNull(
            "QueryPurchasesParams.Builder debe incluir includeSuspendedSubscriptions(boolean) (8.1.0+)",
            includeSuspendedMethod
        )

        val purchaseClass = com.android.billingclient.api.Purchase::class.java
        val isSuspendedMethod = purchaseClass.methods.firstOrNull {
            it.name == "isSuspended"
        }
        org.junit.Assert.assertNotNull(
            "Purchase debe incluir isSuspended() para detectar on_hold (8.1.0+)",
            isSuspendedMethod
        )
    }
}
