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
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TelegramApiTest {
    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
    private fun client(handler: io.ktor.client.engine.mock.MockRequestHandler) =
        HttpClient(MockEngine(handler)) { install(ContentNegotiation) { json() } }

    @Test fun getUpdatesParsesMessageAndCallback() = runBlocking {
        val body = """{"ok":true,"result":[
          {"update_id":1,"message":{"message_id":10,"from":{"id":42},"chat":{"id":42},"text":"/connect"}},
          {"update_id":2,"callback_query":{"id":"cb1","from":{"id":42},"data":"remove:abc"}}]}"""
        val api = TelegramApi("TOK", client { respond(body, HttpStatusCode.OK, jsonHeaders) })
        val updates = api.getUpdates(0)
        assertEquals(2, updates.size)
        assertEquals("/connect", updates[0].message?.text)
        assertEquals("remove:abc", updates[1].callbackQuery?.data)
    }

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
    }
}
