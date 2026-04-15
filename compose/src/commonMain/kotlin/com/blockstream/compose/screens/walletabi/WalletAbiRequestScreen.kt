package com.blockstream.compose.screens.walletabi

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.blockstream.common.models.walletabi.WalletAbiRequestViewModelAbstract
import com.blockstream.common.utils.StringHolder
import com.blockstream.common.models.walletabi.WalletAbiRequestImpactLook
import com.blockstream.common.models.walletabi.WalletAbiRequestLook
import com.blockstream.common.models.walletabi.WalletAbiRequestOutputLook
import com.blockstream.compose.components.GreenButton
import com.blockstream.compose.components.GreenButtonColor
import com.blockstream.compose.components.GreenButtonSize
import com.blockstream.compose.components.GreenButtonType
import com.blockstream.compose.components.GreenCard
import com.blockstream.compose.components.SlideToUnlock
import com.blockstream.compose.theme.bodyLarge
import com.blockstream.compose.theme.bodyMedium
import com.blockstream.compose.theme.bodySmall
import com.blockstream.compose.theme.headlineSmall
import com.blockstream.compose.theme.labelLarge
import com.blockstream.compose.theme.labelMedium
import com.blockstream.compose.theme.titleMedium
import com.blockstream.compose.theme.titleSmall
import com.blockstream.compose.theme.whiteMedium
import com.blockstream.compose.utils.SetupScreen
import com.blockstream.ui.components.GreenColumn
import com.blockstream.ui.components.GreenRow

