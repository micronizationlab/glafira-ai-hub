package com.micronizationlab.glafira

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.math.BigInteger
import java.net.InetAddress
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.util.Date
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.KeyManager
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.ExtendedKeyUsage
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.asn1.x509.KeyPurposeId
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter

/**
 * Creates and persists an owner-device TLS identity.
 *
 * The EC private key is generated and retained by Android Keystore. Only the public
 * X.509 certificate and its fingerprint are persisted in app-private preferences.
 * This class does not start a listener or expose a network endpoint.
 */
class AndroidKeystoreBridgeServerIdentityStore(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    @Synchronized
    fun getOrCreate(host: BridgeServerHost): BridgeServerIdentity {
        val keyStore = loadKeyStore()
        val storedHost = preferences.getString(KEY_HOST, null)
        val storedCertificate = preferences.getString(KEY_CERTIFICATE, null)
        if (storedHost == host.value && storedCertificate != null &&
            keyStore.containsAlias(KEY_ALIAS)
        ) {
            val certificate = decodeCertificate(storedCertificate)
            if (isValidIdentity(certificate, host)) {
                installCertificateChain(keyStore, certificate)
                return buildIdentity(keyStore, certificate, host)
            }
        }

        if (keyStore.containsAlias(KEY_ALIAS)) keyStore.deleteEntry(KEY_ALIAS)
        preferences.edit().remove(KEY_HOST).remove(KEY_CERTIFICATE).commit()

        val generator = KeyPairGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_EC,
            ANDROID_KEY_STORE
        )
        generator.initialize(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
            )
                .setAlgorithmParameterSpec(ECGenParameterSpec(EC_CURVE))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .setUserAuthenticationRequired(false)
                .build()
        )
        val keyPair = generator.generateKeyPair()
        val certificate = issueCertificate(keyPair.public, keyPair.private, host)
        check(isValidIdentity(certificate, host)) {
            "Generated Bridge TLS certificate failed SAN/EKU validation."
        }
        installCertificateChain(keyStore, certificate)

        val committed = preferences.edit()
            .putString(KEY_HOST, host.value)
            .putString(KEY_CERTIFICATE, Base64.encodeToString(certificate.encoded, Base64.NO_WRAP))
            .commit()
        if (!committed) {
            keyStore.deleteEntry(KEY_ALIAS)
            throw IllegalStateException("Unable to persist Bridge TLS certificate metadata.")
        }
        return buildIdentity(keyStore, certificate, host)
    }

    @Synchronized
    fun currentConnectionDetails(port: Int = GlafiraBridgeTlsHttpServer.DEFAULT_PORT): BridgeServerConnectionDetails? {
        if (port !in 1024..65_535) return null
        val hostValue = preferences.getString(KEY_HOST, null) ?: return null
        val encoded = preferences.getString(KEY_CERTIFICATE, null) ?: return null
        return runCatching {
            val host = BridgeServerHost.ipv4(hostValue)
            val certificate = decodeCertificate(encoded)
            require(isValidIdentity(certificate, host))
            val fingerprint = MessageDigest.getInstance("SHA-256")
                .digest(certificate.encoded)
                .joinToString("") { "%02x".format(it) }
            BridgeServerConnectionDetails("https://${host.value}:$port", fingerprint)
        }.getOrNull()
    }

    private fun buildIdentity(
        keyStore: KeyStore,
        certificate: X509Certificate,
        host: BridgeServerHost
    ): BridgeServerIdentity {
        val keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).run {
            init(keyStore, null)
            keyManagers
        }
        val privateKey = keyStore.getKey(KEY_ALIAS, null)
            ?: throw IllegalStateException("Bridge TLS private key is unavailable.")
        val fingerprint = MessageDigest.getInstance("SHA-256")
            .digest(certificate.encoded)
            .joinToString("") { "%02x".format(it) }
        return BridgeServerIdentity(
            host = host.value,
            certificate = certificate,
            certificateSha256 = fingerprint,
            keyManagers = keyManagers,
            privateKeyAlgorithm = privateKey.algorithm
        )
    }

    private fun issueCertificate(
        publicKey: java.security.PublicKey,
        privateKey: java.security.PrivateKey,
        host: BridgeServerHost
    ): X509Certificate {
        val subject = X500Name("CN=${host.value}")
        val now = System.currentTimeMillis()
        val builder = JcaX509v3CertificateBuilder(
            subject,
            BigInteger(159, SecureRandom()).abs().add(BigInteger.ONE),
            Date(now - NOT_BEFORE_SKEW_MS),
            Date(now + CERTIFICATE_LIFETIME_MS),
            subject,
            publicKey
        )
        val san = if (host.kind == BridgeServerHostKind.IP_ADDRESS) {
            GeneralName(GeneralName.iPAddress, host.value)
        } else {
            GeneralName(GeneralName.dNSName, host.value)
        }
        builder.addExtension(Extension.subjectAlternativeName, false, GeneralNames(san))
        builder.addExtension(Extension.basicConstraints, true, BasicConstraints(false))
        builder.addExtension(Extension.keyUsage, true, KeyUsage(KeyUsage.digitalSignature))
        builder.addExtension(
            Extension.extendedKeyUsage,
            false,
            ExtendedKeyUsage(KeyPurposeId.id_kp_serverAuth)
        )
        // Let JCA select the Android Keystore-compatible Signature provider for this
        // non-exportable key. Android registers its Keystore workaround provider ahead
        // of Bouncy Castle specifically to avoid BC claiming unsupported Keystore keys.
        val signer = JcaContentSignerBuilder(SIGNATURE_ALGORITHM)
            .build(privateKey)
        return JcaX509CertificateConverter()
            .setProvider(BouncyCastleProvider())
            .getCertificate(builder.build(signer))
    }

    private fun installCertificateChain(keyStore: KeyStore, certificate: X509Certificate) {
        val privateKey = keyStore.getKey(KEY_ALIAS, null)
            ?: throw IllegalStateException("Bridge TLS private key is unavailable.")
        keyStore.setKeyEntry(KEY_ALIAS, privateKey, null, arrayOf(certificate))
    }

    private fun decodeCertificate(value: String): X509Certificate {
        val bytes = Base64.decode(value, Base64.NO_WRAP)
        return java.security.cert.CertificateFactory.getInstance("X.509")
            .generateCertificate(bytes.inputStream()) as X509Certificate
    }

    private fun isValidIdentity(certificate: X509Certificate, host: BridgeServerHost): Boolean =
        runCatching {
            certificate.checkValidity()
            require(certificate.publicKey.algorithm.equals("EC", ignoreCase = true))
            require(certificate.basicConstraints == -1)
            require(certificate.keyUsage?.getOrNull(0) == true)
            require(certificate.extendedKeyUsage?.contains(SERVER_AUTH_OID) == true)
            require(certificate.subjectAlternativeNames.orEmpty().any { entry ->
                val type = entry.getOrNull(0) as? Int
                val value = entry.getOrNull(1)?.toString()
                when (host.kind) {
                    BridgeServerHostKind.DNS_NAME -> type == 2 && value.equals(host.value, true)
                    BridgeServerHostKind.IP_ADDRESS -> type == 7 &&
                        runCatching { InetAddress.getByName(value).address.contentEquals(InetAddress.getByName(host.value).address) }.getOrDefault(false)
                }
            })
            true
        }.getOrDefault(false)

    private fun loadKeyStore(): KeyStore =
        KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }

    companion object {
        private const val ANDROID_KEY_STORE = "AndroidKeyStore"
        private const val PREFS_NAME = "glafira_bridge_server_identity"
        private const val KEY_ALIAS = "glafira.bridge.server.identity"
        private const val KEY_HOST = "host"
        private const val KEY_CERTIFICATE = "certificate_der"
        private const val EC_CURVE = "secp256r1"
        private const val SIGNATURE_ALGORITHM = "SHA256withECDSA"
        private const val SERVER_AUTH_OID = "1.3.6.1.5.5.7.3.1"
        private const val NOT_BEFORE_SKEW_MS = 60_000L
        private const val CERTIFICATE_LIFETIME_MS = 365L * 24 * 60 * 60 * 1000
    }
}

