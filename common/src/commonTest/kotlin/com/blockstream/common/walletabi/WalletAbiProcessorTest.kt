package com.blockstream.common.walletabi

import com.blockstream.common.gdk.GdkSession
import com.blockstream.common.gdk.data.Account
import com.blockstream.common.walletabi.transport.WalletAbiErrorInfo
import com.blockstream.common.walletabi.transport.WalletAbiNetwork
import com.blockstream.common.walletabi.transport.WalletAbiRuntimeParams
import com.blockstream.common.walletabi.transport.WalletAbiStatus
import com.blockstream.common.walletabi.transport.WalletAbiTxCreateRequest
import com.blockstream.common.walletabi.transport.WalletAbiTxCreateResponse
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import lwk.Network
import sun.misc.Unsafe

class WalletAbiProcessorTest {
    private val json = Json {
        encodeDefaults = true
        explicitNulls = false
        ignoreUnknownKeys = true
    }

    @Test
    fun processContextReturnsSanitizedAbiError() = runTest {
        val processor = WalletAbiProcessor(
            json = json,
            executionContextResolver = unusedResolver(),
            providerRunner = object : WalletAbiProviderRunning {
                override suspend fun run(
                    context: WalletAbiExecutionContext,
                    request: WalletAbiTxCreateRequest,
                    requestJson: String,
                ): WalletAbiProviderRunResult {
                    val response = WalletAbiTxCreateResponse(
                        abiVersion = request.abiVersion,
                        requestId = request.requestId,
                        network = request.network,
                        status = WalletAbiStatus.ERROR,
                        error = WalletAbiErrorInfo(
                            code = "denied",
                            message = "Wallet ABI failed | trace: signer:getPubkey",
                            details = JsonPrimitive("full-details"),
                        ),
                    )
                    return WalletAbiProviderRunResult(
                        response = response,
                        responseJson = json.encodeToString(response),
                    )
                }

                override suspend fun runJsonRpcRequest(
                    context: WalletAbiExecutionContext,
                    requestEnvelopeJson: String,
                ): WalletAbiProviderJsonRpcRunResult {
                    error("not used")
                }
            },
        )

        val result = withContext(network = WalletAbiNetwork.LIQUID) { context, requestJson ->
            processor.process(
                context = context,
                requestJson = requestJson,
            )
        }

        val abiError = assertIs<WalletAbiProcessResult.AbiError>(result)
        assertFalse(abiError.error.message.contains("| trace:"))
        assertTrue(abiError.error.message.contains("truncated"))
        assertNull(abiError.error.details)
        assertEquals(abiError.error, abiError.response.error)
    }

    @Test
    fun processContextRejectsMalformedJsonBeforeRunner() = runTest {
        var runnerCalled = false
        val processor = WalletAbiProcessor(
            json = json,
            executionContextResolver = unusedResolver(),
            providerRunner = object : WalletAbiProviderRunning {
                override suspend fun run(
                    context: WalletAbiExecutionContext,
                    request: WalletAbiTxCreateRequest,
                    requestJson: String,
                ): WalletAbiProviderRunResult {
                    runnerCalled = true
                    error("not used")
                }

                override suspend fun runJsonRpcRequest(
                    context: WalletAbiExecutionContext,
                    requestEnvelopeJson: String,
                ): WalletAbiProviderJsonRpcRunResult {
                    runnerCalled = true
                    error("not used")
                }
            },
        )

        val result = withContext(network = WalletAbiNetwork.LIQUID) { context, _ ->
            processor.process(
                context = context,
                requestJson = "{",
            )
        }

        val failed = assertIs<WalletAbiProcessResult.Failed>(result)
        assertEquals("Wallet ABI request JSON is malformed", failed.message)
        assertFalse(runnerCalled)
    }

