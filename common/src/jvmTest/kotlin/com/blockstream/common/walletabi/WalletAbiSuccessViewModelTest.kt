package com.blockstream.common.walletabi

import com.blockstream.common.models.walletabi.WalletAbiSuccessViewModelPreview
import com.blockstream.common.sideeffects.SideEffects
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class WalletAbiSuccessViewModelTest {
    @Test
    fun shareEmitsShareSideEffect() = runTest {
        val viewModel = WalletAbiSuccessViewModelPreview.preview()
        val sideEffects = async {
            viewModel.sideEffect.take(1).toList()
        }

        viewModel.share()

        val share = assertIs<SideEffects.Share>(sideEffects.await().single())
        assertEquals(
            "Contract confirmed\n49ef9797c308d11a92d8de30f4bd31029d2b60b4c089ff6af35b7d0cfdcfef9",
            share.text,
        )
    }
}
