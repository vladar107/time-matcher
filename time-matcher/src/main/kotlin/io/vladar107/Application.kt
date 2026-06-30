package io.vladar107

import io.ktor.server.application.*
import io.vladar107.web.di.configureDi
import io.vladar107.web.documentation.configureOpenAPI
import io.vladar107.web.monitoring.configureMonitoring
import io.vladar107.web.plugins.configureSerialization
import io.vladar107.web.plugins.configureStatusPages
import io.vladar107.web.availability.configureAvailability
import io.vladar107.web.booking.configureBooking
import io.vladar107.web.booking.configureBookingPage
import io.vladar107.web.booking.configureEventTypes
import io.vladar107.web.oauth.configureGoogleOAuth
import io.vladar107.web.telegram.configureTelegramBot
import io.vladar107.web.user.configureUser

fun main(args: Array<String>) {
    io.ktor.server.netty.EngineMain.main(args)
}

fun Application.module() {
    configureDi()
    configureOpenAPI()
    configureMonitoring()
    configureSerialization()
    configureStatusPages()
    configureUser()
    configureAvailability()
    configureEventTypes()
    configureBooking()
    configureBookingPage()
    configureGoogleOAuth()
    configureTelegramBot()
}
