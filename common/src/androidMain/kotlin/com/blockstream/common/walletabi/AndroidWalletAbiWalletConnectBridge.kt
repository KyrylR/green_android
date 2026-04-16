package com.blockstream.common.walletabi

import android.app.Application
import com.blockstream.common.data.AppConfig
import com.blockstream.green.utils.Loggable
import com.reown.android.Core
import com.reown.android.CoreClient
import com.reown.walletkit.client.Wallet
import com.reown.walletkit.client.WalletKit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

 class AndroidWalletAbiWalletConnectBridge(
    private val application: Application,
    private val appConfig: AppConfig,
) : Loggable(), WalletAbiWalletConnectBridge {
    private val initializationMutex = Mutex()
    private val initializationStateLock = Any()

    @Volatile
    private var initialized = false

    @Volatile
    private var initializationDeferred: CompletableDeferred<Unit>? = null

    private var listener: WalletAbiWalletConnectBridgeListener? = null

    override suspend fun initialize() {
        if (initialized) {
            return
        }

        val deferred = initializationMutex.withLock {
            if (initialized) {
                return@withLock null
            }

            initializationDeferred?.let { existing ->
                return@withLock existing
            }

            val next = CompletableDeferred<Unit>()
            initializationDeferred = next

            try {
                startInitialization(next)
            } catch (error: Throwable) {
                synchronized(initializationStateLock) {
                    if (initializationDeferred === next) {
                        initializationDeferred = null
                        initialized = false
                    }
                }
                next.completeExceptionally(error)
                throw error
            }

            next
        }

        deferred?.await()
    }

    override fun setListener(listener: WalletAbiWalletConnectBridgeListener?) {
        this.listener = listener
    }

    override suspend fun pair(uri: String) {
        initialize()
        awaitWalletKit<Unit> { onSuccess, onError ->
            WalletKit.pair(
                params = Wallet.Params.Pair(uri),
                onSuccess = { onSuccess(Unit) },
                onError = { error -> onError(error.throwable) },
            )
        }
    }

    override suspend fun getActiveSessions(): List<WalletAbiSessionInfo> {
        initialize()
        return WalletKit.getListOfActiveSessions().map(Wallet.Model.Session::toBridge)
    }

    override suspend fun getActiveSession(topic: String): WalletAbiSessionInfo? {
        initialize()
        return WalletKit.getActiveSessionByTopic(topic)?.toBridge()
    }

    override suspend fun getPendingRequests(topic: String): List<WalletAbiSessionRequest> {
        initialize()
        return WalletKit.getPendingListOfSessionRequests(topic).map(Wallet.Model.SessionRequest::toBridge)
    }

    override suspend fun approveSession(
        proposal: WalletAbiSessionProposal,
        approval: WalletAbiSessionApproval,
    ) {
        initialize()
        awaitWalletKit<Unit> { onSuccess, onError ->
            WalletKit.approveSession(
                params = Wallet.Params.SessionApprove(
                    proposerPublicKey = proposal.proposerPublicKey,
                    namespaces = approval.namespaces.toWalletNamespaces(),
                    properties = approval.properties,
                    scopedProperties = approval.scopedProperties,
                    relayProtocol = approval.relayProtocol,
                ),
                onSuccess = { onSuccess(Unit) },
                onError = { error -> onError(error.throwable) },
            )
        }
    }

    override suspend fun rejectSession(proposal: WalletAbiSessionProposal, reason: String) {
        initialize()
        awaitWalletKit<Unit> { onSuccess, onError ->
            WalletKit.rejectSession(
                params = Wallet.Params.SessionReject(
                    proposerPublicKey = proposal.proposerPublicKey,
                    reason = reason,
                ),
                onSuccess = { onSuccess(Unit) },
                onError = { error -> onError(error.throwable) },
            )
        }
    }

    override suspend fun respondSuccess(topic: String, requestId: Long, resultJson: String) {
        initialize()
        awaitWalletKit<Unit> { onSuccess, onError ->
            WalletKit.respondSessionRequest(
                params = Wallet.Params.SessionRequestResponse(
                    sessionTopic = topic,
                    jsonRpcResponse = Wallet.Model.JsonRpcResponse.JsonRpcResult(
                        id = requestId,
                        result = resultJson,
                    ),
                ),
                onSuccess = { onSuccess(Unit) },
                onError = { error -> onError(error.throwable) },
            )
        }
    }

    override suspend fun respondError(topic: String, requestId: Long, code: Int, message: String) {
        initialize()
        awaitWalletKit<Unit> { onSuccess, onError ->
            WalletKit.respondSessionRequest(
                params = Wallet.Params.SessionRequestResponse(
                    sessionTopic = topic,
                    jsonRpcResponse = Wallet.Model.JsonRpcResponse.JsonRpcError(
                        id = requestId,
                        code = code,
                        message = message,
                    ),
                ),
                onSuccess = { onSuccess(Unit) },
                onError = { error -> onError(error.throwable) },
            )
        }
    }

    override suspend fun disconnect(topic: String) {
        initialize()
        awaitWalletKit<Unit> { onSuccess, onError ->
            WalletKit.disconnectSession(
                params = Wallet.Params.SessionDisconnect(sessionTopic = topic),
                onSuccess = { onSuccess(Unit) },
                onError = { error -> onError(error.throwable) },
            )
        }
    }

    private fun startInitialization(initialization: CompletableDeferred<Unit>) {
        val projectId = appConfig.reownProjectId?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("Missing reown_project_id in app_keys.txt")

        val metadata = Core.Model.AppMetaData(
            name = "Green",
            description = "Blockstream Green Wallet",
            url = "https://blockstream.com/green/",
            icons = listOf("https://blockstream.com/green/favicon.ico"),
            redirect = null,
        )

        CoreClient.initialize(
            application = application,
            projectId = projectId,
            metaData = metadata,
        ) { error ->
            failInitialization(
                initialization = initialization,
                throwable = error.throwable,
                fallbackMessage = "WalletConnect core initialization failed",
            )
        }

        WalletKit.initialize(
            params = Wallet.Params.Init(CoreClient),
            onSuccess = {
                completeInitialization(initialization)
            },
        ) { error ->
            failInitialization(
                initialization = initialization,
                throwable = error.throwable,
                fallbackMessage = "WalletKit initialization failed",
            )
        }

        WalletKit.setWalletDelegate(
            object : WalletKit.WalletDelegate {
                override fun onSessionProposal(
                    sessionProposal: Wallet.Model.SessionProposal,
                    verifyContext: Wallet.Model.VerifyContext,
                ) {
                    listener?.onSessionProposal(sessionProposal.toBridge(verifyContext.toBridge()))
                }

                override fun onSessionRequest(
                    sessionRequest: Wallet.Model.SessionRequest,
                    verifyContext: Wallet.Model.VerifyContext,
                ) {
                    listener?.onSessionRequest(sessionRequest.toBridge(verifyContext.toBridge()))
                }

                override fun onSessionDelete(sessionDelete: Wallet.Model.SessionDelete) {
                    when (sessionDelete) {
                        is Wallet.Model.SessionDelete.Success -> {
                            listener?.onSessionDelete(sessionDelete.topic, sessionDelete.reason)
                        }

                        is Wallet.Model.SessionDelete.Error -> {
                            listener?.onError(
                                sessionDelete.error.message ?: "WalletConnect session disconnected",
                            )
                        }
                    }
                }

                override fun onSessionExtend(session: Wallet.Model.Session) {
                    listener?.onSessionExtend(session.toBridge())
                }

                override fun onSessionSettleResponse(
                    settleSessionResponse: Wallet.Model.SettledSessionResponse,
                ) = Unit

                override fun onSessionUpdateResponse(
                    sessionUpdateResponse: Wallet.Model.SessionUpdateResponse,
                ) = Unit

                override fun onConnectionStateChange(state: Wallet.Model.ConnectionState) {
                    if (state.isAvailable) {
                        return
                    }

                    val message = state.reason?.let { reason ->
                        when (reason) {
                            is Wallet.Model.ConnectionState.Reason.ConnectionClosed -> reason.message
                            is Wallet.Model.ConnectionState.Reason.ConnectionFailed -> {
                                reason.throwable.message ?: "WalletConnect connection failed"
                            }
                        }
                    } ?: "WalletConnect connection unavailable"

                    listener?.onError(message)
                }

                override fun onError(error: Wallet.Model.Error) {
                    listener?.onError(error.throwable.message ?: "WalletConnect wallet error")
                }
            },
        )
    }

    private fun completeInitialization(initialization: CompletableDeferred<Unit>) {
        synchronized(initializationStateLock) {
            if (initializationDeferred !== initialization || initialization.isCompleted) {
                return
            }

            initialized = true
            initialization.complete(Unit)
        }
    }

    private fun failInitialization(
        initialization: CompletableDeferred<Unit>,
        throwable: Throwable,
        fallbackMessage: String,
    ) {
        val message = throwable.message ?: fallbackMessage
        val shouldNotify = synchronized(initializationStateLock) {
            if (initializationDeferred !== initialization) {
                return@synchronized false
            }

            initialized = false
            initializationDeferred = null
            if (!initialization.isCompleted) {
                initialization.completeExceptionally(throwable)
            }
            true
        }

        if (shouldNotify) {
            logger.w { message }
            listener?.onError(message)
        }
    }
}

