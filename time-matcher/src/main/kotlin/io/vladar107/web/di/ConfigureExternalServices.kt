package io.vladar107.web.di

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.*
import io.vladar107.application.availability.CalendarBusyWriter
import io.vladar107.application.availability.CalendarProvider
import io.vladar107.application.availability.CalendarWriter
import io.vladar107.data.google.GoogleCalendarApi
import io.vladar107.data.google.GoogleCalendarProvider
import io.vladar107.data.google.GoogleCalendarWriter
import io.vladar107.data.google.GoogleOAuthApi
import io.vladar107.data.google.GoogleTokenManager
import io.vladar107.data.persistence.Db
import io.vladar107.data.repositories.InMemoryCalendarProvider
import io.vladar107.data.repositories.NoOpCalendarBusyWriter
import io.vladar107.data.telegram.TelegramApi
import io.vladar107.web.oauth.ConnectStateStore
import org.kodein.di.DI
import org.kodein.di.bind
import org.kodein.di.instance
import org.kodein.di.singleton
import java.time.Clock

fun DI.MainBuilder.configureExternalServices(application: Application) {
    val cfg = application.environment.config
    val url = cfg.propertyOrNull("db.url")?.getString()
        ?: "jdbc:h2:file:./data/timematcher;DB_CLOSE_DELAY=-1"
    Db.init(url, cfg.propertyOrNull("db.user")?.getString() ?: "sa", cfg.propertyOrNull("db.password")?.getString() ?: "")
    bind<Clock>() with singleton { Clock.systemDefaultZone() }

    // Bind TelegramApi unconditionally so both google and in-memory modes can resolve it.
    // The poll loop (configureTelegramBot) only starts when a real token is configured.
    val telegramToken = application.environment.config.propertyOrNull("telegram.botToken")?.getString() ?: ""
    bind<TelegramApi>() with singleton { TelegramApi(telegramToken, HttpClient(CIO) { install(ContentNegotiation) { json() } }) }
    bind<ConnectStateStore>() with singleton { ConnectStateStore(instance()) }

    val provider = application.environment.config.propertyOrNull("calendar.provider")?.getString() ?: "inmemory"
    if (provider == "google") {
        val cfg = application.environment.config
        fun req(k: String) = cfg.propertyOrNull(k)?.getString()?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("Missing config: $k")
        val httpClient = HttpClient(CIO) { install(ContentNegotiation) { json() } }
        val redirectBase = cfg.propertyOrNull("publicBaseUrl")?.getString() ?: "http://localhost:8080"
        bind<GoogleTokenManager>() with singleton { GoogleTokenManager(req("google.clientId"), req("google.clientSecret"), httpClient, instance()) }
        bind<GoogleCalendarApi>() with singleton { GoogleCalendarApi(httpClient) }
        bind<GoogleOAuthApi>() with singleton { GoogleOAuthApi(req("google.clientId"), req("google.clientSecret"), "$redirectBase/oauth/google/callback", httpClient) }
        bind<CalendarProvider>() with singleton { GoogleCalendarProvider(instance(), instance(), instance()) }
        bind<CalendarWriter>() with singleton { GoogleCalendarWriter(instance(), instance(), instance()) }
        bind<CalendarBusyWriter>() with singleton { NoOpCalendarBusyWriter() }
    } else {
        bind<InMemoryCalendarProvider>() with singleton { InMemoryCalendarProvider() }
        bind<CalendarProvider>() with singleton { instance<InMemoryCalendarProvider>() }
        bind<CalendarBusyWriter>() with singleton { instance<InMemoryCalendarProvider>() }
        bind<CalendarWriter>() with singleton { instance<InMemoryCalendarProvider>() }
    }
}
