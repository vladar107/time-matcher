package io.vladar107.data.google

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GoogleOAuthApiTest {
    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
    private fun api(handler: io.ktor.client.engine.mock.MockRequestHandler) = GoogleOAuthApi(
        "cid", "sec", "http://localhost:8080/oauth/google/callback",
        HttpClient(MockEngine(handler)) { install(ContentNegotiation) { json() } })

    @Test fun authorizationUrlHasScopeOfflineConsentAndState() {
        val url = api { respond("{}", HttpStatusCode.OK, jsonHeaders) }.authorizationUrl("st8")
        assertTrue(url.startsWith("https://accounts.google.com/o/oauth2/v2/auth"))
        assertTrue(url.contains("access_type=offline") && url.contains("prompt=consent") && url.contains("state=st8"))
        assertTrue(url.contains("calendar")) // scope present (URL-encoded)
    }

    @Test fun exchangeCodeParsesTokens() = runBlocking {
        val tokens = api { respond("""{"access_token":"AT","refresh_token":"RT","expires_in":3600}""", HttpStatusCode.OK, jsonHeaders) }
            .exchangeCode("auth-code")
        assertEquals("AT", tokens.accessToken); assertEquals("RT", tokens.refreshToken)
    }

    @Test fun accountEmailReadsPrimaryCalendarId() = runBlocking {
        assertEquals("me@gmail.com",
            api { respond("""{"id":"me@gmail.com"}""", HttpStatusCode.OK, jsonHeaders) }.accountEmail("AT"))
    }
}
