package com.blockstream.common.walletabi

import com.blockstream.common.walletabi.transport.WalletAbiOutputSchema
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@Serializable
internal enum class WalletAbiOutputClassification {
    EXTERNAL,
    WALLET_RECEIVE,
    OP_RETURN,
    UNKNOWN,
}

internal fun classifyWalletAbiOutput(output: WalletAbiOutputSchema): WalletAbiOutputClassification {
    val lockObject = output.lock as? JsonObject ?: output.lock.jsonObject
    return when (lockObject["type"]?.jsonPrimitive?.content) {
        "finalizer" -> when (lockObject["finalizer"]?.jsonObject?.get("type")?.jsonPrimitive?.content) {
            "wallet" -> WalletAbiOutputClassification.WALLET_RECEIVE
            else -> if (output.hasWalletBlinder()) {
                WalletAbiOutputClassification.WALLET_RECEIVE
            } else {
                WalletAbiOutputClassification.UNKNOWN
            }
        }

        "script" -> {
            val script = lockObject["script"]?.jsonPrimitive?.content.orEmpty().lowercase()
            if (script.startsWith("6a")) {
                WalletAbiOutputClassification.OP_RETURN
            } else if (output.hasWalletBlinder()) {
                WalletAbiOutputClassification.WALLET_RECEIVE
            } else {
                WalletAbiOutputClassification.EXTERNAL
            }
        }

        else -> if (output.hasWalletBlinder()) {
            WalletAbiOutputClassification.WALLET_RECEIVE
        } else {
            WalletAbiOutputClassification.UNKNOWN
        }
    }
}

internal suspend fun resolveWalletAbiOutputClassification(
    output: WalletAbiOutputSchema,
    walletOwnedDestinationDetector: suspend (WalletAbiOutputSchema) -> Boolean = { false },
): WalletAbiOutputClassification {
    val heuristic = classifyWalletAbiOutput(output)
    if (heuristic == WalletAbiOutputClassification.WALLET_RECEIVE ||
        heuristic == WalletAbiOutputClassification.OP_RETURN
    ) {
        return heuristic
    }

    return if (walletOwnedDestinationDetector(output)) {
        WalletAbiOutputClassification.WALLET_RECEIVE
    } else {
        heuristic
    }
}

private fun WalletAbiOutputSchema.hasWalletBlinder(): Boolean {
    return blinder.enumVariantNameOrNull() == "wallet"
}

private fun JsonElement.enumVariantNameOrNull(): String? {
    return when (this) {
        is JsonObject -> this["type"]?.jsonPrimitive?.content ?: entries.singleOrNull()?.key
        else -> runCatching { jsonPrimitive.content }.getOrNull()
    }
}
