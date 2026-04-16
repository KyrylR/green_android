package com.blockstream.common.walletabi

import com.blockstream.common.gdk.GdkSession
import com.blockstream.common.gdk.data.Account
import com.blockstream.common.utils.toAmountLook
import com.blockstream.common.walletabi.transport.WalletAbiInputSchema
import com.blockstream.common.walletabi.transport.WalletAbiOutputSchema
import com.blockstream.common.walletabi.transport.WalletAbiTxCreateRequest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@Serializable
data class WalletAbiAccountOptionLook(
    val id: String,
    val name: String,
)

@Serializable
data class WalletAbiInputReviewLook(
    val label: String,
    val detail: String,
)

@Serializable
data class WalletAbiInputSourceSummaryLook(
    val walletSelectedInputCount: Int,
    val explicitExternalInputCount: Int,
    val otherInputCount: Int,
) {
    val hasExplicitExternalInputs: Boolean
        get() = explicitExternalInputCount > 0
}

@Serializable
enum class WalletAbiExactImpactState {
    REQUEST_DERIVED,
    PENDING,
    READY,
    UNAVAILABLE,
}

@Serializable
enum class WalletAbiResolutionState {
    NOT_REQUIRED,
    REQUIRED,
    RESOLVED,
}

@Serializable
data class WalletAbiImpactAssetLook(
    val assetId: String,
    val assetLabel: String,
    val sentAway: String,
    val sentBackToWallet: String,
    val otherOutputs: String? = null,
    val exactFee: String? = null,
    val exactNetWalletDelta: String? = null,
)

@Serializable
data class WalletAbiCompactImpactSummaryLook(
    val sentAway: String,
    val sentBackToWallet: String,
    val additionalAssetCount: Int,
)

@Serializable
data class WalletAbiOutputReviewLook(
    val label: String,
    val amount: String,
    val detail: String,
    val classification: WalletAbiOutputClassification,
)

@Serializable
data class WalletAbiTransactionReviewLook(
    val origin: String,
    val requestId: String,
    val network: String,
    val broadcast: Boolean,
    val resolutionState: WalletAbiResolutionState,
    val accountOptions: List<WalletAbiAccountOptionLook>,
    val selectedAccountId: String,
    val selectedAccountName: String,
    val inputs: List<WalletAbiInputReviewLook>,
    val outputs: List<WalletAbiOutputReviewLook>,
    val impactAssets: List<WalletAbiImpactAssetLook>,
    val exactImpactState: WalletAbiExactImpactState,
    val inputSourceSummary: WalletAbiInputSourceSummaryLook,
    val statusMessage: String,
    val warnings: List<String>,
)

data class WalletAbiOutputImpactTotals(
    val assetId: String,
    val sentAwaySat: Long,
    val sentBackToWalletSat: Long,
    val otherOutputsSat: Long,
)

