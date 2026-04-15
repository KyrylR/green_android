package com.blockstream.compose.screens.walletabi

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.blockstream.common.models.walletabi.WalletAbiConnectionScreenLook
import com.blockstream.common.models.walletabi.WalletAbiConnectionSectionLook
import com.blockstream.common.models.walletabi.WalletAbiConnectionViewModelAbstract
import com.blockstream.compose.GreenPreview
import com.blockstream.compose.components.GreenButton
import com.blockstream.compose.components.GreenButtonColor
import com.blockstream.compose.components.GreenButtonSize
import com.blockstream.compose.components.GreenButtonType
import com.blockstream.compose.components.GreenCard
import com.blockstream.compose.theme.bodyMedium
import com.blockstream.compose.theme.labelLarge
import com.blockstream.compose.theme.titleSmall
import com.blockstream.compose.theme.whiteMedium
import com.blockstream.compose.utils.SetupScreen
import com.blockstream.ui.components.GreenColumn
import com.blockstream.ui.components.GreenRow
import org.jetbrains.compose.ui.tooling.preview.Preview

@Composable
fun WalletAbiConnectionScreen(
    viewModel: WalletAbiConnectionViewModelAbstract,
) {
    SetupScreen(
        viewModel = viewModel,
        withPadding = false,
        withBottomInsets = false,
    ) { innerPadding ->
        val screen by viewModel.screen.collectAsStateWithLifecycle()
        val isWorking by viewModel.isWorking.collectAsStateWithLifecycle()

        GreenColumn(
            padding = 0,
            space = 16,
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .padding(top = 8.dp)
                .padding(bottom = innerPadding.calculateBottomPadding() + 16.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            WalletAbiConnectionSummary(screen = screen)

            screen.sections.forEach { section ->
                WalletAbiConnectionSection(section = section)
            }

            screen.warning?.also { warning ->
                GreenCard {
                    GreenColumn(
                        padding = 0,
                        space = 8,
                    ) {
                        Text(
                            text = "Attention",
                            style = titleSmall,
                        )
                        Text(
                            text = warning,
                            style = bodyMedium,
                            color = whiteMedium,
                        )
                    }
                }
            }

            screen.primaryActionLabel?.also { label ->
                GreenButton(
                    text = label,
                    size = GreenButtonSize.BIG,
                    modifier = Modifier.fillMaxWidth(),
                    onProgress = isWorking,
                    enabled = !isWorking,
                ) {
                    viewModel.primaryAction()
                }
            }

            screen.secondaryActionLabel?.also { label ->
                GreenButton(
                    text = label,
                    type = GreenButtonType.OUTLINE,
                    color = GreenButtonColor.WHITE,
                    size = GreenButtonSize.BIG,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isWorking,
                ) {
                    viewModel.secondaryAction()
                }
            }
        }
    }
}

@Composable
private fun WalletAbiConnectionSummary(
    screen: WalletAbiConnectionScreenLook,
) {
    GreenCard {
        GreenColumn(
            padding = 0,
            space = 12,
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
                        text = screen.card.title,
                        style = titleSmall,
                    )
                    screen.card.subtitle?.also { subtitle ->
                        Text(
                            text = subtitle,
                            style = bodyMedium,
                            color = whiteMedium,
                        )
                    }
                }

                Text(
                    text = screen.card.statusLabel,
                    style = labelLarge,
                )
            }

            Text(
                text = screen.card.body,
                style = bodyMedium,
                color = whiteMedium,
            )
        }
    }
}

@Composable
private fun WalletAbiConnectionSection(
    section: WalletAbiConnectionSectionLook,
) {
    GreenCard {
        GreenColumn(
            padding = 0,
            space = 12,
        ) {
            Text(
                text = section.title,
                style = titleSmall,
            )

            section.lines.forEach { line ->
                Text(
                    text = line,
                    style = bodyMedium,
                    color = whiteMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@Preview
@Composable
private fun WalletAbiConnectionScreenPreview() {
    GreenPreview {
        WalletAbiConnectionScreen(
            viewModel = com.blockstream.common.models.walletabi.WalletAbiConnectionViewModelPreview.preview(),
        )
    }
}
