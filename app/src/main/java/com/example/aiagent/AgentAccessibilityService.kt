package com.example.aiagent

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Rootless Native Android Accessibility Service.
 * Provides automation capabilities without root:
 * - Direct app launching with Intent.FLAG_ACTIVITY_NEW_TASK
 * - UI node discovery and automated clicking
 * - System global actions (HOME, BACK, RECENTS)
 */
class AgentAccessibilityService : AccessibilityService() {

    private val tag = "AgentAccessibility"

    companion object {
        @Volatile
        var instance: AgentAccessibilityService? = null
            private set

        val isServiceRunning: Boolean
            get() = instance != null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(tag, "AgentAccessibilityService connected and ready for automation.")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Observes UI changes, window state transitions, and interactive views
    }

    override fun onInterrupt() {
        Log.w(tag, "AgentAccessibilityService interrupted.")
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        Log.i(tag, "AgentAccessibilityService destroyed.")
    }

    /**
     * Finds an installed application by display label or package name and opens it.
     */
    fun executeOpenApp(appNameQuery: String): Boolean {
        try {
            val pm = packageManager
            val cleanQuery = appNameQuery.trim().lowercase()

            // 1. Direct package match
            var launchIntent = pm.getLaunchIntentForPackage(appNameQuery)
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(launchIntent)
                Log.i(tag, "Launched package directly: $appNameQuery")
                return true
            }

            // 2. Search all launcher activities
            val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }
            val apps = pm.queryIntentActivities(mainIntent, 0)

            for (resolveInfo in apps) {
                val label = resolveInfo.loadLabel(pm).toString().lowercase()
                val pkg = resolveInfo.activityInfo.packageName.lowercase()

                if (label == cleanQuery || label.contains(cleanQuery) || pkg.contains(cleanQuery)) {
                    val targetPkg = resolveInfo.activityInfo.packageName
                    launchIntent = pm.getLaunchIntentForPackage(targetPkg)
                    if (launchIntent != null) {
                        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        startActivity(launchIntent)
                        Log.i(tag, "Launched app '$label' ($targetPkg)")
                        return true
                    }
                }
            }

            Log.w(tag, "Could not find installed app matching: '$appNameQuery'")
            return false
        } catch (e: Exception) {
            Log.e(tag, "Error launching app '$appNameQuery': ${e.message}", e)
            return false
        }
    }

    /**
     * Traverses the active window hierarchy to locate and click a node by text or View ID.
     */
    fun findAndClickNode(textOrId: String): Boolean {
        val root = rootInActiveWindow ?: return false
        try {
            // Try by View ID first
            val nodesById = root.findAccessibilityNodeInfosByViewId(textOrId)
            if (!nodesById.isNullOrEmpty()) {
                for (node in nodesById) {
                    if (performClickRecursive(node)) {
                        Log.i(tag, "Clicked node by ID: $textOrId")
                        return true
                    }
                }
            }

            // Try by Text
            val nodesByText = root.findAccessibilityNodeInfosByText(textOrId)
            if (!nodesByText.isNullOrEmpty()) {
                for (node in nodesByText) {
                    if (performClickRecursive(node)) {
                        Log.i(tag, "Clicked node by text: $textOrId")
                        return true
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "Error clicking node '$textOrId': ${e.message}", e)
        }
        return false
    }

    private fun performClickRecursive(node: AccessibilityNodeInfo?): Boolean {
        var current = node ?: return false
        while (true) {
            if (current.isClickable) {
                return current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            val parent = current.parent ?: return false
            current = parent
        }
    }
}
