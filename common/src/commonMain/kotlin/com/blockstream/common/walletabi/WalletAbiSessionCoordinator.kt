package com.blockstream.common.walletabi

import com.blockstream.common.data.GreenWallet
import com.blockstream.common.gdk.GdkSession
import com.blockstream.common.gdk.params.BroadcastTransactionParams
import com.blockstream.common.managers.WalletSettingsManager
import com.blockstream.common.walletabi.transport.WalletAbiNetwork
import com.blockstream.common.walletabi.transport.WalletAbiTxCreateRequest
import com.blockstream.common.walletabi.transport.WalletAbiTxCreateResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

private const val WALLET_ABI_USER_REJECTED_MESSAGE =
    "WalletConnect Wallet ABI request was rejected by user"
private const val WALLET_ABI_USER_REJECTED_RPC_CODE = 4001
private const val WALLET_ABI_UNSUPPORTED_METHOD_RPC_CODE = -32_601
private const val WALLET_ABI_PROCESSING_FAILED_RPC_CODE = -32_000
private const val WALLET_ABI_APPROVAL_POLL_ATTEMPTS = 20
private const val WALLET_ABI_APPROVAL_POLL_DELAY_MS = 150L

internal data class WalletAbiConnectionLook(
    val origin: String?,
    val network: String?,
    val state: WalletAbiSessionState?,
    val approvedGetters: Set<WalletAbiGetterPermission>,
)

internal sealed interface WalletAbiOverlayLook {
    data class SessionProposalApproval(
        val origin: String,
        val network: String,
        val requestedMethods: List<String>,
        val autoApprovedGetters: Set<WalletAbiGetterPermission>,
        val warning: String?,
        val willReplaceExistingConnection: Boolean,
    ) : WalletAbiOverlayLook

    data class ConnectionEstablished(
        val origin: String?,
        val network: String?,
        val replacedExistingConnection: Boolean,
    ) : WalletAbiOverlayLook

    data class GetterApproval(
        val origin: String,
        val requestId: String,
        val method: String,
        val value: String,
        val warning: String,
        val network: String?,
    ) : WalletAbiOverlayLook

    data class TransactionApproval(
        val review: WalletAbiTransactionReviewLook,
    ) : WalletAbiOverlayLook

    data class Error(
        val message: String,
    ) : WalletAbiOverlayLook
}

internal data class WalletAbiSessionUiState(
    val activeConnection: WalletAbiConnectionLook? = null,
    val overlay: WalletAbiOverlayLook? = null,
)

internal sealed interface WalletAbiActionOutcome {
    data class Success(val message: String) : WalletAbiActionOutcome
    data class Error(val throwable: Throwable) : WalletAbiActionOutcome
}

private sealed interface WalletAbiPendingItem {
    data class SessionProposalApproval(
        val proposal: WalletAbiSessionProposal,
        val chainId: String,
        val requestedMethods: List<String>,
        val origin: String,
        val warning: String?,
        val willReplaceExistingConnection: Boolean,
    ) : WalletAbiPendingItem

    data class ConnectionEstablished(
        val origin: String?,
        val network: String?,
        val replacedExistingConnection: Boolean,
    ) : WalletAbiPendingItem

    data class GetterApproval(
        val request: WalletAbiSessionRequest,
        val permission: WalletAbiGetterPermission,
        val requestNetwork: WalletAbiNetwork,
        val resultJson: String,
        val value: String,
        val warning: String,
    ) : WalletAbiPendingItem

    data class TransactionApproval(
        val request: WalletAbiSessionRequest,
        val origin: String,
        val requestPayloadJson: String,
        val txRequest: WalletAbiTxCreateRequest,
        val requestNetwork: WalletAbiNetwork,
        val selectedAccountId: String,
        val review: WalletAbiTransactionReviewLook,
        val preparedResolution: WalletAbiPreparedResolution? = null,
    ) : WalletAbiPendingItem

    data class Error(
        val message: String,
    ) : WalletAbiPendingItem
}

private data class WalletAbiPreparedResolution(
    val response: WalletAbiTxCreateResponse,
    val responseJson: String,
    val txHex: String,
    val reviewRequest: WalletAbiTxCreateRequest,
)

private data class WalletAbiRuntimeState(
    var persistedState: WalletAbiPersistedSessionState? = null,
    var persistedLoaded: Boolean = false,
    var boundSession: GdkSession? = null,
    var activeSession: WalletAbiSessionInfo? = null,
    var pendingPairingTopic: String? = null,
    var currentItem: WalletAbiPendingItem? = null,
    val queuedItems: ArrayDeque<WalletAbiPendingItem> = ArrayDeque(),
    val completedRequestKeys: MutableSet<String> = linkedSetOf(),
)