data class WalletAbiResolvedOutputReview(
    val assetId: String,
    val amountSat: Long,
    val classification: WalletAbiOutputClassification,
    val detail: String,
)

 suspend fun walletAbiOutputDetail(
    output: WalletAbiOutputSchema,
    classification: WalletAbiOutputClassification,
): String {
    val destination = walletAbiOutputDestination(output)
    return when (classification) {
        WalletAbiOutputClassification.WALLET_RECEIVE -> "Wallet-controlled output (change / self-transfer)"
        WalletAbiOutputClassification.OP_RETURN -> "OP_RETURN or burn-like output"
        WalletAbiOutputClassification.UNKNOWN -> "Unknown output: $destination"

        WalletAbiOutputClassification.EXTERNAL -> destination
    }
}

 suspend fun walletAbiOutputDestination(output: WalletAbiOutputSchema): String {
    return when (output.lock.jsonObject["type"]?.jsonPrimitive?.content) {
        "script" -> {
            val script = output.lock.jsonObject["script"]?.jsonPrimitive?.content
            script?.takeIf { it.isNotBlank() }?.let { "Script ${it.take(20)}" } ?: "Script output"
        }

        "finalizer" -> {
            val finalizerType = output.lock.jsonObject["finalizer"]?.jsonObject?.get("type")
                ?.jsonPrimitive
                ?.content
            "Finalizer ${finalizerType ?: "unknown"}"
        }

        else -> "Unknown destination"
    }
}

 suspend fun formatWalletAbiReviewAmount(
    session: GdkSession,
    amountSat: Long,
    assetId: String,
    account: Account,
): String {
    return amountSat.toAmountLook(
        session = session,
        assetId = assetId,
        withUnit = true,
    ) ?: fallbackWalletAbiReviewAmount(
        amountSat = amountSat,
        assetId = assetId,
        account = account,
        assetTicker = runCatching { session.getAsset(assetId)?.ticker }.getOrNull(),
    )
}

 fun resolveWalletAbiAssetLabel(
    session: GdkSession?,
    assetId: String,
    account: Account,
): String {
    return fallbackWalletAbiAssetLabel(
        assetId = assetId,
        account = account,
        assetTicker = runCatching { session?.getAsset(assetId)?.ticker }.getOrNull(),
    )
}

 fun buildWalletAbiInputSourceSummary(
    inputs: List<WalletAbiInputSchema>,
): WalletAbiInputSourceSummaryLook {
    var walletSelectedCount = 0
    var explicitExternalCount = 0
    var otherCount = 0

    inputs.forEach { input ->
        when (input.utxoSource.jsonObject["type"]?.jsonPrimitive?.content ?: "unknown") {
            "wallet" -> walletSelectedCount += 1
            "outpoint" -> explicitExternalCount += 1
            else -> otherCount += 1
        }
    }

    return WalletAbiInputSourceSummaryLook(
        walletSelectedInputCount = walletSelectedCount,
        explicitExternalInputCount = explicitExternalCount,
        otherInputCount = otherCount,
    )
}

 fun aggregateWalletAbiOutputImpacts(
    outputs: List<WalletAbiOutputSchema>,
    policyAssetId: String,
): List<WalletAbiOutputImpactTotals> {
    data class MutableTotals(
        var sentAwaySat: Long = 0L,
        var sentBackToWalletSat: Long = 0L,
        var otherOutputsSat: Long = 0L,
    )

    val orderedAssetIds = mutableListOf<String>()
    val totalsByAssetId = mutableMapOf<String, MutableTotals>()

    outputs.forEach { output ->
        val assetId = resolveWalletAbiAssetId(output.asset)
        val totals = totalsByAssetId.getOrPut(assetId) {
            orderedAssetIds += assetId
            MutableTotals()
        }

        when (classifyWalletAbiOutput(output)) {
            WalletAbiOutputClassification.EXTERNAL -> totals.sentAwaySat += output.amountSat
            WalletAbiOutputClassification.WALLET_RECEIVE -> totals.sentBackToWalletSat += output.amountSat
            WalletAbiOutputClassification.OP_RETURN,
            WalletAbiOutputClassification.UNKNOWN,
            -> totals.otherOutputsSat += output.amountSat
        }
    }

    return reorderWalletAbiImpactTotals(
        orderedAssetIds = orderedAssetIds,
        policyAssetId = policyAssetId,
    ) { assetId ->
        val totals = totalsByAssetId.getValue(assetId)
        WalletAbiOutputImpactTotals(
            assetId = assetId,
            sentAwaySat = totals.sentAwaySat,
            sentBackToWalletSat = totals.sentBackToWalletSat,
            otherOutputsSat = totals.otherOutputsSat,
        )
    }
}

 fun aggregateWalletAbiResolvedOutputImpacts(
    outputs: List<WalletAbiResolvedOutputReview>,
    policyAssetId: String,
): List<WalletAbiOutputImpactTotals> {
    data class MutableTotals(
        var sentAwaySat: Long = 0L,
        var sentBackToWalletSat: Long = 0L,
        var otherOutputsSat: Long = 0L,
    )

    val orderedAssetIds = mutableListOf<String>()
    val totalsByAssetId = mutableMapOf<String, MutableTotals>()

    outputs.forEach { output ->
        val totals = totalsByAssetId.getOrPut(output.assetId) {
            orderedAssetIds += output.assetId
            MutableTotals()
        }

        when (output.classification) {
            WalletAbiOutputClassification.EXTERNAL -> totals.sentAwaySat += output.amountSat
            WalletAbiOutputClassification.WALLET_RECEIVE -> totals.sentBackToWalletSat += output.amountSat
            WalletAbiOutputClassification.OP_RETURN,
            WalletAbiOutputClassification.UNKNOWN,
            -> totals.otherOutputsSat += output.amountSat
        }
    }

    return reorderWalletAbiImpactTotals(
        orderedAssetIds = orderedAssetIds,
        policyAssetId = policyAssetId,
    ) { assetId ->
        val totals = totalsByAssetId.getValue(assetId)
        WalletAbiOutputImpactTotals(
            assetId = assetId,
            sentAwaySat = totals.sentAwaySat,
            sentBackToWalletSat = totals.sentBackToWalletSat,
            otherOutputsSat = totals.otherOutputsSat,
        )
    }
}

 fun walletAbiCompactImpactSummary(
    review: WalletAbiTransactionReviewLook,
): WalletAbiCompactImpactSummaryLook? {
    val primaryAsset = review.impactAssets.firstOrNull() ?: return null
    return WalletAbiCompactImpactSummaryLook(
        sentAway = primaryAsset.sentAway,
        sentBackToWallet = primaryAsset.sentBackToWallet,
        additionalAssetCount = (review.impactAssets.size - 1).coerceAtLeast(0),
    )
}

 suspend fun walletAbiStatusMessage(
    previewResult: WalletAbiImpactPreviewResult,
    resolutionState: WalletAbiResolutionState,
): String {
    if (resolutionState == WalletAbiResolutionState.REQUIRED) {
        return "Resolve the transaction to review final asset ids"
    }

    return previewResult.statusMessage ?: when (previewResult.state) {
        WalletAbiExactImpactState.READY -> "Exact balance impact available"
        WalletAbiExactImpactState.PENDING -> "Preparing exact balance impact"

        WalletAbiExactImpactState.REQUEST_DERIVED,
        WalletAbiExactImpactState.UNAVAILABLE,
        -> "Balance impact derived from request; the final fee may still change"
    }
}

 suspend fun buildWalletAbiTransactionReview(
    origin: String,
    txRequest: WalletAbiTxCreateRequest,
    accounts: List<Account>,
    selectedAccount: Account,
    resolutionState: WalletAbiResolutionState,
    amountFormatter: suspend (amountSat: Long, assetId: String, account: Account) -> String,
    assetLabelResolver: (assetId: String, account: Account) -> String,
    exactImpactPreview: WalletAbiImpactPreviewResult,
    walletOwnedDestinationDetector: suspend (WalletAbiOutputSchema, Account) -> Boolean = { _, _ -> false },
): WalletAbiTransactionReviewLook {
    val resolvedOutputs = txRequest.params.outputs.map { output ->
        val assetId = resolveWalletAbiAssetId(output.asset)
        val classification = resolveWalletAbiOutputClassification(output) {
            walletOwnedDestinationDetector(it, selectedAccount)
        }
        WalletAbiResolvedOutputReview(
            assetId = assetId,
            amountSat = output.amountSat,
            classification = classification,
            detail = walletAbiOutputDetail(output, classification),
        )
    }

    val outputs = resolvedOutputs.mapIndexed { index, output ->
        WalletAbiOutputReviewLook(
            label = "Output ${index + 1}",
            amount = amountFormatter(output.amountSat, output.assetId, selectedAccount),
            detail = output.detail,
            classification = output.classification,
        )
    }

    val impactAssets = aggregateWalletAbiResolvedOutputImpacts(
        outputs = resolvedOutputs,
        policyAssetId = selectedAccount.network.policyAsset,
    ).map { totals ->
        WalletAbiImpactAssetLook(
            assetId = totals.assetId,
            assetLabel = assetLabelResolver(totals.assetId, selectedAccount),
            sentAway = amountFormatter(totals.sentAwaySat, totals.assetId, selectedAccount),
            sentBackToWallet = amountFormatter(totals.sentBackToWalletSat, totals.assetId, selectedAccount),
            otherOutputs = totals.otherOutputsSat.takeIf { it > 0L }?.let { amount ->
                amountFormatter(amount, totals.assetId, selectedAccount)
            },
            exactFee = exactImpactPreview.exactFeesByAssetId[totals.assetId],
            exactNetWalletDelta = exactImpactPreview.exactNetDeltasByAssetId[totals.assetId],
        )
    }

    val inputSourceSummary = buildWalletAbiInputSourceSummary(txRequest.params.inputs)

    return WalletAbiTransactionReviewLook(
        origin = origin,
        requestId = txRequest.requestId,
        network = txRequest.network.serialValue(),
        broadcast = txRequest.broadcast,
        resolutionState = resolutionState,
        accountOptions = accounts.map { account ->
            WalletAbiAccountOptionLook(
                id = account.id,
                name = account.name,
            )
        },
        selectedAccountId = selectedAccount.id,
        selectedAccountName = selectedAccount.name,
        inputs = txRequest.params.inputs.mapIndexed { index, input ->
            WalletAbiInputReviewLook(
                label = "Input ${index + 1}",
                detail = describeWalletAbiInput(input),
            )
        },
        outputs = outputs,
        impactAssets = impactAssets,
        exactImpactState = exactImpactPreview.state,
        inputSourceSummary = inputSourceSummary,
        statusMessage = walletAbiStatusMessage(
            previewResult = exactImpactPreview,
            resolutionState = resolutionState,
        ),
        warnings = buildWalletAbiWarnings(
            txRequest = txRequest,
            outputs = outputs,
            inputSourceSummary = inputSourceSummary,
            resolutionState = resolutionState,
        ),
    )
}

 suspend fun buildWalletAbiWarnings(
    txRequest: WalletAbiTxCreateRequest,
    outputs: List<WalletAbiOutputReviewLook>,
    inputSourceSummary: WalletAbiInputSourceSummaryLook,
    resolutionState: WalletAbiResolutionState,
): List<String> {
    val warnings = mutableListOf<String>()

    warnings += if (resolutionState == WalletAbiResolutionState.REQUIRED) {
        "Resolve the transaction to review final asset ids"
    } else if (txRequest.broadcast) {
        if (resolutionState == WalletAbiResolutionState.RESOLVED) {
            "Broadcasting will submit the resolved transaction"
        } else {
            "Request will sign and broadcast the transaction"
        }
    } else {
        if (resolutionState == WalletAbiResolutionState.RESOLVED) {
            "Approving will return the resolved transaction"
        } else {
            "Request will sign but not broadcast"
        }
    }

    if (outputs.any { it.classification == WalletAbiOutputClassification.OP_RETURN }) {
        warnings += "Request contains OP_RETURN output"
    }
    if (outputs.count { it.classification == WalletAbiOutputClassification.EXTERNAL } > 1) {
        warnings += "Request pays multiple external outputs"
    }
    if (outputs.any { it.classification == WalletAbiOutputClassification.UNKNOWN }) {
        warnings += "Output could not be classified safely"
    }
    if (inputSourceSummary.hasExplicitExternalInputs) {
        warnings += "Request includes explicit external inputs; balance impact was not inferred"
    }

    return warnings.distinct()
}

 suspend fun describeWalletAbiInput(input: WalletAbiInputSchema): String {
    val source = input.utxoSource.jsonObject["type"]?.jsonPrimitive?.content ?: "unknown"
    return when (source) {
        "wallet" -> "Wallet-selected input ${input.id}"
        "outpoint" -> "Specific external outpoint ${input.id}"
        else -> "Input ${input.id} ($source)"
    }
}

 fun walletAbiOutputSectionTitle(classification: WalletAbiOutputClassification): String {
    return when (classification) {
        WalletAbiOutputClassification.EXTERNAL -> "Sent away"
        WalletAbiOutputClassification.WALLET_RECEIVE -> "Sent back to wallet"
        WalletAbiOutputClassification.OP_RETURN,
        WalletAbiOutputClassification.UNKNOWN,
        -> "Other outputs"
    }
}

 fun walletAbiOutputClassificationLabel(classification: WalletAbiOutputClassification): String {
    return when (classification) {
        WalletAbiOutputClassification.EXTERNAL -> "External destination"
        WalletAbiOutputClassification.WALLET_RECEIVE -> "Wallet-controlled output (change / self-transfer)"
        WalletAbiOutputClassification.OP_RETURN -> "OP_RETURN or burn-like"
        WalletAbiOutputClassification.UNKNOWN -> "Unknown destination"
    }
}

