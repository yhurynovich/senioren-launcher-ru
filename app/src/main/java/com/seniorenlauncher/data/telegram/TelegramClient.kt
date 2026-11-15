package com.seniorenlauncher.data.telegram

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import com.seniorenlauncher.BuildConfig
import com.seniorenlauncher.LauncherApp
import com.seniorenlauncher.MainActivity
import com.seniorenlauncher.R
import com.seniorenlauncher.data.model.Conversation
import com.seniorenlauncher.data.model.SmsMessage
import com.seniorenlauncher.util.i18n
import io.github.up9cloud.td.JsonClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** Where the user currently is in the Telegram sign-in. */
sealed interface TgAuth {
    data object NotConfigured : TgAuth
    data object Loading : TgAuth
    data object WaitPhone : TgAuth
    data class WaitCode(val viaSms: Boolean) : TgAuth
    data object WaitPassword : TgAuth
    data class WaitQr(val link: String) : TgAuth
    data object NeedsRegistration : TgAuth
    data object Ready : TgAuth
    data class Failed(val message: String) : TgAuth
}

private data class TgChat(
    val id: Long,
    val title: String,
    val lastText: String,
    val lastDate: Long,
    val unread: Int
)

/**
 * Thin wrapper around TDLib's JSON interface (libtdjson.so). Text chats only (v1).
 * Telegram conversations use the address "tg-<chatId>" so they can share the SMS UI.
 */
object TelegramClient {
    private const val TAG = "TelegramClient"
    const val ADDRESS_PREFIX = "tg-"

    fun isTelegramAddress(address: String) = address.startsWith(ADDRESS_PREFIX) && address.drop(ADDRESS_PREFIX.length).toLongOrNull() != null
    fun chatIdOf(address: String): Long? = if (address.startsWith(ADDRESS_PREFIX)) address.drop(ADDRESS_PREFIX.length).toLongOrNull() else null
    fun addressOf(chatId: Long) = "$ADDRESS_PREFIX$chatId"

    val isConfigured: Boolean get() = BuildConfig.TELEGRAM_API_ID != 0 && BuildConfig.TELEGRAM_API_HASH.isNotEmpty()

    private val _auth = MutableStateFlow<TgAuth>(if (isConfigured) TgAuth.Loading else TgAuth.NotConfigured)
    val auth: StateFlow<TgAuth> = _auth.asStateFlow()

    private val _conversations = MutableStateFlow<List<Conversation>>(emptyList())
    val conversations: StateFlow<List<Conversation>> = _conversations.asStateFlow()

    private val _messages = MutableStateFlow<List<SmsMessage>>(emptyList())
    val messages: StateFlow<List<SmsMessage>> = _messages.asStateFlow()

    /** Last request error (e.g. wrong code), cleared on the next successful step. */
    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private val chats = ConcurrentHashMap<Long, TgChat>()
    private val callbacks = ConcurrentHashMap<Long, (JSONObject) -> Unit>()
    private val extraCounter = AtomicLong(1)
    private val main = Handler(Looper.getMainLooper())

    @Volatile private var clientId = 0
    @Volatile private var started = false
    @Volatile private var receiver: Thread? = null
    @Volatile private var openChatId: Long? = null
    @Volatile private var appContext: Context? = null

    private fun dbDir(ctx: Context) = File(ctx.filesDir, "tdlib")

    /** True when a previous sign-in left a TDLib database, so the client should run in the background. */
    fun hasSession(ctx: Context) = dbDir(ctx).exists()

    @Synchronized
    fun start(ctx: Context) {
        if (!isConfigured) { _auth.value = TgAuth.NotConfigured; return }
        if (started) return
        appContext = ctx.applicationContext
        try {
            JsonClient.td_execute("""{"@type":"setLogVerbosityLevel","new_verbosity_level":1}""")
            clientId = JsonClient.td_create_client_id()
            started = true
            _auth.value = TgAuth.Loading
            if (receiver == null) {
                receiver = Thread({
                    while (true) {
                        try {
                            val raw = JsonClient.td_receive(30.0) ?: continue
                            handle(JSONObject(raw))
                        } catch (e: Exception) {
                            Log.e(TAG, "receive failed", e)
                        }
                    }
                }, "tdlib-receiver").apply { isDaemon = true; start() }
            }
            send(JSONObject().put("@type", "getOption").put("name", "version"))
        } catch (e: Throwable) {
            Log.e(TAG, "TDLib failed to start", e)
            started = false
            _auth.value = TgAuth.Failed(e.message ?: "TDLib")
        }
    }

    // ---- sending -------------------------------------------------------------------------

    private fun send(req: JSONObject, cb: ((JSONObject) -> Unit)? = null) {
        if (cb != null) {
            val id = extraCounter.getAndIncrement()
            callbacks[id] = cb
            req.put("@extra", id)
        }
        JsonClient.td_send(clientId, req.toString())
    }

