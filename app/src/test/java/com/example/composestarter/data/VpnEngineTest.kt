package com.example.composestarter.data

import org.junit.Assert.*
import org.junit.Test

class VpnEngineTest {
    @Test fun autoSelectionPrefersClientWithControlEntry() {
        assertEquals(VpnEngine.FLCLASH,
            VpnEngine.effective(null, listOf(VpnEngine.UMIVPN, VpnEngine.FLCLASH)))
    }

    @Test fun unsupportedClientIsExcludedFromListAndOldSelection() {
        assertFalse(VpnEngine.UMIVPN in VpnEngine.supportedEntries)
        assertEquals(VpnEngine.FLCLASH,
            VpnEngine.effective("umivpn", listOf(VpnEngine.FLCLASH, VpnEngine.UMIVPN)))
    }

    @Test fun onlyUmiInstalledDoesNotOfferAnAdaptedClient() {
        assertNull(VpnEngine.effective(null, listOf(VpnEngine.UMIVPN)))
        assertNull(VpnEngine.effective("umivpn", listOf(VpnEngine.UMIVPN)))
        assertNull(VpnEngine.UMIVPN.startIntent())
        assertNull(VpnEngine.UMIVPN.stopIntent())
    }

    @Test fun stopFallbackSkipsUnsupportedClientAndKeepsPreferredFirst() {
        assertEquals(listOf(VpnEngine.SURFBOARD, VpnEngine.FLCLASH),
            VpnEngine.stopCandidates(VpnEngine.SURFBOARD,
                listOf(VpnEngine.UMIVPN, VpnEngine.FLCLASH, VpnEngine.SURFBOARD)))
    }

    @Test fun knownUnsupportedOwnerDoesNotTryOtherClients() {
        assertTrue(VpnEngine.stopCandidates(VpnEngine.UMIVPN,
            listOf(VpnEngine.FLCLASH, VpnEngine.UMIVPN)).isEmpty())
    }
}
