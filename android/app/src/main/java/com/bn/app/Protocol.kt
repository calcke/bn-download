package com.bn.app

import java.math.BigDecimal
import java.security.KeyFactory
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * Pure logic, no Android calls.
 *
 * Offers are the merchant's own (they edit them in the app). Credits are the seller's: a credit token is
 * signed by the seller's PRIVATE key (kept in the admin page) and checked here with the PUBLIC key that is
 * stored in assets/public_key.txt. The public key cannot make tokens, so taking the app apart gains nothing.
 */
object Protocol {

    // Both receipt layouts. See bn/parser.py for examples. \z is used instead of $ on purpose.
    private val HEAD = Regex("""([A-Z0-9]{10})\s+Confirmed\.?""")
    private val AMOUNT = Regex(
        """(?s)[Kk][Ss][Hh]\s?([\d,]+(?:\.\d{1,2})?)\s+received\s+from\s+(.+?)(?=\s+on\s+\d|\s+New\s+[Aa]ccount|\s*\z)"""
    )
    // The other layout, for money sent to a personal number: the name comes BEFORE the number.
    private val AMOUNT_PERSONAL = Regex(
        """(?s)received\s+[Kk][Ss][Hh]\s?([\d,]+(?:\.\d{1,2})?)\s+from\s+(.+?)(?=\s+on\s+\d)"""
    )
    private val DATE = Regex("""on\s+\d{1,2}/\d{1,2}/\d{2,4}\s+at\s+\d{1,2}:\d{2}\s?[AaPp][Mm]""")
    private val PHONE = Regex("""(?:\+?254|0)\d{9}""")

    private val CODE = Regex("""\*\d+(\*\d+)*#""")
    const val TOKEN_PREFIX = "BN"
    private const val SEP = "\$"

    /**
     * [senderPhone] is the full number (254...). Safaricom now hides some middle digits of the sender's number:
     * then [masked] is true, [senderPhone] is empty and [prefix]/[suffix] hold the visible digits (254 form).
     */
    data class Payment(
        val txCode: String,
        val amount: String,
        val senderPhone: String,
        val name: String = "",
        val masked: Boolean = false,
        val prefix: String = "",
        val suffix: String = ""
    )

    // A phone number with some digits replaced by * (or x, a dot, a bullet).
    private val MASKED = Regex("""\+?[0-9][0-9*xX\u2022.]{6,}""")

    private fun cleanName(raw: String): String =
        raw.trim().trimStart('-', '\u2013', '\u2014', ' ').trimEnd('.', ' ').replace(Regex("\\s+"), " ").take(60)

    /** Returns the payment in the SMS, or null if the text isn't an M-Pesa receipt. */
    fun parsePayment(raw: String): Payment? {
        // Some phones put non-breaking spaces or invisible characters in SMS text. Java's \s misses them.
        val text = raw.replace('\u00A0', ' ').replace("\u200B", "").replace("\uFEFF", "")
        val head = HEAD.find(text) ?: return null
        if (DATE.find(text) == null) return null
        val found = AMOUNT.find(text) ?: AMOUNT_PERSONAL.find(text) ?: return null
        val who = found.groupValues[2].trim()
        val amt = normaliseAmount(found.groupValues[1])
        val tx = head.groupValues[1]

        val full = PHONE.find(who)
        if (full != null) {
            val name = cleanName(who.substring(0, full.range.first) + " " + who.substring(full.range.last + 1))
            return Payment(tx, amt, normalisePhone(full.value), name)
        }
        // No full number: maybe the middle digits are hidden (0740***731).
        val m = MASKED.find(who) ?: return null
        val token = m.value
        if (!token.any { it == '*' || it == 'x' || it == 'X' || it == '\u2022' || it == '.' }) return null
        val digits = token.removePrefix("+")
        val lead = digits.takeWhile { it.isDigit() }
        val trail = digits.takeLastWhile { it.isDigit() }
        val prefix = if (lead.startsWith("0")) "254" + lead.substring(1) else lead
        if (prefix.length + trail.length < 4 || prefix.length + trail.length >= 12) return null
        val name = cleanName(who.substring(0, m.range.first) + " " + who.substring(m.range.last + 1))
        return Payment(tx, amt, "", name, true, prefix, trail)
    }