private fun reorderWalletAbiImpactTotals(
    orderedAssetIds: List<String>,
    policyAssetId: String,
    build: (assetId: String) -> WalletAbiOutputImpactTotals,
): List<WalletAbiOutputImpactTotals> {
    val reorderedAssetIds = buildList {
        orderedAssetIds.firstOrNull { it == policyAssetId }?.let(::add)
        orderedAssetIds.filterNot { it == policyAssetId }.forEach(::add)
    }
    return reorderedAssetIds.map(build)
}

private fun fallbackWalletAbiReviewAmount(
    amountSat: Long,
    assetId: String,
    account: Account,
    assetTicker: String?,
): String {
    val assetLabel = fallbackWalletAbiAssetLabel(
        assetId = assetId,
        account = account,
        assetTicker = assetTicker,
    )
    return if (assetId == account.network.policyAsset) {
        val whole = amountSat / 100_000_000L
        val fraction = (amountSat % 100_000_000L).toString().padStart(8, '0').trimEnd('0')
        if (fraction.isBlank()) {
            "$whole $assetLabel"
        } else {
            "$whole.$fraction $assetLabel"
        }
    } else {
        "$amountSat $assetLabel"
    }
}

private fun fallbackWalletAbiAssetLabel(
    assetId: String,
    account: Account,
    assetTicker: String?,
): String {
    return if (assetId == account.network.policyAsset) {
        if (account.network.isMainnet) "LBTC" else "tLBTC"
    } else {
        assetTicker?.takeIf { it.isNotBlank() } ?: assetId.take(8)
    }
}
