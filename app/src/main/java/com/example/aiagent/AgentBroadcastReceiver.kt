package com.example.aiagent

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Captures hardware triggers (charger plugged/unplugged, battery low/okay) in the background.
 * Optimized for EMUI/Doze mode: uses goAsync() and acquires a temporary PARTIAL_WAKE_LOCK
 * inside a try-finally block to ensure background processing completes safely without CPU sleep.
 */
class AgentBroadcastReceiver : BroadcastReceiver() {

    private val tag = "AgentBroadcastReceiver"

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        Log.i(tag, "Hardware trigger received: $action")

        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "CogniAgent:HardwareTriggerWakeLock"
        )

        // Asynchronously process trigger without blocking main thread
        val pendingResult = goAsync()
        wakeLock.acquire(10_000L) // 10-second safety timeout

        CoroutineScope(Dispatchers.IO).launch {
            try {
                handleTrigger(context, action)
            } catch (e: Exception) {
                Log.e(tag, "Error handling broadcast trigger: ${e.message}", e)
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

    private suspend fun handleTrigger(context: Context, action: String) {
        val database = AgentDatabase.getInstance(context)
        val dao = database.agentDao()

        val triggerType = when (action) {
            Intent.ACTION_POWER_CONNECTED -> "POWER_CONNECTED"
            Intent.ACTION_POWER_DISCONNECTED -> "POWER_DISCONNECTED"
            Intent.ACTION_BATTERY_LOW -> "BATTERY_LOW"
            Intent.ACTION_BATTERY_OKAY -> "BATTERY_OKAY"
            else -> action
        }

        // Query active routines tied to this trigger type
        val routines = dao.getActiveRoutinesByTrigger(triggerType)
        Log.i(tag, "Found ${routines.size} active routines for trigger $triggerType")

        val tts = AgentTextToSpeechManager(context)
        val settings = DeviceSettingsManager(context)
        val executor = RoutineExecutor(context, database, tts, settings)

        if (routines.isEmpty()) {
            // Default spoken hardware feedback
            when (triggerType) {
                "POWER_CONNECTED" -> tts.speak("Ładowarka podłączona. Rozpoczynam ładowanie baterii.")
                "POWER_DISCONNECTED" -> tts.speak("Ładowarka odłączona.")
                "BATTERY_LOW" -> tts.speak("Uwaga: niski poziom naładowania baterii. Podłącz ładowarkę.")
            }
        } else {
            for (routine in routines) {
                executor.executeSkillByName(routine.targetSkillName)
            }
        }
    }
}