    private fun obj(type: String) = JSONObject().put("@type", type)

    /** Raw TDLib request for other Telegram features (calls). */
    internal fun request(req: JSONObject, cb: ((JSONObject) -> Unit)? = null) {
        if (!started) { cb?.invoke(obj("error").put("message", "not started")); return }
        send(req, cb)
    }

    internal fun appContextOrNull(): Context? = appContext

    /** Name of a Telegram user we already have a private chat with (private chat id == user id), or "". */
    fun displayName(userId: Long): String = chats[userId]?.title.orEmpty()

    /** "First Last" from a TDLib user object. */
    fun nameOf(user: JSONObject): String =
        listOf(user.optString("first_name"), user.optString("last_name")).filter { it.isNotBlank() }.joinToString(" ")

    private fun errorHandler(onOk: (() -> Unit)? = null): (JSONObject) -> Unit = { r ->
        if (r.optString("@type") == "error") {
            _lastError.value = r.optString("message")
        } else {
            _lastError.value = null
            onOk?.invoke()
        }
    }

    fun submitPhone(phone: String) {
        _lastError.value = null
        val digits = phone.filter { it.isDigit() }
        send(obj("setAuthenticationPhoneNumber").put("phone_number", digits).put(
            "settings", obj("phoneNumberAuthenticationSettings")
                .put("allow_flash_call", false).put("allow_missed_call", false)
                .put("is_current_phone_number", true).put("allow_sms_retriever_api", false)
        ), errorHandler())
    }

    fun submitCode(code: String) {
        _lastError.value = null
        send(obj("checkAuthenticationCode").put("code", code.filter { it.isDigit() }), errorHandler())
    }

    fun submitPassword(password: String) {
        _lastError.value = null
        send(obj("checkAuthenticationPassword").put("password", password), errorHandler())
    }

    fun requestQrLogin() {
        _lastError.value = null
        send(obj("requestQrCodeAuthentication").put("other_user_ids", JSONArray()), errorHandler())
    }

    /** Signs out and wipes the local TDLib data. Also used to go back to the phone-number step. */
    fun logout() {
        send(obj("logOut"), errorHandler())
    }

    /** Called by SmsReceiver for every incoming SMS; fills in Telegram's login code automatically. */
    fun onSmsReceived(body: String) {
        if (_auth.value !is TgAuth.WaitCode) return
        val m = Regex("(?i)(?:telegram|login)\\s+code[:\\s]+(\\d{5,6})").find(body) ?: return
        submitCode(m.groupValues[1])
    }

    // ---- chats ---------------------------------------------------------------------------

    fun openChat(chatId: Long) {
        openChatId = chatId
        _messages.value = emptyList()
        send(obj("openChat").put("chat_id", chatId))
        fetchHistory(chatId, 0)
    }

    fun closeChat() {
        openChatId?.let { send(obj("closeChat").put("chat_id", it)) }
        openChatId = null
        _messages.value = emptyList()
    }

    private fun fetchHistory(chatId: Long, attempt: Int) {
        send(obj("getChatHistory").put("chat_id", chatId).put("from_message_id", 0).put("offset", 0)
            .put("limit", 50).put("only_local", false)) { r ->
            if (openChatId != chatId) return@send
            val arr = r.optJSONArray("messages") ?: return@send
            val list = (0 until arr.length()).mapNotNull { toSms(arr.getJSONObject(it)) }
            _messages.value = list
            markViewed(chatId, list.filter { !it.isSent }.map { it.id })
            // TDLib may return only a few cached messages first; ask again once it has loaded more.
            if (list.size < 10 && attempt < 2) main.postDelayed({ if (openChatId == chatId) fetchHistory(chatId, attempt + 1) }, 700)
        }
    }

    private fun markViewed(chatId: Long, ids: List<Long>) {
        if (ids.isEmpty()) return
        send(obj("viewMessages").put("chat_id", chatId).put("message_ids", JSONArray(ids)).put("force_read", true))
    }

    /** Temporary message id -> callback, for sends that need to know whether they reached Telegram. */
    private val pendingSends = ConcurrentHashMap<Long, (Boolean) -> Unit>()

