package com.bn.app

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * The "bn USSD helper". Turn it on once in Settings > Accessibility. It reads only the phone's USSD menu windows,
 * and only while bn is dialing a step-by-step code. At every other time it returns immediately and does nothing.
 */
class UssdAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || !UssdSession.wantsEvents()) return
        try {
            val type = event.eventType
            if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
                type != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) return
            var node: AccessibilityNodeInfo? = event.source
            if (node != null) {
                var parent = node.parent
                while (parent != null) {
                    node = parent
                    parent = node.parent
                }
            } else {
                node = rootInActiveWindow
            }
            if (node != null) UssdSession.onWindow(this, node)
        } catch (e: Exception) {
            // never crash the phone's accessibility system
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    companion object {
        @Volatile var instance: UssdAccessibilityService? = null

        /** True when the helper is switched on in the phone's Accessibility settings. */
        fun isEnabled(ctx: Context): Boolean {
            val list = try {
                Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            } catch (e: Exception) {
                null
            } ?: return false
            val me = ComponentName(ctx, UssdAccessibilityService::class.java)
            return list.split(':').any {
                it.equals(me.flattenToString(), true) || it.equals(me.flattenToShortString(), true)
            }
        }
    }
}
