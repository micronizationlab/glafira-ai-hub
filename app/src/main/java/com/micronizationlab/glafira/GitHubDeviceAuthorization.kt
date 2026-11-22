package com.micronizationlab.glafira

import android.content.Context
import android.net.Uri
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal data class GitHubDeviceCode(
    val deviceCode: String,
    val userCode: String,
    val verificationUri: String,
    val expiresAtMillis: Long,
    val intervalSeconds: Int
)

internal sealed interface GitHubDevicePollResult {
    data object Pending : GitHubDevicePollResult
    data class SlowDown(val intervalSeconds: Int) : GitHubDevicePollResult
    data class Authorized(
        val accessToken: String,
        val refreshToken: String?,
        val expiresInSeconds: Long?,
        val refreshTokenExpiresInSeconds: Long?
    ) : GitHubDevicePollResult
    data object Denied : GitHubDevicePollResult
    data object Expired : GitHubDevicePollResult
}

internal class GitHubDeviceAuthorization(
    private val clientId: String = BuildConfig.GITHUB_APP_CLIENT_ID
) {
    suspend fun requestDeviceCode(): GitHubDeviceCode = withContext(Dispatchers.IO) {
        require(clientId.isNotBlank()) { "GitHub App Client ID is not configured." }
        val response = postForm(
            DEVICE_CODE_URL,
            mapOf("client_id" to clientId, "scope" to "offline_access")
        )
        val json = JSONObject(response)
        val verificationUri = json.optString("verification_uri")
        require(verificationUri == VERIFICATION_URI) { "GitHub returned an unexpected verification address." }
        val deviceCode = json.optString("device_code")
        val userCode = json.optString("user_code")
        require(deviceCode.isNotBlank() && userCode.isNotBlank()) { "GitHub returned an incomplete device authorization response." }
        GitHubDeviceCode(
            deviceCode = deviceCode,
            userCode = userCode,
            verificationUri = verificationUri,
            expiresAtMillis = System.currentTimeMillis() + json.optLong("expires_in", 0L) * 1000L,
            intervalSeconds = json.optInt("interval", 5).coerceAtLeast(1)
        ).also {
            require(it.expiresAtMillis > System.currentTimeMillis()) { "GitHub device authorization has already expired." }
        }
    }

    suspend fun poll(deviceCode: GitHubDeviceCode): GitHubDevicePollResult = withContext(Dispatchers.IO) {
        val response = postForm(
            ACCESS_TOKEN_URL,
            mapOf(
                "client_id" to clientId,
                "device_code" to deviceCode.deviceCode,
                "grant_type" to DEVICE_GRANT_TYPE
            )
        )
        val json = JSONObject(response)
        when (json.optString("error")) {
            "authorization_pending" -> GitHubDevicePollResult.Pending
            "slow_down" -> GitHubDevicePollResult.SlowDown((deviceCode.intervalSeconds + 5).coerceAtMost(60))
            "access_denied" -> GitHubDevicePollResult.Denied
            "expired_token" -> GitHubDevicePollResult.Expired
            else -> {
                val accessToken = json.optString("access_token")
                require(accessToken.isNotBlank()) { "GitHub authorization response did not contain an access token." }
                GitHubDevicePollResult.Authorized(
                    accessToken = accessToken,
                    refreshToken = json.optString("refresh_token").takeIf(String::isNotBlank),
                    expiresInSeconds = json.optLong("expires_in").takeIf { it > 0L },
                    refreshTokenExpiresInSeconds = json.optLong("refresh_token_expires_in").takeIf { it > 0L }
                )
            }
        }
    }

    suspend fun refresh(refreshToken: String): GitHubDevicePollResult.Authorized = withContext(Dispatchers.IO) {
        require(refreshToken.isNotBlank()) { "GitHub refresh token is missing." }
        val json = JSONObject(
            postForm(
                ACCESS_TOKEN_URL,
                mapOf(
                    "client_id" to clientId,
                    "grant_type" to "refresh_token",
                    "refresh_token" to refreshToken
                )
            )
        )
        val accessToken = json.optString("access_token")
        require(accessToken.isNotBlank()) {
            "GitHub could not refresh authorization: " + json.optString("error", "unknown_error")
        }
        GitHubDevicePollResult.Authorized(
            accessToken = accessToken,
            refreshToken = json.optString("refresh_token").takeIf(String::isNotBlank),
            expiresInSeconds = json.optLong("expires_in").takeIf { it > 0L },
            refreshTokenExpiresInSeconds = json.optLong("refresh_token_expires_in").takeIf { it > 0L }
        )
    }
    private fun postForm(endpoint: String, values: Map<String, String>): String {
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 15_000
            doOutput = true
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            setRequestProperty("User-Agent", "GLAFIRA-AI-HUB-Android")
        }
        return try {
            val body = values.entries.joinToString("&") { (key, value) ->
                "${formEncode(key)}=${formEncode(value)}"
            }
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val stream = if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            require(connection.responseCode in 200..299) {
                "GitHub authorization request failed (HTTP ${connection.responseCode})."
            }
            response
        } finally {
            connection.disconnect()
        }
    }

    private fun formEncode(value: String): String =
        URLEncoder.encode(value, Charsets.UTF_8.name())

    private companion object {
        const val DEVICE_CODE_URL = "https://github.com/login/device/code"
        const val ACCESS_TOKEN_URL = "https://github.com/login/oauth/access_token"
        const val VERIFICATION_URI = "https://github.com/login/device"
        const val DEVICE_GRANT_TYPE = "urn:ietf:params:oauth:grant-type:device_code"
    }
}

