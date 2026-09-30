package com.example.aiagent

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.provider.Settings
import android.util.Log

/**
 * Manages device hardware controls: Media/Ringer Audio Volume and Screen Brightness.
 * Properly validates Settings.System.canWrite() before modifying protected system settings.
 */
class DeviceSettingsManager(private val context: Context) {

    private val tag = "DeviceSettingsManager"
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    /**
     * Checks if the app has permission to write system settings (e.g. brightness).
     */
    fun canModifySystemSettings(): Boolean {
        return Settings.System.canWrite(context)
    }

    /**
     * Creates an Intent prompting the user to grant WRITE_SETTINGS permission.
     */
    fun getWriteSettingsIntent(): Intent {
        return Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
            data = Uri.parse("package:${context.packageName}")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
    }

    /**
     * Sets Media Volume as a percentage (0 to 100).
     */
    fun setMediaVolume(percent: Int): Boolean {
        return try {
            val clamped = percent.coerceIn(0, 100)
            val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val targetVolume = (clamped * maxVolume) / 100
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, targetVolume, AudioManager.FLAG_SHOW_UI)
            Log.i(tag, "Media volume set to $clamped% (level $targetVolume/$maxVolume)")
            true
        } catch (e: Exception) {
            Log.e(tag, "Failed to set media volume: ${e.message}", e)
            false
        }
    }

    /**
     * Gets current Media Volume as percentage.
     */
    fun getMediaVolumePercent(): Int {
        val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        return if (maxVolume > 0) (current * 100) / maxVolume else 0
    }

    /**
     * Sets Ringer/Notification Volume as percentage (0 to 100).
     */
    fun setRingerVolume(percent: Int): Boolean {
        return try {
            val clamped = percent.coerceIn(0, 100)
            val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_RING)
            val targetVolume = (clamped * maxVolume) / 100
            audioManager.setStreamVolume(AudioManager.STREAM_RING, targetVolume, AudioManager.FLAG_SHOW_UI)
            Log.i(tag, "Ringer volume set to $clamped% (level $targetVolume/$maxVolume)")
            true
        } catch (e: Exception) {
            Log.e(tag, "Failed to set ringer volume: ${e.message}", e)
            false
        }
    }

    /**
     * Sets Screen Brightness as percentage (0 to 100).
     * Requires Settings.System.canWrite() validation.
     */
    fun setScreenBrightness(percent: Int): Boolean {
        if (!canModifySystemSettings()) {
            Log.w(tag, "Cannot set brightness: WRITE_SETTINGS permission not granted!")
            return false
        }

        return try {
            val clamped = percent.coerceIn(0, 100)
            // Brightness range in Android is 0 - 255
            val brightnessValue = ((clamped * 255) / 100).coerceIn(1, 255)

            // Switch to manual mode so manual value takes effect
            Settings.System.putInt(
                context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
            )

            Settings.System.putInt(
                context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS,
                brightnessValue
            )
            Log.i(tag, "Screen brightness set to $clamped% (raw $brightnessValue/255)")
            true
        } catch (e: Exception) {
            Log.e(tag, "Failed to set screen brightness: ${e.message}", e)
            false
        }
    }

    /**
     * Gets current Screen Brightness as percentage (0 to 100).
     */
    fun getScreenBrightnessPercent(): Int {
        return try {
            val raw = Settings.System.getInt(
                context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS,
                128
            )
            (raw * 100) / 255
        } catch (e: Exception) {
            50
        }
    }
}