    /** True when a 254... number fits the digits that were visible in a masked receipt. */
    fun matchesMask(phone254: String, prefix: String, suffix: String): Boolean =
        phone254.length == 12 && phone254.startsWith(prefix) && phone254.endsWith(suffix)

    /** The reply from 334 after the customer agrees to share their number. */
    data class Approval(val txCode: String, val approved: Boolean, val phone: String, val name: String)

    private val APPROVAL_TX = Regex("""Transaction\s+ID\s+([A-Z0-9]{10})""", RegexOption.IGNORE_CASE)

    /** Reads "...Transaction ID UCP75AJSX1 has been approved. Here are the details: 0712345678 - JANE WANJIKU KAMAU." */
    fun parseApproval(raw: String): Approval? {
        val t = raw.replace('\u00A0', ' ')
        val tx = APPROVAL_TX.find(t) ?: return null
        val txCode = tx.groupValues[1].uppercase()
        if (!t.contains("approved", ignoreCase = true)) return Approval(txCode, false, "", "")
        val tail = t.substring(tx.range.last + 1)
        val ph = PHONE.find(tail) ?: return Approval(txCode, false, "", "")
        return Approval(txCode, true, ph.value, cleanName(tail.substring(ph.range.last + 1)))
    }

    /** True for the M-Pesa sender name however the phone shows it: MPESA, M-PESA, Mpesa. */
    fun isMpesaSender(sender: String): Boolean =
        sender.filter { it.isLetter() }.uppercase() == "MPESA"

    /** "1,500.00" -> "1500", "19.00" -> "19". */
    fun normaliseAmount(raw: String): String =
        BigDecimal(raw.replace(",", "")).stripTrailingZeros().toPlainString()

    /** "0712345678" or "+254712345678" -> "254712345678". */
    fun normalisePhone(raw: String): String {
        val digits = raw.replace("+", "")
        return if (digits.startsWith("0")) "254" + digits.substring(1) else digits
    }

    /** "254712345678" -> "0712345678". */
    fun localPhone(senderPhone: String): String =
        if (senderPhone.startsWith("254") && senderPhone.length > 3) "0" + senderPhone.substring(3) else senderPhone

    // ---- offers ---------------------------------------------------------------------------------

    const val CAT_DATA = "data"
    const val CAT_MINUTES = "minutes"
    const val CAT_SMS = "sms"
    val CATEGORIES = listOf(CAT_DATA, CAT_MINUTES, CAT_SMS)

    fun categoryTitle(cat: String): String = when (cat) {
        CAT_DATA -> "DATA BUNDLES"
        CAT_MINUTES -> "MINUTES"
        CAT_SMS -> "SMS"
        else -> cat.uppercase()
    }

    /**
     * [amount] is what the customer pays: "20", or a range "22-23" (any payment from 22 to 23 counts).
     * [template] is the USSD code with "n" where the customer's number goes. Blank means "no code yet".
     */
    data class Offer(
        val id: String,
        val category: String,
        val amount: String,
        val label: String,
        val template: String,
        val enabled: Boolean,
        val from: String = "",
        val to: String = ""
    ) {
        /** True when the offer only sells between two times of the day (24-hour clock, e.g. 06:00 to 23:00). */
        val scheduled: Boolean get() = from.isNotBlank() && to.isNotBlank()
    }

    /** "06:00" -> minutes since midnight, or null if it isn't a time. */
    fun parseClock(s: String): Int? {
        val m = Regex("""^(\d{1,2}):(\d{2})$""").find(s.trim()) ?: return null
        val h = m.groupValues[1].toInt()
        val mi = m.groupValues[2].toInt()
        return if (h > 23 || mi > 59) null else h * 60 + mi
    }

