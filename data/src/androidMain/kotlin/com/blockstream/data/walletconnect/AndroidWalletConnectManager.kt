package com.blockstream.data.walletconnect

import android.util.Base64
import com.blockstream.data.BTC_POLICY_ASSET
import com.blockstream.data.data.AppConfig
import com.blockstream.data.gdk.GdkSession
import com.blockstream.data.gdk.PsbtInputDetails
import com.blockstream.data.gdk.data.Account
import com.blockstream.data.gdk.data.AccountType
import com.blockstream.data.gdk.data.Address
import com.blockstream.data.gdk.data.CreateTransaction
import com.blockstream.data.gdk.data.Utxo
import com.blockstream.data.gdk.params.AddressParams
import com.blockstream.data.gdk.params.BroadcastTransactionParams
import com.blockstream.data.gdk.params.CreateTransactionParams
import com.blockstream.data.gdk.params.PsbtSignParams
import com.blockstream.data.gdk.params.SignMessageParams
import com.blockstream.data.managers.SessionManager
import com.blockstream.data.utils.hexToByteArray
import com.blockstream.data.utils.toHex
import com.blockstream.libwally.Wally as WallyJava
import com.blockstream.utils.Loggable
import com.russhwolf.settings.ObservableSettings
import com.russhwolf.settings.get
import com.russhwolf.settings.set
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.math.BigInteger
import java.security.MessageDigest

