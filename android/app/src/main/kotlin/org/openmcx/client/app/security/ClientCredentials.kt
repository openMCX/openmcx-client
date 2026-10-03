package org.openmcx.client.app.security

import android.security.keystore.KeyProperties
import android.security.keystore.KeyProtection
import org.openmcx.client.security.Pem
import java.net.Socket
import java.security.KeyStore
import java.security.Principal
import java.security.PrivateKey
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509ExtendedKeyManager

/**
 * The UE's TLS client certificate. The private key lives in the Android Keystore:
 * imported once from the provisioning bundle, it cannot be read back out, and the
 * PEM file is deleted after import.
 */
object ClientCredentials {
    private const val ALIAS = "mcx-client"
    private fun keyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    val installed: Boolean get() = keyStore().containsAlias(ALIAS)

    /** Import the PKCS#8 key and its certificate chain into the Keystore. */
    fun import(keyPem: String, chainPem: String) {
        val key = Pem.privateKey(keyPem)
        val chain = Pem.certificates(chainPem)
        // TLS 1.3 signs with RSA-PSS, which Conscrypt pads itself and then applies as a raw
        // private-key operation: that needs the decrypt purpose with no padding.
        val protection = KeyProtection.Builder(KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_DECRYPT)
            .setDigests(KeyProperties.DIGEST_NONE, KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA384, KeyProperties.DIGEST_SHA512)
            .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1, KeyProperties.SIGNATURE_PADDING_RSA_PSS)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE, KeyProperties.ENCRYPTION_PADDING_RSA_PKCS1)
            .build()
        keyStore().setEntry(ALIAS, KeyStore.PrivateKeyEntry(key, chain.toTypedArray()), protection)
    }

    fun remove() = keyStore().deleteEntry(ALIAS)

    /**
     * Make a new EC P-256 key in the Keystore for activation (MCX Admin slice F2): it never
     * leaves the device; the Credential Service certifies it (installCertificate).
     */
    fun generate(): org.openmcx.client.provisioning.Activation.DeviceKey {
        val generator = java.security.KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
        generator.initialize(android.security.keystore.KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
            .setAlgorithmParameterSpec(java.security.spec.ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_NONE, KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA384, KeyProperties.DIGEST_SHA512)
            .build())
        val pair = generator.generateKeyPair()
        return object : org.openmcx.client.provisioning.Activation.DeviceKey {
            override val subjectPublicKeyInfo: ByteArray = pair.public.encoded
            override fun sign(data: ByteArray): ByteArray =
                java.security.Signature.getInstance("SHA256withECDSA").run { initSign(pair.private); update(data); sign() }
        }
    }

    /** Put the certificate the Credential Service issued beside the generated key. */
    fun installCertificate(certificatePem: String) {
        val store = keyStore()
        val key = store.getKey(ALIAS, null) as? PrivateKey ?: throw IllegalStateException("no generated key")
        store.setKeyEntry(ALIAS, key, null, Pem.certificates(certificatePem).toTypedArray())
    }

    /** A TLS context presenting the Keystore key and trusting only [caPem]. */
    fun sslContext(caPem: String): SSLContext {
        val store = keyStore()
        val key = store.getKey(ALIAS, null) as? PrivateKey ?: throw IllegalStateException("no client key installed")
        val chain = store.getCertificateChain(ALIAS).map { it as X509Certificate }.toTypedArray()
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            .apply { init(Pem.trustStore(Pem.certificates(caPem))) }
        return SSLContext.getInstance("TLS").apply { init(arrayOf(KeyManager(key, chain)), tmf.trustManagers, null) }
    }

    private class KeyManager(private val key: PrivateKey, private val chain: Array<X509Certificate>) : X509ExtendedKeyManager() {
        override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?) = ALIAS
        override fun chooseEngineClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, engine: SSLEngine?) = ALIAS
        override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?) = arrayOf(ALIAS)
        override fun getCertificateChain(alias: String?) = chain
        override fun getPrivateKey(alias: String?) = key
        override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?) = null
        override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?) = null
    }
}