internal class WalletAbiSessionCoordinator(
    private val json: Json,
    private val executionContextResolver: WalletAbiExecutionContextResolving,
    private val walletAbiImpactPreviewer: WalletAbiImpactPreviewing,
    private val walletAbiProcessor: WalletAbiProcessor,
    private val walletAbiResultPresenter: WalletAbiResultPresenter,
    private val walletAbiProviderRunner: WalletAbiProviderRunning,
    private val walletSettingsManager: WalletSettingsManager,
    private val walletConnectBridge: WalletAbiWalletConnectBridge,
    private val walletAbiTransactionStore: WalletAbiTransactionStore? = null,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val runtimes = mutableMapOf<String, WalletAbiRuntimeState>()
    private val states = mutableMapOf<String, MutableStateFlow<WalletAbiSessionUiState>>()
    private var walletConnectInitialized = false

    init {
        walletConnectBridge.setListener(
            object : WalletAbiWalletConnectBridgeListener {
                override fun onSessionProposal(proposal: WalletAbiSessionProposal) {
                    scope.launch(start = CoroutineStart.UNDISPATCHED) {
                        routeSessionProposal(proposal)
                    }
                }

                override fun onSessionRequest(request: WalletAbiSessionRequest) {
                    scope.launch(start = CoroutineStart.UNDISPATCHED) {
                        routeSessionRequest(request)
                    }
                }

                override fun onSessionDelete(topic: String, reason: String) {
                    scope.launch(start = CoroutineStart.UNDISPATCHED) {
                        handleSessionDelete(topic, reason)
                    }
                }

                override fun onSessionExtend(session: WalletAbiSessionInfo) {
                    scope.launch(start = CoroutineStart.UNDISPATCHED) {
                        handleSessionExtend(session)
                    }
                }

                override fun onError(message: String) {
                    scope.launch(start = CoroutineStart.UNDISPATCHED) {
                        handleWalletConnectError(message)
                    }
                }
            },
        )
    }

    fun state(walletId: String): StateFlow<WalletAbiSessionUiState> {
        return stateFlow(walletId).asStateFlow()
    }

    suspend fun bind(
        greenWallet: GreenWallet,
        session: GdkSession,
    ) {
        val runtime = runtime(greenWallet.id)
        runtime.boundSession = session

        if (!runtime.persistedLoaded) {
            runtime.persistedLoaded = true
            runtime.persistedState = loadPersistedSessionStateOrNull(greenWallet.id)
        }

        ensureWalletConnectInitialized()
        syncRuntimeWithWalletConnect(greenWallet.id)
        refreshState(greenWallet.id)
    }

    suspend fun pairWalletConnectUri(
        greenWallet: GreenWallet,
        session: GdkSession,
        input: String,
    ) {
        bind(greenWallet, session)
        ensureWalletConnectInitialized()

        val walletConnectUri = normalizeWalletConnectPairingUri(input)
        val pairingTopic = walletConnectPairingTopic(walletConnectUri)
        val runtime = runtime(greenWallet.id)
        runtime.pendingPairingTopic = pairingTopic

        runCatching {
            walletConnectBridge.pair(walletConnectUri)
        }.onFailure { error ->
            runtime.pendingPairingTopic = null
            throw error
        }
    }

    suspend fun dismissOverlay(walletId: String) {
        val runtime = runtime(walletId)
        when (runtime.currentItem) {
            is WalletAbiPendingItem.ConnectionEstablished,
            is WalletAbiPendingItem.Error,
            null,
            -> {
                runtime.currentItem = runtime.queuedItems.removeFirstOrNull()
                refreshState(walletId)
            }

            else -> Unit
        }
    }

    suspend fun approveCurrentOverlay(walletId: String): WalletAbiActionOutcome? {
        val runtime = runtime(walletId)
        return when (val item = runtime.currentItem) {
            is WalletAbiPendingItem.SessionProposalApproval -> approveSessionProposal(walletId, item)
            is WalletAbiPendingItem.GetterApproval -> approveGetter(walletId, item)
            is WalletAbiPendingItem.TransactionApproval -> null
            is WalletAbiPendingItem.ConnectionEstablished -> {
                dismissOverlay(walletId)
                null
            }

            is WalletAbiPendingItem.Error -> {
                dismissOverlay(walletId)
                null
            }

            null -> null
        }
    }

    suspend fun rejectCurrentOverlay(walletId: String): WalletAbiActionOutcome? {
        val runtime = runtime(walletId)
        return when (val item = runtime.currentItem) {
            is WalletAbiPendingItem.SessionProposalApproval -> rejectSessionProposal(walletId, item)
            is WalletAbiPendingItem.GetterApproval -> rejectSessionRequest(walletId, item.request)
            is WalletAbiPendingItem.TransactionApproval -> rejectTransaction(walletId, item)
            is WalletAbiPendingItem.ConnectionEstablished -> {
                dismissOverlay(walletId)
                null
            }

            is WalletAbiPendingItem.Error -> {
                dismissOverlay(walletId)
                null
            }

            null -> null
        }
    }

    suspend fun disconnectActiveSession(walletId: String): WalletAbiActionOutcome {
        val runtime = runtime(walletId)
        val topic = runtime.activeSession?.topic ?: runtime.persistedState?.topic
            ?: return WalletAbiActionOutcome.Error(
                IllegalStateException("No WalletConnect Wallet ABI session is active"),
            )

        return runCatching {
            walletConnectBridge.disconnect(topic)
            forgetSession(
                walletId = walletId,
                runtime = runtime,
                topic = topic,
            )
            WalletAbiActionOutcome.Success("WalletConnect Wallet ABI session disconnected")
        }.getOrElse { error ->
            WalletAbiActionOutcome.Error(error)
        }
    }

    private suspend fun loadPersistedSessionStateOrNull(
        walletId: String,
    ): WalletAbiPersistedSessionState? {
        val stateJson = walletSettingsManager.getWalletAbiSessionState(walletId) ?: return null
        return runCatching {
            json.decodeFromString<WalletAbiPersistedSessionState>(stateJson)
        }.getOrElse {
            walletSettingsManager.clearWalletAbiSessionState(walletId)
            null
        }
    }

    private suspend fun persistSession(
        walletId: String,
        runtime: WalletAbiRuntimeState = runtime(walletId),
        session: WalletAbiSessionInfo,
        approvedGetters: Set<WalletAbiGetterPermission>,
    ) {
        val persistedState = WalletAbiPersistedSessionState(
            topic = session.topic,
            chainId = session.walletAbiChainId()
                ?: runtime.persistedState?.chainId
                ?: throw IllegalStateException("Wallet ABI session is missing chain information"),
            originHint = session.displayOrigin(),
            approvedGetters = mergeWalletAbiApprovedGetters(
                approvedGetters = approvedGetters,
                requestedMethods = session.walletAbiMethods(),
            ),
        )

        runtime.persistedState = persistedState
        walletSettingsManager.setWalletAbiSessionState(
            walletId = walletId,
            stateJson = json.encodeToString(persistedState),
        )
    }

    private suspend fun forgetSession(
        walletId: String,
        runtime: WalletAbiRuntimeState = runtime(walletId),
        topic: String,
    ) {
        if (runtime.activeSession?.topic == topic) {
            runtime.activeSession = null
        }
        if (runtime.persistedState?.topic == topic) {
            runtime.persistedState = null
            walletSettingsManager.clearWalletAbiSessionState(walletId)
        }
        runtime.removeItemsForTopic(topic)
        refreshState(walletId)
    }

    private suspend fun routeSessionProposal(proposal: WalletAbiSessionProposal) {
        val walletId = runtimes.entries.firstOrNull { it.value.pendingPairingTopic == proposal.pairingTopic }
            ?.key
            ?: runtimes.entries.firstOrNull { it.value.boundSession != null }?.key
            ?: return

        handleSessionProposal(walletId, proposal)
    }

    private suspend fun routeSessionRequest(request: WalletAbiSessionRequest) {
        val walletId = runtimes.entries.firstOrNull { entry ->
            entry.value.persistedState?.topic == request.topic || entry.value.activeSession?.topic == request.topic
        }?.key ?: return

        handleIncomingSessionRequest(walletId, request)
    }

    private suspend fun handleSessionProposal(
        walletId: String,
        proposal: WalletAbiSessionProposal,
    ) {
        val runtime = runtime(walletId)
        val validation = runCatching { validateWalletAbiProposal(proposal) }.getOrElse { error ->
            runtime.pendingPairingTopic = null
            walletConnectBridge.rejectSession(
                proposal = proposal,
                reason = error.message ?: "Unsupported WalletConnect Wallet ABI proposal",
            )
            enqueueItem(walletId, WalletAbiPendingItem.Error(error.message ?: "Unsupported WalletConnect Wallet ABI proposal"))
            return
        }

        val requestNetwork = validation.chainId.toWalletAbiNetworkFromChainId()
        runtime.boundSession?.let { session ->
            runCatching {
                executionContextResolver.resolveDirect(
                    session = session,
                    requestNetwork = requestNetwork,
                )
            }.onFailure { error ->
                runtime.pendingPairingTopic = null
                val message = error.message ?: "Connected wallet cannot satisfy this WalletConnect chain"
                walletConnectBridge.rejectSession(proposal = proposal, reason = message)
                enqueueItem(walletId, WalletAbiPendingItem.Error(message))
                return
            }
        }

        enqueueItem(
            walletId = walletId,
            item = WalletAbiPendingItem.SessionProposalApproval(
                proposal = proposal,
                chainId = validation.chainId,
                requestedMethods = validation.requestedMethods,
                origin = proposal.displayOrigin(),
                warning = proposal.verifyWarning(),
                willReplaceExistingConnection = runtime.hasActiveConnection(),
            ),
        )
    }

    private suspend fun handleIncomingSessionRequest(
        walletId: String,
        request: WalletAbiSessionRequest,
    ) {
        val runtime = runtime(walletId)
        val session = runtime.boundSession ?: return
        val activeSession = runtime.activeSession
            ?: walletConnectBridge.getActiveSession(request.topic)
                ?.takeIf(::isWalletAbiSession)
            ?: return
        runtime.activeSession = activeSession

        if (runtime.hasSeenRequest(request.requestKey())) {
            return
        }

        val requestNetwork = request.chainId?.toWalletAbiNetworkFromChainId()
            ?: activeSession.walletAbiNetworkOrNull()
            ?: throw IllegalStateException("Wallet ABI session request is missing chain information")
        val origin = request.displayOrigin(runtime.persistedState?.originHint)

        when (request.method) {
            WALLET_ABI_METHOD_GET_SIGNER_RECEIVE_ADDRESS -> {
                handleGetterRequest(
                    walletId = walletId,
                    session = session,
                    request = request,
                    requestNetwork = requestNetwork,
                    permission = WalletAbiGetterPermission.GET_SIGNER_RECEIVE_ADDRESS,
                    warning = "Sharing a receive address lets the dApp monitor deposits and correlate wallet activity tied to that address.",
                )
            }

            WALLET_ABI_METHOD_GET_RAW_SIGNING_X_ONLY_PUBKEY -> {
                handleGetterRequest(
                    walletId = walletId,
                    session = session,
                    request = request,
                    requestNetwork = requestNetwork,
                    permission = WalletAbiGetterPermission.GET_RAW_SIGNING_X_ONLY_PUBKEY,
                    warning = "This shares a stable wallet-linked identifier. It is not a spending key, but it can still be used for correlation.",
                )
            }

            WALLET_ABI_METHOD_PROCESS_REQUEST -> {
                val txRequest = json.decodeFromString<WalletAbiTxCreateRequest>(request.paramsJson)
                val context = executionContextResolver.resolveSessionRequest(
                    incoming = session,
                    requestNetwork = txRequest.network,
                )
                enqueueItem(
                    walletId = walletId,
                    item = WalletAbiPendingItem.TransactionApproval(
                        request = request,
                        origin = origin,
                        requestPayloadJson = json.encodeToString(txRequest),
                        txRequest = txRequest,
                        requestNetwork = txRequest.network,
                        selectedAccountId = context.primaryAccount.id,
                        review = buildTransactionReview(
                            origin = origin,
                            txRequest = txRequest,
                            context = context,
                        ),
                    ),
                )
            }

            else -> {
                respondErrorOrExpire(
                    runtime = runtime,
                    topic = request.topic,
                    requestId = request.requestId,
                    code = WALLET_ABI_UNSUPPORTED_METHOD_RPC_CODE,
                    message = "Unsupported Wallet ABI method '${request.method}'",
                )
                refreshState(walletId)
            }
        }
    }

    private suspend fun handleGetterRequest(
        walletId: String,
        session: GdkSession,
        request: WalletAbiSessionRequest,
        requestNetwork: WalletAbiNetwork,
        permission: WalletAbiGetterPermission,
        warning: String,
    ) {
        val runtime = runtime(walletId)
        val context = executionContextResolver.resolveSessionRequest(
            incoming = session,
            requestNetwork = requestNetwork,
        )
        val resultJson = walletAbiProviderRunner.runJsonRpcRequest(
            context = context,
            requestEnvelopeJson = buildJsonRpcRequestEnvelope(
                json = json,
                requestId = request.requestId,
                method = request.method,
                paramsJson = request.paramsJson,
            ),
        ).resultJson

        if (permission in (runtime.persistedState?.approvedGetters ?: emptySet())) {
            respondSuccessOrExpire(
                runtime = runtime,
                topic = request.topic,
                requestId = request.requestId,
                resultJson = resultJson,
            )
            return
        }

        enqueueItem(
            walletId = walletId,
            item = WalletAbiPendingItem.GetterApproval(
                request = request,
                permission = permission,
                requestNetwork = requestNetwork,
                resultJson = resultJson,
                value = parseGetterValue(
                    json = json,
                    permission = permission,
                    resultJson = resultJson,
                ),
                warning = warning,
            ),
        )
    }

    private suspend fun approveSessionProposal(
        walletId: String,
        item: WalletAbiPendingItem.SessionProposalApproval,
    ): WalletAbiActionOutcome {
        val runtime = runtime(walletId)
        val session = runtime.boundSession ?: return WalletAbiActionOutcome.Error(
            IllegalStateException("Wallet ABI session is not bound to a connected wallet session"),
        )
        val requestNetwork = item.chainId.toWalletAbiNetworkFromChainId()
        val account = buildWalletConnectAccount(
            session = session,
            requestNetwork = requestNetwork,
            chainId = item.chainId,
        )
        val previousTopic = runtime.persistedState?.topic?.takeIf { it.isNotBlank() }

        if (previousTopic != null) {
            walletConnectBridge.disconnect(previousTopic)
            forgetSession(walletId = walletId, runtime = runtime, topic = previousTopic)
        }

        walletConnectBridge.approveSession(
            proposal = item.proposal,
            approval = WalletAbiSessionApproval(
                relayProtocol = item.proposal.relayProtocol,
                namespaces = mapOf(
                    WALLET_ABI_WALLETCONNECT_NAMESPACE to WalletAbiSessionNamespace(
                        chains = listOf(item.chainId),
                        accounts = listOf(account),
                        methods = item.requestedMethods,
                        events = emptyList(),
                    ),
                ),
                properties = item.proposal.properties,
                scopedProperties = item.proposal.scopedProperties,
            ),
        )

        runtime.pendingPairingTopic = null
        val activeSession = awaitApprovedSession(
            proposal = item.proposal,
            chainId = item.chainId,
            previousTopic = previousTopic,
        ) ?: return WalletAbiActionOutcome.Error(
            IllegalStateException("WalletConnect Wallet ABI session approval completed without an active session"),
        )

        runtime.activeSession = activeSession
        persistSession(
            walletId = walletId,
            runtime = runtime,
            session = activeSession,
            approvedGetters = walletAbiAutoApprovedGettersForRequestedMethods(item.requestedMethods),
        )
        runtime.currentItem = null
        enqueueItem(
            walletId = walletId,
            item = WalletAbiPendingItem.ConnectionEstablished(
                origin = activeSession.displayOrigin(),
                network = activeSession.walletAbiNetworkOrNull()?.serialValue(),
                replacedExistingConnection = previousTopic != null,
            ),
        )
        refreshState(walletId)
        return WalletAbiActionOutcome.Success("WalletConnect Wallet ABI session approved")
    }

    private suspend fun rejectSessionProposal(
        walletId: String,
        item: WalletAbiPendingItem.SessionProposalApproval,
    ): WalletAbiActionOutcome {
        runtime(walletId).pendingPairingTopic = null
        walletConnectBridge.rejectSession(
            proposal = item.proposal,
            reason = WALLET_ABI_USER_REJECTED_MESSAGE,
        )
        val runtime = runtime(walletId)
        runtime.currentItem = runtime.queuedItems.removeFirstOrNull()
        refreshState(walletId)
        return WalletAbiActionOutcome.Success("WalletConnect Wallet ABI session rejected")
    }

    private suspend fun approveGetter(
        walletId: String,
        item: WalletAbiPendingItem.GetterApproval,
    ): WalletAbiActionOutcome {
        val runtime = runtime(walletId)
        val requestExpired = respondSuccessOrExpire(
            runtime = runtime,
            topic = item.request.topic,
            requestId = item.request.requestId,
            resultJson = item.resultJson,
        )

        val persistedState = runtime.persistedState
        if (!requestExpired && persistedState != null && persistedState.topic == item.request.topic) {
            runtime.persistedState = persistedState.copy(
                approvedGetters = persistedState.approvedGetters + item.permission,
            ).also { updated ->
                walletSettingsManager.setWalletAbiSessionState(
                    walletId = walletId,
                    stateJson = json.encodeToString(updated),
                )
            }
        }

        runtime.currentItem = runtime.queuedItems.removeFirstOrNull()
        refreshState(walletId)
        return if (requestExpired) {
            WalletAbiActionOutcome.Success("Wallet ABI request expired")
        } else {
            WalletAbiActionOutcome.Success("Wallet ABI getter approved")
        }
    }

    private suspend fun rejectSessionRequest(
        walletId: String,
        request: WalletAbiSessionRequest,
    ): WalletAbiActionOutcome {
        val runtime = runtime(walletId)
        val requestExpired = respondErrorOrExpire(
            runtime = runtime,
            topic = request.topic,
            requestId = request.requestId,
            code = WALLET_ABI_USER_REJECTED_RPC_CODE,
            message = WALLET_ABI_USER_REJECTED_MESSAGE,
        )
        runtime.currentItem = runtime.queuedItems.removeFirstOrNull()
        refreshState(walletId)
        return if (requestExpired) {
            WalletAbiActionOutcome.Success("Wallet ABI request expired")
        } else {
            WalletAbiActionOutcome.Success("Wallet ABI request rejected")
        }
    }

    suspend fun selectTransactionAccount(
        walletId: String,
        accountId: String,
    ) {
        val runtime = runtime(walletId)
        val session = runtime.boundSession ?: return
        val item = runtime.currentItem as? WalletAbiPendingItem.TransactionApproval ?: return
        val context = executionContextResolver.resolveSessionRequest(
            incoming = session,
            requestNetwork = item.requestNetwork,
            preferredAccountId = accountId,
        )
        runtime.currentItem = item.copy(
            selectedAccountId = context.primaryAccount.id,
            preparedResolution = null,
            review = buildTransactionReview(
                origin = item.origin,
                txRequest = item.txRequest,
                context = context,
            ),
        )
        refreshState(walletId)
    }

    suspend fun resolveCurrentTransaction(walletId: String): WalletAbiActionOutcome {
        val runtime = runtime(walletId)
        val session = runtime.boundSession ?: return WalletAbiActionOutcome.Error(
            IllegalStateException("Wallet ABI session is not bound to a connected wallet session"),
        )
        val item = runtime.currentItem as? WalletAbiPendingItem.TransactionApproval
            ?: return WalletAbiActionOutcome.Error(
                IllegalStateException("No Wallet ABI transaction approval is pending"),
            )

        if (!walletAbiRequestNeedsResolution(item.txRequest)) {
            return WalletAbiActionOutcome.Success("Wallet ABI transaction does not require resolution")
        }

        if (item.preparedResolution != null) {
            return WalletAbiActionOutcome.Success("Wallet ABI transaction is already resolved")
        }

        val context = executionContextResolver.resolveSessionRequest(
            incoming = session,
            requestNetwork = item.requestNetwork,
            preferredAccountId = item.selectedAccountId,
        )
        val resolveRequest = item.txRequest.copy(broadcast = false)
        val resolveRequestJson = json.encodeToString(resolveRequest)
        val result = walletAbiProcessor.process(
            context = context,
            requestJson = resolveRequestJson,
        )

        val prepared = when (result) {
            is WalletAbiProcessResult.Ok -> {
                val transaction = result.response.transaction ?: return WalletAbiActionOutcome.Error(
                    IllegalStateException("Wallet ABI resolve completed without a transaction payload"),
                )
                val resolvedOutputs = walletAbiResolvedOutputsFromTransactionHex(transaction.txHex)
                val reviewRequest = walletAbiTxRequestWithResolvedOutputs(
                    txRequest = item.txRequest,
                    resolvedOutputs = resolvedOutputs,
                )
                WalletAbiPreparedResolution(
                    response = result.response,
                    responseJson = result.responseJson,
                    txHex = transaction.txHex,
                    reviewRequest = reviewRequest,
                )
            }

            is WalletAbiProcessResult.AbiError -> {
                return WalletAbiActionOutcome.Error(Exception(result.error.message))
            }

            is WalletAbiProcessResult.Failed -> {
                return WalletAbiActionOutcome.Error(Exception(result.message, result.cause))
            }
        }

        runtime.currentItem = item.copy(
            review = buildTransactionReview(
                origin = item.origin,
                txRequest = prepared.reviewRequest,
                context = context,
                resolutionState = WalletAbiResolutionState.RESOLVED,
            ),
            preparedResolution = prepared,
        )
        refreshState(walletId)
        return WalletAbiActionOutcome.Success("Wallet ABI transaction resolved")
    }

    suspend fun approveCurrentTransaction(walletId: String): WalletAbiActionOutcome {
        val runtime = runtime(walletId)
        val session = runtime.boundSession ?: return WalletAbiActionOutcome.Error(
            IllegalStateException("Wallet ABI session is not bound to a connected wallet session"),
        )
        val item = runtime.currentItem as? WalletAbiPendingItem.TransactionApproval
            ?: return WalletAbiActionOutcome.Error(
                IllegalStateException("No Wallet ABI transaction approval is pending"),
            )

        item.preparedResolution?.let { prepared ->
            return finalizeResolvedTransaction(
                walletId = walletId,
                runtime = runtime,
                session = session,
                item = item,
                prepared = prepared,
            )
        }

        if (walletAbiRequestNeedsResolution(item.txRequest)) {
            return WalletAbiActionOutcome.Error(
                IllegalStateException("Resolve the transaction first to review final asset ids"),
            )
        }

        val context = executionContextResolver.resolveSessionRequest(
            incoming = session,
            requestNetwork = item.requestNetwork,
            preferredAccountId = item.selectedAccountId,
        )
        val result = walletAbiProcessor.process(
            context = context,
            requestJson = item.requestPayloadJson,
        )

        when (result) {
            is WalletAbiProcessResult.Ok,
            is WalletAbiProcessResult.AbiError,
            -> {
                if (result is WalletAbiProcessResult.Ok) {
                    persistApprovedTransactionRecord(
                        walletId = walletId,
                        txHash = result.response.transaction?.txid,
                        origin = item.origin,
                        status = if (item.txRequest.broadcast) {
                            WalletAbiTransactionRecordStatus.BROADCAST
                        } else {
                            WalletAbiTransactionRecordStatus.APPROVED
                        },
                        review = item.review,
                    )
                }
                val responseJson = when (result) {
                    is WalletAbiProcessResult.Ok -> result.responseJson
                    is WalletAbiProcessResult.AbiError -> result.responseJson
                    else -> error("unreachable")
                }
                respondSuccessOrExpire(
                    runtime = runtime,
                    topic = item.request.topic,
                    requestId = item.request.requestId,
                    resultJson = responseJson,
                )
            }

            is WalletAbiProcessResult.Failed -> {
                respondErrorOrExpire(
                    runtime = runtime,
                    topic = item.request.topic,
                    requestId = item.request.requestId,
                    code = WALLET_ABI_PROCESSING_FAILED_RPC_CODE,
                    message = result.message,
                )
            }
        }

        runtime.currentItem = runtime.queuedItems.removeFirstOrNull()
        refreshState(walletId)

        return when (val presentation = walletAbiResultPresenter.present(result)) {
            is WalletAbiPresentedResult.Success -> WalletAbiActionOutcome.Success(presentation.message)
            is WalletAbiPresentedResult.Error -> WalletAbiActionOutcome.Error(presentation.throwable)
        }
    }

    suspend fun rejectCurrentTransaction(walletId: String): WalletAbiActionOutcome {
        val runtime = runtime(walletId)
        val item = runtime.currentItem as? WalletAbiPendingItem.TransactionApproval
            ?: return WalletAbiActionOutcome.Error(
                IllegalStateException("No Wallet ABI transaction approval is pending"),
            )
        return rejectTransaction(walletId, item)
    }

    private suspend fun finalizeResolvedTransaction(
        walletId: String,
        runtime: WalletAbiRuntimeState,
        session: GdkSession,
        item: WalletAbiPendingItem.TransactionApproval,
        prepared: WalletAbiPreparedResolution,
    ): WalletAbiActionOutcome {
        val broadcastTxid = if (item.txRequest.broadcast) {
            val context = executionContextResolver.resolveSessionRequest(
                incoming = session,
                requestNetwork = item.requestNetwork,
                preferredAccountId = item.selectedAccountId,
            )
            runCatching {
                session.broadcastTransaction(
                    network = context.primaryAccount.network,
                    broadcastTransaction = BroadcastTransactionParams(
                        transaction = prepared.txHex,
                    ),
                )
            }.getOrElse { error ->
                return WalletAbiActionOutcome.Error(error)
            }.txHash ?: prepared.response.transaction?.txid
        } else {
            prepared.response.transaction?.txid
        }

        persistApprovedTransactionRecord(
            walletId = walletId,
            txHash = broadcastTxid ?: prepared.response.transaction?.txid,
            origin = item.origin,
            status = if (item.txRequest.broadcast) {
                WalletAbiTransactionRecordStatus.BROADCAST
            } else {
                WalletAbiTransactionRecordStatus.APPROVED
            },
            review = item.review,
        )

        val requestResponse = runCatching {
            respondSuccessOrExpire(
                runtime = runtime,
                topic = item.request.topic,
                requestId = item.request.requestId,
                resultJson = prepared.responseJson,
            )
        }

        runtime.currentItem = runtime.queuedItems.removeFirstOrNull()
        refreshState(walletId)

        requestResponse.exceptionOrNull()?.let { error ->
            val txid = broadcastTxid ?: prepared.response.transaction?.txid ?: "unknown txid"
            return WalletAbiActionOutcome.Error(
                IllegalStateException(
                    "Wallet ABI transaction was submitted as $txid, but the WalletConnect response failed",
                    error,
                ),
            )
        }

        return if (requestResponse.getOrDefault(false)) {
            WalletAbiActionOutcome.Success("Wallet ABI request expired")
        } else {
            WalletAbiActionOutcome.Success(
                if (item.txRequest.broadcast) {
                    "Wallet ABI transaction broadcast${broadcastTxid?.let { ": $it" } ?: ""}"
                } else {
                    "Wallet ABI request approved"
                },
            )
        }
    }

    private suspend fun persistApprovedTransactionRecord(
        walletId: String,
        txHash: String?,
        origin: String,
        status: WalletAbiTransactionRecordStatus,
        review: WalletAbiTransactionReviewLook,
    ) {
        val resolvedTxHash = txHash?.takeIf { it.isNotBlank() } ?: return
        val store = walletAbiTransactionStore ?: return
        runCatching {
            store.save(
                WalletAbiTransactionRecord(
                    walletId = walletId,
                    txHash = resolvedTxHash,
                    origin = origin,
                    status = status,
                    review = review,
                    updatedAtEpochMilliseconds = kotlin.time.Clock.System.now().toEpochMilliseconds(),
                ),
            )
        }
    }

    private suspend fun rejectTransaction(
        walletId: String,
        item: WalletAbiPendingItem.TransactionApproval,
    ): WalletAbiActionOutcome {
        val runtime = runtime(walletId)
        val requestExpired = respondErrorOrExpire(
            runtime = runtime,
            topic = item.request.topic,
            requestId = item.request.requestId,
            code = WALLET_ABI_USER_REJECTED_RPC_CODE,
            message = WALLET_ABI_USER_REJECTED_MESSAGE,
        )
        runtime.currentItem = runtime.queuedItems.removeFirstOrNull()
        refreshState(walletId)
        return if (requestExpired) {
            WalletAbiActionOutcome.Success("Wallet ABI request expired")
        } else {
            WalletAbiActionOutcome.Success("Wallet ABI request rejected")
        }
    }

    internal suspend fun syncRuntimeWithWalletConnect(walletId: String) {
        val runtime = runtime(walletId)
        val walletAbiSessions = walletConnectBridge.getActiveSessions().filter(::isWalletAbiSession)
        val persistedTopic = runtime.persistedState?.topic
        val activeSession = when {
            persistedTopic != null -> walletAbiSessions.firstOrNull { it.topic == persistedTopic }
            walletAbiSessions.size == 1 -> walletAbiSessions.first()
            else -> null
        }

        if (activeSession == null) {
            if (persistedTopic != null) {
                runtime.persistedState = null
                walletSettingsManager.clearWalletAbiSessionState(walletId)
            }
            runtime.activeSession = null
            refreshState(walletId)
            return
        }

        runtime.activeSession = activeSession
        persistSession(
            walletId = walletId,
            runtime = runtime,
            session = activeSession,
            approvedGetters = runtime.persistedState
                ?.takeIf { it.topic == activeSession.topic }
                ?.approvedGetters
                ?: emptySet(),
        )
        refreshState(walletId)
    }

    private suspend fun awaitApprovedSession(
        proposal: WalletAbiSessionProposal,
        chainId: String,
        previousTopic: String?,
    ): WalletAbiSessionInfo? {
        repeat(WALLET_ABI_APPROVAL_POLL_ATTEMPTS) {
            walletConnectBridge.getActiveSessions()
                .filter(::isWalletAbiSession)
                .firstOrNull { session ->
                    session.topic != previousTopic &&
                        session.matchesProposal(proposal = proposal, chainId = chainId)
                }?.let { return it }

            delay(WALLET_ABI_APPROVAL_POLL_DELAY_MS)
        }

        return walletConnectBridge.getActiveSessions()
            .filter(::isWalletAbiSession)
            .firstOrNull { it.topic != previousTopic }
    }

    private suspend fun ensureWalletConnectInitialized() {
        if (walletConnectInitialized) {
            return
        }
        walletConnectBridge.initialize()
        walletConnectInitialized = true
    }

    private suspend fun buildWalletConnectAccount(
        session: GdkSession,
        requestNetwork: WalletAbiNetwork,
        chainId: String,
    ): String {
        val context = executionContextResolver.resolveSessionRequest(
            incoming = session,
            requestNetwork = requestNetwork,
        )
        val resultJson = walletAbiProviderRunner.runJsonRpcRequest(
            context = context,
            requestEnvelopeJson = buildJsonRpcRequestEnvelope(
                json = json,
                requestId = 1L,
                method = WALLET_ABI_METHOD_GET_RAW_SIGNING_X_ONLY_PUBKEY,
                paramsJson = null,
            ),
        ).resultJson

        val pubkey = json.parseToJsonElement(resultJson)
            .jsonObject["raw_signing_x_only_pubkey"]
            ?.jsonPrimitive
            ?.content
            ?: throw IllegalStateException(
                "Wallet ABI provider did not return raw_signing_x_only_pubkey",
            )

        return "$chainId:$pubkey"
    }

    private suspend fun respondSuccessOrExpire(
        runtime: WalletAbiRuntimeState,
        topic: String,
        requestId: Long,
        resultJson: String,
    ): Boolean {
        return try {
            walletConnectBridge.respondSuccess(
                topic = topic,
                requestId = requestId,
                resultJson = resultJson,
            )
            runtime.completedRequestKeys += "$topic:$requestId"
            false
        } catch (error: Throwable) {
            if (!error.isWalletConnectRequestExpired()) {
                throw error
            }
            runtime.completedRequestKeys += "$topic:$requestId"
            true
        }
    }

    private suspend fun respondErrorOrExpire(
        runtime: WalletAbiRuntimeState,
        topic: String,
        requestId: Long,
        code: Int,
        message: String,
    ): Boolean {
        return try {
            walletConnectBridge.respondError(
                topic = topic,
                requestId = requestId,
                code = code,
                message = message,
            )
            runtime.completedRequestKeys += "$topic:$requestId"
            false
        } catch (error: Throwable) {
            if (!error.isWalletConnectRequestExpired()) {
                throw error
            }
            runtime.completedRequestKeys += "$topic:$requestId"
            true
        }
    }

    private suspend fun buildTransactionReview(
        origin: String,
        txRequest: WalletAbiTxCreateRequest,
        context: WalletAbiExecutionContext,
        resolutionState: WalletAbiResolutionState = when {
            walletAbiRequestNeedsResolution(txRequest) -> WalletAbiResolutionState.REQUIRED
            else -> WalletAbiResolutionState.NOT_REQUIRED
        },
    ): WalletAbiTransactionReviewLook {
        val exactImpactPreview = walletAbiImpactPreviewer.preview(
            WalletAbiImpactPreviewRequest(
                session = context.session,
                accounts = context.accounts,
                selectedAccount = context.primaryAccount,
                txRequest = txRequest,
            ),
        )
        val knownDestinations = runCatching {
            loadWalletAbiKnownDestinationLookup { lastPointer ->
                context.session.getPreviousAddresses(
                    account = context.primaryAccount,
                    lastPointer = lastPointer,
                )
            }
        }.getOrDefault(WalletAbiKnownDestinationLookup())
        val validatedAddressOwnership = mutableMapOf<String, Boolean>()

        return buildWalletAbiTransactionReview(
            origin = origin,
            txRequest = txRequest,
            accounts = context.accounts,
            selectedAccount = context.primaryAccount,
            resolutionState = resolutionState,
            amountFormatter = { amountSat, assetId, account ->
                formatWalletAbiReviewAmount(
                    session = context.session,
                    amountSat = amountSat,
                    assetId = assetId,
                    account = account,
                )
            },
            assetLabelResolver = { assetId, account ->
                resolveWalletAbiAssetLabel(
                    session = context.session,
                    assetId = assetId,
                    account = account,
                )
            },
            exactImpactPreview = exactImpactPreview,
            walletOwnedDestinationDetector = { output, account ->
                if (walletAbiOutputMatchesKnownWalletDestination(output, knownDestinations)) {
                    true
                } else {
                    walletAbiOutputAddressCandidates(output).any { addressCandidate ->
                        validatedAddressOwnership[addressCandidate] ?: runCatching {
                            walletAbiValidatedAddresseeIndicatesWalletOwnership(
                                context.session.validateAddressee(
                                    account = account,
                                    address = addressCandidate,
                                ),
                            )
                        }.getOrDefault(false).also { isOwned ->
                            validatedAddressOwnership[addressCandidate] = isOwned
                        }
                    }
                }
            },
        )
    }

    private suspend fun handleSessionDelete(
        topic: String,
        reason: String,
    ) {
        runtimes.entries.firstOrNull { entry ->
            entry.value.persistedState?.topic == topic || entry.value.activeSession?.topic == topic
        }?.let { (walletId, runtime) ->
            forgetSession(walletId = walletId, runtime = runtime, topic = topic)
            if (reason.isNotBlank()) {
                enqueueItem(walletId, WalletAbiPendingItem.Error(reason))
            } else {
                refreshState(walletId)
            }
        }
    }

    private suspend fun handleSessionExtend(session: WalletAbiSessionInfo) {
        runtimes.entries.firstOrNull { entry ->
            entry.value.persistedState?.topic == session.topic || entry.value.activeSession?.topic == session.topic
        }?.let { (walletId, runtime) ->
            runtime.activeSession = session
            persistSession(
                walletId = walletId,
                runtime = runtime,
                session = session,
                approvedGetters = runtime.persistedState?.approvedGetters ?: emptySet(),
            )
            refreshState(walletId)
        }
    }

    private suspend fun handleWalletConnectError(message: String) {
        val pendingEntry = runtimes.entries.firstOrNull { it.value.pendingPairingTopic != null }
        val activeEntry = runtimes.entries.firstOrNull { it.value.activeSession != null }
        val entry = pendingEntry ?: activeEntry ?: return

        if (entry === pendingEntry) {
            entry.value.pendingPairingTopic = null
        }

        enqueueItem(entry.key, WalletAbiPendingItem.Error(message))
    }

    private fun enqueueItem(
        walletId: String,
        item: WalletAbiPendingItem,
    ) {
        val runtime = runtime(walletId)
        if (item is WalletAbiPendingItem.Error) {
            val currentMessage = (runtime.currentItem as? WalletAbiPendingItem.Error)?.message
            val queuedDuplicate = runtime.queuedItems.any { queued ->
                (queued as? WalletAbiPendingItem.Error)?.message == item.message
            }
            if (currentMessage == item.message || queuedDuplicate) {
                refreshState(walletId)
                return
            }
        }

        if (runtime.currentItem == null) {
            runtime.currentItem = item
        } else {
            runtime.queuedItems.addLast(item)
        }
        refreshState(walletId)
    }

    private fun refreshState(walletId: String) {
        val runtime = runtime(walletId)
        stateFlow(walletId).value = WalletAbiSessionUiState(
            activeConnection = runtime.persistedState?.let { persisted ->
                WalletAbiConnectionLook(
                    origin = runtime.activeSession?.displayOrigin() ?: persisted.originHint,
                    network = persisted.chainId.toWalletAbiNetworkFromChainId().serialValue(),
                    state = runtime.visibleConnectionState(),
                    approvedGetters = persisted.approvedGetters,
                )
            },
            overlay = when (val item = runtime.currentItem) {
                is WalletAbiPendingItem.SessionProposalApproval -> WalletAbiOverlayLook.SessionProposalApproval(
                    origin = item.origin,
                    network = item.chainId.toWalletAbiNetworkFromChainId().serialValue(),
                    requestedMethods = item.requestedMethods,
                    autoApprovedGetters = walletAbiAutoApprovedGettersForRequestedMethods(item.requestedMethods),
                    warning = item.warning,
                    willReplaceExistingConnection = item.willReplaceExistingConnection,
                )

                is WalletAbiPendingItem.ConnectionEstablished -> WalletAbiOverlayLook.ConnectionEstablished(
                    origin = item.origin,
                    network = item.network,
                    replacedExistingConnection = item.replacedExistingConnection,
                )

                is WalletAbiPendingItem.GetterApproval -> WalletAbiOverlayLook.GetterApproval(
                    origin = item.request.displayOrigin(runtime.persistedState?.originHint),
                    requestId = item.request.requestId.toString(),
                    method = item.permission.methodName(),
                    value = item.value,
                    warning = item.warning,
                    network = item.requestNetwork.serialValue(),
                )

                is WalletAbiPendingItem.TransactionApproval -> WalletAbiOverlayLook.TransactionApproval(
                    review = item.review,
                )

                is WalletAbiPendingItem.Error -> WalletAbiOverlayLook.Error(item.message)
                null -> null
            },
        )
    }

    private fun runtime(walletId: String): WalletAbiRuntimeState {
        return runtimes.getOrPut(walletId) { WalletAbiRuntimeState() }
    }

    private fun stateFlow(walletId: String): MutableStateFlow<WalletAbiSessionUiState> {
        return states.getOrPut(walletId) { MutableStateFlow(WalletAbiSessionUiState()) }
    }
}

