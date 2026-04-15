package com.blockstream.common.walletabi

import com.blockstream.common.data.ScanResult
import com.blockstream.common.models.walletabi.WalletAbiScanViewModelPreview
import com.blockstream.common.sideeffects.SideEffects
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class WalletAbiScanViewModelTest {
    @Test
    fun setScanResultEmitsSuccessThenNavigateBack() = runTest {
        val viewModel = WalletAbiScanViewModelPreview.preview()
        val sideEffects = async {
            viewModel.sideEffect.take(2).toList()
        }

        viewModel.setScanResult(ScanResult("wc:example"))

        val emitted = sideEffects.await()
        val success = assertIs<SideEffects.Success>(emitted[0])
        assertEquals("wc:example", (success.data as ScanResult).result)
        assertIs<SideEffects.NavigateBack>(emitted[1])
    }
}
