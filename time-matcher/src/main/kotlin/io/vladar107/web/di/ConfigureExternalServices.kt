package io.vladar107.web.di

import io.ktor.server.application.*
import io.vladar107.data.persistence.Db
import org.kodein.di.DI
import org.kodein.di.bind
import org.kodein.di.singleton
import java.time.Clock

fun DI.MainBuilder.configureExternalServices(application: Application) {
    val url = application.environment.config.propertyOrNull("db.url")?.getString()
        ?: "jdbc:h2:file:./data/timematcher;DB_CLOSE_DELAY=-1"
    Db.init(url)
    bind<Clock>() with singleton { Clock.systemDefaultZone() }
}
