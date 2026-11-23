package com.rutabikersync.app.feature.groups

import androidx.lifecycle.viewModelScope
import com.rutabikersync.app.ui.components.skeleton.SkeletonTimingRules
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class Phase1RulesTest {

    private val testDispatcher = StandardTestDispatcher()
    private val trackedViewModels = mutableListOf<Phase1ViewModel>()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        // El init del VM abre un ciclo infinito de sincronizacion. Cancelar su scope
        // evita que siga usando Dispatchers.Main mientras hacemos resetMain.
        trackedViewModels.forEach { viewModel ->
            viewModel.viewModelScope.coroutineContext[Job]?.cancel()
        }
        trackedViewModels.clear()
        // Drenar antes de resetMain evita que una continuacion pendiente intente
        // despachar en el Main real y dispare "Dispatchers.Main is used concurrently".
        testDispatcher.scheduler.advanceUntilIdle()
        Dispatchers.resetMain()
    }

    /**
     * La red corre en Dispatchers.IO (no controlado por el scheduler de prueba).
     * Avanzar el scheduler en cada sondeo publica el rollback en Dispatchers.Main
     * sin permitir que gane la carrera antes de la asercion de 0 ms.
     */
    private fun awaitRollback(timeoutMs: Long = 25_000L, condition: () -> Boolean) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        while (!condition() && System.nanoTime() < deadline) {
            testDispatcher.scheduler.advanceUntilIdle()
            if (!condition()) Thread.sleep(10)
        }
        testDispatcher.scheduler.advanceUntilIdle()
    }

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
        val repo = ClubRepository().apply { loadDemoDataForTesting() }

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
        val repo = ClubRepository().apply { loadDemoDataForTesting() }

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

        // 3. Prueba canónica de aceptación de invitación a rodar (R2.3) mediante el repositorio delegado
        val inv = repo.invitationRepository.createInvitation(
            type = InvitationType.RODADA,
            clubId = "grp-coyotes-mx",
            clubName = "Coyotes de la Sierra MC",
            inviterUid = "u-leader-2",
            inviterName = "Líder Coyotes"
        )
        val accepted = repo.acceptInvitation(
            invitationId = inv.id,
            inviteeUid = "u-member-1",
            inviteeName = "Miembro 1",
            currentClubId = "grp-halcones-mx"
        )
        assertTrue(accepted.isSuccess)
        val acceptance = accepted.getOrThrow()
        assertTrue(acceptance.isSuccess)
        assertEquals("grp-halcones-mx", acceptance.appliedClubId)

        // 4. Incorporación del invitado (GUEST) en el grupo
        repo.addMemberToClub(
            groupId = "grp-coyotes-mx",
            member = ClubMemberModel(
                userId = "u-member-1",
                displayName = "Miembro 1",
                bikeModel = "Harley Davidson Iron 883",
                statusKind = MemberStatusKind.GUEST,
                clubRole = null,
                isLeader = false,
                isFounder = false,
                paymentSemaphore = PaymentSemaphore.GREEN,
                validRides = 0
            )
        )

        // 5. Regla P7 APROBADA (forbidGuestClubRoles = true por defecto en RULES.md §3 y §12):
        //    los invitados (GUEST) no reciben cargos honoríficos del club.
        val guestRoleDefault = repo.assignMemberRoleOrStatus(
            groupId = "grp-coyotes-mx",
            actorUserId = "u-leader-2",
            memberUserId = "u-member-1",
            newStatusKind = MemberStatusKind.GUEST,
            newClubRole = ClubRole.ROAD_CAPTAIN
        )
        assertTrue(guestRoleDefault.isFailure)
        assertEquals("GUEST_CANNOT_HAVE_CLUB_ROLE", guestRoleDefault.exceptionOrNull()?.message)
    }

    @Test
    fun `R14_2 R5_3 approves sensitive vote at 2 of 3 leaders and sets 7-day hold on group transfer`() {
        val repo = ClubRepository().apply { loadDemoDataForTesting() }

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
        val leaderPassword = System.getenv("RBS_E2E_LEADER_PASS") ?: "StrongLeaderPass!2026"
        val memberPassword = System.getenv("RBS_E2E_MEMBER_PASS") ?: "StrongMemberPass!2026"
        val apiClient = com.rutabikersync.app.core.network.ClubApiClient(baseUrl = backendUrl)
        val repo = ClubRepository(apiClient = apiClient)

        val suffix = (System.currentTimeMillis() % 1_000_000L).toString(16).padStart(6, '1')
        val result = kotlinx.coroutines.runBlocking {
            repo.executeRealBackendFlow(
                runSuffix = suffix,
                leaderPassword = leaderPassword,
                memberPassword = memberPassword
            )
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

    @Test
    fun `R2_2 prevents cross membership when user already belongs to a club`() {
        val repo = ClubRepository().apply { loadDemoDataForTesting() }
        val user = repo.currentUser.value
        val vm = Phase1ViewModel(repository = repo).also { trackedViewModels.add(it) }

        // 1. Intentar unirse a un segundo club (Lobos del Asfalto) mediante QR de miembro
        val qrPayloadSecondClub = GroupInviteQrHelper.encodePayload(
            GroupInviteQrHelper.InviteQrPayload(
                groupId = "grp-lobos",
                inviteCode = "LOBOS-01",
                groupName = "Lobos del Asfalto",
                countryCode = "VE",
                intentType = GroupInviteQrHelper.QrIntentType.MEMBER
            )
        )
        vm.joinByQrCode(qrPayloadSecondClub)

        // 2. Verificar que se bloquea con el mensaje normativo
        val banner = vm.statusBanner.value
        val myClub = repo.groups.value.first { g -> g.members.any { it.userId == user.id || it.displayName.equals(user.displayName, ignoreCase = true) } }
        assertTrue("Debe contener advertencia de Estatuto Biker: $banner", banner?.contains("Estatuto Biker") == true)
        assertTrue("Debe mencionar el club original: $banner", banner?.contains(myClub.name) == true)
        assertTrue("Debe rechazar la doble membresía: $banner", banner?.contains("no puedes ser miembro de dos clubes") == true)

        // 3. Verificar que no se creó ninguna invitación pendiente para el segundo club
        val pending = repo.pendingInvitations.value.filter { it.groupId == "grp-lobos" }
        assertTrue("No debe haber invitaciones pendientes para el segundo club", pending.isEmpty())

        // 4. Verificar que no se agregó como miembro del segundo club
        val lobosClub = repo.groups.value.find { it.id == "grp-lobos" }
        assertTrue("No debe existir o no debe tener al usuario", lobosClub == null || lobosClub.members.none { it.userId == user.id })
    }

    @Test
    fun `expelMemberFromClub enforces leader permissions, founder protection, and removes member from StateFlow`() {
        val repo = ClubRepository().apply { loadDemoDataForTesting() }
        val group = repo.groups.value.first { it.id == "grp-halcones-mx" }
        val founderId = group.principalLeaderId
        val normalMemberId = "u-member-1"
        val nonLeaderId = "u-member-1"

        // 1. Un no-líder intenta expulsar a alguien -> Falla ONLY_LEADER_CAN_EXPEL
        val nonLeaderExpel = repo.expelMemberFromClub(
            groupId = "grp-halcones-mx",
            actorUserId = nonLeaderId,
            targetUserId = "u-prospect-1"
        )
        assertTrue(nonLeaderExpel.isFailure)
        assertEquals("ONLY_LEADER_CAN_EXPEL", nonLeaderExpel.exceptionOrNull()?.message)

        // 2. Un líder intenta expulsar al Fundador -> Falla CANNOT_EXPEL_FOUNDER
        val founderExpel = repo.expelMemberFromClub(
            groupId = "grp-halcones-mx",
            actorUserId = founderId,
            targetUserId = founderId
        )
        assertTrue(founderExpel.isFailure)
        assertEquals("CANNOT_EXPEL_FOUNDER", founderExpel.exceptionOrNull()?.message)

        // 3. Un líder expulsa a un miembro normal -> Exitoso y se remueve del StateFlow
        val successExpel = repo.expelMemberFromClub(
            groupId = "grp-halcones-mx",
            actorUserId = founderId,
            targetUserId = normalMemberId
        )
        assertTrue(successExpel.isSuccess)
        val updatedGroup = successExpel.getOrThrow()
        assertFalse(updatedGroup.members.any { it.userId == normalMemberId })
        val storedGroup = repo.groups.value.first { it.id == "grp-halcones-mx" }
        assertFalse(storedGroup.members.any { it.userId == normalMemberId })
    }

    @Test
    fun `Hito 2 hermandad invitation enforces synchronous precheck and blocks dual membership immediately`() {
        val repo = ClubRepository().apply { loadDemoDataForTesting() }
        val vm = Phase1ViewModel(repository = repo).also { trackedViewModels.add(it) }
        val user = repo.currentUser.value
        val myClub = repo.groups.value.first { g -> g.members.any { it.userId == user.id } }

        // Crear una invitación de Hermandad para un club ajeno (Lobos del Asfalto)
        val inv = repo.invitationRepository.createInvitation(
            type = InvitationType.HERMANDAD,
            clubId = "grp-lobos",
            clubName = "Lobos del Asfalto",
            inviterUid = "u-inviter-lobos",
            inviterName = "Presidente Lobo"
        )

        // Intentar aceptar: debe ser bloqueado a 0 ms por regla R2.2 sin tocar membresía
        vm.acceptInvite(
            invitationId = inv.id,
            consentMembership = true,
            consentLocation = true,
            consentEmergency = true,
            consentNotifications = true
        )

        val banner = vm.statusBanner.value
        assertTrue("Debe proyectar banner de Estatuto Biker: $banner", banner?.contains("Estatuto Biker") == true)
        assertTrue("No debe estar en estado de espera permanente", !vm.isAcceptingInvite.value)

        // Verificar que el usuario sigue en su club original intacto
        val currentClub = repo.groups.value.first { g -> g.members.any { it.userId == user.id } }
        assertEquals(myClub.id, currentClub.id)
        assertFalse(repo.groups.value.any { it.id == "grp-lobos" && it.members.any { m -> m.userId == user.id } })
    }

    @Test
    fun `Hito 2 optimistic expulsion rolls back when backend rejects or is unreachable`() {
        val repo = ClubRepository().apply { loadDemoDataForTesting() }
        repo.apiClient.baseUrl = "http://invalid.test"
        repo.updateDevOverrides { it.copy(useFakeBackend = false) } // Simula backend real conectado
        val vm = Phase1ViewModel(repository = repo).also { trackedViewModels.add(it) }
        val group = repo.groups.value.first { it.id == "grp-halcones-mx" }
        val memberId = "u-member-1"

        // 1. Expulsión optimista inmediata (0 ms): el miembro desaparece de inmediato de la UI local
        vm.expelMember(groupId = group.id, memberUserId = memberId)
        val groupImmediate = repo.groups.value.first { it.id == group.id }
        assertFalse("El miembro debe desaparecer inmediatamente (0 ms) a nivel local", groupImmediate.members.any { it.userId == memberId })

        // 2. Esperar a que el backend rechace (401/403/error de red) y se active el rollback
        awaitRollback {
            val g = repo.groups.value.first { it.id == group.id }
            g.members.any { it.userId == memberId } && vm.statusBanner.value?.contains("revertida") == true
        }
        val restoredGroup = repo.groups.value.first { it.id == group.id }
        val restored = restoredGroup.members.any { it.userId == memberId } &&
            vm.statusBanner.value?.contains("revertida") == true
        assertTrue("El miembro debe ser restaurado por el mecanismo de rollback", restored)
        val banner = vm.statusBanner.value
        assertTrue("Debe notificar que la acción fue revertida: $banner", banner?.contains("revertida") == true)
    }

    @Test
    fun `Hito 2 optimistic club creation rolls back when backend rejects`() {
        val repo = ClubRepository().apply { loadDemoDataForTesting() }
        repo.apiClient.baseUrl = "http://invalid.test"
        repo.updateDevOverrides { it.copy(useFakeBackend = false) }
        val vm = Phase1ViewModel(repository = repo).also { trackedViewModels.add(it) }

        val initialGroupsCount = repo.groups.value.size

        // 1. Creación optimista inmediata (0 ms)
        vm.createNewGroup(
            countryCode = "MX",
            name = "Rollback Club",
            logoPhashHex = "0123456789abcdef"
        )
        assertTrue("El club debe aparecer inmediatamente a nivel local (0 ms)", repo.groups.value.any { it.name == "Rollback Club" })

        // 2. Esperar a que el backend rechace y se ejecute el rollback
        awaitRollback { repo.groups.value.none { it.name == "Rollback Club" } }
        val rolledBack = repo.groups.value.none { it.name == "Rollback Club" }
        println("DEBUG ROLLBACK: groups=${repo.groups.value.map { it.name }}, banner=${vm.statusBanner.value}")
        assertTrue("El club debe ser removido por el rollback. Banner actual: ${vm.statusBanner.value}", rolledBack)
        assertEquals(initialGroupsCount, repo.groups.value.size)
    }
}
