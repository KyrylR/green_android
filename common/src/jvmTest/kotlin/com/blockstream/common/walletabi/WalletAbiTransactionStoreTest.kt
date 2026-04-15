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
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import java.util.prefs.Preferences
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WalletAbiTransactionStoreTest {
    private val json = Json {
        encodeDefaults = true
        explicitNulls = false
        ignoreUnknownKeys = true
    }

    @Test
    fun saveOverwritesByWalletAndTxHashAndListsNewestFirst() = runTest {
        val database = database()
        database.insertWallet(greenWallet(id = "wallet-1"))
        database.insertWallet(greenWallet(id = "wallet-2"))
        val store = WalletAbiTransactionStore(database = database, json = json)

        val original = transactionRecord(
            walletId = "wallet-1",
            txHash = "tx-1",
            origin = "Example dApp",
            status = WalletAbiTransactionRecordStatus.APPROVED,
            updatedAtEpochMilliseconds = 10L,
        )
        val newer = transactionRecord(
            walletId = "wallet-1",
            txHash = "tx-2",
            origin = "Other dApp",
            status = WalletAbiTransactionRecordStatus.BROADCAST,
            updatedAtEpochMilliseconds = 20L,
        )
        val differentWallet = transactionRecord(
            walletId = "wallet-2",
            txHash = "tx-1",
            origin = "Wallet Two dApp",
            status = WalletAbiTransactionRecordStatus.APPROVED,
            updatedAtEpochMilliseconds = 30L,
        )

        store.save(original)
        store.save(newer)
        store.save(differentWallet)

        assertEquals(original, store.get(walletId = "wallet-1", txHash = "tx-1"))
        assertEquals(
            listOf(newer, original),
            store.list(walletId = "wallet-1"),
        )

        val replacement = original.copy(
            origin = "Updated dApp",
            status = WalletAbiTransactionRecordStatus.BROADCAST,
            updatedAtEpochMilliseconds = 40L,
        )
        store.save(replacement)

        assertEquals(replacement, store.get(walletId = "wallet-1", txHash = "tx-1"))
        assertEquals(listOf(replacement, newer), store.list(walletId = "wallet-1"))
        assertEquals(listOf(differentWallet), store.list(walletId = "wallet-2"))

        store.delete(walletId = "wallet-1", txHash = "tx-2")

        assertNull(store.get(walletId = "wallet-1", txHash = "tx-2"))
        assertEquals(listOf(replacement), store.list(walletId = "wallet-1"))
    }

    private fun database(): Database {
        return Database(
            driverFactory = DriverFactory(),
            settingsManager = SettingsManager(
                settings = PreferencesSettings(
                    Preferences.userRoot().node("wallet-abi-transaction-store-test-${System.nanoTime()}"),
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
            extraJson = """{"note":"stored"}""",
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