private val walletConnectReviewJson = Json { ignoreUnknownKeys = true }
private const val BIP32_HARDENED_OFFSET = 0x80000000L
private const val BASE58_ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
private val BASE58_RADIX = BigInteger.valueOf(58)
private val BASE58_INDEXES = IntArray(128) { -1 }.also { indexes ->
    BASE58_ALPHABET.forEachIndexed { index, char ->
        indexes[char.code] = index
    }
}
private val BIP32_MAINNET_PUBLIC_VERSION = byteArrayOf(0x04, 0x88.toByte(), 0xB2.toByte(), 0x1E)
private val BIP32_TESTNET_PUBLIC_VERSION = byteArrayOf(0x04, 0x35, 0x87.toByte(), 0xCF.toByte())
private val SLIP132_PUBLIC_VERSIONS = listOf(
    byteArrayOf(0x04, 0x9D.toByte(), 0x7C, 0xB2.toByte()), // ypub
    byteArrayOf(0x04, 0xB2.toByte(), 0x47, 0x46), // zpub
    byteArrayOf(0x02, 0x95.toByte(), 0xB4.toByte(), 0x3F), // Ypub
    byteArrayOf(0x02, 0xAA.toByte(), 0x7E, 0xD3.toByte()), // Zpub
    byteArrayOf(0x04, 0x4A, 0x52, 0x62), // upub
    byteArrayOf(0x04, 0x5F, 0x1C, 0xF6.toByte()), // vpub
    byteArrayOf(0x02, 0x42, 0x89.toByte(), 0xEF.toByte()), // Upub
    byteArrayOf(0x02, 0x57, 0x54, 0x83.toByte()), // Vpub
)
private val DESCRIPTOR_XPUB_REGEX = Regex("""[xt]pub[1-9A-HJ-NP-Za-km-z]+""")
private const val WALLETCONNECT_MEMO_MAX_BYTES = 80
private const val BITCOIN_STANDARD_DUST_SATS = 546L
private const val WALLETCONNECT_SIGN_PROTOCOL_ECDSA = "ecdsa"
private const val WALLETCONNECT_SIGN_PROTOCOL_BIP322 = "bip322"
private const val BIP322_MESSAGE_TAG = "BIP0322-signed-message"
private const val BIP322_FULL_PREFIX = "ful"
private val BIP322_TO_SPEND_PREVOUT = ByteArray(WallyJava.WALLY_TXHASH_LEN)
private const val BIP322_TO_SPEND_PREVOUT_INDEX = 0xffffffffL
private const val BIP322_TO_SIGN_SEQUENCE = 0L
private const val BIP322_TO_SIGN_VALUE = 0L
private const val BIP322_TO_SIGN_OUTPUT_SCRIPT_OP_RETURN = 0x6a.toByte()
private const val BITCOIN_MAINNET_CHAIN_ID = "bip122:000000000019d6689c085ae165831e93"
private const val BITCOIN_TESTNET_CHAIN_ID = "bip122:000000000933ea01ad0ee984209779ba"
private val BITCOIN_CHAIN_IDS = setOf(BITCOIN_MAINNET_CHAIN_ID, BITCOIN_TESTNET_CHAIN_ID)
private val BITCOIN_METHODS = listOf("getAccountAddresses", "sendTransfer", "signPsbt", "signMessage")
private val BITCOIN_EVENTS = listOf("bip122_addressesChanged")
class AndroidWalletConnectManager constructor(
    private val appConfig: AppConfig,
    private val sessionManager: SessionManager,
    private val settings: ObservableSettings
) : WalletConnectManager, Loggable() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var uriJob: Job? = null
    private var eventJob: Job? = null
    private var client: WalletConnectClient? = null
    private val bitcoinContexts = mutableMapOf<String, BitcoinAccountContext>()
    private val bitcoinAddressesChangedTopics = mutableSetOf<String>()
    private val approvalActionLock = Any()
    private val approvalActionsInFlight = mutableSetOf<String>()

    private val _status = MutableStateFlow(WalletConnectStatus.Disconnected)
    override val status: StateFlow<WalletConnectStatus> = _status.asStateFlow()

    private val _pendingApprovalCount = MutableStateFlow(0)
    override val pendingApprovalCount: StateFlow<Int> = _pendingApprovalCount.asStateFlow()

    private val _pendingApprovals = MutableStateFlow<List<WalletConnectApproval>>(emptyList())
    override val pendingApprovals: StateFlow<List<WalletConnectApproval>> = _pendingApprovals.asStateFlow()

    private val _activeSession = MutableStateFlow<WalletConnectSessionReview?>(null)
    override val activeSession: StateFlow<WalletConnectSessionReview?> = _activeSession.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    override val lastError: StateFlow<String?> = _lastError.asStateFlow()

    override fun start() {
        if (uriJob?.isActive == true) return

        val projectId = appConfig.reownProjectId?.takeIf { it.isNotBlank() }
        if (projectId == null) {
            _status.value = WalletConnectStatus.Disabled
            _lastError.value = "Missing Reown project id"
            logger.w { "WalletConnect disabled: missing Reown project id" }
            return
        }

        _status.value = WalletConnectStatus.Connecting
        uriJob = scope.launch {
            connect(projectId)
            sessionManager.pendingWalletConnectUri
                .filterNotNull()
                .collect { uri ->
                    pair(uri, projectId)
                    sessionManager.pendingWalletConnectUri.value = null
                }
        }
    }

    override fun stop() {
        uriJob?.cancel()
        eventJob?.cancel()
        uriJob = null
        eventJob = null
        runCatching { client?.shutdown() }
        client?.close()
        client = null
        _pendingApprovalCount.value = 0
        _pendingApprovals.value = emptyList()
        _activeSession.value = null
        bitcoinContexts.clear()
        bitcoinAddressesChangedTopics.clear()
        _status.value = WalletConnectStatus.Disconnected
    }

    override fun approve(approvalId: String) {
        val walletConnectClient = client ?: run {
            fail("WalletConnect client is not connected")
            return
        }
        if (!markApprovalActionStarted(approvalId)) return
        removePendingApproval(approvalId)

        scope.launch {
            runCatching {
                val approval = walletConnectClient.pendingApprovals()
                    .firstOrNull { it.id == approvalId }
                    ?: throw IllegalStateException("WalletConnect approval is no longer pending")
                val review = approval.reviewJson.toApprovalReview()
                if (!review.canApprove) {
                    throw UnsupportedOperationException(
                        review.approveUnavailableReason
                            ?: "This WalletConnect approval cannot be approved by this integration"
                    )
                }

                when (approval.kind) {
                    WalletConnectPendingApprovalKind.SESSION_PROPOSAL ->
                        approveSessionProposal(walletConnectClient, approval)

                    WalletConnectPendingApprovalKind.SESSION_REQUEST ->
                        approveSessionRequest(walletConnectClient, approval)

                    WalletConnectPendingApprovalKind.SESSION_UPDATE ->
                        approveSessionUpdate(walletConnectClient, approval)

                    WalletConnectPendingApprovalKind.SESSION_AUTHENTICATE ->
                        approveSessionAuthenticate(walletConnectClient, approval)
                }
            }.onSuccess {
                _lastError.value = null
                refreshPendingApprovals(walletConnectClient)
                refreshActiveSession(walletConnectClient)
            }.onFailure {
                _lastError.value = it.message
                refreshPendingApprovals(walletConnectClient)
                refreshActiveSession(walletConnectClient)
                logger.w(it) { "WalletConnect approval failed" }
            }
            markApprovalActionFinished(approvalId)
        }
    }

    override fun reject(approvalId: String) {
        val walletConnectClient = client ?: run {
            fail("WalletConnect client is not connected")
            return
        }
        if (!markApprovalActionStarted(approvalId)) return
        removePendingApproval(approvalId)

        scope.launch {
            runCatching {
                val approval = walletConnectClient.pendingApprovals()
                    .firstOrNull { it.id == approvalId }
                    ?: return@runCatching

                when (approval.kind) {
                    WalletConnectPendingApprovalKind.SESSION_PROPOSAL ->
                        walletConnectClient.rejectSession(approval.topic, approval.requestId, USER_REJECTED, "User rejected")

                    WalletConnectPendingApprovalKind.SESSION_REQUEST ->
                        walletConnectClient.respondSessionRequestError(
                            approval.topic,
                            approval.requestId,
                            USER_REJECTED,
                            "User rejected"
                        )

                    WalletConnectPendingApprovalKind.SESSION_AUTHENTICATE ->
                        walletConnectClient.rejectSessionAuthenticate(
                            approval.topic,
                            approval.requestId,
                            USER_REJECTED,
                            "User rejected"
                        )

                    WalletConnectPendingApprovalKind.SESSION_UPDATE ->
                        walletConnectClient.rejectSessionUpdate(approval.topic, approval.requestId, USER_REJECTED, "User rejected")
                }
            }.onSuccess {
                _lastError.value = null
                refreshPendingApprovals(walletConnectClient)
                refreshActiveSession(walletConnectClient)
            }.onFailure {
                _lastError.value = it.message
                refreshPendingApprovals(walletConnectClient)
                refreshActiveSession(walletConnectClient)
                logger.w(it) { "WalletConnect rejection failed" }
            }
            markApprovalActionFinished(approvalId)
        }
    }

    override fun disconnectActiveSession() {
        val walletConnectClient = client ?: run {
            fail("WalletConnect client is not connected")
            return
        }

        scope.launch {
            runCatching {
                val topic = _activeSession.value?.topic
                    ?: throw IllegalStateException("No active WalletConnect session")
                walletConnectClient.disconnect(
                    topic = topic,
                    errorCode = USER_DISCONNECTED,
                    message = "User disconnected."
                )
            }.onSuccess {
                _lastError.value = null
                refreshPendingApprovals(walletConnectClient)
                refreshActiveSession(walletConnectClient)
                if (_activeSession.value == null) {
                    _status.value = WalletConnectStatus.Connected
                }
            }.onFailure {
                _lastError.value = it.message
                logger.w(it) { "WalletConnect disconnect failed" }
            }
        }
    }

    override fun extendActiveSession() {
        val walletConnectClient = client ?: run {
            fail("WalletConnect client is not connected")
            return
        }

        scope.launch {
            runCatching {
                val topic = _activeSession.value?.topic
                    ?: throw IllegalStateException("No active WalletConnect session")
                walletConnectClient.extendSession(topic)
            }.onSuccess {
                _lastError.value = null
                refreshActiveSession(walletConnectClient)
            }.onFailure {
                _lastError.value = it.message
                logger.w(it) { "WalletConnect session extension failed" }
            }
        }
    }

    private fun connect(projectId: String) {
        runCatching {
            val config = WalletConnectClientConfig(
                projectId = projectId,
                relayAuthSeedHex = relayAuthSeedHex(),
                authSubject = "wallet",
                userAgent = "wc-2/rust-poc-0.1.0/macos/native",
                receiveTimeoutMs = 15_000,
                maxQueuedEvents = 64,
                automaticReconnectCooldownMs = 5_000,
                receiveTimeoutsBeforeReconnect = 2
            )
            WalletConnectClient.connect(config)
        }.onSuccess {
            client = it
            _lastError.value = null
            _status.value = WalletConnectStatus.Connected
            logger.i { "WalletConnect client connected" }
            refreshActiveSession(it)
            pollEvents(it)
        }.onFailure {
            fail(it.message ?: "WalletConnect client connection failed")
            logger.w(it) { "WalletConnect client connection failed" }
        }
    }

    private fun pair(uri: String, projectId: String) {
        val walletConnectClient = client ?: run {
            fail("WalletConnect client is not connected")
            return
        }

        _status.value = WalletConnectStatus.Pairing
        pairWithClient(walletConnectClient, uri).onSuccess {
            onPairingSubscribed(walletConnectClient)
        }.onFailure { error ->
            if (canRetryPairingWithFreshClient(walletConnectClient)) {
                logger.w(error) { "WalletConnect pairing failed; replacing idle client and retrying once" }
                val retryClient = replaceClientForPairRetry(projectId)
                if (retryClient != null) {
                    pairWithClient(retryClient, uri).onSuccess {
                        onPairingSubscribed(retryClient)
                    }.onFailure {
                        fail(it.message ?: "WalletConnect pairing failed")
                        logger.w(it) { "WalletConnect pairing retry failed" }
                    }
                } else {
                    fail(error.message ?: "WalletConnect pairing failed")
                    logger.w(error) { "WalletConnect pairing failed and client replacement was unavailable" }
                }
            } else {
                fail(error.message ?: "WalletConnect pairing failed")
                logger.w(error) { "WalletConnect pairing failed" }
            }
        }
    }

    private fun pairWithClient(walletConnectClient: WalletConnectClient, uri: String): Result<String> =
        runCatching { walletConnectClient.pair(uri = uri, origin = "deepLink") }

    private fun onPairingSubscribed(walletConnectClient: WalletConnectClient) {
        _lastError.value = null
        _status.value = WalletConnectStatus.Paired
        refreshPendingApprovals(walletConnectClient)
        logger.i { "WalletConnect pairing subscribed" }
    }

    private fun canRetryPairingWithFreshClient(walletConnectClient: WalletConnectClient): Boolean {
        val hasActiveSession = runCatching { walletConnectClient.activeSessionJson() != null }.getOrDefault(true)
        val hasPendingApproval = runCatching { walletConnectClient.pendingApprovals().isNotEmpty() }.getOrDefault(true)
        return !hasActiveSession && !hasPendingApproval
    }

    private fun replaceClientForPairRetry(projectId: String): WalletConnectClient? {
        eventJob?.cancel()
        eventJob = null
        runCatching { client?.shutdown() }
        client?.close()
        client = null
        bitcoinContexts.clear()
        bitcoinAddressesChangedTopics.clear()
        _pendingApprovalCount.value = 0
        _pendingApprovals.value = emptyList()
        _activeSession.value = null
        _status.value = WalletConnectStatus.Connecting
        connect(projectId)
        return client
    }

    private fun pollEvents(walletConnectClient: WalletConnectClient) {
        eventJob?.cancel()
        eventJob = scope.launch {
            while (true) {
                runCatching {
                    walletConnectClient.nextEvent()
                }.onSuccess { event ->
                    if (event.kind != WalletConnectEventKind.NONE) {
                        _lastError.value = null
                        logger.i { "WalletConnect event: ${event.kind}" }
                        if (event.kind == WalletConnectEventKind.SESSION_DELETE ||
                            event.kind == WalletConnectEventKind.SESSION_EXPIRED
                        ) {
                            event.topic?.also { topic ->
                                bitcoinContexts.remove(topic)
                                bitcoinAddressesChangedTopics.remove(topic)
                            }
                        }
                        refreshPendingApprovals(walletConnectClient)
                        refreshActiveSession(walletConnectClient)
                        if (
                            (event.kind == WalletConnectEventKind.SESSION_DELETE ||
                                event.kind == WalletConnectEventKind.SESSION_EXPIRED) &&
                            _activeSession.value == null
                        ) {
                            _status.value = WalletConnectStatus.Connected
                        }
                    }
                }.onFailure { error ->
                    val hasPendingApproval = runCatching {
                        walletConnectClient.pendingApprovals().isNotEmpty()
                    }.getOrDefault(false)
                    val hasActiveSession = runCatching {
                        walletConnectClient.activeSessionReviewJson() != null
                    }.getOrDefault(false)
                    if (error.isWalletConnectIdleReceiveTimeout()) {
                        _lastError.value = null
                    } else if (!hasPendingApproval && hasActiveSession) {
                        _lastError.value = error.message
                    } else if (!hasPendingApproval) {
                        _lastError.value = null
                    }
                    logger.w(error) { "WalletConnect event polling failed" }
                    delay(5_000)
                }
            }
        }
    }

    private fun refreshPendingApprovals(walletConnectClient: WalletConnectClient) {
        runCatching {
            walletConnectClient.pendingApprovals()
        }.onSuccess { approvals ->
            _pendingApprovalCount.value = approvals.size
            _pendingApprovals.value = approvals.map { it.toApproval() }
        }.onFailure {
            logger.w(it) { "WalletConnect pending approval refresh failed" }
        }
    }

    private fun markApprovalActionStarted(approvalId: String): Boolean =
        synchronized(approvalActionLock) {
            approvalActionsInFlight.add(approvalId)
        }

    private fun markApprovalActionFinished(approvalId: String) {
        synchronized(approvalActionLock) {
            approvalActionsInFlight.remove(approvalId)
        }
    }

    private fun removePendingApproval(approvalId: String) {
        val remaining = _pendingApprovals.value.filterNot { it.id == approvalId }
        if (remaining.size != _pendingApprovals.value.size) {
            _pendingApprovals.value = remaining
            _pendingApprovalCount.value = remaining.size
        }
    }

    private fun refreshActiveSession(walletConnectClient: WalletConnectClient) {
        runCatching {
            walletConnectClient.activeSessionReviewJson()
        }.onSuccess { reviewJson ->
            _activeSession.value = reviewJson?.toSessionReview()
        }.onFailure {
            logger.w(it) { "WalletConnect active session refresh failed" }
        }
    }

    private suspend fun approveSessionProposal(
        walletConnectClient: WalletConnectClient,
        approval: WalletConnectPendingApproval
    ) {
        val requestedChainIds = approval.reviewJson.asJsonObject().walletConnectBitcoinChainIds()
        val contextList = activeBitcoinAccountContexts(requestedChainIds)
        val capabilities = contextList.toWalletConnectCapabilities()
        val sessionInfo = walletConnectClient.approveSession(
            topic = approval.topic,
            requestId = approval.requestId,
            capabilities = capabilities,
            walletMetadata = walletMetadata()
        )
        val bitcoinContext = contextList.firstOrNull { sessionInfo.authorizesBitcoinChain(it.chainId) }
            ?: contextList.first()
        bitcoinContexts[sessionInfo.topic] = bitcoinContext
        _status.value = WalletConnectStatus.Paired
        logger.i { "WalletConnect Bitcoin session approved" }
        if (sessionInfo.authorizesBitcoinEvent(BITCOIN_EVENTS.first(), bitcoinContext.chainId)) {
            bitcoinAddressesChangedTopics += sessionInfo.topic
            emitBitcoinAddressesChangedAsync(walletConnectClient, sessionInfo.topic, bitcoinContext)
        } else {
            bitcoinAddressesChangedTopics -= sessionInfo.topic
            logger.i { "WalletConnect Bitcoin addressesChanged event not emitted; session did not authorize it" }
        }
    }

    private suspend fun approveSessionAuthenticate(
        walletConnectClient: WalletConnectClient,
        approval: WalletConnectPendingApproval
    ) {
        val requestedChainIds = approval.reviewJson.asJsonObject().walletConnectBitcoinChainIds()
        val context = activeBitcoinAccountContexts(requestedChainIds).first()
        val signingReview = walletConnectClient.sessionAuthenticateSigningJson(
            topic = approval.topic,
            requestId = approval.requestId,
            accountId = context.accountId
        ).asJsonObject()
        val signingAccount = signingReview.string("account")
            ?: throw IllegalArgumentException("WalletConnect authentication signing review is missing account")
        if (signingAccount != context.accountId) {
            throw IllegalStateException("WalletConnect authentication signing account does not match the selected wallet account")
        }
        val message = signingReview.string("message")
            ?: throw IllegalArgumentException("WalletConnect authentication signing review is missing message")
        val signature = context.session.signMessage(
            network = context.account.network,
            params = SignMessageParams(
                address = context.accountAddress,
                message = message
            )
        ).signature.toWalletConnectHex().let { "0x$it" }
        val cacaosJson = walletConnectClient.sessionAuthenticateCacaosJson(
            topic = approval.topic,
            requestId = approval.requestId,
            accountId = context.accountId,
            signature = signature,
            signatureType = "eip191"
        )
        val outcome = walletConnectClient.approveSessionAuthenticate(
            topic = approval.topic,
            requestId = approval.requestId,
            walletMetadata = walletMetadata(),
            cacaosJson = cacaosJson,
            sessionCapabilities = null
        )

        outcome.session?.also { sessionInfo ->
            bitcoinContexts[sessionInfo.topic] = context
            _status.value = WalletConnectStatus.Paired
        } ?: run {
            _status.value = WalletConnectStatus.Connected
        }
    }

    private suspend fun approveSessionUpdate(
        walletConnectClient: WalletConnectClient,
        approval: WalletConnectPendingApproval
    ) {
        val requestedNamespaces = approval.reviewJson
            .asJsonObject()
            .walletConnectRequestedNamespaces()
        val requestedChainIds = requestedNamespaces
            .flatMap { it.chainIds }
            .toSet()
        val contextList = activeBitcoinAccountContexts(requestedChainIds)
        val capabilities = contextList.toWalletConnectCapabilitiesForRequestedNamespaces(requestedNamespaces)
        val acknowledged = walletConnectClient.approveSessionUpdate(
            topic = approval.topic,
            requestId = approval.requestId,
            capabilities = capabilities
        )
        if (!acknowledged) {
            return
        }

        val approvedContexts = contextList.filter { context ->
            capabilities.chains.any { chain ->
                chain.chainId == context.chainId && context.accountId in chain.accounts
            }
        }
        approvedContexts.firstOrNull()?.also { bitcoinContexts[approval.topic] = it }

        val eventName = BITCOIN_EVENTS.first()
        val eventContext = approvedContexts.firstOrNull { context ->
            capabilities.chains.any { chain ->
                chain.chainId == context.chainId && eventName in chain.events
            }
        }
        if (eventContext != null) {
            bitcoinAddressesChangedTopics += approval.topic
            emitBitcoinAddressesChangedAsync(walletConnectClient, approval.topic, eventContext)
        } else {
            bitcoinAddressesChangedTopics -= approval.topic
        }
        _status.value = WalletConnectStatus.Paired
    }

    private fun emitBitcoinAddressesChangedAsync(
        walletConnectClient: WalletConnectClient,
        topic: String,
        context: BitcoinAccountContext
    ) {
        scope.launch {
            delay(BITCOIN_EVENT_EMIT_INITIAL_DELAY_MS)
            emitBitcoinAddressesChanged(walletConnectClient, topic, context)
        }
    }

    private fun emitBitcoinAddressesChangedForTopicAsync(
        walletConnectClient: WalletConnectClient,
        topic: String
    ) {
        if (topic !in bitcoinAddressesChangedTopics) {
            logger.i { "WalletConnect Bitcoin addressesChanged event not emitted; session did not authorize it" }
            return
        }

        scope.launch {
            val chainId = bitcoinContexts[topic]?.chainId
            val context = runCatching { activeBitcoinAccountContext(chainId) }
                .getOrElse {
                    logger.w(it) { "WalletConnect Bitcoin addressesChanged event skipped; account context unavailable" }
                    return@launch
                }
            bitcoinContexts[topic] = context
            emitBitcoinAddressesChanged(walletConnectClient, topic, context)
        }
    }

    private suspend fun emitBitcoinAddressesChanged(
        walletConnectClient: WalletConnectClient,
        topic: String,
        context: BitcoinAccountContext
    ) {
        repeat(BITCOIN_EVENT_EMIT_ATTEMPTS) { attempt ->
            val result = runCatching {
                walletConnectClient.emitSessionEvent(
                    topic = topic,
                    paramsJson = context.bitcoinAddressesChangedEventParamsJson()
                )
            }
            if (result.isSuccess) {
                logger.i { "WalletConnect Bitcoin addressesChanged event emitted" }
                return
            }

            val error = result.exceptionOrNull()
            if (attempt == BITCOIN_EVENT_EMIT_ATTEMPTS - 1) {
                logger.w(error) { "WalletConnect Bitcoin addressesChanged event failed" }
            } else {
                logger.w(error) { "WalletConnect Bitcoin addressesChanged event retrying" }
                delay(BITCOIN_EVENT_EMIT_RETRY_DELAY_MS)
            }
        }
    }

    private suspend fun approveSessionRequest(
        walletConnectClient: WalletConnectClient,
        approval: WalletConnectPendingApproval
    ) {
        val review = approval.reviewJson.asJsonObject()
        val request = review.bitcoinRequest()
        val type = request.string("type")
            ?: throw IllegalArgumentException("Bitcoin request review is missing request type")
        val requestedChainId = request.walletConnectBitcoinChainId()
        val context = bitcoinContexts[approval.topic]?.takeIf { requestedChainId == null || it.chainId == requestedChainId }
            ?: activeBitcoinAccountContext(requestedChainId)

        val executionError = runCatching {
            respondApprovedBitcoinRequest(walletConnectClient, approval, request, type, context)
        }.exceptionOrNull() ?: return

        walletConnectClient.respondSessionRequestError(
            approval.topic,
            approval.requestId,
            REQUEST_FAILED,
            executionError.message ?: "WalletConnect request failed"
        )
        logger.w(executionError) { "WalletConnect request execution failed" }
    }

    private fun JsonObject.bitcoinRequest(): JsonObject {
        val bitcoin = obj("bitcoin")
            ?: throw UnsupportedOperationException("Bitcoin request is missing reviewed execution payload")
        val request = bitcoin.obj("request")
            ?: throw UnsupportedOperationException("Bitcoin request is missing reviewed execution payload")
        val chainId = bitcoin.string("chainId")
            ?: throw UnsupportedOperationException("Bitcoin request is missing reviewed chain id")
        val type = request.string("type")
            ?: throw UnsupportedOperationException("Bitcoin request is missing reviewed request type")
        return buildJsonObject {
            put("chainId", chainId)
            put("type", type)
            request.forEach { (key, value) ->
                put(key, value)
            }
        }
    }

    private suspend fun respondApprovedBitcoinRequest(
        walletConnectClient: WalletConnectClient,
        approval: WalletConnectPendingApproval,
        request: JsonObject,
        type: String,
        context: BitcoinAccountContext
    ) {
        validateRequestAccount(type, request, context)

        when (type) {
            "getAccountAddresses" -> {
                respondBitcoinRequestSuccess(
                    walletConnectClient = walletConnectClient,
                    approval = approval,
                    context = context,
                    resultJson = context.addresses.toBitcoinAddressesJson(request.stringArray("intentions"))
                )
            }

            "signMessage" -> {
                val protocol = request.string("protocol")
                    ?: throw IllegalArgumentException("signMessage is missing protocol")
                val signingAddress = request.string("signingAddress")
                    ?: throw IllegalArgumentException("signMessage is missing signingAddress")
                val message = request.string("message")
                    ?: throw IllegalArgumentException("signMessage is missing message")
                val signingEntry = context.signingEntry(signingAddress)

                val result = when (protocol) {
                    WALLETCONNECT_SIGN_PROTOCOL_ECDSA -> buildJsonObject {
                        val signature = context.session.signMessage(
                            network = context.account.network,
                            params = SignMessageParams(
                                address = signingAddress,
                                message = message
                            )
                        ).signature.toWalletConnectHex()

                        put("address", signingAddress)
                        put("signature", signature)
                    }

                    WALLETCONNECT_SIGN_PROTOCOL_BIP322 -> {
                        val signature = context.signBip322Message(signingEntry, message)
                        buildJsonObject {
                            put("address", signingAddress)
                            put("signature", signature.signatureHex)
                            put("messageHash", signature.messageHashHex)
                        }
                    }

                    else -> throw UnsupportedOperationException("Unsupported Bitcoin message signing protocol: $protocol")
                }

                respondBitcoinRequestSuccess(
                    walletConnectClient = walletConnectClient,
                    approval = approval,
                    context = context,
                    resultJson = result.toString()
                )
            }

            "sendTransfer" -> {
                if (!context.account.isSinglesig) {
                    throw UnsupportedOperationException("WalletConnect sendTransfer currently requires a singlesig Bitcoin account")
                }

                val recipientAddress = request.string("recipientAddress")
                    ?: throw IllegalArgumentException("sendTransfer is missing recipientAddress")
                val amountSats = request.string("amountSats")?.toLongOrNull()
                    ?: throw IllegalArgumentException("sendTransfer is missing amountSats")
                val changeAddress = request.string("changeAddress")
                val changeAddressDetails = changeAddress?.let { address ->
                    context.walletAddresses[address]
                }
                if (changeAddress != null && changeAddressDetails == null) {
                    throw IllegalArgumentException("sendTransfer changeAddress does not belong to the connected Bitcoin account")
                }
                val utxos = context.session.getUnspentOutputs(context.account).unspentOutputs
                val createTransaction = runCatching {
                    context.session.createTransaction(
                        network = context.account.network,
                        params = CreateTransactionParams(
                            subaccount = context.account.pointer,
                            addressees = listOf(
                                AddressParams(
                                    address = recipientAddress,
                                    satoshi = amountSats
                                ).toJsonElement()
                            ),
                            utxos = utxos,
                            changeAddress = changeAddressDetails?.toBitcoinChangeAddressJson()
                        )
                    )
                }.getOrElse {
                    throw IllegalStateException(walletConnectTransactionError("sendTransfer", it))
                }
                createTransaction.error?.takeIf { it.isNotBlank() }?.also {
                    throw IllegalStateException(walletConnectTransactionError("sendTransfer", IllegalStateException(it)))
                }
                val transactionToSign = createTransaction.withWalletConnectMemoOutput(request.string("memo"))
                val signedTransaction = context.session.signTransaction(context.account.network, transactionToSign)
                val transactionHex = signedTransaction.transaction
                    ?: throw IllegalStateException("sendTransfer signing did not return a transaction")
                val processed = context.session.broadcastTransaction(
                    network = context.account.network,
                    broadcastTransaction = BroadcastTransactionParams(transaction = transactionHex)
                )
                val txid = processed.txHash
                    ?: throw IllegalStateException("sendTransfer broadcast did not return a txid")

                respondBitcoinRequestSuccess(
                    walletConnectClient = walletConnectClient,
                    approval = approval,
                    context = context,
                    resultJson = buildJsonObject {
                        put("txid", txid)
                    }.toString()
                )
                emitBitcoinAddressesChangedForTopicAsync(walletConnectClient, approval.topic)
            }

            "signPsbt" -> {
                val broadcast = request.booleanOrNull("broadcast")
                    ?: throw IllegalArgumentException("signPsbt is missing broadcast")
                val signedPsbtResult = signPsbt(context, request, broadcast)

                respondBitcoinRequestSuccess(
                    walletConnectClient = walletConnectClient,
                    approval = approval,
                    context = context,
                    resultJson = signedPsbtResult.toString()
                )
                if (broadcast) {
                    emitBitcoinAddressesChangedForTopicAsync(walletConnectClient, approval.topic)
                }
            }

            else -> throw UnsupportedOperationException(
                "$type needs a reviewed execution payload before the app can safely approve it"
            )
        }
    }

    private fun respondBitcoinRequestSuccess(
        walletConnectClient: WalletConnectClient,
        approval: WalletConnectPendingApproval,
        context: BitcoinAccountContext,
        resultJson: String
    ) {
        walletConnectClient.respondBitcoinRequestSuccessJson(
            topic = approval.topic,
            requestId = approval.requestId,
            resultJson = resultJson
        )
    }

    private fun validateRequestAccount(
        type: String,
        request: JsonObject,
        context: BitcoinAccountContext
    ) {
        val requestedAccount = request.string("account")
            ?: throw IllegalArgumentException("$type is missing account")
        if (
            requestedAccount != context.accountAddress &&
            requestedAccount != context.accountId
        ) {
            throw IllegalArgumentException("$type account does not match the connected Bitcoin account")
        }
    }

    private suspend fun signPsbt(
        context: BitcoinAccountContext,
        request: JsonObject,
        broadcast: Boolean
    ): JsonObject {
        if (!context.account.isSinglesig) {
            throw UnsupportedOperationException("WalletConnect signPsbt currently requires a singlesig Bitcoin account")
        }

        val psbt = request.string("psbt")
            ?: throw IllegalArgumentException("signPsbt is missing psbt")
        val signInputs = request.signInputs()
        val psbtInputDetails = context.session.psbtInputDetails(psbt)
        val walletSelectedInputs = signInputs.isEmpty()
        val requestedOutpoints = if (walletSelectedInputs) {
            psbtInputDetails.mapIndexed { index, inputDetails ->
                val sighashType = inputDetails.sighashType ?: DEFAULT_SIGHASH_ALL
                if (sighashType != DEFAULT_SIGHASH_ALL) {
                    throw IllegalArgumentException(
                        "signPsbt input index $index uses a non-default sighash type without signInputs authorization"
                    )
                }
                inputDetails.outpoint
            }.toSet()
        } else {
            signInputs.map { signInput ->
                val inputDetails = psbtInputDetails.getOrNull(signInput.index)
                    ?: throw IllegalArgumentException("signPsbt input index ${signInput.index} is outside the PSBT input range")
                val sighashType = inputDetails.sighashType ?: DEFAULT_SIGHASH_ALL
                if (sighashType !in signInput.sighashTypes) {
                    throw IllegalArgumentException("signPsbt input index ${signInput.index} uses an unapproved sighash type")
                }
                inputDetails.outpoint
            }.toSet()
        }
        if (requestedOutpoints.isEmpty()) {
            throw IllegalArgumentException("signPsbt has no PSBT inputs to sign")
        }

        if (!walletSelectedInputs) {
            val requestedSigners = signInputs.map { it.address }.toSet()
            if (requestedSigners.any { it !in context.walletAddresses }) {
                throw IllegalArgumentException("signPsbt requested signer does not belong to the connected Bitcoin account")
            }
        }

        val unspentOutputs = context.session.getUnspentOutputs(context.account)
        val filteredUtxos = unspentOutputs.unspentOutputs
            .mapValues { entry ->
                entry.value.filter { utxoJson ->
                    val utxo = walletConnectReviewJson.decodeFromJsonElement<Utxo>(utxoJson)
                    utxo.outpoint in requestedOutpoints
                }
            }
            .filterValues { it.isNotEmpty() }
        val matchedOutpoints = filteredUtxos.values.flatten()
            .map { walletConnectReviewJson.decodeFromJsonElement<Utxo>(it).outpoint }
            .toSet()
        val missingOutpoints = requestedOutpoints - matchedOutpoints
        if (walletSelectedInputs) {
            if (matchedOutpoints.isEmpty()) {
                throw IllegalArgumentException("signPsbt has no inputs spendable by the connected account")
            }
        } else if (missingOutpoints.isNotEmpty()) {
            throw IllegalArgumentException("signPsbt requested inputs that are not spendable by the connected account")
        }

        val signedPsbt = context.session.psbtSign(
            network = context.account.network,
            params = PsbtSignParams(
                psbt = psbt,
                utxos = filteredUtxos
            )
        ).psbt

        return buildJsonObject {
            put("psbt", signedPsbt)

            if (broadcast) {
                val processed = context.session.broadcastTransaction(
                    network = context.account.network,
                    broadcastTransaction = BroadcastTransactionParams(psbt = signedPsbt)
                )
                put(
                    "txid",
                    processed.txHash ?: throw IllegalStateException("signPsbt broadcast did not return a txid")
                )
            }
        }
    }

    private suspend fun activeBitcoinAccountContext(chainId: String? = null): BitcoinAccountContext =
        activeBitcoinAccountContexts(chainId?.let { setOf(it) } ?: emptySet()).first()

    private suspend fun activeBitcoinAccountContexts(chainIds: Set<String> = emptySet()): List<BitcoinAccountContext> {
        val requestedChainIds = chainIds
            .map(WalletConnectClient::canonicalChainId)
            .filter { it in BITCOIN_CHAIN_IDS }
            .toSet()
        val sessionAndAccount = sessionManager.getSessions()
            .filter { it.isConnected }
            .flatMap { session ->
                session.accounts.value
                    .filter { account ->
                        account.walletConnectBitcoinChainId?.let { chainId ->
                            account.isBitcoin && !account.isLightning && !account.hidden &&
                                (requestedChainIds.isEmpty() || chainId in requestedChainIds)
                        } == true
                    }
                    .map { session to it }
            }
            .sortedBy { (_, account) -> account.walletConnectPreferenceRank() }
        if (sessionAndAccount.isEmpty()) {
            val network = requestedChainIds.firstOrNull()?.walletConnectBitcoinNetworkLabel() ?: "Bitcoin"
            throw IllegalStateException("No active $network account is connected")
        }

        return sessionAndAccount.map { (session, account) ->
        val previousAddresses = runCatching {
            session.getPreviousAddresses(account, null).addresses
        }.getOrElse {
            logger.w(it) { "WalletConnect Bitcoin previous addresses unavailable" }
            emptyList()
        }
        val accountAddressDetails = previousAddresses.walletConnectFirstExternalAddress()
            ?: account.walletConnectFirstExternalAddress()
            ?: session.getReceiveAddress(account)
        val accountAddress = accountAddressDetails.address
        val walletAddressEntries = linkedMapOf<String, WalletConnectBitcoinAddress>()
        fun rememberWalletAddress(address: Address) {
            val entry = address.toWalletConnectBitcoinAddress(account)
            val current = walletAddressEntries[address.address]
            if (current == null || (current.address.walletConnectScriptPubkey == null && address.walletConnectScriptPubkey != null)) {
                walletAddressEntries[address.address] = entry
            }
        }

        rememberWalletAddress(accountAddressDetails)
        previousAddresses.forEach(::rememberWalletAddress)
        account.walletConnectFutureAddresses(previousAddresses).forEach(::rememberWalletAddress)

        val chainId = account.walletConnectBitcoinChainId
            ?: throw IllegalStateException("WalletConnect Bitcoin account has an unsupported network")
        val accountId = WalletConnectClient.canonicalAccountId("$chainId:$accountAddress")
        val addresses = walletAddressEntries.values.toList()
        val addressesJson = addresses.toBitcoinAddressesJson()

        BitcoinAccountContext(
            session = session,
            account = account,
            accountAddress = accountAddress,
            chainId = chainId,
            accountId = accountId,
            addressesJson = addressesJson,
            addresses = addresses,
            walletAddresses = walletAddressEntries.mapValues { it.value.address }
        )
        }
    }

    private fun walletConnectTransactionError(method: String, error: Throwable): String {
        val message = error.message ?: "$method transaction creation failed"
        return if ("key 'utxos' not found" in message) {
            "$method could not create a transaction because the connected account has no spendable UTXOs"
        } else {
            "$method transaction creation failed: $message"
        }
    }

    private fun walletMetadata(): WalletConnectWalletMetadata {
        return WalletConnectWalletMetadata(
            name = "Blockstream Green",
            description = "Blockstream Green Bitcoin wallet",
            url = "https://blockstream.com/green/",
            icons = listOf("https://blockstream.com/img/icons/green.png"),
            redirectNative = "blockstream-green://",
            redirectLinkMode = false
        )
    }

    private fun relayAuthSeedHex(): String {
        return settings[KEY_RELAY_AUTH_SEED_HEX]
            ?: WalletConnectClient.generateRelayAuthSeedHex().also {
                settings[KEY_RELAY_AUTH_SEED_HEX] = it
            }
    }

    private fun fail(message: String) {
        _status.value = WalletConnectStatus.Failed
        _lastError.value = message
    }

    companion object {
        private const val KEY_RELAY_AUTH_SEED_HEX = "walletconnect_relay_auth_seed_hex"
        private const val DEFAULT_SIGHASH_ALL = 1L
        private const val USER_REJECTED = 5_000L
        private const val USER_DISCONNECTED = 6_000L
        private const val REQUEST_FAILED = 5_001L
        private const val BITCOIN_EVENT_EMIT_ATTEMPTS = 3
        private const val BITCOIN_EVENT_EMIT_INITIAL_DELAY_MS = 1_000L
        private const val BITCOIN_EVENT_EMIT_RETRY_DELAY_MS = 2_000L
    }
}

