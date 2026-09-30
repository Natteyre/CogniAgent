package com.example.aiagent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.MainActivity

/**
 * Central Background Foreground Service.
 * Runs with START_STICKY and creates a persistent notification channel to prevent
 * aggressive Huawei EMUI and Android Doze battery optimizations from killing the engine.
 */
class AgentBackgroundEngineService : Service() {

    private val tag = "BackgroundEngine"
    private var hybridAgentManager: HybridAgentManager? = null

    companion object {
        const val NOTIFICATION_ID = 1001
        const val CHANNEL_ID = "cogni_agent_engine_channel"
        const val CHANNEL_NAME = "CogniAgent Background Engine"

        const val ACTION_START = "com.example.aiagent.action.START"
        const val ACTION_STOP = "com.example.aiagent.action.STOP"
        const val ACTION_TRIGGER_VOICE = "com.example.aiagent.action.TRIGGER_VOICE"

        @Volatile
        var isRunning: Boolean = false
            private set

        fun startService(context: Context) {
            val intent = Intent(context, AgentBackgroundEngineService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopService(context: Context) {
            val intent = Intent(context, AgentBackgroundEngineService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }

        fun triggerVoiceInput(context: Context) {
            val intent = Intent(context, AgentBackgroundEngineService::class.java).apply {
                action = ACTION_TRIGGER_VOICE
            }
            context.startService(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(tag, "AgentBackgroundEngineService created.")
        createNotificationChannel()
        hybridAgentManager = HybridAgentManager(applicationContext)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START

        when (action) {
            ACTION_START -> {
                startForegroundEngine()
                isRunning = true
            }
            ACTION_STOP -> {
                stopForegroundEngine()
                stopSelf()
                isRunning = false
            }
            ACTION_TRIGGER_VOICE -> {
                hybridAgentManager?.startVoiceListening()
            }
        }

        return START_STICKY
    }

    private fun startForegroundEngine() {
        val notification = buildPersistentNotification("CogniAgent czuwa w tle (Gotowy do poleceń)")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        Log.i(tag, "Foreground engine running.")
    }

    private fun stopForegroundEngine() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        hybridAgentManager?.destroy()
        hybridAgentManager = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Utrzymuje proces CogniAgent aktywny w tle na smartfonie."
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildPersistentNotification(statusText: String): Notification {
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("CogniAgent • Asystent Kognitywny")
            .setContentText(statusText)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        hybridAgentManager?.destroy()
        hybridAgentManager = null
        Log.i(tag, "AgentBackgroundEngineService destroyed.")
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
