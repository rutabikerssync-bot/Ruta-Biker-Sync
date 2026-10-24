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
    fun `D40 KeystoreEncryptedTokenStore encrypts refreshToken with AES256-GCM and recovers gracefully on key loss`() {
        var reloginTriggered = false
        val store = com.rutabikersync.app.core.network.KeystoreEncryptedTokenStore(
            context = null,
            onKeyInvalidated = { reloginTriggered = true }
        )
        // En pruebas unitarias JVM, AndroidKeyStore del hardware no está montado (`isUsingHardwareAndroidKeystore == false`)
        org.junit.Assert.assertFalse(store.isUsingHardwareAndroidKeystore)

        val rawToken = "eyJ0eXAiOiJyZWZyZXNoIiwia2lkIjoidjEifQ.secret_signature_payload"
        store.saveRefreshToken(rawToken)

        val storedCiphertext = store.rawStoredCiphertextForAudit()
        org.junit.Assert.assertNotNull(storedCiphertext)
        org.junit.Assert.assertNotEquals(rawToken, storedCiphertext)
        org.junit.Assert.assertFalse(storedCiphertext!!.contains("secret_signature_payload"))
        assertEquals(rawToken, store.loadRefreshToken())
        org.junit.Assert.assertFalse(store.requiresRelogin)

        // Simular pérdida o invalidación de la clave del Keystore (p. ej. restauración en otro teléfono o cambio de bloqueo):
        // no debe cerrar la app (sin excepción), debe limpiar el ciphertext ilegible y activar `requiresRelogin = true`
        store.simulateKeyLossOrInvalidationForTest()
        org.junit.Assert.assertNull(store.loadRefreshToken())
        org.junit.Assert.assertTrue(store.requiresRelogin)
        org.junit.Assert.assertTrue(reloginTriggered)
        org.junit.Assert.assertNull(store.rawStoredCiphertextForAudit())
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

    @Test
    fun `Fase 2 SubscriptionStateMachine 6 states FakeBillingClient and 7-3-1 day alarms`() {
        val fakeBilling = com.rutabikersync.app.feature.billing.FakeBillingClient()
        val now = 1_760_000_000_000L
        val dayMs = 24L * 60L * 60L * 1000L

        val purchase = fakeBilling.purchaseSubscription(
            userId = "u-rider-1",
            productId = "member_slot_1",
            assignedGroupId = "grp-1",
            nowEpochMs = now
        ).getOrThrow()

        // 1. ACTIVE recién comprado -> acceso concedido, semáforo GREEN
        val evalActive = com.rutabikersync.app.feature.billing.SubscriptionStateMachine.evaluate(purchase, now)
        assertEquals(com.rutabikersync.app.feature.billing.SubscriptionState.ACTIVE, evalActive.effectiveState)
        org.junit.Assert.assertTrue(evalActive.accessGranted)
        assertEquals(com.rutabikersync.app.feature.groups.PaymentSemaphore.GREEN, evalActive.paymentSemaphore)

        // 2. A 3 días de vencer -> alarma EXPIRES_IN_3_DAYS y semáforo YELLOW
        val eval3DaysLeft = com.rutabikersync.app.feature.billing.SubscriptionStateMachine.evaluate(
            purchase,
            now + 27L * dayMs
        )
        org.junit.Assert.assertTrue(
            eval3DaysLeft.alarms.contains(com.rutabikersync.app.feature.billing.SubscriptionAlarm.EXPIRES_IN_3_DAYS)
        )
        assertEquals(com.rutabikersync.app.feature.groups.PaymentSemaphore.YELLOW, eval3DaysLeft.paymentSemaphore)

        // 3. Fallo de pago -> GRACE (3 días según RULES R7.3): conserva acceso con alerta y semáforo YELLOW
        val inGrace = fakeBilling.transitionState(
            purchase.purchaseToken,
            com.rutabikersync.app.feature.billing.SubscriptionState.GRACE,
            nowEpochMs = now
        ).getOrThrow()
        val evalGrace = com.rutabikersync.app.feature.billing.SubscriptionStateMachine.evaluate(inGrace, now + dayMs)
        assertEquals(com.rutabikersync.app.feature.billing.SubscriptionState.GRACE, evalGrace.effectiveState)
        org.junit.Assert.assertTrue(evalGrace.accessGranted)
        assertEquals(com.rutabikersync.app.feature.groups.PaymentSemaphore.YELLOW, evalGrace.paymentSemaphore)

        // 4. Al vencer los 3 días de gracia sin pago -> pasa automáticamente a ON_HOLD, pierde acceso y semáforo RED
        val evalOnHold = com.rutabikersync.app.feature.billing.SubscriptionStateMachine.evaluate(
            inGrace,
            now + 4L * dayMs
        )
        assertEquals(com.rutabikersync.app.feature.billing.SubscriptionState.ON_HOLD, evalOnHold.effectiveState)
        org.junit.Assert.assertFalse(evalOnHold.accessGranted)
        assertEquals(com.rutabikersync.app.feature.groups.PaymentSemaphore.RED, evalOnHold.paymentSemaphore)

        // 5. Reembolso (REFUNDED) -> revoca acceso inmediatamente
        val refunded = fakeBilling.transitionState(
            purchase.purchaseToken,
            com.rutabikersync.app.feature.billing.SubscriptionState.REFUNDED,
            nowEpochMs = now
        ).getOrThrow()
        val evalRefunded = com.rutabikersync.app.feature.billing.SubscriptionStateMachine.evaluate(refunded, now)
        assertEquals(com.rutabikersync.app.feature.billing.SubscriptionState.REFUNDED, evalRefunded.effectiveState)
        org.junit.Assert.assertFalse(evalRefunded.accessGranted)
        assertEquals(com.rutabikersync.app.feature.groups.PaymentSemaphore.RED, evalRefunded.paymentSemaphore)
    }

    @Test
    fun `Fase 2 OfflineCredentialVerifier Ed25519 signature kid rotation exp cap and revocation list`() {
        val keyring = com.rutabikersync.app.core.crypto.AndroidCredentialKeyring("ed25519-k1")
        val nowSec = 1_760_000_000L
        // Usuario en gracia con solo 2 días restantes (< 7 días TTL máximo)
        val twoDaysLaterSec = nowSec + 2L * 24L * 60L * 60L

        val credK1 = com.rutabikersync.app.core.crypto.OfflineCredentialVerifier.issueCredential(
            uid = "u-rider-1",
            gid = "grp-halcones",
            kind = "full",
            devicePkBase64 = "pk-device-1",
            effectiveAccessUntilEpochSec = twoDaysLaterSec,
            nowEpochSec = nowSec,
            keyring = keyring
        )

        // Rotar clave a ed25519-k2 y emitir segunda credencial
        keyring.generateAndRegisterKeyPair("ed25519-k2")
        val credK2 = com.rutabikersync.app.core.crypto.OfflineCredentialVerifier.issueCredential(
            uid = "u-leader-1",
            gid = "grp-halcones",
            kind = "leader",
            devicePkBase64 = "pk-leader-1",
            effectiveAccessUntilEpochSec = nowSec + 30L * 24L * 60L * 60L,
            nowEpochSec = nowSec,
            keyring = keyring
        )

        // Ambas credenciales (k1 anterior y k2 nueva) son válidas offline con el llavero rotado
        val verifyK1 = com.rutabikersync.app.core.crypto.OfflineCredentialVerifier.verifyCredential(
            token = credK1,
            keyring = keyring,
            nowEpochSec = nowSec + 3600L,
            expectedGroupIds = setOf("grp-halcones"),
            expectedDevicePkBase64 = "pk-device-1"
        )
        org.junit.Assert.assertTrue(verifyK1.valid)
        assertEquals(twoDaysLaterSec, verifyK1.payload!!.exp)

        val verifyK2 = com.rutabikersync.app.core.crypto.OfflineCredentialVerifier.verifyCredential(
            token = credK2,
            keyring = keyring,
            nowEpochSec = nowSec + 3600L,
            expectedGroupIds = setOf("grp-halcones"),
            expectedDevicePkBase64 = "pk-leader-1"
        )
        org.junit.Assert.assertTrue(verifyK2.valid)
        // La credencial del líder con 30 días de vigencia queda acotada a 7 días (TTL máximo)
        assertEquals(nowSec + 7L * 24L * 60L * 60L, verifyK2.payload!!.exp)

        // Si el usuario aparece en la lista de revocación sincronizada antes de salir a rodar, es rechazado
        val verifyRevoked = com.rutabikersync.app.core.crypto.OfflineCredentialVerifier.verifyCredential(
            token = credK1,
            keyring = keyring,
            nowEpochSec = nowSec + 3600L,
            expectedGroupIds = setOf("grp-halcones"),
            revokedUserIds = setOf("u-rider-1")
        )
        org.junit.Assert.assertFalse(verifyRevoked.valid)
        assertEquals(
            com.rutabikersync.app.core.crypto.CredentialRejectionReason.REVOKED_USER_OR_CREDENTIAL,
            verifyRevoked.rejectionReason
        )
    }

    @Test
    fun `Fase 3 Bloque 3_1 RideManifest Ed25519 two groups joint ride and closed participant list`() {
        val keyring = com.rutabikersync.app.core.crypto.AndroidCredentialKeyring("ed25519-f3-k1")
        val nowSec = 1_760_100_000L

        val manifestToken = com.rutabikersync.app.core.crypto.OfflineCredentialVerifier.issueRideManifest(
            rideId = "ride-joint-01",
            hostGroupId = "grp-aguilas",
            cohostGroupId = "grp-lobos",
            hostLeaderId = "u-leader-aguilas",
            cohostLeaderId = "u-leader-lobos",
            routeHash = "sha256:demo-route",
            participants = listOf(
                com.rutabikersync.app.core.crypto.RideManifestParticipantPayload(
                    uid = "u-leader-aguilas",
                    gid = "grp-aguilas",
                    statusKind = "leader",
                    rideRole = "capitan_rodada",
                    pk = "pk-aguilas-1"
                ),
                com.rutabikersync.app.core.crypto.RideManifestParticipantPayload(
                    uid = "u-member-lobos",
                    gid = "grp-lobos",
                    statusKind = "full",
                    rideRole = "sweep",
                    pk = "pk-lobos-1"
                )
            ),
            nowEpochSec = nowSec,
            keyring = keyring
        )

        val verifyOk = com.rutabikersync.app.core.crypto.OfflineCredentialVerifier.verifyRideManifest(
            manifestToken = manifestToken,
            keyring = keyring,
            nowEpochSec = nowSec + 60L,
            requireParticipantUid = "u-member-lobos"
        )
        org.junit.Assert.assertTrue(verifyOk.valid)
        assertEquals(listOf("grp-aguilas", "grp-lobos"), verifyOk.payload!!.groupIds)

        val verifyUnauthorized = com.rutabikersync.app.core.crypto.OfflineCredentialVerifier.verifyRideManifest(
            manifestToken = manifestToken,
            keyring = keyring,
            nowEpochSec = nowSec + 60L,
            requireParticipantUid = "u-intruder"
        )
        org.junit.Assert.assertFalse(verifyUnauthorized.valid)
        assertEquals("PARTICIPANT_NOT_AUTHORIZED", verifyUnauthorized.rejectionReason)
    }
}