private fun isWalletAbiSession(session: WalletAbiSessionInfo): Boolean {
    val namespace = session.namespaces[WALLET_ABI_WALLETCONNECT_NAMESPACE] ?: return false
    return namespace.events.isEmpty() &&
        namespace.methods.all { it in WALLET_ABI_WALLETCONNECT_METHODS } &&
        namespace.chains.all { it in WALLET_ABI_WALLETCONNECT_CHAINS }
}

private fun WalletAbiSessionInfo.matchesProposal(
    proposal: WalletAbiSessionProposal,
    chainId: String,
): Boolean {
    return walletAbiChainId() == chainId &&
        name == proposal.name &&
        url == proposal.url
}

private fun WalletAbiSessionInfo.walletAbiChainId(): String? {
    return namespaces[WALLET_ABI_WALLETCONNECT_NAMESPACE]
        ?.chains
        ?.singleOrNull()
}

private fun WalletAbiSessionInfo.walletAbiMethods(): Set<String> {
    return namespaces[WALLET_ABI_WALLETCONNECT_NAMESPACE]
        ?.methods
        ?.toSet()
        ?: emptySet()
}

private fun WalletAbiSessionInfo.walletAbiNetworkOrNull(): WalletAbiNetwork? {
    return walletAbiChainId()?.toWalletAbiNetworkFromChainId()
}

