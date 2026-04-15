package com.blockstream.common.walletabi

import com.blockstream.common.data.GreenWallet
import com.blockstream.common.data.WalletSerializable
import com.blockstream.common.database.Database
import com.blockstream.common.database.DriverFactory
import com.blockstream.common.gdk.data.Account
import com.blockstream.common.gdk.data.AccountType
import com.blockstream.common.gdk.data.Network
import com.blockstream.common.managers.SettingsManager
import com.russhwolf.settings.PreferencesSettings
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import java.util.prefs.Preferences
import kotlin.test.Test
import kotlin.test.assertEquals

class TransactionViewModelWalletAbiTest {
    private val json = Json {
        encodeDefaults = true
        explicitNulls = false
        ignoreUnknownKeys = true
    }

    @Test
    fun observeByTxHashMapsWalletAbiDetailsLook() = runTest {
        val database = database()
        database.insertWallet(greenWallet(id = "wallet-1"))
        val store = WalletAbiTransactionStore(database = database, json = json)
        val record = transactionRecord(
            walletId = "wallet-1",
            txHash = "tx-1",
            origin = "borrow.blockstream.com",
            status = WalletAbiTransactionRecordStatus.BROADCAST,
            updatedAtEpochMilliseconds = 10L,
        )

        store.save(record)

        val details = store.observe(walletId = "wallet-1", txHash = "tx-1")
            .filterNotNull()
            .first()
            .toDetailsLook()

        assertEquals("borrow.blockstream.com", details.origin)
        assertEquals("Broadcast", details.statusLabel)
        assertEquals("Savings", details.accountName)
        assertEquals("Output 1", details.outputs.single().label)
    }

    private fun database(): Database {
        return Database(
            driverFactory = DriverFactory(),
            settingsManager = SettingsManager(
                settings = PreferencesSettings(
                    Preferences.userRoot().node("transaction-viewmodel-wallet-abi-test-${System.nanoTime()}"),
                ),
                analyticsFeatureEnabled = false,
                lightningFeatureEnabled = false,
                storeRateEnabled = false,
            ),
        )
    }

    private fun greenWallet(id: String): GreenWallet {
        return GreenWallet(
            wallet = WalletSerializable(
                id = id,
                name = "Wallet $id",
                xpub_hash_id = "xpub-$id",
                active_network = "testnet-liquid",
                active_account = 0L,
                is_recovery_confirmed = true,
                is_testnet = true,
                is_hardware = false,
                is_lightning = false,
                ask_bip39_passphrase = false,
                watch_only_username = null,
                device_identifiers = null,
                extras = null,
                order = 0L,
            ),
        )
    }

    private fun transactionRecord(
        walletId: String,
        txHash: String,
        origin: String,
        status: WalletAbiTransactionRecordStatus,
        updatedAtEpochMilliseconds: Long,
    ): WalletAbiTransactionRecord {
        return WalletAbiTransactionRecord(
            walletId = walletId,
            txHash = txHash,
            origin = origin,
            status = status,
            review = WalletAbiTransactionReviewLook(
                origin = origin,
                requestId = "request-$txHash",
                network = "testnet-liquid",
                broadcast = status == WalletAbiTransactionRecordStatus.BROADCAST,
                resolutionState = WalletAbiResolutionState.NOT_REQUIRED,
                accountOptions = listOf(
                    WalletAbiAccountOptionLook(
                        id = account().id,
                        name = account().name,
                    ),
                ),
                selectedAccountId = account().id,
                selectedAccountName = account().name,
                inputs = listOf(
                    WalletAbiInputReviewLook(
                        label = "Input 1",
                        detail = "wallet-selected",
                    ),
                ),
                outputs = listOf(
                    WalletAbiOutputReviewLook(
                        label = "Output 1",
                        amount = "+0.001 L-BTC",
                        detail = "External output",
                        classification = WalletAbiOutputClassification.EXTERNAL,
                    ),
                ),
                impactAssets = listOf(
                    WalletAbiImpactAssetLook(
                        assetId = "asset-1",
                        assetLabel = "L-BTC",
                        sentAway = "-0.001 L-BTC",
                        sentBackToWallet = "+0.0001 L-BTC",
                    ),
                ),
                exactImpactState = WalletAbiExactImpactState.READY,
                inputSourceSummary = WalletAbiInputSourceSummaryLook(
                    walletSelectedInputCount = 1,
                    explicitExternalInputCount = 0,
                    otherInputCount = 0,
                ),
                statusMessage = "Ready",
                warnings = listOf("Warning"),
            ),
            updatedAtEpochMilliseconds = updatedAtEpochMilliseconds,
            extraJson = null,
        )
    }

    private fun account(): Account {
        return Account(
            networkInjected = Network(
                network = Network.GreenTestnetLiquid,
                name = "Liquid Testnet",
                isMainnet = false,
                isLiquid = true,
                isDevelopment = false,
                policyAsset = "policy-asset",
            ),
            gdkName = "Savings",
            pointer = 0L,
            type = AccountType.BIP84_SEGWIT,
        )
    }
}
