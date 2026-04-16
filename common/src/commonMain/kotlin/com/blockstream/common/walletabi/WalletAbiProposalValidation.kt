package com.blockstream.common.walletabi

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
 enum class WalletAbiGetterPermission {
    @SerialName("get_signer_receive_address")
    GET_SIGNER_RECEIVE_ADDRESS,

    @SerialName("get_raw_signing_x_only_pubkey")
    GET_RAW_SIGNING_X_ONLY_PUBKEY,
}

 data class WalletAbiProposalValidation(
    val chainId: String,
    val requestedMethods: List<String>,
)

private val WALLET_ABI_AUTO_APPROVED_GETTER_METHODS = mapOf(
    WALLET_ABI_METHOD_GET_SIGNER_RECEIVE_ADDRESS to WalletAbiGetterPermission.GET_SIGNER_RECEIVE_ADDRESS,
    WALLET_ABI_METHOD_GET_RAW_SIGNING_X_ONLY_PUBKEY to WalletAbiGetterPermission.GET_RAW_SIGNING_X_ONLY_PUBKEY,
)

 fun walletAbiAutoApprovedGettersForRequestedMethods(
    requestedMethods: Collection<String>,
): Set<WalletAbiGetterPermission> {
    return requestedMethods.mapNotNullTo(linkedSetOf()) { method ->
        WALLET_ABI_AUTO_APPROVED_GETTER_METHODS[method]
    }
}

 fun mergeWalletAbiApprovedGetters(
    approvedGetters: Set<WalletAbiGetterPermission>,
    requestedMethods: Collection<String>,
): Set<WalletAbiGetterPermission> {
    return approvedGetters + walletAbiAutoApprovedGettersForRequestedMethods(requestedMethods)
}

 fun normalizeWalletConnectPairingUri(input: String): String {
    return input.toWalletConnectPairingUri()
}

 fun walletConnectPairingTopic(pairingUri: String): String {
    return pairingUri.toWalletConnectPairingTopic()
}

 fun validateWalletAbiProposal(
    proposal: WalletAbiSessionProposal,
): WalletAbiProposalValidation {
    val namespace = proposal.requiredNamespaces[WALLET_ABI_WALLETCONNECT_NAMESPACE]
        ?: proposal.optionalNamespaces[WALLET_ABI_WALLETCONNECT_NAMESPACE]
        ?: throw IllegalArgumentException(
            "WalletConnect proposal does not request the Wallet ABI namespace",
        )

    if (proposal.requiredNamespaces.keys.any { it != WALLET_ABI_WALLETCONNECT_NAMESPACE }) {
        throw IllegalArgumentException(
            "WalletConnect proposal requests unsupported required namespaces",
        )
    }

    val chains = namespace.chains.distinct()
    if (chains.size != 1 || chains.single() !in WALLET_ABI_WALLETCONNECT_CHAINS) {
        throw IllegalArgumentException(
            "WalletConnect proposal must request exactly one supported Wallet ABI chain",
        )
    }

    val methods = namespace.methods.distinct()
    if (methods.isEmpty() || methods.any { it !in WALLET_ABI_WALLETCONNECT_METHODS }) {
        throw IllegalArgumentException(
            "WalletConnect proposal requests unsupported Wallet ABI methods",
        )
    }

    if (namespace.events.isNotEmpty()) {
        throw IllegalArgumentException(
            "WalletConnect proposal requests unsupported Wallet ABI events",
        )
    }

    return WalletAbiProposalValidation(
        chainId = chains.single(),
        requestedMethods = methods.sorted(),
    )
}

private fun String.toWalletConnectPairingUri(): String {
    val trimmed = trim()
    if (trimmed.startsWith("wc:")) {
        return trimmed
    }

    val decoded = decodePercentEncoded(trimmed)
    if (decoded.startsWith("wc:")) {
        return decoded
    }

    listOf(trimmed, decoded).forEach { candidate ->
        candidate.queryParameter("uri")?.let { encodedUri ->
            val uri = decodePercentEncoded(encodedUri)
            if (uri.startsWith("wc:")) {
                return uri
            }
        }

        val wcIndex = candidate.indexOf("wc:")
        if (wcIndex >= 0) {
            return decodePercentEncoded(candidate.substring(wcIndex))
        }
    }

    throw IllegalArgumentException(
        "Only WalletConnect URIs are supported for Wallet ABI connections",
    )
}

private fun String.toWalletConnectPairingTopic(): String {
    val withoutPrefix = removePrefix("wc:")
    val topic = withoutPrefix.substringBefore('@').trim()
    require(topic.isNotBlank()) {
        "WalletConnect URI is missing a pairing topic"
    }
    return topic
}

private fun String.queryParameter(key: String): String? {
    val queryOrFragment = substringAfter('?', missingDelimiterValue = this)
        .substringBefore('#')
        .ifBlank {
            substringAfter('#', missingDelimiterValue = "")
        }

    return queryOrFragment.split('&').firstNotNullOfOrNull { pair ->
        val parts = pair.split('=', limit = 2)
        if (parts.size == 2 && parts[0] == key) parts[1] else null
    }
}

private fun decodePercentEncoded(value: String): String {
    if ('%' !in value && '+' !in value) {
        return value
    }

    val output = StringBuilder(value.length)
    var index = 0
    while (index < value.length) {
        when (val current = value[index]) {
            '%' -> {
                if (index + 2 >= value.length) {
                    throw IllegalArgumentException("Malformed percent-encoded value")
                }
                val decoded = value.substring(index + 1, index + 3).toInt(16).toChar()
                output.append(decoded)
                index += 3
            }

            '+' -> {
                output.append(' ')
                index += 1
            }

            else -> {
                output.append(current)
                index += 1
            }
        }
    }

    return output.toString()
}