private data class BitcoinAccountContext(
    val session: GdkSession,
    val account: Account,
    val accountAddress: String,
    val chainId: String,
    val accountId: String,
    val addressesJson: String,
    val addresses: List<WalletConnectBitcoinAddress>,
    val walletAddresses: Map<String, Address>
)

private data class WalletConnectBitcoinAddress(
    val address: Address,
    val publicKey: String?,
    val path: String?,
    val intention: String = "payment"
)

private data class SignPsbtInput(
    val address: String,
    val index: Int,
    val sighashTypes: List<Long>
)

private data class PsbtOutpoint(
    val txHash: String,
    val outputIndex: Long
)

private data class WalletConnectBip322Signature(
    val signatureHex: String,
    val messageHashHex: String
)

private data class WalletConnectRequestedNamespace(
    val chainIds: Set<String>,
    val accounts: Set<String>,
    val methods: Set<String>,
    val events: Set<String>
)

private fun List<BitcoinAccountContext>.toWalletConnectCapabilities(): WalletConnectCapabilities {
    val addressesJson = flatMap { it.addresses }.toBitcoinAddressesJson()
    return WalletConnectCapabilities(
        chains = map { context ->
            WalletConnectChainCapability(
                chainId = context.chainId,
                accounts = listOf(context.accountId),
                methods = BITCOIN_METHODS,
                events = BITCOIN_EVENTS
            )
        },
        sessionPropertiesJson = WalletConnectClient.bitcoinSessionPropertiesJson(addressesJson)
    )
}

