package com.bn.app

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray
import org.json.JSONObject

/** A sale that is waiting for the customer's real number (their receipt showed it with digits hidden). */
data class NeedsNumber(
    val tx: String, val amount: String, val label: String, val name: String,
    val prefix: String, val suffix: String, val status: String, val created: Long
)

data class Customer(
    val phone: String, val name: String, val total: Long, val count: Int, val last: Long, val referrer: String
)

data class Period(val sales: Int, val money: Long, val failed: Int)

data class JobInfo(val phone: String, val name: String, val label: String, val amount: String)

/**
 * Everything the phone knows, kept encrypted on the phone (Android Keystore-backed).
 * Nothing here needs a network.
 *
 * Every write uses commit() (not apply()) so it is on disk before the next step runs.
 * That matters for money-related state: a job marked "dialing", a credit spent, a receipt seen.
 * If the phone dies a second later, the saved state must already be true.
 */
object Store {
    private const val FILE = "bn_offline"
    private const val MAX_SEEN = 500
    private const val MAX_LOG = 500
    private const val MAX_JOBS = 500
    private const val RETRY_WINDOW_S = 2L * 60L * 60L
    const val JOB_TTL_SECONDS = 900L

    @Volatile private var cached: SharedPreferences? = null

    // ---- in-memory live state (not saved; the screen reads it every second) ----------------
    @Volatile private var liveText: String = ""
    @Volatile private var liveSince: Long = 0L
    @Volatile private var lastBeat: Long = 0L

    /** What the dialer is doing right now, e.g. "Ksh1 -> *130*0712345678#". Empty when idle. */
    fun setLive(text: String) { liveText = text; liveSince = System.currentTimeMillis() }
    fun clearLive() { liveText = "" }
    fun liveNow(): String = liveText
    fun liveSeconds(): Long = if (liveText.isBlank()) 0L else (System.currentTimeMillis() - liveSince) / 1000L

    /** The dialer calls this every couple of seconds. The screen uses it to tell if the dialer is alive. */
    fun beat() { lastBeat = System.currentTimeMillis() }
    fun dialerAlive(): Boolean = System.currentTimeMillis() - lastBeat < 12_000L

    private fun stamp(): String =
        java.text.SimpleDateFormat("dd MMM HH:mm:ss", java.util.Locale.US).format(java.util.Date())

