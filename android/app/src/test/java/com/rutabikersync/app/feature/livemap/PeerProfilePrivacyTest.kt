package com.rutabikersync.app.feature.livemap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tests JVM de la regla de privacidad del perfil por groupId. */
class PeerProfilePrivacyTest {

    @Test
    fun `mismo club autoriza nombre avatar y rol reales`() {
        assertTrue(
            PeerProfilePrivacy.canShowRealProfile(
                isSelf = false,
                peerGroupId = "grp-halcones",
                localGroupId = "grp-halcones"
            )
        )
        assertEquals(
            "Carlos Vega",
            PeerProfilePrivacy.visibleName(2u, "Carlos Vega", showRealProfile = true)
        )
        assertEquals(
            "C.V.",
            PeerProfilePrivacy.visibleAbbreviation(2u, "C.V.", showRealProfile = true)
        )
    }

    @Test
    fun `club distinto redacta el perfil`() {
        assertFalse(
            PeerProfilePrivacy.canShowRealProfile(
                isSelf = false,
                peerGroupId = "grp-coyotes",
                localGroupId = "grp-halcones"
            )
        )
        assertEquals(
            "Piloto #2",
            PeerProfilePrivacy.visibleName(2u, "Carlos Vega", showRealProfile = false)
        )
        assertEquals(
            "Piloto #2",
            PeerProfilePrivacy.visibleAbbreviation(2u, "C.V.", showRealProfile = false)
        )
    }

    @Test
    fun `groupId desconocido o vacio redacta por defecto`() {
        listOf(null, "", "   ").forEach { peerGroupId ->
            assertFalse(
                "peerGroupId=$peerGroupId debe redactarse",
                PeerProfilePrivacy.canShowRealProfile(
                    isSelf = false,
                    peerGroupId = peerGroupId,
                    localGroupId = "grp-halcones"
                )
            )
        }
        assertFalse(
            PeerProfilePrivacy.canShowRealProfile(
                isSelf = false,
                peerGroupId = "grp-halcones",
                localGroupId = null
            )
        )
    }

    @Test
    fun `el piloto local siempre conserva su perfil`() {
        assertTrue(
            PeerProfilePrivacy.canShowRealProfile(
                isSelf = true,
                peerGroupId = null,
                localGroupId = null
            )
        )
    }
}
