package com.bn.app

import android.Manifest
import android.content.ContentProviderOperation
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract

/**
 * Customers are kept in the phone's own contacts as BN_<name> (the name on the M-Pesa receipt).
 * When a receipt hides some digits of the sender's number, the matching BN_ contact gives the real number back.
 * The number always comes from YOUR contacts, never from the SMS, so a fake message cannot redirect a bundle.
 */
object Contacts {

    fun canRead(ctx: Context): Boolean =
        ctx.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    fun canWrite(ctx: Context): Boolean =
        ctx.checkSelfPermission(Manifest.permission.WRITE_CONTACTS) == PackageManager.PERMISSION_GRANTED

    private fun tidy(name: String): String = name.trim().replace(Regex("\\s+"), " ").uppercase()

    /** "0712 345 678", "+254712345678" and "254712345678" all become 254712345678. Null if it isn't a Kenyan mobile number. */
    fun canonical(raw: String): String? {
        val d = raw.filter { it.isDigit() }
        return when {
            d.length == 12 && d.startsWith("254") -> d
            d.length == 10 && d.startsWith("0") -> "254" + d.substring(1)
            d.length == 9 && (d.startsWith("7") || d.startsWith("1")) -> "254$d"
            else -> null
        }
    }

    /** Every number saved under BN_<name>. */
    private fun numbersFor(ctx: Context, name: String): Set<String> {
        val found = LinkedHashSet<String>()
        if (name.isBlank() || !canRead(ctx)) return found
        val wanted = "BN_" + tidy(name)
        try {
            val cursor = ctx.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER
                ),
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " LIKE ?",
                arrayOf("BN%"),
                null
            )
            if (cursor != null) {
                try {
                    while (cursor.moveToNext()) {
                        val n = cursor.getString(0) ?: continue
                        if (tidy(n) != wanted) continue
                        val num = canonical(cursor.getString(1) ?: "") ?: continue
                        found.add(num)
                    }
                } finally {
                    cursor.close()
                }
            }
        } catch (e: Exception) {
            // contacts unavailable: behave as if none were found
        }
        return found
    }

    /** The one BN_<name> number that fits the visible digits (254 form), or null if there is none or it is unclear. */
    fun find(ctx: Context, name: String, prefix: String, suffix: String): String? {
        val fits = numbersFor(ctx, name).filter { Protocol.matchesMask(it, prefix, suffix) }
        return if (fits.size == 1) fits[0] else null
    }

    /** Saves a new customer as BN_<name>. Does nothing if that number is already saved under that name. */
    fun saveIfNew(ctx: Context, name: String, phoneLocal: String) {
        if (name.isBlank() || !canRead(ctx) || !canWrite(ctx)) return
        val phone = canonical(phoneLocal) ?: return
        if (numbersFor(ctx, name).contains(phone)) return
        try {
            val display = "BN_" + tidy(name)
            val ops = ArrayList<ContentProviderOperation>()
            ops.add(
                ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
                    .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, null)
                    .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, null)
                    .build()
            )
            ops.add(
                ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                    .withValue(
                        ContactsContract.Data.MIMETYPE,
                        ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE
                    )
                    .withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, display)
                    .build()
            )
            ops.add(
                ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                    .withValue(
                        ContactsContract.Data.MIMETYPE,
                        ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE
                    )
                    .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, Protocol.localPhone(phone))
                    .withValue(
                        ContactsContract.CommonDataKinds.Phone.TYPE,
                        ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE
                    )
                    .build()
            )
            ctx.contentResolver.applyBatch(ContactsContract.AUTHORITY, ops)
            Store.log(ctx, "Saved $display to contacts")
        } catch (e: Exception) {
            Store.log(ctx, "Could not save the contact: ${e.javaClass.simpleName}")
        }
    }
}
