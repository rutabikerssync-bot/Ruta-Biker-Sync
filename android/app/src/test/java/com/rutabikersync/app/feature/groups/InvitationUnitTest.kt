package com.rutabikersync.app.feature.groups

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Suite de pruebas unitarias canónica para el sistema de Invitaciones y Hermandad (Fase 1).
 * Valida de forma estricta las reglas R2.2 (membresía única) y R2.3 (rodadas libres).
 */
class InvitationUnitTest {

    @Test
    fun `test 1 - createInvitation genera una invitacion en estado PENDING con expiracion correcta`() {
        var currentTime = 1_000_000L
        val repository = InvitationRepository(clock = { currentTime })

        val invitation = repository.createInvitation(
            type = InvitationType.HERMANDAD,
            clubId = "club-aguilas",
            clubName = "Aguilas del Camino",
            inviterUid = "u-oscar-leader",
            inviterName = "Capitán Oscar",
            validDurationMs = 24 * 60 * 60 * 1000L // 24 horas
        )

        assertEquals(InvitationStatus.PENDING, invitation.status)
        assertEquals("club-aguilas", invitation.clubId)
        assertEquals("Aguilas del Camino", invitation.clubName)
        assertEquals("u-oscar-leader", invitation.inviterUid)
        assertNull(invitation.inviteeUid)
        assertEquals(1_000_000L, invitation.createdAt)
        assertEquals(1_000_000L + 86_400_000L, invitation.expiresAt)
        assertTrue(invitation.isPending)
        assertFalse(invitation.isExpired)

        // Verificar que está en el StateFlow
        val stored = repository.invitations.value
        assertEquals(1, stored.size)
        assertEquals(invitation.id, stored.first().id)
    }

    @Test
    fun `test 2 - acceptInvitation con piloto limpio y tipo HERMANDAD resulta en ACCEPTED y club asignado`() {
        val repository = InvitationRepository()

        val invitation = repository.createInvitation(
            type = InvitationType.HERMANDAD,
            clubId = "club-aguilas",
            clubName = "Aguilas del Camino",
            inviterUid = "u-oscar-leader",
            inviterName = "Capitán Oscar"
        )

        val result = repository.acceptInvitation(
            invitationId = invitation.id,
            inviteeUid = "u-ozmar-paz",
            inviteeName = "ozmartinezpaz",
            currentClubId = null // Piloto sin club previo
        )

        assertTrue(result.isSuccess)
        val acceptance = result.getOrThrow()
        assertTrue(acceptance.isSuccess)
        assertEquals(InvitationStatus.ACCEPTED, acceptance.invitation.status)
        assertEquals("u-ozmar-paz", acceptance.invitation.inviteeUid)
        assertEquals("club-aguilas", acceptance.appliedClubId)
        assertTrue(acceptance.message.contains("Bienvenido a 'Aguilas del Camino'"))

        // Verificar persistencia reactiva en el StateFlow
        val updated = repository.getInvitationById(invitation.id)
        assertNotNull(updated)
        assertEquals(InvitationStatus.ACCEPTED, updated?.status)
    }

    @Test
    fun `test 3 - acceptInvitation con piloto que ya pertenece a un club y tipo HERMANDAD es REJECTED por regla R2_2`() {
        val repository = InvitationRepository()

        // Invitación para unirse al club B (Lobos del Asfalto)
        val invitation = repository.createInvitation(
            type = InvitationType.HERMANDAD,
            clubId = "club-lobos",
            clubName = "Lobos del Asfalto",
            inviterUid = "u-leader-lobos",
            inviterName = "Comandante Lobo"
        )

        // El piloto YA pertenece al club A (club-aguilas)
        val result = repository.acceptInvitation(
            invitationId = invitation.id,
            inviteeUid = "u-ozmar-paz",
            inviteeName = "ozmartinezpaz",
            currentClubId = "club-aguilas" // Piloto con membresía activa
        )

        assertTrue(result.isSuccess)
        val rejection = result.getOrThrow()
        assertFalse("No debe permitir la unión cruzada", rejection.isSuccess)
        assertEquals(InvitationStatus.REJECTED, rejection.invitation.status)
        assertEquals("club-aguilas", rejection.appliedClubId) // Mantiene su club original intacto
        assertTrue("Debe citar Estatuto Biker", rejection.message.contains("Estatuto Biker"))
        assertTrue("Debe mencionar la doble membresía", rejection.message.contains("no puedes ser miembro de dos clubes"))
        assertTrue("Razón de rechazo registrada", rejection.invitation.rejectionReason?.contains("R2.2") == true)
    }

    @Test
    fun `test 4 - acceptInvitation con piloto ya en club y tipo RODADA es ACCEPTED sin alterar clubId (R2_3)`() {
        val repository = InvitationRepository()

        // Invitación a rodada abierta emitida por otro club
        val invitation = repository.createInvitation(
            type = InvitationType.RODADA,
            clubId = "club-coyotes",
            clubName = "Coyotes de la Sierra",
            inviterUid = "u-coyote-leader",
            inviterName = "Líder Coyote"
        )

        // El piloto pertenece a Aguilas del Camino
        val result = repository.acceptInvitation(
            invitationId = invitation.id,
            inviteeUid = "u-ozmar-paz",
            inviteeName = "ozmartinezpaz",
            currentClubId = "club-aguilas"
        )

        assertTrue(result.isSuccess)
        val acceptance = result.getOrThrow()
        assertTrue("La invitación a rodar debe ser aceptada", acceptance.isSuccess)
        assertEquals(InvitationStatus.ACCEPTED, acceptance.invitation.status)
        assertEquals("club-aguilas", acceptance.appliedClubId) // NO cambia de club, conserva su club de origen (R2.3)
        assertTrue(acceptance.message.contains("Invitación a rodar con 'Coyotes de la Sierra' confirmada"))
    }

    @Test
    fun `test 5 - acceptInvitation con tiempo expirado resulta en EXPIRED (reloj inyectado)`() {
        var simulatedTime = 1_000_000L
        val repository = InvitationRepository(clock = { simulatedTime })

        val invitation = repository.createInvitation(
            type = InvitationType.HERMANDAD,
            clubId = "club-aguilas",
            clubName = "Aguilas del Camino",
            inviterUid = "u-oscar-leader",
            inviterName = "Capitán Oscar",
            validDurationMs = 60_000L // 1 minuto de validez
        )

        // Avanzar el reloj 2 minutos hacia el futuro
        simulatedTime += 120_000L

        val result = repository.acceptInvitation(
            invitationId = invitation.id,
            inviteeUid = "u-tarde",
            inviteeName = "Piloto Tardío",
            currentClubId = null
        )

        assertTrue(result.isSuccess)
        val acceptance = result.getOrThrow()
        assertFalse(acceptance.isSuccess)
        assertEquals(InvitationStatus.EXPIRED, acceptance.invitation.status)
        assertTrue(acceptance.message.contains("expirado"))

        // getInvitationById debe reflejar EXPIRED
        val queried = repository.getInvitationById(invitation.id)
        assertEquals(InvitationStatus.EXPIRED, queried?.status)
    }

    @Test
    fun `test 6 - getInvitationById con id inexistente retorna null de forma segura sin excepciones`() {
        val repository = InvitationRepository()

        val notFound = repository.getInvitationById("id-fantasma-999")
        assertNull(notFound)
    }
}