private fun List<BitcoinAccountContext>.toWalletConnectCapabilitiesForRequestedNamespaces(
    requestedNamespaces: List<WalletConnectRequestedNamespace>
): WalletConnectCapabilities {
    val includedContexts = mutableListOf<BitcoinAccountContext>()
    val chains = mapNotNull { context ->
        val matchingNamespaces = requestedNamespaces.filter { namespace ->
            context.chainId in namespace.chainIds || context.accountId in namespace.accounts
        }
        if (matchingNamespaces.none { context.accountId in it.accounts }) {
            return@mapNotNull null
        }

        includedContexts += context
        WalletConnectChainCapability(
            chainId = context.chainId,
            accounts = listOf(context.accountId),
            methods = BITCOIN_METHODS.filter { method ->
                matchingNamespaces.any { method in it.methods }
            },
            events = BITCOIN_EVENTS.filter { event ->
                matchingNamespaces.any { event in it.events }
            }
        )
    }

    if (chains.isEmpty()) {
        throw IllegalArgumentException("Session update does not request an active Bitcoin account")
    }

    val addressesJson = includedContexts.flatMap { it.addresses }.toBitcoinAddressesJson()
    return WalletConnectCapabilities(
        chains = chains,
        sessionPropertiesJson = WalletConnectClient.bitcoinSessionPropertiesJson(addressesJson)
    )
}

