package com.blockstream.common.walletabi

import com.blockstream.common.gdk.GdkSession
import com.blockstream.common.gdk.data.Account
import com.blockstream.common.walletabi.transport.WalletAbiTxCreateRequest

internal data class WalletAbiImpactPreviewRequest(
    val session: GdkSession? = null,
    val accounts: List<Account>,
    val selectedAccount: Account,
    val txRequest: WalletAbiTxCreateRequest,
)

internal data class WalletAbiImpactPreviewResult(
    val state: WalletAbiExactImpactState,
    val exactFeesByAssetId: Map<String, String> = emptyMap(),
    val exactNetDeltasByAssetId: Map<String, String> = emptyMap(),
    val statusMessage: String? = null,
)

internal interface WalletAbiImpactPreviewing {
    suspend fun preview(request: WalletAbiImpactPreviewRequest): WalletAbiImpactPreviewResult
}

internal class NoopWalletAbiImpactPreviewer : WalletAbiImpactPreviewing {
    override suspend fun preview(request: WalletAbiImpactPreviewRequest): WalletAbiImpactPreviewResult {
        return WalletAbiImpactPreviewResult(
            state = WalletAbiExactImpactState.UNAVAILABLE,
        )
    }
}