    /**
     * Sends a text and suspends until Telegram confirms it (updateMessageSendSucceeded) or it fails.
     * Returns false on failure, timeout, or when Telegram isn't signed in.
     */
    suspend fun sendTextConfirmed(chatId: Long, text: String, timeoutMs: Long = 20_000): Boolean {
        if (_auth.value !is TgAuth.Ready) return false
        val result = kotlinx.coroutines.CompletableDeferred<Boolean>()
        send(obj("sendMessage").put("chat_id", chatId).put(
            "input_message_content", obj("inputMessageText").put("text", obj("formattedText").put("text", text))
        )) { r ->
            if (r.optString("@type") != "message") { result.complete(false); return@send }
            val tempId = r.optLong("id")
            // Already sent (rare): no sending_state means it is on the server.
            if (r.optJSONObject("sending_state") == null) { result.complete(true); return@send }
            pendingSends[tempId] = { ok -> result.complete(ok) }
        }
        return kotlinx.coroutines.withTimeoutOrNull(timeoutMs) { result.await() } ?: false
    }

    /** findChatByPhone as a suspend function. */
    suspend fun chatForPhone(phone: String): Long? = kotlinx.coroutines.suspendCancellableCoroutine { cont ->
        findChatByPhone(phone) { id -> if (cont.isActive) cont.resumeWith(Result.success(id)) }
    }

    /** Waits until Telegram is signed in and connected (starting the client if a session exists). */
    suspend fun awaitReady(ctx: Context, timeoutMs: Long = 15_000): Boolean {
        if (!isConfigured || !hasSession(ctx)) return false
        start(ctx)
        return kotlinx.coroutines.withTimeoutOrNull(timeoutMs) {
            auth.first { it is TgAuth.Ready || it is TgAuth.Failed || it is TgAuth.WaitPhone }
        } is TgAuth.Ready
    }

    fun sendText(chatId: Long, text: String) {
        send(obj("sendMessage").put("chat_id", chatId).put(
            "input_message_content", obj("inputMessageText").put("text", obj("formattedText").put("text", text))
        ), errorHandler())
    }

    /** Looks up a Telegram chat for a phone number. Calls back on the main thread with the chat id, or null. */
    fun findChatByPhone(phone: String, cb: (Long?) -> Unit) {
        val digits = phone.filter { it.isDigit() }
        if (digits.isEmpty() || _auth.value !is TgAuth.Ready) { main.post { cb(null) }; return }
        send(obj("searchUserByPhoneNumber").put("phone_number", digits).put("only_local", false)) { u ->
            if (u.optString("@type") != "user") { main.post { cb(null) }; return@send }
            send(obj("createPrivateChat").put("user_id", u.getLong("id")).put("force", false)) { c ->
                val id = if (c.optString("@type") == "chat") c.getLong("id") else null
                main.post { cb(id) }
            }
        }
    }

    // ---- update handling -----------------------------------------------------------------

    private fun handle(o: JSONObject) {
        val extra = o.optLong("@extra", 0L)
        if (extra != 0L) callbacks.remove(extra)?.invoke(o)
        when (o.optString("@type")) {
            "updateAuthorizationState" -> onAuth(o.getJSONObject("authorization_state"))
            "updateNewChat" -> upsertChat(o.getJSONObject("chat"))
            "updateChatLastMessage" -> chats[o.getLong("chat_id")]?.let { c ->
                val lm = o.optJSONObject("last_message")
                chats[c.id] = c.copy(lastText = lm?.let { textOf(it) } ?: "", lastDate = (lm?.optLong("date") ?: 0L) * 1000)
                publishChats()
            }
            "updateChatReadInbox" -> chats[o.getLong("chat_id")]?.let { c ->
                chats[c.id] = c.copy(unread = o.optInt("unread_count"))
                publishChats()
            }
            "updateChatTitle" -> chats[o.getLong("chat_id")]?.let { c ->
                chats[c.id] = c.copy(title = o.optString("title"))
                publishChats()
            }
            "updateNewMessage" -> onNewMessage(o.getJSONObject("message"))
            "updateCall", "updateNewCallSignalingData" -> TgCallManager.onUpdate(o)
            "updateMessageSendFailed" -> pendingSends.remove(o.optLong("old_message_id"))?.invoke(false)
            "updateMessageSendSucceeded" -> {
                val m = o.getJSONObject("message")
                val old = o.optLong("old_message_id")
                pendingSends.remove(old)?.invoke(true)
                if (m.optLong("chat_id") == openChatId) {
                    val sms = toSms(m)
                    _messages.value = _messages.value.filter { it.id != old && it.id != m.optLong("id") }.let { l ->
                        if (sms != null) (l + sms).sortedByDescending { it.timestamp } else l
                    }
                }
            }
        }
    }

