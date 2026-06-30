package io.vladar107.data.google

import io.ktor.client.HttpClient
import java.time.Clock
import java.util.concurrent.ConcurrentHashMap

/** One access-token cache (a GoogleTokenSource) per refresh token. */
class GoogleTokenManager(
    private val clientId: String,
    private val clientSecret: String,
    private val httpClient: HttpClient,
    private val clock: Clock,
) {
    private val sources = ConcurrentHashMap<String, GoogleTokenSource>()

    suspend fun accessToken(refreshToken: String): String =
        sources.computeIfAbsent(refreshToken) { GoogleTokenSource(clientId, clientSecret, it, httpClient, clock) }.accessToken()
}
