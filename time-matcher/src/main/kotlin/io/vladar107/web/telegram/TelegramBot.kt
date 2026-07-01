package io.vladar107.web.telegram

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.vladar107.application.booking.ListConnectedCalendarsQuery
import io.vladar107.application.booking.RemoveConnectedCalendarCommand
import io.vladar107.application.booking.SetBookingTargetCommand
import io.vladar107.data.telegram.TelegramApi
import io.vladar107.data.telegram.TgButton
import io.vladar107.data.telegram.TgCallbackQuery
import io.vladar107.data.telegram.TgMessage
import io.vladar107.data.telegram.TgUpdate
import io.vladar107.infrastructure.CommandProvider
import io.vladar107.infrastructure.QueryProvider
import io.vladar107.web.oauth.ConnectStateStore
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import org.kodein.di.instance
import org.kodein.di.ktor.closestDI
import java.util.UUID

/**
 * Host-only Telegram bot: auth-gates all updates to [hostUserId], then dispatches
 * text commands and inline-keyboard callbacks to the appropriate use-case handlers.
 */
class TelegramBot(
    private val api: TelegramApi,
    private val hostUserId: Long,
    private val stateStore: ConnectStateStore,
    private val commandProvider: CommandProvider,
    private val queryProvider: QueryProvider,
    private val oauthBaseUrl: String,
) {
    /** Entry point called by the poll loop and by tests. */
    suspend fun handle(update: TgUpdate) {
        val senderId = update.message?.from?.id ?: update.callbackQuery?.from?.id ?: return
        if (senderId != hostUserId) return
        update.message?.let { onMessage(it) }
        update.callbackQuery?.let { onCallback(it) }
    }

    private suspend fun onMessage(m: TgMessage) {
        val chatId = m.chat.id
        when (m.text?.trim()?.substringBefore(' ')) {
            "/start", "/help" -> api.sendMessage(
                chatId,
                "Commands:\n/connect — connect a Google Calendar\n/calendars — list & manage connected calendars",
            )
            "/connect" -> {
                val nonce = stateStore.create(chatId)
                api.sendMessage(
                    chatId,
                    "Tap to connect your Google Calendar:",
                    listOf(listOf(TgButton("🔗 Connect Google Calendar", url = "$oauthBaseUrl/oauth/google/start?state=$nonce"))),
                )
            }
            "/calendars" -> {
                val cals = queryProvider.query(ListConnectedCalendarsQuery())
                if (cals.isEmpty()) {
                    api.sendMessage(chatId, "No calendars connected yet. Use /connect.")
                    return
                }
                val text = buildString {
                    append("Connected calendars:\n")
                    cals.forEach { append(if (it.isBookingTarget) "★ " else "• ").append(it.accountEmail).append('\n') }
                    append("\n★ = bookings are written here.")
                }
                val buttons = cals.map { c ->
                    listOf(
                        TgButton(if (c.isBookingTarget) "★ booking" else "Set ★", callbackData = "target:${c.id}"),
                        TgButton("Remove 🗑", callbackData = "remove:${c.id}"),
                    )
                }
                api.sendMessage(chatId, text, buttons)
            }
            null -> { }
            else -> api.sendMessage(chatId, "Unknown command. Try /help.")
        }
    }

    private suspend fun onCallback(cb: TgCallbackQuery) {
        val data = cb.data ?: return
        val chatId = cb.message?.chat?.id ?: cb.from.id
        when {
            data.startsWith("target:") -> {
                commandProvider.run(SetBookingTargetCommand(UUID.fromString(data.removePrefix("target:"))))
                api.answerCallback(cb.id)
                api.sendMessage(chatId, "★ Booking target updated.")
            }
            data.startsWith("remove:") -> {
                commandProvider.run(RemoveConnectedCalendarCommand(UUID.fromString(data.removePrefix("remove:"))))
                api.answerCallback(cb.id)
                api.sendMessage(chatId, "Calendar removed.")
            }
            else -> api.answerCallback(cb.id)
        }
    }
}

/**
 * Registers the Telegram webhook route and, at startup, calls [TelegramApi.setWebhook]
 * to tell Telegram where to deliver updates.
 *
 * The route is always registered (so in-memory test suites boot cleanly), but the startup
 * [TelegramApi.setWebhook] call is only made when both [telegram.webhookSecret] and
 * [telegram.botToken] are configured.
 */
fun Application.configureTelegramBot() {
    val cfg = environment.config
    val secret = cfg.propertyOrNull("telegram.webhookSecret")?.getString()?.takeIf { it.isNotBlank() }
    val botToken = cfg.propertyOrNull("telegram.botToken")?.getString()?.takeIf { it.isNotBlank() }
    val publicBaseUrl = cfg.propertyOrNull("publicBaseUrl")?.getString() ?: "http://localhost:8080"
    val hostUserId = cfg.propertyOrNull("telegram.hostUserId")?.getString()?.toLongOrNull() ?: 0L
    val di = closestDI { this@configureTelegramBot }
    val json = Json { ignoreUnknownKeys = true }

    routing {
        post("/telegram/webhook/{secret}") {
            val pathSecret = call.parameters["secret"]
            val headerSecret = call.request.headers["X-Telegram-Bot-Api-Secret-Token"]
            if (secret == null || pathSecret != secret || headerSecret != secret)
                return@post call.respond(HttpStatusCode.Forbidden)
            val update = try { json.decodeFromString(TgUpdate.serializer(), call.receiveText()) }
                catch (e: Exception) { return@post call.respond(HttpStatusCode.BadRequest, "bad update") }
            val api: TelegramApi by di.instance()
            val stateStore: ConnectStateStore by di.instance()
            val bot = TelegramBot(api, hostUserId, stateStore, CommandProvider(this@configureTelegramBot), QueryProvider(this@configureTelegramBot), publicBaseUrl)
            bot.handle(update)
            call.respond(HttpStatusCode.OK)
        }
    }

    // Register the webhook with Telegram at startup (best-effort).
    if (secret != null && botToken != null) {
        val api: TelegramApi by di.instance()
        launch {
            runCatching { api.setWebhook("$publicBaseUrl/telegram/webhook/$secret", secret) }
                .onFailure { environment.log.error("Telegram setWebhook failed", it) }
        }
    }
}
