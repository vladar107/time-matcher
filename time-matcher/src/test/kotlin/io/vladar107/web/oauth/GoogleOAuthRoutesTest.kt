package io.vladar107.web.oauth

import io.ktor.client.plugins.* // HttpRedirect off to observe 302
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.testApplication
import io.vladar107.module
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConnectStateStoreTest {
    private fun store(now: String) = ConnectStateStore(Clock.fixed(Instant.parse(now), ZoneId.of("UTC")))
    @Test fun createThenConsumeReturnsChatIdOnce() {
        val s = store("2030-01-01T00:00:00Z"); val nonce = s.create(42)
        assertEquals(42L, s.consume(nonce)); assertEquals(null, s.consume(nonce)) // single-use
    }
    @Test fun unknownStateIsNull() { assertEquals(null, store("2030-01-01T00:00:00Z").consume("nope")) }
}

class GoogleOAuthRoutesTest {
    private fun cfg() = MapApplicationConfig(
        "db.url" to "jdbc:h2:mem:oauth-${java.util.UUID.randomUUID()};DB_CLOSE_DELAY=-1",
        "calendar.provider" to "google",
        "google.clientId" to "cid", "google.clientSecret" to "sec",
        "publicBaseUrl" to "http://localhost:8080")

    @Test fun startRedirectsToGoogleConsent() = testApplication {
        environment { config = cfg() }; application { module() }
        val client = createClient { followRedirects = false }
        val resp = client.get("/oauth/google/start?state=abc")
        assertEquals(HttpStatusCode.Found, resp.status)
        val location = resp.headers["Location"] ?: ""
        assertTrue(location.startsWith("https://accounts.google.com/o/oauth2/v2/auth"))
        assertTrue(location.contains("state=abc") && location.contains("access_type=offline") && location.contains("prompt=consent"))
        assertTrue(location.contains("redirect_uri="))
        assertTrue(location.contains("calendar"))
    }

    @Test fun callbackWithInvalidStateIs400() = testApplication {
        environment { config = cfg() }; application { module() }
        val resp = createClient { followRedirects = false }.get("/oauth/google/callback?code=x&state=bogus")
        assertEquals(HttpStatusCode.BadRequest, resp.status)
    }
}
