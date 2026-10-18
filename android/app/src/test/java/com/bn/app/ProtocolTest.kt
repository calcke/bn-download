package com.bn.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolTest {
    // Made by the real admin page code (site/admin/core.js): key 1 signed the token for device 7K2F9-QXA3B, 500 credits.
    private val pubKey = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEp5cehkObyg27nneYQZQEwFWoptPqLhsdZR6GL1+laPwoi4MPTfQG4kWf3rdXZa+G5mt4TIsSeP2Yu2Pu1fSnZA=="
    private val otherKey = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEAjwn5tsUCmNH/t0v+DVneghI3ZgWUJ5MUAVEjuI2jclYQhsRiM2MP0lgINoQtbXBFmTfmkfUWwiS8OganLUX4w=="
    private val token = "BN\$V\$A1B2C3D4\$500\$7K2F9QXA3B\$BN6mYYK1Ucv8h3rdNka76SBhvkCqG9MXaTjjbZnTkvmKc-gjKhEUMo4_PBFWCkNF9FPRiELHAcYAJrmBeWKlCA"
    private val device = "7K2F9QXA3B"

    // A real receipt layout: Ksh1 from 254716279430.
    private val oneShilling =
        "UJFMDAJ8VC Confirmed.on 15/10/26 at 11:01 PMKSH1.00 received from 254716279430 PETER KARANJA. " +
            "New Account balance is KSH113.26."

    @Test
    fun ksh1_receipt_is_read() {
        val p = Protocol.parsePayment(oneShilling)
        assertNotNull(p)
        assertEquals("1", p!!.amount)
        assertEquals("254716279430", p.senderPhone)
    }

    @Test
    fun ksh1_test_offer_dials_please_call_me_to_the_sender() {
        assertEquals("*130*0716279430#", Protocol.render("*130*n#", "254716279430"))
        assertTrue(Protocol.isValidTemplate("*130*n#"))
    }

    @Test
    fun default_data_offers_fill_the_customers_number() {
        val d20 = Protocol.pickOffer(Protocol.defaultOffers(), "20")
        assertNotNull(d20)
        assertEquals("Bingwa 250 MBs 24hrs", d20!!.label)
        assertEquals("*180*5*2*0716279430*2*1#", Protocol.render(d20.template, "254716279430"))
    }

    @Test
    fun till_receipt_with_no_space_before_amount_is_read() {
        val sms = "UJFMDAJ8VC Confirmed.on 15/10/26 at 11:01 PMKSH23.00 received from 254716279430 PETER KARANJA. " +
            "New Account balance is KSH113.26. Transaction cost, KSH0.00.  Receiving payments of Ksh 500 & below " +
            "in your Lipa na M-PESA till is now FREE!"
        val p = Protocol.parsePayment(sms)
        assertNotNull(p)
        assertEquals("23", p!!.amount)
        assertEquals("254716279430", p.senderPhone)
        assertEquals("UJFMDAJ8VC", p.txCode)
    }

    @Test
    fun valid_token_is_accepted_for_this_phone() {
        val r = Protocol.checkVoucher(token, pubKey, device)
        assertNotNull(r.voucher)
        assertEquals(500L, r.voucher!!.credits)
        assertEquals("A1B2C3D4", r.voucher!!.serial)
    }

    @Test
    fun token_for_another_phone_is_refused() {
        assertNull(Protocol.checkVoucher(token, pubKey, "AAAAAAAAAA").voucher)
    }

    @Test
    fun token_signed_by_another_key_is_refused() {
        assertNull(Protocol.checkVoucher(token, otherKey, device).voucher)
    }

    @Test
    fun token_with_changed_credits_is_refused() {
        val forged = token.replace("\$500\$", "\$900000\$")
        assertNull(Protocol.checkVoucher(forged, pubKey, device).voucher)
    }

    @Test
    fun app_without_a_public_key_refuses_every_token() {
        assertNull(Protocol.checkVoucher(token, "PASTE-THE-PUBLIC-KEY-FROM-THE-ADMIN-PAGE-HERE", device).voucher)
    }

    @Test
    fun amount_ranges_and_exact_amounts_pick_the_right_offer() {
        val offers = listOf(
            Protocol.Offer("a", Protocol.CAT_DATA, "99", "1.5GB 24hrs", "*1*n#", true),
            Protocol.Offer("b", Protocol.CAT_MINUTES, "95-102", "100 Mins", "*2*n#", true),
            Protocol.Offer("c", Protocol.CAT_MINUTES, "22-23", "45 Mins", "*3*n#", true),
            Protocol.Offer("d", Protocol.CAT_MINUTES, "50", "off one", "*4*n#", false)
        )
        assertEquals("a", Protocol.pickOffer(offers, "99")!!.id)   // exact beats the range
        assertEquals("b", Protocol.pickOffer(offers, "100")!!.id)
        assertEquals("c", Protocol.pickOffer(offers, "23")!!.id)
        assertNull(Protocol.pickOffer(offers, "24"))
        assertNull(Protocol.pickOffer(offers, "50"))               // switched off
    }

    @Test
    fun offer_checks_catch_clashes_and_bad_codes() {
        val all = Protocol.defaultOffers()
        val clash = all.first { it.id == "m51" }.copy(template = "*9*n#", enabled = true)
        assertNotNull(Protocol.validateOffer(clash, all))               // 51 is already a data offer
        val nested = all.first { it.id == "m95" }.copy(template = "*9*n#", enabled = true)
        assertNull(Protocol.validateOffer(nested, all))                 // 95-102 around 99 is fine
        val badCode = all.first { it.id == "d20" }.copy(template = "dial me")
        assertNotNull(Protocol.validateOffer(badCode, all))
        val noCodeOn = all.first { it.id == "m235" }.copy(enabled = true)
        assertNotNull(Protocol.validateOffer(noCodeOn, all))
        assertNull(Protocol.parseAmountSpec("abc"))
        assertNull(Protocol.parseAmountSpec("30-20"))
    }

    @Test
    fun device_id_has_ten_safe_characters() {
        val id = Protocol.newDeviceId()
        assertEquals(10, id.length)
        assertTrue(id.all { it in "ABCDEFGHJKLMNPQRSTUVWXYZ23456789" })
        assertEquals(11, Protocol.showDeviceId(id).length)
    }

    @Test
    fun mpesa_sender_name_is_matched_however_it_is_shown() {
        assertTrue(Protocol.isMpesaSender("MPESA"))
        assertTrue(Protocol.isMpesaSender("M-PESA"))
        assertTrue(Protocol.isMpesaSender("Mpesa"))
        assertFalse(Protocol.isMpesaSender("Safaricom"))
        assertFalse(Protocol.isMpesaSender("0712345678"))
    }

    @Test
    fun receipt_with_non_breaking_spaces_is_read() {
        val nb = "\u00A0"
        val sms = "UJFMDAJ8VC Confirmed.on 15/10/26 at 11:01${nb}PMKSH23.00${nb}received${nb}from 254716279430 PETER KARANJA. " +
            "New Account balance is KSH113.26."
        val p = Protocol.parsePayment(sms)
        assertNotNull(p)
        assertEquals("23", p!!.amount)
    }

    @Test
    fun message_that_is_not_a_receipt_is_ignored() {
        assertNull(Protocol.parsePayment("UJFMDAJ8VC Confirmed. Ksh50.00 sent to JOHN 0712345678 on 15/10/26 at 11:01 PM."))
    }
    @Test
    fun receipt_with_hidden_digits_keeps_the_visible_ones_and_the_name() {
        val sms = "UJGMNA1TU0 Confirmed.on 16/10/26 at 6:46 PMKSH20.00 received from 254712***678 JANE WANJIKU KAMAU. " +
            "New Account balance is KSH20.26. Transaction cost, KSH0.00."
        val p = Protocol.parsePayment(sms)
        assertNotNull(p)
        assertTrue(p!!.masked)
        assertEquals("254712", p.prefix)
        assertEquals("678", p.suffix)
        assertEquals("JANE WANJIKU KAMAU", p.name)
        assertEquals("20", p.amount)
        assertTrue(Protocol.matchesMask("254712345678", p.prefix, p.suffix))
        assertFalse(Protocol.matchesMask("254799345678", p.prefix, p.suffix))
    }

    @Test
    fun full_number_receipt_now_also_gives_the_name() {
        val sms = "UJGMNA1TU0 Confirmed.on 16/10/26 at 6:46 PMKSH1.00 received from 254797298877 Paul Mwangi Thuo. " +
            "New Account balance is KSH20.26. Transaction cost, KSH0.00."
        val p = Protocol.parsePayment(sms)
        assertNotNull(p)
        assertEquals("254797298877", p!!.senderPhone)
        assertEquals("Paul Mwangi Thuo", p.name)
    }

    @Test
    fun reply_from_334_gives_the_number_and_name() {
        val a = Protocol.parseApproval("Your request for M-PESA Transaction ID UCP75AJSX1 has been approved. " +
            "Here are the details: 0712345678 \u2013 JANE WANJIKU KAMAU.")
        assertNotNull(a)
        assertTrue(a!!.approved)
        assertEquals("UCP75AJSX1", a.txCode)
        assertEquals("0712345678", a.phone)
        assertEquals("JANE WANJIKU KAMAU", a.name)
        assertNull(Protocol.parseApproval("UJGMNA1TU0 Confirmed.on 16/10/26 at 6:46 PMKSH1.00 received from 254797298877 Paul."))
    }

    @Test
    fun codes_are_written_with_stars_and_dialed_one_step_at_a_time() {
        assertEquals(listOf("*188#", "5", "2", "0712345678", "1", "1"), Protocol.steps("*188*5*2*N*1*1#", "254712345678"))
        assertEquals(listOf("*180#", "5", "2", "0712345678", "2", "1"), Protocol.steps("*180*5*2*N*2*1#", "254712345678"))
        assertEquals(listOf("*130*0712345678#"), Protocol.steps("*130*N#", "254712345678"))
        assertEquals(listOf("*188#", "5", "0712345678"), Protocol.steps("*188*5*N#", "254712345678"))
        assertEquals(listOf("*188#", "5", "2", "0712345678", "1", "1"), Protocol.steps("*188# > 5 > 2 > N > 1 > 1", "254712345678"))
        assertTrue(Protocol.isValidTemplate("*188*5*2*N*1*1#"))
        assertFalse(Protocol.isValidTemplate("*188# > five"))
    }

    @Test
    fun offers_can_have_opening_times_even_across_midnight() {
        assertTrue(Protocol.inWindow("06:00", "23:00", 7 * 60))
        assertFalse(Protocol.inWindow("06:00", "23:00", 23 * 60 + 30))
        assertTrue(Protocol.inWindow("22:00", "02:00", 23 * 60))
        assertTrue(Protocol.inWindow("22:00", "02:00", 60))
        assertFalse(Protocol.inWindow("22:00", "02:00", 12 * 60))
        val day = Protocol.Offer("a", Protocol.CAT_DATA, "20", "Day", "*1*n#", true, "06:00", "18:00")
        val night = Protocol.Offer("b", Protocol.CAT_DATA, "20", "Night", "*2*n#", true, "18:00", "06:00")
        assertNull(Protocol.validateOffer(night, listOf(day, night)))        // same price, different hours: fine
        assertEquals("a", Protocol.pickOffer(listOf(day, night), "20", 9 * 60)!!.id)
        assertEquals("b", Protocol.pickOffer(listOf(day, night), "20", 20 * 60)!!.id)
        assertNotNull(Protocol.validateOffer(day.copy(to = ""), listOf(day)))  // one time box empty
    }

    @Test
    fun personal_number_receipt_with_hidden_digits_is_read() {
        val sms = "UJH0W9ZBVU Confirmed.You have received Ksh5.00 from Jonah  Thuo 0740***731 on 17/10/26 at 11:56 AM  " +
            "New M-PESA balance is Ksh9,051.36. Invest & earn daily interest with ZIIDI on https://saf.cx/cF6ir"
        val p = Protocol.parsePayment(sms)
        assertNotNull(p)
        assertTrue(p!!.masked)
        assertEquals("5", p.amount)
        assertEquals("254740", p.prefix)
        assertEquals("731", p.suffix)
        assertEquals("Jonah Thuo", p.name)
        assertEquals("UJH0W9ZBVU", p.txCode)
    }
}
