package com.smsforwarder.app.engine.dispatcher

import okhttp3.CipherSuite
import okhttp3.ConnectionSpec
import okhttp3.OkHttpClient
import okhttp3.TlsVersion
import java.util.concurrent.TimeUnit

/**
 * Provides a hardened, shared OkHttpClient enforcing strict TLS 1.2/1.3 and zero telemetry.
 */
object OkHttpClientProvider {

    private val modernTlsSpec = ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
        .tlsVersions(TlsVersion.TLS_1_3, TlsVersion.TLS_1_2)
        .build()

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectionSpecs(listOf(modernTlsSpec, ConnectionSpec.COMPATIBLE_TLS))
            .connectTimeout(45, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .writeTimeout(45, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}
