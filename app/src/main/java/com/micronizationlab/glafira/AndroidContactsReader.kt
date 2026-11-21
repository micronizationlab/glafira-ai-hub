package com.micronizationlab.glafira

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.core.content.ContextCompat

/**
 * Read-only adapter for the Android system contacts provider.
 *
 * Permission prompting is deliberately owned by the UI/Permission Broker. This adapter
 * never requests permissions, writes contacts, reads notes/photos, or sends data elsewhere.
 * Call from a worker thread; the caller decides what to import and when to disclose it.
 */
class AndroidContactsReader(
    private val context: Context,
    private val maxContacts: Int = DEFAULT_MAX_CONTACTS
) {
    init {
        require(maxContacts in 1..MAX_ALLOWED_CONTACTS)
    }

    fun readContacts(): AndroidContactsReadResult {
        when (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS)) {
            PackageManager.PERMISSION_GRANTED -> Unit
            PackageManager.PERMISSION_DENIED -> return AndroidContactsReadResult.PermissionRequired
            else -> return AndroidContactsReadResult.PermissionRequired
        }

        return try {
            val contacts = readBaseContacts()
            val contactIds = contacts.records.map { it.id }
            val phones = readValues(
                contactIds,
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            )
            val emails = readValues(
                contactIds,
                ContactsContract.CommonDataKinds.Email.CONTENT_URI,
                ContactsContract.CommonDataKinds.Email.CONTACT_ID,
                ContactsContract.CommonDataKinds.Email.ADDRESS
            )

            AndroidContactsReadResult.Success(
                contacts = contacts.records.map { contact ->
                    DeviceContactRecord(
                        sourceContactId = contact.id,
                        displayName = contact.name,
                        phoneNumbers = phones[contact.id].orEmpty().distinct(),
                        emailAddresses = emails[contact.id].orEmpty().distinct()
                    )
                },
                truncated = contacts.truncated
            )
        } catch (_: SecurityException) {
            AndroidContactsReadResult.PermissionRequired
        } catch (error: Exception) {
            AndroidContactsReadResult.Unavailable(error.javaClass.simpleName)
        }
    }

    private fun readBaseContacts(): BaseContacts {
        val projection = arrayOf(
            ContactsContract.Contacts._ID,
            ContactsContract.Contacts.DISPLAY_NAME_PRIMARY
        )
        val records = mutableListOf<BaseContact>()
        var truncated = false
        context.contentResolver.query(
            ContactsContract.Contacts.CONTENT_URI,
            projection,
            null,
            null,
            ContactsContract.Contacts.DISPLAY_NAME_PRIMARY + " COLLATE LOCALIZED ASC"
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(ContactsContract.Contacts._ID)
            val nameColumn = cursor.getColumnIndexOrThrow(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY)
            while (cursor.moveToNext()) {
                if (records.size == maxContacts) {
                    truncated = true
                    break
                }
                val id = cursor.getString(idColumn)?.takeIf { it.isNotBlank() } ?: continue
                val name = cursor.getString(nameColumn)?.trim().orEmpty()
                records += BaseContact(id, name)
            }
        } ?: throw IllegalStateException("contacts_query_unavailable")
        return BaseContacts(records, truncated)
    }

    private fun readValues(
        contactIds: List<String>,
        uri: android.net.Uri,
        contactIdColumn: String,
        valueColumn: String
    ): Map<String, List<String>> {
        val values = linkedMapOf<String, MutableList<String>>()
        // Query only the bounded contact page; keep below SQLite's bind-parameter limit.
        contactIds.chunked(400).forEach { chunk ->
            if (chunk.isEmpty()) return@forEach
            val placeholders = chunk.joinToString(",") { "?" }
            context.contentResolver.query(
                uri,
                arrayOf(contactIdColumn, valueColumn),
                "$contactIdColumn IN ($placeholders)",
                chunk.toTypedArray(),
                null
            )?.use { cursor ->
                val idIndex = cursor.getColumnIndexOrThrow(contactIdColumn)
                val valueIndex = cursor.getColumnIndexOrThrow(valueColumn)
                while (cursor.moveToNext()) {
                    val id = cursor.getString(idIndex) ?: continue
                    val value = cursor.getString(valueIndex)?.trim().orEmpty()
                    if (value.isNotEmpty()) values.getOrPut(id) { mutableListOf() }.add(value)
                }
            } ?: throw IllegalStateException("contact_data_query_unavailable")
        }
        return values
    }

    private data class BaseContact(val id: String, val name: String)
    private data class BaseContacts(val records: List<BaseContact>, val truncated: Boolean)

    companion object {
        const val DEFAULT_MAX_CONTACTS = 5_000
        const val MAX_ALLOWED_CONTACTS = 20_000
    }
}

data class DeviceContactRecord(
    /** Opaque ID local to this Android contacts database; never treat it as a global identity. */
    val sourceContactId: String,
    val displayName: String,
    val phoneNumbers: List<String>,
    val emailAddresses: List<String>,
    val source: String = "android_contacts"
)

sealed interface AndroidContactsReadResult {
    data object PermissionRequired : AndroidContactsReadResult
    data class Success(
        val contacts: List<DeviceContactRecord>,
        val truncated: Boolean
    ) : AndroidContactsReadResult
    data class Unavailable(val safeReasonCode: String) : AndroidContactsReadResult
}
