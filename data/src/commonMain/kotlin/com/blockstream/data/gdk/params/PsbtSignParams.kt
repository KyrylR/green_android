package com.blockstream.data.gdk.params

import com.blockstream.data.gdk.GreenJson
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class PsbtSignParams constructor(
    @SerialName("psbt")
    val psbt: String,
    @SerialName("utxos")
    val utxos: Map<String, List<JsonElement>>,
    @SerialName("blinding_nonces")
    val blindingNonces: List<String>? = null,
) : GreenJson<PsbtSignParams>() {
    override fun encodeDefaultsValues(): Boolean = false

    override fun kSerializer() = serializer()
}
