package com.blockstream.common.walletabi

import com.blockstream.common.gdk.data.Account
import com.blockstream.common.gdk.data.AccountType
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class WalletAbiProviderRunnerTest {
    private val json = Json {
        ignoreUnknownKeys = true
    }

    @Test
    fun parseWalletAbiJsonRpcDispatchReadsMethodAndParams() {
        val dispatch = parseWalletAbiJsonRpcDispatch(
            json = json,
            requestEnvelopeJson = """
                {
                  "id": 1,
                  "jsonrpc": "2.0",
                  "method": "wallet_abi_process_request",
                  "params": {
                    "request_id": "req-1"
                  }
                }
            """.trimIndent(),
        )

        assertEquals(
            "wallet_abi_process_request",
            dispatch.first,
        )
        assertEquals(
            """{"request_id":"req-1"}""",
            dispatch.second,
        )
    }

    @Test
    fun parseWalletAbiJsonRpcDispatchDefaultsParamsToEmptyObject() {
        val dispatch = parseWalletAbiJsonRpcDispatch(
            json = json,
            requestEnvelopeJson = """
                {
                  "id": 1,
                  "jsonrpc": "2.0",
                  "method": "wallet_getCapabilities"
                }
            """.trimIndent(),
        )

        assertEquals("wallet_getCapabilities", dispatch.first)
        assertEquals("{}", dispatch.second)
    }

    @Test
    fun parseWalletAbiJsonRpcDispatchRequiresMethod() {
        val error = assertFailsWith<WalletAbiExecutionContextException> {
            parseWalletAbiJsonRpcDispatch(
                json = json,
                requestEnvelopeJson = """{"jsonrpc":"2.0","params":{}}""",
            )
        }

        assertEquals(
            "Wallet ABI JSON-RPC request envelope is missing method",
            error.message,
        )
    }

    @Test
    fun walletAbiAccountIndexPrefersDerivationPathChild() {
        val account = account(
            pointer = 48,
            type = AccountType.BIP84_SEGWIT,
            derivationPath = listOf(2147483732L, 2147485424L, 2147483657L),
        )

        assertEquals(9u, account.walletAbiAccountIndex())
    }

    @Test
    fun walletAbiAccountIndexFallsBackToSinglesigPointerGroup() {
        val account = account(
            pointer = 48,
            type = AccountType.BIP84_SEGWIT,
        )

        assertEquals(3u, account.walletAbiAccountIndex())
    }

    @Test
    fun walletAbiAccountIndexFallsBackToMultisigPointer() {
        val account = account(
            pointer = 7,
            type = AccountType.STANDARD,
        )

        assertEquals(7u, account.walletAbiAccountIndex())
    }

    private fun account(
        pointer: Long,
        type: AccountType,
        derivationPath: List<Long>? = null,
    ) = Account(
        gdkName = "",
        pointer = pointer,
        type = type,
        derivationPath = derivationPath,
    )
}
