package com.blockstream.common.walletabi

import com.blockstream.common.gdk.data.Network as GdkNetwork
import com.blockstream.green.data.config.AppInfo
import com.blockstream.green.network.AppHttpClient
import com.blockstream.green.network.NetworkResponse
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import lwk.Script
import lwk.TxOut

 class WalletAbiEsploraHttpClient(appInfo: AppInfo) :
    AppHttpClient(enableLogging = appInfo.isDevelopmentOrDebug) {
    suspend fun getTransaction(
        apiBaseUrl: String,
        txid: String,
    ): NetworkResponse<WalletAbiEsploraTransaction> {
        val normalizedBaseUrl = apiBaseUrl.trim().trimEnd('/')
        return get("$normalizedBaseUrl/tx/$txid")
    }

    suspend fun getTransactionHex(
        apiBaseUrl: String,
        txid: String,
    ): NetworkResponse<String> {
        val normalizedBaseUrl = apiBaseUrl.trim().trimEnd('/')
        return get("$normalizedBaseUrl/tx/$txid/hex")
    }
}

@Serializable
 data class WalletAbiEsploraTransaction(
    val txid: String? = null,
    val vout: List<WalletAbiEsploraVout> = emptyList(),
)

@Serializable
 data class WalletAbiEsploraVout(
    @SerialName("scriptpubkey")
    val scriptPubkey: String? = null,
    @SerialName("scriptpubkey_hex")
    val scriptPubkeyHex: String? = null,
    val value: Long? = null,
    val asset: String? = null,
)

 fun String?.toWalletAbiEsploraApiBaseUrl(): String? {
    val trimmed = this?.trim()?.trimEnd('/') ?: return null
    if (trimmed.isBlank()) {
        return null
    }
    if (trimmed.endsWith("/api")) {
        return trimmed
    }

    val withoutTx = trimmed.removeSuffix("/tx")
    if (withoutTx != trimmed) {
        return if (withoutTx.endsWith("/api")) {
            withoutTx
        } else {
            "$withoutTx/api"
        }
    }

    return "$trimmed/api"
}

 fun GdkNetwork.walletAbiEsploraApiBaseUrlOrNull(): String? {
    explorerUrl.toWalletAbiEsploraApiBaseUrl()?.let { return it }

    return when (canonicalNetworkId) {
        GdkNetwork.GreenMainnet -> "https://blockstream.info/api"
        GdkNetwork.GreenTestnet -> "https://blockstream.info/testnet/api"
        GdkNetwork.GreenLiquid -> "https://blockstream.info/liquid/api"
        GdkNetwork.GreenTestnetLiquid -> "https://blockstream.info/liquidtestnet/api"
        else -> null
    }
}

 fun WalletAbiEsploraVout.toExplicitTxOutOrNull(): TxOut? {
    val scriptHex = (scriptPubkey ?: scriptPubkeyHex).orEmpty().trim()
    val assetId = asset.orEmpty().trim()
    val amount = value?.takeIf { it >= 0L }?.toULong() ?: return null
    if (scriptHex.isEmpty() || assetId.isEmpty()) {
        return null
    }

    val script = runCatching { Script(scriptHex) }.getOrNull() ?: return null
    return TxOut.fromExplicit(script, assetId, amount)
}
