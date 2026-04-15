package com.blockstream.common.walletabi

import com.blockstream.common.data.GreenWallet
import com.blockstream.common.data.WalletSerializable
import com.blockstream.common.gdk.GdkSession
import com.blockstream.common.managers.SettingsManager
import com.blockstream.common.managers.WalletSettingsManager
import com.russhwolf.settings.PreferencesSettings
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.prefs.Preferences
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WalletAbiSessionCoordinatorGetterTest {
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    @Test
    fun autoApprovedGetterRespondsWithoutOverlay() = runTest {
        val manager = walletSettingsManager()
        manager.setWalletAbiSessionState(
            walletId = "wallet-1",
            stateJson = json.encodeToString(
                WalletAbiPersistedSessionState(
                    topic = "topic-1",
                    chainId = "walabi:testnet-liquid",
                    approvedGetters = setOf(WalletAbiGetterPermission.GET_SIGNER_RECEIVE_ADDRESS),
                ),
            ),
        )
        val bridge = GetterRequestBridge(
            sessions = listOf(
                walletAbiSessionInfo(
                    topic = "topic-1",
                    methods = listOf(WALLET_ABI_METHOD_GET_SIGNER_RECEIVE_ADDRESS),
                ),
            ),
        )
        val coordinator = coordinator(
            walletSettingsManager = manager,
            walletConnectBridge = bridge,
        )

        coordinator.bind(
            greenWallet = greenWallet(),
            session = mockk<GdkSession>(),
        )
        bridge.emitRequest(
            walletAbiSessionRequest(
                topic = "topic-1",
                requestId = 7L,
                method = WALLET_ABI_METHOD_GET_SIGNER_RECEIVE_ADDRESS,
            ),
        )

        assertNull(coordinator.state("wallet-1").value.overlay)
        assertEquals(1, bridge.successResponses.size)
        assertTrue(bridge.successResponses.single().resultJson.contains("signer_receive_address"))
    }

    @Test
    fun unapprovedGetterQueuesOverlayAndPersistsApproval() = runTest {
        val manager = walletSettingsManager()
        manager.setWalletAbiSessionState(
            walletId = "wallet-1",
            stateJson = json.encodeToString(
                WalletAbiPersistedSessionState(
                    topic = "topic-1",
                    chainId = "walabi:testnet-liquid",
                ),
            ),
        )
        val bridge = GetterRequestBridge(
            sessions = listOf(
                walletAbiSessionInfo(
                    topic = "topic-1",
                    methods = listOf(WALLET_ABI_METHOD_PROCESS_REQUEST),
                ),
            ),
        )
        val coordinator = coordinator(
            walletSettingsManager = manager,
            walletConnectBridge = bridge,
        )

        coordinator.bind(
            greenWallet = greenWallet(),
            session = mockk<GdkSession>(),
        )
        bridge.emitRequest(
            walletAbiSessionRequest(
                topic = "topic-1",
                requestId = 8L,
                method = WALLET_ABI_METHOD_GET_RAW_SIGNING_X_ONLY_PUBKEY,
            ),
        )

        val overlay = assertIs<WalletAbiOverlayLook.GetterApproval>(
            coordinator.state("wallet-1").value.overlay,
        )
        assertEquals("Example dApp", overlay.origin)
        assertEquals(WALLET_ABI_METHOD_GET_RAW_SIGNING_X_ONLY_PUBKEY, overlay.method)
        assertEquals("0123abcd", overlay.value)

        val outcome = coordinator.approveCurrentOverlay("wallet-1")

        assertEquals(
            WalletAbiActionOutcome.Success("Wallet ABI getter approved"),
            outcome,
        )
        assertEquals(1, bridge.successResponses.size)
        assertNull(coordinator.state("wallet-1").value.overlay)
        assertTrue(
            manager.getWalletAbiSessionState("wallet-1")!!
                .contains("get_raw_signing_x_only_pubkey"),
        )
    }

    @Test
    fun duplicateGetterRequestRejectsOnlyOnce() = runTest {
        val manager = walletSettingsManager()
        manager.setWalletAbiSessionState(
            walletId = "wallet-1",
            stateJson = json.encodeToString(
                WalletAbiPersistedSessionState(
                    topic = "topic-1",
                    chainId = "walabi:testnet-liquid",
                ),
            ),
        )
        val bridge = GetterRequestBridge(
            sessions = listOf(
                walletAbiSessionInfo(
                    topic = "topic-1",
                    methods = listOf(WALLET_ABI_METHOD_PROCESS_REQUEST),
                ),
            ),
        )
        val coordinator = coordinator(
            walletSettingsManager = manager,
            walletConnectBridge = bridge,
        )

        coordinator.bind(
            greenWallet = greenWallet(),
            session = mockk<GdkSession>(),
        )
        val request = walletAbiSessionRequest(
            topic = "topic-1",
            requestId = 9L,
            method = WALLET_ABI_METHOD_GET_RAW_SIGNING_X_ONLY_PUBKEY,
        )
        bridge.emitRequest(request)
        bridge.emitRequest(request)

        assertIs<WalletAbiOverlayLook.GetterApproval>(coordinator.state("wallet-1").value.overlay)

        val outcome = coordinator.rejectCurrentOverlay("wallet-1")

        assertEquals(
            WalletAbiActionOutcome.Success("Wallet ABI request rejected"),
            outcome,
        )
        assertEquals(1, bridge.errorResponses.size)
        assertNull(coordinator.state("wallet-1").value.overlay)
    }

    private fun walletSettingsManager(): WalletSettingsManager {
        val settings = PreferencesSettings(
            Preferences.userRoot().node("wallet-abi-session-getter-test-${System.nanoTime()}"),
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

    private fun walletAbiSessionInfo(
        topic: String,
        methods: List<String>,
    ): WalletAbiSessionInfo {
        return WalletAbiSessionInfo(
            topic = topic,
            expiry = 1_000L,
            name = "Example dApp",
            description = null,
            url = "https://example.app",
            icons = emptyList(),
            requiredNamespaces = mapOf(
                WALLET_ABI_WALLETCONNECT_NAMESPACE to WalletAbiSessionNamespaceProposal(
                    chains = listOf("walabi:testnet-liquid"),
                    methods = methods,
                    events = emptyList(),
                ),
            ),
            optionalNamespaces = emptyMap(),
            namespaces = mapOf(
                WALLET_ABI_WALLETCONNECT_NAMESPACE to WalletAbiSessionNamespace(
                    chains = listOf("walabi:testnet-liquid"),
                    accounts = listOf("walabi:testnet-liquid:0123abcd"),
                    methods = methods,
                    events = emptyList(),
                ),
            ),
        )
    }

    private fun walletAbiSessionRequest(
        topic: String,
        requestId: Long,
        method: String,
    ): WalletAbiSessionRequest {
        return WalletAbiSessionRequest(
            topic = topic,
            chainId = "walabi:testnet-liquid",
            requestId = requestId,
            method = method,
            paramsJson = "{}",
            peerName = "Example dApp",
            peerDescription = null,
            peerUrl = "https://example.app",
            peerIcons = emptyList(),
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

private class GetterRequestBridge(
    private val sessions: List<WalletAbiSessionInfo>,
) : WalletAbiWalletConnectBridge {
    private var listener: WalletAbiWalletConnectBridgeListener? = null
    val successResponses = mutableListOf<WalletConnectSuccessResponse>()
    val errorResponses = mutableListOf<WalletConnectErrorResponse>()

    override suspend fun initialize() = Unit

    override fun setListener(listener: WalletAbiWalletConnectBridgeListener?) {
        this.listener = listener
    }

    suspend fun emitRequest(request: WalletAbiSessionRequest) {
        listener?.onSessionRequest(request)
    }

    override suspend fun pair(uri: String) = Unit

    override suspend fun getActiveSessions(): List<WalletAbiSessionInfo> = sessions

    override suspend fun getActiveSession(topic: String): WalletAbiSessionInfo? {
        return sessions.firstOrNull { it.topic == topic }
    }

    override suspend fun getPendingRequests(topic: String): List<WalletAbiSessionRequest> = emptyList()

    override suspend fun approveSession(
        proposal: WalletAbiSessionProposal,
        approval: WalletAbiSessionApproval,
    ) = Unit

    override suspend fun rejectSession(proposal: WalletAbiSessionProposal, reason: String) = Unit

    override suspend fun respondSuccess(topic: String, requestId: Long, resultJson: String) {
        successResponses += WalletConnectSuccessResponse(
            topic = topic,
            requestId = requestId,
            resultJson = resultJson,
        )
    }

    override suspend fun respondError(topic: String, requestId: Long, code: Int, message: String) {
        errorResponses += WalletConnectErrorResponse(
            topic = topic,
            requestId = requestId,
            code = code,
            message = message,
        )
    }

    override suspend fun disconnect(topic: String) = Unit
}

private data class WalletConnectSuccessResponse(
    val topic: String,
    val requestId: Long,
    val resultJson: String,
)

private data class WalletConnectErrorResponse(
    val topic: String,
    val requestId: Long,
    val code: Int,
    val message: String,
)
