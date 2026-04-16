package com.blockstream.compose.screens.overview

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import blockstream_green.common.generated.resources.Res
import blockstream_green.common.generated.resources.id_latest_transactions
import blockstream_green.common.generated.resources.id_your_transactions_will_be_shown
import com.blockstream.compose.GreenPreview
import com.blockstream.common.models.walletabi.WalletAbiSuccessLook
import com.blockstream.compose.components.GreenCard
import com.blockstream.compose.components.GreenColumn
import com.blockstream.compose.components.GreenRow
import com.blockstream.compose.components.GreenTransaction
import com.blockstream.compose.components.ListHeader
import com.blockstream.compose.components.TransactionActionButtons
import com.blockstream.compose.components.WalletBalance
import com.blockstream.compose.events.Events
import com.blockstream.compose.models.overview.TransactViewModelAbstract
import com.blockstream.compose.models.overview.TransactViewModelPreview
import com.blockstream.compose.navigation.LocalInnerPadding
import com.blockstream.compose.navigation.NavigateDestinations
import com.blockstream.compose.navigation.getResult
import com.blockstream.compose.theme.bodyMedium
import com.blockstream.compose.theme.labelLarge
import com.blockstream.compose.theme.titleSmall
import com.blockstream.compose.theme.whiteMedium
import com.blockstream.compose.utils.SetupScreen
import com.blockstream.compose.utils.SwapUtils
import com.blockstream.compose.utils.bottom
import com.blockstream.compose.utils.plus
import com.blockstream.common.walletabi.WalletAbiTransactCardLook
import com.blockstream.data.data.GreenWallet
import com.blockstream.data.data.ScanResult
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.ui.tooling.preview.Preview

@Composable
fun TransactScreen(viewModel: TransactViewModelAbstract) {
    NavigateDestinations.WalletAbiScan.getResult<ScanResult> {
        viewModel.handleWalletAbiScan(it.result)
    }
    NavigateDestinations.WalletAbiRequest.getResult<WalletAbiSuccessLook> {
        viewModel.postEvent(
            NavigateDestinations.WalletAbiSuccess(
                greenWallet = viewModel.greenWallet,
                success = it,
            )
        )
    }

    NavigateDestinations.Login.getResult<GreenWallet> {
        SwapUtils.navigateToDeviceScanOrJadeQr(viewModel)
    }

    SetupScreen(viewModel = viewModel, withPadding = false, withBottomInsets = false) {
        val isMainnet = viewModel.greenWallet.isMainnet
        val transactions by viewModel.transactions.collectAsStateWithLifecycle()
        val walletAbiCard by viewModel.walletAbiCard.collectAsStateWithLifecycle()
        val hasPendingWalletAbiTransactionRequest by viewModel.hasPendingWalletAbiTransactionRequest.collectAsStateWithLifecycle()
        val isMultisigWatchOnly by viewModel.isMultisigWatchOnly.collectAsStateWithLifecycle()
        val innerPadding = LocalInnerPadding.current
        val listState = rememberLazyListState()

        LazyColumn(
            state = listState,
            contentPadding = innerPadding.bottom()
                .plus(PaddingValues(horizontal = 16.dp))
                .plus(PaddingValues(bottom = 80.dp + 16.dp)),
        ) {
            item(key = "WalletBalance") {
                WalletBalance(viewModel = viewModel)
            }

            item(key = "ButtonsRow") {
                TransactionActionButtons(
                    showBuyButton = isMainnet,
                    showSwapButton = false,
                    isSendEnabled = !isMultisigWatchOnly,
                    onBuy = viewModel::onBuy,
                    onSend = viewModel::onSend,
                    onReceive = viewModel::onReceive,
                    onSwap = viewModel::onSwap,
                    onScan = {
                        viewModel.postEvent(
                            NavigateDestinations.WalletAbiScan(
                                greenWallet = viewModel.greenWallet,
                            )
                        )
                    },
                    modifier = Modifier.padding(top = 16.dp),
                )
            }

            walletAbiCard?.also { card ->
                item(key = "WalletAbiCard") {
                    WalletAbiPendingRequestCard(
                        card = card,
                        modifier = Modifier.padding(top = 16.dp),
                        onClick = if (hasPendingWalletAbiTransactionRequest) {
                            {
                                viewModel.postEvent(
                                    NavigateDestinations.WalletAbiRequest(
                                        greenWallet = viewModel.greenWallet,
                                    )
                                )
                            }
                        } else {
                            {
                                viewModel.postEvent(
                                    NavigateDestinations.WalletAbiConnection(
                                        greenWallet = viewModel.greenWallet,
                                    )
                                )
                            }
                        },
                    )
                }
            }

            item(key = "TransactionsHeader") {
                ListHeader(title = stringResource(Res.string.id_latest_transactions))

                if (transactions.isLoading()) {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .padding(all = 32.dp)
                            .height(1.dp)
                            .fillMaxWidth(),
                    )
                } else if (transactions.isEmpty()) {
                    Text(
                        text = stringResource(Res.string.id_your_transactions_will_be_shown),
                        style = bodyMedium,
                        textAlign = TextAlign.Center,
                        fontStyle = FontStyle.Italic,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 24.dp)
                            .padding(horizontal = 16.dp),
                    )
                }
            }

            items(items = transactions.data() ?: listOf(), key = { tx ->
                tx.transaction.uniqueId
            }) { item ->
                GreenTransaction(modifier = Modifier.padding(bottom = 6.dp), transactionLook = item) {
                    viewModel.postEvent(Events.Transaction(transaction = it.transaction))
                }
            }
        }
    }
}

@Composable
private fun WalletAbiPendingRequestCard(
    card: WalletAbiTransactCardLook,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    GreenCard(
        modifier = modifier,
        padding = 16,
        onClick = onClick,
    ) {
        GreenColumn(
            padding = 0,
            space = 8,
        ) {
            GreenRow(
                padding = 0,
                modifier = Modifier.fillMaxWidth(),
            ) {
                GreenColumn(
                    padding = 0,
                    space = 4,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        text = card.title,
                        style = titleSmall,
                    )
                    card.subtitle?.also { subtitle ->
                        Text(
                            text = subtitle,
                            style = bodyMedium,
                            color = whiteMedium,
                        )
                    }
                }

                Text(
                    text = card.statusLabel,
                    style = labelLarge,
                )
            }

            Text(
                text = card.body,
                style = bodyMedium,
                color = whiteMedium,
            )
        }
    }
}

@Preview
@Composable
fun PreviewTransactScreen() {
    GreenPreview {
        TransactScreen(viewModel = TransactViewModelPreview.create())
    }
}
