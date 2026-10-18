package com.bn.app

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.telephony.TelephonyManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

private const val DIAL_TIMEOUT_MS = 90_000L
private const val STEP_TIMEOUT_MS = 150_000L
private const val BETWEEN_JOBS_MS = 5_000L
private const val IDLE_POLL_MS = 3_000L
private const val BUSY_RETRY_MS = 8_000L
private const val MAX_ATTEMPTS = 3
private const val CHANNEL_ID = "offline"
private const val NOTE_ID = 1
private const val SUMMARY_CHANNEL = "summary"
private const val SUMMARY_NOTE_ID = 2

/** The network's answer to a USSD code. [code] is Android's failure code when [ok] is false. */
/** [menu] is true when a step-by-step session ended on a menu that still wanted an answer (nothing was bought). */
internal data class DialResult(val ok: Boolean, val text: String, val code: Int = 0, val menu: Boolean = false)

/**
 * Dials queued USSD one at a time. Uses the cellular network only, so it works with no data.
 * A job is never dialed twice: a job interrupted mid-dial is marked "unknown" on restart.
 *
 * Nothing in here is allowed to crash the app: every step is guarded, and a problem is written
 * to the log and shown on screen instead.
 */
class OfflineService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "bn", NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(SUMMARY_CHANNEL, "Daily summary", NotificationManager.IMPORTANCE_DEFAULT)
        )
        startForeground(NOTE_ID, notification("Waiting for M-Pesa payments"))
        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "bn:offline")
            .apply { acquire() }

        safely { Store.recoverInterrupted(this) }
        Store.beat()
        // Heartbeat: lets the screen show whether the dialer is alive, even during a long dial.
        scope.launch {
            while (true) {
                Store.beat()
                delay(2_000L)
            }
        }
        scope.launch { workLoop() }
    }

    companion object {
        /** Starts the dialer if it isn't running. If Android refuses, the job stays queued and is logged. */
        fun ensureRunning(ctx: Context) {
            try {
                ctx.startForegroundService(Intent(ctx, OfflineService::class.java))
            } catch (e: Exception) {
                try {
                    Store.log(ctx, "Could not start the dialer (${e.javaClass.simpleName}). Open the app and tap REFRESH.")
                } catch (ignored: Exception) {}
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        try { wakeLock?.release() } catch (ignored: Exception) {}
        super.onDestroy()
    }

    // ---- helpers -------------------------------------------------------

    private fun notification(text: String): Notification =
        Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("bn is running")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .build()

    /** Shows what the dialer is doing, on the screen banner and in the notification. */
    private fun show(text: String) {
        Store.setLive(text)
        try {
            getSystemService(NotificationManager::class.java).notify(NOTE_ID, notification("Dialing  $text"))
        } catch (ignored: Exception) {}
    }

    private fun idle() {
        Store.clearLive()
        try {
            getSystemService(NotificationManager::class.java).notify(NOTE_ID, notification("Waiting for M-Pesa payments"))
        } catch (ignored: Exception) {}
    }

    private inline fun safely(block: () -> Unit) {
        try { block() } catch (e: CancellationException) { throw e } catch (ignored: Exception) {}
    }

    // ---- the loop ------------------------------------------------------

    private suspend fun workLoop() {
        while (true) {
            try {
                safely { Store.expireAwaiting(this) }
                safely { maybeSummary() }
                val job = Store.takeNext(this)
                if (job != null) {
                    runJob(job)
                    delay(BETWEEN_JOBS_MS)
                    continue
                }
                val listening = Store.listening(this)
                if (Store.balanceRequested(this) || (listening && Store.balanceDue(this))) {
                    checkBalance()
                } else if (!listening) {
                    // Listening is switched off and nothing is waiting: stop the service completely.
                    idle()
                    stopSelf()
                    return
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                safely { Store.log(this, "Dialer problem: ${e.javaClass.simpleName} ${e.message ?: ""}".take(140)) }
                idle()
                delay(10_000L)   // don't spin if something keeps failing
            }
            delay(IDLE_POLL_MS)
        }
    }

    private suspend fun runJob(job: org.json.JSONObject) {
        val tx = job.getString("tx")
        val ussd = job.getString("ussd")
        val amountText = job.optString("amount")
        val steps = ussd.split(">").map { it.trim() }.filter { it.isNotEmpty() }
        val stepwise = steps.size > 1
        var charged = 0L
        var status = "unknown"
        var message = ""
        var refund = false

        try {
            if (checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
                status = "failed"
                message = "Phone permission is off. Allow it in the app, then tap RETRY."
            } else if (stepwise && !UssdAccessibilityService.isEnabled(this)) {
                status = "failed"
                message = "The bn helper is off. Turn it on in Settings > Accessibility > bn USSD helper, then tap RETRY. Not dialed."
            } else {
                // Credits: the sale value is deducted before dialing. Not enough credits means no dial.
                val sale = Protocol.wholeShillings(amountText)
                if (sale == null || !Store.spendCredits(this, sale)) {
                    status = "no_credits"
                    message = "needs $amountText credits, balance ${Store.creditBalance(this)}. Not dialed. Top up, then tap RETRY."
                } else {
                    charged = sale
                    var attempt = 0
                    var result: DialResult? = null
                    while (true) {
                        attempt++
                        show("Ksh$amountText  ->  $ussd" + if (attempt > 1) "  (try $attempt)" else "")
                        safely { Store.log(this, "Dialing $tx: Ksh$amountText -> $ussd" + if (attempt > 1) " (try $attempt)" else "") }
                        result = if (stepwise) dialSteps(steps) else withTimeoutOrNull(DIAL_TIMEOUT_MS) { dial(ussd) }
                        // Code -2 means the network could not take the request at all, so it is safe to try again.
                        if (result != null && !result.ok && result.code == -2 && attempt < MAX_ATTEMPTS) {
                            show("Network busy. Trying again in ${BUSY_RETRY_MS / 1000}s...")
                            delay(BUSY_RETRY_MS)
                            continue
                        }
                        break
                    }
                    when {
                        // A timeout does not prove the bundle wasn't bought, so a person must check. Credits stay spent.
                        result == null -> {
                            status = "unknown"
                            message = "no reply in time. Check the customer got the bundle before retrying."
                        }
                        result.ok -> {
                            status = "done"
                            message = result.text
                        }
                        else -> {
                            status = "failed"
                            refund = true
                            message = failureText(result.code) +
                                (if (stepwise && result.text.isNotBlank()) " Reply: " + result.text.replace('\n', ' ').take(90) else "") +
                                " Credits returned."
                        }
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e   // the service is stopping; the job stays "dialing" and is marked unknown on restart
        } catch (e: Exception) {
            status = "failed"
            refund = charged > 0L
            message = "error before dialing: ${e.javaClass.simpleName} ${e.message ?: ""}".take(140) +
                if (refund) " Credits returned." else ""
        }

        if (refund && charged > 0L) safely { Store.refundCredits(this, charged) }
        Store.finish(this, tx, status, message)
        safely { afterJob(tx, status) }
        idle()
    }

    /** Save a new customer to contacts, and text the customer if the owner switched that on. */
    private fun afterJob(tx: String, status: String) {
        val info = Store.jobInfo(this, tx) ?: return
        if (status == "done" && info.phone.isNotBlank() && Store.flag(this, "save_contacts", true)) {
            Contacts.saveIfNew(this, info.name, info.phone)
        }
        val tellOk = status == "done" && Store.flag(this, "sms_ok", false)
        val tellFail = (status == "failed" || status == "no_credits") && Store.flag(this, "sms_fail", true)
        if ((tellOk || tellFail) && info.phone.isNotBlank() && Store.claimCustomerSms(this, tx)) {
            val template = if (tellOk) Store.text(this, "sms_ok_text", Store.DEFAULT_SMS_OK)
            else Store.text(this, "sms_fail_text", Store.DEFAULT_SMS_FAIL)
            val sent = Sms.send(this, info.phone, Sms.fill(template, info.name, info.label, info.amount))
            Store.log(this, if (sent) "Customer SMS sent to ${info.phone}" else "Could not send the customer SMS to ${info.phone} (allow SMS sending in SETTINGS)")
        }
    }

    /** Once a day, at the hour the owner chose, a notification with the day's totals. */
    private fun maybeSummary() {
        if (!Store.summaryDue(this)) return
        Store.markSummaryDone(this)
        val text = Store.summaryText(this)
        val note = Notification.Builder(this, SUMMARY_CHANNEL)
            .setContentTitle("bn: today's sales")
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .build()
        getSystemService(NotificationManager::class.java).notify(SUMMARY_NOTE_ID, note)
        Store.log(this, "Daily summary: $text")
    }

    /** Dials a code one step at a time through the bn helper, with the screen kept awake. */
    @Suppress("DEPRECATION")
    private suspend fun dialSteps(steps: List<String>): DialResult? {
        var wl: PowerManager.WakeLock? = null
        try {
            val lock = (getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(
                PowerManager.SCREEN_DIM_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP, "bn:ussd"
            )
            lock.acquire(STEP_TIMEOUT_MS + 5_000L)
            wl = lock
        } catch (ignored: Exception) {}
        try {
            return UssdSession.run(this, steps, STEP_TIMEOUT_MS)
        } finally {
            try { if (wl != null && wl.isHeld) wl.release() } catch (ignored: Exception) {}
        }
    }

    private fun failureText(code: Int): String = when (code) {
        -2 -> "The network could not take the request (USSD unavailable on this SIM)."
        -1 -> "The network refused the code or gave no answer."
        -3 -> "Could not start the call (check the Phone permission and the SIM)."
        else -> "The dial failed (code $code)."
    }

    /** Dials the balance code when nothing else is waiting. Its reply goes to the top bar. */
    private suspend fun checkBalance() {
        if (checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            Store.saveAirtime(this, "phone permission is off", ok = false)
            return
        }
        show("Checking airtime  ->  ${Store.BALANCE_CODE}")
        UssdSession.closeUntil = System.currentTimeMillis() + 20_000L
        try {
            val result = withTimeoutOrNull(DIAL_TIMEOUT_MS) { dial(Store.BALANCE_CODE) }
            when {
                result == null -> Store.saveAirtime(this, "no reply", ok = false)
                result.ok -> Store.saveAirtime(this, result.text, ok = true)
                else -> Store.saveAirtime(this, failureText(result.code), ok = false)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Store.saveAirtime(this, "check failed: ${e.javaClass.simpleName}", ok = false)
        } finally {
            idle()
        }
    }

    /**
     * Dials a USSD code and waits for the network's reply. No internet involved.
     * If a M-Pesa line has been chosen, the code is dialed on that SIM, not on the phone's default one.
     */
    private suspend fun dial(ussd: String): DialResult = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { cont ->
            val base = getSystemService(TELEPHONY_SERVICE) as TelephonyManager
            val sub = Store.designatedSub(this@OfflineService)
            val tm = if (sub != -1) base.createForSubscriptionId(sub) else base
            try {
                tm.sendUssdRequest(
                    ussd,
                    object : TelephonyManager.UssdResponseCallback() {
                        override fun onReceiveUssdResponse(
                            telephonyManager: TelephonyManager,
                            request: String,
                            response: CharSequence
                        ) {
                            if (cont.isActive) cont.resume(DialResult(true, response.toString()))
                        }

                        override fun onReceiveUssdResponseFailed(
                            telephonyManager: TelephonyManager,
                            request: String,
                            failureCode: Int
                        ) {
                            if (cont.isActive) cont.resume(DialResult(false, "failure code $failureCode", failureCode))
                        }
                    },
                    Handler(Looper.getMainLooper())
                )
            } catch (e: Exception) {
                if (cont.isActive) cont.resume(DialResult(false, "${e.javaClass.simpleName}: ${e.message ?: ""}", 0))
            }
        }
    }
}

/** Restarts the dialer after a reboot, if listening is switched on. */
class BootReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        try {
            if (intent.action == Intent.ACTION_BOOT_COMPLETED && Store.listening(context)) {
                OfflineService.ensureRunning(context)
            }
        } catch (ignored: Exception) {}
    }
}
