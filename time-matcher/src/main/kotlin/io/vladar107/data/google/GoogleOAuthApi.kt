package io.vladar107.data.google

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.http.Parameters
import io.ktor.http.encodeURLParameter
import io.ktor.http.isSuccess
import io.vladar107.application.availability.CalendarException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

data class GoogleTokens(val accessToken: String, val refreshToken: String)

class GoogleOAuthApi(
    private val clientId: String,
    private val clientSecret: String,
    private val redirectUri: String,
    private val httpClient: HttpClient,
) {
    @Serializable
    private data class TokenResponse(
        @SerialName("access_token") val accessToken: String,
        @SerialName("refresh_token") val refreshToken: String,
        @SerialName("expires_in") val expiresIn: Long,
    )

    @Serializable
    private data class CalendarPrimaryResponse(val id: String)

    /**
     * Builds the Google OAuth2 authorization URL with the given [state] nonce.
     * Scope: https://www.googleapis.com/auth/calendar, offline access, forced consent prompt.
     */
    fun authorizationUrl(state: String): String {
        val scope = "https://www.googleapis.com/auth/calendar".encodeURLParameter()
        val redirect = redirectUri.encodeURLParameter()
        val st = state.encodeURLParameter()
        val cid = clientId.encodeURLParameter()
        return "https://accounts.google.com/o/oauth2/v2/auth" +
            "?client_id=$cid" +
            "&redirect_uri=$redirect" +
            "&response_type=code" +
            "&scope=$scope" +
            "&access_type=offline" +
            "&prompt=consent" +
            "&state=$st"
    }

    /**
     * Exchanges an authorization [code] for access + refresh tokens.
     * Throws [CalendarException] on non-2xx.
     */
    suspend fun exchangeCode(code: String): GoogleTokens {
        val response = try {
            httpClient.submitForm(
                url = "https://oauth2.googleapis.com/token",
                formParameters = Parameters.build {
                    append("grant_type", "authorization_code")
                    append("code", code)
                    append("client_id", clientId)
                    append("client_secret", clientSecret)
                    append("redirect_uri", redirectUri)
                },
            )
        } catch (e: Exception) {
            throw CalendarException("Google OAuth token exchange failed", e)
        }
        if (!response.status.isSuccess()) throw CalendarException("Google OAuth token exchange failed: ${response.status}")
        val token = response.body<TokenResponse>()
        return GoogleTokens(token.accessToken, token.refreshToken)
    }

    /**
     * Reads the primary calendar's id, which is the Google account email address.
     * Throws [CalendarException] on non-2xx.
     */
    suspend fun accountEmail(accessToken: String): String {
        val response = try {
            httpClient.get("https://www.googleapis.com/calendar/v3/calendars/primary") {
                header(HttpHeaders.Authorization, "Bearer $accessToken")
            }
        } catch (e: Exception) {
            throw CalendarException("Google Calendar primary read failed", e)
        }
        if (!response.status.isSuccess()) throw CalendarException("Google Calendar primary read failed: ${response.status}")
        return response.body<CalendarPrimaryResponse>().id
    }
}
