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
import io.vladar107.data.google.GoogleTokenSource
import io.vladar107.data.persistence.Db
import io.vladar107.data.repositories.InMemoryCalendarProvider
import io.vladar107.data.repositories.NoOpCalendarBusyWriter
import org.kodein.di.DI
import org.kodein.di.bind
import org.kodein.di.instance
import org.kodein.di.singleton
import java.time.Clock

fun DI.MainBuilder.configureExternalServices(application: Application) {
    val url = application.environment.config.propertyOrNull("db.url")?.getString()
        ?: "jdbc:h2:file:./data/timematcher;DB_CLOSE_DELAY=-1"
    Db.init(url)
    bind<Clock>() with singleton { Clock.systemDefaultZone() }

    val provider = application.environment.config.propertyOrNull("calendar.provider")?.getString() ?: "inmemory"
    if (provider == "google") {
        val cfg = application.environment.config
        fun req(k: String) = cfg.propertyOrNull(k)?.getString()?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("Missing config: $k")
        val httpClient = HttpClient(CIO) {
            install(ContentNegotiation) { json() }
        }
        val tokenSource = GoogleTokenSource(
            req("google.clientId"), req("google.clientSecret"), req("google.refreshToken"), httpClient, java.time.Clock.systemUTC())
        val api = GoogleCalendarApi(tokenSource, httpClient)
        val calendarId = cfg.propertyOrNull("google.calendarId")?.getString()?.takeIf { it.isNotBlank() } ?: "primary"
        bind<CalendarProvider>() with singleton { GoogleCalendarProvider(api, calendarId) }
        bind<CalendarWriter>() with singleton { GoogleCalendarWriter(api, calendarId) }
        bind<CalendarBusyWriter>() with singleton { NoOpCalendarBusyWriter() }
    } else {
        bind<InMemoryCalendarProvider>() with singleton { InMemoryCalendarProvider() }
        bind<CalendarProvider>() with singleton { instance<InMemoryCalendarProvider>() }
        bind<CalendarBusyWriter>() with singleton { instance<InMemoryCalendarProvider>() }
        bind<CalendarWriter>() with singleton { instance<InMemoryCalendarProvider>() }
    }
}
