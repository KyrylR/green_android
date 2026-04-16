package com.blockstream.common.walletabi

import com.blockstream.common.walletabi.transport.WalletAbiTxCreateRequest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import lwk.Transaction

 fun resolveWalletAbiAssetId(asset: JsonElement): String {
    return asset.jsonObject["asset_id"]?.jsonPrimitive?.content
        ?: asset.jsonObject["assetId"]?.jsonPrimitive?.content
        ?: "unknown"
}

 fun walletAbiAssetIdNeedsResolution(asset: JsonElement): Boolean {
    val assetObject = asset as? JsonObject ?: return true
    return assetObject["asset_id"]?.jsonPrimitive?.contentOrNull.isNullOrBlank() &&
        assetObject["assetId"]?.jsonPrimitive?.contentOrNull.isNullOrBlank()
}

 fun walletAbiRequestNeedsResolution(txRequest: WalletAbiTxCreateRequest): Boolean {
    return txRequest.params.outputs.any { output ->
        walletAbiAssetIdNeedsResolution(output.asset)
    }
}

 data class WalletAbiResolvedTransactionOutput(
    val assetId: String?,
    val scriptHex: String?,
)

 fun walletAbiResolvedOutputsFromTransactionHex(txHex: String): List<WalletAbiResolvedTransactionOutput> {
    val transaction = runCatching { Transaction.fromString(txHex) }.getOrNull() ?: return emptyList()
    val outputs = runCatching { transaction.outputs() }.getOrNull() ?: return emptyList()
    return outputs.map { output ->
        WalletAbiResolvedTransactionOutput(
            assetId = runCatching { output.asset() }
                .getOrNull()
                ?.toString()
                ?.trim()
                ?.takeIf { it.isNotBlank() },
            scriptHex = runCatching { output.scriptPubkey().toString() }
                .getOrNull()
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?.lowercase(),
        )
    }
}

 fun walletAbiTxRequestWithResolvedOutputs(
    txRequest: WalletAbiTxCreateRequest,
    resolvedOutputs: List<WalletAbiResolvedTransactionOutput>,
): WalletAbiTxCreateRequest {
    val outputs = txRequest.params.outputs.mapIndexed { index, output ->
        val resolvedOutput = resolvedOutputs.getOrNull(index) ?: return@mapIndexed output
        val resolvedAssetId = resolvedOutput.assetId
            ?.takeIf { it.isNotBlank() && walletAbiAssetIdNeedsResolution(output.asset) }
        val resolvedScriptHex = resolvedOutput.scriptHex
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.lowercase()

        output.copy(
            asset = resolvedAssetId?.let {
                buildJsonObject {
                    put("asset_id", it)
                }
            } ?: output.asset,
            lock = resolvedScriptHex?.let { scriptHex ->
                buildJsonObject {
                    put("type", "script")
                    put("script", scriptHex)
                }
            } ?: output.lock,
        )
    }

    return txRequest.copy(
        params = txRequest.params.copy(outputs = outputs),
    )
}
