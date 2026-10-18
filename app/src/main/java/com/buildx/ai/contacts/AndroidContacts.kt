package com.buildx.ai.contacts

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import android.provider.ContactsContract
import com.buildx.ai.call.ContactSource

/** Reads the phone's contacts (name, numbers, nickname field). Cached for a minute. */
class AndroidContacts(private val ctx: Context) : ContactSource {

    companion object {
        private var cache: List<ContactEntry>? = null
        private var cachedAt = 0L
        private const val CACHE_MS = 60_000L

        fun invalidate() {
            cache = null
        }
    }

    override fun all(): List<ContactEntry>? {
        if (ctx.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return null
        val now = SystemClock.elapsedRealtime()
        cache?.let { if (now - cachedAt < CACHE_MS) return it }
        val list = try {
            load()
        } catch (e: Exception) {
            return null
        }
        cache = list
        cachedAt = now
        return list
    }

    private fun label(type: Int): String = when (type) {
        ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE,
        ContactsContract.CommonDataKinds.Phone.TYPE_WORK_MOBILE -> "mobile"
        ContactsContract.CommonDataKinds.Phone.TYPE_HOME -> "home"
        ContactsContract.CommonDataKinds.Phone.TYPE_WORK -> "work"
        else -> "other"
    }

    private fun load(): List<ContactEntry> {
        val nicks = HashMap<String, String>()
        ctx.contentResolver.query(
            ContactsContract.Data.CONTENT_URI,
            arrayOf(ContactsContract.Data.CONTACT_ID, ContactsContract.CommonDataKinds.Nickname.NAME),
            "${ContactsContract.Data.MIMETYPE} = ?",
            arrayOf(ContactsContract.CommonDataKinds.Nickname.CONTENT_ITEM_TYPE),
            null
        )?.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0) ?: continue
                val n = c.getString(1) ?: continue
                if (n.isNotBlank()) nicks[id] = n
            }
        }

        val names = LinkedHashMap<String, String>()
        val numbers = LinkedHashMap<String, MutableList<PhoneEntry>>()
        ctx.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER,
                ContactsContract.CommonDataKinds.Phone.TYPE
            ),
            null,
            null,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME
        )?.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0) ?: continue
                val name = c.getString(1) ?: continue
                val num = c.getString(2) ?: continue
                names[id] = name
                numbers.getOrPut(id) { ArrayList() }.add(PhoneEntry(num, label(c.getInt(3))))
            }
        }
        return names.map { (id, name) ->
            // mobile numbers first, so the first one is the best guess
            val sorted = numbers[id].orEmpty().sortedBy { if (it.label == "mobile") 0 else 1 }
            ContactEntry(id, name, sorted, nicks[id].orEmpty())
        }
    }
}
