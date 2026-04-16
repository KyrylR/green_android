package com.blockstream.compose.screens.walletabi

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.SealCheck
import com.blockstream.common.models.walletabi.WalletAbiSuccessViewModelAbstract
import com.blockstream.compose.GreenPreview
import com.blockstream.compose.components.GreenButton
import com.blockstream.compose.components.GreenButtonColor
import com.blockstream.compose.components.GreenButtonSize
import com.blockstream.compose.components.GreenButtonType
import com.blockstream.compose.components.GreenCard
import com.blockstream.compose.components.GreenColumn
import com.blockstream.compose.theme.MonospaceFont
import com.blockstream.compose.theme.bodyMedium
import com.blockstream.compose.theme.bodySmall
import com.blockstream.compose.theme.headlineSmall
import com.blockstream.compose.theme.md_theme_primary
import com.blockstream.compose.theme.md_theme_surface
import com.blockstream.compose.theme.titleSmall
import com.blockstream.compose.theme.whiteMedium
import com.blockstream.compose.utils.SetupScreen
import org.jetbrains.compose.ui.tooling.preview.Preview

@Composable
fun WalletAbiSuccessScreen(
    viewModel: WalletAbiSuccessViewModelAbstract,
) {
    val success by viewModel.success.collectAsStateWithLifecycle()

    SetupScreen(
        viewModel = viewModel,
        withPadding = false,
        withBottomInsets = false,
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp)
                .padding(bottom = innerPadding.calculateBottomPadding()),
            contentAlignment = Alignment.Center,
        ) {
            GreenCard(
                modifier = Modifier.fillMaxWidth(),
            ) {
                GreenColumn(
                    padding = 0,
                    space = 20,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        modifier = Modifier
                            .size(96.dp)
                            .clip(RoundedCornerShape(20.dp))
                            .background(md_theme_primary),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = PhosphorIcons.Regular.SealCheck,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = md_theme_surface,
                        )
                    }

                    GreenColumn(
                        padding = 0,
                        space = 8,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text = success.title,
                            style = headlineSmall,
                            textAlign = TextAlign.Center,
                        )
                        Text(
                            text = success.message,
                            style = bodyMedium,
                            color = whiteMedium,
                            textAlign = TextAlign.Center,
                        )
                    }

                    success.reference?.also { reference ->
                        GreenColumn(
                            padding = 0,
                            space = 4,
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                text = "Transaction ID",
                                style = titleSmall,
                            )
                            Text(
                                text = reference,
                                style = bodySmall,
                                fontFamily = MonospaceFont(),
                                textAlign = TextAlign.Center,
                            )
                        }
                    }

                    GreenButton(
                        text = "Done",
                        size = GreenButtonSize.BIG,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        viewModel.done()
                    }

                    GreenButton(
                        text = "Share link",
                        type = GreenButtonType.OUTLINE,
                        color = GreenButtonColor.GREENER,
                        size = GreenButtonSize.BIG,
                        enabled = success.shareText != null,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        viewModel.share()
                    }
                }
            }
        }
    }
}

@Preview
@Composable
private fun WalletAbiSuccessScreenPreview() {
    GreenPreview {
        WalletAbiSuccessScreen(
            viewModel = com.blockstream.common.models.walletabi.WalletAbiSuccessViewModelPreview.preview(),
        )
    }
}
