package com.blockstream.data.walletconnect

import com.blockstream.walletconnect.WcCapabilities
import com.blockstream.walletconnect.WcChainCapability
import com.blockstream.walletconnect.WcClient
import com.blockstream.walletconnect.WcClientConfig
import com.blockstream.walletconnect.WcEvent
import com.blockstream.walletconnect.WcEventKind
import com.blockstream.walletconnect.WcPendingApproval
import com.blockstream.walletconnect.WcPendingApprovalKind
import com.blockstream.walletconnect.WcSessionAuthenticateOutcome
import com.blockstream.walletconnect.WcSessionInfo
import com.blockstream.walletconnect.WcUniFfiException
import com.blockstream.walletconnect.WcWalletMetadata
import com.blockstream.walletconnect.wcBitcoinAddressesChangedEventParamsJson
import com.blockstream.walletconnect.wcBitcoinSessionPropertiesJson
import com.blockstream.walletconnect.wcCanonicalAccountId
import com.blockstream.walletconnect.wcCanonicalChainId
import com.blockstream.walletconnect.wcGenerateRelayAuthSeedHex

class WalletConnectException(
    message: String,
    cause: WcUniFfiException
) : RuntimeException(message, cause)

data class WalletConnectChainCapability(
    val chainId: String,
    val accounts: List<String>,
    val methods: List<String>,
    val events: List<String>
)

data class WalletConnectCapabilities(
    val chains: List<WalletConnectChainCapability>,
    val sessionPropertiesJson: String? = null,
    val scopedPropertiesJson: String? = null
)

data class WalletConnectWalletMetadata(
    val name: String,
    val description: String,
    val url: String,
    val icons: List<String>,
    val verifyUrl: String? = null,
    val redirectNative: String? = null,
    val redirectUniversal: String? = null,
    val redirectLinkMode: Boolean? = null
)

enum class WalletConnectEventKind {
    NONE,
    SESSION_PROPOSAL,
    SESSION_AUTHENTICATE,
    PAIRING_MESSAGE,
    SESSION_PROPOSAL_EXPIRED,
    SESSION_AUTHENTICATE_EXPIRED,
    SESSION_REQUEST,
    SESSION_UPDATE,
    SESSION_EVENT,
    SESSION_PING,
    SESSION_EXTEND,
    SESSION_DELETE,
    UNSUPPORTED_SESSION_MESSAGE,
    REJECTED_SESSION_REQUEST,
    SESSION_EXPIRED
}

enum class WalletConnectPendingApprovalKind {
    SESSION_PROPOSAL,
    SESSION_AUTHENTICATE,
    SESSION_REQUEST,
    SESSION_UPDATE
}

data class WalletConnectEvent(
    val kind: WalletConnectEventKind,
    val topic: String?,
    val requestId: Long?,
    val reviewJson: String?,
    val message: String?
)

data class WalletConnectPendingApproval(
    val kind: WalletConnectPendingApprovalKind,
    val topic: String,
    val requestId: Long,
    val reviewJson: String
)

data class WalletConnectSessionInfo(
    val topic: String,
    val expiry: Long,
    val acknowledged: Boolean,
    val sessionJson: String
)

data class WalletConnectSessionAuthenticateOutcome(
    val responseAck: Boolean,
    val session: WalletConnectSessionInfo?
)

class WalletConnectClientConfig(
    val projectId: String,
    relayAuthSeedHex: String,
    val authSubject: String,
    val relayUrl: String? = null,
    val userAgent: String? = null,
    val authTtlSeconds: Long? = null,
    val receiveTimeoutMs: Long? = null,
    val maxQueuedEvents: Long? = null,
    val automaticReconnectCooldownMs: Long? = null,
    val receiveTimeoutsBeforeReconnect: Long? = null
) {
    private val relayAuthSeedHex: String = relayAuthSeedHex

    internal fun toNative(): WcClientConfig {
        return WcClientConfig(
            projectId = projectId,
            relayAuthSeedHex = relayAuthSeedHex,
            authSubject = authSubject,
            relayUrl = relayUrl,
            userAgent = userAgent,
            authTtlSeconds = authTtlSeconds,
            receiveTimeoutMs = receiveTimeoutMs,
            maxQueuedEvents = maxQueuedEvents,
            automaticReconnectCooldownMs = automaticReconnectCooldownMs,
            receiveTimeoutsBeforeReconnect = receiveTimeoutsBeforeReconnect
        )
    }

    override fun toString(): String {
        return "WalletConnectClientConfig(" +
            "projectId=$projectId, " +
            "relayAuthSeedHex=<redacted>, " +
            "authSubject=$authSubject, " +
            "relayUrl=$relayUrl, " +
            "userAgent=$userAgent, " +
            "authTtlSeconds=$authTtlSeconds, " +
            "receiveTimeoutMs=$receiveTimeoutMs, " +
            "maxQueuedEvents=$maxQueuedEvents, " +
            "automaticReconnectCooldownMs=$automaticReconnectCooldownMs, " +
            "receiveTimeoutsBeforeReconnect=$receiveTimeoutsBeforeReconnect" +
            ")"
    }
}

