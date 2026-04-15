package com.blockstream.common.walletabi

import kotlinx.serialization.Serializable

@Serializable
data class WalletAbiTransactionDetailsAssetLook(
    val assetLabel: String,
    val sentAway: String,
    val sentBackToWallet: String,
    val otherOutputs: String? = null,
    val exactFee: String? = null,
    val exactNetWalletDelta: String? = null,
)

@Serializable
data class WalletAbiTransactionDetailsOutputLook(
    val label: String,
    val amount: String,
    val detail: String,
)

@Serializable
data class WalletAbiTransactionDetailsLook(
    val origin: String,
    val network: String,
    val accountName: String,
    val statusLabel: String,
    val statusMessage: String,
    val assets: List<WalletAbiTransactionDetailsAssetLook>,
    val outputs: List<WalletAbiTransactionDetailsOutputLook>,
    val warnings: List<String>,
)

internal fun WalletAbiTransactionRecord.toDetailsLook(): WalletAbiTransactionDetailsLook {
    return WalletAbiTransactionDetailsLook(
        origin = origin,
        network = review.network,
        accountName = review.selectedAccountName,
        statusLabel = status.presentationLabel(),
        statusMessage = review.statusMessage,
        assets = review.impactAssets.map { asset ->
            WalletAbiTransactionDetailsAssetLook(
                assetLabel = asset.assetLabel,
                sentAway = asset.sentAway,
                sentBackToWallet = asset.sentBackToWallet,
                otherOutputs = asset.otherOutputs,
                exactFee = asset.exactFee,
                exactNetWalletDelta = asset.exactNetWalletDelta,
            )
        },
        outputs = review.outputs.map { output ->
            WalletAbiTransactionDetailsOutputLook(
                label = output.label,
                amount = output.amount,
                detail = output.detail,
            )
        },
        warnings = review.warnings,
    )
}
