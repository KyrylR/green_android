package com.blockstream.common.walletabi

import com.blockstream.common.walletabi.transport.WalletAbiErrorInfo
import com.blockstream.common.walletabi.transport.WalletAbiInputSchema
import com.blockstream.common.walletabi.transport.WalletAbiNetwork
import com.blockstream.common.walletabi.transport.WalletAbiOutputSchema
import com.blockstream.common.walletabi.transport.WalletAbiRuntimeParams
import com.blockstream.common.walletabi.transport.WalletAbiStatus
import com.blockstream.common.walletabi.transport.WalletAbiTransactionInfo
import com.blockstream.common.walletabi.transport.WalletAbiTxCreateRequest
import com.blockstream.common.walletabi.transport.WalletAbiTxCreateResponse
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import lwk.Chain
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WalletAbiSupportTest {
    private val json = Json {
        encodeDefaults = true
        explicitNulls = false
        ignoreUnknownKeys = true
    }

    @Test
    fun txCreateRequestEncodesWalletAbiWireFieldNames() {
        val requestJson = json.encodeToString(
            WalletAbiTxCreateRequest(
                abiVersion = "wallet-abi-0.1",
                requestId = "req-1",
                network = WalletAbiNetwork.TESTNET_LIQUID,
                params = WalletAbiRuntimeParams(
                    inputs = listOf(
                        WalletAbiInputSchema(
                            id = "input-1",
                            utxoSource = buildJsonObject {
                                put("type", "wallet")
                            },
                            unblinding = buildJsonObject {
                                put("type", "wallet")
                            },
                            sequence = 1L,
                            finalizer = buildJsonObject {
                                put("type", "wallet")
                            },
                        ),
                    ),
                    outputs = listOf(
                        WalletAbiOutputSchema(
                            id = "output-1",
                            amountSat = 2_500L,
                            lock = buildJsonObject {
                                put("type", "script")
                                put("script", "0014abcd")
                            },
                            asset = buildJsonObject {
                                put("asset_id", "asset-1")
                            },
                            blinder = buildJsonObject {
                                put("type", "wallet")
                            },
                        ),
                    ),
                    feeRateSatKvb = 123.5f,
                    lockTime = JsonPrimitive(144),
                ),
                broadcast = true,
            ),
        )

        assertTrue(requestJson.contains("\"abi_version\":\"wallet-abi-0.1\""))
        assertTrue(requestJson.contains("\"request_id\":\"req-1\""))
        assertTrue(requestJson.contains("\"network\":\"testnet-liquid\""))
        assertTrue(requestJson.contains("\"fee_rate_sat_kvb\":123.5"))
        assertTrue(requestJson.contains("\"lock_time\":144"))
        assertTrue(requestJson.contains("\"utxo_source\""))
        assertTrue(requestJson.contains("\"amount_sat\":2500"))
    }

    @Test
    fun txCreateResponseDecodesSuccessPayload() {
        val response = json.decodeFromString<WalletAbiTxCreateResponse>(
            """
                {
                  "abi_version": "wallet-abi-0.1",
                  "request_id": "req-1",
                  "network": "liquid",
                  "status": "ok",
                  "transaction": {
                    "tx_hex": "deadbeef",
                    "txid": "abc123"
                  },
                  "artifacts": {
                    "pset": "cHNldA=="
                  }
                }
            """.trimIndent(),
        )

        assertEquals("wallet-abi-0.1", response.abiVersion)
        assertEquals("req-1", response.requestId)
        assertEquals(WalletAbiNetwork.LIQUID, response.network)
        assertEquals(WalletAbiStatus.OK, response.status)
        assertEquals(
            WalletAbiTransactionInfo(
                txHex = "deadbeef",
                txid = "abc123",
            ),
            response.transaction,
        )
        assertEquals("cHNldA==", response.artifacts?.get("pset")?.let { (it as JsonPrimitive).content })
        assertNull(response.error)
    }

    @Test
    fun txCreateResponseEncodesErrorPayloadDetails() {
        val responseJson = json.encodeToString(
            WalletAbiTxCreateResponse(
                abiVersion = "wallet-abi-0.1",
                requestId = "req-2",
                network = WalletAbiNetwork.LOCALTEST_LIQUID,
                status = WalletAbiStatus.ERROR,
                error = WalletAbiErrorInfo(
                    code = "denied",
                    message = "user rejected",
                    details = buildJsonObject {
                        put("reason", "slide_cancelled")
                    },
                ),
            ),
        )

        val decoded = json.decodeFromString<WalletAbiTxCreateResponse>(responseJson)
        val error = assertNotNull(decoded.error)
        val details = assertNotNull(error.details).jsonObject

        assertTrue(responseJson.contains("\"network\":\"localtest-liquid\""))
        assertTrue(responseJson.contains("\"status\":\"error\""))
        assertEquals(WalletAbiStatus.ERROR, decoded.status)
        assertEquals(
            WalletAbiErrorInfo(
                code = "denied",
                message = "user rejected",
                details = buildJsonObject {
                    put("reason", "slide_cancelled")
                },
            ),
            error,
        )
        assertEquals("slide_cancelled", details.getValue("reason").let { (it as JsonPrimitive).content })
        assertNull(decoded.transaction)
    }

    @Test
    fun esploraApiBaseUrlNormalizationAppendsApiAndTrimsTxSuffix() {
        assertEquals("https://blockstream.info/liquid/api", "https://blockstream.info/liquid".toWalletAbiEsploraApiBaseUrl())
        assertEquals("https://blockstream.info/liquid/api", "https://blockstream.info/liquid/api".toWalletAbiEsploraApiBaseUrl())
        assertEquals("https://blockstream.info/liquid/api", " https://blockstream.info/liquid/tx/ ".toWalletAbiEsploraApiBaseUrl())
        assertNull("   ".toWalletAbiEsploraApiBaseUrl())
        assertNull((null as String?).toWalletAbiEsploraApiBaseUrl())
    }

    @Test
    fun explicitTxOutRequiresAssetValueAndScript() {
        val explicit = WalletAbiEsploraVout(
            scriptPubkey = "0014751e76e8199196d454941c45d1b3a323f1433bd6",
            value = 1_234L,
            asset = "5ac9f65c6cbeca7c9c0f74c684e1e4e79d7bb7aa6fe8f5a3303ad42d00000001",
        ).toExplicitTxOutOrNull()

        assertNotNull(explicit)
        assertNull(
            WalletAbiEsploraVout(
                scriptPubkey = "0014751e76e8199196d454941c45d1b3a323f1433bd6",
                value = null,
                asset = "5ac9f65c6cbeca7c9c0f74c684e1e4e79d7bb7aa6fe8f5a3303ad42d00000001",
            ).toExplicitTxOutOrNull(),
        )
        assertNull(
            WalletAbiEsploraVout(
                scriptPubkey = "0014751e76e8199196d454941c45d1b3a323f1433bd6",
                value = 1_234L,
                asset = null,
            ).toExplicitTxOutOrNull(),
        )
        assertNull(
            WalletAbiEsploraVout(
                scriptPubkey = "",
                value = 1_234L,
                asset = "5ac9f65c6cbeca7c9c0f74c684e1e4e79d7bb7aa6fe8f5a3303ad42d00000001",
            ).toExplicitTxOutOrNull(),
        )
    }

    @Test
    fun walletAbiDerivationPathPrefersExactUserPath() {
        val derivationPath = walletAbiDerivationPath(
            accountDerivationPath = listOf(2147483732L, 2147485424L, 2147483648L),
            io = com.blockstream.common.gdk.data.InputOutput(
                userPath = listOf(2147483732L, 2147485424L, 2147483648L, 1L, 12L),
            ),
        )

        assertEquals(
            listOf(2147483732u, 2147485424u, 2147483648u, 1u, 12u),
            derivationPath,
        )
    }

    @Test
    fun walletAbiDerivationPathFallsBackToAccountPathAndChangeFlags() {
        val derivationPath = walletAbiDerivationPath(
            accountDerivationPath = listOf(2147483732L, 2147485424L, 2147483648L),
            io = com.blockstream.common.gdk.data.InputOutput(
                isInternal = true,
                pointer = 7,
            ),
        )

        assertEquals(
            listOf(2147483732u, 2147485424u, 2147483648u, 1u, 7u),
            derivationPath,
        )
        assertEquals(
            WalletAbiOutputDerivation(
                chain = Chain.INTERNAL,
                wildcardIndex = 7u,
            ),
            com.blockstream.common.gdk.data.InputOutput(
                isInternal = true,
                pointer = 7,
            ).toWalletAbiDerivation(),
        )
    }

    @Test
    fun walletAbiExplicitTxOutFallsBackToPolicyAsset() {
        val txOut = com.blockstream.common.gdk.data.InputOutput(
            scriptPubkey = "0014751e76e8199196d454941c45d1b3a323f1433bd6",
            satoshi = 42L,
        ).toWalletAbiExplicitTxOutOrNull(
            policyAsset = "5ac9f65c6cbeca7c9c0f74c684e1e4e79d7bb7aa6fe8f5a3303ad42d00000001",
        )

        assertNotNull(txOut)
        assertEquals("0014751e76e8199196d454941c45d1b3a323f1433bd6", txOut.scriptPubkey().toString())
    }
}
