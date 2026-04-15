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
import com.blockstream.common.data.ScanResult
import com.blockstream.common.events.Events
import com.blockstream.common.models.overview.TransactViewModelAbstract
import com.blockstream.common.navigation.NavigateDestinations
import com.blockstream.common.walletabi.WalletAbiTransactCardLook
import com.blockstream.compose.components.GreenCard
import com.blockstream.compose.components.GreenTransaction
import com.blockstream.compose.components.ListHeader
import com.blockstream.compose.components.TransactionActionButtons
import com.blockstream.compose.components.WalletBalance
import com.blockstream.compose.theme.bodyMedium
import com.blockstream.compose.theme.labelLarge
import com.blockstream.compose.theme.titleSmall
import com.blockstream.compose.theme.whiteMedium
import com.blockstream.compose.utils.SetupScreen
import com.blockstream.ui.components.GreenColumn
import com.blockstream.ui.components.GreenRow
import com.blockstream.ui.navigation.LocalInnerPadding
import com.blockstream.ui.navigation.getResult
import com.blockstream.ui.utils.bottom
import com.blockstream.ui.utils.plus
import org.jetbrains.compose.resources.stringResource

@Composable
fun TransactScreen(viewModel: TransactViewModelAbstract) {
    NavigateDestinations.WalletAbiScan.getResult<ScanResult> {
        viewModel.handleWalletAbiScan(it.result)
    }

    SetupScreen(viewModel = viewModel, withPadding = false, withBottomInsets = false) {

        val transactions by viewModel.transactions.collectAsStateWithLifecycle()
        val walletAbiCard by viewModel.walletAbiCard.collectAsStateWithLifecycle()
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
                    showBuyButton = true,
                    sendEnabled = !isMultisigWatchOnly,
                    onBuy = { viewModel.buy() },
                    onSend = { viewModel.postEvent(NavigateDestinations.SendAddress(greenWallet = viewModel.greenWallet)) },
                    onReceive = {
                        viewModel.postEvent(
                            NavigateDestinations.ReceiveChooseAsset(
                                greenWallet = viewModel.greenWallet
                            )
                        )
                    },
                    onScan = {
                        viewModel.postEvent(
                            NavigateDestinations.WalletAbiScan(
                                greenWallet = viewModel.greenWallet,
                            )
                        )
                    },
                    modifier = Modifier.padding(top = 16.dp)
                )
            }

            walletAbiCard?.also { card ->
                item(key = "WalletAbiCard") {
                    WalletAbiPendingRequestCard(
                        card = card,
                        modifier = Modifier.padding(top = 16.dp),
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
                            .fillMaxWidth()
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
                            .padding(horizontal = 16.dp)
                    )
                }
            }

            items(items = transactions.data() ?: listOf(), key = { tx ->
                tx.transaction.account.id.hashCode() + tx.transaction.txHash.hashCode() + tx.transaction.txType.gdkType.hashCode()
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
) {
    GreenCard(
        modifier = modifier,
        padding = 16,
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
                    androidx.compose.material3.Text(
                        text = card.title,
                        style = titleSmall,
                    )
                    card.subtitle?.also { subtitle ->
                        androidx.compose.material3.Text(
                            text = subtitle,
                            style = bodyMedium,
                            color = whiteMedium,
                        )
                    }
                }

                androidx.compose.material3.Text(
                    text = card.statusLabel,
                    style = labelLarge,
                )
            }

            androidx.compose.material3.Text(
                text = card.body,
                style = bodyMedium,
                color = whiteMedium,
            )
        }
    }
}
