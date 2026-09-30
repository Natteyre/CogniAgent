package com.example.aiagent

import android.content.Context
import android.content.Intent
import android.telephony.SmsManager
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Routine and Macro Sequence Execution Engine.
 * Reads JSON-serialized action steps from the Room database using Gson,
 * and executes them asynchronously step-by-step with coroutine delays.
 */
class RoutineExecutor(
    private val context: Context,
    private val database: AgentDatabase,
    private val ttsManager: AgentTextToSpeechManager,
    private val settingsManager: DeviceSettingsManager
) {

    private val tag = "RoutineExecutor"
    private val gson = Gson()
    private val scope = CoroutineScope(Dispatchers.Default)

    /**
     * Executes a skill routine by its registered name in Room.
     */
    fun executeSkillByName(skillName: String, onFinished: (() -> Unit)? = null) {
        scope.launch {
            val dao = database.agentDao()
            val skill = dao.getSkillByName(skillName)
            if (skill == null) {
                Log.w(tag, "Skill '$skillName' not found in database.")
                ttsManager.speak("Nie znaleziono umiejętności o nazwie $skillName")
                onFinished?.invoke()
                return@launch
            }

            try {
                val listType = object : TypeToken<List<RoutineAction>>() {}.type
                val actions: List<RoutineAction> = gson.fromJson(skill.actionsJson, listType) ?: emptyList()
                Log.i(tag, "Executing skill '$skillName' with ${actions.size} action steps.")
                executeActionList(actions)
                onFinished?.invoke()
            } catch (e: Exception) {
                Log.e(tag, "Failed to parse/execute skill '$skillName': ${e.message}", e)
                onFinished?.invoke()
            }
        }
    }

    /**
     * Executes a list of routine actions sequentially.
     */
    suspend fun executeActionList(actions: List<RoutineAction>) {
        for (action in actions) {
            try {
                when (action.type.uppercase()) {
                    "SPEAK" -> {
                        val text = action.param1 ?: ""
                        if (text.isNotBlank()) {
                            ttsManager.speak(text)
                        }
                    }

                    "OPEN_APP" -> {
                        val appQuery = action.param1 ?: ""
                        if (appQuery.isNotBlank()) {
                            val opened = AgentAccessibilityService.instance?.executeOpenApp(appQuery) ?: false
                            if (!opened) {
                                openAppViaPackageManager(appQuery)
                            }
                        }
                    }

                    "SET_VOLUME" -> {
                        val percent = action.param1?.toIntOrNull() ?: 50
                        settingsManager.setMediaVolume(percent)
                    }

                    "SET_BRIGHTNESS" -> {
                        val percent = action.param1?.toIntOrNull() ?: 50
                        settingsManager.setScreenBrightness(percent)
                    }

                    "CLICK_NODE" -> {
                        val nodeTextOrId = action.param1 ?: ""
                        if (nodeTextOrId.isNotBlank()) {
                            AgentAccessibilityService.instance?.findAndClickNode(nodeTextOrId)
                        }
                    }

                    "SEND_SMS" -> {
                        val phone = action.param1
                        val msg = action.param2
                        if (!phone.isNullOrBlank() && !msg.isNullOrBlank()) {
                            sendSmsDirect(phone, msg)
                        }
                    }

                    "DELAY" -> {
                        val ms = action.param1?.toLongOrNull() ?: action.delayMs
                        delay(ms)
                    }

                    else -> {
                        Log.w(tag, "Unknown action type: ${action.type}")
                    }
                }
            } catch (e: Exception) {
                Log.e(tag, "Error executing action ${action.type}: ${e.message}", e)
            }

            if (action.delayMs > 0) {
                delay(action.delayMs)
            }
        }
    }

    private fun openAppViaPackageManager(appNameOrPackage: String) {
        try {
            val pm = context.packageManager
            var launchIntent = pm.getLaunchIntentForPackage(appNameOrPackage)

            if (launchIntent == null) {
                // Search installed apps matching query
                val installed = pm.getInstalledApplications(0)
                for (info in installed) {
                    val label = pm.getApplicationLabel(info).toString()
                    if (label.contains(appNameOrPackage, ignoreCase = true) ||
                        info.packageName.contains(appNameOrPackage, ignoreCase = true)
                    ) {
                        launchIntent = pm.getLaunchIntentForPackage(info.packageName)
                        if (launchIntent != null) break
                    }
                }
            }

            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(launchIntent)
            }
        } catch (e: Exception) {
            Log.e(tag, "Error launching app '$appNameOrPackage': ${e.message}")
        }
    }

    private fun sendSmsDirect(phoneNumber: String, message: String) {
        try {
            @Suppress("DEPRECATION")
            val smsManager: SmsManager = context.getSystemService(SmsManager::class.java)
                ?: SmsManager.getDefault()
            val parts = smsManager.divideMessage(message)
            smsManager.sendMultipartTextMessage(phoneNumber, null, parts, null, null)
            Log.i(tag, "Direct SMS sent to $phoneNumber: $message")
        } catch (e: Exception) {
            Log.e(tag, "Failed to send SMS to $phoneNumber: ${e.message}", e)
        }
    }
}
