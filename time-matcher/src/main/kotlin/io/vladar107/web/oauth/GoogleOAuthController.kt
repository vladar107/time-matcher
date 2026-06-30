package io.vladar107.web.oauth

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.vladar107.application.booking.ConnectGoogleCalendarCommand
import io.vladar107.data.google.GoogleOAuthApi
import io.vladar107.data.telegram.TelegramApi
import io.vladar107.infrastructure.CommandProvider
import org.kodein.di.instance
import org.kodein.di.ktor.closestDI
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Stores per-connection state nonces with a TTL.
 * Thread-safe; single-use consumption.
 */
class ConnectStateStore(private val clock: Clock, private val ttl: Duration = Duration.ofMinutes(10)) {
    private data class Entry(val chatId: Long, val expiresAt: Instant)
    private val map = ConcurrentHashMap<String, Entry>()

    /** Creates a nonce mapped to [chatId] and returns it. */
    fun create(chatId: Long): String {
        val n = UUID.randomUUID().toString()
        map[n] = Entry(chatId, clock.instant().plus(ttl))
        return n
    }

    /**
     * Consumes [state]: removes it and returns its chatId if valid and not expired;
     * returns null otherwise (unknown, already used, or expired).
     */
    fun consume(state: String): Long? {
        val e = map.remove(state) ?: return null
        return if (clock.instant().isBefore(e.expiresAt)) e.chatId else null
    }
}

/**
 * Registers the Google OAuth start and callback routes.
 *
 * IMPORTANT: All DI resolutions are done INSIDE route handlers (per-request) so that
 * `Application.module()` can boot in in-memory mode without the google-only bindings
 * (GoogleOAuthApi, ConnectStateStore, TelegramApi) present. They are only resolved
 * when an actual request hits /oauth/google/start or /callback, which in-memory test suites never do.
 */
fun Application.configureGoogleOAuth() {
    val commandProvider = CommandProvider(this@configureGoogleOAuth)
    routing {
        get("/oauth/google/start") {
            val state = call.request.queryParameters["state"]
                ?: return@get call.respond(HttpStatusCode.BadRequest, "missing state")
            val di = closestDI { this@configureGoogleOAuth }
            val oauthApi: GoogleOAuthApi by di.instance()
            call.respondRedirect(oauthApi.authorizationUrl(state))
        }

        get("/oauth/google/callback") {
            val code = call.request.queryParameters["code"]
            val state = call.request.queryParameters["state"]

            // Resolve ConnectStateStore inside the handler — lazy, google-mode only
            val di = closestDI { this@configureGoogleOAuth }
            val stateStore: ConnectStateStore by di.instance()
            val chatId = state?.let { stateStore.consume(it) }

            if (code == null || chatId == null) {
                return@get call.respondText(
                    "Link expired — run /connect again in Telegram.",
                    status = HttpStatusCode.BadRequest,
                )
            }

            // Resolve google-only services only on the happy path
            val oauthApi: GoogleOAuthApi by di.instance()
            val tokens = oauthApi.exchangeCode(code)
            val email = oauthApi.accountEmail(tokens.accessToken)
            commandProvider.run(ConnectGoogleCalendarCommand(email, "primary", tokens.refreshToken))

            // TelegramApi binding is added in Task 6 — resolve it lazily here
            val telegramApi: TelegramApi by di.instance()
            runCatching { telegramApi.sendMessage(chatId, "✅ Connected $email") }

            call.respondText("✅ Connected $email — you can return to Telegram.", ContentType.Text.Plain)
        }
    }
}
