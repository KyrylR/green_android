package com.blockstream.common.walletabi

import com.blockstream.common.data.GreenWallet
import com.blockstream.common.data.WalletSerializable
import com.blockstream.common.gdk.GdkSession
import com.blockstream.common.managers.SettingsManager
import com.blockstream.common.managers.WalletSettingsManager
import com.russhwolf.settings.PreferencesSettings
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import java.util.prefs.Preferences
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class WalletAbiSessionCoordinatorProposalTest {
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    @Test
    fun pairWalletConnectUriQueuesSessionProposalApproval() = runTest {
        val proposal = walletAbiProposal()
        val bridge = ProposalBridge(proposal = proposal)
        val coordinator = coordinator(
            walletSettingsManager = walletSettingsManager(),
            walletConnectBridge = bridge,
        )

        coordinator.pairWalletConnectUri(
            greenWallet = greenWallet(),
            session = mockk<GdkSession>(),
            input = "wc:pairing-topic@2?relay-protocol=irn&symKey=deadbeef",
        )

        val overlay = coordinator.state("wallet-1").value.overlay
        val approval = assertIs<WalletAbiOverlayLook.SessionProposalApproval>(overlay)
        assertEquals("Example dApp", approval.origin)
        assertEquals("testnet-liquid", approval.network)
        assertTrue(approval.requestedMethods.contains(WALLET_ABI_METHOD_PROCESS_REQUEST))
    }

    @Test
    fun approveCurrentOverlayPersistsApprovedSession() = runTest {
        val proposal = walletAbiProposal()
        val sessionInfo = WalletAbiSessionInfo(
            topic = "topic-1",
            expiry = 100L,
            name = "Example dApp",
            description = null,
            url = "https://example.app",
            icons = emptyList(),
            requiredNamespaces = proposal.requiredNamespaces,
            optionalNamespaces = emptyMap(),
            namespaces = mapOf(
                WALLET_ABI_WALLETCONNECT_NAMESPACE to WalletAbiSessionNamespace(
                    chains = listOf("walabi:testnet-liquid"),
                    accounts = listOf("walabi:testnet-liquid:0123abcd"),
                    methods = listOf(
                        WALLET_ABI_METHOD_GET_SIGNER_RECEIVE_ADDRESS,
                        WALLET_ABI_METHOD_PROCESS_REQUEST,
                    ),
                    events = emptyList(),
                ),
            ),
        )
        val manager = walletSettingsManager()
        val bridge = ProposalBridge(
            proposal = proposal,
            approvedSession = sessionInfo,
        )
        val coordinator = coordinator(
            walletSettingsManager = manager,
            walletConnectBridge = bridge,
        )

        coordinator.pairWalletConnectUri(
            greenWallet = greenWallet(),
            session = mockk<GdkSession>(),
            input = "wc:pairing-topic@2?relay-protocol=irn&symKey=deadbeef",
        )
        val outcome = coordinator.approveCurrentOverlay("wallet-1")

        assertEquals(
            WalletAbiActionOutcome.Success("WalletConnect Wallet ABI session approved"),
            outcome,
        )
        assertEquals("topic-1", bridge.approvedTopic)
        assertTrue(manager.getWalletAbiSessionState("wallet-1")!!.contains("topic-1"))
        assertIs<WalletAbiOverlayLook.ConnectionEstablished>(coordinator.state("wallet-1").value.overlay)
        assertEquals(
            WalletAbiSessionState.CONNECTED,
            coordinator.state("wallet-1").value.activeConnection?.state,
        )
    }

    private fun walletSettingsManager(): WalletSettingsManager {
        val settings = PreferencesSettings(
            Preferences.userRoot().node("wallet-abi-session-proposal-test-${System.nanoTime()}"),
        )
        return WalletSettingsManager(
            com.blockstream.common.database.Database(
                driverFactory = com.blockstream.common.database.DriverFactory(),
                settingsManager = SettingsManager(
                    settings = settings,
                    analyticsFeatureEnabled = false,
                    lightningFeatureEnabled = false,
                    storeRateEnabled = false,
                ),
            ),
        )
    }

    private fun greenWallet(): GreenWallet {
        return GreenWallet(
            wallet = WalletSerializable(
                id = "wallet-1",
                name = "Wallet",
                xpub_hash_id = "xpub-wallet-1",
                active_network = "testnet-liquid",
                active_account = 0L,
                is_recovery_confirmed = true,
                is_testnet = true,
                is_hardware = false,
                is_lightning = false,
                ask_bip39_passphrase = false,
                watch_only_username = null,
                device_identifiers = null,
                extras = null,
                order = 0L,
            ),
        )
    }

    private fun walletAbiProposal(): WalletAbiSessionProposal {
        return WalletAbiSessionProposal(
            pairingTopic = "pairing-topic",
            proposerPublicKey = "pubkey",
            name = "Example dApp",
            description = null,
            url = "https://example.app",
            icons = emptyList(),
            redirect = null,
            relayProtocol = "irn",
            requiredNamespaces = mapOf(
                WALLET_ABI_WALLETCONNECT_NAMESPACE to WalletAbiSessionNamespaceProposal(
                    chains = listOf("walabi:testnet-liquid"),
                    methods = listOf(
                        WALLET_ABI_METHOD_GET_SIGNER_RECEIVE_ADDRESS,
                        WALLET_ABI_METHOD_PROCESS_REQUEST,
                    ),
                    events = emptyList(),
                ),
            ),
            optionalNamespaces = emptyMap(),
            properties = null,
            scopedProperties = null,
            verifyContext = null,
        )
    }

    private fun coordinator(
        walletSettingsManager: WalletSettingsManager,
        walletConnectBridge: WalletAbiWalletConnectBridge,
    ): WalletAbiSessionCoordinator {
        val executionContextResolver = FakeExecutionContextResolver()
        val walletAbiProviderRunner = FakeWalletAbiProviderRunner()
        return WalletAbiSessionCoordinator(
            json = json,
            executionContextResolver = executionContextResolver,
            walletAbiImpactPreviewer = NoopWalletAbiImpactPreviewer(),
            walletAbiProcessor = WalletAbiProcessor(
                json = json,
                executionContextResolver = executionContextResolver,
                providerRunner = walletAbiProviderRunner,
            ),
            walletAbiResultPresenter = WalletAbiResultPresenter(),
            walletAbiProviderRunner = walletAbiProviderRunner,
            walletSettingsManager = walletSettingsManager,
            walletConnectBridge = walletConnectBridge,
        )
    }
}

