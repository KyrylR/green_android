package com.blockstream.common.walletabi

import kotlin.test.Test
import kotlin.test.assertEquals

class WalletAbiTransactCardLookTest {
    @Test
    fun sessionProposalMapsToPendingConnectionCard() {
        val look = WalletAbiSessionUiState(
            overlay = WalletAbiOverlayLook.SessionProposalApproval(
                origin = "Example dApp",
                network = "testnet-liquid",
                requestedMethods = listOf(
                    WALLET_ABI_METHOD_GET_SIGNER_RECEIVE_ADDRESS,
                    WALLET_ABI_METHOD_PROCESS_REQUEST,
                ),
                autoApprovedGetters = emptySet(),
                warning = null,
                willReplaceExistingConnection = false,
            ),
        ).toTransactCardLook()

        assertEquals("Connection request", look?.title)
        assertEquals("Example dApp", look?.subtitle)
        assertEquals("Pending", look?.statusLabel)
        assertEquals(
            "$WALLET_ABI_METHOD_GET_SIGNER_RECEIVE_ADDRESS, $WALLET_ABI_METHOD_PROCESS_REQUEST",
            look?.body,
        )
    }

    @Test
    fun transactionApprovalMapsToCompactImpactSummaryCard() {
        val look = WalletAbiSessionUiState(
            overlay = WalletAbiOverlayLook.TransactionApproval(
                review = WalletAbiTransactionReviewLook(
                    origin = "lending-contract.blockstream.com",
                    requestId = "request-1",
                    network = "testnet-liquid",
                    broadcast = true,
                    resolutionState = WalletAbiResolutionState.NOT_REQUIRED,
                    accountOptions = emptyList(),
                    selectedAccountId = "account-1",
                    selectedAccountName = "Account 1",
                    inputs = emptyList(),
                    outputs = emptyList(),
                    impactAssets = listOf(
                        WalletAbiImpactAssetLook(
                            assetId = "asset-1",
                            assetLabel = "L-BTC",
                            sentAway = "1 L-BTC",
                            sentBackToWallet = "0.1 L-BTC",
                        ),
                        WalletAbiImpactAssetLook(
                            assetId = "asset-2",
                            assetLabel = "USDT",
                            sentAway = "50,000 USDT",
                            sentBackToWallet = "0 USDT",
                        ),
                    ),
                    exactImpactState = WalletAbiExactImpactState.READY,
                    inputSourceSummary = WalletAbiInputSourceSummaryLook(
                        walletSelectedInputCount = 1,
                        explicitExternalInputCount = 0,
                        otherInputCount = 0,
                    ),
                    statusMessage = "Ready to sign and broadcast",
                    warnings = emptyList(),
                ),
            ),
        ).toTransactCardLook()

        assertEquals("Smart contract request", look?.title)
        assertEquals("lending-contract.blockstream.com", look?.subtitle)
        assertEquals("Broadcast", look?.statusLabel)
        assertEquals("1 L-BTC • 0.1 L-BTC • +1 assets", look?.body)
    }
}
