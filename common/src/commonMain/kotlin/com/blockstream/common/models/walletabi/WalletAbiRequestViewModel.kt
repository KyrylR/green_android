package com.blockstream.common.models.walletabi

import androidx.lifecycle.viewModelScope
import com.blockstream.common.data.GreenWallet
import com.blockstream.common.utils.StringHolder
import com.blockstream.common.walletabi.WalletAbiActionOutcome
import com.blockstream.common.walletabi.WalletAbiAccountOptionLook
import com.blockstream.common.walletabi.WalletAbiExactImpactState
import com.blockstream.common.walletabi.WalletAbiImpactAssetLook
import com.blockstream.common.walletabi.WalletAbiInputReviewLook
import com.blockstream.common.walletabi.WalletAbiInputSourceSummaryLook
import com.blockstream.common.walletabi.WalletAbiOutputClassification
import com.blockstream.common.walletabi.WalletAbiOutputReviewLook
import com.blockstream.common.walletabi.WalletAbiResolutionState
import com.blockstream.common.walletabi.WalletAbiSessionCoordinator
import com.blockstream.common.walletabi.WalletAbiSessionUiState
import com.blockstream.common.walletabi.WalletAbiTransactionReviewLook
import com.blockstream.compose.extensions.previewWallet
import com.blockstream.compose.models.GreenViewModel
import com.blockstream.compose.navigation.NavData
import com.blockstream.compose.sideeffects.SideEffects
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.get

data class WalletAbiRequestAccountLook(
    val id: String,
    val name: String,
    val selected: Boolean,
)

data class WalletAbiRequestInputLook(
    val label: String,
    val detail: String,
)

data class WalletAbiRequestOutputLook(
    val label: String,
    val amount: String,
    val detail: String,
)

data class WalletAbiRequestImpactLook(
    val assetLabel: String,
    val sentAway: String,
    val sentBackToWallet: String,
    val exactFee: String? = null,
    val exactNetWalletDelta: String? = null,
)

data class WalletAbiRequestLook(
    val origin: String,
    val network: String,
    val statusMessage: String,
    val isBroadcast: Boolean,
    val requiresResolution: Boolean,
    val accountOptions: List<WalletAbiRequestAccountLook>,
    val selectedAccountId: String,
    val inputs: List<WalletAbiRequestInputLook>,
    val outputs: List<WalletAbiRequestOutputLook>,
    val impactAssets: List<WalletAbiRequestImpactLook>,
    val warnings: List<String>,
)

abstract class WalletAbiRequestViewModelAbstract(
    greenWallet: GreenWallet,
) : GreenViewModel(greenWalletOrNull = greenWallet) {
    override fun screenName(): String = "WalletAbiRequest"

    abstract val review: StateFlow<WalletAbiRequestLook?>
    abstract val isResolving: StateFlow<Boolean>
    abstract val isApproving: StateFlow<Boolean>

    abstract fun selectAccount(accountId: String)
    abstract fun resolveTransaction()
    abstract fun approveTransaction()
    abstract fun rejectTransaction()

    protected fun postApprovalSuccess(success: WalletAbiSuccessLook) {
        postSideEffect(SideEffects.Success(success))
        postSideEffect(SideEffects.NavigateBack())
    }

    protected fun postCompletedAction(message: String) {
        postSideEffect(SideEffects.Snackbar(StringHolder.create(message)))
        postSideEffect(SideEffects.NavigateBack())
    }
}

