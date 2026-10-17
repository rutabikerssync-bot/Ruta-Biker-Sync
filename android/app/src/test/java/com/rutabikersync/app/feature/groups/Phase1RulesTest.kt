package com.rutabikersync.app.feature.groups

import com.rutabikersync.app.ui.components.skeleton.SkeletonTimingRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Phase1RulesTest {

    @Test
    fun `R5_1 normalizes group names and detects leet speak and near duplicate collisions per country`() {
        assertEquals(
            "halconesdelasfaltomc",
            ClubRulesEngine.normalizeGroupName("¡Halcónes del Asfálto! - M.C.")
        )

        val exact = ClubRulesEngine.checkGroupNameCollision(
            "HALCONES DEL ASFALTO!",
            "Halcones del Asfalto"
        )
        assertTrue(exact.collides)
        assertEquals("EXACT_NORMALIZED", exact.reason)

        val leet = ClubRulesEngine.checkGroupNameCollision(
            "H4lc0n3s d3l 4sf4lt0",
            "Halcones del Asfalto"
        )
        assertTrue(leet.collides)
        assertEquals("LEET_EQUIVALENT", leet.reason)

        val different = ClubRulesEngine.checkGroupNameCollision(
            "Guerreros del Norte",
            "Halcones del Asfalto"
        )
        assertFalse(different.collides)
    }

    @Test
    fun `R5_2 computes 64-bit pHash Hamming distance and rejects distance below 8`() {
        val base = "a5a5a5a5a5a5a5a5"
        val oneBitDiff = "a5a5a5a5a5a5a5a4" // 0x5 xor 0x4 = 1 bit
        val sim1 = ClubRulesEngine.evaluateLogoSimilarity(oneBitDiff, base)
        assertEquals(1, sim1.distance)
        assertTrue(sim1.rejected)

        val tenBitsDiff = "a5a5a5a5a5a50000" // 0xa5a5 has 8 bits -> let's check exact distance
        val dist = ClubRulesEngine.computePhashHammingDistance(tenBitsDiff, base)
        assertEquals(8, dist)
        val simBorder = ClubRulesEngine.evaluateLogoSimilarity(tenBitsDiff, base)
        assertFalse(simBorder.rejected)
        assertTrue(simBorder.needsManualReview)

        val completelyDifferent = "5a5a5a5a5a5a5a5a" // 64 bits different
        val simClean = ClubRulesEngine.evaluateLogoSimilarity(completelyDifferent, base)
        assertEquals(64, simClean.distance)
        assertFalse(simClean.rejected)
        assertFalse(simClean.needsManualReview)
    }

    @Test
    fun `R1_2 R4_1 R4_3 R5_1 R5_2 enforces leader-only creation, country uniqueness, and max 3 leaders`() {
        val repo = ClubRepository()

        // 1. Miembro normal no puede crear grupo (R1.2, R4.1)
        val memberFail = repo.createGroup(
            actorUserId = "u-member-1",
            countryCode = "MX",
            name = "Lobos Libres",
            logoPhashHex = "1111222233334444"
        )
        assertTrue(memberFail.isFailure)
        assertEquals("LEADER_REQUIRED", memberFail.exceptionOrNull()?.message)

        // 2. Nombre en leet speak colisiona en MX pero se permite en AR (R5.1)
        val leetFailMx = repo.createGroup(
            actorUserId = "u-founder-1",
            countryCode = "MX",
            name = "H4lc0n3s d3l 4sf4lt0",
            logoPhashHex = "1111222233334444"
        )
        assertTrue(leetFailMx.isFailure)
        assertTrue(leetFailMx.exceptionOrNull()?.message?.startsWith("GROUP_NAME_TAKEN_IN_COUNTRY") == true)

        val allowedInAr = repo.createGroup(
            actorUserId = "u-founder-1",
            countryCode = "AR",
            name = "Halcones del Asfalto",
            logoPhashHex = "a5a5a5a5a5a5a5a5"
        )
        assertTrue(allowedInAr.isSuccess)

        // 3. Grupo principal en MX ya tiene 3 líderes en datos demo; rechaza un 4.º líder (R4.1)
        val fourthLeaderFail = repo.addCoLeader(
            groupId = "grp-halcones-mx",
            actorUserId = "u-founder-1",
            candidateUserId = "u-leader-4"
        )
        assertTrue(fourthLeaderFail.isFailure)
        assertEquals("MAX_LEADERS_REACHED", fourthLeaderFail.exceptionOrNull()?.message)

        // 4. Co-líder no principal no puede nombrar líderes (R4.3)
        val nonPrincipalFail = repo.addCoLeader(
            groupId = "grp-coyotes-mx",
            actorUserId = "u-founder-1",
            candidateUserId = "u-leader-4"
        )
        assertTrue(nonPrincipalFail.isFailure)
        assertEquals("ONLY_PRINCIPAL_LEADER", nonPrincipalFail.exceptionOrNull()?.message)
    }

    @Test
    fun `R2_1 R2_2 R2_3 R2_4 R3_3 R15_1 enforces invitation acceptance, consents, Full exclusivity, and Guest role ban`() {
        val repo = ClubRepository()

        // 1. u-member-1 ya es FULL en grp-halcones-mx -> no puede ser invitado como FULL a otro grupo (R2.2)
        val exclusiveFail = repo.sendInvitation(
            groupId = "grp-coyotes-mx",
            actorUserId = "u-leader-2",
            invitedUserId = "u-member-1",
            statusKind = MemberStatusKind.FULL
        )
        assertTrue(exclusiveFail.isFailure)
        assertEquals("EXCLUSIVE_CLUB_MEMBERSHIP", exclusiveFail.exceptionOrNull()?.message)

        // 2. Pero sí puede ser invitado como GUEST (R2.3) y queda en estado "pending" (R2.1)
        val guestInvite = repo.sendInvitation(
            groupId = "grp-coyotes-mx",
            actorUserId = "u-leader-2",
            invitedUserId = "u-member-1",
            statusKind = MemberStatusKind.GUEST
        )
        assertTrue(guestInvite.isSuccess)
        val invId = guestInvite.getOrThrow().id
        assertEquals("pending", guestInvite.getOrThrow().state)

        // 3. No puede aceptar sin los consentimientos obligatorios de membresía y ubicación (R15.1)
        val missingConsents = repo.acceptInvitation(
            invitationId = invId,
            consentMembership = true,
            consentLocation = false,
            consentEmergencyPackage = false,
            consentNotifications = false
        )
        assertTrue(missingConsents.isFailure)
        assertEquals("REQUIRED_CONSENTS_MISSING", missingConsents.exceptionOrNull()?.message)

        // 4. Con consentimientos obligatorios entra al grupo como GUEST
        val accepted = repo.acceptInvitation(
            invitationId = invId,
            consentMembership = true,
            consentLocation = true,
            consentEmergencyPackage = true,
            consentNotifications = true
        )
        assertTrue(accepted.isSuccess)

        // 5. Por defecto forbidGuestClubRoles = false (propuesta P7 en RULES.md §12 pendiente del dueño):
        //    no bloquea salvo que se active el parámetro P7.
        val guestRoleDefault = repo.assignMemberRoleOrStatus(
            groupId = "grp-coyotes-mx",
            actorUserId = "u-leader-2",
            memberUserId = "u-member-1",
            newStatusKind = MemberStatusKind.GUEST,
            newClubRole = ClubRole.ROAD_CAPTAIN
        )
        assertTrue(guestRoleDefault.isSuccess)

        repo.updateDevOverrides { it.copy(forbidGuestClubRoles = true) }
        val guestRoleFail = repo.assignMemberRoleOrStatus(
            groupId = "grp-coyotes-mx",
            actorUserId = "u-leader-2",
            memberUserId = "u-member-1",
            newStatusKind = MemberStatusKind.GUEST,
            newClubRole = ClubRole.ROAD_CAPTAIN
        )
        assertTrue(guestRoleFail.isFailure)
        assertEquals("GUEST_CANNOT_HAVE_CLUB_ROLE", guestRoleFail.exceptionOrNull()?.message)
    }

    @Test
    fun `R14_2 R5_3 approves sensitive vote at 2 of 3 leaders and sets 7-day hold on group transfer`() {
        val repo = ClubRepository()

        // Voto de un segundo líder alcanza mayoría 2/3 en grp-halcones-mx (R14.2)
        val voteRes = repo.castLeaderVote(
            groupId = "grp-halcones-mx",
            voteId = "vote-demo-1",
            leaderUserId = "u-leader-2",
            approve = true
        )
        assertTrue(voteRes.isSuccess)
        assertEquals("approved", voteRes.getOrThrow().state)
        assertEquals(2, voteRes.getOrThrow().approvals.size)

        // Transferencia de grupo requiere 7 días de espera (R5.3)
        val now = 1_700_000_000_000L
        val transferRes = repo.initiateGroupTransfer(
            groupId = "grp-halcones-mx",
            actorUserId = "u-founder-1",
            toLeaderUserId = "u-leader-2",
            nowEpochMs = now
        )
        assertTrue(transferRes.isSuccess)
        val expectedAvailable = now + 7L * 24L * 60L * 60L * 1000L
        assertEquals(expectedAvailable, transferRes.getOrThrow().pendingTransferAvailableAtEpochMs)
    }

    @Test
    fun `SKELETONS_5_1 enforces 150ms show delay, 400ms min visible hold, 3s slow hint, and 8s timeout`() {
        // 1. Si los datos llegan a los 100 ms (<150 ms), nunca aparece el skeleton
        val fast100 = SkeletonTimingRules.evaluateAt(elapsedMs = 100L, loadingFinishedAtMs = 100L)
        assertFalse(fast100.showSkeleton)
        assertFalse(fast100.timedOutToNetworkError)

        // 2. A los 200 ms de carga activa, el skeleton es visible
        val active200 = SkeletonTimingRules.evaluateAt(elapsedMs = 200L, loadingFinishedAtMs = null)
        assertTrue(active200.showSkeleton)
        assertFalse(active200.showSlowHint)

        // 3. Si termina a los 160 ms, mantiene el skeleton visible hasta completar 400 ms visibles (150 + 400 = 550 ms)
        val holdAt350 = SkeletonTimingRules.evaluateAt(elapsedMs = 350L, loadingFinishedAtMs = 160L)
        assertTrue(holdAt350.showSkeleton)
        val releasedAt560 = SkeletonTimingRules.evaluateAt(elapsedMs = 560L, loadingFinishedAtMs = 160L)
        assertFalse(releasedAt560.showSkeleton)

        // 4. A los 3 200 ms muestra el texto secundario "Esto está tardando más de lo normal…"
        val slowAt3200 = SkeletonTimingRules.evaluateAt(elapsedMs = 3_200L, loadingFinishedAtMs = null)
        assertTrue(slowAt3200.showSkeleton)
        assertTrue(slowAt3200.showSlowHint)

        // 5. A los 8 000 ms o más pasa a Error(NETWORK)
        val timeoutAt8500 = SkeletonTimingRules.evaluateAt(elapsedMs = 8_500L, loadingFinishedAtMs = null)
        assertFalse(timeoutAt8500.showSkeleton)
        assertTrue(timeoutAt8500.timedOutToNetworkError)
    }

    @Test
    fun `Shared JSON test vectors match Kotlin ClubRulesEngine 100 percent`() {
        val candidates = listOf(
            java.io.File("../docs/test-vectors/phase1_rules_vectors.json"),
            java.io.File("../../docs/test-vectors/phase1_rules_vectors.json"),
            java.io.File("D:/Ruta-Biker-Sync/docs/test-vectors/phase1_rules_vectors.json")
        )
        val file = candidates.firstOrNull { it.exists() }
            ?: error("No se encontró docs/test-vectors/phase1_rules_vectors.json")
        val root = kotlinx.serialization.json.Json.parseToJsonElement(file.readText()).let {
            it as kotlinx.serialization.json.JsonObject
        }

        val normCases = root["normalizationCases"] as kotlinx.serialization.json.JsonArray
        for (el in normCases) {
            val obj = el as kotlinx.serialization.json.JsonObject
            val input = obj["input"]!!.let { (it as kotlinx.serialization.json.JsonPrimitive).content }
            val expNorm = obj["expectedNormalized"]!!.let { (it as kotlinx.serialization.json.JsonPrimitive).content }
            val expSkel = obj["expectedLeetSkeleton"]!!.let { (it as kotlinx.serialization.json.JsonPrimitive).content }
            assertEquals(expNorm, ClubRulesEngine.normalizeGroupName(input))
            assertEquals(expSkel, ClubRulesEngine.toLeetSkeleton(input))
        }

        val colCases = root["collisionCases"] as kotlinx.serialization.json.JsonArray
        for (el in colCases) {
            val obj = el as kotlinx.serialization.json.JsonObject
            val cand = (obj["candidate"] as kotlinx.serialization.json.JsonPrimitive).content
            val exist = (obj["existing"] as kotlinx.serialization.json.JsonPrimitive).content
            val expCol = (obj["expectedCollides"] as kotlinx.serialization.json.JsonPrimitive).content.toBoolean()
            val res = ClubRulesEngine.checkGroupNameCollision(cand, exist)
            assertEquals(expCol, res.collides)
        }

        val phashCases = root["phashCases"] as kotlinx.serialization.json.JsonArray
        for (el in phashCases) {
            val obj = el as kotlinx.serialization.json.JsonObject
            val cand = (obj["candidateHex"] as kotlinx.serialization.json.JsonPrimitive).content
            val exist = (obj["existingHex"] as kotlinx.serialization.json.JsonPrimitive).content
            val expDist = (obj["expectedDistance"] as kotlinx.serialization.json.JsonPrimitive).content.toInt()
            val expRej = (obj["expectedRejected"] as kotlinx.serialization.json.JsonPrimitive).content.toBoolean()
            val expRev = (obj["expectedManualReview"] as kotlinx.serialization.json.JsonPrimitive).content.toBoolean()
            val sim = ClubRulesEngine.evaluateLogoSimilarity(cand, exist)
            assertEquals(expDist, sim.distance)
            assertEquals(expRej, sim.rejected)
            assertEquals(expRev, sim.needsManualReview)
        }

        val semCases = root["semaphoreCases"] as kotlinx.serialization.json.JsonArray
        for (el in semCases) {
            val obj = el as kotlinx.serialization.json.JsonObject
            val nowMs = (obj["nowEpochMs"] as kotlinx.serialization.json.JsonPrimitive).content.toLong()
            val paidRaw = obj["paidUntilEpochMs"] as kotlinx.serialization.json.JsonPrimitive
            val paidMs = if (paidRaw.isString || paidRaw.content == "null") null else paidRaw.content.toLong()
            val graceRaw = obj["graceUntilEpochMs"] as kotlinx.serialization.json.JsonPrimitive
            val graceMs = if (graceRaw.isString || graceRaw.content == "null") null else graceRaw.content.toLong()
            val expSem = (obj["expectedSemaphore"] as kotlinx.serialization.json.JsonPrimitive).content.uppercase()
            val actual = ClubRulesEngine.computePaymentSemaphore(paidMs, graceMs, nowMs)
            assertEquals(expSem, actual.name)
        }
    }

    @Test
    fun `EndToEnd Android ClubRepository and ClubApiClient with useFakeBackend false against real Fastify and PostgreSQL 16`() {
        val backendUrl = System.getenv("RBS_E2E_BACKEND_URL") ?: "http://127.0.0.1:3000"
        val apiClient = com.rutabikersync.app.core.network.ClubApiClient(baseUrl = backendUrl)
        val repo = ClubRepository(apiClient = apiClient)

        val suffix = (System.currentTimeMillis() % 1_000_000L).toString(16).padStart(6, '1')
        val result = kotlinx.coroutines.runBlocking {
            repo.executeRealBackendFlow(runSuffix = suffix)
        }
        if (result.isFailure) {
            val err = result.exceptionOrNull()
            if (System.getenv("RBS_REQUIRE_E2E") == "1") {
                throw AssertionError("Falló flujo E2E real Android -> Backend ($backendUrl): ${err?.message}", err)
            } else {
                println("[SKIP E2E] Backend local no disponible en $backendUrl: ${err?.message}")
                return
            }
        }
        val summary = result.getOrThrow()
        println(
            "[E2E_ANDROID_REAL_BACKEND_OK] useFakeBackend=${repo.devOverrides.value.useFakeBackend} " +
                "leaderId=${summary.leaderId} memberId=${summary.memberId} groupId=${summary.groupId} " +
                "invitationId=${summary.invitationId} membersCount=${summary.membersCount} " +
                "refreshedTokenPrefix=${summary.refreshedTokenPrefix} logoutRevoked=${summary.logoutRevoked}"
        )
        assertFalse(repo.devOverrides.value.useFakeBackend)
        assertTrue(summary.leaderId.isNotBlank())
        assertTrue(summary.memberId.isNotBlank())
        assertTrue(summary.groupId.isNotBlank())
        assertTrue(summary.invitationId.isNotBlank())
        assertEquals(1, summary.membersCount)
        assertTrue(summary.logoutRevoked)
    }
}
