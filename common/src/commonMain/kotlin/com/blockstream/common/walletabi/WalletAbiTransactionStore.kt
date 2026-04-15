package com.blockstream.common.walletabi

import com.blockstream.data.database.Database
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
internal enum class WalletAbiTransactionRecordStatus {
    APPROVED,
    BROADCAST,
}

@Serializable
internal data class WalletAbiTransactionRecord(
    val walletId: String,
    val txHash: String,
    val origin: String,
    val status: WalletAbiTransactionRecordStatus,
    val review: WalletAbiTransactionReviewLook,
    val updatedAtEpochMilliseconds: Long,
    val extraJson: String? = null,
)

internal class WalletAbiTransactionStore(
    private val database: Database,
    private val json: Json,
) {
    fun observeList(walletId: String): Flow<List<WalletAbiTransactionRecord>> {
        return database.getWalletAbiTransactionRecordsFlow(walletId).map { rows ->
            rows.mapNotNull(::decodeRecordOrNull)
        }
    }

    suspend fun get(walletId: String, txHash: String): WalletAbiTransactionRecord? {
        return database.getWalletAbiTransactionRecord(
            walletId = walletId,
            txHash = txHash,
        )?.let(::decodeRecord)
    }

    suspend fun list(walletId: String): List<WalletAbiTransactionRecord> {
        return database.getWalletAbiTransactionRecords(walletId)
            .mapNotNull(::decodeRecordOrNull)
    }

    suspend fun save(record: WalletAbiTransactionRecord) {
        database.setWalletAbiTransactionRecord(
            walletId = record.walletId,
            txHash = record.txHash,
            updatedAtEpochMilliseconds = record.updatedAtEpochMilliseconds,
            recordJson = json.encodeToString(record),
        )
    }

    suspend fun delete(walletId: String, txHash: String) {
        database.deleteWalletAbiTransactionRecord(
            walletId = walletId,
            txHash = txHash,
        )
    }

    private fun decodeRecord(row: com.blockstream.data.database.wallet.WalletAbiTransactions): WalletAbiTransactionRecord {
        return json.decodeFromString(row.record_json)
    }

    private fun decodeRecordOrNull(
        row: com.blockstream.data.database.wallet.WalletAbiTransactions,
    ): WalletAbiTransactionRecord? {
        return runCatching {
            decodeRecord(row)
        }.getOrNull()
    }
}
