package com.blockstream.common.walletabi

import kotlinx.serialization.Serializable

@Serializable
internal data class WalletAbiPersistedSessionState(
    val topic: String,
    val chainId: String,
    val originHint: String? = null,
    val approvedGetters: Set<WalletAbiGetterPermission> = emptySet(),
)