private suspend fun <T> awaitWalletKit(
    block: (onSuccess: (T) -> Unit, onError: (Throwable) -> Unit) -> Unit,
): T {
    return suspendCancellableCoroutine { continuation ->
        block(
            { result ->
                if (continuation.isActive) {
                    continuation.resume(result)
                }
            },
            { throwable ->
                if (continuation.isActive) {
                    continuation.resumeWithException(throwable)
                }
            },
        )
    }
}

private fun Wallet.Model.VerifyContext.toBridge(): WalletAbiVerifyLook {
    return WalletAbiVerifyLook(
        origin = origin.takeIf { it.isNotBlank() },
        validation = validation.name,
        verifyUrl = verifyUrl.takeIf { it.isNotBlank() },
        isScam = isScam,
    )
}

private fun Wallet.Model.Namespace.Proposal.toBridge(): WalletAbiSessionNamespaceProposal {
    return WalletAbiSessionNamespaceProposal(
        chains = chains.orEmpty(),
        methods = methods,
        events = events,
    )
}

private fun Wallet.Model.Namespace.Session.toBridge(): WalletAbiSessionNamespace {
    return WalletAbiSessionNamespace(
        chains = chains.orEmpty(),
        accounts = accounts,
        methods = methods,
        events = events,
    )
}