    private fun openPrefs(app: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(app).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        return EncryptedSharedPreferences.create(
            app, FILE, masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    private fun prefs(ctx: Context): SharedPreferences {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val app = ctx.applicationContext
            val p = try {
                openPrefs(app)
            } catch (e: Exception) {
                // The saved data can't be read (for example the phone's keystore was reset).
                // Starting fresh is better than crashing every time the app opens.
                try { app.deleteSharedPreferences(FILE) } catch (ignored: Exception) {}
                try {
                    val ks = java.security.KeyStore.getInstance("AndroidKeyStore")
                    ks.load(null)
                    ks.deleteEntry("_androidx_security_master_key_")
                } catch (ignored: Exception) {}
                openPrefs(app).also {
                    it.edit().putString("log",
                        "${stamp()}  Saved data could not be read and was reset. Offers are back to the defaults and credits are 0. Ask for a new credit token.")
                        .commit()
                }
            }
            cached = p
            return p
        }
    }

    // ---- this phone's ID (shown in the app; the seller puts it into your credit token) ----------

    @Synchronized
    fun deviceId(ctx: Context): String {
        val p = prefs(ctx)
        val saved = p.getString("device_id", "") ?: ""
        if (saved.length == 10) return saved
        val fresh = Protocol.newDeviceId()
        p.edit().putString("device_id", fresh).commit()
        return fresh
    }

    // ---- listening switch ----------------------------------------------------------------------

    @Synchronized
    fun listening(ctx: Context): Boolean = prefs(ctx).getBoolean("listening", true)

    @Synchronized
    fun setListening(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean("listening", on).commit()
        log(ctx, if (on) "Listening for payments: ON" else "Listening for payments: OFF")
    }

    /** The text stored on a sale: the steps joined by " > " (one step means a normal one-shot code). */
    fun ussdFor(template: String, phone254: String): String =
        Protocol.joinSteps(Protocol.steps(template, phone254))

    // ---- settings the owner can change ---------------------------------------------------------

    const val DEFAULT_SMS_OK = "Hi {name}, your {offer} (Ksh {amount}) has been sent. Thank you!"
    const val DEFAULT_SMS_FAIL = "Sorry {name}, your {offer} (Ksh {amount}) could not be delivered. We are fixing it and will get back to you."
    const val DEFAULT_WINBACK = "Hi {name}, we miss you! Buy your data and minutes bundles from us today."

    @Synchronized
    fun flag(ctx: Context, key: String, def: Boolean): Boolean = prefs(ctx).getBoolean("set_$key", def)

    @Synchronized
    fun setFlag(ctx: Context, key: String, value: Boolean) {
        prefs(ctx).edit().putBoolean("set_$key", value).commit()
    }

    @Synchronized
    fun text(ctx: Context, key: String, def: String): String = prefs(ctx).getString("set_$key", null) ?: def

    @Synchronized
    fun setText(ctx: Context, key: String, value: String) {
        prefs(ctx).edit().putString("set_$key", value).commit()
    }

    @Synchronized
    fun num(ctx: Context, key: String, def: Int): Int = prefs(ctx).getInt("set_$key", def)

    @Synchronized
    fun setNum(ctx: Context, key: String, value: Int) {
        prefs(ctx).edit().putInt("set_$key", value).commit()
    }

    // ---- offers (the merchant's own list; stored as JSON) --------------------------------------

    private fun offerToJson(o: Protocol.Offer): JSONObject = JSONObject()
        .put("id", o.id).put("cat", o.category).put("amt", o.amount)
        .put("label", o.label).put("code", o.template).put("on", o.enabled)
        .put("from", o.from).put("to", o.to)

    @Synchronized
    fun offers(ctx: Context): List<Protocol.Offer> {
        val p = prefs(ctx)
        val raw = p.getString("offers_v2", null)
        if (raw == null) {
            val defaults = Protocol.defaultOffers()
            saveOffers(ctx, defaults)
            return defaults
        }
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val j = arr.getJSONObject(i)
                Protocol.Offer(
                    j.getString("id"), j.getString("cat"), j.getString("amt"),
                    j.optString("label", ""), j.optString("code", ""), j.optBoolean("on", false),
                    j.optString("from", ""), j.optString("to", "")
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    @Synchronized
    fun saveOffers(ctx: Context, list: List<Protocol.Offer>) {
        val arr = JSONArray()
        list.forEach { arr.put(offerToJson(it)) }
        prefs(ctx).edit().putString("offers_v2", arr.toString()).commit()
    }

    /** Adds a new offer, or replaces the one with the same id. */
    @Synchronized
    fun upsertOffer(ctx: Context, offer: Protocol.Offer) {
        val list = offers(ctx).toMutableList()
        val at = list.indexOfFirst { it.id == offer.id }
        if (at >= 0) list[at] = offer else list.add(offer)
        saveOffers(ctx, list)
        log(ctx, "Offer saved: Ksh${offer.amount} ${offer.label}" + if (offer.enabled) "" else " (off)")
    }

    @Synchronized
    fun deleteOffer(ctx: Context, id: String) {
        val gone = offers(ctx).firstOrNull { it.id == id }
        saveOffers(ctx, offers(ctx).filter { it.id != id })
        if (gone != null) log(ctx, "Offer deleted: Ksh${gone.amount} ${gone.label}")
    }

    @Synchronized
    fun resetOffers(ctx: Context) {
        saveOffers(ctx, Protocol.defaultOffers())
        log(ctx, "Offers reset to the defaults")
    }

    @Synchronized
    fun offerFor(ctx: Context, amount: String): Protocol.Offer? {
        val c = java.util.Calendar.getInstance()
        val nowMin = c.get(java.util.Calendar.HOUR_OF_DAY) * 60 + c.get(java.util.Calendar.MINUTE)
        return Protocol.pickOffer(offers(ctx), amount, nowMin)
    }

    /** Returns false if this payment was already handled, so one SMS never dials twice. */
    @Synchronized
    fun markSeen(ctx: Context, txCode: String): Boolean {
        val seen = (prefs(ctx).getString("seen", "") ?: "").split(",").filter { it.isNotBlank() }.toMutableList()
        if (seen.contains(txCode)) return false
        seen.add(txCode)
        val trimmed = seen.takeLast(MAX_SEEN)
        prefs(ctx).edit().putString("seen", trimmed.joinToString(",")).commit()
        return true
    }

    // ---- designated M-Pesa line ----------------------------------------

    /** Subscription id of the SIM that receives customer payments. -1 means any line counts. */
    @Synchronized
    fun designatedSub(ctx: Context): Int = prefs(ctx).getInt("designated_sub", -1)

    @Synchronized
    fun setDesignatedSub(ctx: Context, id: Int) {
        prefs(ctx).edit().putInt("designated_sub", id).commit()
        log(ctx, if (id == -1) "Any line counts for payments" else "Only the chosen line counts for payments")
    }

    // ---- credits -------------------------------------------------------

    @Synchronized
    fun creditBalance(ctx: Context): Long = prefs(ctx).getLong("tokens", 0L)

    /** Adds credits from a top-up code. A serial that was already used is refused. */
    @Synchronized
    fun addCredits(ctx: Context, voucher: Protocol.Voucher): Boolean {
        val used = (prefs(ctx).getString("vouchers_used", "") ?: "").split(",").filter { it.isNotBlank() }
        if (used.contains(voucher.serial)) {
            log(ctx, "Token ${voucher.serial} was already used. Ignored.")
            return false
        }
        val balance = creditBalance(ctx) + voucher.credits
        prefs(ctx).edit()
            .putLong("tokens", balance)
            .putString("vouchers_used", (used + voucher.serial).takeLast(MAX_SEEN).joinToString(","))
            .commit()
        log(ctx, "Added ${voucher.credits} credits. Balance $balance.")
        return true
    }

    /** Takes the sale value in credits before dialing. Returns false, and changes nothing, if not enough. */
    @Synchronized
    fun spendCredits(ctx: Context, saleAmount: Long): Boolean {
        val next = Protocol.chargeSale(creditBalance(ctx), saleAmount) ?: return false
        prefs(ctx).edit().putLong("tokens", next).commit()
        log(ctx, "Used $saleAmount credits. Balance $next.")
        return true
    }

    /** Gives credits back when a dial definitely did not go through. */
    @Synchronized
    fun refundCredits(ctx: Context, amount: Long) {
        if (amount <= 0L) return
        val next = creditBalance(ctx) + amount
        prefs(ctx).edit().putLong("tokens", next).commit()
        log(ctx, "Returned $amount credits (dial did not go through). Balance $next.")
    }

    // ---- airtime balance (checked by dialing the balance code; costs nothing to check) ----------

    /** Balance code for the merchant's network. Safaricom airtime balance is *144#. */
    const val BALANCE_CODE = "*144#"
    private const val BALANCE_EVERY_MS = 60L * 60L * 1000L

    @Synchronized
    fun balanceDue(ctx: Context): Boolean {
        val p = prefs(ctx)
        if (p.getBoolean("balance_now", false)) return true
        return System.currentTimeMillis() - p.getLong("airtime_at", 0L) >= BALANCE_EVERY_MS
    }

    /** Asks for a balance check on the next idle moment (the top bar tap or REFRESH). */
    @Synchronized
    fun requestBalance(ctx: Context) {
        prefs(ctx).edit().putBoolean("balance_now", true).commit()
    }

    /** True when a person asked for a check (tap on the airtime tile). */
    @Synchronized
    fun balanceRequested(ctx: Context): Boolean = prefs(ctx).getBoolean("balance_now", false)

    @Synchronized
    fun balanceChecking(ctx: Context): Boolean = prefs(ctx).getBoolean("balance_now", false)

    /** Saves the reply to the balance code. A failed check keeps the last good reading. */
    @Synchronized
    fun saveAirtime(ctx: Context, reply: String, ok: Boolean = true) {
        val e = prefs(ctx).edit()
            .putLong("airtime_at", System.currentTimeMillis())
            .putBoolean("balance_now", false)
        if (ok) e.putString("airtime_raw", reply.take(200)).putString("airtime_err", "")
        else e.putString("airtime_err", reply.take(120))
        e.commit()
        log(ctx, if (ok) "Airtime reply: ${reply.replace('\n', ' ').take(100)}" else "Airtime check failed: ${reply.take(100)}")
    }

    /** Milliseconds since the airtime balance was last read (a very large number if never). */
    @Synchronized
    fun balanceAgeMs(ctx: Context): Long {
        val at = prefs(ctx).getLong("airtime_at", 0L)
        return if (at == 0L) Long.MAX_VALUE else System.currentTimeMillis() - at
    }

    /**
     * For the header tile: (big text, small text).
     * Reads replies like "Airtime Bal: 0.02KSH.Expire date:06-01-2027, Okoa Bal 4.50 KSH. ..." into "KSH 0.02" and "Okoa 4.50".
     */
    @Synchronized
    fun airtimeParts(ctx: Context): Pair<String, String> {
        val p = prefs(ctx)
        val checking = p.getBoolean("balance_now", false)
        val err = p.getString("airtime_err", "") ?: ""
        val raw = p.getString("airtime_raw", null)
            ?: return if (checking) "..." to "checking" else if (err.isNotBlank()) "--" to "check failed, tap to retry" else "--" to "tap to check"
        val at = p.getLong("airtime_at", 0L)
        val amountRx = """([0-9][0-9,]*(?:\.[0-9]{1,2})?)"""
        val airtime = Regex("""(?i)airtime\s*bal(?:ance)?[^0-9]{0,15}$amountRx""").find(raw)?.groupValues?.get(1)
            ?: Regex("""(?i)(?:ksh|kes|bal(?:ance)?)[^0-9]{0,30}$amountRx""").find(raw)?.groupValues?.get(1)
        val okoa = Regex("""(?i)okoa\s*bal(?:ance)?[^0-9]{0,15}$amountRx""").find(raw)?.groupValues?.get(1)
        val big = if (airtime != null) "KSH $airtime" else raw.replace('\n', ' ').take(24)
        val time = java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(java.util.Date(at))
        val extra = if (okoa != null && okoa != "0" && okoa != "0.00") "Okoa $okoa  -  " else ""
        val small = when {
            checking -> "checking..."
            err.isNotBlank() -> "last check failed  $time"
            else -> extra + "at $time"
        }
        return big to small
    }

    // ---- job queue -----------------------------------------------------

    private fun jobs(ctx: Context): JSONArray =
        try { JSONArray(prefs(ctx).getString("jobs", "[]") ?: "[]") } catch (e: Exception) { JSONArray() }

    private fun saveJobs(ctx: Context, arr: JSONArray) {
        prefs(ctx).edit().putString("jobs", arr.toString()).commit()
    }

    @Synchronized
    fun enqueue(ctx: Context, txCode: String, amount: String, ussd: String, phone: String, label: String, name: String = "") {
        addJob(ctx, JSONObject()
            .put("name", name)
            .put("tx", txCode)
            .put("amount", amount)
            .put("ussd", ussd)
            .put("phone", phone)
            .put("label", label)
            .put("status", "queued")
            .put("created", System.currentTimeMillis() / 1000))
        log(ctx, "Queued $txCode: Ksh$amount $label -> $ussd")
    }

    /** A payment arrived that matches no ON offer. Kept in the sales list so the money is not forgotten. */
    @Synchronized
    fun recordNoOffer(ctx: Context, txCode: String, amount: String, phone: String, name: String = "") {
        addJob(ctx, JSONObject()
            .put("name", name)
            .put("tx", txCode)
            .put("amount", amount)
            .put("ussd", "")
            .put("phone", phone)
            .put("label", "no offer for this amount")
            .put("status", "no_offer")
            .put("created", System.currentTimeMillis() / 1000))
        log(ctx, "No offer for Ksh$amount ($txCode). Nothing dialed.")
    }

    /** A paid sale whose number is hidden. status is "awaiting" (asked 334) or "no_number". */
    @Synchronized
    fun enqueueMasked(
        ctx: Context, txCode: String, amount: String, template: String, label: String,
        name: String, prefix: String, suffix: String, status: String, note: String
    ) {
        addJob(ctx, JSONObject()
            .put("tx", txCode).put("amount", amount).put("ussd", "").put("phone", "")
            .put("label", label).put("name", name).put("tpl", template)
            .put("mask", "$prefix|$suffix").put("status", status).put("response", note)
            .put("created", System.currentTimeMillis() / 1000))
        log(ctx, "Number hidden on $txCode (Ksh$amount $name): $note")
    }

    /** The customer's real number is now known. Puts the sale in the queue. Returns a problem in words, or null if all is well. */
    @Synchronized
    fun resolveJob(ctx: Context, txCode: String, phone254: String, nameFromReply: String): String? {
        val arr = jobs(ctx)
        val now = System.currentTimeMillis() / 1000
        for (i in 0 until arr.length()) {
            val job = arr.getJSONObject(i)
            if (job.getString("tx") != txCode) continue
            val st = job.getString("status")
            if ((st != "awaiting" && st != "no_number") || now - job.getLong("created") > RETRY_WINDOW_S) {
                return "That sale is not waiting for a number."
            }
            val mask = job.optString("mask", "|").split("|")
            val prefix = mask.getOrElse(0) { "" }
            val suffix = mask.getOrElse(1) { "" }
            if (!Protocol.matchesMask(phone254, prefix, suffix)) {
                return "That number does not fit the digits shown in the receipt."
            }
            val tpl = job.optString("tpl", "")
            if (tpl.isBlank()) return "There is no code saved for this sale."
            job.put("phone", Protocol.localPhone(phone254))
            job.put("ussd", ussdFor(tpl, phone254))
            if (nameFromReply.isNotBlank()) job.put("name", nameFromReply)
            job.put("status", "queued")
            job.put("created", now)
            job.remove("response")
            saveJobs(ctx, arr)
            log(ctx, "Number known for $txCode: ${Protocol.localPhone(phone254)}. Queued.")
            return null
        }
        return "That sale was not found."
    }

    /** The customer said no (or 334 gave no number). */
    @Synchronized
    fun refuseNumber(ctx: Context, txCode: String) {
        val arr = jobs(ctx)
        for (i in 0 until arr.length()) {
            val job = arr.getJSONObject(i)
            if (job.getString("tx") == txCode && job.getString("status") == "awaiting") {
                job.put("status", "no_number")
                job.put("response", "the customer did not approve sharing their number")
                saveJobs(ctx, arr)
                log(ctx, "No number for $txCode: the customer did not approve")
            }
        }
    }

    /** Sales that waited too long for the customer to approve. They stay in the list for ENTER NUMBER. */
    @Synchronized
    fun expireAwaiting(ctx: Context) {
        val arr = jobs(ctx)
        val now = System.currentTimeMillis() / 1000
        val limit = num(ctx, "wait_min", 10) * 60L
        var changed = false
        for (i in 0 until arr.length()) {
            val job = arr.getJSONObject(i)
            if (job.getString("status") == "awaiting" && now - job.getLong("created") > limit) {
                job.put("status", "no_number")
                job.put("response", "no approval yet. Tap ENTER NUMBER if the customer sent it.")
                log(ctx, "No number for ${job.getString("tx")}: no approval in ${limit / 60} min")
                changed = true
            }
        }
        if (changed) saveJobs(ctx, arr)
    }

    @Synchronized
    fun needsNumber(ctx: Context): List<NeedsNumber> {
        val arr = jobs(ctx)
        val now = System.currentTimeMillis() / 1000
        val out = mutableListOf<NeedsNumber>()
        for (i in arr.length() - 1 downTo 0) {
            val j = arr.getJSONObject(i)
            val st = j.getString("status")
            if ((st == "awaiting" || st == "no_number") && now - j.getLong("created") <= RETRY_WINDOW_S) {
                val mask = j.optString("mask", "|").split("|")
                out.add(NeedsNumber(j.getString("tx"), j.optString("amount"), j.optString("label"), j.optString("name"),
                    mask.getOrElse(0) { "" }, mask.getOrElse(1) { "" }, st, j.getLong("created")))
            }
        }
        return out
    }

    @Synchronized
    fun jobInfo(ctx: Context, txCode: String): JobInfo? {
        val arr = jobs(ctx)
        for (i in 0 until arr.length()) {
            val j = arr.getJSONObject(i)
            if (j.getString("tx") == txCode) {
                return JobInfo(j.optString("phone"), j.optString("name"), j.optString("label"), j.optString("amount"))
            }
        }
        return null
    }

    /** True the first time it is called for a sale, so a customer is never texted twice about one sale. */
    @Synchronized
    fun claimCustomerSms(ctx: Context, txCode: String): Boolean {
        val arr = jobs(ctx)
        for (i in 0 until arr.length()) {
            val j = arr.getJSONObject(i)
            if (j.getString("tx") == txCode) {
                if (j.optBoolean("sms", false)) return false
                j.put("sms", true)
                saveJobs(ctx, arr)
                return true
            }
        }
        return false
    }

    private fun addJob(ctx: Context, job: JSONObject) {
        var arr = jobs(ctx)
        arr.put(job)
        if (arr.length() > MAX_JOBS) {
            val trimmed = JSONArray()
            for (i in arr.length() - MAX_JOBS until arr.length()) trimmed.put(arr.getJSONObject(i))
            arr = trimmed
        }
        saveJobs(ctx, arr)
    }

    /** Marks the next queued job as dialing and returns it. Expired jobs are skipped and logged. */
    @Synchronized
    fun takeNext(ctx: Context): JSONObject? {
        val arr = jobs(ctx)
        val now = System.currentTimeMillis() / 1000
        var changed = false
        for (i in 0 until arr.length()) {
            val job = arr.getJSONObject(i)
            if (job.getString("status") != "queued") continue
            if (now - job.getLong("created") > JOB_TTL_SECONDS) {
                job.put("status", "expired")
                changed = true
                saveJobs(ctx, arr)
                log(ctx, "Expired ${job.getString("tx")} (not dialed in time). Not dialed.")
                recordDay(ctx, false, 0L)
                continue
            }
            job.put("status", "dialing")
            saveJobs(ctx, arr)
            return job
        }
        if (changed) saveJobs(ctx, arr)
        return null
    }

    @Synchronized
    fun finish(ctx: Context, txCode: String, status: String, response: String) {
        val arr = jobs(ctx)
        var phone = ""
        var name = ""
        var amount = 0L
        for (i in 0 until arr.length()) {
            val job = arr.getJSONObject(i)
            if (job.getString("tx") == txCode) {
                job.put("status", status)
                job.put("response", response.take(200))
                phone = job.optString("phone")
                name = job.optString("name")
                amount = Protocol.wholeShillings(job.optString("amount")) ?: 0L
            }
        }
        saveJobs(ctx, arr)
        log(ctx, "$status $txCode: ${response.take(120)}")
        if (status == "done") {
            recordDay(ctx, true, amount)
            if (phone.isNotBlank()) recordCustomerSale(ctx, phone, name, amount)
        } else if (status == "failed" || status == "no_credits" || status == "unknown") {
            recordDay(ctx, false, 0L)
        }
    }

    // ---- daily totals and customers ------------------------------------------------------------

    private fun dayKey(ms: Long): String =
        java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date(ms))

    private fun dailyObject(ctx: Context): JSONObject =
        try { JSONObject(prefs(ctx).getString("daily", "{}") ?: "{}") } catch (e: Exception) { JSONObject() }

    @Synchronized
    fun recordDay(ctx: Context, ok: Boolean, amount: Long) {
        val all = dailyObject(ctx)
        val key = dayKey(System.currentTimeMillis())
        val d = all.optJSONObject(key) ?: JSONObject().put("n", 0).put("a", 0L).put("f", 0)
        if (ok) {
            d.put("n", d.optInt("n") + 1)
            d.put("a", d.optLong("a") + amount)
        } else {
            d.put("f", d.optInt("f") + 1)
        }
        all.put(key, d)
        val keys = ArrayList<String>()
        val it = all.keys()
        while (it.hasNext()) keys.add(it.next())
        keys.sort()
        while (keys.size > 70) all.remove(keys.removeAt(0))
        prefs(ctx).edit().putString("daily", all.toString()).commit()
    }

    /** Totals for the last [days] days including today. */
    @Synchronized
    fun period(ctx: Context, days: Int): Period {
        val all = dailyObject(ctx)
        var n = 0
        var money = 0L
        var failed = 0
        val now = System.currentTimeMillis()
        for (i in 0 until days) {
            val d = all.optJSONObject(dayKey(now - i * 86_400_000L)) ?: continue
            n += d.optInt("n")
            money += d.optLong("a")
            failed += d.optInt("f")
        }
        return Period(n, money, failed)
    }

    /** The bundles that sold most among the recent finished sales. */
    @Synchronized
    fun topOffers(ctx: Context, limit: Int): List<Pair<String, Int>> {
        val arr = jobs(ctx)
        val counts = LinkedHashMap<String, Int>()
        for (i in 0 until arr.length()) {
            val j = arr.getJSONObject(i)
            if (j.getString("status") != "done") continue
            val label = j.optString("label", "").ifBlank { "Ksh" + j.optString("amount") }
            counts[label] = (counts[label] ?: 0) + 1
        }
        return counts.entries.sortedByDescending { it.value }.take(limit).map { it.key to it.value }
    }

    private fun customerArray(ctx: Context): JSONArray =
        try { JSONArray(prefs(ctx).getString("customers", "[]") ?: "[]") } catch (e: Exception) { JSONArray() }

    @Synchronized
    fun customers(ctx: Context): List<Customer> {
        val arr = customerArray(ctx)
        return (0 until arr.length()).map { i ->
            val c = arr.getJSONObject(i)
            Customer(c.getString("p"), c.optString("n"), c.optLong("t"), c.optInt("c"), c.optLong("l"), c.optString("r"))
        }
    }

    private fun recordCustomerSale(ctx: Context, phone: String, name: String, amount: Long) {
        val arr = customerArray(ctx)
        val now = System.currentTimeMillis() / 1000
        var found = false
        for (i in 0 until arr.length()) {
            val c = arr.getJSONObject(i)
            if (c.getString("p") == phone) {
                c.put("t", c.optLong("t") + amount)
                c.put("c", c.optInt("c") + 1)
                c.put("l", now)
                if (name.isNotBlank()) c.put("n", name)
                found = true
            }
        }
        if (!found) {
            arr.put(JSONObject().put("p", phone).put("n", name.ifBlank { phone }).put("t", amount).put("c", 1).put("l", now).put("r", ""))
        }
        prefs(ctx).edit().putString("customers", arr.toString()).commit()
    }

    @Synchronized
    fun setReferrer(ctx: Context, phone: String, referrerPhone: String) {
        val arr = customerArray(ctx)
        for (i in 0 until arr.length()) {
            val c = arr.getJSONObject(i)
            if (c.getString("p") == phone) c.put("r", referrerPhone)
        }
        prefs(ctx).edit().putString("customers", arr.toString()).commit()
    }

    // ---- daily summary ---------------------------------------------------------------------------

    @Synchronized
    fun summaryDue(ctx: Context): Boolean {
        if (!flag(ctx, "summary", true)) return false
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        return hour >= num(ctx, "summary_hour", 21) &&
            prefs(ctx).getString("summary_day", "") != dayKey(System.currentTimeMillis())
    }

    @Synchronized
    fun markSummaryDone(ctx: Context) {
        prefs(ctx).edit().putString("summary_day", dayKey(System.currentTimeMillis())).commit()
    }

    @Synchronized
    fun summaryText(ctx: Context): String {
        val p = period(ctx, 1)
        val tried = p.sales + p.failed
        val rate = if (tried == 0) 100 else p.sales * 100 / tried
        return "Today: ${p.sales} sale(s), Ksh ${p.money}, $rate% delivered. Credits left: ${creditBalance(ctx)}."
    }

    /**
     * Run on service start. A job still "dialing" means the app stopped mid-dial, so the outcome
     * is unknown. Mark it for a person to check; never dial it again automatically.
     */
    @Synchronized
    fun recoverInterrupted(ctx: Context) {
        val arr = jobs(ctx)
        var changed = false
        for (i in 0 until arr.length()) {
            val job = arr.getJSONObject(i)
            if (job.getString("status") == "dialing") {
                job.put("status", "unknown")
                log(ctx, "CHECK ${job.getString("tx")}: app stopped during dial, outcome unknown. Not retried.")
                changed = true
            }
        }
        if (changed) saveJobs(ctx, arr)
    }

    @Synchronized
    fun hasQueued(ctx: Context): Boolean {
        val arr = jobs(ctx)
        for (i in 0 until arr.length()) {
            if (arr.getJSONObject(i).getString("status") == "queued") return true
        }
        return false
    }

    private fun retryable(job: JSONObject, now: Long): Boolean {
        val s = job.getString("status")
        return (s == "failed" || s == "no_credits") && now - job.getLong("created") <= RETRY_WINDOW_S
    }

    /** How many jobs a person can send again: failed or out-of-credits ones from the last 2 hours. */
    @Synchronized
    fun retryableCount(ctx: Context): Int {
        val arr = jobs(ctx)
        val now = System.currentTimeMillis() / 1000
        var n = 0
        for (i in 0 until arr.length()) if (retryable(arr.getJSONObject(i), now)) n++
        return n
    }

    /** Puts those jobs back in the queue. Only ever called by a person tapping RETRY and confirming. */
    @Synchronized
    fun requeueRetryable(ctx: Context): Int {
        val arr = jobs(ctx)
        val now = System.currentTimeMillis() / 1000
        var n = 0
        for (i in 0 until arr.length()) {
            val job = arr.getJSONObject(i)
            if (retryable(job, now)) {
                job.put("status", "queued")
                job.put("created", now)
                job.remove("response")
                n++
            }
        }
        if (n > 0) {
            saveJobs(ctx, arr)
            log(ctx, "Retrying $n job(s) by request")
        }
        return n
    }

    /** A sale that failed or ran out of credits and can be dialed again. Credits are only used when it is delivered. */
    data class Retryable(
        val tx: String, val amount: String, val label: String, val name: String,
        val phone: String, val status: String, val reason: String
    )

    @Synchronized
    fun retryables(ctx: Context): List<Retryable> {
        val arr = jobs(ctx)
        val now = System.currentTimeMillis() / 1000
        val out = mutableListOf<Retryable>()
        for (i in arr.length() - 1 downTo 0) {
            val j = arr.getJSONObject(i)
            if (retryable(j, now)) {
                out.add(Retryable(j.getString("tx"), j.optString("amount"), j.optString("label"), j.optString("name"),
                    j.optString("phone"), j.getString("status"), j.optString("response", "")))
            }
        }
        return out
    }

    /** Dial one failed sale again. Returns false if it can't be retried any more. */
    @Synchronized
    fun retryOne(ctx: Context, txCode: String): Boolean {
        val arr = jobs(ctx)
        val now = System.currentTimeMillis() / 1000
        for (i in 0 until arr.length()) {
            val job = arr.getJSONObject(i)
            if (job.getString("tx") == txCode && retryable(job, now)) {
                job.put("status", "queued")
                job.put("created", now)
                job.remove("response")
                saveJobs(ctx, arr)
                log(ctx, "Retrying $txCode by request")
                return true
            }
        }
        return false
    }

    private fun statusWord(status: String): String = when (status) {
        "done" -> "DONE"
        "failed" -> "FAILED"
        "no_credits" -> "NO CREDITS"
        "queued" -> "WAITING"
        "dialing" -> "DIALING"
        "expired" -> "EXPIRED"
        "unknown" -> "CHECK"
        "no_offer" -> "NO OFFER"
        "awaiting" -> "ASKING 334"
        "no_number" -> "NO NUMBER"
        else -> status.uppercase()
    }

    /** "0712345678" -> "0712 345 678" */
    private fun spaced(phone: String): String =
        if (phone.length == 10) phone.substring(0, 4) + " " + phone.substring(4, 7) + " " + phone.substring(7) else phone

    /** Who bought what and when, newest first. Three short lines per sale. */
    @Synchronized
    fun recentJobs(ctx: Context, limit: Int = 20): List<String> {
        val arr = jobs(ctx)
        val out = mutableListOf<String>()
        val fmt = java.text.SimpleDateFormat("dd MMM HH:mm", java.util.Locale.US)
        for (i in arr.length() - 1 downTo maxOf(0, arr.length() - limit)) {
            val j = arr.getJSONObject(i)
            val time = fmt.format(java.util.Date(j.getLong("created") * 1000L))
            val phone = j.optString("phone", "")
            val label = j.optString("label", "")
            val who = if (phone.isNotBlank()) "  " + spaced(phone) else j.optString("name", "").let { if (it.isBlank()) "" else "  $it" }
            val what = if (label.isBlank()) "" else "  " + label
            val reply = j.optString("response", "").replace('\n', ' ').trim()
            val sb = StringBuilder()
            sb.append("$time  ${statusWord(j.getString("status"))}\n")
            sb.append("Ksh${j.getString("amount")}$who$what")
            if (reply.isNotEmpty()) sb.append("\n    " + reply.take(90))
            out.add(sb.toString())
        }
        return out
    }

    /** (sales completed today, shillings) since midnight. */
    @Synchronized
    fun todaySummary(ctx: Context): Pair<Int, Long> {
        val arr = jobs(ctx)
        val cal = java.util.Calendar.getInstance()
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0); cal.set(java.util.Calendar.MINUTE, 0)
        cal.set(java.util.Calendar.SECOND, 0); cal.set(java.util.Calendar.MILLISECOND, 0)
        val start = cal.timeInMillis / 1000L
        var n = 0
        var total = 0L
        for (i in 0 until arr.length()) {
            val j = arr.getJSONObject(i)
            if (j.getString("status") == "done" && j.getLong("created") >= start) {
                n++
                total += Protocol.wholeShillings(j.optString("amount", "")) ?: 0L
            }
        }
        return n to total
    }

    // ---- log -----------------------------------------------------------

    @Synchronized
    fun log(ctx: Context, message: String) {
        val lines = (prefs(ctx).getString("log", "") ?: "").split("\n").filter { it.isNotBlank() }.toMutableList()
        lines.add("${stamp()}  $message")
        prefs(ctx).edit().putString("log", lines.takeLast(MAX_LOG).joinToString("\n")).commit()
    }

    @Synchronized
    fun logLines(ctx: Context, limit: Int = 30): List<String> =
        (prefs(ctx).getString("log", "") ?: "").split("\n").filter { it.isNotBlank() }.takeLast(limit).reversed()
}
