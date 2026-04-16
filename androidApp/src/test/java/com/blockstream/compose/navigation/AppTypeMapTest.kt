package com.blockstream.compose.navigation

import com.blockstream.common.models.walletabi.WalletAbiSuccessLook
import org.junit.Assert.assertNotNull
import org.junit.Test
import kotlin.reflect.typeOf

class AppTypeMapTest {

    @Test
    fun `AppTypeMap registers WalletAbiSuccessLook`() {
        assertNotNull(AppTypeMap[typeOf<WalletAbiSuccessLook>()])
    }
}
