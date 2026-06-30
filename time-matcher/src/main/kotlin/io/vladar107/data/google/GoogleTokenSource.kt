package io.vladar107.data.google

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.forms.submitForm
import io.ktor.client.statement.HttpResponse
import io.ktor.http.Parameters
import io.ktor.http.isSuccess
import io.vladar107.application.availability.CalendarException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Clock
import java.time.Instant

class GoogleTokenSource(
    private val clientId: String,
    private val clientSecret: String,
    private val refreshToken: String,
    private val httpClient: HttpClient,
    private val clock: Clock,
) {
    @Serializable
    private data class TokenResponse(@SerialName("access_token") val accessToken: String, @SerialName("expires_in") val expiresIn: Long)

    private val mutex = Mutex()
    @Volatile private var cached: String? = null
    @Volatile private var expiresAt: Instant = Instant.MIN

    suspend fun accessToken(): String = mutex.withLock {
        val now = clock.instant()
        val current = cached
        if (current != null && now.isBefore(expiresAt.minusSeconds(60))) return@withLock current
        val response: HttpResponse = try {
            httpClient.submitForm(
                url = "https://oauth2.googleapis.com/token",
                formParameters = Parameters.build {
                    append("grant_type", "refresh_token")
                    append("client_id", clientId)
                    append("client_secret", clientSecret)
                    append("refresh_token", refreshToken)
                },
            )
        } catch (e: Exception) {
            throw CalendarException("Google token refresh failed", e)
        }
        if (!response.status.isSuccess()) throw CalendarException("Google token refresh failed: ${response.status}")
        val token = response.body<TokenResponse>()
        cached = token.accessToken
        expiresAt = now.plusSeconds(token.expiresIn)
        token.accessToken
    }
}
