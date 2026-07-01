package io.vladar107.data.telegram

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
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class TelegramApiTest {
    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
    private fun client(handler: io.ktor.client.engine.mock.MockRequestHandler) =
        HttpClient(MockEngine(handler)) { install(ContentNegotiation) { json() } }

    @Test fun sendMessagePostsChatIdTextAndInlineKeyboard() = runBlocking {
        var captured = ""
        val api = TelegramApi("TOK", client { req ->
            if (req.url.toString().contains("/sendMessage")) captured = (req.body as io.ktor.http.content.TextContent).text
            respond("""{"ok":true,"result":{}}""", HttpStatusCode.OK, jsonHeaders) })
        api.sendMessage(42, "hello", listOf(listOf(TgButton("Set ★", callbackData = "target:1"), TgButton("Connect", url = "https://h/x"))))
        assertTrue(captured.contains("\"chat_id\":42"))
        assertTrue(captured.contains("hello"))
        assertTrue(captured.contains("inline_keyboard"))
        assertTrue(captured.contains("callback_data") && captured.contains("target:1"))
        assertTrue(captured.contains("\"url\":\"https://h/x\""))
        assertFalse(captured.contains("\"callback_data\":null"))
        assertFalse(captured.contains("\"url\":null"))
    }

    @Test fun setWebhookPostsUrlAndSecret() = runBlocking {
        var captured = ""
        val api = TelegramApi("TOK", client { req ->
            if (req.url.toString().contains("/setWebhook")) captured = (req.body as io.ktor.http.content.TextContent).text
            respond("""{"ok":true,"result":true}""", HttpStatusCode.OK, jsonHeaders) })
        api.setWebhook("https://h/telegram/webhook/s3cr3t", "s3cr3t")
        assertTrue(captured.contains("https://h/telegram/webhook/s3cr3t"))
        assertTrue(captured.contains("secret_token") && captured.contains("s3cr3t"))
        assertTrue(captured.contains("\"drop_pending_updates\":true"))
    }

    @Test fun setWebhookThrowsOnNonSuccess() = runBlocking<Unit> {
        val api = TelegramApi("TOK", client { respond("""{"ok":false}""", HttpStatusCode.InternalServerError, jsonHeaders) })
        assertFailsWith<TelegramException> { api.setWebhook("https://h/w", "s") }
    }
}