private fun BitcoinAccountContext.bitcoinAddressesChangedEventParamsJson(): String =
    WalletConnectClient.bitcoinAddressesChangedEventParamsJson(
        chainId = chainId,
        addressesJson = addressesJson
    )

private val WalletConnectPendingApproval.id: String
    get() = "${kind.name}:$topic:$requestId"

private fun WalletConnectSessionInfo.authorizesBitcoinChain(chainId: String): Boolean {
    val namespaces = runCatching {
        sessionJson.asJsonObject().obj("namespaces") ?: return false
    }.getOrElse {
        return false
    }

    return namespaces.values
        .mapNotNull { it as? JsonObject }
        .any { namespace ->
            val chains = namespace.stringArray("chains")
            val accounts = namespace.stringArray("accounts")
            chainId in chains || accounts.any { account -> account.startsWith("$chainId:") }
        }
}

private fun WalletConnectSessionInfo.authorizesBitcoinEvent(event: String, chainId: String): Boolean {
    val namespaces = runCatching {
        sessionJson.asJsonObject().obj("namespaces") ?: return false
    }.getOrElse {
        return false
    }

    return namespaces.values
        .mapNotNull { it as? JsonObject }
        .any { namespace ->
            val events = namespace.stringArray("events")
            if (event !in events) {
                return@any false
            }

            val chains = namespace.stringArray("chains")
            chains.isEmpty() || chainId in chains
        }
}

private val Address.walletConnectScriptPubkey: String?
    get() = scriptPubkey ?: script

private val Account.walletConnectBitcoinChainId: String?
    get() = when {
        isBitcoinMainnet -> WalletConnectClient.canonicalChainId(BITCOIN_MAINNET_CHAIN_ID)
        isBitcoinTestnet -> WalletConnectClient.canonicalChainId(BITCOIN_TESTNET_CHAIN_ID)
        else -> null
    }

private val Account.walletConnectWallyNetwork: Long
    get() = if (network.isBitcoinTestnet) {
        WallyJava.WALLY_NETWORK_BITCOIN_TESTNET.toLong()
    } else {
        WallyJava.WALLY_NETWORK_BITCOIN_MAINNET.toLong()
    }

private val Account.walletConnectSegwitAddressFamily: String
    get() = if (network.isBitcoinTestnet) "tb" else "bc"

private val Account.walletConnectCoinType: Long
    get() = if (network.isBitcoinTestnet) 1L else 0L

private fun String.walletConnectBitcoinNetworkLabel(): String =
    when (WalletConnectClient.canonicalChainId(this)) {
        BITCOIN_MAINNET_CHAIN_ID -> "Bitcoin mainnet"
        BITCOIN_TESTNET_CHAIN_ID -> "Bitcoin testnet"
        else -> "Bitcoin"
    }

private fun JsonObject.walletConnectBitcoinChainId(): String? =
    string("chainId")?.walletConnectChainIdOrNull()?.takeIf { it in BITCOIN_CHAIN_IDS }
        ?: string("account")?.walletConnectAccountChainIdOrNull()

private fun String.walletConnectAccountChainIdOrNull(): String? {
    val parts = split(":")
    if (parts.size < 3) return null
    return runCatching { WalletConnectClient.canonicalChainId("${parts[0]}:${parts[1]}") }.getOrNull()
        ?.takeIf { it in BITCOIN_CHAIN_IDS }
}

private fun String.walletConnectChainIdOrNull(): String? =
    runCatching { WalletConnectClient.canonicalChainId(this) }.getOrNull()
        ?: walletConnectAccountChainIdOrNull()

private fun String.walletConnectAccountIdOrNull(): String? =
    runCatching { WalletConnectClient.canonicalAccountId(this) }.getOrNull()
        ?.takeIf { it.walletConnectAccountChainIdOrNull() in BITCOIN_CHAIN_IDS }

private fun JsonObject.walletConnectRequestedNamespaces(): List<WalletConnectRequestedNamespace> {
    val namespaces = obj("requestedNamespaces")
        ?: throw IllegalArgumentException("WalletConnect session update is missing requested namespaces")

    return namespaces.entries.mapNotNull { (namespaceKey, element) ->
        val namespace = element as? JsonObject ?: return@mapNotNull null
        val accounts = namespace.stringArray("accounts")
            .mapNotNull { it.walletConnectAccountIdOrNull() }
            .toSet()
        val chainIds = buildSet {
            namespaceKey.walletConnectChainIdOrNull()?.also(::add)
            namespace.stringArray("chains").mapNotNullTo(this) { it.walletConnectChainIdOrNull() }
            accounts.mapNotNullTo(this) { it.walletConnectAccountChainIdOrNull() }
        }.filter { it in BITCOIN_CHAIN_IDS }.toSet()

        WalletConnectRequestedNamespace(
            chainIds = chainIds,
            accounts = accounts,
            methods = namespace.stringArray("methods").toSet(),
            events = namespace.stringArray("events").toSet()
        )
    }
}

private fun JsonObject.walletConnectBitcoinChainIds(): Set<String> =
    buildSet {
        collectWalletConnectBitcoinChainIds(this@walletConnectBitcoinChainIds)
    }

private fun MutableSet<String>.collectWalletConnectBitcoinChainIds(element: JsonElement) {
    when (element) {
        is JsonObject -> element.values.forEach(::collectWalletConnectBitcoinChainIds)
        is JsonArray -> element.forEach(::collectWalletConnectBitcoinChainIds)
        is JsonPrimitive -> element.contentOrNull
            ?.takeIf { it.startsWith("bip122:") }
            ?.let { it.walletConnectChainIdOrNull() }
            ?.takeIf { it in BITCOIN_CHAIN_IDS }
            ?.also(::add)
    }
}

private fun Address.toBitcoinChangeAddressJson(): Map<String, JsonElement> {
    val scriptPubkey = walletConnectScriptPubkey
        ?: throw IllegalArgumentException("sendTransfer changeAddress is missing scriptpubkey")

    return mapOf(
        BTC_POLICY_ASSET to buildJsonObject {
            put("address", address)
            put("scriptpubkey", scriptPubkey)
        }
    )
}

private fun BitcoinAccountContext.signingEntry(signingAddress: String): WalletConnectBitcoinAddress =
    addresses.firstOrNull { it.address.address == signingAddress }
        ?: throw IllegalArgumentException("signMessage signing address does not belong to the connected Bitcoin account")

private suspend fun BitcoinAccountContext.signBip322Message(
    signingEntry: WalletConnectBitcoinAddress,
    message: String
): WalletConnectBip322Signature {
    if (!account.isSinglesig) {
        throw UnsupportedOperationException("WalletConnect BIP-322 signing currently requires a singlesig Bitcoin account")
    }

    val path = signingEntry.path
        ?: throw IllegalArgumentException("signMessage BIP-322 signing address is missing a derivation path")
    val credentials = session.getCredentials()
    val mnemonic = credentials.mnemonic?.takeIf { it.isNotBlank() }
        ?: throw UnsupportedOperationException("WalletConnect BIP-322 signing requires software wallet credentials")
    val seed = WallyJava.bip39_mnemonic_to_seed512(mnemonic, credentials.bip39Passphrase ?: "")
    val version = if (account.network.isBitcoinTestnet) {
        WallyJava.BIP32_VER_TEST_PRIVATE
    } else {
        WallyJava.BIP32_VER_MAIN_PRIVATE
    }
    val masterKey = WallyJava.bip32_key_from_seed(
        seed,
        version.toLong(),
        WallyJava.BIP32_FLAG_SKIP_HASH.toLong()
    )

    try {
        val childKey = WallyJava.bip32_key_from_parent_path(
            masterKey,
            path.toBip32ChildPath(),
            WallyJava.BIP32_FLAG_SKIP_HASH.toLong()
        )
        try {
            val publicKey = WallyJava.bip32_key_get_pub_key(childKey)
            val privateKey = WallyJava.bip32_key_get_priv_key(childKey)
            val scriptPubkey = walletConnectExternalScriptPubkey(childKey, account.type)
            val expectedScriptPubkey = signingEntry.address.walletConnectScriptPubkey
                ?.hexToByteArray()
                ?.takeIf { it.isNotEmpty() }
                ?: signingEntry.address.address.walletConnectAddressScriptPubkey(account)
            if (!scriptPubkey.contentEquals(expectedScriptPubkey)) {
                throw IllegalStateException("signMessage BIP-322 derivation does not match the requested signing address")
            }

            val messageHash = WallyJava.bip340_tagged_hash(
                message.encodeToByteArray(),
                BIP322_MESSAGE_TAG,
                null
            )
            val toSpendTx = bip322ToSpendTransaction(messageHash, scriptPubkey)
            try {
                val toSignTx = bip322ToSignTransaction(toSpendTx)
                try {
                    val serializationFlags = when (account.type) {
                        AccountType.BIP44_LEGACY -> signBip322P2pkh(toSignTx, scriptPubkey, publicKey, privateKey)
                        AccountType.BIP49_SEGWIT_WRAPPED -> signBip322P2shP2wpkh(toSignTx, childKey, publicKey, privateKey)
                        AccountType.BIP84_SEGWIT -> signBip322P2wpkh(toSignTx, childKey, publicKey, privateKey)
                        AccountType.BIP86_TAPROOT -> signBip322P2tr(toSignTx, privateKey)
                        else -> throw UnsupportedOperationException("Unsupported WalletConnect BIP-322 account type: ${account.type}")
                    }
                    val signature = BIP322_FULL_PREFIX + Base64.encodeToString(
                        WallyJava.tx_to_bytes(toSignTx, serializationFlags),
                        Base64.NO_WRAP
                    )

                    return WalletConnectBip322Signature(
                        signatureHex = signature.encodeToByteArray().toHex().lowercase(),
                        messageHashHex = messageHash.toHex().lowercase()
                    )
                } finally {
                    WallyJava.tx_free(toSignTx)
                }
            } finally {
                WallyJava.tx_free(toSpendTx)
            }
        } finally {
            WallyJava.bip32_key_free(childKey)
        }
    } finally {
        WallyJava.bip32_key_free(masterKey)
    }
}