enum class BridgeServerHostKind { DNS_NAME, IP_ADDRESS }

data class BridgeServerHost private constructor(
    val value: String,
    val kind: BridgeServerHostKind
) {
    companion object {
        fun dnsLocal(value: String): BridgeServerHost {
            require(value.length in 9..253 && value.endsWith(".local", true))
            require(value.split('.').all { label ->
                label.isNotEmpty() && label.length <= 63 &&
                    label.first().isLetterOrDigit() && label.last().isLetterOrDigit() &&
                    label.all { it.isLetterOrDigit() || it == '-' }
            }) { "Bridge DNS identity must be a valid .local hostname." }
            return BridgeServerHost(value.lowercase(), BridgeServerHostKind.DNS_NAME)
        }

        fun ipv4(value: String): BridgeServerHost {
            val parts = value.split('.')
            require(parts.size == 4 && parts.all { part ->
                part.isNotEmpty() && part.length <= 3 && part.all(Char::isDigit) &&
                    part.toIntOrNull()?.let { it in 0..255 } == true
            }) { "Bridge IP identity must be a valid IPv4 address." }
            return BridgeServerHost(value, BridgeServerHostKind.IP_ADDRESS)
        }
    }
}

data class BridgeServerIdentity internal constructor(
    val host: String,
    val certificate: X509Certificate,
    val certificateSha256: String,
    internal val keyManagers: Array<KeyManager>,
    internal val privateKeyAlgorithm: String
)


data class BridgeServerConnectionDetails(
    val origin: String,
    val certificateSha256: String
)