private fun WalletAbiSessionInfo.displayOrigin(): String? {
    return name?.takeIf { it.isNotBlank() }
        ?: url?.takeIf { it.isNotBlank() }
        ?: description?.takeIf { it.isNotBlank() }
}

private fun WalletAbiSessionProposal.displayOrigin(): String {
    return name?.takeIf { it.isNotBlank() }
        ?: url?.takeIf { it.isNotBlank() }
        ?: verifyContext?.origin?.takeIf { it.isNotBlank() }
        ?: "Unknown dApp"
}

private fun WalletAbiSessionProposal.verifyWarning(): String? {
    return when {
        verifyContext?.isScam == true -> "Verify flagged this dApp as potentially malicious."
        verifyContext?.validation?.isNotBlank() == true -> "Verify status: ${verifyContext.validation}"
        else -> null
    }
}

private fun String.toWalletAbiNetworkFromChainId(): WalletAbiNetwork {
    val normalized = trim()
    require(normalized.startsWith("$WALLET_ABI_WALLETCONNECT_NAMESPACE:")) {
        "Unsupported WalletConnect Wallet ABI chain id '$this'"
    }
    return normalized.substringAfter(':').toWalletAbiNetwork()
}

private fun WalletAbiRuntimeState.visibleConnectionState(): WalletAbiSessionState? {
    return if (persistedState != null && activeSession != null) {
        WalletAbiSessionState.CONNECTED
    } else {
        null
    }
}

