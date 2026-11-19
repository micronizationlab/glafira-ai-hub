package com.micronizationlab.glafira

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.KeyStore
import java.time.Instant
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Project-scoped encrypted contact dossier storage.
 *
 * Data is encrypted with a dedicated Android Keystore AES-GCM key. The project ID is
 * authenticated as AAD and is also checked inside the encrypted document. A failed
 * decrypt or schema check is surfaced as an error; corrupted data is never treated as
 * an empty contact list and therefore cannot be silently overwritten.
 *
 * This store is local-only. It does not read external accounts or send information.
 */
internal object HrContactStore {
    private const val KEY_ALIAS = "glafira_hr_contacts_aes"
    private const val ENVELOPE_VERSION = 1
    private const val DOCUMENT_VERSION = 1
    private const val IV_LENGTH = 12
    private const val MAX_FILE_BYTES = 5 * 1024 * 1024
    private const val DIRECTORY = "hr_contacts"
    private val lock = Any()

    fun list(context: Context, projectId: String): List<HrContactCard> = synchronized(lock) {
        require(projectId.isNotBlank())
        read(context, projectId)
    }

    fun upsert(context: Context, projectId: String, contact: HrContactCard) = synchronized(lock) {
        require(projectId.isNotBlank())
        val current = read(context, projectId)
        val updated = current.filterNot { it.contactId == contact.contactId } + contact
        write(context, projectId, updated)
    }

    fun delete(context: Context, projectId: String, contactId: String): Boolean = synchronized(lock) {
        require(projectId.isNotBlank() && contactId.isNotBlank())
        val current = read(context, projectId)
        val updated = current.filterNot { it.contactId == contactId }
        if (updated.size == current.size) return@synchronized false
        write(context, projectId, updated)
        true
    }

    private fun read(context: Context, projectId: String): List<HrContactCard> {
        val file = fileFor(context, projectId)
        if (!file.exists()) return emptyList()
        try {
            require(file.length() in 1..MAX_FILE_BYTES.toLong()) { "HR dossier file size is invalid." }
            val envelope = JSONObject(file.readText(Charsets.UTF_8))
            require(envelope.getInt("version") == ENVELOPE_VERSION) {
                "Unsupported HR dossier envelope version."
            }
            val plaintext = decrypt(envelope.getString("data"), associatedData(projectId))
            val document = JSONObject(plaintext)
            require(document.getInt("schemaVersion") == DOCUMENT_VERSION) {
                "Unsupported HR dossier schema version."
            }
            require(document.getString("projectId") == projectId) {
                "HR dossier project scope mismatch."
            }
            val array = document.getJSONArray("contacts")
            return buildList {
                for (index in 0 until array.length()) add(array.getJSONObject(index).toContact())
            }
        } catch (error: Exception) {
            throw IllegalStateException(
                "Не удалось проверить зашифрованную базу контактов. Данные не изменены.",
                error
            )
        }
    }

