package com.blockstream.common.walletabi

import com.blockstream.common.models.walletabi.WalletAbiConnectionViewModelPreview
import com.blockstream.common.models.walletabi.toConnectionScreenLook
import com.blockstream.common.navigation.NavigateDestinations
import com.blockstream.common.sideeffects.SideEffects
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class WalletAbiConnectionViewModelTest {
    @Test
    fun transactionRequestPrimaryActionNavigatesToWalletAbiRequest() = runTest {
        val viewModel = WalletAbiConnectionViewModelPreview.transactionRequest()
        val sideEffects = async {
            viewModel.sideEffect.take(1).toList()
        }

        viewModel.primaryAction()

        val navigate = assertIs<SideEffects.NavigateTo>(sideEffects.await().single())
        assertIs<NavigateDestinations.WalletAbiRequest>(navigate.destination)
    }

    @Test
    fun activeConnectionLookListsApprovedGetters() {
        val screen = WalletAbiSessionUiState(
            activeConnection = WalletAbiConnectionLook(
                origin = "Example dApp",
                network = "liquid",
                state = WalletAbiSessionState.CONNECTED,
                approvedGetters = setOf(
                    WalletAbiGetterPermission.GET_SIGNER_RECEIVE_ADDRESS,
                    WalletAbiGetterPermission.GET_RAW_SIGNING_X_ONLY_PUBKEY,
                ),
            ),
        ).toConnectionScreenLook()

        assertEquals("Disconnect", screen.primaryActionLabel)
        assertEquals(
            listOf("Share receive address", "Share x-only signing pubkey"),
            screen.sections.single().lines,
        )
    }
}
