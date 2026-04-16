package com.blockstream.common.extensions

import com.blockstream.data.data.GreenWallet

fun previewWallet(
    isHardware: Boolean = false,
    isWatchOnly: Boolean = false,
    isEphemeral: Boolean = false,
): GreenWallet {
    return com.blockstream.compose.extensions.previewWallet(
        isHardware = isHardware,
        isWatchOnly = isWatchOnly,
        isEphemeral = isEphemeral,
    )
}
