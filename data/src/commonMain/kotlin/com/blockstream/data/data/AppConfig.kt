package com.blockstream.data.data

typealias AppKeysString = String

data class AppConfig(
    val isDebug: Boolean,
    val filesDir: String,
    val cacheDir: String,
    val greenlightKey: String? = null,
    val greenlightCert: String? = null,
    val zendeskClientId: String? = null,
    val reownProjectId: String? = null,
    val analyticsFeatureEnabled: Boolean = true,
    val lightningFeatureEnabled: Boolean = true,
    val storeRateEnabled: Boolean = false
) {
    companion object {
        fun default(
            isDebug: Boolean,
            filesDir: String,
            cacheDir: String,
            analyticsFeatureEnabled: Boolean,
            lightningFeatureEnabled: Boolean,
            storeRateEnabled: Boolean,
            appKeysString: AppKeysString?
        ): AppConfig {
            val appKeys: AppKeys? = appKeysString?.takeIf { it.isNotBlank() }?.let { AppKeys.fromText(it) }

            return AppConfig(
                isDebug = isDebug,
                filesDir = filesDir,
                cacheDir = cacheDir,
                greenlightKey = appKeys?.greenlightKey,
                greenlightCert = appKeys?.greenlightCert,
                zendeskClientId = appKeys?.zendeskClientId,
                reownProjectId = appKeys?.reownProjectId,
                analyticsFeatureEnabled = analyticsFeatureEnabled,
                lightningFeatureEnabled = lightningFeatureEnabled && appKeys?.greenlightCert != null,
                storeRateEnabled = storeRateEnabled
            )
        }
    }
}
