package com.blockstream.data.walletconnect

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class WalletConnectStatus {
    Disabled,
    Disconnected,
    Connecting,
    Connected,
    Pairing,
    Paired,
    Failed
}

enum class WalletConnectApprovalKind {
    SESSION_PROPOSAL,
    SESSION_AUTHENTICATE,
    SESSION_REQUEST,
    SESSION_UPDATE
}

data class WalletConnectApprovalReview(
    val title: String,
    val subtitle: String? = null,
    val requesterName: String? = null,
    val method: String? = null,
    val chainId: String? = null,
    val verifyRisk: String? = null,
    val intent: String? = null,
    val warnings: List<String> = emptyList(),
    val details: List<WalletConnectReviewField> = emptyList(),
    val info: List<String> = emptyList(),
    val canApprove: Boolean = true,
    val approveUnavailableReason: String? = null,
    val rawJson: String
)

data class WalletConnectReviewField(
    val label: String,
    val value: String
)

data class WalletConnectApproval(
    val id: String,
    val kind: WalletConnectApprovalKind,
    val topic: String,
    val requestId: Long,
    val review: WalletConnectApprovalReview
)

data class WalletConnectSessionActionReview(
    val intent: String,
    val warnings: List<String> = emptyList(),
    val details: List<WalletConnectReviewField> = emptyList(),
    val info: List<String> = emptyList()
)

data class WalletConnectSessionReview(
    val topic: String,
    val title: String,
    val subtitle: String? = null,
    val peerName: String? = null,
    val peerUrl: String? = null,
    val expiry: Long? = null,
    val acknowledged: Boolean = false,
    val connectionState: String? = null,
    val intent: String? = null,
    val warnings: List<String> = emptyList(),
    val details: List<WalletConnectReviewField> = emptyList(),
    val info: List<String> = emptyList(),
    val disconnect: WalletConnectSessionActionReview? = null,
    val extend: WalletConnectSessionActionReview? = null,
    val rawJson: String
)

interface WalletConnectManager {
    val status: StateFlow<WalletConnectStatus>
    val pendingApprovalCount: StateFlow<Int>
    val pendingApprovals: StateFlow<List<WalletConnectApproval>>
    val activeSession: StateFlow<WalletConnectSessionReview?>
    val lastError: StateFlow<String?>

    fun start()
    fun stop()
    fun approve(approvalId: String)
    fun reject(approvalId: String)
    fun disconnectActiveSession()
    fun extendActiveSession()
}

object NoOpWalletConnectManager : WalletConnectManager {
    override val status: StateFlow<WalletConnectStatus> =
        MutableStateFlow(WalletConnectStatus.Disabled)
    override val pendingApprovalCount: StateFlow<Int> = MutableStateFlow(0)
    override val pendingApprovals: StateFlow<List<WalletConnectApproval>> =
        MutableStateFlow(emptyList())
    override val activeSession: StateFlow<WalletConnectSessionReview?> = MutableStateFlow(null)
    override val lastError: StateFlow<String?> = MutableStateFlow(null)

    override fun start() = Unit

    override fun stop() = Unit

    override fun approve(approvalId: String) = Unit

    override fun reject(approvalId: String) = Unit

    override fun disconnectActiveSession() = Unit

    override fun extendActiveSession() = Unit
}
