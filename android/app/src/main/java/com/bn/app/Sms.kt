package com.bn.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.telephony.SmsManager

/** Sends text messages from the M-Pesa SIM. Each one uses the phone's airtime. */
object Sms {

    fun canSend(ctx: Context): Boolean =
        ctx.checkSelfPermission(Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED

    @Suppress("DEPRECATION")
    private fun manager(ctx: Context): SmsManager {
        val sub = Store.designatedSub(ctx)
        return if (sub != -1) SmsManager.getSmsManagerForSubscriptionId(sub) else SmsManager.getDefault()
    }

    /** True if the phone accepted the message for sending. */
    fun send(ctx: Context, to: String, text: String): Boolean {
        if (!canSend(ctx) || to.isBlank() || text.isBlank()) return false
        return try {
            val m = manager(ctx)
            val parts = m.divideMessage(text)
            if (parts.size > 1) m.sendMultipartTextMessage(to, null, parts, null, null)
            else m.sendTextMessage(to, null, text, null, null)
            true
        } catch (e: Exception) {
            Store.log(ctx, "SMS problem: ${e.javaClass.simpleName} ${e.message ?: ""}".take(120))
            false
        }
    }

    /** Fills {name}, {offer} and {amount} in a message. The name is just the first name, nicely capitalised. */
    fun fill(template: String, name: String, offer: String, amount: String): String {
        val first = name.trim().split(" ").firstOrNull { it.isNotBlank() } ?: "customer"
        val nice = first.lowercase().replaceFirstChar { it.uppercase() }
        return template.replace("{name}", nice).replace("{offer}", offer).replace("{amount}", amount)
    }
}
