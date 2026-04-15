package com.blockstream.common.walletabi

import co.touchlab.stately.collections.ConcurrentMutableMap
import com.blockstream.common.BTC_UNIT
import com.blockstream.common.gdk.GdkSession
import com.blockstream.common.gdk.data.Account
import com.blockstream.common.gdk.data.AccountType
import com.blockstream.common.gdk.data.Asset
import com.blockstream.common.gdk.data.Balance
import com.blockstream.common.gdk.data.Network
import com.blockstream.common.gdk.data.Pricing
import com.blockstream.common.gdk.data.Settings
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class WalletAbiReviewFormattingTest {
    @Test
    fun formatWalletAbiReviewAmountUsesSharedFormatterForPolicyAsset() = runTest {
        val policyAssetId = "liquid-policy-asset"
        val account = liquidAccount(policyAssetId)
        val session = mockk<GdkSession>()
        val settings = Settings(
            pricing = Pricing(currency = "USD", exchange = "test"),
            unit = BTC_UNIT,
        )
        val networks = ConcurrentMutableMap<Network, Any>().apply {
            put(account.network, Any())
        }

        every { session.gdkSessions } returns networks
        every { session.activeLiquid } returns account.network
        every { session.liquid } returns account.network
        every { session.getSettings(account.network) } returns settings
        coEvery {
            session.convert(
                assetId = policyAssetId,
                asString = null,
                asLong = 12_345_678L,
                denomination = null,
                onlyInAcceptableRange = true,
            )
        } returns Balance(
            btc = "0.12345678",
            satoshi = 12_345_678L,
        )

        val formatted = formatWalletAbiReviewAmount(
            session = session,
            amountSat = 12_345_678L,
            assetId = policyAssetId,
            account = account,
        )

        assertEquals("0.12345678 LBTC", formatted)
    }

    @Test
    fun formatWalletAbiReviewAmountUsesSharedFormatterForIssuedAsset() = runTest {
        val issuedAssetId = "asset-issued-123"
        val account = liquidAccount("liquid-policy-asset")
        val session = mockk<GdkSession>()

        every { session.gdkSessions } returns ConcurrentMutableMap<Network, Any>()
        coEvery {
            session.convert(
                assetId = issuedAssetId,
                asString = null,
                asLong = 123_456L,
                denomination = null,
                onlyInAcceptableRange = true,
            )
        } returns Balance(
            satoshi = 123_456L,
            assetAmount = "123.456",
            asset = Asset(
                name = "Tether USD",
                assetId = issuedAssetId,
                precision = 3,
                ticker = "USDT",
            ),
        )

        val formatted = formatWalletAbiReviewAmount(
            session = session,
            amountSat = 123_456L,
            assetId = issuedAssetId,
            account = account,
        )

        assertEquals("123.456 USDT", formatted)
        assertFalse(formatted.startsWith("123456 "))
        assertFalse(formatted.contains(issuedAssetId.take(8)))
    }

    private fun liquidAccount(policyAssetId: String): Account {
        return Account(
            networkInjected = Network(
                network = Network.GreenLiquid,
                name = "Liquid",
                isMainnet = true,
                isLiquid = true,
                isDevelopment = false,
                policyAsset = policyAssetId,
            ),
            gdkName = "Liquid Account",
            pointer = 0,
            type = AccountType.BIP84_SEGWIT,
        )
    }
}