class WalletConnectClient private constructor(
    private val native: WcClient
) : AutoCloseable {
    fun activeSessionJson(): String? = wcCall { native.activeSessionJson() }

    fun activeSessionReviewJson(): String? = wcCall { native.activeSessionReviewJson() }

    fun rememberVerifyContext(
        topic: String,
        requestId: Long,
        verifyContextJson: String
    ): Boolean = wcCall { native.rememberVerifyContext(topic, requestId, verifyContextJson) }

    fun approveSession(
        topic: String,
        requestId: Long,
        capabilities: WalletConnectCapabilities,
        walletMetadata: WalletConnectWalletMetadata,
        expiry: Long? = null,
        verifyContextJson: String? = null
    ): WalletConnectSessionInfo {
        return wcCall {
            native.approveSession(
                topic = topic,
                requestId = requestId,
                capabilities = capabilities.toNative(),
                walletMetadata = walletMetadata.toNative(),
                expiry = expiry,
                verifyContextJson = verifyContextJson
            ).toGreen()
        }
    }

    fun approveSessionAuthenticate(
        topic: String,
        requestId: Long,
        walletMetadata: WalletConnectWalletMetadata,
        cacaosJson: String,
        sessionCapabilities: WalletConnectCapabilities? = null,
        verifyContextJson: String? = null
    ): WalletConnectSessionAuthenticateOutcome {
        return wcCall {
            native.approveSessionAuthenticate(
                topic = topic,
                requestId = requestId,
                walletMetadata = walletMetadata.toNative(),
                cacaosJson = cacaosJson,
                sessionCapabilities = sessionCapabilities?.toNative(),
                verifyContextJson = verifyContextJson
            ).toGreen()
        }
    }

    fun sessionAuthenticateSigningJson(
        topic: String,
        requestId: Long,
        accountId: String
    ): String = wcCall { native.sessionAuthenticateSigningJson(topic, requestId, accountId) }

    fun sessionAuthenticateCacaosJson(
        topic: String,
        requestId: Long,
        accountId: String,
        signature: String,
        signatureType: String? = null,
        signatureMeta: String? = null
    ): String = wcCall {
        native.sessionAuthenticateCacaosJson(
            topic = topic,
            requestId = requestId,
            accountId = accountId,
            signature = signature,
            signatureType = signatureType,
            signatureMeta = signatureMeta
        )
    }

    fun approveSessionUpdate(
        topic: String,
        requestId: Long,
        capabilities: WalletConnectCapabilities
    ): Boolean = wcCall { native.approveSessionUpdate(topic, requestId, capabilities.toNative()) }

    fun updateSession(
        topic: String,
        capabilities: WalletConnectCapabilities
    ): Boolean = wcCall { native.updateSession(topic, capabilities.toNative()) }

    fun emitSessionEvent(
        topic: String,
        paramsJson: String
    ): Boolean = wcCall { native.emitSessionEvent(topic, paramsJson) }

    fun connectionState(): String = wcCall { native.connectionState() }

    fun disconnect(
        topic: String,
        errorCode: Long,
        message: String
    ): Boolean = wcCall { native.disconnect(topic, errorCode, message) }

    fun extendSession(topic: String): Boolean = wcCall { native.extendSession(topic) }

    fun nextEvent(): WalletConnectEvent = wcCall { native.nextEvent().toGreen() }

    fun pair(
        uri: String,
        origin: String
    ): String = wcCall { native.pair(uri, origin) }

    fun pendingApprovals(): List<WalletConnectPendingApproval> {
        return wcCall { native.pendingApprovals().map { it.toGreen() } }
    }

    fun rejectSession(
        topic: String,
        requestId: Long,
        errorCode: Long,
        message: String
    ): Boolean = wcCall { native.rejectSession(topic, requestId, errorCode, message) }

    fun rejectSessionAuthenticate(
        topic: String,
        requestId: Long,
        errorCode: Long,
        message: String
    ): Boolean = wcCall { native.rejectSessionAuthenticate(topic, requestId, errorCode, message) }

    fun rejectSessionUpdate(
        topic: String,
        requestId: Long,
        errorCode: Long,
        message: String
    ): Boolean = wcCall { native.rejectSessionUpdate(topic, requestId, errorCode, message) }

    fun respondBitcoinRequestSuccessJson(
        topic: String,
        requestId: Long,
        resultJson: String,
        verifyContextJson: String? = null
    ): Boolean {
        return wcCall {
            native.respondBitcoinRequestSuccessJson(topic, requestId, resultJson, verifyContextJson)
        }
    }

    fun respondSessionRequestError(
        topic: String,
        requestId: Long,
        errorCode: Long,
        message: String
    ): Boolean = wcCall { native.respondSessionRequestError(topic, requestId, errorCode, message) }

    fun respondSessionRequestSuccessJson(
        topic: String,
        requestId: Long,
        resultJson: String,
        verifyContextJson: String? = null
    ): Boolean {
        return wcCall {
            native.respondSessionRequestSuccessJson(topic, requestId, resultJson, verifyContextJson)
        }
    }

    fun shutdown() = wcCall { native.shutdown() }

    fun status(): Long = wcCall { native.status() }

    override fun close() = native.close()

    companion object {
        fun connect(config: WalletConnectClientConfig): WalletConnectClient {
            return wcCall { WalletConnectClient(WcClient.connect(config.toNative())) }
        }

        fun generateRelayAuthSeedHex(): String = wcCall { wcGenerateRelayAuthSeedHex() }

        fun canonicalChainId(chainId: String): String = wcCall { wcCanonicalChainId(chainId) }

        fun canonicalAccountId(accountId: String): String = wcCall { wcCanonicalAccountId(accountId) }

        fun bitcoinSessionPropertiesJson(addressesJson: String): String {
            return wcCall { wcBitcoinSessionPropertiesJson(addressesJson) }
        }

        fun bitcoinAddressesChangedEventParamsJson(
            chainId: String,
            addressesJson: String
        ): String = wcCall { wcBitcoinAddressesChangedEventParamsJson(chainId, addressesJson) }
    }
}

