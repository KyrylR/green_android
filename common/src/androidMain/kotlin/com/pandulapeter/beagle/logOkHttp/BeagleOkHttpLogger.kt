package com.pandulapeter.beagle.logOkHttp

// Reown's debug build references Beagle's OkHttp hook directly.
// Returning null disables the interceptor so pairing can run without the optional dependency.
object BeagleOkHttpLogger {
    val logger: Any?
        get() = null
}
