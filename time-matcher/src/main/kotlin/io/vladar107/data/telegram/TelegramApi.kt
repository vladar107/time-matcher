package io.vladar107.data.telegram

import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// ---- Public DTOs ----

@Serializable
data class TgUser(val id: Long)

@Serializable
data class TgChat(val id: Long)

@Serializable
data class TgMessage(
    @SerialName("message_id") val messageId: Long,
    val from: TgUser? = null,
    val chat: TgChat,
    val text: String? = null,
)

@Serializable
data class TgCallbackQuery(
    val id: String,
    val from: TgUser,
    val message: TgMessage? = null,
    val data: String? = null,
)

@Serializable
data class TgUpdate(
    @SerialName("update_id") val updateId: Long,
    val message: TgMessage? = null,
    @SerialName("callback_query") val callbackQuery: TgCallbackQuery? = null,
)

/**
 * Represents a single inline keyboard button. Exactly one of [callbackData] or [url] should be set.
 */
data class TgButton(
    val text: String,
    val callbackData: String? = null,
    val url: String? = null,
)

// ---- Private wire DTOs ----

@Serializable
private data class SetWebhookRequest(
    val url: String,
    @SerialName("secret_token") val secretToken: String,
    // No default: field is always serialized so Telegram sees drop_pending_updates=true explicitly.
    @SerialName("drop_pending_updates") val dropPendingUpdates: Boolean,
)

@Serializable
private data class InlineButton(
    val text: String,
    @SerialName("callback_data") val callbackData: String? = null,
    val url: String? = null,
)

@Serializable
private data class InlineKeyboardMarkup(
    @SerialName("inline_keyboard") val inlineKeyboard: List<List<InlineButton>>,
)

@Serializable
private data class SendMessageRequest(
    @SerialName("chat_id") val chatId: Long,
    val text: String,
    @SerialName("reply_markup") val replyMarkup: InlineKeyboardMarkup? = null,
)

@Serializable
private data class AnswerCallbackRequest(
    @SerialName("callback_query_id") val callbackQueryId: String,
)

/** JSON configured to omit null fields and defaults — required for correct Telegram wire format. */
private val telegramJson = Json {
    encodeDefaults = false
    explicitNulls = false
    ignoreUnknownKeys = true
}

/**
 * Raw Telegram Bot API client.
 *
 * @param botToken Bot token issued by @BotFather.
 * @param httpClient Ktor HTTP client with ContentNegotiation/JSON installed.
 */
class TelegramApi(botToken: String, private val httpClient: HttpClient) {

    private val logger = org.slf4j.LoggerFactory.getLogger(TelegramApi::class.java)
    private val base = "https://api.telegram.org/bot$botToken"

    /** Registers the webhook URL with Telegram. Throws on non-2xx so startup can log the failure. */
    suspend fun setWebhook(url: String, secretToken: String) {
        val body = telegramJson.encodeToString(SetWebhookRequest.serializer(), SetWebhookRequest(url, secretToken, dropPendingUpdates = true))
        call { httpClient.post("$base/setWebhook") { contentType(ContentType.Application.Json); setBody(body) } }
    }

    /**
     * Sends a text message to [chatId], optionally with an inline keyboard.
     * Non-2xx errors are logged and swallowed (bot UX — best-effort delivery).
     */
    suspend fun sendMessage(chatId: Long, text: String, buttons: List<List<TgButton>> = emptyList()) {
        val markup = if (buttons.isEmpty()) null
        else InlineKeyboardMarkup(buttons.map { row -> row.map { btn -> InlineButton(btn.text, btn.callbackData, btn.url) } })
        val req = SendMessageRequest(chatId, text, markup)
        val body = telegramJson.encodeToString(SendMessageRequest.serializer(), req)
        val resp = try {
            httpClient.post("$base/sendMessage") {
                contentType(ContentType.Application.Json)
                setBody(body)
            }
        } catch (e: Exception) {
            return  // swallow network errors for send
        }
        if (!resp.status.isSuccess()) {
            logger.warn("Telegram sendMessage failed: {}", resp.status)
        }
    }

    /**
     * Acknowledges a callback query so Telegram removes the loading spinner.
     * Non-2xx errors are swallowed (best-effort).
     */
    suspend fun answerCallback(callbackId: String) {
        val req = AnswerCallbackRequest(callbackId)
        val body = telegramJson.encodeToString(AnswerCallbackRequest.serializer(), req)
        try {
            val resp = httpClient.post("$base/answerCallbackQuery") {
                contentType(ContentType.Application.Json)
                setBody(body)
            }
            if (!resp.status.isSuccess()) logger.warn("Telegram answerCallbackQuery failed: {}", resp.status)
        } catch (e: Exception) {
            // swallow
        }
    }

    private suspend fun call(block: suspend () -> HttpResponse): HttpResponse {
        val resp = try {
            block()
        } catch (e: Exception) {
            throw TelegramException("Telegram request failed", e)
        }
        if (!resp.status.isSuccess()) throw TelegramException("Telegram error: ${resp.status}")
        return resp
    }
}

/** Thrown by [TelegramApi] on network or non-2xx errors. */
class TelegramException(message: String, cause: Throwable? = null) : Exception(message, cause)
