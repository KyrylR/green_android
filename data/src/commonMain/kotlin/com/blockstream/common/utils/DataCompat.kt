package com.blockstream.common.utils

import com.blockstream.data.data.Denomination
import com.blockstream.data.gdk.GdkSession
import com.blockstream.data.gdk.data.Balance
import com.blockstream.data.utils.toAmountLook as dataToAmountLook

fun Balance?.toAmountLook(
    session: GdkSession,
    assetId: String? = null,
    withUnit: Boolean = true,
    withGrouping: Boolean = true,
    withMinimumDigits: Boolean = false,
    denomination: Denomination? = null,
): String? {
    return this.dataToAmountLook(
        session = session,
        assetId = assetId,
        withUnit = withUnit,
        withGrouping = withGrouping,
        withMinimumDigits = withMinimumDigits,
        denomination = denomination,
    )
}

suspend fun Long?.toAmountLook(
    session: GdkSession,
    assetId: String? = null,
    withUnit: Boolean = true,
    withGrouping: Boolean = true,
    withDirection: Boolean = false,
    withMinimumDigits: Boolean = false,
    denomination: Denomination? = null,
): String? {
    return this.dataToAmountLook(
        session = session,
        assetId = assetId,
        withUnit = withUnit,
        withGrouping = withGrouping,
        withDirection = withDirection,
        withMinimumDigits = withMinimumDigits,
        denomination = denomination,
    )
}