class WalletAbiRequestViewModel private constructor(
    greenWallet: GreenWallet,
    private val walletAbiSessionCoordinator: WalletAbiSessionCoordinator,
) : WalletAbiRequestViewModelAbstract(greenWallet = greenWallet) {

    private val _isResolving = MutableStateFlow(false)
    override val isResolving: StateFlow<Boolean> = _isResolving

    private val _isApproving = MutableStateFlow(false)
    override val isApproving: StateFlow<Boolean> = _isApproving

    override val review: StateFlow<WalletAbiRequestLook?> =
        walletAbiSessionCoordinator.state(greenWallet.id)
            .map { state ->
                transactionReviewOrNull(state)?.toRequestLook()
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), null)

    constructor(greenWallet: GreenWallet) : this(
        greenWallet = greenWallet,
        walletAbiSessionCoordinator = RequestViewModelDependencies.walletAbiSessionCoordinator(),
    )

    init {
        viewModelScope.launch {
            _navData.value = NavData(
                title = "Confirm contract",
                walletName = greenWallet.name,
            )
        }

        viewModelScope.launch {
            walletAbiSessionCoordinator.bind(
                greenWallet = greenWallet,
                session = session,
            )
        }

        bootstrap()
    }

    override fun selectAccount(accountId: String) {
        if (accountId.isBlank() || accountId == review.value?.selectedAccountId) {
            return
        }

        viewModelScope.launch {
            walletAbiSessionCoordinator.selectTransactionAccount(
                walletId = greenWallet.id,
                accountId = accountId,
            )
        }
    }

    override fun resolveTransaction() {
        if (_isResolving.value || _isApproving.value) {
            return
        }

        viewModelScope.launch {
            _isResolving.value = true
            try {
                handleOutcome(
                    walletAbiSessionCoordinator.resolveCurrentTransaction(greenWallet.id),
                    navigateBack = false,
                )
            } finally {
                _isResolving.value = false
            }
        }
    }

    override fun approveTransaction() {
        if (_isResolving.value || _isApproving.value) {
            return
        }

        viewModelScope.launch {
            val currentReview = review.value
            _isApproving.value = true
            try {
                handleOutcome(
                    walletAbiSessionCoordinator.approveCurrentTransaction(greenWallet.id),
                    navigateBack = true,
                    success = currentReview,
                )
            } finally {
                _isApproving.value = false
            }
        }
    }

    override fun rejectTransaction() {
        if (_isResolving.value || _isApproving.value) {
            return
        }

        viewModelScope.launch {
            _isApproving.value = true
            try {
                handleOutcome(
                    walletAbiSessionCoordinator.rejectCurrentTransaction(greenWallet.id),
                    navigateBack = true,
                )
            } finally {
                _isApproving.value = false
            }
        }
    }

    private fun handleOutcome(
        outcome: WalletAbiActionOutcome,
        navigateBack: Boolean,
        success: WalletAbiRequestLook? = null,
    ) {
        when (outcome) {
            is WalletAbiActionOutcome.Success -> {
                val successLook = success?.toSuccessLook(outcome.message)
                if (successLook != null) {
                    postApprovalSuccess(successLook)
                } else if (navigateBack) {
                    postCompletedAction(outcome.message)
                } else {
                    postSideEffect(SideEffects.Snackbar(StringHolder.create(outcome.message)))
                }
            }

            is WalletAbiActionOutcome.Error -> {
                postSideEffect(SideEffects.ErrorSnackbar(outcome.throwable))
            }
        }
    }

    companion object {
        internal fun withCoordinator(
            greenWallet: GreenWallet,
            walletAbiSessionCoordinator: WalletAbiSessionCoordinator,
        ): WalletAbiRequestViewModel {
            return WalletAbiRequestViewModel(
                greenWallet = greenWallet,
                walletAbiSessionCoordinator = walletAbiSessionCoordinator,
            )
        }
    }
}

class WalletAbiRequestViewModelPreview :
    WalletAbiRequestViewModelAbstract(greenWallet = previewWallet()) {
    private val _review = MutableStateFlow(sampleWalletAbiTransactionReviewLook().toRequestLook())
    override val review: StateFlow<WalletAbiRequestLook?> = _review

    private val _isResolving = MutableStateFlow(false)
    override val isResolving: StateFlow<Boolean> = _isResolving

    private val _isApproving = MutableStateFlow(false)
    override val isApproving: StateFlow<Boolean> = _isApproving

    init {
        viewModelScope.launch {
            _navData.value = NavData(
                title = "Confirm contract",
                walletName = greenWallet.name,
            )
        }
    }

    override fun selectAccount(accountId: String) {
        val currentReview = _review.value ?: return
        if (currentReview.accountOptions.none { it.id == accountId }) {
            return
        }
        _review.value = currentReview.copy(
            selectedAccountId = accountId,
            accountOptions = currentReview.accountOptions.map { option ->
                option.copy(selected = option.id == accountId)
            },
        )
    }

    override fun resolveTransaction() {
        val currentReview = _review.value ?: return
        if (!currentReview.requiresResolution) {
            return
        }

        _review.value = currentReview.copy(
            requiresResolution = false,
            statusMessage = "Exact transaction review is ready",
        )
    }

    override fun approveTransaction() {
        _review.value?.toSuccessLook("Wallet ABI request approved")?.also(::postApprovalSuccess)
    }

    override fun rejectTransaction() {
        postCompletedAction("Wallet ABI request rejected")
    }

    companion object {
        fun preview() = WalletAbiRequestViewModelPreview()
    }
}

private fun transactionReviewOrNull(
    state: WalletAbiSessionUiState,
): WalletAbiTransactionReviewLook? {
    return (state.overlay as? com.blockstream.common.walletabi.WalletAbiOverlayLook.TransactionApproval)?.review
}

