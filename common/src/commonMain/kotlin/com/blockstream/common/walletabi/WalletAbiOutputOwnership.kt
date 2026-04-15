package com.blockstream.common.walletabi

import com.blockstream.common.gdk.data.ValidateAddressees
import com.blockstream.common.walletabi.transport.WalletAbiOutputSchema
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

internal data class WalletAbiKnownDestinationLookup(
    val addresses: Set<String> = emptySet(),
    val scripts: Set<String> = emptySet(),
)

internal fun walletAbiValidatedAddresseeIndicatesWalletOwnership(
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

internal fun walletAbiOutputMatchesKnownWalletScript(
    output: WalletAbiOutputSchema,
    knownScripts: Set<String>,
): Boolean {
    return walletAbiOutputScriptCandidates(output).any { it in knownScripts }
}

internal fun walletAbiOutputMatchesKnownWalletAddress(
    output: WalletAbiOutputSchema,
    knownAddresses: Set<String>,
): Boolean {
    return walletAbiOutputAddressCandidates(output).any { it in knownAddresses }
}

internal fun walletAbiOutputMatchesKnownWalletDestination(
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

private fun walletAbiOutputAddressCandidates(output: WalletAbiOutputSchema): Set<String> {
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
