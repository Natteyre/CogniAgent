package com.example.aiagent

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

/**
 * Intercepts incoming SMS and Email (Gmail, EMUI Mail) notifications in the background.
 * Automatically verbalizes important communications in Polish via Text-to-Speech.
 */
class AgentNotificationListener : NotificationListenerService() {

    private val tag = "AgentNotification"
    private var ttsManager: AgentTextToSpeechManager? = null

    companion object {
        @Volatile
        var isConnected: Boolean = false
            private set
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        isConnected = true
        ttsManager = AgentTextToSpeechManager(applicationContext)
        Log.i(tag, "NotificationListener connected.")
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        isConnected = false
        ttsManager?.shutdown()
        ttsManager = null
        Log.i(tag, "NotificationListener disconnected.")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null || sbn.isOngoing) return

        val pkg = sbn.packageName?.lowercase() ?: return
        val extras = sbn.notification.extras ?: return

        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim()

        if (title.isNullOrBlank() && text.isNullOrBlank()) return

        // 1. Detect SMS apps (Google Messages, Huawei/AOSP MMS)
        val isSms = pkg.contains("messaging") || pkg.contains("mms") || pkg.contains("sms") || pkg == "com.huawei.message"

        // 2. Detect Email apps (Gmail, Huawei Mail, Outlook)
        val isEmail = pkg == "com.google.android.gm" || pkg.contains("email") || pkg.contains("outlook") || pkg.contains("mail")

        if (isSms) {
            val announcement = buildString {
                append("Nowa wiadomość SMS")
                if (!title.isNullOrBlank()) append(" od $title")
                if (!text.isNullOrBlank()) append(". Treść: $text")
            }
            Log.i(tag, "Intercepted SMS: $announcement")
            speakAnnouncement(announcement)
        } else if (isEmail) {
            val announcement = buildString {
                append("Nowy e-mail")
                if (!title.isNullOrBlank()) append(" od $title")
                if (!text.isNullOrBlank()) append(". Tytuł: $text")
            }
            Log.i(tag, "Intercepted Email: $announcement")
            speakAnnouncement(announcement)
        }
    }

    private fun speakAnnouncement(message: String) {
        if (ttsManager == null) {
            ttsManager = AgentTextToSpeechManager(applicationContext)
        }
        ttsManager?.speak(message)
    }

    override fun onDestroy() {
        super.onDestroy()
        isConnected = false
        ttsManager?.shutdown()
        ttsManager = null
    }
}
