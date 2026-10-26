package com.rutabikersync.app.feature.groups

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupInviteQrHelperTest {

    @Test
    fun `encodePayload genera URI valida con groupId y codigo de invitacion`() {
        val payload = GroupInviteQrHelper.InviteQrPayload(
            groupId = "grp-biker-cdmx-01",
            inviteCode = "RUTA-TEST-01",
            groupName = "Águilas del Valle",
            countryCode = "MX"
        )

        val uriString = GroupInviteQrHelper.encodePayload(payload)
        assertTrue("Debe empezar con esquema rbs://invite?", uriString.startsWith("rbs://invite?"))
        assertTrue("Debe contener groupId", uriString.contains("groupId=grp-biker-cdmx-01"))
        assertTrue("Debe contener code", uriString.contains("code=RUTA-TEST-01"))

        val parsed = GroupInviteQrHelper.parsePayload(uriString)
        assertNotNull("Debe parsear correctamente el URI generado", parsed)
        assertEquals("grp-biker-cdmx-01", parsed!!.groupId)
        assertEquals("RUTA-TEST-01", parsed.inviteCode)
        assertEquals("Águilas del Valle", parsed.groupName)
        assertEquals("MX", parsed.countryCode)
    }

    @Test
    fun `parsePayload admite fallback de codigo alfanumerico directo`() {
        val parsed = GroupInviteQrHelper.parsePayload("RUTA-TEST-02")
        assertNotNull(parsed)
        assertEquals("RUTA-TEST-02", parsed!!.inviteCode)

        val invalid = GroupInviteQrHelper.parsePayload("   ")
        assertNull(invalid)
    }

    @Test
    fun `canShareQr autoriza unicamente a Presidente y Vicepresidente`() {
        // Presidente y Vicepresidente deben poder compartir QR
        assertTrue("Presidente debe poder compartir QR", GroupInviteQrHelper.canShareQr(isLeader = true, role = ClubRole.PRESIDENT))
        assertTrue("Vicepresidente debe poder compartir QR", GroupInviteQrHelper.canShareQr(isLeader = true, role = ClubRole.VICE_PRESIDENT))
        assertTrue("Vicepresidente sin flag isLeader debe poder compartir QR", GroupInviteQrHelper.canShareQr(isLeader = false, role = ClubRole.VICE_PRESIDENT))

        // Otros cargos NO deben poder compartir QR
        assertFalse("Tesorero NO debe poder compartir QR", GroupInviteQrHelper.canShareQr(isLeader = false, role = ClubRole.TREASURER))
        assertFalse("Capitán de Ruta NO debe poder compartir QR", GroupInviteQrHelper.canShareQr(isLeader = false, role = ClubRole.ROAD_CAPTAIN))
        assertFalse("Sargento de Armas NO debe poder compartir QR", GroupInviteQrHelper.canShareQr(isLeader = false, role = ClubRole.SERGEANT_AT_ARMS))
        assertFalse("Secretario NO debe poder compartir QR", GroupInviteQrHelper.canShareQr(isLeader = false, role = ClubRole.SECRETARY))
        assertFalse("Miembro común NO debe poder compartir QR", GroupInviteQrHelper.canShareQr(isLeader = false, role = null))
    }
}
