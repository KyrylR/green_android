package com.blockstream.compose.screens.walletabi

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import blockstream_green.common.generated.resources.Res
import blockstream_green.common.generated.resources.id_scan_from_image
import com.blockstream.common.data.ScanResult
import com.blockstream.common.models.walletabi.WalletAbiScanViewModelAbstract
import com.blockstream.compose.GreenPreview
import com.blockstream.compose.LocalDialog
import com.blockstream.compose.components.CameraView
import com.blockstream.compose.components.GreenButton
import com.blockstream.compose.components.GreenButtonColor
import com.blockstream.compose.components.GreenButtonSize
import com.blockstream.compose.components.GreenButtonType
import com.blockstream.compose.events.Events
import com.blockstream.compose.managers.rememberImagePicker
import com.blockstream.compose.managers.rememberPlatformManager
import com.blockstream.compose.navigation.LocalInnerPadding
import com.blockstream.compose.navigation.NavigateDestinations
import com.blockstream.compose.navigation.setResult
import com.blockstream.compose.sideeffects.SideEffects
import com.blockstream.compose.theme.md_theme_background
import com.blockstream.compose.utils.SetupScreen
import com.blockstream.compose.utils.bottom
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.ui.tooling.preview.Preview

@Composable
fun WalletAbiScanScreen(
    viewModel: WalletAbiScanViewModelAbstract,
) {
    val platformManager = rememberPlatformManager()
    val dialog = LocalDialog.current
    val scope = rememberCoroutineScope()
    val innerPadding = LocalInnerPadding.current
    val progress by viewModel.progress.collectAsStateWithLifecycle()

    val imagePicker = rememberImagePicker(scope = scope) { bytes ->
        val result = platformManager.scanQrFromByteArray(bytes)
        if (result != null) {
            viewModel.postEvent(Events.SetBarcodeScannerResult(result))
        } else {
            scope.launch {
                dialog.openErrorDialog(Exception("id_could_not_recognized_qr_code"))
            }
        }
    }

    SetupScreen(
        viewModel = viewModel,
        withPadding = false,
        withBottomInsets = false,
        sideEffectsHandler = {
            if (it is SideEffects.Success) {
                (it.data as? ScanResult)?.also { scanResult ->
                    NavigateDestinations.WalletAbiScan.setResult(scanResult)
                }
            }
        },
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(md_theme_background),
        ) {
            CameraView(
                modifier = Modifier.fillMaxSize(),
                isDecodeContinuous = true,
                showScanFromImage = false,
                viewModel = viewModel,
            )

            progress?.also { value ->
                LinearProgressIndicator(
                    progress = { value / 100f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter),
                )
            }

            GreenButton(
                text = stringResource(Res.string.id_scan_from_image),
                type = GreenButtonType.OUTLINE,
                color = GreenButtonColor.GREENER,
                size = GreenButtonSize.BIG,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 16.dp)
                    .padding(innerPadding.bottom())
                    .padding(bottom = 24.dp)
                    .fillMaxWidth(),
            ) {
                imagePicker.launch()
            }
        }
    }
}

@Preview
@Composable
private fun WalletAbiScanScreenPreview() {
    GreenPreview {
        WalletAbiScanScreen(viewModel = com.blockstream.common.models.walletabi.WalletAbiScanViewModelPreview.preview())
    }
}
