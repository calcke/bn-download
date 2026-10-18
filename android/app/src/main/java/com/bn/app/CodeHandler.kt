package com.bn.app

import android.content.Context

/**
 * Applies a credit token that was pasted into the app (or arrived by SMS). Checked against the public
 * key stored in assets/public_key.txt and against this phone's Device ID. No network involved.
 */
object CodeHandler {

    private val FIND = Regex("BN\\$[^\\s]+")

    private fun clean(raw: String): String =
        raw.replace("\u200B", "").replace('\u00A0', ' ').trim()

    /** The seller's public key, shipped inside the app. Empty if it was never filled in. */
    fun publicKey(ctx: Context): String = try {
        ctx.assets.open("public_key.txt").bufferedReader().use { it.readText() }.trim()
    } catch (e: Exception) {
        ""
    }

    /** Returns (worked, message to show the person). */
    fun handle(ctx: Context, raw: String): Pair<Boolean, String> {
        val text = FIND.find(clean(raw))?.value
            ?: return false to "No token found. Copy the whole message that starts with BN\$."
        val result = Protocol.checkVoucher(text, publicKey(ctx), Store.deviceId(ctx))
        val voucher = result.voucher
        if (voucher == null) {
            val why = result.error ?: "That token is not valid."
            Store.log(ctx, "Rejected a token: $why")
            return false to why
        }
        return if (Store.addCredits(ctx, voucher)) {
            true to "Added ${voucher.credits} credits. Balance ${Store.creditBalance(ctx)}."
        } else {
            false to "That token was already used."
        }
    }
}
