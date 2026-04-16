package com.blockstream.common.models.walletabi

import androidx.lifecycle.viewModelScope
import blockstream_green.common.generated.resources.Res
import blockstream_green.common.generated.resources.id_scan_qr_code
import com.blockstream.common.data.GreenWallet
import com.blockstream.common.data.ScanResult
import com.blockstream.compose.extensions.previewWallet
import com.blockstream.compose.models.abstract.AbstractScannerViewModel
import com.blockstream.compose.navigation.NavData
import com.blockstream.compose.sideeffects.SideEffects
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.getString

abstract class WalletAbiScanViewModelAbstract(
    greenWallet: GreenWallet,
) : AbstractScannerViewModel(
    isDecodeContinuous = true,
    greenWalletOrNull = greenWallet,
) {
    override fun screenName(): String = "WalletAbiScan"

    protected fun completeScan(scanResult: ScanResult) {
        postSideEffect(SideEffects.Success(scanResult))
        postSideEffect(SideEffects.NavigateBack())
    }
}

class WalletAbiScanViewModel(
    greenWallet: GreenWallet,
) : WalletAbiScanViewModelAbstract(greenWallet = greenWallet) {
    init {
        viewModelScope.launch {
            _navData.value = NavData(
                title = getString(Res.string.id_scan_qr_code),
                walletName = greenWallet.name,
            )
        }

        bootstrap()
    }

    override fun setScanResult(scanResult: ScanResult) {
        completeScan(scanResult)
    }
}

class WalletAbiScanViewModelPreview :
    WalletAbiScanViewModelAbstract(greenWallet = previewWallet()) {
    override fun setScanResult(scanResult: ScanResult) {
        completeScan(scanResult)
    }

    companion object {
        fun preview() = WalletAbiScanViewModelPreview()
    }
}