/**
 * GitHub credentials are isolated from AI-provider and Bridge credentials.
 * Values are encrypted with a dedicated non-exportable Android Keystore key.
 */
internal class GitHubTokenStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )

    @Synchronized
    fun save(
        accessToken: String,
        refreshToken: String?,
        expiresInSeconds: Long?,
        refreshTokenExpiresInSeconds: Long?
    ): Boolean {
        if (accessToken.isBlank()) return false
        return runCatching {
            val editor = preferences.edit()
                .putString(ACCESS_TOKEN, encrypt(accessToken))
                .putLong(ACCESS_EXPIRES_AT, expiresInSeconds?.let { System.currentTimeMillis() + it * 1000L } ?: 0L)
            if (refreshToken.isNullOrBlank()) {
                editor.remove(REFRESH_TOKEN).remove(REFRESH_EXPIRES_AT)
            } else {
                editor.putString(REFRESH_TOKEN, encrypt(refreshToken))
                    .putLong(REFRESH_EXPIRES_AT, refreshTokenExpiresInSeconds?.let { System.currentTimeMillis() + it * 1000L } ?: 0L)
            }
            editor.commit()
        }.getOrDefault(false)
    }

    @Synchronized
    fun loadAccessToken(): String? {
        val expiresAt = preferences.getLong(ACCESS_EXPIRES_AT, 0L)
        if (expiresAt > 0L && expiresAt <= System.currentTimeMillis()) return null
        return decryptStored(ACCESS_TOKEN)
    }

    @Synchronized
    fun loadRefreshToken(): String? {
        val expiresAt = preferences.getLong(REFRESH_EXPIRES_AT, 0L)
        if (expiresAt > 0L && expiresAt <= System.currentTimeMillis()) return null
        return decryptStored(REFRESH_TOKEN)
    }

    /** Returns the remaining refresh-token lifetime, or null when no expiry is recorded. */
    @Synchronized
    fun loadRefreshTokenExpiresInSeconds(): Long? {
        val expiresAt = preferences.getLong(REFRESH_EXPIRES_AT, 0L)
        if (expiresAt <= 0L) return null
        val remainingMillis = expiresAt - System.currentTimeMillis()
        if (remainingMillis <= 0L) return null
        return (remainingMillis + 999L) / 1000L
    }

    fun hasAccessToken(): Boolean {
        val expiresAt = preferences.getLong(ACCESS_EXPIRES_AT, 0L)
        return !preferences.getString(ACCESS_TOKEN, null).isNullOrBlank() &&
            (expiresAt == 0L || expiresAt > System.currentTimeMillis())
    }

    @Synchronized
    fun clear(): Boolean {
        val committed = preferences.edit().clear().commit()
        if (committed) {
            runCatching {
                val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
                if (keyStore.containsAlias(KEY_ALIAS)) keyStore.deleteEntry(KEY_ALIAS)
            }
        }
        return committed
    }

    private fun decryptStored(name: String): String? {
        val encoded = preferences.getString(name, null) ?: return null
        return runCatching {
            val payload = Base64.decode(encoded, Base64.NO_WRAP)
            require(payload.size > IV_BYTES)
            val iv = payload.copyOfRange(0, IV_BYTES)
            val ciphertext = payload.copyOfRange(IV_BYTES, payload.size)
            try {
                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(TAG_BITS, iv))
                String(cipher.doFinal(ciphertext), Charsets.UTF_8)
            } finally {
                iv.fill(0)
                ciphertext.fill(0)
                payload.fill(0)
            }
        }.getOrNull()
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        return try {
            Base64.encodeToString(encrypted, Base64.NO_WRAP)
        } finally {
            encrypted.fill(0)
        }
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE)
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

    companion object {
        private const val ANDROID_KEY_STORE = "AndroidKeyStore"
        private const val PREFS_NAME = "glafira_github_auth"
        private const val KEY_ALIAS = "glafira_github_oauth_aes"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_BYTES = 12
        private const val TAG_BITS = 128
        private const val ACCESS_TOKEN = "access_token"
        private const val REFRESH_TOKEN = "refresh_token"
        private const val ACCESS_EXPIRES_AT = "access_expires_at"
        private const val REFRESH_EXPIRES_AT = "refresh_expires_at"
    }
}

/** Refreshes short-lived GitHub access tokens without exposing credentials to callers. */
internal class GitHubSession(context: Context) {
    private val store = GitHubTokenStore(context.applicationContext)
    private val authorization = GitHubDeviceAuthorization()

    suspend fun accessTokenOrRefresh(): String? {
        store.loadAccessToken()?.let { return it }
        val refreshToken = store.loadRefreshToken() ?: return null
        val refreshed = authorization.refresh(refreshToken)
        // GitHub may omit refresh_token when it does not rotate the credential.
        // In that case retain the existing token and its remaining lifetime.
        val nextRefreshToken = refreshed.refreshToken ?: refreshToken
        val nextRefreshTokenLifetime = refreshed.refreshTokenExpiresInSeconds
            ?: store.loadRefreshTokenExpiresInSeconds()
        val saved = store.save(
            accessToken = refreshed.accessToken,
            refreshToken = nextRefreshToken,
            expiresInSeconds = refreshed.expiresInSeconds,
            refreshTokenExpiresInSeconds = nextRefreshTokenLifetime
        )
        return if (saved) store.loadAccessToken() else null
    }
}
