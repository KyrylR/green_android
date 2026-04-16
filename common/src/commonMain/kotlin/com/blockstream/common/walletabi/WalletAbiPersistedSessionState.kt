package com.blockstream.common.walletabi

import kotlinx.serialization.Serializable

@Serializable
 data class WalletAbiPersistedSessionState(
    val topic: String,
    val chainId: String,
    val originHint: String? = null,
    val approvedGetters: Set<WalletAbiGetterPermission> = emptySet(),
)
