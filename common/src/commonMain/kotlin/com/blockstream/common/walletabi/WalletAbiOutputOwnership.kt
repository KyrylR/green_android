package com.blockstream.common.walletabi

import com.blockstream.common.gdk.data.ValidateAddressees
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

internal fun walletAbiValidatedAddresseeIndicatesWalletOwnership(
    result: ValidateAddressees,
): Boolean {
    if (!result.isValid) {
        return false
    }

    return result.addressees.any { addressee ->
        addressee["is_internal"]?.let { json ->
            if ((json as? JsonPrimitive)?.booleanOrNull == true) {
                return true
            }
        }

        "pointer" in addressee ||
            "subaccount" in addressee ||
            "user_path" in addressee
    }
}
