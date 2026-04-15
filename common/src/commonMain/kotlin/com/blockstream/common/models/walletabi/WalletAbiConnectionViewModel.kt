package com.blockstream.common.models.walletabi

import com.blockstream.common.data.GreenWallet
import com.blockstream.common.extensions.previewWallet
import com.blockstream.common.models.GreenViewModel
import com.blockstream.common.navigation.NavigateDestinations
import com.blockstream.common.sideeffects.SideEffects
import com.blockstream.common.utils.StringHolder
import com.blockstream.common.walletabi.WALLET_ABI_METHOD_GET_RAW_SIGNING_X_ONLY_PUBKEY
import com.blockstream.common.walletabi.WALLET_ABI_METHOD_GET_SIGNER_RECEIVE_ADDRESS
import com.blockstream.common.walletabi.WALLET_ABI_METHOD_PROCESS_REQUEST
import com.blockstream.common.walletabi.WalletAbiActionOutcome
import com.blockstream.common.walletabi.WalletAbiGetterPermission
import com.blockstream.common.walletabi.WalletAbiOverlayLook
import com.blockstream.common.walletabi.WalletAbiSessionCoordinator
import com.blockstream.common.walletabi.WalletAbiSessionUiState
import com.blockstream.common.walletabi.WalletAbiTransactCardLook
import com.blockstream.common.walletabi.toTransactCardLook
import com.blockstream.ui.navigation.NavData
import com.blockstream.ui.sideeffects.SideEffect
import com.rickclephas.kmp.observableviewmodel.MutableStateFlow
import com.rickclephas.kmp.observableviewmodel.launch
import com.rickclephas.kmp.observableviewmodel.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import org.koin.core.component.KoinComponent
import org.koin.core.component.get

data class WalletAbiConnectionSectionLook(
    val title: String,
    val lines: List<String>,
)

data class WalletAbiConnectionScreenLook(
    val card: WalletAbiTransactCardLook,
    val sections: List<WalletAbiConnectionSectionLook> = emptyList(),
    val warning: String? = null,
    val primaryActionLabel: String? = null,
    val secondaryActionLabel: String? = null,
)

abstract class WalletAbiConnectionViewModelAbstract(
    greenWallet: GreenWallet,
) : GreenViewModel(greenWalletOrNull = greenWallet) {
    override fun screenName(): String = "WalletAbiConnection"

    abstract val screen: StateFlow<WalletAbiConnectionScreenLook>
    abstract val isWorking: StateFlow<Boolean>

    abstract fun primaryAction()
    abstract fun secondaryAction()
}

