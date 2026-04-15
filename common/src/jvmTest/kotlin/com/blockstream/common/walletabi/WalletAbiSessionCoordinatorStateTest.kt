package com.blockstream.common.walletabi

import com.blockstream.common.data.GreenWallet
import com.blockstream.common.data.WalletSerializable
import com.blockstream.common.gdk.GdkSession
import com.blockstream.common.managers.SettingsManager
import com.blockstream.common.managers.WalletSettingsManager
import com.blockstream.common.walletabi.transport.WalletAbiNetwork
import com.russhwolf.settings.PreferencesSettings
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import lwk.Network as LwkNetwork
import java.util.prefs.Preferences
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WalletAbiSessionCoordinatorStateTest {
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    @Test
    fun bindRestoresPersistedWalletAbiSessionFromActiveBridgeState() = runTest {
        val walletSettingsManager = walletSettingsManager()
        walletSettingsManager.setWalletAbiSessionState(
            walletId = "wallet-1",
            stateJson = json.encodeToString(
                WalletAbiPersistedSessionState(
                    topic = "topic-1",
                    chainId = "walabi:testnet-liquid",
                ),
            ),
        )
        val bridge = FakeWalletAbiWalletConnectBridge(
            sessions = listOf(
                walletAbiSessionInfo(
                    topic = "topic-1",
                    origin = "Example dApp",
                    methods = listOf(
                        WALLET_ABI_METHOD_GET_SIGNER_RECEIVE_ADDRESS,
                        WALLET_ABI_METHOD_PROCESS_REQUEST,
                    ),
                    chainId = "walabi:testnet-liquid",
                ),
            ),
        )
        val coordinator = coordinator(
            walletSettingsManager = walletSettingsManager,
            walletConnectBridge = bridge,
        )

        coordinator.bind(
            greenWallet = greenWallet("wallet-1"),
            session = mockk<GdkSession>(),
        )

        val connection = coordinator.state("wallet-1").value.activeConnection
        assertEquals("Example dApp", connection?.origin)
        assertEquals("testnet-liquid", connection?.network)
        assertEquals(WalletAbiSessionState.CONNECTED, connection?.state)
        assertEquals(
            setOf(WalletAbiGetterPermission.GET_SIGNER_RECEIVE_ADDRESS),
            connection?.approvedGetters,
        )
        assertEquals(1, bridge.initializeCalls)
    }

    @Test
    fun bindClearsStalePersistedWalletAbiSessionWhenBridgeHasNoMatchingSession() = runTest {
        val walletSettingsManager = walletSettingsManager()
        walletSettingsManager.setWalletAbiSessionState(
            walletId = "wallet-2",
            stateJson = json.encodeToString(
                WalletAbiPersistedSessionState(
                    topic = "missing-topic",
                    chainId = "walabi:liquid",
                ),
            ),
        )
        val coordinator = coordinator(
            walletSettingsManager = walletSettingsManager,
            walletConnectBridge = FakeWalletAbiWalletConnectBridge(),
        )

        coordinator.bind(
            greenWallet = greenWallet("wallet-2"),
            session = mockk<GdkSession>(),
        )

        assertNull(coordinator.state("wallet-2").value.activeConnection)
        assertNull(walletSettingsManager.getWalletAbiSessionState("wallet-2"))
    }

    private fun walletSettingsManager(): WalletSettingsManager {
        val settings = PreferencesSettings(
            Preferences.userRoot().node("wallet-abi-session-coordinator-test-${System.nanoTime()}"),
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

    private fun greenWallet(walletId: String): GreenWallet {
        return GreenWallet(
            wallet = WalletSerializable(
                id = walletId,
                name = "Wallet",
                xpub_hash_id = "xpub-$walletId",
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
        origin: String,
        methods: List<String>,
        chainId: String,
    ) = WalletAbiSessionInfo(
        topic = topic,
        expiry = 1_000L,
        name = origin,
        description = null,
        url = "https://example.app",
        icons = emptyList(),
        requiredNamespaces = mapOf(
            WALLET_ABI_WALLETCONNECT_NAMESPACE to WalletAbiSessionNamespaceProposal(
                chains = listOf(chainId),
                methods = methods,
                events = emptyList(),
            ),
        ),
        optionalNamespaces = emptyMap(),
        namespaces = mapOf(
            WALLET_ABI_WALLETCONNECT_NAMESPACE to WalletAbiSessionNamespace(
                chains = listOf(chainId),
                accounts = listOf("$chainId:pubkey"),
                methods = methods,
                events = emptyList(),
            ),
        ),
    )

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

private class FakeWalletAbiWalletConnectBridge(
    private val sessions: List<WalletAbiSessionInfo> = emptyList(),
) : WalletAbiWalletConnectBridge {
    var initializeCalls: Int = 0

    override suspend fun initialize() {
        initializeCalls += 1
    }

    override fun setListener(listener: WalletAbiWalletConnectBridgeListener?) = Unit

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

    override suspend fun respondSuccess(topic: String, requestId: Long, resultJson: String) = Unit

    override suspend fun respondError(topic: String, requestId: Long, code: Int, message: String) = Unit

    override suspend fun disconnect(topic: String) = Unit
}

internal class FakeExecutionContextResolver : WalletAbiExecutionContextResolving {
    override suspend fun resolveDirect(
        session: GdkSession,
        requestNetwork: WalletAbiNetwork,
        preferredAccountId: String?,
    ): WalletAbiExecutionContext {
        return context(session, requestNetwork)
    }

    override suspend fun resolveSessionRequest(
        incoming: GdkSession,
        requestNetwork: WalletAbiNetwork,
        preferredAccountId: String?,
    ): WalletAbiExecutionContext {
        return context(incoming, requestNetwork)
    }

    private fun context(
        session: GdkSession,
        requestNetwork: WalletAbiNetwork,
    ): WalletAbiExecutionContext {
        val account = com.blockstream.common.gdk.data.Account(
            networkInjected = com.blockstream.common.gdk.data.Network(
                network = com.blockstream.common.gdk.data.Network.GreenTestnetLiquid,
                name = "Liquid Testnet",
                isMainnet = false,
                isLiquid = true,
                isDevelopment = false,
                policyAsset = "policy-asset",
            ),
            gdkName = "Account 1",
            pointer = 0,
            type = com.blockstream.common.gdk.data.AccountType.BIP84_SEGWIT,
        )
        return WalletAbiExecutionContext(
            session = session,
            requestNetwork = requestNetwork,
            accounts = listOf(account),
            primaryAccount = account,
            lwkNetwork = LwkNetwork.testnet(),
            signerKind = WalletAbiSignerKind.SOFTWARE,
        )
    }
}

internal class FakeWalletAbiProviderRunner : WalletAbiProviderRunning {
    override suspend fun run(
        context: WalletAbiExecutionContext,
        request: com.blockstream.common.walletabi.transport.WalletAbiTxCreateRequest,
        requestJson: String,
    ): WalletAbiProviderRunResult {
        error("Not used in these tests")
    }

    override suspend fun runJsonRpcRequest(
        context: WalletAbiExecutionContext,
        requestEnvelopeJson: String,
    ): WalletAbiProviderJsonRpcRunResult {
        val (method, _) = parseWalletAbiJsonRpcDispatch(
            json = Json { ignoreUnknownKeys = true },
            requestEnvelopeJson = requestEnvelopeJson,
        )
        return WalletAbiProviderJsonRpcRunResult(
            resultJson = when (method) {
                WALLET_ABI_METHOD_GET_SIGNER_RECEIVE_ADDRESS -> {
                    """{"signer_receive_address":"tlq1qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqh5v5m2"}"""
                }

                WALLET_ABI_METHOD_GET_RAW_SIGNING_X_ONLY_PUBKEY -> {
                    """{"raw_signing_x_only_pubkey":"0123abcd"}"""
                }

                else -> error("Unexpected JSON-RPC method $method")
            },
        )
    }
}
