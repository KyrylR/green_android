package com.blockstream.common.walletabi.transport

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

@Serializable
enum class WalletAbiStatus {
    @SerialName("ok")
    OK,

    @SerialName("error")
    ERROR,
}

@Serializable
enum class WalletAbiNetwork {
    @SerialName("liquid")
    LIQUID,

    @SerialName("testnet-liquid")
    TESTNET_LIQUID,

    @SerialName("localtest-liquid")
    LOCALTEST_LIQUID,
}

@Serializable
data class WalletAbiRuntimeParams(
    val inputs: List<WalletAbiInputSchema>,
    val outputs: List<WalletAbiOutputSchema>,
    @SerialName("fee_rate_sat_kvb")
    val feeRateSatKvb: Float? = null,
    @SerialName("lock_time")
    val lockTime: JsonElement? = null,
)

@Serializable
data class WalletAbiInputSchema(
    val id: String,
    @SerialName("utxo_source")
    val utxoSource: JsonElement,
    val unblinding: JsonElement,
    val sequence: Long,
    val issuance: JsonElement? = null,
    val finalizer: JsonElement,
)

@Serializable
data class WalletAbiOutputSchema(
    val id: String,
    @SerialName("amount_sat")
    val amountSat: Long,
    val lock: JsonElement,
    val asset: JsonElement,
    val blinder: JsonElement,
)

@Serializable
data class WalletAbiTxCreateRequest(
    @SerialName("abi_version")
    val abiVersion: String,
    @SerialName("request_id")
    val requestId: String,
    val network: WalletAbiNetwork,
    val params: WalletAbiRuntimeParams,
    val broadcast: Boolean,
)

@Serializable
data class WalletAbiTxCreateResponse(
    @SerialName("abi_version")
    val abiVersion: String,
    @SerialName("request_id")
    val requestId: String,
    val network: WalletAbiNetwork,
    val status: WalletAbiStatus,
    val transaction: WalletAbiTransactionInfo? = null,
    val artifacts: JsonObject? = null,
    val error: WalletAbiErrorInfo? = null,
)

@Serializable
data class WalletAbiTransactionInfo(
    @SerialName("tx_hex")
    val txHex: String,
    val txid: String,
)

@Serializable
data class WalletAbiErrorInfo(
    val code: String,
    val message: String,
    val details: JsonElement? = null,
)
