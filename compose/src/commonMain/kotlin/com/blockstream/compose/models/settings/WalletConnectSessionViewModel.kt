package com.blockstream.compose.models.settings

import androidx.lifecycle.viewModelScope
import com.blockstream.compose.models.GreenViewModel
import com.blockstream.compose.navigation.NavData
import com.blockstream.data.data.GreenWallet
import com.blockstream.data.walletconnect.WalletConnectManager
import com.blockstream.data.walletconnect.WalletConnectSessionReview
import com.blockstream.data.walletconnect.WalletConnectStatus
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.koin.core.component.inject

abstract class WalletConnectSessionViewModelAbstract(
    greenWallet: GreenWallet
) : GreenViewModel(greenWalletOrNull = greenWallet) {
    abstract val activeSession: StateFlow<WalletConnectSessionReview?>
    abstract val status: StateFlow<WalletConnectStatus>
    abstract val lastError: StateFlow<String?>

    override fun screenName(): String = "WalletConnectSession"

    abstract fun disconnect()
    abstract fun extend()
}

class WalletConnectSessionViewModel(
    greenWallet: GreenWallet
) : WalletConnectSessionViewModelAbstract(greenWallet = greenWallet) {
    private val walletConnectManager: WalletConnectManager by inject()

    override val activeSession: StateFlow<WalletConnectSessionReview?> = walletConnectManager.activeSession
    override val status: StateFlow<WalletConnectStatus> = walletConnectManager.status
    override val lastError: StateFlow<String?> = walletConnectManager.lastError

    init {
        viewModelScope.launch {
            _navData.value = NavData(
                title = "WalletConnect",
                subtitle = greenWallet.name
            )
        }

        bootstrap()
    }

    override fun disconnect() {
        walletConnectManager.disconnectActiveSession()
    }

    override fun extend() {
        walletConnectManager.extendActiveSession()
    }
}