private fun bip322ToSpendTransaction(messageHash: ByteArray, scriptPubkey: ByteArray): Any {
    val toSpendTx = WallyJava.tx_init(0L, 0L, 1L, 1L)
    WallyJava.tx_add_raw_input(
        toSpendTx,
        BIP322_TO_SPEND_PREVOUT,
        BIP322_TO_SPEND_PREVOUT_INDEX,
        0L,
        byteArrayOf(0x00, WallyJava.SHA256_LEN.toByte()) + messageHash,
        null,
        0L
    )
    WallyJava.tx_add_raw_output(toSpendTx, BIP322_TO_SIGN_VALUE, scriptPubkey, 0L)
    return toSpendTx
}

private fun bip322ToSignTransaction(toSpendTx: Any): Any {
    val toSignTx = WallyJava.tx_init(0L, 0L, 1L, 1L)
    WallyJava.tx_add_raw_input(
        toSignTx,
        WallyJava.tx_get_txid(toSpendTx),
        0L,
        BIP322_TO_SIGN_SEQUENCE,
        null,
        null,
        0L
    )
    WallyJava.tx_add_raw_output(toSignTx, BIP322_TO_SIGN_VALUE, byteArrayOf(BIP322_TO_SIGN_OUTPUT_SCRIPT_OP_RETURN), 0L)
    return toSignTx
}

private fun signBip322P2pkh(
    toSignTx: Any,
    scriptPubkey: ByteArray,
    publicKey: ByteArray,
    privateKey: ByteArray
): Long {
    val signatureHash = WallyJava.tx_get_btc_signature_hash(
        toSignTx,
        0L,
        scriptPubkey,
        BIP322_TO_SIGN_VALUE,
        WallyJava.WALLY_SIGHASH_ALL.toLong(),
        0L,
        null
    )
    val signature = WallyJava.ec_sig_from_bytes(
        privateKey,
        signatureHash,
        (WallyJava.EC_FLAG_ECDSA or WallyJava.EC_FLAG_GRIND_R).toLong()
    )
    val signatureWithSighash = signature.ecdsaDerWithSighash(WallyJava.WALLY_SIGHASH_ALL)
    val scriptSig = ByteArray(WallyJava.WALLY_SCRIPTSIG_P2PKH_MAX_LEN)
    val written = WallyJava.scriptsig_p2pkh_from_der(
        publicKey,
        signatureWithSighash,
        scriptSig
    )
    WallyJava.tx_set_input_script(toSignTx, 0L, scriptSig.copyOf(written))
    return 0L
}

private fun signBip322P2shP2wpkh(
    toSignTx: Any,
    childKey: Any,
    publicKey: ByteArray,
    privateKey: ByteArray
): Long {
    val redeemScript = p2wpkhScriptPubkey(childKey)
    WallyJava.tx_set_input_script(toSignTx, 0L, WallyJava.script_push_from_bytes(redeemScript, 0L))
    signBip322P2wpkh(toSignTx, childKey, publicKey, privateKey)
    return WallyJava.WALLY_TX_FLAG_USE_WITNESS.toLong()
}

private fun signBip322P2wpkh(
    toSignTx: Any,
    childKey: Any,
    publicKey: ByteArray,
    privateKey: ByteArray
): Long {
    val signatureHash = WallyJava.tx_get_btc_signature_hash(
        toSignTx,
        0L,
        p2pkhScriptPubkey(childKey),
        BIP322_TO_SIGN_VALUE,
        WallyJava.WALLY_SIGHASH_ALL.toLong(),
        WallyJava.WALLY_TX_FLAG_USE_WITNESS.toLong(),
        null
    )
    val signature = WallyJava.ec_sig_from_bytes(
        privateKey,
        signatureHash,
        (WallyJava.EC_FLAG_ECDSA or WallyJava.EC_FLAG_GRIND_R).toLong()
    )
    val witness = WallyJava.tx_witness_stack_init(2L)
    WallyJava.tx_witness_stack_add(witness, signature.ecdsaDerWithSighash(WallyJava.WALLY_SIGHASH_ALL))
    WallyJava.tx_witness_stack_add(witness, publicKey)
    WallyJava.tx_set_input_witness(toSignTx, 0L, witness)
    return WallyJava.WALLY_TX_FLAG_USE_WITNESS.toLong()
}

private fun signBip322P2tr(
    toSignTx: Any,
    privateKey: ByteArray
): Long {
    val signatureHash = WallyJava.tx_get_btc_taproot_signature_hash(
        toSignTx,
        0L,
        null,
        longArrayOf(BIP322_TO_SIGN_VALUE),
        null,
        0L,
        0xffffffffL,
        null,
        WallyJava.WALLY_SIGHASH_DEFAULT.toLong(),
        0L,
        null
    )
    val tweakedPrivateKey = WallyJava.ec_private_key_bip341_tweak(privateKey, null, 0L, null)
    val signature = WallyJava.ec_sig_from_bytes(
        tweakedPrivateKey,
        signatureHash,
        WallyJava.EC_FLAG_SCHNORR.toLong()
    )
    val witness = WallyJava.tx_witness_stack_init(1L)
    WallyJava.tx_witness_stack_add(witness, signature)
    WallyJava.tx_set_input_witness(toSignTx, 0L, witness)
    return WallyJava.WALLY_TX_FLAG_USE_WITNESS.toLong()
}

private fun ByteArray.ecdsaDerWithSighash(sighash: Int): ByteArray =
    WallyJava.ec_sig_to_der(this) + byteArrayOf(sighash.toByte())

private fun CreateTransaction.withWalletConnectMemoOutput(memoHex: String?): CreateTransaction {
    if (memoHex == null) return this

    val details = jsonElement?.jsonObject
        ?: throw IllegalStateException("sendTransfer memo requires raw transaction details")
    val transactionHex = details.string("transaction")
        ?: throw IllegalStateException("sendTransfer memo requires an unsigned transaction")
    val feeRate = details.long("fee_rate")
        ?: throw IllegalStateException("sendTransfer memo requires a fee rate")
    val transactionOutputs = details["transaction_outputs"]?.jsonArray
        ?: throw IllegalStateException("sendTransfer memo requires transaction outputs")
    val memoScript = memoHex.walletConnectOpReturnScript()

    val tx = WallyJava.tx_from_hex(transactionHex, 0L)
    try {
        val outputCount = WallyJava.tx_get_num_outputs(tx)
        if (outputCount != transactionOutputs.size) {
            throw IllegalStateException("sendTransfer memo could not match transaction outputs")
        }

        val changeIndex = transactionOutputs.indexOfFirst { output ->
            val obj = output.jsonObject
            obj.boolean("is_change") && (obj.long("satoshi") ?: 0L) > 0L
        }
        if (changeIndex < 0) {
            throw IllegalStateException("sendTransfer memo requires a spendable change output to pay the extra relay fee")
        }

        val changeOutput = transactionOutputs[changeIndex].jsonObject
        val changeBefore = changeOutput.long("satoshi")
            ?: throw IllegalStateException("sendTransfer memo change output is missing satoshi")
        val oldVsize = WallyJava.tx_get_vsize(tx).toLong()
        WallyJava.tx_add_raw_output(tx, 0L, memoScript, 0L)
        val newVsize = WallyJava.tx_get_vsize(tx).toLong()
        val extraFee = ((newVsize - oldVsize) * feeRate + 999L) / 1000L
        val changeAfter = changeBefore - extraFee
        if (changeAfter < BITCOIN_STANDARD_DUST_SATS) {
            throw IllegalStateException("sendTransfer memo requires change above dust after paying the extra relay fee")
        }

        WallyJava.tx_set_output_satoshi(tx, changeIndex.toLong(), changeAfter)
        val memoScriptHex = memoScript.toHex().lowercase()
        val updatedOutputs = buildJsonArray {
            transactionOutputs.forEachIndexed { index, output ->
                if (index == changeIndex) {
                    add(output.jsonObject.replacingLong("satoshi", changeAfter))
                } else {
                    add(output)
                }
            }
            add(buildJsonObject {
                put("satoshi", 0L)
                put("scriptpubkey", memoScriptHex)
                put("is_change", false)
                put("is_walletconnect_memo", true)
            })
        }

        jsonElement = details.replacing(
            "transaction" to JsonPrimitive(WallyJava.tx_to_hex(tx, 0L)),
            "transaction_outputs" to updatedOutputs,
            "fee" to JsonPrimitive((details.long("fee") ?: 0L) + extraFee),
            "satoshi" to (details.obj("satoshi")?.addingLong(BTC_POLICY_ASSET, -extraFee) ?: details["satoshi"] ?: JsonObject(emptyMap())),
            "change_address" to (details.obj("change_address")?.withUpdatedChangeOutput(changeOutput, changeAfter) ?: details["change_address"] ?: JsonObject(emptyMap()))
        )
    } finally {
        WallyJava.tx_free(tx)
    }

    return this
}

private fun String.walletConnectOpReturnScript(): ByteArray {
    val normalized = trim()
    if (!normalized.isEvenLengthHex()) {
        throw IllegalArgumentException("sendTransfer memo must be even-length hex")
    }

    val memo = normalized.hexToByteArray()
    if (memo.size > WALLETCONNECT_MEMO_MAX_BYTES) {
        throw IllegalArgumentException("sendTransfer memo exceeds $WALLETCONNECT_MEMO_MAX_BYTES bytes")
    }

    val script = ByteArray(WallyJava.WALLY_SCRIPTPUBKEY_OP_RETURN_MAX_LEN)
    val written = WallyJava.scriptpubkey_op_return_from_bytes(memo, 0L, script)
    if (written <= 0) {
        throw IllegalArgumentException("sendTransfer memo could not be encoded as OP_RETURN")
    }
    return script.copyOf(written)
}

