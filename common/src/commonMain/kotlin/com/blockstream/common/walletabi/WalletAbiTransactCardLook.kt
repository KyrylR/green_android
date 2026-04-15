package com.blockstream.common.walletabi

import kotlinx.serialization.Serializable

@Serializable
data class WalletAbiTransactCardLook(
    val title: String,
    val subtitle: String? = null,
    val body: String,
    val statusLabel: String,
)

internal fun WalletAbiSessionUiState.toTransactCardLook(): WalletAbiTransactCardLook? {
    overlay?.let { currentOverlay ->
        return when (currentOverlay) {
            is WalletAbiOverlayLook.SessionProposalApproval -> WalletAbiTransactCardLook(
                title = "Connection request",
                subtitle = currentOverlay.origin,
                body = currentOverlay.requestedMethods.joinToString(),
                statusLabel = "Pending",
            )

            is WalletAbiOverlayLook.ConnectionEstablished -> WalletAbiTransactCardLook(
                title = "Connected dApp",
                subtitle = currentOverlay.origin,
                body = currentOverlay.network?.let { "Network: $it" } ?: "Wallet ABI session connected",
                statusLabel = "Connected",
            )

            is WalletAbiOverlayLook.GetterApproval -> WalletAbiTransactCardLook(
                title = "Approval required",
                subtitle = currentOverlay.origin,
                body = currentOverlay.method,
                statusLabel = "Pending",
            )

            is WalletAbiOverlayLook.TransactionApproval -> WalletAbiTransactCardLook(
                title = "Smart contract request",
                subtitle = currentOverlay.review.origin,
                body = currentOverlay.review.transactCardBody(),
                statusLabel = if (currentOverlay.review.broadcast) {
                    "Broadcast"
                } else {
                    "Sign"
                },
            )

            is WalletAbiOverlayLook.Error -> WalletAbiTransactCardLook(
                title = "Wallet ABI error",
                body = currentOverlay.message,
                statusLabel = "Error",
            )
        }
    }

    val connection = activeConnection ?: return null
    val gettersSummary = connection.approvedGetters.takeIf { it.isNotEmpty() }?.size?.let { count ->
        if (count == 1) {
            "1 auto-approved getter"
        } else {
            "$count auto-approved getters"
        }
    }

    return WalletAbiTransactCardLook(
        title = "Connected dApp",
        subtitle = connection.origin,
        body = listOfNotNull(
            connection.network?.let { "Network: $it" },
            gettersSummary,
        ).ifEmpty {
            listOf("Wallet ABI session connected")
        }.joinToString(" • "),
        statusLabel = when (connection.state) {
            WalletAbiSessionState.CONNECTED -> "Connected"
            WalletAbiSessionState.CLOSED -> "Closed"
            WalletAbiSessionState.EXPIRED -> "Expired"
            WalletAbiSessionState.ERROR -> "Error"
            null -> "Connected"
        },
    )
}

private fun WalletAbiTransactionReviewLook.transactCardBody(): String {
    val summary = walletAbiCompactImpactSummary(this)
    return if (summary == null) {
        statusMessage
    } else {
        buildList {
            add(summary.sentAway)
            add(summary.sentBackToWallet)
            if (summary.additionalAssetCount > 0) {
                add("+${summary.additionalAssetCount} assets")
            }
        }.joinToString(" • ")
    }
}
