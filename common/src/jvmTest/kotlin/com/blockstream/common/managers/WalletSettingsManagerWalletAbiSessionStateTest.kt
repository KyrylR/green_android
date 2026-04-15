package com.blockstream.common.managers

import com.blockstream.common.database.Database
import com.blockstream.common.database.DriverFactory
import com.russhwolf.settings.PreferencesSettings
import kotlinx.coroutines.test.runTest
import java.util.prefs.Preferences
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WalletSettingsManagerWalletAbiSessionStateTest {
    @Test
    fun walletAbiSessionStateRoundTripsThroughWalletSettingsStorage() = runTest {
        val settings = PreferencesSettings(
            Preferences.userRoot().node("wallet-abi-session-state-test"),
        )
        val database = Database(
            driverFactory = DriverFactory(),
            settingsManager = SettingsManager(
                settings = settings,
                analyticsFeatureEnabled = false,
                lightningFeatureEnabled = false,
                storeRateEnabled = false,
            ),
        )
        val manager = WalletSettingsManager(database)

        assertNull(manager.getWalletAbiSessionState("wallet-1"))

        manager.setWalletAbiSessionState("wallet-1", """{"topic":"topic-1"}""")
        assertEquals("""{"topic":"topic-1"}""", manager.getWalletAbiSessionState("wallet-1"))

        manager.clearWalletAbiSessionState("wallet-1")
        assertNull(manager.getWalletAbiSessionState("wallet-1"))
    }
}