    private fun onAuth(s: JSONObject) {
        when (s.optString("@type")) {
            "authorizationStateWaitTdlibParameters" -> {
                val ctx = appContext ?: return
                val dir = dbDir(ctx)
                send(obj("setTdlibParameters")
                    .put("use_test_dc", false)
                    .put("database_directory", dir.absolutePath)
                    .put("files_directory", File(dir, "files").absolutePath)
                    .put("database_encryption_key", "")
                    .put("use_file_database", false)
                    .put("use_chat_info_database", true)
                    .put("use_message_database", true)
                    .put("use_secret_chats", false)
                    .put("api_id", BuildConfig.TELEGRAM_API_ID)
                    .put("api_hash", BuildConfig.TELEGRAM_API_HASH)
                    .put("system_language_code", Locale.getDefault().language)
                    .put("device_model", Build.MODEL)
                    .put("system_version", Build.VERSION.RELEASE)
                    .put("application_version", BuildConfig.VERSION_NAME)
                ) { r -> if (r.optString("@type") == "error") _auth.value = TgAuth.Failed(r.optString("message")) }
            }
            "authorizationStateWaitPhoneNumber" -> _auth.value = TgAuth.WaitPhone
            "authorizationStateWaitCode" -> {
                val type = s.optJSONObject("code_info")?.optJSONObject("type")?.optString("@type").orEmpty()
                _auth.value = TgAuth.WaitCode(viaSms = type.startsWith("authenticationCodeTypeSms"))
            }
            "authorizationStateWaitOtherDeviceConfirmation" -> _auth.value = TgAuth.WaitQr(s.optString("link"))
            "authorizationStateWaitPassword" -> _auth.value = TgAuth.WaitPassword
            "authorizationStateWaitRegistration" -> _auth.value = TgAuth.NeedsRegistration
            "authorizationStateReady" -> {
                _lastError.value = null
                _auth.value = TgAuth.Ready
                val list = obj("chatListMain")
                // loadChats may answer with an error once everything is loaded; getChats works either way.
                send(obj("loadChats").put("chat_list", list).put("limit", 100)) {
                    send(obj("getChats").put("chat_list", obj("chatListMain")).put("limit", 100))
                }
            }
            "authorizationStateClosed" -> {
                // Logged out (or closed): the client is gone. Start a fresh one for a new sign-in.
                chats.clear(); publishChats(); _messages.value = emptyList()
                started = false
                openChatId = null
                appContext?.let { start(it) }
            }
        }
    }

    private fun upsertChat(c: JSONObject) {
        if (c.optJSONObject("type")?.optString("@type") != "chatTypePrivate") return
        val lm = c.optJSONObject("last_message")
        chats[c.getLong("id")] = TgChat(
            id = c.getLong("id"),
            title = c.optString("title"),
            lastText = lm?.let { textOf(it) } ?: "",
            lastDate = (lm?.optLong("date") ?: 0L) * 1000,
            unread = c.optInt("unread_count")
        )
        publishChats()
    }

    private fun publishChats() {
        _conversations.value = chats.values.filter { it.lastDate > 0 }
            .sortedByDescending { it.lastDate }.take(50)
            .map { Conversation(address = addressOf(it.id), snippet = it.lastText, date = it.lastDate, contactName = it.title, threadId = it.id, isRead = it.unread == 0, unreadCount = it.unread) }
    }

    private fun textOf(m: JSONObject): String {
        val c = m.optJSONObject("content") ?: return ""
        return if (c.optString("@type") == "messageText") c.optJSONObject("text")?.optString("text").orEmpty() else i18n(R.string.tg_media)
    }

    private fun toSms(m: JSONObject): SmsMessage? {
        val chatId = m.optLong("chat_id")
        return SmsMessage(
            id = m.optLong("id"), address = addressOf(chatId), body = textOf(m),
            timestamp = m.optLong("date") * 1000, isSent = m.optBoolean("is_outgoing"), isRead = true
        )
    }

    private fun onNewMessage(m: JSONObject) {
        val chatId = m.optLong("chat_id")
        val sms = toSms(m) ?: return
        if (chatId == openChatId) {
            if (_messages.value.none { it.id == sms.id }) _messages.value = listOf(sms) + _messages.value
            if (!sms.isSent) markViewed(chatId, listOf(sms.id))
        } else if (!sms.isSent) {
            notifyIncoming(chatId, sms.body)
        }
    }

    private fun notifyIncoming(chatId: Long, body: String) {
        val ctx = appContext ?: return
        try {
            val title = chats[chatId]?.title ?: "Telegram"
            val address = addressOf(chatId)
            val intent = Intent(ctx, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("NAVIGATE_TO", "sms"); putExtra("SMS_ADDRESS", address)
            }
            val pi = PendingIntent.getActivity(ctx, address.hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val n = NotificationCompat.Builder(ctx, LauncherApp.CH_SMS)
                .setSmallIcon(android.R.drawable.stat_notify_chat).setContentTitle(title).setContentText(body)
                .setPriority(NotificationCompat.PRIORITY_MAX).setAutoCancel(true).setContentIntent(pi).build()
            ctx.getSystemService(NotificationManager::class.java).notify(address.hashCode(), n)
        } catch (e: Exception) {
            Log.e(TAG, "notify failed", e)
        }
    }
}