    private fun write(context: Context, projectId: String, contacts: List<HrContactCard>) {
        require(contacts.map { it.contactId }.distinct().size == contacts.size) {
            "Duplicate HR contact IDs are not allowed."
        }
        val document = JSONObject().apply {
            put("schemaVersion", DOCUMENT_VERSION)
            put("projectId", projectId)
            put("contacts", JSONArray().apply { contacts.forEach { put(it.toJson()) } })
        }
        val envelope = JSONObject().apply {
            put("version", ENVELOPE_VERSION)
            put("data", encrypt(document.toString(), associatedData(projectId)))
        }
        val bytes = envelope.toString().toByteArray(Charsets.UTF_8)
        require(bytes.size in 1..MAX_FILE_BYTES) { "HR dossier exceeds the local storage limit." }

        val file = fileFor(context, projectId)
        val parent = file.parentFile ?: error("HR dossier directory is unavailable.")
        check(parent.exists() || parent.mkdirs()) { "Could not create HR dossier directory." }
        val temp = File(parent, file.name + ".tmp")
        try {
            FileOutputStream(temp).use { stream ->
                stream.write(bytes)
                stream.fd.sync()
            }
            try {
                Files.move(
                    temp.toPath(), file.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            if (temp.exists()) temp.delete()
        }
    }

    private fun fileFor(context: Context, projectId: String): File {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(projectId.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return File(File(context.filesDir, DIRECTORY), "$digest.json.enc")
    }

    private fun associatedData(projectId: String) = "GLAFIRA_HR_CONTACTS_V1|$projectId"

    private fun encrypt(plaintext: String, aad: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        cipher.updateAAD(aad.toByteArray(Charsets.UTF_8))
        val payload = cipher.iv + cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(payload, Base64.NO_WRAP)
    }

    private fun decrypt(encoded: String, aad: String): String {
        val payload = Base64.decode(encoded, Base64.NO_WRAP)
        require(payload.size > IV_LENGTH) { "Encrypted HR dossier payload is incomplete." }
        val iv = payload.copyOfRange(0, IV_LENGTH)
        val ciphertext = payload.copyOfRange(IV_LENGTH, payload.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
        cipher.updateAAD(aad.toByteArray(Charsets.UTF_8))
        return String(cipher.doFinal(ciphertext), Charsets.UTF_8)
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = keyStore.getKey(KEY_ALIAS, null) as? SecretKey
        if (existing != null) return existing

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    private fun HrContactCard.toJson() = JSONObject().apply {
        put("contactId", contactId)
        put("displayName", displayName)
        put("aliases", JSONArray().apply { aliases.sorted().forEach { put(it) } })
        put("organization", organization ?: JSONObject.NULL)
        put("role", role ?: JSONObject.NULL)
        put("relationship", relationship?.name ?: JSONObject.NULL)
        put("identityStatus", identityStatus.name)
        put("facts", JSONArray().apply { facts.forEach { put(it.toJson()) } })
        put("interactions", JSONArray().apply { interactions.forEach { put(it.toJson()) } })
        put("commitments", JSONArray().apply { commitments.forEach { put(it.toJson()) } })
        put("lastVerifiedAt", lastVerifiedAt?.toString() ?: JSONObject.NULL)
    }

    private fun HrContactFact.toJson() = JSONObject().apply {
        put("category", category.name)
        put("value", value)
        put("confidence", confidence.name)
        put("sensitivity", sensitivity.name)
        put("sources", JSONArray().apply { sources.forEach { put(it.toJson()) } })
        put("isDirectlyStated", isDirectlyStated)
    }

    private fun HrInteraction.toJson() = JSONObject().apply {
        put("interactionId", interactionId)
        put("occurredAt", occurredAt.toString())
        put("channel", channel)
        put("summary", summary)
        put("sources", JSONArray().apply { sources.forEach { put(it.toJson()) } })
    }

    private fun HrCommitment.toJson() = JSONObject().apply {
        put("commitmentId", commitmentId)
        put("description", description)
        put("dueAt", dueAt?.toString() ?: JSONObject.NULL)
        put("status", status.name)
        put("sources", JSONArray().apply { sources.forEach { put(it.toJson()) } })
    }

    private fun HrSourceReference.toJson() = JSONObject().apply {
        put("sourceType", sourceType)
        put("sourceId", sourceId)
        put("recordId", recordId)
        put("observedAt", observedAt.toString())
        put("eventAt", eventAt?.toString() ?: JSONObject.NULL)
    }

    private fun JSONObject.toContact() = HrContactCard(
        contactId = getString("contactId"),
        displayName = getString("displayName"),
        aliases = getJSONArray("aliases").toStringSet(),
        organization = nullableString("organization"),
        role = nullableString("role"),
        relationship = nullableString("relationship")?.let { enumValueOf<HrRelationshipType>(it) },
        identityStatus = enumValueOf(getString("identityStatus")),
        facts = getJSONArray("facts").toList { it.toFact() },
        interactions = getJSONArray("interactions").toList { it.toInteraction() },
        commitments = getJSONArray("commitments").toList { it.toCommitment() },
        lastVerifiedAt = nullableString("lastVerifiedAt")?.let(Instant::parse)
    )

    private fun JSONObject.toFact() = HrContactFact(
        category = enumValueOf(getString("category")),
        value = getString("value"),
        confidence = enumValueOf(getString("confidence")),
        sensitivity = enumValueOf(getString("sensitivity")),
        sources = getJSONArray("sources").toList { it.toSource() },
        isDirectlyStated = getBoolean("isDirectlyStated")
    )

    private fun JSONObject.toInteraction() = HrInteraction(
        interactionId = getString("interactionId"),
        occurredAt = Instant.parse(getString("occurredAt")),
        channel = getString("channel"),
        summary = getString("summary"),
        sources = getJSONArray("sources").toList { it.toSource() }
    )

    private fun JSONObject.toCommitment() = HrCommitment(
        commitmentId = getString("commitmentId"),
        description = getString("description"),
        dueAt = nullableString("dueAt")?.let(Instant::parse),
        status = enumValueOf(getString("status")),
        sources = getJSONArray("sources").toList { it.toSource() }
    )

    private fun JSONObject.toSource() = HrSourceReference(
        sourceType = getString("sourceType"),
        sourceId = getString("sourceId"),
        recordId = getString("recordId"),
        observedAt = Instant.parse(getString("observedAt")),
        eventAt = nullableString("eventAt")?.let(Instant::parse)
    )

    private fun JSONObject.nullableString(key: String): String? =
        if (isNull(key)) null else getString(key)

    private fun JSONArray.toStringSet(): Set<String> =
        buildSet { for (index in 0 until length()) add(getString(index)) }

    private inline fun <T> JSONArray.toList(transform: (JSONObject) -> T): List<T> =
        buildList { for (index in 0 until length()) add(transform(getJSONObject(index))) }
}