    /** True when [nowMin] (minutes since midnight) is inside the window. A window may cross midnight (22:00 to 02:00). */
    fun inWindow(from: String, to: String, nowMin: Int): Boolean {
        val a = parseClock(from) ?: return true
        val b = parseClock(to) ?: return true
        return when {
            a == b -> true
            a < b -> nowMin >= a && nowMin < b
            else -> nowMin >= a || nowMin < b
        }
    }

    private fun windowsOverlap(x: Offer, y: Offer): Boolean {
        if (!x.scheduled || !y.scheduled) return true
        for (minute in 0 until 1440) {
            if (inWindow(x.from, x.to, minute) && inWindow(y.from, y.to, minute)) return true
        }
        return false
    }

    /** Starting offers. The minutes have no codes because the codes depend on your account: add them yourself. */
    fun defaultOffers(): List<Offer> = listOf(
        Offer("d19", CAT_DATA, "19", "1GB 1 hour", "*180*5*2*n*1*1#", true),
        Offer("d20", CAT_DATA, "20", "Bingwa 250 MBs 24hrs", "*180*5*2*n*2*1#", true),
        Offer("d48", CAT_DATA, "48", "Bingwa 750 MBs 24hrs", "*180*5*2*n*3*1#", true),
        Offer("d49", CAT_DATA, "49", "Bingwa 1.5GB 3hrs", "*180*5*2*n*7*1#", true),
        Offer("d51", CAT_DATA, "51", "400 MBs 7 days", "*180*5*2*n*4*1#", true),
        Offer("d55", CAT_DATA, "55", "Bingwa 1GB till midnight", "*180*5*2*n*6*1#", true),
        Offer("d99", CAT_DATA, "99", "1.5GB 24hrs", "*180*5*2*n*5*1#", true),
        Offer("m51", CAT_MINUTES, "51", "50 Mins till midnight", "", false),
        Offer("m22", CAT_MINUTES, "22-23", "45 Mins 3hrs", "", false),
        Offer("m95", CAT_MINUTES, "95-102", "100 Mins", "", false),
        Offer("m235", CAT_MINUTES, "235", "250 Mins 7 days", "", false)
    )

    private val ID_RANDOM = SecureRandom()
    fun newOfferId(): String = "o" + java.lang.Long.toString(System.currentTimeMillis(), 36) + ID_RANDOM.nextInt(1000)

    /** Fills a stored offer code for one customer. "n" (or "@") = 07 form, "+" = 254 form. */
    fun render(template: String, senderPhone: String): String {
        val local = localPhone(senderPhone)
        return template.replace("n", local).replace("N", local).replace("@", local).replace("+", senderPhone)
    }

    fun isValidCode(code: String): Boolean = CODE.matches(code)

    /**
     * The steps to dial. Offers are written the usual way, *188*5*2*N*1*1#, and the phone dials that one step at a time:
     * *188# first, then it types 5, 2, the customer's number, 1, 1. Short codes such as *130*N# are dialed in one go.
     * (A code written as "*188# > 5 > 2" is still understood.)
     */
    fun steps(template: String, phone254: String): List<String> {
        val t = template.trim()
        if (t.contains(">")) return t.split(">").map { render(it.trim(), phone254) }.filter { it.isNotEmpty() }
        val rendered = render(t, phone254)
        if (!CODE.matches(rendered)) return listOf(rendered)
        val parts = rendered.removePrefix("*").removeSuffix("#").split("*")
        if (parts.size < 3) return listOf(rendered)
        return listOf("*" + parts[0] + "#") + parts.drop(1)
    }

    fun joinSteps(list: List<String>): String = list.joinToString(" > ")

    /** Template check against a sample number, so a bad code can't be stored. */
    fun isValidTemplate(template: String): Boolean {
        val t = template.trim()
        if (t.isEmpty()) return false
        if (!t.contains(">")) return isValidCode(render(t, "254700000000"))
        val sample = steps(t, "254700000000")
        if (sample.size < 2 || !isValidCode(sample[0])) return false
        return sample.drop(1).all { Regex("""\d{1,15}""").matches(it) }
    }

