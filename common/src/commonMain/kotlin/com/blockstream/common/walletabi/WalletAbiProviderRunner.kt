package com.blockstream.common.walletabi

import com.blockstream.common.gdk.data.Account
import com.blockstream.common.walletabi.transport.WalletAbiTxCreateRequest
import com.blockstream.common.walletabi.transport.WalletAbiTxCreateResponse
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import lwk.Mnemonic
import lwk.Signer
import lwk.SignerMetaLink
import lwk.WalletAbiProvider
import lwk.WalletAbiSignerContext
import lwk.WalletBroadcasterLink
import lwk.WalletOutputAllocatorLink
import lwk.WalletPrevoutResolverLink
import lwk.WalletReceiveAddressProviderLink
import lwk.WalletRuntimeDepsLink
import lwk.WalletSessionFactoryLink

private const val WALLET_ABI_PROCESS_REQUEST_METHOD = "wallet_abi_process_request"
private const val WALLET_ABI_HARDENED_BIT_LONG = 0x8000_0000L

internal data class WalletAbiProviderRunResult(
    val response: WalletAbiTxCreateResponse,
    val responseJson: String,
)

internal data class WalletAbiProviderJsonRpcRunResult(
    val resultJson: String,
)

internal interface WalletAbiProviderRunning {
    suspend fun run(
        context: WalletAbiExecutionContext,
        request: WalletAbiTxCreateRequest,
        requestJson: String,
    ): WalletAbiProviderRunResult

    suspend fun runJsonRpcRequest(
        context: WalletAbiExecutionContext,
        requestEnvelopeJson: String,
    ): WalletAbiProviderJsonRpcRunResult
}

internal class WalletAbiProviderRunner(
    private val json: Json,
    private val esploraHttpClient: WalletAbiEsploraHttpClient,
) : WalletAbiProviderRunning {
    override suspend fun run(
        context: WalletAbiExecutionContext,
        request: WalletAbiTxCreateRequest,
        requestJson: String,
    ): WalletAbiProviderRunResult {
        return withProvider(context) { provider ->
            val responseJson = provider.dispatchJson(
                method = WALLET_ABI_PROCESS_REQUEST_METHOD,
                paramsJson = requestJson,
            )
            val response = json.decodeFromString<WalletAbiTxCreateResponse>(responseJson)
            WalletAbiProviderRunResult(
                response = response,
                responseJson = responseJson,
            )
        }
    }

    override suspend fun runJsonRpcRequest(
        context: WalletAbiExecutionContext,
        requestEnvelopeJson: String,
    ): WalletAbiProviderJsonRpcRunResult {
        return withProvider(context) { provider ->
            val (method, paramsJson) = parseWalletAbiJsonRpcDispatch(
                json = json,
                requestEnvelopeJson = requestEnvelopeJson,
            )
            WalletAbiProviderJsonRpcRunResult(
                resultJson = provider.dispatchJson(
                    method = method,
                    paramsJson = paramsJson,
                ),
            )
        }
    }

    private suspend fun <T> withProvider(
        context: WalletAbiExecutionContext,
        block: (WalletAbiProvider) -> T,
    ): T {
        var mnemonic: Mnemonic? = null
        var signer: Signer? = null
        var signerLink: SignerMetaLink? = null
        var sessionFactoryLink: WalletSessionFactoryLink? = null
        var outputAllocatorLink: WalletOutputAllocatorLink? = null
        var prevoutResolverLink: WalletPrevoutResolverLink? = null
        var broadcasterLink: WalletBroadcasterLink? = null
        var receiveAddressProviderLink: WalletReceiveAddressProviderLink? = null
        var walletRuntimeDepsLink: WalletRuntimeDepsLink? = null
        var provider: WalletAbiProvider? = null

        try {
            val mnemonicString = context.session.getCredentials().mnemonic
                ?.takeIf { it.isNotBlank() }
                ?: throw WalletAbiExecutionContextException(
                    "Wallet ABI software signer requires mnemonic credentials",
                )

            val currentMnemonic = Mnemonic(mnemonicString)
            mnemonic = currentMnemonic

            val currentSigner = Signer(currentMnemonic, context.lwkNetwork)
            signer = currentSigner

            signerLink = SignerMetaLink.fromSoftwareSigner(
                signer = currentSigner,
                context = WalletAbiSignerContext(
                    network = context.lwkNetwork,
                    accountIndex = context.primaryAccount.walletAbiAccountIndex(),
                ),
            )

            val snapshotSupport = WalletAbiWalletSnapshotSupport(
                context = context,
                esploraHttpClient = esploraHttpClient,
            )

            sessionFactoryLink = WalletSessionFactoryLink(snapshotSupport)
            outputAllocatorLink = WalletOutputAllocatorLink(snapshotSupport)
            prevoutResolverLink = WalletPrevoutResolverLink(snapshotSupport)
            broadcasterLink = WalletBroadcasterLink(snapshotSupport)
            receiveAddressProviderLink = WalletReceiveAddressProviderLink(snapshotSupport)

            walletRuntimeDepsLink = WalletRuntimeDepsLink(
                sessionFactory = sessionFactoryLink,
                outputAllocator = outputAllocatorLink,
                prevoutResolver = prevoutResolverLink,
                broadcaster = broadcasterLink,
                receiveAddressProvider = receiveAddressProviderLink,
            )

            provider = WalletAbiProvider(
                signer = signerLink,
                wallet = walletRuntimeDepsLink,
            )

            return block(provider)
        } finally {
            provider?.close()
            walletRuntimeDepsLink?.close()
            receiveAddressProviderLink?.close()
            broadcasterLink?.close()
            prevoutResolverLink?.close()
            outputAllocatorLink?.close()
            sessionFactoryLink?.close()
            signerLink?.close()
            signer?.close()
            mnemonic?.close()
        }
    }
}

internal fun parseWalletAbiJsonRpcDispatch(
    json: Json,
    requestEnvelopeJson: String,
): Pair<String, String> {
    val envelope = try {
        json.parseToJsonElement(requestEnvelopeJson).jsonObject
    } catch (error: Exception) {
        throw WalletAbiExecutionContextException(
            message = "Wallet ABI JSON-RPC request envelope is invalid: ${error.message}",
            cause = error,
        )
    }

    val method = envelope["method"]
        ?.jsonPrimitive
        ?.contentOrNull
        ?.takeIf { it.isNotBlank() }
        ?: throw WalletAbiExecutionContextException(
            "Wallet ABI JSON-RPC request envelope is missing method",
        )

    return method to (envelope["params"]?.toString() ?: "{}")
}

internal fun Account.walletAbiAccountIndex(): UInt {
    derivationPath
        ?.lastOrNull()
        ?.let { child ->
            val normalized = if (child >= WALLET_ABI_HARDENED_BIT_LONG) {
                child - WALLET_ABI_HARDENED_BIT_LONG
            } else {
                child
            }
            if (normalized in 0..UInt.MAX_VALUE.toLong()) {
                return normalized.toUInt()
            }
        }

    val fallbackIndex = if (type.isSinglesig()) {
        pointer / 16L
    } else {
        pointer
    }.coerceAtLeast(0L)

    return fallbackIndex
        .coerceAtMost(UInt.MAX_VALUE.toLong())
        .toUInt()
}