private fun WalletAbiTransactionReviewLook.toRequestLook(): WalletAbiRequestLook {
    return WalletAbiRequestLook(
        origin = origin,
        network = network,
        statusMessage = statusMessage,
        isBroadcast = broadcast,
        requiresResolution = resolutionState == WalletAbiResolutionState.REQUIRED,
        accountOptions = accountOptions.map { option ->
            WalletAbiRequestAccountLook(
                id = option.id,
                name = option.name,
                selected = option.id == selectedAccountId,
            )
        },
        selectedAccountId = selectedAccountId,
        inputs = inputs.map { input ->
            WalletAbiRequestInputLook(
                label = input.label,
                detail = input.detail,
            )
        },
        outputs = outputs.map { output ->
            WalletAbiRequestOutputLook(
                label = output.label,
                amount = output.amount,
                detail = output.detail,
            )
        },
        impactAssets = impactAssets.map { asset ->
            WalletAbiRequestImpactLook(
                assetLabel = asset.assetLabel,
                sentAway = asset.sentAway,
                sentBackToWallet = asset.sentBackToWallet,
                exactFee = asset.exactFee,
                exactNetWalletDelta = asset.exactNetWalletDelta,
            )
        },
        warnings = warnings,
    )
}

private fun WalletAbiRequestLook.toSuccessLook(message: String): WalletAbiSuccessLook? {
    if (message == "Wallet ABI request expired" || message == "Wallet ABI request rejected") {
        return null
    }

    val reference = message.substringAfter(": ", missingDelimiterValue = "")
        .takeIf { message.startsWith("Wallet ABI transaction broadcast:") && it.isNotBlank() }
    val title = if (isBroadcast) {
        "Contract confirmed"
    } else {
        "Signature confirmed"
    }
    val successMessage = if (isBroadcast) {
        "The Wallet ABI request from $origin was approved and submitted to the network."
    } else {
        "The Wallet ABI request from $origin was approved and signed successfully."
    }

    return WalletAbiSuccessLook(
        title = title,
        message = successMessage,
        reference = reference,
        shareText = buildList {
            add(title)
            add(successMessage)
            reference?.also { add(it) }
        }.joinToString(separator = "\n"),
    )
}

private object RequestViewModelDependencies : KoinComponent {
    fun walletAbiSessionCoordinator(): WalletAbiSessionCoordinator = get()
}

private fun sampleWalletAbiTransactionReviewLook(): WalletAbiTransactionReviewLook {
    return WalletAbiTransactionReviewLook(
        origin = "lending-contract.blockstream.com",
        requestId = "preview-request",
        network = "testnet-liquid",
        broadcast = true,
        resolutionState = WalletAbiResolutionState.REQUIRED,
        accountOptions = listOf(
            WalletAbiAccountOptionLook(id = "account-1", name = "Default account"),
            WalletAbiAccountOptionLook(id = "account-2", name = "Trading account"),
        ),
        selectedAccountId = "account-1",
        selectedAccountName = "Default account",
        inputs = listOf(
            WalletAbiInputReviewLook(
                label = "Input 1",
                detail = "Wallet-selected input",
            ),
        ),
        outputs = listOf(
            WalletAbiOutputReviewLook(
                label = "Output 1",
                amount = "1.00000000 L-BTC",
                detail = "External destination",
                classification = WalletAbiOutputClassification.EXTERNAL,
            ),
            WalletAbiOutputReviewLook(
                label = "Output 2",
                amount = "0.01000000 L-BTC",
                detail = "Wallet-controlled output",
                classification = WalletAbiOutputClassification.WALLET_RECEIVE,
            ),
        ),
        impactAssets = listOf(
            WalletAbiImpactAssetLook(
                assetId = "asset-1",
                assetLabel = "Liquid Bitcoin",
                sentAway = "1.00000000 L-BTC",
                sentBackToWallet = "0.01000000 L-BTC",
                exactFee = "0.00000123 L-BTC",
                exactNetWalletDelta = "-0.99000123 L-BTC",
            ),
            WalletAbiImpactAssetLook(
                assetId = "asset-2",
                assetLabel = "USDT",
                sentAway = "0 USDT",
                sentBackToWallet = "50,000 USDT",
                exactNetWalletDelta = "+50,000 USDT",
            ),
        ),
        exactImpactState = WalletAbiExactImpactState.PENDING,
        inputSourceSummary = WalletAbiInputSourceSummaryLook(
            walletSelectedInputCount = 1,
            explicitExternalInputCount = 0,
            otherInputCount = 0,
        ),
        statusMessage = "Resolve the transaction to review the exact final asset ids before approval.",
        warnings = listOf(
            "The request will sign and broadcast the resolved transaction.",
        ),
    )
}
