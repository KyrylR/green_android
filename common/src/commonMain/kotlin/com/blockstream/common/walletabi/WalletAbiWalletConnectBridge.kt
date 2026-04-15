package com.blockstream.common.walletabi

private const val WALLET_ABI_ANDROID_ONLY_MESSAGE = "WalletConnect Wallet ABI is only available on Android"

internal enum class WalletAbiSessionState {
    CONNECTED,
    CLOSED,
    EXPIRED,
    ERROR,
}

internal data class WalletAbiVerifyLook(
    val origin: String?,
    val validation: String?,
    val verifyUrl: String?,
    val isScam: Boolean?,
)

internal data class WalletAbiSessionNamespaceProposal(
    val chains: List<String>,
    val methods: List<String>,
    val events: List<String>,
)

internal data class WalletAbiSessionNamespace(
    val chains: List<String>,
    val accounts: List<String>,
    val methods: List<String>,
    val events: List<String>,
)

internal data class WalletAbiSessionProposal(
    val pairingTopic: String,
    val proposerPublicKey: String,
    val name: String?,
    val description: String?,
    val url: String?,
    val icons: List<String>,
    val redirect: String?,
    val relayProtocol: String?,
    val requiredNamespaces: Map<String, WalletAbiSessionNamespaceProposal>,
    val optionalNamespaces: Map<String, WalletAbiSessionNamespaceProposal>,
    val properties: Map<String, String>?,
    val scopedProperties: Map<String, String>?,
    val verifyContext: WalletAbiVerifyLook?,
)

internal data class WalletAbiSessionInfo(
    val topic: String,
    val expiry: Long,
    val name: String?,
    val description: String?,
    val url: String?,
    val icons: List<String>,
    val requiredNamespaces: Map<String, WalletAbiSessionNamespaceProposal>,
    val optionalNamespaces: Map<String, WalletAbiSessionNamespaceProposal>,
    val namespaces: Map<String, WalletAbiSessionNamespace>,
)

internal data class WalletAbiSessionRequest(
    val topic: String,
    val chainId: String?,
    val requestId: Long,
    val method: String,
    val paramsJson: String,
    val peerName: String?,
    val peerDescription: String?,
    val peerUrl: String?,
    val peerIcons: List<String>,
    val verifyContext: WalletAbiVerifyLook?,
)

internal data class WalletAbiSessionApproval(
    val relayProtocol: String?,
    val namespaces: Map<String, WalletAbiSessionNamespace>,
    val properties: Map<String, String>?,
    val scopedProperties: Map<String, String>?,
)

internal interface WalletAbiWalletConnectBridgeListener {
    fun onSessionProposal(proposal: WalletAbiSessionProposal)
    fun onSessionRequest(request: WalletAbiSessionRequest)
    fun onSessionDelete(topic: String, reason: String)
    fun onSessionExtend(session: WalletAbiSessionInfo)
    fun onError(message: String)
}

internal interface WalletAbiWalletConnectBridge {
    suspend fun initialize()
    fun setListener(listener: WalletAbiWalletConnectBridgeListener?)
    suspend fun pair(uri: String)
    suspend fun getActiveSessions(): List<WalletAbiSessionInfo>
    suspend fun getActiveSession(topic: String): WalletAbiSessionInfo?
    suspend fun getPendingRequests(topic: String): List<WalletAbiSessionRequest>
    suspend fun approveSession(proposal: WalletAbiSessionProposal, approval: WalletAbiSessionApproval)
    suspend fun rejectSession(proposal: WalletAbiSessionProposal, reason: String)
    suspend fun respondSuccess(topic: String, requestId: Long, resultJson: String)
    suspend fun respondError(topic: String, requestId: Long, code: Int, message: String)
    suspend fun disconnect(topic: String)
}

internal class NoopWalletAbiWalletConnectBridge : WalletAbiWalletConnectBridge {
    private var listener: WalletAbiWalletConnectBridgeListener? = null

    override suspend fun initialize() = Unit

    override fun setListener(listener: WalletAbiWalletConnectBridgeListener?) {
        this.listener = listener
    }

    override suspend fun pair(uri: String) {
        listener?.onError(WALLET_ABI_ANDROID_ONLY_MESSAGE)
    }

    override suspend fun getActiveSessions(): List<WalletAbiSessionInfo> = emptyList()

    override suspend fun getActiveSession(topic: String): WalletAbiSessionInfo? = null

    override suspend fun getPendingRequests(topic: String): List<WalletAbiSessionRequest> = emptyList()

    override suspend fun approveSession(
        proposal: WalletAbiSessionProposal,
        approval: WalletAbiSessionApproval,
    ) {
        listener?.onError(WALLET_ABI_ANDROID_ONLY_MESSAGE)
    }

    override suspend fun rejectSession(proposal: WalletAbiSessionProposal, reason: String) {
        listener?.onError(WALLET_ABI_ANDROID_ONLY_MESSAGE)
    }

    override suspend fun respondSuccess(topic: String, requestId: Long, resultJson: String) = Unit

    override suspend fun respondError(topic: String, requestId: Long, code: Int, message: String) = Unit

    override suspend fun disconnect(topic: String) = Unit
}
