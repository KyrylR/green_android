package com.blockstream.common.walletabi

import com.blockstream.common.data.GreenWallet
import com.blockstream.common.gdk.GdkSession
import com.blockstream.common.managers.WalletSettingsManager
import com.blockstream.common.walletabi.transport.WalletAbiNetwork
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

private const val WALLET_ABI_USER_REJECTED_MESSAGE =
    "WalletConnect Wallet ABI request was rejected by user"
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

    data class Error(
        val message: String,
    ) : WalletAbiPendingItem
}

private data class WalletAbiRuntimeState(
    var persistedState: WalletAbiPersistedSessionState? = null,
    var persistedLoaded: Boolean = false,
    var boundSession: GdkSession? = null,
    var activeSession: WalletAbiSessionInfo? = null,
    var pendingPairingTopic: String? = null,
    var currentItem: WalletAbiPendingItem? = null,
    val queuedItems: ArrayDeque<WalletAbiPendingItem> = ArrayDeque(),
)

internal class WalletAbiSessionCoordinator(
    private val json: Json,
    private val executionContextResolver: WalletAbiExecutionContextResolving,
    private val walletAbiProviderRunner: WalletAbiProviderRunning,
    private val walletSettingsManager: WalletSettingsManager,
    private val walletConnectBridge: WalletAbiWalletConnectBridge,
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

                override fun onSessionRequest(request: WalletAbiSessionRequest) = Unit

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
        refreshState(walletId)
    }

    private suspend fun routeSessionProposal(proposal: WalletAbiSessionProposal) {
        val walletId = runtimes.entries.firstOrNull { it.value.pendingPairingTopic == proposal.pairingTopic }
            ?.key
            ?: runtimes.entries.firstOrNull { it.value.boundSession != null }?.key
            ?: return

        handleSessionProposal(walletId, proposal)
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

private fun buildJsonRpcRequestEnvelope(
    requestId: Long,
    method: String,
    paramsJson: String?,
): String {
    return buildJsonObject {
        put("id", requestId)
        put("jsonrpc", "2.0")
        put("method", method)
        paramsJson?.let { put("params", it) }
    }.toString()
}
