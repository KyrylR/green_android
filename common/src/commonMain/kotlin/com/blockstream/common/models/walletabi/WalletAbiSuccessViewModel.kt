package com.blockstream.common.models.walletabi

import com.blockstream.common.data.GreenWallet
import com.blockstream.common.extensions.previewWallet
import com.blockstream.common.models.GreenViewModel
import com.blockstream.common.sideeffects.SideEffects
import com.blockstream.ui.navigation.NavData
import com.rickclephas.kmp.observableviewmodel.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable

@Serializable
data class WalletAbiSuccessLook(
    val title: String,
    val message: String,
    val reference: String? = null,
    val shareText: String? = null,
)

abstract class WalletAbiSuccessViewModelAbstract(
    greenWallet: GreenWallet,
) : GreenViewModel(greenWalletOrNull = greenWallet) {
    override fun screenName(): String = "WalletAbiSuccess"

    abstract val success: StateFlow<WalletAbiSuccessLook>

    fun done() {
        postSideEffect(SideEffects.NavigateBack())
    }

    fun share() {
        success.value.shareText?.takeIf { it.isNotBlank() }?.also { shareText ->
            postSideEffect(SideEffects.Share(text = shareText))
        }
    }
}

class WalletAbiSuccessViewModel(
    greenWallet: GreenWallet,
    success: WalletAbiSuccessLook,
) : WalletAbiSuccessViewModelAbstract(greenWallet = greenWallet) {
    override val success: StateFlow<WalletAbiSuccessLook> = MutableStateFlow(success)

    init {
        _navData.value = NavData(
            isVisible = false,
            showNavigationIcon = false,
        )
    }
}

class WalletAbiSuccessViewModelPreview :
    WalletAbiSuccessViewModelAbstract(greenWallet = previewWallet()) {
    override val success: StateFlow<WalletAbiSuccessLook> = MutableStateFlow(
        WalletAbiSuccessLook(
            title = "Contract confirmed",
            message = "Wallet ABI request processed successfully.",
            reference = "49ef9797c308d11a92d8de30f4bd31029d2b60b4c089ff6af35b7d0cfdcfef9",
            shareText = "Contract confirmed\n49ef9797c308d11a92d8de30f4bd31029d2b60b4c089ff6af35b7d0cfdcfef9",
        ),
    )

    init {
        _navData.value = NavData(
            isVisible = false,
            showNavigationIcon = false,
        )
    }

    companion object {
        fun preview() = WalletAbiSuccessViewModelPreview()
    }
}
