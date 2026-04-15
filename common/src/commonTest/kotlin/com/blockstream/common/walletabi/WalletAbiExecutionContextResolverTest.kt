package com.blockstream.common.walletabi

import com.blockstream.common.walletabi.transport.WalletAbiNetwork
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class WalletAbiExecutionContextResolverTest {
    @Test
    fun sessionRequestSelectionPrefersExactNetworkMatch() {
        val incoming = profile(
            id = "incoming",
            networks = listOf(WalletAbiNetwork.LIQUID),
            environmentNetworks = setOf(WalletAbiNetwork.LIQUID),
        )
        val alternate = profile(
            id = "alternate",
            networks = listOf(WalletAbiNetwork.TESTNET_LIQUID),
            environmentNetworks = setOf(WalletAbiNetwork.TESTNET_LIQUID, WalletAbiNetwork.LOCALTEST_LIQUID),
        )

        val selected = selectSessionRequestProfile(
            incoming = incoming,
            connectedCandidates = listOf(alternate),
            requestNetwork = WalletAbiNetwork.TESTNET_LIQUID,
        )

        assertEquals("alternate", selected?.id)
    }

    @Test
    fun sessionRequestSelectionFallsBackToEnvironmentMatch() {
        val incoming = WalletAbiSessionProfile(
            id = "incoming",
            connected = true,
            signerKind = null,
            accountProfiles = emptyList(),
            environmentNetworks = setOf(WalletAbiNetwork.LIQUID),
            summary = "incoming",
        )
        val alternate = profile(
            id = "alternate",
            networks = emptyList(),
            environmentNetworks = setOf(WalletAbiNetwork.TESTNET_LIQUID, WalletAbiNetwork.LOCALTEST_LIQUID),
        )

        val selected = selectSessionRequestProfile(
            incoming = incoming,
            connectedCandidates = listOf(alternate),
            requestNetwork = WalletAbiNetwork.LOCALTEST_LIQUID,
        )

        assertEquals("alternate", selected?.id)
    }

    @Test
    fun sessionRequestSelectionFallsBackToFirstSignerCapableSession() {
        val incoming = WalletAbiSessionProfile(
            id = "incoming",
            connected = true,
            signerKind = null,
            accountProfiles = emptyList(),
            environmentNetworks = emptySet(),
            summary = "incoming",
        )
        val alternate = profile(
            id = "alternate",
            networks = emptyList(),
            environmentNetworks = emptySet(),
        )

        val selected = selectSessionRequestProfile(
            incoming = incoming,
            connectedCandidates = listOf(alternate),
            requestNetwork = WalletAbiNetwork.LIQUID,
        )

        assertEquals("alternate", selected?.id)
    }

    @Test
    fun walletAbiNetworkRoundTripsThroughWireValues() {
        assertEquals("liquid", WalletAbiNetwork.LIQUID.serialValue())
        assertEquals("testnet-liquid", WalletAbiNetwork.TESTNET_LIQUID.serialValue())
        assertEquals("localtest-liquid", WalletAbiNetwork.LOCALTEST_LIQUID.serialValue())

        assertEquals(WalletAbiNetwork.LIQUID, "liquid".toWalletAbiNetwork())
        assertEquals(WalletAbiNetwork.TESTNET_LIQUID, "testnet-liquid".toWalletAbiNetwork())
        assertEquals(WalletAbiNetwork.LOCALTEST_LIQUID, "localtest-liquid".toWalletAbiNetwork())
        assertFailsWith<IllegalArgumentException> {
            "bitcoin".toWalletAbiNetwork()
        }
    }

    private fun profile(
        id: String,
        networks: List<WalletAbiNetwork>,
        environmentNetworks: Set<WalletAbiNetwork>,
    ) = WalletAbiSessionProfile(
        id = id,
        connected = true,
        signerKind = WalletAbiSignerKind.SOFTWARE,
        accountProfiles = networks.mapIndexed { index, network ->
            WalletAbiAccountProfile(
                id = "account-$index",
                network = network,
            )
        },
        environmentNetworks = environmentNetworks,
        summary = id,
    )
}
