package com.blockstream.common.walletabi

internal sealed interface WalletAbiPresentedResult {
    data class Success(
        val message: String,
    ) : WalletAbiPresentedResult

    data class Error(
        val message: String,
        val throwable: Throwable,
    ) : WalletAbiPresentedResult
}

internal class WalletAbiResultPresenter {
    fun present(result: WalletAbiProcessResult): WalletAbiPresentedResult {
        return when (result) {
            is WalletAbiProcessResult.Ok -> {
                val txid = result.response.transaction?.txid
                WalletAbiPresentedResult.Success(
                    message = txid?.let { "Wallet ABI request processed: $it" }
                        ?: "Wallet ABI request processed",
                )
            }

            is WalletAbiProcessResult.AbiError -> WalletAbiPresentedResult.Error(
                message = result.error.message,
                throwable = Exception(result.error.message),
            )

            is WalletAbiProcessResult.Failed -> WalletAbiPresentedResult.Error(
                message = result.message,
                throwable = Exception(result.message, result.cause),
            )
        }
    }
}