class WalletAbiConnectionViewModel private constructor(
    greenWallet: GreenWallet,
    private val walletAbiSessionCoordinator: WalletAbiSessionCoordinator,
) : WalletAbiConnectionViewModelAbstract(greenWallet = greenWallet) {

    private val sessionState: StateFlow<WalletAbiSessionUiState> =
        walletAbiSessionCoordinator.state(greenWallet.id)

    private val _isWorking = MutableStateFlow(false)
    override val isWorking: StateFlow<Boolean> = _isWorking

    override val screen: StateFlow<WalletAbiConnectionScreenLook> =
        sessionState.map { it.toConnectionScreenLook() }
            .stateIn(
                viewModelScope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000L),
                initialValue = WalletAbiSessionUiState().toConnectionScreenLook(),
            )

    constructor(greenWallet: GreenWallet) : this(
        greenWallet = greenWallet,
        walletAbiSessionCoordinator = ConnectionViewModelDependencies.walletAbiSessionCoordinator(),
    )

    init {
        viewModelScope.launch {
            _navData.value = NavData(
                title = "Connected dApps",
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

    override fun primaryAction() {
        if (_isWorking.value) {
            return
        }

        val state = sessionState.value
        when (state.overlay) {
            is WalletAbiOverlayLook.TransactionApproval -> {
                postSideEffect(
                    SideEffects.NavigateTo(
                        NavigateDestinations.WalletAbiRequest(greenWallet = greenWallet),
                    ),
                )
            }

            is WalletAbiOverlayLook.ConnectionEstablished,
            is WalletAbiOverlayLook.Error,
            -> {
                viewModelScope.launch {
                    _isWorking.value = true
                    try {
                        walletAbiSessionCoordinator.dismissOverlay(greenWallet.id)
                    } finally {
                        _isWorking.value = false
                    }
                }
            }

            is WalletAbiOverlayLook.SessionProposalApproval,
            is WalletAbiOverlayLook.GetterApproval,
            -> launchOutcome {
                walletAbiSessionCoordinator.approveCurrentOverlay(greenWallet.id)
            }

            null -> {
                if (state.activeConnection != null) {
                    launchOutcome {
                        walletAbiSessionCoordinator.disconnectActiveSession(greenWallet.id)
                    }
                } else {
                    postSideEffect(SideEffects.NavigateBack())
                }
            }
        }
    }

    override fun secondaryAction() {
        if (_isWorking.value) {
            return
        }

        when (sessionState.value.overlay) {
            is WalletAbiOverlayLook.SessionProposalApproval,
            is WalletAbiOverlayLook.GetterApproval,
            is WalletAbiOverlayLook.TransactionApproval,
            -> launchOutcome {
                walletAbiSessionCoordinator.rejectCurrentOverlay(greenWallet.id)
            }

            else -> Unit
        }
    }

    private fun launchOutcome(block: suspend () -> WalletAbiActionOutcome?) {
        viewModelScope.launch {
            _isWorking.value = true
            try {
                handleOutcome(block())
            } finally {
                _isWorking.value = false
            }
        }
    }

    private fun handleOutcome(outcome: WalletAbiActionOutcome?) {
        when (outcome) {
            is WalletAbiActionOutcome.Success -> {
                postSideEffect(SideEffects.Snackbar(StringHolder.create(outcome.message)))
            }

            is WalletAbiActionOutcome.Error -> {
                postSideEffect(SideEffects.ErrorSnackbar(outcome.throwable))
            }

            null -> Unit
        }
    }

    companion object {
        internal fun withCoordinator(
            greenWallet: GreenWallet,
            walletAbiSessionCoordinator: WalletAbiSessionCoordinator,
        ): WalletAbiConnectionViewModel {
            return WalletAbiConnectionViewModel(
                greenWallet = greenWallet,
                walletAbiSessionCoordinator = walletAbiSessionCoordinator,
            )
        }
    }
}

class WalletAbiConnectionViewModelPreview private constructor(
    initialScreen: WalletAbiConnectionScreenLook,
    private val primaryEffect: SideEffect? = null,
    private val secondaryEffect: SideEffect? = null,
) : WalletAbiConnectionViewModelAbstract(greenWallet = previewWallet()) {
    override val screen: StateFlow<WalletAbiConnectionScreenLook> = MutableStateFlow(initialScreen)
    override val isWorking: StateFlow<Boolean> = MutableStateFlow(false)

    init {
        viewModelScope.launch {
            _navData.value = NavData(
                title = "Connected dApps",
                walletName = greenWallet.name,
            )
        }
    }

    override fun primaryAction() {
        when (val effect = primaryEffect) {
            null -> Unit
            else -> postSideEffect(effect)
        }
    }

    override fun secondaryAction() {
        when (val effect = secondaryEffect) {
            null -> Unit
            else -> postSideEffect(effect)
        }
    }

    companion object {
        fun preview() = WalletAbiConnectionViewModelPreview(
            initialScreen = WalletAbiSessionUiState(
                activeConnection = com.blockstream.common.walletabi.WalletAbiConnectionLook(
                    origin = "lending-contract.blockstream.com",
                    network = "testnet-liquid",
                    state = com.blockstream.common.walletabi.WalletAbiSessionState.CONNECTED,
                    approvedGetters = setOf(
                        WalletAbiGetterPermission.GET_SIGNER_RECEIVE_ADDRESS,
                    ),
                ),
            ).toConnectionScreenLook(),
        )

        fun transactionRequest() = WalletAbiConnectionViewModelPreview(
            initialScreen = WalletAbiSessionUiState(
                overlay = WalletAbiOverlayLook.TransactionApproval(
                    review = walletAbiConnectionPreviewReview(),
                ),
            ).toConnectionScreenLook(),
            primaryEffect = SideEffects.NavigateTo(
                NavigateDestinations.WalletAbiRequest(greenWallet = previewWallet()),
            ),
            secondaryEffect = SideEffects.NavigateBack(),
        )
    }
}

internal fun WalletAbiSessionUiState.toConnectionScreenLook(): WalletAbiConnectionScreenLook {
    val card = toTransactCardLook() ?: WalletAbiTransactCardLook(
        title = "Wallet ABI",
        subtitle = null,
        body = "No connected dApps for this wallet yet.",
        statusLabel = "Idle",
    )

    return when (val currentOverlay = overlay) {
        is WalletAbiOverlayLook.SessionProposalApproval -> WalletAbiConnectionScreenLook(
            card = card,
            sections = buildList {
                add(
                    WalletAbiConnectionSectionLook(
                        title = "Requested methods",
                        lines = currentOverlay.requestedMethods.map(::walletAbiMethodLabel),
                    ),
                )
                currentOverlay.autoApprovedGetters.takeIf { it.isNotEmpty() }?.also { getters ->
                    add(
                        WalletAbiConnectionSectionLook(
                            title = "Auto-approved getters",
                            lines = getters.map(::walletAbiGetterLabel),
                        ),
                    )
                }
            },
            warning = buildList {
                currentOverlay.warning?.takeIf { it.isNotBlank() }?.also(::add)
                if (currentOverlay.willReplaceExistingConnection) {
                    add("Approving this request will replace the current Wallet ABI connection for this wallet.")
                }
            }.joinToString(separator = "\n").ifBlank { null },
            primaryActionLabel = "Approve connection",
            secondaryActionLabel = "Reject",
        )

        is WalletAbiOverlayLook.ConnectionEstablished -> WalletAbiConnectionScreenLook(
            card = card,
            warning = if (currentOverlay.replacedExistingConnection) {
                "The previous Wallet ABI connection was replaced by this dApp session."
            } else {
                null
            },
            primaryActionLabel = "Done",
        )

        is WalletAbiOverlayLook.GetterApproval -> WalletAbiConnectionScreenLook(
            card = card,
            sections = listOf(
                WalletAbiConnectionSectionLook(
                    title = "Requested value",
                    lines = listOf(currentOverlay.value),
                ),
            ),
            warning = currentOverlay.warning,
            primaryActionLabel = "Approve",
            secondaryActionLabel = "Reject",
        )

        is WalletAbiOverlayLook.TransactionApproval -> WalletAbiConnectionScreenLook(
            card = card,
            sections = listOf(
                WalletAbiConnectionSectionLook(
                    title = "Review",
                    lines = buildList {
                        add(currentOverlay.review.statusMessage)
                        currentOverlay.review.impactAssets.map { it.sentAway }
                            .filter { it.isNotBlank() }
                            .take(2)
                            .forEach(::add)
                    },
                ),
            ),
            primaryActionLabel = "Review request",
            secondaryActionLabel = "Reject",
        )

        is WalletAbiOverlayLook.Error -> WalletAbiConnectionScreenLook(
            card = card,
            primaryActionLabel = "Dismiss",
        )

        null -> WalletAbiConnectionScreenLook(
            card = card,
            sections = activeConnection?.approvedGetters?.takeIf { it.isNotEmpty() }?.let { getters ->
                listOf(
                    WalletAbiConnectionSectionLook(
                        title = "Approved getters",
                        lines = getters.map(::walletAbiGetterLabel),
                    ),
                )
            } ?: emptyList(),
            primaryActionLabel = if (activeConnection != null) {
                "Disconnect"
            } else {
                "Back"
            },
        )
    }
}

private object ConnectionViewModelDependencies : KoinComponent {
    fun walletAbiSessionCoordinator(): WalletAbiSessionCoordinator = get()
}

private fun walletAbiMethodLabel(method: String): String {
    return when (method) {
        WALLET_ABI_METHOD_PROCESS_REQUEST -> "Process Wallet ABI request"
        WALLET_ABI_METHOD_GET_SIGNER_RECEIVE_ADDRESS -> walletAbiGetterLabel(
            WalletAbiGetterPermission.GET_SIGNER_RECEIVE_ADDRESS,
        )
        WALLET_ABI_METHOD_GET_RAW_SIGNING_X_ONLY_PUBKEY -> walletAbiGetterLabel(
            WalletAbiGetterPermission.GET_RAW_SIGNING_X_ONLY_PUBKEY,
        )
        else -> method
    }
}

private fun walletAbiGetterLabel(permission: WalletAbiGetterPermission): String {
    return when (permission) {
        WalletAbiGetterPermission.GET_SIGNER_RECEIVE_ADDRESS -> "Share receive address"
        WalletAbiGetterPermission.GET_RAW_SIGNING_X_ONLY_PUBKEY -> "Share x-only signing pubkey"
    }
}

private fun walletAbiConnectionPreviewReview() = com.blockstream.common.walletabi.WalletAbiTransactionReviewLook(
    origin = "lending-contract.blockstream.com",
    requestId = "preview-request",
    network = "testnet-liquid",
    broadcast = true,
    resolutionState = com.blockstream.common.walletabi.WalletAbiResolutionState.REQUIRED,
    accountOptions = listOf(
        com.blockstream.common.walletabi.WalletAbiAccountOptionLook(
            id = "default-account",
            name = "Liquid account",
        ),
    ),
    selectedAccountId = "default-account",
    selectedAccountName = "Liquid account",
    inputs = emptyList(),
    outputs = emptyList(),
    impactAssets = listOf(
        com.blockstream.common.walletabi.WalletAbiImpactAssetLook(
            assetId = "btc",
            assetLabel = "Liquid Bitcoin",
            sentAway = "1 L-BTC",
            sentBackToWallet = "0.4999 L-BTC",
        ),
    ),
    exactImpactState = com.blockstream.common.walletabi.WalletAbiExactImpactState.REQUEST_DERIVED,
    inputSourceSummary = com.blockstream.common.walletabi.WalletAbiInputSourceSummaryLook(
        walletSelectedInputCount = 1,
        explicitExternalInputCount = 0,
        otherInputCount = 0,
    ),
    statusMessage = "Resolve transaction to review final asset ids",
    warnings = emptyList(),
)
