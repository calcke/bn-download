package com.bn.app

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.telephony.SubscriptionManager
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Some bundle codes only work one step at a time: dial *188#, wait for the menu, type 5, wait, type 2, and so on.
 * The phone's USSD API cannot answer a menu, so this places the call normally and lets the bn helper
 * (an accessibility service) read each menu window, type the next step and press Send.
 *
 * The helper does nothing unless a session is [active], and it only looks at the phone's own USSD windows.
 */
internal object UssdSession {

    @Volatile var active = false

    /** Until this time, any phone pop-up (such as the balance reply) is closed by the helper. */
    @Volatile var closeUntil = 0L

    fun wantsEvents(): Boolean = active || System.currentTimeMillis() < closeUntil
    @Volatile private var steps: List<String> = emptyList()
    @Volatile private var next = 1
    @Volatile private var lastKey = ""
    @Volatile private var cont: kotlinx.coroutines.CancellableContinuation<DialResult>? = null
    private val warned = HashSet<String>()
    private val handler = Handler(Looper.getMainLooper())
    private val lock = Mutex()

    private val FAIL_WORDS = listOf(
        "invalid", "insufficient", "not enough", "failed", "unable", "error", "connection problem", "mmi"
    )
    private val PHONE_PACKAGES = listOf("phone", "telecom", "dialer", "incall", "ussd", "mmi", "telephony")

    /** Dials [list]: the first item is dialed, each later item is typed into the menu that follows. */
    suspend fun run(ctx: Context, list: List<String>, timeoutMs: Long): DialResult? =
        lock.withLock { runLocked(ctx, list, timeoutMs) }

    private suspend fun runLocked(ctx: Context, list: List<String>, timeoutMs: Long): DialResult? {
        steps = list
        next = 1
        lastKey = ""
        warned.clear()
        val result = withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine<DialResult> { c ->
                cont = c
                active = true
                c.invokeOnCancellation { active = false; cont = null }
                if (!place(ctx, list[0])) finish(DialResult(false, "could not start the call", -3))
            }
        }
        if (result == null) {
            // Timed out: close any menu left on the screen.
            UssdAccessibilityService.instance?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
        }
        active = false
        cont = null
        return result
    }

    private fun finish(r: DialResult) {
        val c = cont
        cont = null
        active = false
        if (c != null && c.isActive) c.resume(r)
    }

    @SuppressLint("MissingPermission")
    private fun place(ctx: Context, code: String): Boolean {
        val uri = Uri.parse("tel:" + Uri.encode(code))
        try {
            val tm = ctx.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
            val extras = Bundle()
            val handle = accountFor(ctx)
            if (handle != null) extras.putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, handle)
            tm.placeCall(uri, extras)
            return true
        } catch (e: Exception) {
            Store.log(ctx, "placeCall failed (${e.javaClass.simpleName}), trying the normal call")
        }
        return try {
            val i = Intent(Intent.ACTION_CALL, uri)
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(i)
            true
        } catch (e: Exception) {
            Store.log(ctx, "Could not start the call: ${e.javaClass.simpleName} ${e.message ?: ""}".take(120))
            false
        }
    }

    /** The calling account of the chosen M-Pesa SIM, if there are two SIMs. */
    @SuppressLint("MissingPermission")
    private fun accountFor(ctx: Context): PhoneAccountHandle? {
        val sub = Store.designatedSub(ctx)
        if (sub == -1) return null
        return try {
            val sm = ctx.getSystemService(SubscriptionManager::class.java)
            val slot = sm.getActiveSubscriptionInfo(sub)?.simSlotIndex ?: return null
            val tm = ctx.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
            tm.callCapablePhoneAccounts.getOrNull(slot)
        } catch (e: Exception) {
            null
        }
    }

    // ---- called by the helper for every window change while a session is active ----------------

    fun onWindow(svc: AccessibilityService, root: AccessibilityNodeInfo) {
        val pkg = root.packageName?.toString() ?: ""
        if (pkg == svc.packageName) return
        if (!active) {
            // No session, but a balance check just ran: close the phone's reply pop-up if it shows one.
            if (System.currentTimeMillis() < closeUntil && PHONE_PACKAGES.any { pkg.lowercase().contains(it) }) press(root, false)
            return
        }
        if (PHONE_PACKAGES.none { pkg.lowercase().contains(it) }) {
            if (warned.add(pkg)) Store.log(svc, "USSD helper: ignored a window from $pkg")
            return
        }

        val texts = ArrayList<String>()
        var input: AccessibilityNodeInfo? = null
        fun walk(n: AccessibilityNodeInfo?) {
            if (n == null) return
            val cls = n.className?.toString() ?: ""
            if (cls.contains("EditText") || n.isEditable) {
                if (input == null) input = n
            } else if (cls.endsWith("TextView") && !cls.contains("Button")) {
                val t = n.text?.toString()?.trim()
                if (!t.isNullOrEmpty()) texts.add(t)
            }
            for (i in 0 until n.childCount) walk(n.getChild(i))
        }
        walk(root)

        val message = texts.joinToString("\n").trim()
        val inputNode = input
        val low = message.lowercase()
        if (message.isEmpty() && inputNode == null) return
        if (inputNode == null && (low.contains("running") || low.contains("please wait") || low.contains("processing"))) return
        val key = message + "|" + (inputNode != null)
        if (key == lastKey) return
        lastKey = key

        if (inputNode != null && next < steps.size) {
            val reply = steps[next]
            next++
            Store.log(svc, "USSD step ${next - 1}: typing $reply")
            val args = Bundle()
            args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, reply)
            val typed = inputNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            handler.postDelayed({
                val pressed = press(root, true)
                if (!typed || !pressed) Store.log(svc, "USSD helper: could not ${if (!typed) "type" else "press Send"} on this phone's menu (window: $pkg)")
            }, 500L)
            return
        }

        // No more steps to type, or the menu ended early: this is the answer.
        val ok = !FAIL_WORDS.any { low.contains(it) } && !(inputNode == null && next < steps.size)
        Store.log(svc, "USSD answer: ${message.replace('\n', ' ').take(100)}")
        handler.postDelayed({ press(root, false) }, 300L)
        finish(DialResult(ok, message.take(200), if (ok) 0 else -1, inputNode != null))
    }

    /** Presses Send (positive) or OK/Cancel (to close) in the menu window. */
    private fun press(root: AccessibilityNodeInfo, positive: Boolean): Boolean {
        val ids = if (positive) listOf("android:id/button1") else listOf("android:id/button2", "android:id/button1")
        for (id in ids) {
            val found = root.findAccessibilityNodeInfosByViewId(id)
            if (!found.isNullOrEmpty() && found[0].performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        }
        val labels = if (positive) listOf("send", "ok", "reply", "submit", "yes") else listOf("cancel", "ok", "close", "dismiss")
        for (label in labels) {
            for (n in root.findAccessibilityNodeInfosByText(label)) {
                val isButton = n.isClickable || (n.className?.toString() ?: "").contains("Button")
                if (isButton && n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            }
        }
        return false
    }
}
