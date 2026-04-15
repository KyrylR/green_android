package com.blockstream.common.walletabi

import com.blockstream.common.gdk.data.Address
import com.blockstream.common.gdk.data.PreviousAddresses
import com.blockstream.common.gdk.data.ValidateAddressees
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
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.coroutines.test.runTest
import lwk.Chain
import lwk.OutPoint
import lwk.PsetBuilder
import lwk.PsetInputBuilder
import lwk.PsetOutputBuilder
import lwk.Script
import lwk.Txid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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

    @Test
    fun walletAbiOutputClassificationTreatsWalletBlindedScriptAsWalletReceive() {
        val output = walletOutputSchema()

        assertEquals(
            WalletAbiOutputClassification.WALLET_RECEIVE,
            classifyWalletAbiOutput(output),
        )
    }

    @Test
    fun walletAbiOutputClassificationAcceptsStringBlinderVariant() {
        val output = walletOutputSchema(
            blinder = JsonPrimitive("wallet"),
        )

        assertEquals(
            WalletAbiOutputClassification.WALLET_RECEIVE,
            classifyWalletAbiOutput(output),
        )
    }

    @Test
    fun walletAbiOutputClassificationKeepsOpReturnAsBurnLike() {
        val output = WalletAbiOutputSchema(
            id = "burn-0",
            amountSat = 100,
            lock = buildJsonObject {
                put("type", "script")
                put("script", "6a0bdeadbeef")
            },
            asset = buildJsonObject {
                put("asset_id", "policy-asset")
            },
            blinder = buildJsonObject {
                put("type", "wallet")
            },
        )

        assertEquals(
            WalletAbiOutputClassification.OP_RETURN,
            classifyWalletAbiOutput(output),
        )
    }

    @Test
    fun walletAbiOutputClassificationPromotesKnownWalletDestination() = runTest {
        val output = externalOutputSchema()

        assertEquals(
            WalletAbiOutputClassification.WALLET_RECEIVE,
            resolveWalletAbiOutputClassification(
                output = output,
                walletOwnedDestinationDetector = { true },
            ),
        )
    }

    @Test
    fun walletAbiRequestNeedsResolutionWhenOutputAssetIdIsDeferred() {
        val request = txCreateRequest(
            outputs = listOf(
                externalOutputSchema().copy(
                    asset = buildJsonObject {
                        put("type", "issuance_asset")
                    },
                ),
            ),
        )

        assertTrue(walletAbiRequestNeedsResolution(request))
    }

    @Test
    fun walletAbiResolvedOutputsFromTransactionHexReadsExplicitOutputs() {
        val txHex = PsetBuilder.newV2().run {
            addInput(
                PsetInputBuilder.fromPrevout(
                    OutPoint.fromParts(
                        Txid.fromString("0000000000000000000000000000000000000000000000000000000000000001"),
                        0u,
                    ),
                ).build(),
            )
            addOutput(
                PsetOutputBuilder.newExplicit(
                    scriptPubkey = Script("0014751e76e8199196d454941c45d1b3a323f1433bd6"),
                    satoshi = 1_234u,
                    asset = "5ac9f65c6cbeca7c9c0f74c684e1e4e79d7bb7aa6fe8f5a3303ad42d00000001",
                ).build(),
            )
            addOutput(
                PsetOutputBuilder.newExplicit(
                    scriptPubkey = Script("0014feedface1234feedface1234feedface1234feed"),
                    satoshi = 4_321u,
                    asset = "4f0d5ff8f2c8d9d6e28d208e65d1f9cf56d0b34b5f5b0df4f5eb80b600000002",
                ).build(),
            )
            build().extractTx().toString()
        }

        val outputs = walletAbiResolvedOutputsFromTransactionHex(txHex)

        assertEquals(
            listOf(
                WalletAbiResolvedTransactionOutput(
                    assetId = "5ac9f65c6cbeca7c9c0f74c684e1e4e79d7bb7aa6fe8f5a3303ad42d00000001",
                    scriptHex = "0014751e76e8199196d454941c45d1b3a323f1433bd6",
                ),
                WalletAbiResolvedTransactionOutput(
                    assetId = "4f0d5ff8f2c8d9d6e28d208e65d1f9cf56d0b34b5f5b0df4f5eb80b600000002",
                    scriptHex = "0014feedface1234feedface1234feedface1234feed",
                ),
            ),
            outputs,
        )
    }

    @Test
    fun walletAbiResolvedOutputsFromTransactionHexRejectsMalformedHex() {
        assertTrue(walletAbiResolvedOutputsFromTransactionHex("not-a-transaction").isEmpty())
    }

    @Test
    fun walletAbiValidatedAddresseeIndicatesWalletOwnershipNeedsWalletMetadata() {
        assertTrue(
            walletAbiValidatedAddresseeIndicatesWalletOwnership(
                ValidateAddressees(
                    addressees = listOf(
                        buildJsonObject {
                            put("address", "tex1qwallet")
                            put("pointer", 7)
                        },
                    ),
                    isValid = true,
                ),
            ),
        )

        assertTrue(
            walletAbiValidatedAddresseeIndicatesWalletOwnership(
                ValidateAddressees(
                    addressees = listOf(
                        buildJsonObject {
                            put("address", "tex1qwallet")
                            put("is_internal", true)
                        },
                    ),
                    isValid = true,
                ),
            ),
        )

        assertFalse(
            walletAbiValidatedAddresseeIndicatesWalletOwnership(
                ValidateAddressees(
                    addressees = listOf(
                        buildJsonObject {
                            put("address", "tex1qexternal")
                            put("address_type", "p2wpkh")
                        },
                    ),
                    isValid = true,
                ),
            ),
        )

        assertFalse(
            walletAbiValidatedAddresseeIndicatesWalletOwnership(
                ValidateAddressees(
                    addressees = listOf(
                        buildJsonObject {
                            put("address", "tex1qwallet")
                            put("pointer", 7)
                        },
                    ),
                    isValid = false,
                ),
            ),
        )
    }

    @Test
    fun walletAbiOutputMatchesKnownWalletScriptReadsNestedScriptCandidates() {
        val output = externalOutputSchema().copy(
            lock = buildJsonObject {
                put("type", "script")
                put(
                    "lock",
                    buildJsonObject {
                        put("script_pubkey", "0014FEEDFACE1234FEEDFACE1234FEEDFACE1234")
                    },
                )
            },
        )

        assertTrue(
            walletAbiOutputMatchesKnownWalletScript(
                output = output,
                knownScripts = setOf("0014feedface1234feedface1234feedface1234"),
            ),
        )
        assertFalse(
            walletAbiOutputMatchesKnownWalletScript(
                output = output,
                knownScripts = setOf("0014aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"),
            ),
        )
    }

    @Test
    fun walletAbiOutputMatchesKnownWalletAddressReadsNestedAddressCandidates() {
        val output = externalOutputSchema().copy(
            lock = buildJsonObject {
                put("type", "address")
                put(
                    "recipient",
                    buildJsonObject {
                        put("unconfidential_address", "TEX1QWALLETADDRESS")
                    },
                )
            },
        )

        assertTrue(
            walletAbiOutputMatchesKnownWalletAddress(
                output = output,
                knownAddresses = setOf("tex1qwalletaddress"),
            ),
        )
        assertFalse(
            walletAbiOutputMatchesKnownWalletAddress(
                output = output,
                knownAddresses = setOf("tex1qsomeoneelse"),
            ),
        )
    }

    @Test
    fun walletAbiOutputMatchesKnownWalletDestinationMatchesKnownAddressOrScript() {
        assertTrue(
            walletAbiOutputMatchesKnownWalletDestination(
                output = externalOutputSchema().copy(
                    lock = buildJsonObject {
                        put("type", "address")
                        put("address", "tex1qwalletaddress")
                    },
                ),
                knownDestinations = WalletAbiKnownDestinationLookup(
                    addresses = setOf("tex1qwalletaddress"),
                ),
            ),
        )
        assertTrue(
            walletAbiOutputMatchesKnownWalletDestination(
                output = externalOutputSchema().copy(
                    lock = buildJsonObject {
                        put("type", "script")
                        put("script", "0014feedface1234feedface1234feedface1234")
                    },
                ),
                knownDestinations = WalletAbiKnownDestinationLookup(
                    scripts = setOf("0014feedface1234feedface1234feedface1234"),
                ),
            ),
        )
        assertFalse(
            walletAbiOutputMatchesKnownWalletDestination(
                output = externalOutputSchema(),
                knownDestinations = WalletAbiKnownDestinationLookup(),
            ),
        )
    }

    @Test
    fun loadWalletAbiKnownDestinationLookupCollectsPagedAddressesAndScripts() = runTest {
        var call = 0
        val lookup = loadWalletAbiKnownDestinationLookup { lastPointer ->
            when (call++) {
                0 -> {
                    assertEquals(null, lastPointer)
                    PreviousAddresses(
                        lastPointer = 11,
                        addresses = listOf(
                            Address(
                                address = "TEX1QFIRST",
                                script = "0014FIRSTFIRSTFIRSTFIRSTFIRSTFIRSTFIRST12",
                            ),
                        ),
                    )
                }

                else -> {
                    assertEquals(11, lastPointer)
                    PreviousAddresses(
                        lastPointer = null,
                        addresses = listOf(
                            Address(
                                address = "tex1qsecond",
                                script = "0014secondsecondsecondsecondsecond1234",
                            ),
                        ),
                    )
                }
            }
        }

        assertEquals(setOf("tex1qfirst", "tex1qsecond"), lookup.addresses)
        assertEquals(
            setOf(
                "0014firstfirstfirstfirstfirstfirstfirst12",
                "0014secondsecondsecondsecondsecond1234",
            ),
            lookup.scripts,
        )
    }

    @Test
    fun walletAbiTxRequestWithResolvedOutputsPatchesDeferredOutputsAndScripts() {
        val request = txCreateRequest(
            outputs = listOf(
                externalOutputSchema(assetId = "known-asset"),
                externalOutputSchema().copy(
                    asset = buildJsonObject {
                        put("type", "issuance_asset")
                    },
                ),
            ),
        )

        val resolved = walletAbiTxRequestWithResolvedOutputs(
            txRequest = request,
            resolvedOutputs = listOf(
                WalletAbiResolvedTransactionOutput(
                    assetId = "ignored",
                    scriptHex = "0014aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                ),
                WalletAbiResolvedTransactionOutput(
                    assetId = "resolved-asset-id",
                    scriptHex = "0014feedface1234feedface1234feedface1234",
                ),
            ),
        )

        assertEquals("known-asset", resolveWalletAbiAssetId(resolved.params.outputs[0].asset))
        assertEquals("resolved-asset-id", resolveWalletAbiAssetId(resolved.params.outputs[1].asset))
        assertEquals(
            "0014aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            resolved.params.outputs[0].lock.jsonObject.getValue("script").jsonPrimitive.content,
        )
        assertEquals(
            "0014feedface1234feedface1234feedface1234",
            resolved.params.outputs[1].lock.jsonObject.getValue("script").jsonPrimitive.content,
        )
    }

    private fun walletOutputSchema(
        assetId: String = "policy-asset",
        amountSat: Long = 2_500,
        blinder: kotlinx.serialization.json.JsonElement = buildJsonObject {
            put("type", "wallet")
        },
    ) = WalletAbiOutputSchema(
        id = "receive-0",
        amountSat = amountSat,
        lock = buildJsonObject {
            put("type", "script")
            put("script", "0014feedface1234feedface1234feedface1234")
        },
        asset = buildJsonObject {
            put("asset_id", assetId)
        },
        blinder = blinder,
    )

    private fun externalOutputSchema(
        assetId: String = "policy-asset",
        amountSat: Long = 2_500,
    ) = WalletAbiOutputSchema(
        id = "external-0",
        amountSat = amountSat,
        lock = buildJsonObject {
            put("type", "script")
            put("script", "0014aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
        },
        asset = buildJsonObject {
            put("asset_id", assetId)
        },
        blinder = buildJsonObject {
            put("type", "rand")
        },
    )

    private fun txCreateRequest(
        outputs: List<WalletAbiOutputSchema>,
    ) = WalletAbiTxCreateRequest(
        abiVersion = "wallet-abi-0.1",
        requestId = "req-1",
        network = WalletAbiNetwork.LIQUID,
        params = WalletAbiRuntimeParams(
            inputs = emptyList(),
            outputs = outputs,
        ),
        broadcast = true,
    )
}
