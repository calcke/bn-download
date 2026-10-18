package com.bn.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.telephony.SubscriptionManager
import android.text.Editable
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Four screens along the bottom: HOME (balance and offers only), SALES (totals, what needs action, recent sales, log),
 * CUSTOMERS (best buyers, who referred whom, who stopped buying) and SETTINGS (everything you set once).
 *
 * Colours follow the dark theme of the reference page: background #07090c, surface #0d1117, line #1c242d,
 * text #e6e9ed, muted #8b95a1, mint #2dd4a7 for values and actions, orange #f97316 for labels, red for alerts.
 *
 * No box here takes the cursor by itself: the keyboard only opens when you tap a box.
 */
class MainActivity : Activity() {

    private val BG = Color.parseColor("#0a0d11")
    private val SURFACE = Color.parseColor("#12171e")
    private val SURFACE2 = Color.parseColor("#1a212a")
    private val LINE = Color.parseColor("#232c37")
    private val TEXT = Color.parseColor("#eef1f4")
    private val MUTED = Color.parseColor("#8b95a1")
    private val MINT = Color.parseColor("#2dd4a7")
    private val MINT_INK = Color.parseColor("#04110d")
    private val ORANGE = Color.parseColor("#f97316")
    private val RED = Color.parseColor("#ef4444")

    private lateinit var frame: FrameLayout
    private lateinit var root: LinearLayout
    private val sheets = ArrayList<Sheet>()
    private val tabViews = mutableListOf<ScrollView>()
    private val tabButtons = mutableListOf<TextView>()
    private var currentTab = 0

    // HOME
    private lateinit var listenLabel: TextView
    private lateinit var listenSwitch: Switch
    private lateinit var airtimeBig: TextView
    private lateinit var airtimeSmall: TextView
    private lateinit var creditsBig: TextView
    private lateinit var creditsSmall: TextView
    private lateinit var statusView: TextView
    private lateinit var setupCard: LinearLayout
    private lateinit var setupBox: LinearLayout
    private var setupSig = ""
    private lateinit var liveCard: LinearLayout
    private lateinit var liveView: TextView
    private lateinit var offersBox: LinearLayout

    // SALES
    private lateinit var statsView: TextView
    private lateinit var needsCard: LinearLayout
    private lateinit var needsBox: LinearLayout
    private lateinit var jobsView: TextView
    private lateinit var salesToggle: TextView
    private lateinit var retryBtn: Button

    // CUSTOMERS and SETTINGS
    private lateinit var customersBox: LinearLayout
    private lateinit var settingsBox: LinearLayout

    private val ui = Handler(Looper.getMainLooper())
    private var tickCount = 0
    private val startedAt = System.currentTimeMillis()
    private var showMoreSales = false
    private var offersSig = ""
    private var needsSig = ""
    private val openSections = HashSet<String>()
    private val job = SupervisorJob()
    private val scope = CoroutineScope(job + Dispatchers.Main)

    private val listenListener = android.widget.CompoundButton.OnCheckedChangeListener { _, on ->
        Store.setListening(this@MainActivity, on)
        if (on) OfflineService.ensureRunning(this@MainActivity)
        refreshHome()
    }

    /** Runs every second while the app is on screen: banner each second, the open screen every 3 seconds. */
    private val tick = object : Runnable {
        override fun run() {
            try {
                updateLive()
                tickCount++
                if (tickCount % 3 == 0) {
                    when (currentTab) {
                        0 -> refreshHome()
                        1 -> refreshSales()
                    }
                }
            } catch (e: Exception) {
                // never let a screen update crash the app
            }
            ui.postDelayed(this, 1000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.setBackgroundColor(BG)
        val missingNow = missingPermissions()
        if (missingNow.isNotEmpty()) requestPermissions(missingNow, 1)

        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
            isFocusable = true
            isFocusableInTouchMode = true
        }
        val content = FrameLayout(this)
        for (i in 0 until 4) {
            val page = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(18), dp(16), dp(24))
            }
            val sv = ScrollView(this).apply {
                setBackgroundColor(BG)
                addView(page)
                visibility = if (i == 0) View.VISIBLE else View.GONE
            }
            tabViews.add(sv)
            content.addView(sv, FrameLayout.LayoutParams(MATCH, MATCH))
        }
        root.addView(content, LinearLayout.LayoutParams(MATCH, 0, 1f))
        root.addView(View(this).apply { setBackgroundColor(LINE) }, LinearLayout.LayoutParams(MATCH, dp(1)))
        root.addView(buildTabBar(), LinearLayout.LayoutParams(MATCH, dp(56)))

        buildHome(pageOf(0))
        buildSales(pageOf(1))
        buildCustomers(pageOf(2))
        buildSettings(pageOf(3))

