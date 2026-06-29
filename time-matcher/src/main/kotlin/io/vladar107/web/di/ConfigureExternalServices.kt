package io.vladar107.web.di

import org.kodein.di.DI
import org.kodein.di.bind
import org.kodein.di.singleton
import java.time.Clock

fun DI.MainBuilder.configureExternalServices() {
    bind<Clock>() with singleton { Clock.systemDefaultZone() }
}
