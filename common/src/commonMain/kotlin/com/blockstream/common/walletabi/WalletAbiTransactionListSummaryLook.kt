package com.blockstream.common.walletabi

import kotlinx.serialization.Serializable

@Serializable
data class WalletAbiTransactionListSummaryLook(
    val origin: String,
    val statusLabel: String,
    val sentAway: String? = null,
    val sentBackToWallet: String? = null,
    val additionalAssetCount: Int = 0,
)

 fun WalletAbiTransactionRecord.toListSummaryLook(): WalletAbiTransactionListSummaryLook {
    val impactSummary = walletAbiCompactImpactSummary(review)
    return WalletAbiTransactionListSummaryLook(
        origin = origin,
        statusLabel = status.presentationLabel(),
        sentAway = impactSummary?.sentAway,
        sentBackToWallet = impactSummary?.sentBackToWallet,
        additionalAssetCount = impactSummary?.additionalAssetCount ?: 0,
    )
}

 fun WalletAbiTransactionRecordStatus.presentationLabel(): String {
    return when (this) {
        WalletAbiTransactionRecordStatus.APPROVED -> "Signed"
        WalletAbiTransactionRecordStatus.BROADCAST -> "Broadcast"
    }
}
