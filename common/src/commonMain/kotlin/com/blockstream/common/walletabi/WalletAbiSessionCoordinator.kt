package com.blockstream.common.walletabi

import com.blockstream.common.data.GreenWallet
import com.blockstream.common.gdk.GdkSession
import com.blockstream.common.managers.WalletSettingsManager
import com.blockstream.common.walletabi.transport.WalletAbiNetwork
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal data class WalletAbiConnectionLook(
    val origin: String?,
    val network: String?,
    val state: WalletAbiSessionState?,
    val approvedGetters: Set<WalletAbiGetterPermission>,
)

internal data class WalletAbiSessionUiState(
    val activeConnection: WalletAbiConnectionLook? = null,
)

private data class WalletAbiRuntimeState(
    var persistedState: WalletAbiPersistedSessionState? = null,
    var persistedLoaded: Boolean = false,
    var boundSession: GdkSession? = null,
    var activeSession: WalletAbiSessionInfo? = null,
)

internal class WalletAbiSessionCoordinator(
    private val json: Json,
    private val walletSettingsManager: WalletSettingsManager,
    private val walletConnectBridge: WalletAbiWalletConnectBridge,
) {
    private val runtimes = mutableMapOf<String, WalletAbiRuntimeState>()
    private val states = mutableMapOf<String, MutableStateFlow<WalletAbiSessionUiState>>()
    private var walletConnectInitialized = false

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

    internal suspend fun awaitApprovedSession(
        topic: String,
    ): WalletAbiSessionInfo? {
        return walletConnectBridge.getActiveSessions()
            .filter(::isWalletAbiSession)
            .firstOrNull { it.topic == topic }
    }

    private suspend fun ensureWalletConnectInitialized() {
        if (walletConnectInitialized) {
            return
        }
        walletConnectBridge.initialize()
        walletConnectInitialized = true
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
