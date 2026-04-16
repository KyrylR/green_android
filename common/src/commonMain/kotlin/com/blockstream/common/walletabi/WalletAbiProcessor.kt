package com.blockstream.common.walletabi

import com.blockstream.common.gdk.GdkSession
import com.blockstream.common.walletabi.transport.WalletAbiErrorInfo
import com.blockstream.common.walletabi.transport.WalletAbiStatus
import com.blockstream.common.walletabi.transport.WalletAbiTxCreateRequest
import com.blockstream.common.walletabi.transport.WalletAbiTxCreateResponse
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private const val WALLET_ABI_SESSION_ERROR_MESSAGE_MAX_CHARS = 900
private const val WALLET_ABI_SESSION_PROCESSING_FAILED_MAX_CHARS = 1400
private const val WALLET_ABI_TRACE_DELIMITER = " | trace:"

 sealed interface WalletAbiProcessResult {
    data class Ok(
        val response: WalletAbiTxCreateResponse,
        val responseJson: String,
    ) : WalletAbiProcessResult

    data class AbiError(
        val response: WalletAbiTxCreateResponse,
        val responseJson: String,
        val error: WalletAbiErrorInfo,
    ) : WalletAbiProcessResult

    data class Failed(
        val message: String,
        val cause: Throwable? = null,
    ) : WalletAbiProcessResult
}

 class WalletAbiProcessor(
    private val json: Json,
    private val executionContextResolver: WalletAbiExecutionContextResolving,
    private val providerRunner: WalletAbiProviderRunning,
) {
    suspend fun process(
        session: GdkSession,
        requestJson: String,
    ): WalletAbiProcessResult {
        val request = decodeRequest(requestJson) ?: return WalletAbiProcessResult.Failed(
            message = "Wallet ABI request JSON is malformed",
        )

        val context = try {
            executionContextResolver.resolveDirect(
                session = session,
                requestNetwork = request.network,
            )
        } catch (error: WalletAbiExecutionContextException) {
            return WalletAbiProcessResult.Failed(
                message = error.message ?: "Wallet ABI context resolution failed",
                cause = error.cause ?: error,
            )
        }

        return processDecodedRequest(
            context = context,
            request = request,
            requestJson = requestJson,
        )
    }

    suspend fun process(
        context: WalletAbiExecutionContext,
        requestJson: String,
    ): WalletAbiProcessResult {
        val request = decodeRequest(requestJson) ?: return WalletAbiProcessResult.Failed(
            message = "Wallet ABI request JSON is malformed",
        )
        if (request.network != context.requestNetwork) {
            return WalletAbiProcessResult.Failed(
                message = "Wallet ABI request network mismatch: " +
                    "request=${request.network.serialValue()} context=${context.requestNetwork.serialValue()}",
            )
        }

        return processDecodedRequest(
            context = context,
            request = request,
            requestJson = requestJson,
        )
    }

    private fun decodeRequest(requestJson: String): WalletAbiTxCreateRequest? {
        return runCatching {
            json.decodeFromString<WalletAbiTxCreateRequest>(requestJson)
        }.getOrNull()
    }

    private suspend fun processDecodedRequest(
        context: WalletAbiExecutionContext,
        request: WalletAbiTxCreateRequest,
        requestJson: String,
    ): WalletAbiProcessResult {
        return try {
            val providerResult = providerRunner.run(
                context = context,
                request = request,
                requestJson = requestJson,
            )
            val sessionSafeResponse = sanitizeWalletAbiResponseForSession(
                response = providerResult.response,
            )
            val sessionSafeResponseJson = if (sessionSafeResponse === providerResult.response) {
                providerResult.responseJson
            } else {
                json.encodeToString(sessionSafeResponse)
            }

            when (sessionSafeResponse.status) {
                WalletAbiStatus.OK -> WalletAbiProcessResult.Ok(
                    response = sessionSafeResponse,
                    responseJson = sessionSafeResponseJson,
                )

                WalletAbiStatus.ERROR -> WalletAbiProcessResult.AbiError(
                    response = sessionSafeResponse,
                    responseJson = sessionSafeResponseJson,
                    error = sessionSafeResponse.error ?: WalletAbiErrorInfo(
                        code = "invalid_response",
                        message = "Wallet ABI returned status=error without error payload",
                    ),
                )
            }
        } catch (error: WalletAbiExecutionContextException) {
            WalletAbiProcessResult.Failed(
                message = error.message ?: "Wallet ABI context resolution failed",
                cause = error.cause ?: error,
            )
        } catch (error: Throwable) {
            val debugDetails = buildWalletAbiExceptionDetails(error)
            val sessionSafeMessage = debugDetails
                .removeWalletAbiTraceSection()
                .compactForSessionResponse(WALLET_ABI_SESSION_PROCESSING_FAILED_MAX_CHARS)
            WalletAbiProcessResult.Failed(
                message = sessionSafeMessage,
                cause = error,
            )
        }
    }

    private fun sanitizeWalletAbiResponseForSession(
        response: WalletAbiTxCreateResponse,
    ): WalletAbiTxCreateResponse {
        val error = response.error ?: return response
        val sanitized = sanitizeWalletAbiErrorForSessionResponse(error)
        return if (sanitized == error) {
            response
        } else {
            response.copy(error = sanitized)
        }
    }
}

 fun buildWalletAbiExceptionDetails(error: Throwable): String {
    val chain = generateSequence(error) { it.cause }
        .take(5)
        .joinToString(separator = " <- ") { throwable ->
            val message = throwable.message ?: "no-message"
            "${throwable::class.simpleName ?: "Throwable"}($message)"
        }

    val stack = runCatching {
        error.stackTraceToString()
            .lineSequence()
            .drop(1)
            .filter { it.isNotBlank() }
            .take(8)
            .joinToString(separator = " | ") { it.trim() }
    }.getOrNull().orEmpty()

    return if (stack.isBlank()) {
        "Wallet ABI processing failed: $chain"
    } else {
        "Wallet ABI processing failed: $chain ; stack=$stack"
    }
}

 fun sanitizeWalletAbiErrorForSessionResponse(error: WalletAbiErrorInfo): WalletAbiErrorInfo {
    val traceRemovedMessage = error.message.removeWalletAbiTraceSection()
    val compactMessage = traceRemovedMessage.compactForSessionResponse(WALLET_ABI_SESSION_ERROR_MESSAGE_MAX_CHARS)
    val safeMessage = if (compactMessage != traceRemovedMessage || traceRemovedMessage != error.message) {
        "$compactMessage [truncated]"
            .compactForSessionResponse(WALLET_ABI_SESSION_ERROR_MESSAGE_MAX_CHARS)
    } else {
        compactMessage
    }

    return error.copy(
        message = safeMessage,
        details = null,
    )
}

 fun String.removeWalletAbiTraceSection(): String {
    val markerIndex = indexOf(WALLET_ABI_TRACE_DELIMITER)
    return if (markerIndex >= 0) {
        substring(0, markerIndex)
    } else {
        this
    }.trim()
}

private fun String.compactForSessionResponse(maxChars: Int): String {
    if (length <= maxChars) {
        return trim()
    }

    return take(maxChars)
        .trimEnd()
        .trim()
}
