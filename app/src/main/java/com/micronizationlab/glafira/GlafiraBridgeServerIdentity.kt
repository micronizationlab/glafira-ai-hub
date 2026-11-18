package com.micronizationlab.glafira

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Date
import javax.net.ssl.KeyManager
import javax.net.ssl.SSLContext
import javax.net.ssl.X509KeyManager
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.ExtendedKeyUsage
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.asn1.x509.KeyPurposeId
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.security.Principal

/**
 * Owner-created, self-signed TLS server identity. The EC private key never leaves Android
 * Keystore; only the public certificate is persisted in app-private preferences.
 *
 * The returned SSLContext is for a LAN server socket. Clients must still verify the exact
 * DNS/IP SAN and the SHA-256 certificate pin delivered through owner-approved pairing.
 */
class GlafiraBridgeServerIdentity private constructor(
    val certificate: X509Certificate,
    private val privateKey: PrivateKey
) {
    val sha256Fingerprint: String
        get() = java.security.MessageDigest.getInstance("SHA-256")
            .digest(certificate.encoded)
            .joinToString("") { "%02x".format(it) }

    fun serverSslContext(): SSLContext {
        val keyManager = object : X509KeyManager {
            override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = null
            override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: java.net.Socket?): String? = null
            override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?): Array<String> =
                if (keyType == null || keyType.equals("EC", true) || keyType.equals("ECDSA", true)) arrayOf(KEY_ALIAS) else emptyArray()
            override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: java.net.Socket?): String? =
                if (getServerAliases(keyType, issuers).isNotEmpty()) KEY_ALIAS else null
            override fun getCertificateChain(alias: String?): Array<X509Certificate>? =
                if (alias == KEY_ALIAS) arrayOf(certificate) else null
            override fun getPrivateKey(alias: String?): PrivateKey? =
                if (alias == KEY_ALIAS) privateKey else null
        }
        return SSLContext.getInstance("TLS").apply {
            init(arrayOf<KeyManager>(keyManager), null, SecureRandom())
        }
    }

    companion object {
        private const val KEY_ALIAS = "glafira.bridge.server.identity.v1"
        private const val PREFS = "glafira_bridge_server_identity"
        private const val CERT_KEY = "leaf_certificate_der"

        /**
         * Creates a fresh owner-controlled identity for one explicit DNS name or IP literal.
         * Calling this again rotates the identity; callers must not do so silently while clients
         * are paired. Use create only from an explicit owner action.
         */
        fun create(context: Context, sanHost: String, isIpAddress: Boolean): GlafiraBridgeServerIdentity {
            require(sanHost.isNotBlank() && sanHost.length <= 253 && sanHost.none { it.isWhitespace() || it.isISOControl() })
            if (isIpAddress) {
                require(sanHost.all { it in "0123456789.:abcdefABCDEF" }) { "IP SAN must be a literal address." }
            } else {
                require(sanHost.matches(Regex("(?i)(?=.{1,253}$)([a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)(\\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)*"))) {
                    "DNS SAN is invalid."
                }
            }

            val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            if (keyStore.containsAlias(KEY_ALIAS)) keyStore.deleteEntry(KEY_ALIAS)

            val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
            generator.initialize(
                KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_SIGN)
                    .setAlgorithmParameterSpec(java.security.spec.ECGenParameterSpec("secp256r1"))
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .setCertificateSubject(javax.security.auth.x500.X500Principal("CN=GLAFIRA Bridge"))
                    .setCertificateSerialNumber(BigInteger(159, SecureRandom()).abs().add(BigInteger.ONE))
                    .setCertificateNotBefore(Date(System.currentTimeMillis() - 60_000L))
                    .setCertificateNotAfter(Date(System.currentTimeMillis() + CERTIFICATE_LIFETIME_MS))
                    .build()
            )
            val pair = generator.generateKeyPair()
            val now = System.currentTimeMillis()
            val subject = X500Name("CN=GLAFIRA Bridge")
            val builder = JcaX509v3CertificateBuilder(
                subject,
                BigInteger(159, SecureRandom()).abs().add(BigInteger.ONE),
                Date(now - 60_000L),
                Date(now + CERTIFICATE_LIFETIME_MS),
                subject,
                pair.public
            )
            val san = GeneralName(if (isIpAddress) GeneralName.iPAddress else GeneralName.dNSName, sanHost)
            builder.addExtension(Extension.subjectAlternativeName, false, GeneralNames(san))
            builder.addExtension(Extension.extendedKeyUsage, false, ExtendedKeyUsage(KeyPurposeId.id_kp_serverAuth))
            builder.addExtension(Extension.keyUsage, true, KeyUsage(KeyUsage.digitalSignature))
            builder.addExtension(Extension.basicConstraints, true, BasicConstraints(false))

            val signer = JcaContentSignerBuilder("SHA256withECDSA").build(pair.private)
            val certificate = JcaX509CertificateConverter().getCertificate(builder.build(signer))
            certificate.verify(pair.public)
            certificate.checkValidity(Date(now))
            require(certificate.subjectAlternativeNames?.any { entry ->
                entry.size >= 2 && entry[0] == (if (isIpAddress) GeneralName.iPAddress else GeneralName.dNSName) &&
                    entry[1].toString().equals(sanHost, ignoreCase = !isIpAddress)
            } == true) { "Generated TLS certificate SAN does not match the selected host." }
            require(KeyPurposeId.id_kp_serverAuth.id in (certificate.extendedKeyUsage ?: emptyList())) {
                "Generated TLS certificate is missing serverAuth EKU."
            }

            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(CERT_KEY, Base64.encodeToString(certificate.encoded, Base64.NO_WRAP))
                .apply()
            return GlafiraBridgeServerIdentity(certificate, pair.private)
        }

        /** Reopens the current identity without exporting or regenerating its Keystore key. */
        fun load(context: Context): GlafiraBridgeServerIdentity? {
            val encoded = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(CERT_KEY, null)
                ?: return null
            return runCatching {
                val certificate = CertificateFactory.getInstance("X.509").generateCertificate(
                    Base64.decode(encoded, Base64.NO_WRAP).inputStream()
                ) as X509Certificate
                certificate.checkValidity()
                val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                val privateKey = keyStore.getKey(KEY_ALIAS, null) as? PrivateKey
                    ?: return null
                require(certificate.publicKey.algorithm.equals("EC", true))
                GlafiraBridgeServerIdentity(certificate, privateKey)
            }.getOrNull()
        }

        private const val CERTIFICATE_LIFETIME_MS = 90L * 24 * 60 * 60 * 1000
    }
}
