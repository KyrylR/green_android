package com.blockstream.common.data

import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

class AppKeysTest {
    @Test
    fun fromTextParsesRawJsonAppKeys() {
        val appKeys = AppKeys.fromText(
            """
                {
                  "zendesk_client_id": "zendesk-id",
                  "reown_project_id": "reown-id"
                }
            """.trimIndent(),
        )

        assertNotNull(appKeys)
        assertEquals("zendesk-id", appKeys.zendeskClientId)
        assertEquals("reown-id", appKeys.reownProjectId)
    }

    @Test
    fun fromTextParsesBase64AppKeysWithWhitespace() {
        val encoded = Base64.encode(
            """
                {
                  "breez_api_key": "breez-id",
                  "reown_project_id": "reown-id"
                }
            """.trimIndent().encodeToByteArray(),
        )

        val appKeys = AppKeys.fromText("  $encoded\n")

        assertNotNull(appKeys)
        assertEquals("breez-id", appKeys.breezApiKey)
        assertEquals("reown-id", appKeys.reownProjectId)
    }

    @Test
    fun fromAppKeysCarriesReownProjectIdIntoAppConfig() {
        val appConfig = AppConfig.fromAppKeys(
            isDebug = true,
            filesDir = "/tmp/files",
            cacheDir = "/tmp/cache",
            analyticsFeatureEnabled = true,
            lightningFeatureEnabled = false,
            storeRateEnabled = false,
            appKeys = AppKeys(
                zendeskClientId = "zendesk-id",
                reownProjectId = "reown-id",
            ),
        )

        assertEquals("zendesk-id", appConfig.zendeskClientId)
        assertEquals("reown-id", appConfig.reownProjectId)
        assertFalse(appConfig.lightningFeatureEnabled)
    }
}
