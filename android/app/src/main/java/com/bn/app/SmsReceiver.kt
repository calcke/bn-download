package com.bn.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony

/**
 * Three kinds of SMS matter here:
 *  - An M-Pesa receipt: find the offer for the amount, and queue a dial to the sender's number.
 *    If Safaricom hid some digits of that number, the number is looked up in your BN_ contacts,
 *    or the receipt is forwarded to 334 so the customer can approve sharing it.
 *  - The reply from 334 with the customer's number.
 *  - A credit token ("BN$V$..."): check its signature and add the credits.
 * Everything runs on the phone. No network calls.
 *
 * This runs inside the SMS broadcast, so it must never throw: every path is wrapped.
 */
class SmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        try {
            handle(context, intent)
        } catch (e: Exception) {
            try { Store.log(context, "SMS handling problem: ${e.javaClass.simpleName} ${e.message ?: ""}".take(140)) } catch (ignored: Exception) {}
        }
    }

    private fun handle(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val parts = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        val sender = parts.firstOrNull()?.originatingAddress ?: return
        val text = parts.joinToString("") { it.messageBody ?: "" }

        if (text.startsWith(Protocol.TOKEN_PREFIX + "\$V\$")) {
            CodeHandler.handle(context, text)   // logs the reason if it is refused
            return
        }

        // The answer from 334: "Your request for M-PESA Transaction ID ... has been approved. Here are the details: ..."
        val approval = Protocol.parseApproval(text)
        if (approval != null) {
            if (sender.filter { it.isDigit() } == "334" || Protocol.isMpesaSender(sender)) {
                handleApproval(context, approval)
            } else {
                Store.log(context, "Ignored a number message from an unknown sender ($sender)")
            }
            return
        }

        if (Protocol.isMpesaSender(sender)) {
            // The SIM the SMS arrived on. Phones put its id in one of two extras, depending on the make.
            val designated = Store.designatedSub(context)
            var arrivedOn = intent.getIntExtra("subscription", -1)
            if (arrivedOn == -1) arrivedOn = intent.getIntExtra("android.telephony.extra.SUBSCRIPTION_INDEX", -1)
            // If the phone doesn't say which SIM it was, accept the receipt rather than lose a sale.
            if (designated != -1 && arrivedOn != -1 && arrivedOn != designated) {
                Store.log(context, "Receipt ignored: it arrived on a line that isn't the chosen one")
                return
            }
            handlePayment(context, text)
        }
    }

    private fun handleApproval(ctx: Context, a: Protocol.Approval) {
        if (!a.approved) {
            Store.refuseNumber(ctx, a.txCode)
            return
        }
        val phone = Contacts.canonical(a.phone)
        if (phone == null) {
            Store.log(ctx, "334 gave a number I could not read for ${a.txCode}")
            return
        }
        val problem = Store.resolveJob(ctx, a.txCode, phone, a.name)
        if (problem != null) {
            Store.log(ctx, "334 reply for ${a.txCode} was not used: $problem")
        } else {
            OfflineService.ensureRunning(ctx)
        }
    }

    private fun handlePayment(ctx: Context, text: String) {
        val payment = Protocol.parsePayment(text)
        if (payment == null) {
            // Many M-Pesa messages are not payments (balances, money you sent). Only mention ones that look like a receipt.
            if (text.contains("received", ignoreCase = true) && text.contains("Confirmed", ignoreCase = true)) {
                Store.log(ctx, "Could not read this M-Pesa message: ${text.replace('\n', ' ').take(70)}")
            }
            return
        }
        val local = if (payment.masked) "" else Protocol.localPhone(payment.senderPhone)
        val who = if (payment.masked) "a hidden number (${payment.prefix}...${payment.suffix})" else local
        if (!Store.listening(ctx)) {
            Store.log(ctx, "Ignored Ksh${payment.amount} from $who (${payment.txCode}): listening is OFF")
            return
        }
        if (!Store.markSeen(ctx, payment.txCode)) {
            Store.log(ctx, "Same receipt arrived again (${payment.txCode}), skipped")   // never dial twice
            return
        }

        val offer = Store.offerFor(ctx, payment.amount)
        if (offer == null) {
            Store.recordNoOffer(ctx, payment.txCode, payment.amount, local, payment.name)
            return
        }

        if (!payment.masked) {
            val ussd = Store.ussdFor(offer.template, payment.senderPhone)
            Store.enqueue(ctx, payment.txCode, payment.amount, ussd, local, offer.label, payment.name)
            OfflineService.ensureRunning(ctx)
            return
        }

        // 1) a saved BN_<name> contact whose number fits the visible digits
        if (Store.flag(ctx, "use_contacts", true)) {
            val known = Contacts.find(ctx, payment.name, payment.prefix, payment.suffix)
            if (known != null) {
                val ussd = Store.ussdFor(offer.template, known)
                Store.enqueue(ctx, payment.txCode, payment.amount, ussd, Protocol.localPhone(known), offer.label, payment.name)
                Store.log(ctx, "Hidden number matched contact BN_${payment.name}")
                OfflineService.ensureRunning(ctx)
                return
            }
        }
        // 2) forward the receipt to 334 so the customer can approve sharing their number
        if (Store.flag(ctx, "use_334", true) && Sms.canSend(ctx) && Sms.send(ctx, "334", text)) {
            Store.enqueueMasked(ctx, payment.txCode, payment.amount, offer.template, offer.label, payment.name,
                payment.prefix, payment.suffix, "awaiting", "receipt sent to 334, waiting for the customer to approve")
            OfflineService.ensureRunning(ctx)   // the service also ends the wait if nobody approves
            return
        }
        // 3) nothing worked: the owner types the number in
        Store.enqueueMasked(ctx, payment.txCode, payment.amount, offer.template, offer.label, payment.name,
            payment.prefix, payment.suffix, "no_number", "no saved contact, and 334 was not used. Tap ENTER NUMBER.")
    }
}
