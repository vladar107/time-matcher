package io.vladar107.web.telegram

import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.testApplication
import io.vladar107.module
import kotlin.test.Test
import kotlin.test.assertEquals

class TelegramWebhookRoutesTest {
    private fun ApplicationTestBuilderConfig() = MapApplicationConfig(
        "db.url" to "jdbc:h2:mem:wh-${java.util.UUID.randomUUID()};DB_CLOSE_DELAY=-1",
        "telegram.webhookSecret" to "s3cr3t",
        "telegram.hostUserId" to "42")
    // Non-host sender (id 999 != hostUserId 42): TelegramBot.handle auth-gates it to a no-op,
    // so the route returns 200 WITHOUT any outbound Telegram sendMessage (the DI TelegramApi uses a
    // live CIO client with a blank token — we must not trigger a real network call in this test).
    private val update = """{"update_id":1,"message":{"message_id":10,"from":{"id":999},"chat":{"id":999},"text":"/help"}}"""

    @Test fun validSecretAndHeaderReturns200() = testApplication {
        environment { config = ApplicationTestBuilderConfig() }; application { module() }
        val resp = client.post("/telegram/webhook/s3cr3t") {
            header("X-Telegram-Bot-Api-Secret-Token", "s3cr3t"); contentType(ContentType.Application.Json); setBody(update) }
        assertEquals(HttpStatusCode.OK, resp.status)  // secret+header valid → route accepts, handle no-ops, 200
    }

    @Test fun wrongPathSecretReturns403() = testApplication {
        environment { config = ApplicationTestBuilderConfig() }; application { module() }
        val resp = client.post("/telegram/webhook/WRONG") {
            header("X-Telegram-Bot-Api-Secret-Token", "s3cr3t"); contentType(ContentType.Application.Json); setBody(update) }
        assertEquals(HttpStatusCode.Forbidden, resp.status)
    }

    @Test fun wrongHeaderReturns403() = testApplication {
        environment { config = ApplicationTestBuilderConfig() }; application { module() }
        val resp = client.post("/telegram/webhook/s3cr3t") {
            header("X-Telegram-Bot-Api-Secret-Token", "nope"); contentType(ContentType.Application.Json); setBody(update) }
        assertEquals(HttpStatusCode.Forbidden, resp.status)
    }

    @Test fun malformedBodyReturns400() = testApplication {
        environment { config = ApplicationTestBuilderConfig() }; application { module() }
        val resp = client.post("/telegram/webhook/s3cr3t") {
            header("X-Telegram-Bot-Api-Secret-Token", "s3cr3t"); contentType(ContentType.Application.Json); setBody("{not json") }
        assertEquals(HttpStatusCode.BadRequest, resp.status)
    }
}