        frame = FrameLayout(this)
        frame.addView(root, FrameLayout.LayoutParams(MATCH, MATCH))
        setContentView(frame)
        if (Store.listening(this)) OfflineService.ensureRunning(this)
        showTab(0)
        balanceOnOpen()
    }

    private fun pageOf(i: Int): LinearLayout = tabViews[i].getChildAt(0) as LinearLayout

    private fun buildTabBar(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(SURFACE)
        }
        val names = listOf("Home", "Sales", "Customers", "Settings")
        for ((i, name) in names.withIndex()) {
            val t = TextView(this).apply {
                text = name
                gravity = Gravity.CENTER
                textSize = 13f
                setOnClickListener { showTab(i) }
            }
            tabButtons.add(t)
            bar.addView(t, LinearLayout.LayoutParams(0, MATCH, 1f))
        }
        return bar
    }

    private fun showTab(i: Int) {
        currentTab = i
        for ((k, v) in tabViews.withIndex()) v.visibility = if (k == i) View.VISIBLE else View.GONE
        for ((k, b) in tabButtons.withIndex()) {
            b.setTextColor(if (k == i) MINT else MUTED)
            b.typeface = Typeface.create("sans-serif-medium", if (k == i) Typeface.BOLD else Typeface.NORMAL)
        }
        when (i) {
            0 -> refreshHome()
            1 -> refreshSales()
            2 -> refreshCustomers()
            3 -> refreshSettings()
        }
        root.requestFocus()
        hideKeyboard()
    }

    private fun hideKeyboard() {
        try {
            (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(frame.windowToken, 0)
        } catch (e: Exception) {
            // nothing to do
        }
    }

    /** The Back button closes an open sheet first. */
    override fun onBackPressed() {
        val top = sheets.lastOrNull()
        if (top != null) top.dismiss() else super.onBackPressed()
    }

    override fun onResume() {
        super.onResume()
        if (Store.listening(this)) OfflineService.ensureRunning(this)
        showTab(currentTab)
        ui.removeCallbacks(tick)
        ui.postDelayed(tick, 1000L)
    }

    override fun onPause() {
        ui.removeCallbacks(tick)
        super.onPause()
    }

    override fun onDestroy() {
        job.cancel()
        super.onDestroy()
    }

    /** Read the airtime balance when the app is opened, but never more than once every 15 minutes. */
    private fun balanceOnOpen() {
        if (checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) return
        if (Store.balanceAgeMs(this) < 15L * 60_000L) return
        Store.requestBalance(this)
        OfflineService.ensureRunning(this)
    }

    private fun askForBalance() {
        if (checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            toast("Allow the Phone permission first (SETTINGS) to check airtime.")
            return
        }
        Store.requestBalance(this)
        OfflineService.ensureRunning(this)
        refreshHome()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    // =====================================================================
    // HOME: balance and offers, nothing else
    // =====================================================================

    private fun buildHome(page: LinearLayout) {
        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(14))
        }
        titleRow.addView(text("bn", MINT, 26f, bold = true), LinearLayout.LayoutParams(0, WRAP, 1f))
        listenLabel = text("Listening", MUTED, 12f).apply { setPadding(0, 0, dp(8), 0) }
        titleRow.addView(listenLabel)
        listenSwitch = Switch(this).apply {
            tint(this)
            isChecked = Store.listening(this@MainActivity)
            setOnCheckedChangeListener(listenListener)
        }
        titleRow.addView(listenSwitch)
        page.addView(titleRow)

        val balance = card()
        val cols = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val airtimeCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setOnClickListener { askForBalance() }
        }
        airtimeCol.addView(label("AIRTIME"))
        airtimeBig = text("--", TEXT, 28f, bold = true)
        airtimeCol.addView(airtimeBig)
        airtimeSmall = text("", MUTED, 11f)
        airtimeCol.addView(airtimeSmall)
        cols.addView(airtimeCol, LinearLayout.LayoutParams(0, WRAP, 1.3f))
        val creditCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setOnClickListener { showAddCredits() }
        }
        creditCol.addView(label("CREDITS"))
        creditsBig = text("0", MINT, 28f, bold = true)
        creditCol.addView(creditsBig)
        creditsSmall = text("", MUTED, 11f)
        creditCol.addView(creditsSmall)
        cols.addView(creditCol, LinearLayout.LayoutParams(0, WRAP, 1f))
        balance.addView(cols)
        statusView = text("", MUTED, 13f).apply { setPadding(0, dp(12), 0, 0) }
        balance.addView(statusView)
        page.addView(balance)

        setupCard = card().apply { visibility = View.GONE }
        setupCard.addView(label("FINISH SETUP"))
        setupBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        setupCard.addView(setupBox)
        page.addView(setupCard)

        liveCard = card().apply {
            visibility = View.GONE
            background = box(SURFACE, MINT)
        }
        liveCard.addView(label("DIALING NOW"))
        liveView = text("", MINT, 13f)
        liveCard.addView(liveView)
        page.addView(liveCard)

        val offersHead = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, dp(10))
        }
        offersHead.addView(text("Offers", TEXT, 18f, bold = true), LinearLayout.LayoutParams(0, WRAP, 1f))
        offersHead.addView(smallButton("+ Add", true) { editOffer(null) })
        page.addView(offersHead)
        offersBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        page.addView(offersBox)
    }

    private class SetupItem(val title: String, val note: String, val ok: Boolean, val action: String, val run: () -> Unit)

    private fun simList(): List<android.telephony.SubscriptionInfo> = try {
        if (checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) emptyList()
        else SubscriptionManager.from(this).activeSubscriptionInfoList ?: emptyList()
    } catch (e: Exception) {
        emptyList()
    }

    private fun openAccessibility() {
        toast("Find \"bn USSD helper\" in the list and turn it on.")
        try {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        } catch (e: Exception) {
            toast("Open Settings > Accessibility yourself.")
        }
    }

    private fun chooseSim() {
        val subs = simList()
        if (subs.isEmpty()) {
            toast("Allow the phone permission first.")
            return
        }
        choiceSheet("Which line do customers pay into?", subs.map { "SIM ${it.simSlotIndex + 1}  ${it.carrierName ?: ""}".trim() }) { i ->
            Store.setDesignatedSub(this, subs[i].subscriptionId)
            showTab(currentTab)
        }
    }

    /** The few things that must be done before selling. Empty once everything is set. */
    private fun setupItems(): List<SetupItem> {
        val out = mutableListOf<SetupItem>()
        out.add(SetupItem("Allow permissions", "SMS, phone calls and notifications", missingPermissions().isEmpty(), "Allow") { requestMissing() })
        out.add(SetupItem("Turn on the dialing helper", "It dials bundle codes step by step", UssdAccessibilityService.isEnabled(this), "Turn on") { openAccessibility() })
        if (simList().size > 1) {
            out.add(SetupItem("Choose your M-Pesa line", "The SIM your customers pay into", Store.designatedSub(this) != -1, "Choose") { chooseSim() })
        }
        return out
    }

    private fun refreshSetup() {
        val items = setupItems()
        val sig = items.joinToString("|") { it.title + it.ok }
        if (sig == setupSig) return
        setupSig = sig
        setupBox.removeAllViews()
        for (item in items) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(6), 0, dp(6))
            }
            row.addView(text(if (item.ok) "\u25CF" else "\u25CB", if (item.ok) MINT else ORANGE, 16f),
                LinearLayout.LayoutParams(dp(26), WRAP))
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            col.addView(text(item.title, if (item.ok) MUTED else TEXT, 14f))
            if (!item.ok) col.addView(text(item.note, MUTED, 11f))
            row.addView(col, LinearLayout.LayoutParams(0, WRAP, 1f))
            if (!item.ok) row.addView(smallButton(item.action, true) { item.run() })
            setupBox.addView(row)
        }
        setupCard.visibility = if (items.all { it.ok }) View.GONE else View.VISIBLE
    }

    /** What is stopping sales, in plain words. Empty means ready. */
    private fun problems(): List<String> {
        val out = mutableListOf<String>()
        if (Store.offers(this).none { it.enabled && it.template.isNotBlank() }) out.add("No offer is on. Turn one on, or tap + Add.")
        if (Store.creditBalance(this) <= 0L) out.add("Out of credits. Tap Credits to add some.")
        if (!Store.dialerAlive() && System.currentTimeMillis() - startedAt > 10_000L) {
            out.add("The dialer stopped. Tap Airtime to wake it.")
        }
        return out
    }

    private fun updateLive() {
        val live = Store.liveNow()
        if (live.isBlank()) {
            liveCard.visibility = View.GONE
        } else {
            liveView.text = live + "\n" + Store.liveSeconds() + "s, waiting for the network..."
            liveCard.visibility = View.VISIBLE
        }
    }

    private fun refreshHome() {
        val balance = Store.creditBalance(this)
        val (big, small) = Store.airtimeParts(this)
        airtimeBig.text = big
        airtimeSmall.text = small

        val on = Store.listening(this)
        listenLabel.text = if (on) "Listening" else "Paused"
        listenLabel.setTextColor(if (on) MINT else ORANGE)
        if (listenSwitch.isChecked != on) {
            listenSwitch.setOnCheckedChangeListener(null)
            listenSwitch.isChecked = on
            listenSwitch.setOnCheckedChangeListener(listenListener)
        }

        creditsBig.text = balance.toString()
        creditsBig.setTextColor(if (balance > 0L) MINT else RED)
        creditsSmall.text = if (balance > 0L) "tap to add more" else "tap to top up"

        val issues = problems()
        when {
            !on -> { statusView.text = "Paused. Payments are being ignored."; statusView.setTextColor(ORANGE) }
            issues.isNotEmpty() -> { statusView.text = issues[0]; statusView.setTextColor(RED) }
            else -> { statusView.text = "\u25CF  Ready. Waiting for payments."; statusView.setTextColor(MINT) }
        }
        refreshSetup()
        updateLive()

        val offers = Store.offers(this)
        val cal = java.util.Calendar.getInstance()
        val nowMin = cal.get(java.util.Calendar.HOUR_OF_DAY) * 60 + cal.get(java.util.Calendar.MINUTE)
        val sig = offers.joinToString("|") {
            "${it.id}${it.category}${it.amount}${it.label}${it.template}${it.enabled}${it.from}${it.to}" +
                (if (it.scheduled) Protocol.inWindow(it.from, it.to, nowMin).toString() else "")
        }
        if (sig != offersSig) {
            offersSig = sig
            offersBox.removeAllViews()
            if (offers.isEmpty()) offersBox.addView(text("No offers yet. Tap + Add to make one.", MUTED, 13f))
            for (cat in Protocol.CATEGORIES) {
                val inCat = offers.filter { it.category == cat }
                if (inCat.isEmpty()) continue
                offersBox.addView(text(Protocol.categoryTitle(cat), MUTED, 11f).apply {
                    letterSpacing = 0.1f
                    setPadding(0, dp(10), 0, dp(6))
                })
                inCat.forEach { offersBox.addView(offerRow(it, nowMin)) }
            }
        }
    }

    /** One offer line: amount, what they get, the code, and an on/off switch. */
    private fun offerRow(offer: Protocol.Offer, nowMin: Int): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(12), dp(12), dp(12))
            background = box(SURFACE, LINE)
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { setMargins(0, 0, 0, dp(8)) }
            setOnClickListener { editOffer(offer) }
        }
        row.addView(text("Ksh ${offer.amount}", if (offer.enabled) MINT else MUTED, 15f, bold = true),
            LinearLayout.LayoutParams(dp(92), WRAP))
        val mid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        mid.addView(text(offer.label, if (offer.enabled) TEXT else MUTED, 14f))
        if (offer.template.isBlank()) mid.addView(text("No code yet. Tap to add one.", ORANGE, 11f))
        if (offer.scheduled) {
            val live = Protocol.inWindow(offer.from, offer.to, nowMin)
            mid.addView(text("${offer.from} - ${offer.to}" + if (live) "" else "  (closed now)", if (live) MUTED else ORANGE, 11f))
        }
        row.addView(mid, LinearLayout.LayoutParams(0, WRAP, 1f))
        val sw = Switch(this).apply {
            tint(this)
            isChecked = offer.enabled
            setOnCheckedChangeListener { _, on -> toggleOffer(offer, on) }
        }
        row.addView(sw)
        return row
    }

    private fun toggleOffer(offer: Protocol.Offer, on: Boolean) {
        val updated = offer.copy(enabled = on)
        val problem = Protocol.validateOffer(updated, Store.offers(this))
        if (problem != null) toast(problem) else Store.upsertOffer(this, updated)
        offersSig = ""   // redraw so the switch shows the truth
        refreshHome()
    }

    private fun confirmResetOffers() {
        confirmSheet("Reset offers?", "This replaces all your offers with the starting list. Your changes are lost.", "Reset") {
            Store.resetOffers(this)
            offersSig = ""
            refreshHome()
        }
    }

    /** Opens only when you tap CREDITS, so the keyboard never appears by itself. */
    private fun showAddCredits() {
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(24))
        }
        body.addView(text("ADD CREDITS", MINT, 18f, bold = true))
        body.addView(text("YOUR DEVICE ID  (send this to the seller with your payment)", MUTED, 11f).apply { setPadding(0, dp(14), 0, 0) })
        val idRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        idRow.addView(text(Protocol.showDeviceId(Store.deviceId(this)), TEXT, 20f, bold = true), LinearLayout.LayoutParams(0, WRAP, 1f))
        idRow.addView(smallButton("COPY", false) { copyText(Protocol.showDeviceId(Store.deviceId(this))) })
        body.addView(idRow)
        body.addView(text("When you receive a token, copy it, then paste it below.", MUTED, 12f).apply { setPadding(0, dp(16), 0, dp(6)) })
        val field = EditText(this).apply {
            hint = "paste the token here"
            setHintTextColor(MUTED)
            setTextColor(TEXT)
            typeface = Typeface.MONOSPACE
            textSize = 14f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            minLines = 3
            maxLines = 6
            gravity = Gravity.TOP or Gravity.START
            background = box(SURFACE2, LINE)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        body.addView(field, LinearLayout.LayoutParams(MATCH, WRAP))
        val note = text("", MUTED, 13f).apply { setPadding(0, dp(10), 0, 0); visibility = View.GONE }
        body.addView(note)
        val dialog = openSheet(ScrollView(this).apply { addView(body) })
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(14), 0, 0) }
        buttons.addView(smallButton("PASTE", false) { field.setText(readClipboard()) },
            LinearLayout.LayoutParams(0, dp(50), 1f).apply { setMargins(0, 0, dp(6), 0) })
        buttons.addView(smallButton("ADD CREDITS", true) {
            val (ok, message) = CodeHandler.handle(this, field.text.toString())
            note.text = message
            note.setTextColor(if (ok) MINT else RED)
            note.visibility = View.VISIBLE
            if (ok) field.setText("")
            refreshHome()
        }, LinearLayout.LayoutParams(0, dp(50), 1f).apply { setMargins(dp(6), 0, 0, 0) })
        body.addView(buttons)
        body.addView(smallButton("CLOSE", false) { dialog.dismiss() },
            LinearLayout.LayoutParams(MATCH, dp(46)).apply { setMargins(0, dp(10), 0, 0) })
        dialog.show()
    }

    // =====================================================================
    // SALES: totals, what needs action, recent sales, log
    // =====================================================================

    private fun buildSales(page: LinearLayout) {
        page.addView(text("Sales", TEXT, 24f, bold = true).apply { setPadding(0, 0, 0, dp(14)) })
        val stats = card()
        stats.addView(label("TOTALS"))
        statsView = text("", TEXT, 13f)
        stats.addView(statsView)
        page.addView(stats)

        needsCard = card().apply { visibility = View.GONE; background = box(SURFACE, ORANGE) }
        needsCard.addView(label("NEEDS ACTION"))
        needsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        needsCard.addView(needsBox)
        retryBtn = smallButton("RETRY", false) { confirmRetry() }.apply { visibility = View.GONE }
        needsCard.addView(retryBtn, LinearLayout.LayoutParams(MATCH, WRAP).apply { setMargins(0, dp(8), 0, 0) })
        page.addView(needsCard)

        val recent = card()
        recent.addView(label("RECENT SALES"))
        jobsView = text("", TEXT, 11f).apply { setPadding(0, dp(4), 0, 0) }
        recent.addView(jobsView)
        salesToggle = text("", MINT, 11f).apply {
            setPadding(0, dp(8), 0, 0)
            setOnClickListener { showMoreSales = !showMoreSales; refreshSales() }
        }
        recent.addView(salesToggle)
        recent.addView(smallButton("Open activity log", false) { showLog() },
            LinearLayout.LayoutParams(MATCH, dp(46)).apply { setMargins(0, dp(12), 0, 0) })
        page.addView(recent)
    }

    private fun periodLine(name: String, days: Int): String {
        val p = Store.period(this, days)
        val tried = p.sales + p.failed
        val rate = if (tried == 0) "-" else (p.sales * 100 / tried).toString() + "% ok"
        return name.padEnd(9) + "${p.sales} sales  Ksh ${p.money}  $rate"
    }

    private fun refreshSales() {
        val top = Store.topOffers(this, 3)
        val best = if (top.isEmpty()) "" else "\n\nBEST SELLERS\n" + top.joinToString("\n") { "  ${it.first}  (${it.second})" }
        statsView.text = periodLine("TODAY", 1) + "\n" + periodLine("7 DAYS", 7) + "\n" + periodLine("30 DAYS", 30) + best

        val needs = Store.needsNumber(this)
        val fails = Store.retryables(this)
        val sig = needs.joinToString("|") { it.tx + it.status } + "#" + fails.joinToString("|") { it.tx + it.status }
        if (sig != needsSig) {
            needsSig = sig
            needsBox.removeAllViews()
            for (n in needs) {
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, dp(4), 0, dp(4))
                }
                val shown = n.prefix + "..." + n.suffix
                val info = "Ksh${n.amount}  ${n.label}\n${n.name.ifBlank { "unknown name" }}\nnumber hidden: $shown  (" +
                    (if (n.status == "awaiting") "asking 334" else "no number yet") + ")"
                row.addView(text(info, TEXT, 11f), LinearLayout.LayoutParams(0, WRAP, 1f))
                row.addView(smallButton("Enter number", true) { showEnterNumber(n) })
                needsBox.addView(row)
            }
            for (f in fails.take(12)) {
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, dp(4), 0, dp(4))
                }
                val who = if (f.phone.isNotBlank()) spaced(f.phone) else f.name
                val why = f.reason.replace('\n', ' ').take(80)
                val info = (if (f.status == "no_credits") "NO CREDITS" else "FAILED") + "  Ksh${f.amount}  ${f.label}\n$who" +
                    (if (why.isNotBlank()) "\n$why" else "")
                row.addView(text(info, TEXT, 11f), LinearLayout.LayoutParams(0, WRAP, 1f))
                row.addView(smallButton("Retry", true) {
                    if (Store.retryOne(this, f.tx)) {
                        OfflineService.ensureRunning(this)
                        toast("Dialing again. Credits are only used if it is delivered.")
                    }
                    needsSig = ""
                    refreshSales()
                })
                needsBox.addView(row)
            }
            retryBtn.visibility = if (fails.size > 1) View.VISIBLE else View.GONE
            retryBtn.text = "Retry all ${fails.size}"
            needsCard.visibility = if (needs.isNotEmpty() || fails.isNotEmpty()) View.VISIBLE else View.GONE
        }

        val limit = if (showMoreSales) 60 else 8
        jobsView.text = Store.recentJobs(this, limit).joinToString("\n\n").ifBlank { "No sales yet." }
        salesToggle.text = if (showMoreSales) "Show fewer" else "Show more"
    }

    private fun confirmRetry() {
        val n = Store.retryableCount(this)
        if (n == 0) { refreshSales(); return }
        confirmSheet("Dial $n sale(s) again?", "These did not go through. Credits are only used for the ones that are delivered.", "Retry all") {
            Store.requeueRetryable(this)
            OfflineService.ensureRunning(this)
            needsSig = ""
            refreshSales()
        }
    }

    /** Type the customer's real number for a sale whose receipt hid some digits. */
    private fun showEnterNumber(n: NeedsNumber) {
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(24))
        }
        body.addView(text("ENTER NUMBER", MINT, 18f, bold = true))
        body.addView(text("Ksh${n.amount}  ${n.label}\n${n.name.ifBlank { "unknown name" }}", TEXT, 14f).apply { setPadding(0, dp(12), 0, 0) })
        body.addView(text("The receipt showed ${n.prefix}...${n.suffix}. The number you type must start and end with those digits.",
            MUTED, 12f).apply { setPadding(0, dp(8), 0, dp(10)) })
        val field = EditText(this).apply {
            hint = "07XX XXX XXX"
            setHintTextColor(MUTED)
            setTextColor(TEXT)
            typeface = Typeface.MONOSPACE
            textSize = 20f
            inputType = InputType.TYPE_CLASS_PHONE
            maxLines = 1
            background = box(SURFACE2, LINE)
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        body.addView(field, LinearLayout.LayoutParams(MATCH, WRAP))
        val verdict = text("", MUTED, 13f).apply { setPadding(0, dp(8), 0, 0) }
        body.addView(verdict)
        fun check(): String? {
            val phone = Contacts.canonical(field.text.toString())
            return when {
                field.text.isBlank() -> null
                phone == null -> "That is not a Kenyan mobile number."
                !Protocol.matchesMask(phone, n.prefix, n.suffix) -> "That number does not fit the digits in the receipt."
                else -> ""
            }
        }
        watch(field) {
            val c = check()
            verdict.text = when (c) { null -> ""; "" -> "Fits the receipt."; else -> c }
            verdict.setTextColor(if (c == "") MINT else RED)
        }
        val dialog = openSheet(ScrollView(this).apply { addView(body) })
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(16), 0, 0) }
        buttons.addView(smallButton("SEND BUNDLE", true) {
            val phone = Contacts.canonical(field.text.toString())
            if (phone == null || check() != "") {
                verdict.text = check() ?: "Type the number first."
                verdict.setTextColor(RED)
            } else {
                val problem = Store.resolveJob(this, n.tx, phone, "")
                if (problem != null) {
                    verdict.text = problem
                    verdict.setTextColor(RED)
                } else {
                    OfflineService.ensureRunning(this)
                    dialog.dismiss()
                    needsSig = ""
                    refreshSales()
                }
            }
        }, LinearLayout.LayoutParams(0, dp(52), 2f).apply { setMargins(0, 0, dp(6), 0) })
        buttons.addView(smallButton("CANCEL", false) { dialog.dismiss() },
            LinearLayout.LayoutParams(0, dp(52), 1f).apply { setMargins(dp(6), 0, 0, 0) })
        body.addView(buttons)
        dialog.show()
    }

    // ---- activity log: what was attempted and what failed ----------------

    /** True for lines about something that went wrong or a sale that did not go through. */
    private fun isProblemLine(line: String): Boolean {
        val l = line.lowercase()
        return listOf("failed", "rejected", "could not", "no offer", "ignored", "problem", "not dialed",
            "unknown", "no_credits", "error", "not valid", "expired", "stopped", "no number", "not used").any { l.contains(it) }
    }

    private fun logText(lines: List<String>): CharSequence {
        val sb = SpannableStringBuilder()
        for (line in lines) {
            val start = sb.length
            sb.append(line).append("\n\n")
            val color = when {
                isProblemLine(line) -> RED
                line.contains("  done ") -> MINT
                else -> MUTED
            }
            sb.setSpan(ForegroundColorSpan(color), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return sb
    }

    private fun showLog() {
        val all = Store.logLines(this, 500)
        var onlyProblems = false
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        body.addView(text("ACTIVITY LOG", MINT, 18f, bold = true))
        val summary = text("", MUTED, 12f).apply { setPadding(0, dp(4), 0, dp(10)) }
        body.addView(summary)
        val view = text("", MUTED, 12f, mono = true).apply { setLineSpacing(0f, 1.1f) }
        val dialog = openSheet(ScrollView(this).apply { addView(body) })

        val tabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val allBtn = smallButton("ALL", true) {}
        val badBtn = smallButton("PROBLEMS ONLY", false) {}
        fun shown(): List<String> = if (onlyProblems) all.filter { isProblemLine(it) } else all
        fun paint() {
            val lines = shown()
            view.text = if (lines.isEmpty()) (if (onlyProblems) "No problems recorded." else "Log is empty.") else logText(lines)
            summary.text = "${all.count { isProblemLine(it) }} problem line(s) in ${all.size} entries. Newest first. Red = problem, green = sale done."
            allBtn.setTextColor(if (!onlyProblems) MINT_INK else MINT)
            allBtn.background = box(if (!onlyProblems) MINT else SURFACE2, MINT)
            badBtn.setTextColor(if (onlyProblems) MINT_INK else MINT)
            badBtn.background = box(if (onlyProblems) MINT else SURFACE2, MINT)
        }
        allBtn.setOnClickListener { onlyProblems = false; paint() }
        badBtn.setOnClickListener { onlyProblems = true; paint() }
        tabs.addView(allBtn, LinearLayout.LayoutParams(0, dp(46), 1f).apply { setMargins(0, 0, dp(6), 0) })
        tabs.addView(badBtn, LinearLayout.LayoutParams(0, dp(46), 1f).apply { setMargins(dp(6), 0, 0, 0) })
        body.addView(tabs)
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(8), 0, dp(10)) }
        actions.addView(smallButton("COPY", false) { copyText(shown().joinToString("\n")) },
            LinearLayout.LayoutParams(0, dp(46), 1f).apply { setMargins(0, 0, dp(6), 0) })
        actions.addView(smallButton("CLOSE", false) { dialog.dismiss() },
            LinearLayout.LayoutParams(0, dp(46), 1f).apply { setMargins(dp(6), 0, 0, 0) })
        body.addView(actions)
        body.addView(view)
        paint()
        dialog.show()
    }

    // =====================================================================
    // CUSTOMERS: best buyers, promoters, and who stopped buying
    // =====================================================================

    private fun buildCustomers(page: LinearLayout) {
        page.addView(text("Customers", TEXT, 24f, bold = true).apply { setPadding(0, 0, 0, dp(14)) })
        customersBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        page.addView(customersBox)
    }

    private fun dateOf(sec: Long): String =
        java.text.SimpleDateFormat("dd MMM", java.util.Locale.US).format(java.util.Date(sec * 1000L))

    private fun spaced(phone: String): String =
        if (phone.length == 10) phone.substring(0, 4) + " " + phone.substring(4, 7) + " " + phone.substring(7) else phone

    private fun refreshCustomers() {
        customersBox.removeAllViews()
        val all = Store.customers(this)
        if (all.isEmpty()) {
            val c = card()
            c.addView(label("CUSTOMERS"))
            c.addView(text("Customers appear here after their first delivered sale.", MUTED, 12f))
            customersBox.addView(c)
            return
        }

        val top = card()
        top.addView(label("TOP CUSTOMERS"))
        for ((i, c) in all.sortedByDescending { it.total }.take(15).withIndex()) top.addView(customerRow(i + 1, c))
        customersBox.addView(top)

        val byRef = all.filter { it.referrer.isNotBlank() }.groupBy { it.referrer }
        if (byRef.isNotEmpty()) {
            val pc = card()
            pc.addView(label("TOP PROMOTERS"))
            val rows = byRef.entries.sortedByDescending { e -> e.value.sumOf { it.total } }.take(10)
            for ((i, e) in rows.withIndex()) {
                val name = all.firstOrNull { it.phone == e.key }?.name ?: e.key
                val total = e.value.sumOf { it.total }
                pc.addView(text("${i + 1}  $name\n    ${e.value.size} customer(s) brought, Ksh $total", TEXT, 12f)
                    .apply { setPadding(0, dp(4), 0, dp(4)) })
            }
            customersBox.addView(pc)
        }

        val days = Store.num(this, "dormant_days", 14)
        val cutoff = System.currentTimeMillis() / 1000L - days * 86_400L
        val dormant = all.filter { it.last < cutoff }.sortedBy { it.last }
        val dc = card()
        dc.addView(label("NOT BOUGHT IN $days DAYS"))
        if (dormant.isEmpty()) {
            dc.addView(text("Nobody. Good.", MUTED, 12f))
        } else {
            dc.addView(smallButton("SMS ALL (${dormant.size})", false) { confirmWinBack(dormant) },
                LinearLayout.LayoutParams(MATCH, dp(44)).apply { setMargins(0, 0, 0, dp(6)) })
            for (c in dormant.take(30)) dc.addView(dormantRow(c))
        }
        customersBox.addView(dc)
    }

    private fun customerRow(rank: Int, c: Customer): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(6), dp(8), dp(6))
            background = box(SURFACE2, LINE)
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { setMargins(0, dp(4), 0, 0) }
            setOnClickListener { customerDetail(c) }
        }
        row.addView(text("$rank", MUTED, 12f), LinearLayout.LayoutParams(dp(26), WRAP))
        val mid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        mid.addView(text(c.name, TEXT, 13f))
        mid.addView(text("${spaced(c.phone)}  -  ${c.count} buy(s), last ${dateOf(c.last)}", MUTED, 10f))
        row.addView(mid, LinearLayout.LayoutParams(0, WRAP, 1f))
        row.addView(text("Ksh ${c.total}", MINT, 12f, bold = true))
        return row
    }

    private fun dormantRow(c: Customer): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(4))
        }
        val mid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        mid.addView(text(c.name, TEXT, 13f))
        mid.addView(text("${spaced(c.phone)}  -  last bought ${dateOf(c.last)}", MUTED, 10f))
        row.addView(mid, LinearLayout.LayoutParams(0, WRAP, 1f))
        row.addView(smallButton("SMS", false) { sendWinBack(listOf(c)) })
        return row
    }

    private fun customerDetail(c: Customer) {
        val refName = Store.customers(this).firstOrNull { it.phone == c.referrer }?.name
        val body = sheetBody()
        body.addView(text(c.name, TEXT, 20f, bold = true))
        body.addView(text(spaced(c.phone), MUTED, 14f))
        body.addView(text("Spent Ksh ${c.total} in ${c.count} purchase(s)\nLast purchase ${dateOf(c.last)}\nReferred by: ${refName ?: "nobody"}",
            TEXT, 14f).apply { setPadding(0, dp(12), 0, dp(16)) })
        val sheet = openSheet(body)
        body.addView(smallButton("Set who referred them", true) { sheet.dismiss(); chooseReferrer(c) },
            LinearLayout.LayoutParams(MATCH, dp(50)))
        body.addView(smallButton("Close", false) { sheet.dismiss() },
            LinearLayout.LayoutParams(MATCH, dp(46)).apply { setMargins(0, dp(8), 0, 0) })
        sheet.show()
    }

    private fun chooseReferrer(c: Customer) {
        val others = Store.customers(this).filter { it.phone != c.phone }.sortedBy { it.name }.take(60)
        if (others.isEmpty()) {
            toast("There are no other customers yet.")
            return
        }
        val names = listOf("Nobody") + others.map { it.name + "  " + spaced(it.phone) }
        choiceSheet("Who referred ${c.name}?", names) { which ->
            Store.setReferrer(this, c.phone, if (which == 0) "" else others[which - 1].phone)
            refreshCustomers()
        }
    }

    private fun confirmWinBack(list: List<Customer>) {
        val text = Store.text(this, "winback_text", Store.DEFAULT_WINBACK)
        confirmSheet("Message ${list.size} customer(s)?", "Each message uses your airtime.\n\n$text", "Send") { sendWinBack(list) }
    }

    private fun sendWinBack(list: List<Customer>) {
        if (!Sms.canSend(this)) {
            toast("Allow SMS sending first: SETTINGS > MESSAGES TO CUSTOMERS.")
            return
        }
        val template = Store.text(this, "winback_text", Store.DEFAULT_WINBACK)
        var sent = 0
        for (c in list) if (Sms.send(this, c.phone, Sms.fill(template, c.name, "", ""))) sent++
        Store.log(this, "Come-back SMS sent to $sent customer(s)")
        toast("Sent $sent message(s).")
    }

    // =====================================================================
    // SETTINGS: everything you set once
    // =====================================================================

    private fun buildSettings(page: LinearLayout) {
        page.addView(text("Settings", TEXT, 24f, bold = true).apply { setPadding(0, 0, 0, dp(14)) })
        settingsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        page.addView(settingsBox)
    }

    private fun refreshSettings() {
        settingsBox.removeAllViews()

        settingsBox.addView(section("Phone setup", "phone") { b ->
            // permissions
            val missing = missingPermissions()
            b.addView(statusRow("Permissions", missing.isEmpty(), if (missing.isEmpty()) "All allowed" else "Some are missing") {
                if (missing.isEmpty()) openAppSettings() else requestMissing()
            })
            // dialing helper
            val helper = UssdAccessibilityService.isEnabled(this)
            b.addView(statusRow("Dialing helper", helper, if (helper) "On" else "Off. Bundle codes will not dial.") { openAccessibility() })
            if (!helper) {
                b.addView(text("If it is greyed out: Settings > Apps > bn > top-right menu > Allow restricted settings, then try again. " +
                    "The helper does nothing except while bn is dialing a sale. Keep the phone plugged in with no screen lock.",
                    MUTED, 11f).apply { setPadding(0, 0, 0, dp(8)) })
            }
            // M-Pesa line
            val subs = simList()
            if (subs.size > 1) {
                val chosen = subs.firstOrNull { it.subscriptionId == Store.designatedSub(this) }
                val line = if (chosen == null) "Not chosen yet" else "SIM ${chosen.simSlotIndex + 1}  ${chosen.carrierName ?: ""}".trim()
                b.addView(statusRow("M-Pesa line", chosen != null, line) { chooseSim() })
            }
            // device id
            b.addView(text("Your device ID", MUTED, 12f).apply { setPadding(0, dp(10), 0, 0) })
            val idRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            idRow.addView(text(Protocol.showDeviceId(Store.deviceId(this)), TEXT, 18f, mono = true, bold = true), LinearLayout.LayoutParams(0, WRAP, 1f))
            idRow.addView(smallButton("Copy", false) { copyText(Protocol.showDeviceId(Store.deviceId(this))) })
            b.addView(idRow)
            b.addView(text("Send this to the seller when you buy credits.", MUTED, 11f))
        })

        settingsBox.addView(section("Messages to customers", "sms") { b ->
            b.addView(text("Each message uses your airtime.", MUTED, 12f))
            b.addView(switchRow("Tell the customer if it fails", "", Store.flag(this, "sms_fail", true)) { Store.setFlag(this, "sms_fail", it) })
            b.addView(textSetting("Failure message", "sms_fail_text", Store.DEFAULT_SMS_FAIL))
            b.addView(switchRow("Tell the customer when it is delivered", "", Store.flag(this, "sms_ok", false)) { Store.setFlag(this, "sms_ok", it) })
            b.addView(textSetting("Success message", "sms_ok_text", Store.DEFAULT_SMS_OK))
            if (!Sms.canSend(this)) {
                b.addView(text("Sending messages is not allowed yet.", ORANGE, 12f).apply { setPadding(0, dp(10), 0, dp(6)) })
                b.addView(smallButton("Allow sending messages", true) { requestPermissions(arrayOf(Manifest.permission.SEND_SMS), 2) },
                    LinearLayout.LayoutParams(MATCH, dp(46)))
            }
        })

        settingsBox.addView(section("Customers with hidden numbers", "hidden") { b ->
            b.addView(text("Safaricom hides some digits of the sender's number. bn finds the real one like this:", MUTED, 12f))
            b.addView(switchRow("Use saved customers", "A contact named BN_<their name> with a matching number.", Store.flag(this, "use_contacts", true)) { Store.setFlag(this, "use_contacts", it) })
            b.addView(switchRow("Ask 334 for new customers", "The customer approves and you get their number.", Store.flag(this, "use_334", true)) { Store.setFlag(this, "use_334", it) })
            b.addView(switchRow("Save new customers to contacts", "As BN_<name> after their first delivered sale.", Store.flag(this, "save_contacts", true)) { Store.setFlag(this, "save_contacts", it) })
            b.addView(text("How long to wait for approval", MUTED, 12f).apply { setPadding(0, dp(10), 0, dp(2)) })
            b.addView(chipRow(listOf("5 min" to 5, "10 min" to 10, "20 min" to 20), Store.num(this, "wait_min", 10)) { Store.setNum(this, "wait_min", it) })
            if (!Contacts.canRead(this) || !Contacts.canWrite(this)) {
                b.addView(text("Contacts are not allowed yet.", ORANGE, 12f).apply { setPadding(0, dp(10), 0, dp(6)) })
                b.addView(smallButton("Allow contacts", true) {
                    requestPermissions(arrayOf(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS), 2)
                }, LinearLayout.LayoutParams(MATCH, dp(46)))
            }
        })

        settingsBox.addView(section("Come-back message and daily summary", "back") { b ->
            b.addView(text("A customer has gone quiet after", MUTED, 12f))
            b.addView(chipRow(listOf("7 days" to 7, "14 days" to 14, "30 days" to 30), Store.num(this, "dormant_days", 14)) { Store.setNum(this, "dormant_days", it) })
            b.addView(textSetting("Come-back message", "winback_text", Store.DEFAULT_WINBACK))
            b.addView(switchRow("Daily summary notification", "The day's sales, every evening.", Store.flag(this, "summary", true)) { Store.setFlag(this, "summary", it) })
            b.addView(chipRow(listOf("6 pm" to 18, "8 pm" to 20, "9 pm" to 21, "10 pm" to 22), Store.num(this, "summary_hour", 21)) { Store.setNum(this, "summary_hour", it) })
        })

        settingsBox.addView(section("Offers", "offers") { b ->
            b.addView(text("Put the starting offers back. Offers you made or changed are lost.", MUTED, 12f))
            b.addView(smallButton("Reset offers", false) { confirmResetOffers() },
                LinearLayout.LayoutParams(MATCH, dp(46)).apply { setMargins(0, dp(8), 0, 0) })
        })
    }

    /** A line with a name, a short status and a button, for the phone-setup section. */
    private fun statusRow(name: String, ok: Boolean, status: String, onTap: () -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, dp(6))
        }
        row.addView(text(if (ok) "\u25CF" else "\u25CB", if (ok) MINT else ORANGE, 16f), LinearLayout.LayoutParams(dp(26), WRAP))
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(text(name, TEXT, 14f))
        col.addView(text(status, if (ok) MUTED else ORANGE, 12f))
        row.addView(col, LinearLayout.LayoutParams(0, WRAP, 1f))
        row.addView(smallButton(if (ok) "Change" else "Fix", !ok) { onTap() })
        return row
    }

    private fun requiredPermissions(): Array<String> {
        val list = mutableListOf(Manifest.permission.RECEIVE_SMS, Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_PHONE_STATE)
        if (Build.VERSION.SDK_INT >= 33) list.add(Manifest.permission.POST_NOTIFICATIONS)
        return list.toTypedArray()
    }

    private fun missingPermissions(): Array<String> =
        requiredPermissions().filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }.toTypedArray()

    private fun requestMissing() {
        val missing = missingPermissions()
        if (missing.isNotEmpty()) requestPermissions(missing, 1)
    }

    private fun openAppSettings() {
        try {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", packageName, null)))
        } catch (e: Exception) {
            // some phones have no such screen; nothing else to do
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (Store.listening(this)) OfflineService.ensureRunning(this)
        showTab(currentTab)
    }

    private fun friendlyPermission(p: String): String = when (p) {
        Manifest.permission.RECEIVE_SMS -> "SMS, to read M-Pesa payments"
        Manifest.permission.CALL_PHONE -> "Phone calls, to dial the bundle codes"
        Manifest.permission.READ_PHONE_STATE -> "Phone info, to choose the M-Pesa SIM"
        Manifest.permission.POST_NOTIFICATIONS -> "Notifications, to show that bn is running"
        else -> p.substringAfterLast('.')
    }

    // =====================================================================
    // SHEETS: panels that slide up inside the app. No pop-up windows, nothing fills the whole screen.
    // =====================================================================

    private inner class Sheet(content: View) {
        private val body: View = if (content is ScrollView && content.childCount > 0) {
            val child = content.getChildAt(0)
            content.removeView(child)
            child
        } else {
            content
        }
        private var overlay: FrameLayout? = null

        fun show() {
            val ov = FrameLayout(this@MainActivity).apply {
                setBackgroundColor(Color.parseColor("#B3000000"))
                isClickable = true
                setOnClickListener { dismiss() }
            }
            val scroll = SheetScroll(this@MainActivity).apply {
                addView(body)
                background = topRounded(SURFACE)
                isClickable = true
            }
            ov.addView(scroll, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM))
            frame.addView(ov, FrameLayout.LayoutParams(MATCH, MATCH))
            overlay = ov
            sheets.add(this)
            ov.alpha = 0f
            ov.animate().alpha(1f).setDuration(150L).start()
        }

        fun dismiss() {
            val ov = overlay ?: return
            overlay = null
            frame.removeView(ov)
            sheets.remove(this)
            hideKeyboard()
        }
    }

    private fun openSheet(content: View): Sheet = Sheet(content)

    private fun topRounded(fill: Int): GradientDrawable = GradientDrawable().apply {
        setColor(fill)
        val r = dp(24).toFloat()
        cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
    }

    private fun sheetBody(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(22), dp(20), dp(28))
    }

    /** A yes/no question inside the app. */
    private fun confirmSheet(title: String, message: String, yes: String, onYes: () -> Unit) {
        val body = sheetBody()
        body.addView(text(title, TEXT, 18f, bold = true))
        body.addView(text(message, MUTED, 14f).apply { setPadding(0, dp(8), 0, dp(18)) })
        val sheet = openSheet(body)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(smallButton("Cancel", false) { sheet.dismiss() },
            LinearLayout.LayoutParams(0, dp(50), 1f).apply { setMargins(0, 0, dp(6), 0) })
        row.addView(smallButton(yes, true) { sheet.dismiss(); onYes() },
            LinearLayout.LayoutParams(0, dp(50), 1f).apply { setMargins(dp(6), 0, 0, 0) })
        body.addView(row)
        sheet.show()
    }

    /** A short list to pick from. */
    private fun choiceSheet(title: String, items: List<String>, onPick: (Int) -> Unit) {
        val body = sheetBody()
        body.addView(text(title, TEXT, 18f, bold = true))
        val sheet = openSheet(body)
        for ((i, name) in items.withIndex()) {
            body.addView(smallButton(name, false) { sheet.dismiss(); onPick(i) },
                LinearLayout.LayoutParams(MATCH, dp(50)).apply { setMargins(0, dp(10), 0, 0) })
        }
        body.addView(smallButton("Cancel", false) { sheet.dismiss() },
            LinearLayout.LayoutParams(MATCH, dp(46)).apply { setMargins(0, dp(14), 0, 0) })
        sheet.show()
    }

    @SuppressLint("MissingPermission", "HardwareIds")
    private fun ownNumber(): String = try {
        val tm = getSystemService(Context.TELEPHONY_SERVICE) as android.telephony.TelephonyManager
        val c = Contacts.canonical(tm.line1Number ?: "")
        if (c == null) "" else Protocol.localPhone(c)
    } catch (e: Exception) {
        ""
    }

    /**
     * Dials the code with the owner's number, stops before the last [skip] steps, and shows the menu the buyer would see.
     * If the purchase goes through anyway, the credits are used like a sale, so the test cannot be used to get free bundles.
     */
    private fun runCodeTest(o: Protocol.Offer, phone254: String, skip: Int, status: TextView, button: Button) {
        val steps = Protocol.steps(o.template, phone254)
        if (steps.size < 2) {
            status.text = "This code is dialed in one go, so it can only be tested by really buying it."
            status.setTextColor(ORANGE)
            return
        }
        if (!UssdAccessibilityService.isEnabled(this)) {
            status.text = "Turn on the bn helper first: SETTINGS > DIALING HELPER."
            status.setTextColor(RED)
            return
        }
        if (checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            status.text = "Allow the Phone permission first: SETTINGS > PERMISSIONS."
            status.setTextColor(RED)
            return
        }
        val cost = Protocol.parseAmountSpec(o.amount)?.lo?.toLong() ?: 0L
        if (cost <= 0L) {
            status.text = "Fill in the amount first."
            status.setTextColor(RED)
            return
        }
        if (Store.creditBalance(this) < cost) {
            status.text = "You need at least $cost credits to test this code, in case the purchase goes through."
            status.setTextColor(RED)
            return
        }
        val skipped = minOf(skip, steps.size - 1)
        val use = steps.dropLast(skipped)
        button.isEnabled = false
        status.text = "Testing: dialing ${use[0]}" + (if (use.size > 1) " and typing ${use.drop(1).joinToString(", ")}" else "") +
            ". Do not touch the phone until this finishes..."
        status.setTextColor(MINT)
        Store.log(this, "Code test: ${use.joinToString(" > ")} (last $skipped step(s) not sent)")
        scope.launch {
            val r = UssdSession.run(this@MainActivity, use, 150_000L)
            button.isEnabled = true
            if (r == null) {
                status.text = "No answer in time. Check the helper is on and the phone is not locked."
                status.setTextColor(RED)
                Store.log(this@MainActivity, "Code test: no answer in time")
            } else if (r.ok && !r.menu) {
                // The purchase actually went through: it is charged exactly like a sale.
                val charged = Store.spendCredits(this@MainActivity, cost)
                status.text = "The purchase WENT THROUGH, so " + (if (charged) "$cost credits were used" else "it could not be charged") +
                    ". Leave out more of the last steps next time.\n\n" + r.text.ifBlank { "(empty reply)" }
                status.setTextColor(ORANGE)
                Store.log(this@MainActivity, "Code test went through: " + (if (charged) "$cost credits used" else "not charged") +
                    ". " + r.text.replace('\n', ' ').take(80))
                refreshHome()
            } else {
                status.text = "WHAT THE BUYER WOULD SEE:\n" + r.text.ifBlank { "(empty reply)" } +
                    "\n\nNothing was bought. The last $skipped step(s) were not sent."
                status.setTextColor(if (r.ok) MINT else RED)
                Store.log(this@MainActivity, "Code test answer: " + r.text.replace('\n', ' ').take(100))
            }
        }
    }

    // =====================================================================
    // OFFER EDITOR: a full page, every box wide and fully visible, checked live
    // =====================================================================

    private fun watch(f: EditText, onChange: () -> Unit) {
        f.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) { onChange() }
        })
    }

    private fun insertAt(f: EditText, s: String) {
        val a = maxOf(f.selectionStart, 0)
        val b = maxOf(f.selectionEnd, 0)
        f.text.replace(minOf(a, b), maxOf(a, b), s)
    }

    /** Add a new offer (existing = null) or edit/delete one. Everything typed is checked and previewed live. */
    private fun editOffer(existing: Protocol.Offer?) {
        var category = existing?.category ?: Protocol.CAT_DATA
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(24))
        }
        body.addView(text(if (existing == null) "ADD OFFER" else "EDIT OFFER", MINT, 18f, bold = true))

        body.addView(label("TYPE").apply { setPadding(0, dp(16), 0, dp(6)) })
        val catRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val catButtons = mutableListOf<Pair<String, Button>>()
        var updatePreview: () -> Unit = {}
        fun paintCategories() {
            for ((key, button) in catButtons) {
                val on = key == category
                button.setTextColor(if (on) MINT_INK else MINT)
                button.background = box(if (on) MINT else SURFACE2, MINT)
            }
        }
        for (key in Protocol.CATEGORIES) {
            val name = when (key) {
                Protocol.CAT_DATA -> "DATA"
                Protocol.CAT_MINUTES -> "MINUTES"
                else -> "SMS"
            }
            val button = smallButton(name, false) { category = key; paintCategories(); updatePreview() }
            catButtons.add(key to button)
            catRow.addView(button, LinearLayout.LayoutParams(0, dp(48), 1f).apply { setMargins(0, 0, dp(6), 0) })
        }
        body.addView(catRow)

        fun field(title: String, hint: String, value: String, mono: Boolean, multi: Boolean): EditText {
            body.addView(label(title).apply { setPadding(0, dp(18), 0, dp(6)) })
            val f = EditText(this).apply {
                this.hint = hint
                setHintTextColor(MUTED)
                setTextColor(TEXT)
                if (mono) typeface = Typeface.MONOSPACE
                textSize = 17f
                inputType = if (multi) {
                    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                } else {
                    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                }
                if (multi) { minLines = 2; maxLines = 4; gravity = Gravity.TOP or Gravity.START }
                setText(value)
                background = box(SURFACE2, LINE)
                setPadding(dp(14), dp(12), dp(14), dp(12))
                minHeight = dp(52)
            }
            body.addView(f, LinearLayout.LayoutParams(MATCH, WRAP))
            return f
        }
        val amountField = field("AMOUNT THE CUSTOMER PAYS (KSH)", "20   or a range like 22-23", existing?.amount ?: "", true, false)
        val labelField = field("WHAT THE CUSTOMER GETS", "e.g. Bingwa 250 MBs 24hrs", existing?.label ?: "", false, true)
        val codeFieldE = field("USSD CODE", "*180*5*2*N*2*1#", existing?.template ?: "", true, false)

        // Tap-to-type keys for the characters that are awkward on a phone keyboard.
        val keys = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(8), 0, 0) }
        for (k in listOf("*", "#", "N")) {
            keys.addView(smallButton(if (k == "N") "N = number" else k, false) { insertAt(codeFieldE, k) },
                LinearLayout.LayoutParams(0, dp(46), if (k == "N") 2f else 1f).apply { setMargins(0, 0, dp(6), 0) })
        }
        body.addView(keys)
        body.addView(text(
            "Write the code the usual way, with * and #. N is the customer's number.\n" +
                "Example: *188*5*2*N*1*1#\n" +
                "The phone dials it one step at a time for you: *188# first, then 5, 2, the number, 1, 1. " +
                "A short code like *130*N# is dialed in one go.",
            MUTED, 12f).apply { setPadding(0, dp(6), 0, 0) })

        val fromField = field("OPEN FROM (optional, 24-hour)", "06:00", existing?.from ?: "", true, false)
        val toField = field("OPEN UNTIL", "23:00", existing?.to ?: "", true, false)

        // Live preview of what will happen, updated on every keystroke.
        val preview = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = box(SURFACE, LINE)
        }
        preview.addView(label("PREVIEW"))
        val pricePreview = text("", TEXT, 14f)
        val dialPreview = text("", MINT, 14f, mono = true).apply { setPadding(0, dp(6), 0, 0) }
        val timePreview = text("", MUTED, 13f).apply { setPadding(0, dp(6), 0, 0) }
        val verdict = text("", MUTED, 13f).apply { setPadding(0, dp(10), 0, 0) }
        preview.addView(pricePreview)
        preview.addView(dialPreview)
        preview.addView(timePreview)
        preview.addView(verdict)
        body.addView(preview, LinearLayout.LayoutParams(MATCH, WRAP).apply { setMargins(0, dp(18), 0, 0) })

        fun candidate(): Protocol.Offer {
            val template = codeFieldE.text.toString().trim()
            val wasBlank = existing == null || existing.template.isBlank()
            val enabled = if (template.isBlank()) false else if (wasBlank) true else existing!!.enabled
            return Protocol.Offer(
                existing?.id ?: Protocol.newOfferId(),
                category,
                amountField.text.toString().trim(),
                labelField.text.toString().replace('\n', ' ').trim(),
                template,
                enabled,
                fromField.text.toString().trim(),
                toField.text.toString().trim()
            )
        }
        // Test: dials the steps with YOUR number but stops before the last ones, so nothing is bought.
        val testCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = box(SURFACE, LINE)
        }
        testCard.addView(label("TEST THIS CODE"))
        testCard.addView(text("Dials the steps with your own number but stops before the last ones, so nothing is bought. " +
            "You see what the buyer would be offered. If the purchase goes through anyway, the credits for it are used, like a sale.", MUTED, 12f))
        val testNumber = EditText(this).apply {
            hint = "your number, 07XX XXX XXX"
            setHintTextColor(MUTED)
            setTextColor(TEXT)
            typeface = Typeface.MONOSPACE
            textSize = 16f
            inputType = InputType.TYPE_CLASS_PHONE
            maxLines = 1
            setText(Store.text(this@MainActivity, "test_number", ownNumber()))
            background = box(SURFACE2, LINE)
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }
        watch(testNumber) { Store.setText(this, "test_number", testNumber.text.toString().trim()) }
        testCard.addView(testNumber, LinearLayout.LayoutParams(MATCH, WRAP).apply { setMargins(0, dp(8), 0, 0) })
        testCard.addView(label("DO NOT SEND THE LAST").apply { setPadding(0, dp(10), 0, dp(2)) })
        var skipLast = 1
        testCard.addView(chipRow(listOf("1 STEP" to 1, "2 STEPS" to 2, "3 STEPS" to 3), 1) { skipLast = it })
        val testStatus = text("", MUTED, 13f).apply { setPadding(0, dp(8), 0, 0) }
        val testButton = smallButton("RUN TEST", true) {}
        testButton.setOnClickListener {
            val o = candidate()
            val phone = Contacts.canonical(testNumber.text.toString())
            if (o.template.isBlank()) {
                testStatus.text = "Type the code first."
                testStatus.setTextColor(RED)
            } else if (phone == null) {
                testStatus.text = "Type your own number first."
                testStatus.setTextColor(RED)
            } else {
                runCodeTest(o, phone, skipLast, testStatus, testButton)
            }
        }
        testCard.addView(testButton, LinearLayout.LayoutParams(MATCH, dp(48)).apply { setMargins(0, dp(8), 0, 0) })
        testCard.addView(testStatus)
        body.addView(testCard, LinearLayout.LayoutParams(MATCH, WRAP).apply { setMargins(0, dp(14), 0, 0) })

        updatePreview = {
            val o = candidate()
            val r = Protocol.parseAmountSpec(o.amount)
            pricePreview.text = when {
                o.amount.isBlank() -> "Customer pays: ..."
                r == null -> "Customer pays: not a valid amount"
                r.lo.compareTo(r.hi) == 0 -> "Customer pays: Ksh ${o.amount.trim()}"
                else -> "Customer pays: any amount from Ksh ${r.lo.toPlainString()} to ${r.hi.toPlainString()}"
            }
            if (o.template.isBlank()) {
                dialPreview.text = "Phone dials: ..."
            } else {
                val steps = Protocol.steps(o.template, "254712345678")
                dialPreview.text = if (steps.size > 1) "Phone dials ${steps[0]}\nthen types: ${steps.drop(1).joinToString(", ")}"
                else "Phone dials: ${steps[0]}"
            }
            timePreview.text = if (o.scheduled) "Sells only from ${o.from} to ${o.to}" else "Sells all day"
            if (o.amount.isBlank() && o.label.isBlank() && o.template.isBlank()) {
                verdict.text = "Fill in the boxes. The preview uses a sample customer, 0712345678."
                verdict.setTextColor(MUTED)
            } else {
                val problem = Protocol.validateOffer(o, Store.offers(this))
                if (problem == null) {
                    verdict.text = "Looks good." + if (o.template.isBlank()) " (Saved switched off until you add a code.)" else ""
                    verdict.setTextColor(MINT)
                } else {
                    verdict.text = problem
                    verdict.setTextColor(RED)
                }
            }
        }
        watch(amountField) { updatePreview() }
        watch(labelField) { updatePreview() }
        watch(codeFieldE) { updatePreview() }
        watch(fromField) { updatePreview() }
        watch(toField) { updatePreview() }

        val dialog = openSheet(ScrollView(this).apply { addView(body) })
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(20), 0, 0) }
        buttons.addView(smallButton("SAVE", true) {
            val offer = candidate()
            val problem = Protocol.validateOffer(offer, Store.offers(this))
            if (problem != null) {
                verdict.text = problem
                verdict.setTextColor(RED)
            } else {
                Store.upsertOffer(this, offer)
                dialog.dismiss()
                offersSig = ""
                refreshHome()
            }
        }, LinearLayout.LayoutParams(0, dp(52), 2f).apply { setMargins(0, 0, dp(6), 0) })
        buttons.addView(smallButton("CANCEL", false) { dialog.dismiss() },
            LinearLayout.LayoutParams(0, dp(52), 1f).apply { setMargins(dp(6), 0, 0, 0) })
        body.addView(buttons)
        if (existing != null) {
            body.addView(smallButton("Delete this offer", false) {
                confirmSheet("Delete this offer?", "Ksh ${existing.amount}  ${existing.label}", "Delete") {
                    Store.deleteOffer(this, existing.id)
                    dialog.dismiss()
                    offersSig = ""
                    refreshHome()
                }
            }.apply { setTextColor(RED) },
                LinearLayout.LayoutParams(MATCH, dp(48)).apply { setMargins(0, dp(14), 0, 0) })
        }
        paintCategories()
        updatePreview()
        dialog.show()
    }

    // =====================================================================
    // small view helpers
    // =====================================================================

    private fun readClipboard(): String = try {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString() ?: ""
    } catch (e: Exception) {
        ""
    }

    private fun copyText(s: String) {
        try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("bn", s))
            Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            // nothing else to do
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun box(fill: Int, stroke: Int): GradientDrawable = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(14).toFloat()
        if (stroke != LINE) setStroke(dp(1), stroke)
    }

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(14), dp(16), dp(14))
        background = box(SURFACE, LINE)
        layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { setMargins(0, 0, 0, dp(12)) }
    }

    /** A small boxed area for one number in the header. */
    private fun tile(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(10), dp(8), dp(10), dp(8))
        background = box(SURFACE2, LINE)
    }

    private fun label(s: String): TextView = TextView(this).apply {
        text = s
        setTextColor(MUTED)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        textSize = 11f
        letterSpacing = 0.08f
        setPadding(0, 0, 0, dp(6))
    }

    private fun text(
        s: String,
        color: Int,
        size: Float,
        mono: Boolean = false,
        bold: Boolean = false
    ): TextView = TextView(this).apply {
        this.text = s
        setTextColor(color)
        textSize = size
        typeface = when {
            mono -> Typeface.create(Typeface.MONOSPACE, if (bold) Typeface.BOLD else Typeface.NORMAL)
            bold -> Typeface.create("sans-serif-medium", Typeface.BOLD)
            else -> Typeface.create("sans-serif", Typeface.NORMAL)
        }
    }

    /** A short button. Filled mint for the main action, outlined for the rest. */
    private fun smallButton(caption: String, filled: Boolean, onTap: () -> Unit): Button = Button(this).apply {
        this.text = caption
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        textSize = 13f
        isAllCaps = false
        minHeight = 0
        minimumHeight = dp(40)
        setPadding(dp(14), dp(6), dp(14), dp(6))
        setTextColor(if (filled) MINT_INK else MINT)
        background = box(if (filled) MINT else SURFACE2, if (filled) MINT else LINE)
        stateListAnimator = null
        setOnClickListener { onTap() }
    }

    private fun tint(sw: Switch) {
        val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
        sw.thumbTintList = ColorStateList(states, intArrayOf(MINT, MUTED))
        sw.trackTintList = ColorStateList(states, intArrayOf(Color.parseColor("#14604d"), LINE))
    }

    /** A card with a heading that folds open and shut, so the settings stay tidy. */
    private fun section(title: String, key: String, build: (LinearLayout) -> Unit): View {
        val card = card()
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, 0)
        }
        val head = text("", TEXT, 15f, bold = true).apply { setPadding(0, dp(4), 0, dp(4)) }
        fun paint() {
            val open = openSections.contains(key)
            head.text = (if (open) "\u25BE  " else "\u25B8  ") + title
            body.visibility = if (open) View.VISIBLE else View.GONE
        }
        head.setOnClickListener {
            if (openSections.contains(key)) openSections.remove(key) else openSections.add(key)
            paint()
        }
        build(body)
        card.addView(head)
        card.addView(body)
        paint()
        return card
    }

    private fun switchRow(title: String, note: String, checked: Boolean, onChange: (Boolean) -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, dp(6))
        }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(text(title, TEXT, 13f))
        if (note.isNotBlank()) col.addView(text(note, MUTED, 11f))
        row.addView(col, LinearLayout.LayoutParams(0, WRAP, 1f))
        val sw = Switch(this).apply {
            tint(this)
            isChecked = checked
            setOnCheckedChangeListener { _, on -> onChange(on) }
        }
        row.addView(sw)
        return row
    }

    /** A row of choices where one is selected. */
    private fun chipRow(options: List<Pair<String, Int>>, current: Int, onPick: (Int) -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(4), 0, dp(4))
        }
        val buttons = mutableListOf<Pair<Int, Button>>()
        var selected = current
        fun paint() {
            for ((v, b) in buttons) {
                val on = v == selected
                b.setTextColor(if (on) MINT_INK else MINT)
                b.background = box(if (on) MINT else SURFACE2, MINT)
            }
        }
        for ((caption, v) in options) {
            val b = smallButton(caption, false) { selected = v; paint(); onPick(v) }
            buttons.add(v to b)
            row.addView(b, LinearLayout.LayoutParams(0, dp(40), 1f).apply { setMargins(0, 0, dp(4), 0) })
        }
        paint()
        return row
    }

    /** A message box that saves itself as you type. */
    private fun textSetting(title: String, key: String, def: String): View {
        val wrap = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        wrap.addView(label(title).apply { setPadding(0, dp(8), 0, dp(4)) })
        val f = EditText(this).apply {
            setText(Store.text(this@MainActivity, key, def))
            setTextColor(TEXT)
            textSize = 14f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            minLines = 2
            maxLines = 5
            gravity = Gravity.TOP or Gravity.START
            background = box(SURFACE2, LINE)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        watch(f) { Store.setText(this, key, f.text.toString()) }
        wrap.addView(f, LinearLayout.LayoutParams(MATCH, WRAP))
        wrap.addView(text("You can use {name}, {offer} and {amount}.", MUTED, 11f))
        return wrap
    }

    companion object {
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    }
}

/** A scroll view for sheets: as tall as its content, but never more than 90% of the space it is given. */
private class SheetScroll(context: Context) : ScrollView(context) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val avail = View.MeasureSpec.getSize(heightMeasureSpec)
        val cap = if (avail > 0) (avail * 0.9f).toInt() else avail
        super.onMeasure(widthMeasureSpec, View.MeasureSpec.makeMeasureSpec(cap, View.MeasureSpec.AT_MOST))
    }
}