private class ProposalBridge(
    private val proposal: WalletAbiSessionProposal,
    private val approvedSession: WalletAbiSessionInfo? = null,
) : WalletAbiWalletConnectBridge {
    private var listener: WalletAbiWalletConnectBridgeListener? = null
    var approvedTopic: String? = null
    private var sessions: List<WalletAbiSessionInfo> = emptyList()

    override suspend fun initialize() = Unit

    override fun setListener(listener: WalletAbiWalletConnectBridgeListener?) {
        this.listener = listener
    }

    override suspend fun pair(uri: String) {
        listener?.onSessionProposal(proposal)
    }

    override suspend fun getActiveSessions(): List<WalletAbiSessionInfo> = sessions

    override suspend fun getActiveSession(topic: String): WalletAbiSessionInfo? {
        return sessions.firstOrNull { it.topic == topic }
    }

    override suspend fun getPendingRequests(topic: String): List<WalletAbiSessionRequest> = emptyList()

    override suspend fun approveSession(
        proposal: WalletAbiSessionProposal,
        approval: WalletAbiSessionApproval,
    ) {
        approvedTopic = approvedSession?.topic
        sessions = listOfNotNull(approvedSession)
    }

    override suspend fun rejectSession(proposal: WalletAbiSessionProposal, reason: String) = Unit

    override suspend fun respondSuccess(topic: String, requestId: Long, resultJson: String) = Unit

    override suspend fun respondError(topic: String, requestId: Long, code: Int, message: String) = Unit

    override suspend fun disconnect(topic: String) = Unit
}
