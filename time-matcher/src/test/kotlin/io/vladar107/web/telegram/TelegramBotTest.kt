package io.vladar107.web.telegram

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.testApplication
import io.vladar107.application.booking.ConnectGoogleCalendarCommand
import io.vladar107.data.telegram.*
import io.vladar107.infrastructure.CommandProvider
import io.vladar107.infrastructure.QueryProvider
import io.vladar107.module
import io.vladar107.web.oauth.ConnectStateStore
import kotlinx.coroutines.runBlocking
import java.time.Clock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TelegramBotTest {
    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
    private val sent = mutableListOf<String>()
    private fun api() = TelegramApi("TOK", HttpClient(MockEngine { req ->
        if (req.url.toString().contains("/sendMessage")) sent += (req.body as io.ktor.http.content.TextContent).text
        respond("""{"ok":true,"result":{}}""", HttpStatusCode.OK, jsonHeaders)
    }) { install(ContentNegotiation) { json() } })
    private fun msg(uid: Long, text: String) = TgUpdate(1, message = TgMessage(10, TgUser(uid), TgChat(uid), text))

    @Test fun nonHostSenderIsIgnored() = testApplication {
        environment { config = MapApplicationConfig("db.url" to "jdbc:h2:mem:bot1-${java.util.UUID.randomUUID()};DB_CLOSE_DELAY=-1") }
        lateinit var app: Application; application { app = this; module() }
        startApplication()
        val bot = TelegramBot(api(), hostUserId = 42, ConnectStateStore(Clock.systemUTC()), CommandProvider(app), QueryProvider(app), "http://localhost:8080")
        bot.handle(msg(999, "/connect"))
        assertTrue(sent.isEmpty())
    }

    @Test fun connectSendsAStartLinkWithState() = testApplication {
        environment { config = MapApplicationConfig("db.url" to "jdbc:h2:mem:bot2-${java.util.UUID.randomUUID()};DB_CLOSE_DELAY=-1") }
        lateinit var app: Application; application { app = this; module() }
        startApplication()
        val bot = TelegramBot(api(), 42, ConnectStateStore(Clock.systemUTC()), CommandProvider(app), QueryProvider(app), "http://localhost:8080")
        bot.handle(msg(42, "/connect"))
        assertEquals(1, sent.size)
        assertTrue(sent[0].contains("/oauth/google/start?state="))
    }

    @Test fun calendarsListsConnectedWithStarAndButtons() = testApplication {
        environment { config = MapApplicationConfig("db.url" to "jdbc:h2:mem:bot3-${java.util.UUID.randomUUID()};DB_CLOSE_DELAY=-1") }
        lateinit var app: Application; application { app = this; module() }
        startApplication()
        // seed two connected calendars through the real use case
        CommandProvider(app).run(ConnectGoogleCalendarCommand("a@x.com", "primary", "rt1"))
        CommandProvider(app).run(ConnectGoogleCalendarCommand("b@x.com", "primary", "rt2"))
        val bot = TelegramBot(api(), 42, ConnectStateStore(Clock.systemUTC()), CommandProvider(app), QueryProvider(app), "http://localhost:8080")
        bot.handle(msg(42, "/calendars"))
        assertEquals(1, sent.size)
        assertTrue(sent[0].contains("a@x.com") && sent[0].contains("b@x.com"))
        assertTrue(sent[0].contains("★"))            // ★ marks the target (a@x.com, first connected)
        assertTrue(sent[0].contains("target:") && sent[0].contains("remove:")) // inline buttons
    }
}