private fun JsonObject.withUpdatedChangeOutput(changeOutput: JsonObject, satoshi: Long): JsonObject =
    buildJsonObject {
        val changeScript = changeOutput.string("scriptpubkey")
        this@withUpdatedChangeOutput.forEach { (key, value) ->
            val obj = value as? JsonObject
            val isMatchingChange = obj != null &&
                (key == BTC_POLICY_ASSET || changeScript != null && obj.string("scriptpubkey") == changeScript)
            put(key, if (isMatchingChange) obj.replacingLong("satoshi", satoshi) else value)
        }
    }

private fun JsonObject.addingLong(key: String, delta: Long): JsonObject {
    val current = long(key) ?: return this
    return replacingLong(key, current + delta)
}

private fun JsonObject.replacingLong(key: String, value: Long): JsonObject =
    replacing(key to JsonPrimitive(value))

private fun JsonObject.replacing(vararg replacements: Pair<String, JsonElement>): JsonObject {
    val replacementMap = replacements.toMap()
    return buildJsonObject {
        this@replacing.forEach { (key, value) ->
            put(key, replacementMap[key] ?: value)
        }
        replacementMap.forEach { (key, value) ->
            if (key !in this@replacing) {
                put(key, value)
            }
        }
    }
}

private fun List<WalletConnectBitcoinAddress>.toBitcoinAddressesJson(intentions: List<String> = emptyList()): String {
    val allowedIntentions = intentions.toSet()
    return buildJsonArray {
        this@toBitcoinAddressesJson
            .filter { allowedIntentions.isEmpty() || it.intention in allowedIntentions }
            .forEach { entry ->
                add(buildJsonObject {
                    put("address", entry.address.address)
                    entry.publicKey?.also { put("publicKey", it) }
                    entry.path?.also { put("path", it) }
                    put("intention", entry.intention)
                })
            }
    }.toString()
}

private fun Address.toWalletConnectBitcoinAddress(account: Account): WalletConnectBitcoinAddress =
    WalletConnectBitcoinAddress(
        address = this,
        publicKey = account.walletConnectPublicKey(this),
        path = walletConnectPath(account)
    )

private fun Account.walletConnectPreferenceRank(): Int =
    when (type) {
        AccountType.BIP84_SEGWIT -> 0
        AccountType.BIP86_TAPROOT -> 1
        AccountType.BIP49_SEGWIT_WRAPPED -> 2
        AccountType.BIP44_LEGACY -> 3
        AccountType.STANDARD,
        AccountType.TWO_OF_THREE,
        AccountType.AMP_ACCOUNT -> 4
        AccountType.LIGHTNING,
        AccountType.UNKNOWN -> 5
    }

private fun List<Address>.walletConnectFirstExternalAddress(): Address? =
    filter { it.isWalletConnectExternalAddress() }
        .minByOrNull { it.pointer }

private fun Address.isWalletConnectExternalAddress(): Boolean {
    val path = userPath
    return if (path != null && path.size >= 2) {
        path[path.lastIndex - 1] == 0L
    } else {
        branch == 0L
    }
}

private val Address.walletConnectBranch: Long
    get() {
        val path = userPath
        return if (path != null && path.size >= 2) {
            path[path.lastIndex - 1]
        } else {
            branch
        }
    }

private fun Address.walletConnectPath(account: Account): String? =
    userPath?.toWalletConnectPath()
        ?: account.walletConnectPurpose()?.let { purpose ->
            "m/$purpose'/${account.walletConnectCoinType}'/${account.pointer}'/$walletConnectBranch/$pointer"
        }

private fun List<Long>.toWalletConnectPath(): String =
    joinToString(separator = "/", prefix = "m/") { child ->
        if (child >= BIP32_HARDENED_OFFSET) {
            "${child - BIP32_HARDENED_OFFSET}'"
        } else {
            child.toString()
        }
    }

private fun String.toBip32ChildPath(): IntArray {
    val path = removePrefix("m/")
    if (path.isBlank()) {
        throw IllegalArgumentException("WalletConnect BIP32 path is empty")
    }

    return path.split("/").map { segment ->
        val hardened = segment.endsWith("'") || segment.endsWith("h") || segment.endsWith("H")
        val value = segment.dropLast(if (hardened) 1 else 0).toLongOrNull()
            ?: throw IllegalArgumentException("WalletConnect BIP32 path contains an invalid child number")
        if (value < 0 || value >= BIP32_HARDENED_OFFSET) {
            throw IllegalArgumentException("WalletConnect BIP32 path child number is out of range")
        }
        (value + if (hardened) BIP32_HARDENED_OFFSET else 0L).toInt()
    }.toIntArray()
}

private fun Account.walletConnectFutureAddresses(previousAddresses: List<Address>): List<Address> {
    if (!isSinglesig) return emptyList()

    val maxExternalPointer = previousAddresses
        .filter { it.walletConnectBranch == 0L }
        .maxOfOrNull { it.pointer }
        ?: 0L

    val futureReceive = ((maxExternalPointer + 1)..(maxExternalPointer + 2)).map { 0L to it }
    val futureChange = listOf(1L to 0L, 1L to 1L)
    return (futureReceive + futureChange).mapNotNull { (branch, pointer) ->
        walletConnectAddress(branch, pointer)
    }
}

private fun Account.walletConnectFirstExternalAddress(): Address? {
    return walletConnectAddress(branch = 0, pointer = 0)
}

private fun Account.walletConnectAddress(branch: Long, pointer: Long): Address? {
    if (!isSinglesig) return null
    val accountKey = walletConnectBip32PublicKey() ?: return null

    return try {
        val childKey = WallyJava.bip32_key_from_parent_path(
            accountKey,
            intArrayOf(branch.toInt(), pointer.toInt()),
            (WallyJava.BIP32_FLAG_KEY_PUBLIC or WallyJava.BIP32_FLAG_SKIP_HASH).toLong()
        )
        try {
            val addressType = when (type) {
                AccountType.BIP84_SEGWIT -> "p2wpkh"
                AccountType.BIP86_TAPROOT -> "p2tr"
                AccountType.BIP49_SEGWIT_WRAPPED -> "p2sh-p2wpkh"
                AccountType.BIP44_LEGACY -> "p2pkh"
                else -> null
            } ?: return null
            val script = walletConnectExternalScriptPubkey(childKey, type)
            val scriptHex = script.toHex()
            Address(
                address = script.walletConnectScriptPubkeyAddress(this),
                pointer = pointer,
                addressType = addressType,
                branch = branch,
                script = scriptHex,
                scriptPubkey = scriptHex
            )
        } finally {
            WallyJava.bip32_key_free(childKey)
        }
    } catch (_: Throwable) {
        null
    } finally {
        WallyJava.bip32_key_free(accountKey)
    }
}

private fun Account.walletConnectPublicKey(address: Address): String? {
    if (!isSinglesig) return null
    val accountKey = walletConnectBip32PublicKey() ?: return null

    return try {
        val childKey = WallyJava.bip32_key_from_parent_path(
            accountKey,
            intArrayOf(address.walletConnectBranch.toInt(), address.pointer.toInt()),
            (WallyJava.BIP32_FLAG_KEY_PUBLIC or WallyJava.BIP32_FLAG_SKIP_HASH).toLong()
        )
        try {
            WallyJava.bip32_key_get_pub_key(childKey).toHex()
        } finally {
            WallyJava.bip32_key_free(childKey)
        }
    } catch (_: Throwable) {
        null
    } finally {
        WallyJava.bip32_key_free(accountKey)
    }
}

private fun Account.walletConnectPurpose(): Long? =
    when (type) {
        AccountType.BIP84_SEGWIT -> 84
        AccountType.BIP86_TAPROOT -> 86
        AccountType.BIP49_SEGWIT_WRAPPED -> 49
        AccountType.BIP44_LEGACY -> 44
        else -> null
    }

private fun Account.walletConnectBip32PublicKey(): Any? {
    val xpub = walletConnectStandardExtendedPubkey() ?: return null
    return runCatching { WallyJava.bip32_key_from_base58(xpub) }.getOrNull()
}

private fun Account.walletConnectStandardExtendedPubkey(): String? {
    coreDescriptors
        ?.asSequence()
        ?.mapNotNull { DESCRIPTOR_XPUB_REGEX.find(it)?.value }
        ?.firstOrNull()
        ?.also { return it }

    return extendedPubkey?.toWalletConnectStandardXpub(isTestnet = network.isBitcoinTestnet)
}

private fun String.toWalletConnectStandardXpub(isTestnet: Boolean): String {
    val decoded = base58CheckDecode() ?: return this
    if (decoded.size != 78) return this

    val targetVersion = if (isTestnet) BIP32_TESTNET_PUBLIC_VERSION else BIP32_MAINNET_PUBLIC_VERSION
    val version = decoded.copyOfRange(0, 4)
    if (version.contentEquals(targetVersion)) return this
    if (SLIP132_PUBLIC_VERSIONS.none { it.contentEquals(version) }) return this

    return (targetVersion + decoded.copyOfRange(4, decoded.size)).base58CheckEncode()
}

private fun walletConnectExternalScriptPubkey(key: Any, type: AccountType): ByteArray =
    when (type) {
        AccountType.BIP84_SEGWIT -> p2wpkhScriptPubkey(key)
        AccountType.BIP86_TAPROOT -> p2trScriptPubkey(key)
        AccountType.BIP49_SEGWIT_WRAPPED -> p2shP2wpkhScriptPubkey(key)
        AccountType.BIP44_LEGACY -> p2pkhScriptPubkey(key)
        else -> throw IllegalArgumentException("Unsupported WalletConnect account type: $type")
    }

private fun String.walletConnectAddressScriptPubkey(account: Account): ByteArray {
    val normalized = trim()
    return if (normalized.startsWith("${account.walletConnectSegwitAddressFamily}1", ignoreCase = true)) {
        WallyJava.addr_segwit_to_bytes(normalized.lowercase(), account.walletConnectSegwitAddressFamily, 0L)
    } else {
        val script = ByteArray(WallyJava.WALLY_ADDRESS_PUBKEY_MAX_LEN)
        val written = WallyJava.address_to_scriptpubkey(normalized, account.walletConnectWallyNetwork, script)
        if (written <= 0 || written > script.size) {
            throw IllegalArgumentException("Unable to infer scriptPubKey from WalletConnect signing address")
        }
        script.copyOf(written)
    }
}