    @Test
    fun processContextRejectsNetworkMismatchBeforeRunner() = runTest {
        var runnerCalled = false
        val processor = WalletAbiProcessor(
            json = json,
            executionContextResolver = unusedResolver(),
            providerRunner = object : WalletAbiProviderRunning {
                override suspend fun run(
                    context: WalletAbiExecutionContext,
                    request: WalletAbiTxCreateRequest,
                    requestJson: String,
                ): WalletAbiProviderRunResult {
                    runnerCalled = true
                    error("not used")
                }

                override suspend fun runJsonRpcRequest(
                    context: WalletAbiExecutionContext,
                    requestEnvelopeJson: String,
                ): WalletAbiProviderJsonRpcRunResult {
                    runnerCalled = true
                    error("not used")
                }
            },
        )

        val result = withContext(network = WalletAbiNetwork.TESTNET_LIQUID) { context, requestJson ->
            processor.process(
                context = context,
                requestJson = requestJson,
            )
        }

        val failed = assertIs<WalletAbiProcessResult.Failed>(result)
        assertEquals(
            "Wallet ABI request network mismatch: request=liquid context=testnet-liquid",
            failed.message,
        )
        assertFalse(runnerCalled)
    }

    @Test
    fun sanitizeWalletAbiErrorRemovesTraceAndDetails() {
        val sanitized = sanitizeWalletAbiErrorForSessionResponse(
            WalletAbiErrorInfo(
                code = "invalid_request",
                message = "Wallet ABI failed | trace: signer:getPubkey",
                details = JsonPrimitive("full-details"),
            ),
        )

        assertFalse(sanitized.message.contains("| trace:"))
        assertNull(sanitized.details)
        assertTrue(sanitized.message.contains("truncated"))
    }

    @Test
    fun buildWalletAbiExceptionDetailsIncludesCauseChain() {
        val root = IllegalArgumentException("bad input")
        val wrapped = IllegalStateException("top level", root)

        val details = buildWalletAbiExceptionDetails(wrapped)

        assertTrue(details.contains("IllegalStateException(top level)"))
        assertTrue(details.contains("IllegalArgumentException(bad input)"))
    }

    private suspend fun withContext(
        network: WalletAbiNetwork,
        block: suspend (WalletAbiExecutionContext, String) -> WalletAbiProcessResult,
    ): WalletAbiProcessResult {
        val lwkNetwork = when (network) {
            WalletAbiNetwork.LIQUID -> Network.mainnet()
            WalletAbiNetwork.TESTNET_LIQUID -> Network.testnet()
            WalletAbiNetwork.LOCALTEST_LIQUID -> Network.regtestDefault()
        }
        return try {
            val context = WalletAbiExecutionContext(
                session = unusedSession(),
                requestNetwork = network,
                accounts = emptyList(),
                primaryAccount = unusedAccount(),
                lwkNetwork = lwkNetwork,
                signerKind = WalletAbiSignerKind.SOFTWARE,
            )
            block(context, validRequestJson())
        } finally {
            lwkNetwork.close()
        }
    }

    private fun unusedResolver() = object : WalletAbiExecutionContextResolving {
        override suspend fun resolveDirect(
            session: GdkSession,
            requestNetwork: WalletAbiNetwork,
            preferredAccountId: String?,
        ): WalletAbiExecutionContext {
            error("not used")
        }

        override suspend fun resolveSessionRequest(
            incoming: GdkSession,
            requestNetwork: WalletAbiNetwork,
            preferredAccountId: String?,
        ): WalletAbiExecutionContext {
            error("not used")
        }
    }

    private fun validRequestJson(): String {
        return json.encodeToString(
            WalletAbiTxCreateRequest(
                abiVersion = "wallet-abi-0.1",
                requestId = "req-1",
                network = WalletAbiNetwork.LIQUID,
                params = WalletAbiRuntimeParams(
                    inputs = emptyList(),
                    outputs = emptyList(),
                ),
                broadcast = true,
            ),
        )
    }

    private fun unusedSession(): GdkSession = uninitializedInstance(GdkSession::class.java)

    private fun unusedAccount(): Account = uninitializedInstance(Account::class.java)

    @Suppress("UNCHECKED_CAST")
    private fun <T> uninitializedInstance(type: Class<T>): T {
        val field = Unsafe::class.java.getDeclaredField("theUnsafe").apply {
            isAccessible = true
        }
        val unsafe = field.get(null) as Unsafe
        return unsafe.allocateInstance(type) as T
    }
}
