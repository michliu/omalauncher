package dev.omalauncher

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent

/**
 * Locks the screen for the "lock screen" gesture. Android only lets an accessibility
 * service do that without device-admin rights, so this one exists for nothing else: it
 * takes no events and reads no windows. The user turns it on in Android's accessibility
 * settings; [requestAccess] takes them there.
 */
class LockService : AccessibilityService() {
    override fun onServiceConnected() {
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    companion object {
        private var instance: LockService? = null

        private fun component(context: Context) = ComponentName(context, LockService::class.java)

        /** Whether the user has turned the service on. */
        fun enabled(context: Context): Boolean {
            val on = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
            val me = component(context)
            return on.split(':').any { ComponentName.unflattenFromString(it) == me }
        }

        /** Android's accessibility settings, where the service is turned on. */
        fun requestAccess(context: Context) {
            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }

        /** Locks the screen, or asks for access when the service isn't running. */
        fun lock(context: Context) {
            val service = instance
            if (service == null || !enabled(context)) return requestAccess(context)
            service.performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
        }
    }
}
