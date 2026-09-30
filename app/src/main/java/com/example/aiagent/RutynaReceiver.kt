package com.example.aiagent

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Exact Alarm Receiver for timed routines and scheduled automation.
 * Operates reliably across Doze Mode on Android 10+ using AlarmManager.setExactAndAllowWhileIdle().
 */
class RutynaReceiver : BroadcastReceiver() {

    private val tag = "RutynaReceiver"

    override fun onReceive(context: Context, intent: Intent) {
        val skillName = intent.getStringExtra(EXTRA_SKILL_NAME) ?: return
        val routineId = intent.getLongExtra(EXTRA_ROUTINE_ID, -1L)
        Log.i(tag, "Received scheduled routine trigger for skill: '$skillName' (routineId: $routineId)")

        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "CogniAgent:RutynaWakeLock"
        )

        val pendingResult = goAsync()
        wakeLock.acquire(15_000L) // 15 seconds wake lock

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val database = AgentDatabase.getInstance(context)
                val tts = AgentTextToSpeechManager(context)
                val settings = DeviceSettingsManager(context)
                val executor = RoutineExecutor(context, database, tts, settings)

                executor.executeSkillByName(skillName)
            } catch (e: Exception) {
                Log.e(tag, "Error executing scheduled routine: ${e.message}", e)
            } finally {
                try {
                    if (wakeLock.isHeld) {
                        wakeLock.release()
                    }
                } catch (e: Exception) {
                    Log.e(tag, "Error releasing wake lock: ${e.message}")
                }
                pendingResult.finish()
            }
        }
    }

    companion object {
        const val EXTRA_SKILL_NAME = "extra_skill_name"
        const val EXTRA_ROUTINE_ID = "extra_routine_id"

        /**
         * Schedules an exact alarm routine that executes even while the phone is idling or screen is locked.
         */
        fun scheduleExactRoutine(
            context: Context,
            routineId: Long,
            skillName: String,
            triggerAtMillis: Long
        ) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            val intent = Intent(context, RutynaReceiver::class.java).apply {
                putExtra(EXTRA_ROUTINE_ID, routineId)
                putExtra(EXTRA_SKILL_NAME, skillName)
            }

            val pendingIntent = PendingIntent.getBroadcast(
                context,
                routineId.toInt(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            try {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAtMillis,
                    pendingIntent
                )
                Log.i("RutynaReceiver", "Scheduled exact alarm for skill '$skillName' at $triggerAtMillis")
            } catch (e: SecurityException) {
                Log.e("RutynaReceiver", "SecurityException scheduling exact alarm: ${e.message}")
            }
        }

        fun cancelRoutine(context: Context, routineId: Long) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            val intent = Intent(context, RutynaReceiver::class.java)
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                routineId.toInt(),
                intent,
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
            )
            if (pendingIntent != null) {
                alarmManager.cancel(pendingIntent)
                pendingIntent.cancel()
                Log.i("RutynaReceiver", "Cancelled alarm for routineId: $routineId")
            }
        }
    }
}
