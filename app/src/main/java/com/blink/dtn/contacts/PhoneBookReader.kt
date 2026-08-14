package com.blink.dtn.contacts

import android.content.Context
import android.provider.ContactsContract
import com.blink.dtn.net.PhoneContactsMatcher

/**
 * Reads the device address book in memory only. Never written to Room or uploaded raw.
 */
object PhoneBookReader {

    fun load(context: Context): List<PhoneContactsMatcher.DeviceContact> {
        val resolver = context.contentResolver
        val grouped = LinkedHashMap<Long, MutableContact>()
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )
        resolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            projection,
            null,
            null,
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} COLLATE LOCALIZED ASC"
        )?.use { cursor ->
            val idIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
            val nameIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val numIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            if (idIdx < 0 || nameIdx < 0 || numIdx < 0) return emptyList()
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idIdx)
                val name = cursor.getString(nameIdx).orEmpty()
                val number = cursor.getString(numIdx).orEmpty()
                val row = grouped.getOrPut(id) { MutableContact(id, name) }
                if (row.displayName.isBlank() && name.isNotBlank()) row.displayName = name
                if (number.isNotBlank()) row.numbers += number
            }
        }
        return grouped.values.map {
            PhoneContactsMatcher.DeviceContact(
                contactId = it.id,
                displayName = it.displayName,
                rawNumbers = it.numbers.distinct()
            )
        }
    }

    private class MutableContact(
        val id: Long,
        var displayName: String,
        val numbers: MutableList<String> = mutableListOf()
    )
}
