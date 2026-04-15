package com.blockstream.common.models.overview

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class SecurityViewModelTest {
    @Test
    fun previewExposesWalletAbiCardWhenRequested() {
        val viewModel = SecurityViewModelPreview.preview(showWalletAbiCard = true)

        val card = assertNotNull(viewModel.walletAbiCard.value)
        assertEquals("Connected dApp", card.title)
        assertEquals("Connected", card.statusLabel)
    }
}