private fun WalletAbiRuntimeState.hasActiveConnection(): Boolean {
    return visibleConnectionState() == WalletAbiSessionState.CONNECTED
}

private fun WalletAbiRuntimeState.hasSeenRequest(requestKey: String): Boolean {
    return requestKey in completedRequestKeys ||
        currentItem?.requestKey() == requestKey ||
        queuedItems.any { it.requestKey() == requestKey }
}

private fun WalletAbiRuntimeState.removeItemsForTopic(topic: String) {
    if (currentItem?.topic() == topic) {
        currentItem = null
    }

    if (queuedItems.isNotEmpty()) {
        val retained = queuedItems.filterNot { it.topic() == topic }
        queuedItems.clear()
        retained.forEach(queuedItems::addLast)
    }

    if (currentItem == null) {
        currentItem = queuedItems.removeFirstOrNull()
    }

    completedRequestKeys.removeAll { requestKey -> requestKey.startsWith("$topic:") }
}

private fun WalletAbiPendingItem.requestKey(): String? {
    return when (this) {
        is WalletAbiPendingItem.GetterApproval -> request.requestKey()
        is WalletAbiPendingItem.TransactionApproval -> request.requestKey()
        else -> null
    }
}

private fun WalletAbiPendingItem.topic(): String? {
    return when (this) {
        is WalletAbiPendingItem.GetterApproval -> request.topic
        is WalletAbiPendingItem.TransactionApproval -> request.topic
        else -> null
    }
}

