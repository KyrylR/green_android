package com.blockstream.common.walletabi

import com.blockstream.common.extensions.previewTransaction
import com.blockstream.common.looks.transaction.Completed
import com.blockstream.common.looks.transaction.TransactionLook
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TransactionLookWalletAbiTest {
    @Test
    fun transactionLookWithWalletAbiRecordMapsMatchingSummary() {
        val review = WalletAbiTransactionReviewLook(
            origin = "borrow.blockstream.com",
            requestId = "request-1",
            network = "Testnet Liquid",
            broadcast = true,
            resolutionState = WalletAbiResolutionState.NOT_REQUIRED,
            accountOptions = emptyList(),
            selectedAccountId = "account-1",
            selectedAccountName = "Savings",
            inputs = emptyList(),
            outputs = emptyList(),
            impactAssets = listOf(
                WalletAbiImpactAssetLook(
                    assetId = "asset-1",
                    assetLabel = "L-BTC",
                    sentAway = "-1.00000000 L-BTC",
                    sentBackToWallet = "+0.25000000 L-BTC",
                ),
            ),
            exactImpactState = WalletAbiExactImpactState.READY,
            inputSourceSummary = WalletAbiInputSourceSummaryLook(1, 0, 0),
            statusMessage = "Ready",
            warnings = emptyList(),
        )
        val record = WalletAbiTransactionRecord(
            walletId = "wallet-1",
            txHash = "tx-1",
            origin = "borrow.blockstream.com",
            status = WalletAbiTransactionRecordStatus.BROADCAST,
            review = review,
            updatedAtEpochMilliseconds = 1L,
        )
        val look = TransactionLook(
            status = Completed(),
            transaction = previewTransaction().copy(txHash = "tx-1"),
            assets = listOf("-1.00000000 L-BTC"),
        )

        val updated = TransactionLook.withWalletAbiRecord(look, record)

        assertEquals("borrow.blockstream.com", updated.walletAbiSummary?.origin)
        assertEquals("Broadcast", updated.walletAbiSummary?.statusLabel)
        assertEquals("-1.00000000 L-BTC", updated.walletAbiSummary?.sentAway)
    }

    @Test
    fun transactionLookWithWalletAbiRecordIgnoresMissingRecord() {
        val look = TransactionLook(
            status = Completed(),
            transaction = previewTransaction().copy(txHash = "tx-2"),
            assets = listOf("-1.00000000 L-BTC"),
        )

        val updated = TransactionLook.withWalletAbiRecord(look, null)

        assertNull(updated.walletAbiSummary)
    }
}