@Composable
fun WalletAbiRequestScreen(
    viewModel: WalletAbiRequestViewModelAbstract,
) {
    SetupScreen(
        viewModel = viewModel,
        withPadding = false,
        withBottomInsets = false,
    ) { innerPadding ->
        val review by viewModel.review.collectAsStateWithLifecycle()
        val isResolving by viewModel.isResolving.collectAsStateWithLifecycle()
        val isApproving by viewModel.isApproving.collectAsStateWithLifecycle()

        GreenColumn(
            padding = 0,
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .padding(top = 8.dp)
                .padding(bottom = innerPadding.calculateBottomPadding() + 16.dp),
        ) {
            val currentReview = review
            if (currentReview == null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 48.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    GreenColumn(
                        padding = 0,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text = "No pending Wallet ABI request",
                            style = headlineSmall,
                            textAlign = TextAlign.Center,
                        )
                        Text(
                            text = "Open a pending transaction request from the Transact tab to review it here.",
                            style = bodyMedium,
                            color = whiteMedium,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            } else {
                GreenColumn(
                    padding = 0,
                    space = 16,
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState()),
                ) {
                    WalletAbiRequestHeader(currentReview)

                    if (currentReview.accountOptions.size > 1) {
                        WalletAbiAccountSection(
                            review = currentReview,
                            enabled = !isResolving && !isApproving,
                            onSelectAccount = viewModel::selectAccount,
                        )
                    }

                    currentReview.impactAssets.forEach { asset ->
                        WalletAbiImpactCard(asset = asset)
                    }

                    if (currentReview.outputs.isNotEmpty()) {
                        WalletAbiOutputsCard(outputs = currentReview.outputs)
                    }

                    if (currentReview.inputs.isNotEmpty()) {
                        GreenCard {
                            GreenColumn(
                                padding = 0,
                                space = 12,
                            ) {
                                Text(
                                    text = "Inputs",
                                    style = titleSmall,
                                )
                                currentReview.inputs.forEach { input ->
                                    GreenColumn(
                                        padding = 0,
                                        space = 2,
                                    ) {
                                        Text(
                                            text = input.label,
                                            style = bodyMedium,
                                            fontWeight = FontWeight.SemiBold,
                                        )
                                        Text(
                                            text = input.detail,
                                            style = bodySmall,
                                            color = whiteMedium,
                                        )
                                    }
                                }
                            }
                        }
                    }

                    if (currentReview.warnings.isNotEmpty()) {
                        GreenCard {
                            GreenColumn(
                                padding = 0,
                                space = 8,
                            ) {
                                Text(
                                    text = "Warnings",
                                    style = titleSmall,
                                )
                                currentReview.warnings.forEach { warning ->
                                    Text(
                                        text = warning,
                                        style = bodyMedium,
                                        color = whiteMedium,
                                    )
                                }
                            }
                        }
                    }
                }

                val requiresResolution = currentReview.requiresResolution

                if (requiresResolution) {
                    GreenButton(
                        text = "Resolve transaction",
                        size = GreenButtonSize.BIG,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !isResolving && !isApproving,
                        onProgress = isResolving,
                    ) {
                        viewModel.resolveTransaction()
                    }
                }

                GreenButton(
                    text = "Reject request",
                    type = GreenButtonType.OUTLINE,
                    color = GreenButtonColor.WHITE,
                    size = GreenButtonSize.BIG,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isResolving && !isApproving,
                ) {
                    viewModel.rejectTransaction()
                }

                SlideToUnlock(
                    isLoading = isApproving,
                    enabled = !isResolving && !requiresResolution,
                    onSlideComplete = {
                        viewModel.approveTransaction()
                    },
                    hint = StringHolder.create(
                        if (currentReview.isBroadcast) {
                            "Slide to Confirm"
                        } else {
                            "Slide to Sign"
                        },
                    ),
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun WalletAbiRequestHeader(
    review: WalletAbiRequestLook,
) {
    GreenCard {
        GreenColumn(
            padding = 0,
            space = 16,
        ) {
            GreenColumn(
                padding = 0,
                space = 4,
            ) {
                Text(
                    text = "Contract from",
                    style = labelMedium,
                    color = whiteMedium,
                )
                Text(
                    text = review.origin,
                    style = titleMedium,
                )
                Text(
                    text = review.network,
                    style = bodyMedium,
                    color = whiteMedium,
                )
            }

            HorizontalDivider()

            Text(
                text = review.statusMessage,
                style = bodyLarge,
            )
        }
    }
}

@Composable
private fun WalletAbiAccountSection(
    review: WalletAbiRequestLook,
    enabled: Boolean,
    onSelectAccount: (String) -> Unit,
) {
    GreenCard {
        GreenColumn(
            padding = 0,
            space = 12,
        ) {
            Text(
                text = "Signing account",
                style = titleSmall,
            )
            review.accountOptions.forEach { option ->
                GreenButton(
                    text = option.name,
                    type = if (option.id == review.selectedAccountId) {
                        GreenButtonType.COLOR
                    } else {
                        GreenButtonType.OUTLINE
                    },
                    color = if (option.id == review.selectedAccountId) {
                        GreenButtonColor.GREEN
                    } else {
                        GreenButtonColor.WHITE
                    },
                    size = GreenButtonSize.BIG,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = enabled,
                ) {
                    onSelectAccount(option.id)
                }
            }
        }
    }
}

@Composable
private fun WalletAbiImpactCard(
    asset: WalletAbiRequestImpactLook,
) {
    GreenCard {
        GreenColumn(
            padding = 0,
            space = 12,
        ) {
            Text(
                text = asset.assetLabel,
                style = titleSmall,
                color = whiteMedium,
            )

            Text(
                text = asset.sentAway,
                style = headlineSmall,
            )

            GreenRow(
                padding = 0,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = "Returned to wallet",
                    style = bodyMedium,
                    color = whiteMedium,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = asset.sentBackToWallet,
                    style = bodyMedium,
                    textAlign = TextAlign.End,
                )
            }

            asset.exactFee?.also { fee ->
                GreenRow(
                    padding = 0,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = "Fee",
                        style = bodySmall,
                        color = whiteMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = fee,
                        style = bodySmall,
                    )
                }
            }

            asset.exactNetWalletDelta?.also { delta ->
                GreenRow(
                    padding = 0,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = "Net wallet change",
                        style = labelLarge,
                        color = whiteMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = delta,
                        style = labelLarge,
                        textAlign = TextAlign.End,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun WalletAbiOutputsCard(
    outputs: List<WalletAbiRequestOutputLook>,
) {
    GreenCard {
        GreenColumn(
            padding = 0,
            space = 12,
        ) {
            Text(
                text = "Outputs",
                style = titleSmall,
            )

            outputs.forEachIndexed { index, output ->
                if (index > 0) {
                    HorizontalDivider()
                }

                GreenColumn(
                    padding = 0,
                    space = 4,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    GreenRow(
                        padding = 0,
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = output.label,
                            style = bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = output.amount,
                            style = bodyMedium,
                            textAlign = TextAlign.End,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Text(
                        text = output.detail,
                        style = bodySmall,
                        color = whiteMedium,
                    )
                }
            }
        }
    }
}