private fun WalletAbiGetterPermission.methodName(): String {
    return when (this) {
        WalletAbiGetterPermission.GET_SIGNER_RECEIVE_ADDRESS -> WALLET_ABI_METHOD_GET_SIGNER_RECEIVE_ADDRESS
        WalletAbiGetterPermission.GET_RAW_SIGNING_X_ONLY_PUBKEY -> WALLET_ABI_METHOD_GET_RAW_SIGNING_X_ONLY_PUBKEY
    }
}

private fun WalletAbiSessionRequest.requestKey(): String = "$topic:$requestId"

private fun buildJsonRpcRequestEnvelope(
    json: Json,
    requestId: Long,
    method: String,
    paramsJson: String?,
): String {
    return buildJsonObject {
        put("id", requestId)
        put("jsonrpc", "2.0")
        put("method", method)
        paramsJson?.normalizeJsonRpcParams()?.let { normalized ->
            put("params", json.parseToJsonElement(normalized))
        }
    }.toString()
}

private fun parseGetterValue(
    json: Json,
    permission: WalletAbiGetterPermission,
    resultJson: String,
): String {
    val result = json.parseToJsonElement(resultJson).jsonObject
    return when (permission) {
        WalletAbiGetterPermission.GET_SIGNER_RECEIVE_ADDRESS -> {
            result["signer_receive_address"]?.jsonPrimitive?.content
        }

        WalletAbiGetterPermission.GET_RAW_SIGNING_X_ONLY_PUBKEY -> {
            result["raw_signing_x_only_pubkey"]?.jsonPrimitive?.content
        }
    } ?: throw IllegalStateException(
        "Wallet ABI provider response is missing ${permission.methodName()} result",
    )
}

private fun Throwable.isWalletConnectRequestExpired(): Boolean {
    return message?.contains("request has expired", ignoreCase = true) == true ||
        this::class.qualifiedName == "com.reown.android.internal.common.exception.RequestExpiredException"
}

private fun WalletAbiSessionRequest.displayOrigin(fallback: String?): String {
    return peerName?.takeIf { it.isNotBlank() }
        ?: peerUrl?.takeIf { it.isNotBlank() }
        ?: verifyContext?.origin?.takeIf { it.isNotBlank() }
        ?: fallback
        ?: "Unknown dApp"
}

private fun String.normalizeJsonRpcParams(): String? {
    val trimmed = trim()
    return when {
        trimmed.isBlank() -> null
        trimmed == "null" -> null
        else -> trimmed
    }
}
