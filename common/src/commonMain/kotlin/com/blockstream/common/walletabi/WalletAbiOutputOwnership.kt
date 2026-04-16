package com.blockstream.common.walletabi

import com.blockstream.common.gdk.data.Address
import com.blockstream.common.gdk.data.PreviousAddresses
import com.blockstream.common.gdk.data.ValidateAddressees
import com.blockstream.common.walletabi.transport.WalletAbiOutputSchema
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

private const val WALLET_ABI_PREVIOUS_ADDRESS_PAGE_LIMIT = 20

 data class WalletAbiKnownDestinationLookup(
    val addresses: Set<String> = emptySet(),
    val scripts: Set<String> = emptySet(),
)

 suspend fun loadWalletAbiKnownDestinationLookup(
    loadPreviousAddresses: suspend (lastPointer: Int?) -> PreviousAddresses,
): WalletAbiKnownDestinationLookup {
    val addresses = linkedSetOf<String>()
    val scripts = linkedSetOf<String>()
    var lastPointer: Int? = null
    var pageCount = 0

    while (pageCount < WALLET_ABI_PREVIOUS_ADDRESS_PAGE_LIMIT) {
        val page = loadPreviousAddresses(lastPointer)
        page.addresses.forEach { address ->
            address.normalizedAddressOrNull()?.let(addresses::add)
            address.normalizedScriptOrNull()?.let(scripts::add)
        }

        val nextPointer = page.lastPointer ?: break
        if (nextPointer == lastPointer) {
            break
        }
        lastPointer = nextPointer
        pageCount += 1
    }

    return WalletAbiKnownDestinationLookup(
        addresses = addresses,
        scripts = scripts,
    )
}

 fun walletAbiValidatedAddresseeIndicatesWalletOwnership(
    result: ValidateAddressees,
): Boolean {
    if (!result.isValid) {
        return false
    }

    return result.addressees.any { addressee ->
        addressee["is_internal"]?.let { json ->
            if ((json as? JsonPrimitive)?.booleanOrNull == true) {
                return true
            }
        }

        "pointer" in addressee ||
            "subaccount" in addressee ||
            "user_path" in addressee
    }
}

 fun walletAbiOutputMatchesKnownWalletScript(
    output: WalletAbiOutputSchema,
    knownScripts: Set<String>,
): Boolean {
    return walletAbiOutputScriptCandidates(output).any { it in knownScripts }
}

 fun walletAbiOutputMatchesKnownWalletAddress(
    output: WalletAbiOutputSchema,
    knownAddresses: Set<String>,
): Boolean {
    return walletAbiOutputAddressCandidates(output).any { it in knownAddresses }
}

 fun walletAbiOutputMatchesKnownWalletDestination(
    output: WalletAbiOutputSchema,
    knownDestinations: WalletAbiKnownDestinationLookup,
): Boolean {
    return walletAbiOutputMatchesKnownWalletAddress(
        output = output,
        knownAddresses = knownDestinations.addresses,
    ) || walletAbiOutputMatchesKnownWalletScript(
        output = output,
        knownScripts = knownDestinations.scripts,
    )
}

 fun walletAbiOutputAddressCandidates(output: WalletAbiOutputSchema): Set<String> {
    val candidates = linkedSetOf<String>()
    collectWalletAbiAddressCandidates(
        element = output.lock,
        into = candidates,
    )
    return candidates
}

private fun walletAbiOutputScriptCandidates(output: WalletAbiOutputSchema): Set<String> {
    val candidates = linkedSetOf<String>()
    collectWalletAbiScriptCandidates(
        element = output.lock,
        into = candidates,
    )
    return candidates
}

private fun collectWalletAbiScriptCandidates(
    element: JsonElement,
    into: MutableSet<String>,
) {
    when (element) {
        is JsonObject -> {
            element.forEach { (key, value) ->
                if (key == "script" || key == "script_pubkey" || key == "scriptpubkey") {
                    (value as? JsonPrimitive)
                        ?.takeIf { it.isString }
                        ?.content
                        ?.trim()
                        ?.takeIf { it.isNotEmpty() }
                        ?.lowercase()
                        ?.let(into::add)
                }

                collectWalletAbiScriptCandidates(
                    element = value,
                    into = into,
                )
            }
        }

        is JsonArray -> {
            element.forEach { value ->
                collectWalletAbiScriptCandidates(
                    element = value,
                    into = into,
                )
            }
        }

        else -> Unit
    }
}

private fun Address.normalizedAddressOrNull(): String? {
    return address
        .trim()
        .takeIf { it.isNotEmpty() }
        ?.lowercase()
}

private fun Address.normalizedScriptOrNull(): String? {
    return script
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?.lowercase()
}

private fun collectWalletAbiAddressCandidates(
    element: JsonElement,
    into: MutableSet<String>,
) {
    when (element) {
        is JsonObject -> {
            element.forEach { (key, value) ->
                if (key == "address" || key == "unblinded_address" || key == "unconfidential_address") {
                    (value as? JsonPrimitive)
                        ?.takeIf { it.isString }
                        ?.content
                        ?.trim()
                        ?.takeIf { it.isNotEmpty() }
                        ?.lowercase()
                        ?.let(into::add)
                }

                collectWalletAbiAddressCandidates(
                    element = value,
                    into = into,
                )
            }
        }

        is JsonArray -> {
            element.forEach { value ->
                collectWalletAbiAddressCandidates(
                    element = value,
                    into = into,
                )
            }
        }

        else -> Unit
    }
}