    class Range(val lo: BigDecimal, val hi: BigDecimal)

    /** "20" -> 20..20, "22-23" -> 22..23. Null when it isn't a sensible amount. */
    fun parseAmountSpec(spec: String): Range? {
        val s = spec.trim().replace('\u2013', '-').replace('\u2014', '-').replace(" ", "")
        if (s.isEmpty()) return null
        val bits = s.split("-")
        return try {
            when (bits.size) {
                1 -> {
                    val v = BigDecimal(bits[0])
                    if (v.signum() <= 0) null else Range(v, v)
                }
                2 -> {
                    val a = BigDecimal(bits[0])
                    val b = BigDecimal(bits[1])
                    if (a.signum() <= 0 || b.compareTo(a) < 0) null else Range(a, b)
                }
                else -> null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun sameRange(a: Range, b: Range): Boolean = a.lo.compareTo(b.lo) == 0 && a.hi.compareTo(b.hi) == 0
    private fun inside(inner: Range, outer: Range): Boolean =
        outer.lo.compareTo(inner.lo) <= 0 && inner.hi.compareTo(outer.hi) <= 0
    private fun touches(a: Range, b: Range): Boolean = a.lo.compareTo(b.hi) <= 0 && b.lo.compareTo(a.hi) <= 0

    /**
     * Two ON offers may overlap only when one sits fully inside the other (99 inside 95-102): then the
     * narrower one wins. Anything else is ambiguous, so it is refused.
     */
    private fun conflicts(a: Range, b: Range): Boolean {
        if (!touches(a, b)) return false
        if (sameRange(a, b)) return true
        return !(inside(a, b) || inside(b, a))
    }

    /** Returns a plain-words problem, or null if the offer can be saved. [all] is every offer including this one's old copy. */
    fun validateOffer(offer: Offer, all: List<Offer>): String? {
        if (offer.category !in CATEGORIES) return "Pick Data, Minutes or SMS."
        if (offer.label.isBlank()) return "Write what the customer gets, e.g. 250 MBs 24hrs."
        val range = parseAmountSpec(offer.amount)
            ?: return "Amount must be a number like 20, or a range like 22-23."
        if (offer.template.isNotBlank() && !isValidTemplate(offer.template)) {
            return "The code must look like *180*5*2*n*1*1# (digits, * and #, with N for the customer's number)."
        }
        val f = offer.from.isNotBlank()
        val t = offer.to.isNotBlank()
        if (f != t || (f && (parseClock(offer.from) == null || parseClock(offer.to) == null))) {
            return "Times must look like 06:00. Fill both boxes, or leave both empty."
        }
        if (offer.enabled) {
            if (offer.template.isBlank()) return "Add the code before turning this offer on."
            for (other in all) {
                if (other.id == offer.id || !other.enabled) continue
                val r = parseAmountSpec(other.amount) ?: continue
                if (conflicts(range, r) && windowsOverlap(offer, other)) {
                    return "Ksh ${offer.amount} clashes with \"${other.label}\" (Ksh ${other.amount}). Turn that one off or change the amount."
                }
            }
        }
        return null
    }

    /** The ON offer that matches this payment. The most exact amount wins (20 beats 15-25). */
    fun pickOffer(offers: List<Offer>, amount: String, nowMin: Int = -1): Offer? {
        val v = try { BigDecimal(amount) } catch (e: Exception) { return null }
        var best: Offer? = null
        var bestWidth: BigDecimal? = null
        for (o in offers) {
            if (!o.enabled || o.template.isBlank()) continue
            if (nowMin >= 0 && o.scheduled && !inWindow(o.from, o.to, nowMin)) continue
            val r = parseAmountSpec(o.amount) ?: continue
            if (v.compareTo(r.lo) < 0 || v.compareTo(r.hi) > 0) continue
            val width = r.hi.subtract(r.lo)
            val current = bestWidth
            if (current == null || width.compareTo(current) < 0) {
                best = o
                bestWidth = width
            }
        }
        return best
    }

    // ---- credits -------------------------------------------------------------------------------

    private const val ID_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"

    /** 10 characters, no I O 0 1. The admin page checks the same alphabet. */
    fun newDeviceId(): String {
        val sb = StringBuilder()
        for (i in 0 until 10) sb.append(ID_ALPHABET[ID_RANDOM.nextInt(ID_ALPHABET.length)])
        return sb.toString()
    }

    /** "7K2F9QXA3B" -> "7K2F9-QXA3B" */
    fun showDeviceId(id: String): String = if (id.length == 10) id.substring(0, 5) + "-" + id.substring(5) else id

    data class Voucher(val serial: String, val credits: Long)
    data class VoucherResult(val voucher: Voucher?, val error: String?)

    /**
     * Token format: BN$V$serial$credits$deviceId$signature (signature = ECDSA P-256 / SHA-256, base64url).
     * Checks the signature with [publicKey] (base64 of the X.509 key) and that the token is for this phone.
     */
    fun checkVoucher(text: String, publicKey: String, deviceId: String): VoucherResult {
        val parts = text.trim().split(SEP)
        if (parts.size != 6 || parts[0] != TOKEN_PREFIX || parts[1] != "V") {
            return VoucherResult(null, "That is not a credit token. Copy the whole message.")
        }
        val key = parseKey(publicKey)
            ?: return VoucherResult(null, "This app has no signing key inside it yet. Put the public key from your admin page into the app and rebuild it.")
        val body = parts.subList(0, 5).joinToString(SEP)
        if (!signatureValid(body, parts[5], key)) {
            return VoucherResult(null, "That token is not valid. It may have been changed when copied, or it was made for a different app key.")
        }
        if (parts[4].uppercase() != deviceId.uppercase()) {
            return VoucherResult(null, "That token is for a different phone.")
        }
        val credits = parts[3].toLongOrNull()
        if (credits == null || credits <= 0L) return VoucherResult(null, "That token has no credits in it.")
        return VoucherResult(Voucher(parts[2], credits), null)
    }

    private fun parseKey(b64: String): PublicKey? = try {
        val clean = b64.filter { !it.isWhitespace() }
        if (clean.length < 80) null
        else KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(clean)))
    } catch (e: Exception) {
        null
    }

    private fun signatureValid(body: String, sigB64Url: String, key: PublicKey): Boolean = try {
        val raw = Base64.getUrlDecoder().decode(sigB64Url)
        if (raw.size != 64) {
            false
        } else {
            val sig = Signature.getInstance("SHA256withECDSA")
            sig.initVerify(key)
            sig.update(body.toByteArray(Charsets.UTF_8))
            sig.verify(p1363ToDer(raw))
        }
    } catch (e: Exception) {
        false
    }

    /** The browser gives the signature as r||s (64 bytes). Java wants it as a DER sequence. */
    internal fun p1363ToDer(raw: ByteArray): ByteArray {
        val r = derInt(raw.copyOfRange(0, 32))
        val s = derInt(raw.copyOfRange(32, 64))
        return byteArrayOf(0x30, (r.size + s.size).toByte()) + r + s
    }

    private fun derInt(b: ByteArray): ByteArray {
        var i = 0
        while (i < b.size - 1 && b[i].toInt() == 0) i++
        var v = b.copyOfRange(i, b.size)
        if (v[0].toInt() < 0) v = byteArrayOf(0) + v
        return byteArrayOf(0x02, v.size.toByte()) + v
    }

    /** Credits are Ksh for Ksh: a sale of Ksh N costs N credits. Returns the new balance, or null if not enough. */
    fun chargeSale(balance: Long, saleAmount: Long): Long? {
        if (saleAmount <= 0L || balance < saleAmount) return null
        return balance - saleAmount
    }

    /** "19" -> 19. Returns null for anything that isn't a whole, positive shilling amount. */
    fun wholeShillings(amount: String): Long? = try {
        val d = BigDecimal(amount).stripTrailingZeros()
        if (d.scale() > 0 || d.signum() <= 0) null else d.longValueExact()
    } catch (e: Exception) {
        null
    }
}