private fun ByteArray.walletConnectScriptPubkeyAddress(account: Account): String =
    if (isWalletConnectSegwitScriptPubkey()) {
        WallyJava.addr_segwit_from_bytes(this, account.walletConnectSegwitAddressFamily, 0L)
    } else {
        WallyJava.scriptpubkey_to_address(this, account.walletConnectWallyNetwork)
    }

private fun ByteArray.isWalletConnectSegwitScriptPubkey(): Boolean {
    if (size < 4) return false
    val version = this[0].toInt() and 0xff
    val pushSize = this[1].toInt() and 0xff
    return (version == 0 || version in 0x51..0x60) && pushSize == size - 2 && pushSize in 2..40
}

private fun p2pkhScriptPubkey(key: Any): ByteArray {
    val publicKey = WallyJava.bip32_key_get_pub_key(key)
    return WallyJava.scriptpubkey_p2pkh_from_bytes(publicKey, WallyJava.WALLY_SCRIPT_HASH160.toLong())
}

private fun p2wpkhScriptPubkey(key: Any): ByteArray {
    val publicKey = WallyJava.bip32_key_get_pub_key(key)
    return WallyJava.witness_program_from_bytes(publicKey, WallyJava.WALLY_SCRIPT_HASH160.toLong())
}

private fun p2shP2wpkhScriptPubkey(key: Any): ByteArray {
    val witnessProgram = p2wpkhScriptPubkey(key)
    return WallyJava.scriptpubkey_p2sh_from_bytes(witnessProgram, WallyJava.WALLY_SCRIPT_HASH160.toLong())
}

private fun p2trScriptPubkey(key: Any): ByteArray {
    val publicKey = WallyJava.bip32_key_get_pub_key(key)
    val script = ByteArray(WallyJava.WALLY_SCRIPTPUBKEY_P2TR_LEN)
    val written = WallyJava.scriptpubkey_p2tr_from_bytes(publicKey, 0, script)
    if (written != script.size) {
        throw IllegalArgumentException("Unexpected P2TR script length")
    }
    return script
}

private fun String.base58CheckDecode(): ByteArray? {
    if (isBlank()) return null

    var value = BigInteger.ZERO
    for (char in this) {
        if (char.code >= BASE58_INDEXES.size) return null
        val digit = BASE58_INDEXES[char.code]
        if (digit < 0) return null
        value = value.multiply(BASE58_RADIX).add(BigInteger.valueOf(digit.toLong()))
    }

    val rawValueBytes = value.toByteArray()
    val valueBytes = if (rawValueBytes.size > 1 && rawValueBytes.first() == 0.toByte()) {
        rawValueBytes.copyOfRange(1, rawValueBytes.size)
    } else {
        rawValueBytes
    }
    val leadingZeros = takeWhile { it == '1' }.length
    val decoded = ByteArray(leadingZeros + valueBytes.size)
    valueBytes.copyInto(decoded, destinationOffset = leadingZeros)
    if (decoded.size < 4) return null

    val payload = decoded.copyOfRange(0, decoded.size - 4)
    val checksum = decoded.copyOfRange(decoded.size - 4, decoded.size)
    return payload.takeIf { it.base58Checksum().contentEquals(checksum) }
}

private fun ByteArray.base58CheckEncode(): String {
    val encoded = this + base58Checksum()
    var value = BigInteger(1, encoded)
    val result = StringBuilder()
    while (value > BigInteger.ZERO) {
        val divRem = value.divideAndRemainder(BASE58_RADIX)
        result.append(BASE58_ALPHABET[divRem[1].toInt()])
        value = divRem[0]
    }
    encoded.takeWhile { it == 0.toByte() }.forEach { result.append('1') }
    return result.reverse().toString()
}

private fun ByteArray.base58Checksum(): ByteArray =
    sha256().sha256().copyOfRange(0, 4)

private fun ByteArray.sha256(): ByteArray =
    MessageDigest.getInstance("SHA-256").digest(this)

private fun Throwable.isWalletConnectIdleReceiveTimeout(): Boolean {
    val ownMessage = message.orEmpty()
    return ownMessage.contains("receive timed out", ignoreCase = true) ||
        cause?.isWalletConnectIdleReceiveTimeout() == true
}

private fun WalletConnectPendingApproval.toApproval(): WalletConnectApproval {
    return WalletConnectApproval(
        id = id,
        kind = kind.toApprovalKind(),
        topic = topic,
        requestId = requestId,
        review = reviewJson.toApprovalReview()
    )
}

private fun WalletConnectPendingApprovalKind.toApprovalKind(): WalletConnectApprovalKind =
    WalletConnectApprovalKind.valueOf(name)

private fun String.toApprovalReview(): WalletConnectApprovalReview {
    val json = runCatching { asJsonObject() }.getOrNull()
    val kind = json?.string("kind")
    val bitcoin = json?.obj("bitcoin")
    val bitcoinRequest = bitcoin?.obj("request")
    val requesterName = json?.obj("proposer")?.obj("metadata")?.string("name")
        ?: json?.obj("requester")?.obj("metadata")?.string("name")
    val method = json?.string("method") ?: bitcoinRequest?.string("type")
    val chainId = json?.string("chainId") ?: bitcoin?.string("chainId")
    val verifyRisk = json?.obj("verify")?.string("risk")
    val display = bitcoin?.obj("display") ?: json?.obj("display")
    val displayApproval = display?.obj("approval")
    val unsupportedAuthenticate = kind == "sessionAuthenticate" &&
        json.walletConnectBitcoinChainIds().isEmpty()
    val title = when (kind) {
        "sessionProposal" -> "WalletConnect session"
        "sessionRequest" -> method ?: "WalletConnect request"
        "sessionAuthenticate" -> "WalletConnect authentication"
        "sessionUpdate" -> "WalletConnect update"
        else -> "WalletConnect approval"
    }
    val subtitle = when (kind) {
        "sessionProposal" -> "Connect Bitcoin account"
        "sessionRequest" -> chainId
        else -> requesterName
    }

    return WalletConnectApprovalReview(
        title = title,
        subtitle = subtitle,
        requesterName = requesterName,
        method = method,
        chainId = chainId,
        verifyRisk = verifyRisk,
        intent = display?.string("intent"),
        warnings = display?.stringArray("warnings") ?: emptyList(),
        details = display?.reviewFields("details") ?: emptyList(),
        info = display?.stringArray("info") ?: emptyList(),
        canApprove = (displayApproval?.booleanOrNull("enabled") ?: true) && !unsupportedAuthenticate,
        approveUnavailableReason = if (unsupportedAuthenticate) {
            "This wallet can only approve Bitcoin WalletConnect authentication requests."
        } else {
            displayApproval?.string("reason")
        },
        rawJson = this
    )
}

private fun String.toSessionReview(): WalletConnectSessionReview {
    val json = asJsonObject()
    val display = json.obj("display")
    val actions = json.obj("actions")
    val peer = json.obj("peer")?.obj("metadata")
    val peerName = peer?.string("name")
    val peerUrl = peer?.string("url")
    val topic = json.string("topic").orEmpty()
    val subtitle = listOfNotNull(peerName, peerUrl)
        .takeIf { it.isNotEmpty() }
        ?.joinToString(" - ")

    return WalletConnectSessionReview(
        topic = topic,
        title = display?.string("intent") ?: "WalletConnect session",
        subtitle = subtitle,
        peerName = peerName,
        peerUrl = peerUrl,
        expiry = json.long("expiryTimestamp"),
        acknowledged = json.boolean("acknowledged"),
        connectionState = json.string("connectionState"),
        intent = display?.string("intent"),
        warnings = display?.stringArray("warnings") ?: emptyList(),
        details = display?.reviewFields("details") ?: emptyList(),
        info = display?.stringArray("info") ?: emptyList(),
        disconnect = actions?.obj("disconnect")?.toSessionActionReview("Disconnect WalletConnect session"),
        extend = actions?.obj("extend")?.toSessionActionReview("Extend WalletConnect session"),
        rawJson = this
    )
}

private fun JsonObject.toSessionActionReview(fallbackIntent: String): WalletConnectSessionActionReview =
    WalletConnectSessionActionReview(
        intent = string("intent") ?: fallbackIntent,
        warnings = stringArray("warnings"),
        details = reviewFields("details"),
        info = stringArray("info")
    )

private fun String.asJsonObject(): JsonObject = walletConnectReviewJson.parseToJsonElement(this).jsonObject

private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.contentOrNull?.toLongOrNull()

private fun JsonObject.stringArray(key: String): List<String> =
    (this[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList()

private fun JsonObject.reviewFields(key: String): List<WalletConnectReviewField> =
    (this[key] as? JsonArray)?.mapNotNull { item ->
        val field = item as? JsonObject ?: return@mapNotNull null
        val label = field.string("label")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val value = field.string("value")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        WalletConnectReviewField(label = label, value = value)
    } ?: emptyList()

private fun JsonObject.boolean(key: String): Boolean = booleanOrNull(key) == true

private fun JsonObject.booleanOrNull(key: String): Boolean? =
    when ((this[key] as? JsonPrimitive)?.contentOrNull) {
        "true" -> true
        "false" -> false
        else -> null
    }

private fun JsonObject.signInputs(): List<SignPsbtInput> {
    val inputs = (this["signInputs"] as? JsonArray)
        ?: throw IllegalArgumentException("signPsbt is missing signInputs")

    return inputs.map { item ->
        val input = item.jsonObject
        SignPsbtInput(
            address = input.string("address") ?: throw IllegalArgumentException("signPsbt signInputs entry is missing address"),
            index = input.string("index")?.toIntOrNull()
                ?: throw IllegalArgumentException("signPsbt signInputs entry is missing index"),
            sighashTypes = input.sighashTypes()
        )
    }
}

private fun JsonObject.sighashTypes(): List<Long> {
    val sighashTypes = this["sighashTypes"]
        ?: throw IllegalArgumentException("signPsbt signInputs entry is missing sighashTypes")
    val parsed = sighashTypes.jsonArray.map {
        it.jsonPrimitive.content.toLong()
    }
    if (parsed.isEmpty()) {
        throw IllegalArgumentException("signPsbt sighashTypes must not be empty")
    }
    return parsed
}

private val PsbtInputDetails.outpoint: PsbtOutpoint
    get() = PsbtOutpoint(txHash = txHash.lowercase(), outputIndex = outputIndex)

private val Utxo.outpoint: PsbtOutpoint
    get() = PsbtOutpoint(txHash = txHash.lowercase(), outputIndex = index)

private fun String.toWalletConnectHex(): String {
    val trimmed = trim()
    if (trimmed.isEvenLengthHex()) {
        return trimmed.lowercase()
    }

    return Base64.decode(trimmed, Base64.DEFAULT).toHex().lowercase()
}

private fun String.isEvenLengthHex(): Boolean {
    return length % 2 == 0 && all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
}