private fun Map<String, WalletAbiSessionNamespace>.toWalletNamespaces(): Map<String, Wallet.Model.Namespace.Session> {
    return mapValues { (_, namespace) ->
        Wallet.Model.Namespace.Session(
            chains = namespace.chains.ifEmpty { null },
            accounts = namespace.accounts,
            methods = namespace.methods,
            events = namespace.events,
        )
    }
}

private fun Wallet.Model.SessionProposal.toBridge(
    verifyContext: WalletAbiVerifyLook?,
): WalletAbiSessionProposal {
    return WalletAbiSessionProposal(
        pairingTopic = pairingTopic,
        proposerPublicKey = proposerPublicKey,
        name = name.takeIf { it.isNotBlank() },
        description = description.takeIf { it.isNotBlank() },
        url = url.takeIf { it.isNotBlank() },
        icons = icons.map { it.toString() },
        redirect = redirect.takeIf { it.isNotBlank() },
        relayProtocol = relayProtocol.takeIf { it.isNotBlank() },
        requiredNamespaces = requiredNamespaces.mapValues { (_, namespace) -> namespace.toBridge() },
        optionalNamespaces = optionalNamespaces.mapValues { (_, namespace) -> namespace.toBridge() },
        properties = properties,
        scopedProperties = scopedProperties,
        verifyContext = verifyContext,
    )
}

private fun Wallet.Model.Session.toBridge(): WalletAbiSessionInfo {
    return WalletAbiSessionInfo(
        topic = topic,
        expiry = expiry,
        name = metaData?.name?.takeIf { it.isNotBlank() },
        description = metaData?.description?.takeIf { it.isNotBlank() },
        url = metaData?.url?.takeIf { it.isNotBlank() },
        icons = metaData?.icons.orEmpty().map { it.toString() },
        requiredNamespaces = requiredNamespaces.mapValues { (_, namespace) -> namespace.toBridge() },
        optionalNamespaces = optionalNamespaces.orEmpty()
            .mapValues { (_, namespace) -> namespace.toBridge() },
        namespaces = namespaces.mapValues { (_, namespace) -> namespace.toBridge() },
    )
}

private fun Wallet.Model.SessionRequest.toBridge(
    verifyContext: WalletAbiVerifyLook? = null,
): WalletAbiSessionRequest {
    return WalletAbiSessionRequest(
        topic = topic,
        chainId = chainId?.takeIf { it.isNotBlank() },
        requestId = request.id,
        method = request.method,
        paramsJson = request.params,
        peerName = peerMetaData?.name?.takeIf { it.isNotBlank() },
        peerDescription = peerMetaData?.description?.takeIf { it.isNotBlank() },
        peerUrl = peerMetaData?.url?.takeIf { it.isNotBlank() },
        peerIcons = peerMetaData?.icons.orEmpty().map { it.toString() },
        verifyContext = verifyContext,
    )
}