private inline fun <T> wcCall(block: () -> T): T {
    try {
        return block()
    } catch (exception: WcUniFfiException) {
        throw WalletConnectException(exception.message ?: "WalletConnect operation failed", exception)
    }
}

private fun WalletConnectCapabilities.toNative(): WcCapabilities {
    return WcCapabilities(
        chains = chains.map { it.toNative() },
        sessionPropertiesJson = sessionPropertiesJson,
        scopedPropertiesJson = scopedPropertiesJson
    )
}

private fun WalletConnectChainCapability.toNative(): WcChainCapability {
    return WcChainCapability(
        chainId = chainId,
        accounts = accounts,
        methods = methods,
        events = events
    )
}

private fun WalletConnectWalletMetadata.toNative(): WcWalletMetadata {
    return WcWalletMetadata(
        name = name,
        description = description,
        url = url,
        icons = icons,
        verifyUrl = verifyUrl,
        redirectNative = redirectNative,
        redirectUniversal = redirectUniversal,
        redirectLinkMode = redirectLinkMode
    )
}

private fun WcEvent.toGreen(): WalletConnectEvent {
    return WalletConnectEvent(
        kind = kind.toGreen(),
        topic = topic,
        requestId = requestId,
        reviewJson = review?.json(),
        message = message
    )
}

private fun WcPendingApproval.toGreen(): WalletConnectPendingApproval {
    return WalletConnectPendingApproval(
        kind = kind.toGreen(),
        topic = topic,
        requestId = requestId,
        reviewJson = review.json()
    )
}

private fun WcSessionInfo.toGreen(): WalletConnectSessionInfo {
    return WalletConnectSessionInfo(
        topic = topic,
        expiry = expiry,
        acknowledged = acknowledged,
        sessionJson = session.json()
    )
}

private fun WcSessionAuthenticateOutcome.toGreen(): WalletConnectSessionAuthenticateOutcome {
    return WalletConnectSessionAuthenticateOutcome(
        responseAck = responseAck,
        session = session?.toGreen()
    )
}

private fun WcEventKind.toGreen(): WalletConnectEventKind {
    return WalletConnectEventKind.valueOf(name)
}

private fun WcPendingApprovalKind.toGreen(): WalletConnectPendingApprovalKind {
    return WalletConnectPendingApprovalKind.valueOf(name)
}
