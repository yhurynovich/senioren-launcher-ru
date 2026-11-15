package com.seniorenlauncher.service

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class NotificationListener : NotificationListenerService() {

    companion object {
        private const val TAG = "NotificationListener"
        private val _activeNotifications = MutableStateFlow<List<StatusBarNotification>>(emptyList())
        val activeNotificationsFlow: StateFlow<List<StatusBarNotification>> = _activeNotifications

        var instance: NotificationListener? = null
            private set

        fun getBadgeCountByAppId(appId: String, appMappings: Map<String, String>, counts: Map<String, Int>): Int {
            return when (appId) {
                "phone" -> {
                    (counts["com.android.server.telecom"] ?: 0) +
                    (counts["com.google.android.dialer"] ?: 0) +
                    (counts["com.android.dialer"] ?: 0) +
                    (counts["com.samsung.android.dialer"] ?: 0)
                }
                "sms" -> {
                    (counts["com.google.android.apps.messaging"] ?: 0) +
                    (counts["com.android.mms"] ?: 0) +
                    (counts["com.samsung.android.messaging"] ?: 0) +
                    (counts["com.seniorenlauncher"] ?: 0)
                }
                "whatsapp" -> counts["com.whatsapp"] ?: 0
                "calendar" -> {
                    (counts["com.google.android.calendar"] ?: 0) +
                    (counts["com.samsung.android.calendar"] ?: 0)
                }
                else -> {
                    if (appId.startsWith("mapped_")) {
                        val pkg = appMappings[appId]
                        if (pkg != null) counts[pkg] ?: 0 else 0
                    } else {
                        counts[appId] ?: 0
                    }
                }
            }
        }
        
        // Legacy support for badge counts
        private val _notifications = MutableStateFlow<Map<String, Int>>(emptyMap())
        val notifications: StateFlow<Map<String, Int>> = _notifications

        /** Official Telegram clients that ring for the same account as the launcher's built-in Telegram. */
        private val TELEGRAM_APPS = setOf(
            "org.telegram.messenger", "org.telegram.messenger.web", "org.telegram.messenger.beta", "org.thunderdog.challegram"
        )

        /**
         * While the launcher's own Telegram call screen is up, hide the official Telegram app's
         * call banner for the same call (both clients ring). Needs notification access.
         */
        fun hideTelegramCallBanners() {
            val l = instance ?: return
            try { l.activeNotifications?.forEach { l.hideIfTelegramCall(it) } } catch (e: Exception) { Log.w(TAG, "sweep", e) }
        }

        /**
         * Clear everything. Messengers (Telegram, WhatsApp…) post a notification again while the chat
         * is still unread, so first press their "Mark as read" action, then remove the notification.
         */
        fun dismissAll() {
            val l = instance ?: return
            try { l.activeNotifications?.forEach { markRead(it) } } catch (e: Exception) { Log.w(TAG, "mark read", e) }
            l.cancelAllNotifications()
        }

        fun dismiss(key: String) {
            try {
                val l = instance ?: return
                l.activeNotifications?.firstOrNull { it.key == key }?.let { markRead(it) }
                l.cancelNotification(key)
            } catch (e: Exception) {
                Log.e(TAG, "Error dismissing notification: $key", e)
            }
        }

        /** Words for "read" in the "Mark as read" button, for apps that don't tag the action. */
        private val READ_WORDS = listOf("read", "прочит", "gelezen", "gelesen", "comme lu", "okundu", "مقروء", "قراءة")

        private fun markRead(sbn: StatusBarNotification) {
            val actions = sbn.notification.actions ?: return
            val action = actions.firstOrNull {
                android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P &&
                    it.semanticAction == Notification.Action.SEMANTIC_ACTION_MARK_AS_READ
            } ?: actions.firstOrNull { a ->
                // Only actions without text input (not "Reply"), whose title says "read".
                a.remoteInputs.isNullOrEmpty() && a.title?.toString()?.lowercase()?.let { t -> READ_WORDS.any { w -> w in t } } == true
            } ?: return
            try {
                action.actionIntent?.send()
                Log.i(TAG, "marked as read: ${sbn.packageName}")
            } catch (e: Exception) {
                Log.w(TAG, "mark read ${sbn.packageName}", e)
            }
        }
    }

    private fun hideIfTelegramCall(sbn: StatusBarNotification) {
        if (sbn.packageName !in TELEGRAM_APPS) return
        val call = com.seniorenlauncher.data.telegram.TgCallManager.current.value ?: return
        if (call.phase is com.seniorenlauncher.data.telegram.TgCallPhase.Ended) return
        val n = sbn.notification
        if (n.category != Notification.CATEGORY_CALL && n.fullScreenIntent == null) return
        Log.i(TAG, "hiding Telegram app call notification ${sbn.key}")
        // Cancel works for normal notifications; a foreground-service one can only be snoozed.
        try { cancelNotification(sbn.key) } catch (e: Exception) { Log.w(TAG, "cancel", e) }
        try { snoozeNotification(sbn.key, 3 * 60_000L) } catch (e: Exception) { Log.w(TAG, "snooze", e) }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        Log.d(TAG, "Notification posted from: ${sbn?.packageName}")
        sbn?.let { hideIfTelegramCall(it) }
        updateState()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        Log.d(TAG, "Notification removed from: ${sbn?.packageName}")
        updateState()
    }

    override fun onListenerConnected() {
        Log.d(TAG, "Notification listener connected")
        instance = this
        updateState()
    }

    override fun onListenerDisconnected() {
        Log.d(TAG, "Notification listener disconnected")
        instance = null
    }

    private fun updateState() {
        try {
            val active = try { activeNotifications } catch (e: Exception) { null } ?: emptyArray()
            
            // Loosened filter logic: Include more by default, exclude obvious noise
            val filtered = active.filter { sbn ->
                if (sbn.isOngoing) return@filter false
                
                val pkg = sbn.packageName
                val category = sbn.notification.category
                
                // Exclude some very common system noise
                if (pkg == "android" && category != Notification.CATEGORY_MESSAGE && category != Notification.CATEGORY_CALL) {
                    if (sbn.id == 17) return@filter false // Android System "USB debugging connected" etc
                    if (category == Notification.CATEGORY_SYSTEM) return@filter false
                }
                if (pkg == "com.android.systemui") return@filter false
                
                val isCommunication = category == Notification.CATEGORY_MESSAGE || 
                                     category == Notification.CATEGORY_CALL ||
                                     category == Notification.CATEGORY_EMAIL ||
                                     category == Notification.CATEGORY_EVENT ||
                                     category == Notification.CATEGORY_REMINDER
                
                // Known important packages that might not have categories set
                val importantPackages = listOf(
                    "com.whatsapp", 
                    "com.google.android.apps.messaging", 
                    "com.android.mms", 
                    "com.samsung.android.messaging",
                    "com.seniorenlauncher",
                    "com.google.android.dialer",
                    "com.samsung.android.dialer",
                    "com.android.dialer"
                )
                
                isCommunication || pkg in importantPackages || pkg.contains("mail") || pkg.contains("message")
            }
            
            _activeNotifications.value = filtered
            
            // Update legacy badge counts
            val counts = mutableMapOf<String, Int>()
            filtered.forEach { sbn ->
                counts[sbn.packageName] = (counts[sbn.packageName] ?: 0) + 1
            }
            _notifications.value = counts
            
        } catch (e: Exception) {
            Log.e(TAG, "Error updating notification state", e)
        }
    }
}
