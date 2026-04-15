package com.blockstream.common.walletabi

import com.blockstream.common.models.walletabi.WalletAbiRequestViewModelPreview
import com.blockstream.common.sideeffects.SideEffects
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class WalletAbiRequestViewModelTest {
    @Test
    fun resolveTransactionUpdatesPreviewReview() = runTest {
        val viewModel = WalletAbiRequestViewModelPreview.preview()

        assertEquals(
            true,
            viewModel.review.value?.requiresResolution,
        )

        viewModel.resolveTransaction()

        assertEquals(
            false,
            viewModel.review.value?.requiresResolution,
        )
    }

    @Test
    fun approveTransactionEmitsSnackbarThenNavigateBack() = runTest {
        val viewModel = WalletAbiRequestViewModelPreview.preview()
        val sideEffects = async {
            viewModel.sideEffect.take(2).toList()
        }

        viewModel.approveTransaction()

        val emitted = sideEffects.await()
        val snackbar = assertIs<SideEffects.Snackbar>(emitted[0])
        assertEquals("Wallet ABI request approved", snackbar.text.fallbackString())
        assertIs<SideEffects.NavigateBack>(emitted[1])
    }
}
