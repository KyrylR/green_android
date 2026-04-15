package com.blockstream.common.walletabi

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WalletAbiWalletConnectBridgeTest {
    @Test
    fun noopBridgeReportsAndroidOnlyErrorForSessionActions() = runTest {
        val errors = mutableListOf<String>()
        val bridge = NoopWalletAbiWalletConnectBridge().also {
            it.setListener(
                object : WalletAbiWalletConnectBridgeListener {
                    override fun onSessionProposal(proposal: WalletAbiSessionProposal) = Unit

                    override fun onSessionRequest(request: WalletAbiSessionRequest) = Unit

                    override fun onSessionDelete(topic: String, reason: String) = Unit

                    override fun onSessionExtend(session: WalletAbiSessionInfo) = Unit

                    override fun onError(message: String) {
                        errors += message
                    }
                },
            )
        }

        bridge.pair("wc:test")
        bridge.approveSession(
            proposal = WalletAbiSessionProposal(
                pairingTopic = "topic-1",
                proposerPublicKey = "pubkey-1",
                name = "Example dApp",
                description = null,
                url = null,
                icons = emptyList(),
                redirect = null,
                relayProtocol = null,
                requiredNamespaces = emptyMap(),
                optionalNamespaces = emptyMap(),
                properties = null,
                scopedProperties = null,
                verifyContext = null,
            ),
            approval = WalletAbiSessionApproval(
                relayProtocol = null,
                namespaces = emptyMap(),
                properties = null,
                scopedProperties = null,
            ),
        )
        bridge.rejectSession(
            proposal = WalletAbiSessionProposal(
                pairingTopic = "topic-1",
                proposerPublicKey = "pubkey-1",
                name = "Example dApp",
                description = null,
                url = null,
                icons = emptyList(),
                redirect = null,
                relayProtocol = null,
                requiredNamespaces = emptyMap(),
                optionalNamespaces = emptyMap(),
                properties = null,
                scopedProperties = null,
                verifyContext = null,
            ),
            reason = "rejected",
        )

        assertEquals(
            listOf(
                "WalletConnect Wallet ABI is only available on Android",
                "WalletConnect Wallet ABI is only available on Android",
                "WalletConnect Wallet ABI is only available on Android",
            ),
            errors,
        )
    }

    @Test
    fun noopBridgeHasNoTrackedSessionsOrRequests() = runTest {
        val bridge = NoopWalletAbiWalletConnectBridge()

        bridge.initialize()

        assertTrue(bridge.getActiveSessions().isEmpty())
        assertNull(bridge.getActiveSession("missing-topic"))
        assertTrue(bridge.getPendingRequests("missing-topic").isEmpty())
    }
}
