package com.blockstream.data.di

import android.bluetooth.BluetoothAdapter
import android.content.Context
import androidx.preference.PreferenceManager
import com.blockstream.common.data.AppConfig
import com.blockstream.common.walletabi.AndroidWalletAbiWalletConnectBridge
import com.blockstream.common.walletabi.WalletAbiWalletConnectBridge
import com.blockstream.data.crypto.GreenKeystore
import com.blockstream.data.database.DriverFactory
import com.blockstream.data.managers.BluetoothManager
import com.blockstream.data.managers.LocaleManager
import com.blockstream.data.managers.SettingsManager
import com.blockstream.data.utils.AndroidKeystore
import com.russhwolf.settings.ObservableSettings
import com.russhwolf.settings.SharedPreferencesSettings
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.Module
import org.koin.dsl.binds
import org.koin.dsl.module

actual val platformModule: Module = module {
    single {
        DriverFactory(androidContext())
    }
    single {
        PreferenceManager.getDefaultSharedPreferences(androidContext())
    }
    single {
        LocaleManager(get())
    }
    single<ObservableSettings> {
        val sharedPreferences = androidContext().getSharedPreferences(
            SettingsManager.APPLICATION_SETTINGS_NAME,
            Context.MODE_PRIVATE
        )
        SharedPreferencesSettings(sharedPreferences)
    }
    single {
        AndroidKeystore(androidContext())
    } binds (arrayOf(GreenKeystore::class))

    single<BluetoothAdapter?> { (androidContext().getSystemService(Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager)?.adapter }
    single<BluetoothManager> { BluetoothManager(androidContext(), null) }
    single<WalletAbiWalletConnectBridge> {
        AndroidWalletAbiWalletConnectBridge(
            application = androidContext().applicationContext as android.app.Application,
            appConfig = get<AppConfig>(),
        )
    }
}
