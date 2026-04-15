package com.blockstream.common.walletabi

import com.blockstream.common.data.GreenWallet
import com.blockstream.common.data.WalletSerializable
import com.blockstream.common.gdk.GdkSession
import com.blockstream.common.gdk.data.Account
import com.blockstream.common.gdk.data.AccountType
import com.blockstream.common.gdk.data.Network
import com.blockstream.common.gdk.data.PreviousAddresses
import com.blockstream.common.gdk.data.ValidateAddressees
import com.blockstream.common.managers.SettingsManager
import com.blockstream.common.managers.WalletSettingsManager
import com.blockstream.common.walletabi.transport.WalletAbiNetwork
import com.blockstream.common.walletabi.transport.WalletAbiOutputSchema
import com.blockstream.common.walletabi.transport.WalletAbiRuntimeParams
import com.blockstream.common.walletabi.transport.WalletAbiStatus
import com.blockstream.common.walletabi.transport.WalletAbiTransactionInfo
import com.blockstream.common.walletabi.transport.WalletAbiTxCreateRequest
import com.blockstream.common.walletabi.transport.WalletAbiTxCreateResponse
import com.russhwolf.settings.PreferencesSettings
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import lwk.OutPoint
import lwk.PsetBuilder
import lwk.PsetInputBuilder
import lwk.PsetOutputBuilder
import lwk.Script
import lwk.Txid
import lwk.Network as LwkNetwork
import java.util.prefs.Preferences
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WalletAbiSessionCoordinatorTransactionTest {
    private val json = Json {
        encodeDefaults = true
        explicitNulls = false
        ignoreUnknownKeys = true
    }

    @Test
    fun processRequestQueuesTransactionApprovalAndAllowsAccountSelection() = runTest {
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
        val bridge = TransactionRequestBridge(
            sessions = listOf(walletAbiSessionInfo(topic = "topic-1")),
        )
        val session = walletSession()
        val store = WalletAbiTransactionStore(database = manager.database, json = json)
        val coordinator = coordinator(
            walletSettingsManager = manager,
            walletConnectBridge = bridge,
            executionContextResolver = TransactionExecutionContextResolver(),
            walletAbiProviderRunner = TransactionWalletAbiProviderRunner(json),
            walletAbiTransactionStore = store,
        )

        coordinator.bind(
            greenWallet = greenWallet(),
            session = session,
        )
        bridge.emitRequest(
            walletAbiSessionRequest(
                topic = "topic-1",
                requestId = 10L,
                txRequest = explicitTxCreateRequest(requestId = "req-10"),
            ),
        )

        val initial = coordinator.awaitTransactionApproval("wallet-1")
        assertEquals(transactionAccount(name = "Account 1", pointer = 0L).id, initial.review.selectedAccountId)

        coordinator.selectTransactionAccount("wallet-1", transactionAccount(name = "Account 2", pointer = 16L).id)

        val updated = coordinator.awaitTransactionApproval("wallet-1")
        assertEquals(transactionAccount(name = "Account 2", pointer = 16L).id, updated.review.selectedAccountId)

        val outcome = coordinator.approveCurrentTransaction("wallet-1")

        assertEquals(
            WalletAbiActionOutcome.Success("Wallet ABI request processed: approved-txid"),
            outcome,
        )
        assertEquals(1, bridge.successResponses.size)
        assertEquals(
            WalletAbiTransactionRecordStatus.APPROVED,
            store.get(walletId = "wallet-1", txHash = "approved-txid")?.status,
        )
        assertNull(coordinator.state("wallet-1").value.overlay)
    }

    @Test
    fun resolveCurrentTransactionPreparesResolvedApproval() = runTest {
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
        val bridge = TransactionRequestBridge(
            sessions = listOf(walletAbiSessionInfo(topic = "topic-1")),
        )
        val session = walletSession()
        val store = WalletAbiTransactionStore(database = manager.database, json = json)
        val coordinator = coordinator(
            walletSettingsManager = manager,
            walletConnectBridge = bridge,
            executionContextResolver = TransactionExecutionContextResolver(),
            walletAbiProviderRunner = TransactionWalletAbiProviderRunner(json),
            walletAbiTransactionStore = store,
        )

        coordinator.bind(
            greenWallet = greenWallet(),
            session = session,
        )
        bridge.emitRequest(
            walletAbiSessionRequest(
                topic = "topic-1",
                requestId = 11L,
                txRequest = deferredAssetTxCreateRequest(requestId = "req-11"),
            ),
        )
        coordinator.awaitTransactionApproval("wallet-1")

        val resolveOutcome = coordinator.resolveCurrentTransaction("wallet-1")

        assertEquals(
            WalletAbiActionOutcome.Success("Wallet ABI transaction resolved"),
            resolveOutcome,
        )
        val resolved = assertIs<WalletAbiOverlayLook.TransactionApproval>(
            coordinator.state("wallet-1").value.overlay,
        )
        assertEquals(WalletAbiResolutionState.RESOLVED, resolved.review.resolutionState)

        val approveOutcome = coordinator.approveCurrentTransaction("wallet-1")

        assertEquals(
            WalletAbiActionOutcome.Success("Wallet ABI request approved"),
            approveOutcome,
        )
        assertEquals(1, bridge.successResponses.size)
        assertTrue(bridge.successResponses.single().resultJson.contains("resolved-txid"))
        assertEquals(
            WalletAbiTransactionRecordStatus.APPROVED,
            store.get(walletId = "wallet-1", txHash = "resolved-txid")?.status,
        )
        assertNull(coordinator.state("wallet-1").value.overlay)
    }

    @Test
    fun rejectCurrentTransactionRespondsWithWalletConnectError() = runTest {
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
        val bridge = TransactionRequestBridge(
            sessions = listOf(walletAbiSessionInfo(topic = "topic-1")),
        )
        val session = walletSession()
        val coordinator = coordinator(
            walletSettingsManager = manager,
            walletConnectBridge = bridge,
            executionContextResolver = TransactionExecutionContextResolver(),
            walletAbiProviderRunner = TransactionWalletAbiProviderRunner(json),
        )

        coordinator.bind(
            greenWallet = greenWallet(),
            session = session,
        )
        bridge.emitRequest(
            walletAbiSessionRequest(
                topic = "topic-1",
                requestId = 12L,
                txRequest = explicitTxCreateRequest(requestId = "req-12"),
            ),
        )
        coordinator.awaitTransactionApproval("wallet-1")

        val outcome = coordinator.rejectCurrentTransaction("wallet-1")

        assertEquals(
            WalletAbiActionOutcome.Success("Wallet ABI request rejected"),
            outcome,
        )
        assertEquals(1, bridge.errorResponses.size)
        assertEquals(4001, bridge.errorResponses.single().code)
        assertNull(coordinator.state("wallet-1").value.overlay)
    }

    private fun walletSession(): GdkSession {
        return mockk(relaxed = true) {
            coEvery { getPreviousAddresses(any(), any()) } returns PreviousAddresses()
            coEvery { validateAddressee(any(), any()) } returns ValidateAddressees(
                addressees = emptyList(),
                isValid = false,
            )
        }
    }

    private fun coordinator(
        walletSettingsManager: WalletSettingsManager,
        walletConnectBridge: WalletAbiWalletConnectBridge,
        executionContextResolver: WalletAbiExecutionContextResolving,
        walletAbiProviderRunner: WalletAbiProviderRunning,
        walletAbiTransactionStore: WalletAbiTransactionStore? = null,
    ): WalletAbiSessionCoordinator {
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
            walletAbiTransactionStore = walletAbiTransactionStore,
        )
    }

    private fun walletSettingsManager(): WalletSettingsManager {
        val settings = PreferencesSettings(
            Preferences.userRoot().node("wallet-abi-session-transaction-test-${System.nanoTime()}"),
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

    private fun walletAbiSessionInfo(topic: String): WalletAbiSessionInfo {
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
                    methods = listOf(WALLET_ABI_METHOD_PROCESS_REQUEST),
                    events = emptyList(),
                ),
            ),
            optionalNamespaces = emptyMap(),
            namespaces = mapOf(
                WALLET_ABI_WALLETCONNECT_NAMESPACE to WalletAbiSessionNamespace(
                    chains = listOf("walabi:testnet-liquid"),
                    accounts = listOf("walabi:testnet-liquid:0123abcd"),
                    methods = listOf(WALLET_ABI_METHOD_PROCESS_REQUEST),
                    events = emptyList(),
                ),
            ),
        )
    }

    private fun walletAbiSessionRequest(
        topic: String,
        requestId: Long,
        txRequest: WalletAbiTxCreateRequest,
    ): WalletAbiSessionRequest {
        return WalletAbiSessionRequest(
            topic = topic,
            chainId = "walabi:testnet-liquid",
            requestId = requestId,
            method = WALLET_ABI_METHOD_PROCESS_REQUEST,
            paramsJson = json.encodeToString(txRequest),
            peerName = "Example dApp",
            peerDescription = null,
            peerUrl = "https://example.app",
            peerIcons = emptyList(),
            verifyContext = null,
        )
    }

    private fun explicitTxCreateRequest(requestId: String): WalletAbiTxCreateRequest {
        return WalletAbiTxCreateRequest(
            abiVersion = "wallet-abi-0.1",
            requestId = requestId,
            network = WalletAbiNetwork.TESTNET_LIQUID,
            params = WalletAbiRuntimeParams(
                inputs = emptyList(),
                outputs = listOf(
                    WalletAbiOutputSchema(
                        id = "external-0",
                        amountSat = 2_500L,
                        lock = buildJsonObject {
                            put("type", "script")
                            put("script", "0014aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
                        },
                        asset = buildJsonObject {
                            put("asset_id", "policy-asset")
                        },
                        blinder = buildJsonObject {
                            put("type", "rand")
                        },
                    ),
                ),
            ),
            broadcast = false,
        )
    }

    private fun deferredAssetTxCreateRequest(requestId: String): WalletAbiTxCreateRequest {
        return explicitTxCreateRequest(requestId).copy(
            params = WalletAbiRuntimeParams(
                inputs = emptyList(),
                outputs = listOf(
                    WalletAbiOutputSchema(
                        id = "external-0",
                        amountSat = 2_500L,
                        lock = buildJsonObject {
                            put("type", "script")
                            put("script", "0014aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
                        },
                        asset = buildJsonObject {
                            put("type", "issuance_asset")
                        },
                        blinder = buildJsonObject {
                            put("type", "rand")
                        },
                    ),
                ),
            ),
        )
    }
}

private suspend fun WalletAbiSessionCoordinator.awaitTransactionApproval(
    walletId: String,
): WalletAbiOverlayLook.TransactionApproval {
    return withTimeout(5_000) {
        while (true) {
            val overlay = state(walletId).value.overlay
            if (overlay is WalletAbiOverlayLook.TransactionApproval) {
                return@withTimeout overlay
            }
            yield()
        }
        error("unreachable")
    }
}

private class TransactionExecutionContextResolver : WalletAbiExecutionContextResolving {
    private val accounts = listOf(
        transactionAccount(
            name = "Account 1",
            pointer = 0L,
        ),
        transactionAccount(
            name = "Account 2",
            pointer = 16L,
        ),
    )

    override suspend fun resolveDirect(
        session: GdkSession,
        requestNetwork: WalletAbiNetwork,
        preferredAccountId: String?,
    ): WalletAbiExecutionContext {
        return context(session, requestNetwork, preferredAccountId)
    }

    override suspend fun resolveSessionRequest(
        incoming: GdkSession,
        requestNetwork: WalletAbiNetwork,
        preferredAccountId: String?,
    ): WalletAbiExecutionContext {
        return context(incoming, requestNetwork, preferredAccountId)
    }

    private fun context(
        session: GdkSession,
        requestNetwork: WalletAbiNetwork,
        preferredAccountId: String?,
    ): WalletAbiExecutionContext {
        val primaryAccount = accounts.firstOrNull { it.id == preferredAccountId } ?: accounts.first()
        return WalletAbiExecutionContext(
            session = session,
            requestNetwork = requestNetwork,
            accounts = accounts,
            primaryAccount = primaryAccount,
            lwkNetwork = LwkNetwork.testnet(),
            signerKind = WalletAbiSignerKind.SOFTWARE,
        )
    }
}

private class TransactionWalletAbiProviderRunner(
    private val json: Json,
) : WalletAbiProviderRunning {
    override suspend fun run(
        context: WalletAbiExecutionContext,
        request: WalletAbiTxCreateRequest,
        requestJson: String,
    ): WalletAbiProviderRunResult {
        val response = if (walletAbiRequestNeedsResolution(request)) {
            WalletAbiTxCreateResponse(
                abiVersion = request.abiVersion,
                requestId = request.requestId,
                network = request.network,
                status = WalletAbiStatus.OK,
                transaction = WalletAbiTransactionInfo(
                    txHex = resolvedTransactionHex(),
                    txid = "resolved-txid",
                ),
            )
        } else {
            WalletAbiTxCreateResponse(
                abiVersion = request.abiVersion,
                requestId = request.requestId,
                network = request.network,
                status = WalletAbiStatus.OK,
                transaction = WalletAbiTransactionInfo(
                    txHex = resolvedTransactionHex(),
                    txid = "approved-txid",
                ),
            )
        }
        return WalletAbiProviderRunResult(
            response = response,
            responseJson = json.encodeToString(response),
        )
    }

    override suspend fun runJsonRpcRequest(
        context: WalletAbiExecutionContext,
        requestEnvelopeJson: String,
    ): WalletAbiProviderJsonRpcRunResult {
        val (method, _) = parseWalletAbiJsonRpcDispatch(
            json = json,
            requestEnvelopeJson = requestEnvelopeJson,
        )
        return WalletAbiProviderJsonRpcRunResult(
            resultJson = when (method) {
                WALLET_ABI_METHOD_GET_RAW_SIGNING_X_ONLY_PUBKEY -> {
                    """{"raw_signing_x_only_pubkey":"0123abcd"}"""
                }

                WALLET_ABI_METHOD_GET_SIGNER_RECEIVE_ADDRESS -> {
                    """{"signer_receive_address":"tlq1qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqh5v5m2"}"""
                }

                else -> error("Unexpected JSON-RPC method $method")
            },
        )
    }

    private fun resolvedTransactionHex(): String {
        return PsetBuilder.newV2().run {
            addInput(
                PsetInputBuilder.fromPrevout(
                    OutPoint.fromParts(
                        Txid.fromString("0000000000000000000000000000000000000000000000000000000000000001"),
                        0u,
                    ),
                ).build(),
            )
            addOutput(
                PsetOutputBuilder.newExplicit(
                    scriptPubkey = Script("0014aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"),
                    satoshi = 2_500u,
                    asset = "5ac9f65c6cbeca7c9c0f74c684e1e4e79d7bb7aa6fe8f5a3303ad42d00000001",
                ).build(),
            )
            build().extractTx().toString()
        }
    }
}

private class TransactionRequestBridge(
    private val sessions: List<WalletAbiSessionInfo>,
) : WalletAbiWalletConnectBridge {
    private var listener: WalletAbiWalletConnectBridgeListener? = null
    val successResponses = mutableListOf<TransactionWalletConnectSuccessResponse>()
    val errorResponses = mutableListOf<TransactionWalletConnectErrorResponse>()

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
        successResponses += TransactionWalletConnectSuccessResponse(topic, requestId, resultJson)
    }

    override suspend fun respondError(topic: String, requestId: Long, code: Int, message: String) {
        errorResponses += TransactionWalletConnectErrorResponse(topic, requestId, code, message)
    }

    override suspend fun disconnect(topic: String) = Unit
}

private fun transactionAccount(
    name: String,
    pointer: Long,
): Account {
    return Account(
        networkInjected = Network(
            network = Network.GreenTestnetLiquid,
            name = "Liquid Testnet",
            isMainnet = false,
            isLiquid = true,
            isDevelopment = false,
            policyAsset = "policy-asset",
        ),
        gdkName = name,
        pointer = pointer,
        type = AccountType.BIP84_SEGWIT,
    )
}

private data class TransactionWalletConnectSuccessResponse(
    val topic: String,
    val requestId: Long,
    val resultJson: String,
)

private data class TransactionWalletConnectErrorResponse(
    val topic: String,
    val requestId: Long,
    val code: Int,
    val message: String,
)
