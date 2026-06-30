package com.blockstream.data.data

import com.blockstream.data.extensions.isNotBlank
import com.blockstream.data.gdk.GreenJson
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okio.internal.commonToUtf8String
import kotlin.io.encoding.Base64

@Serializable
data class AppKeys(
    @SerialName("greenlight_key")
    val greenlightKey: String? = null,
    @SerialName("greenlight_cert")
    val greenlightCert: String? = null,
    @SerialName("zendesk_client_id")
    val zendeskClientId: String? = null,
    @SerialName("reown_project_id")
    val reownProjectId: String? = null,
) : GreenJson<AppKeys>() {
    override fun kSerializer() = serializer()

    companion object {
        fun fromText(text: String): AppKeys? = text.takeIf { it.isNotBlank() }?.let {
            try {
                val trimmed = it.trim()
                val jsonText = if (trimmed.startsWith("{")) {
                    trimmed
                } else {
                    Base64.decode(trimmed).commonToUtf8String()
                }
                json.decodeFromString(jsonText)
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }
    }
}
