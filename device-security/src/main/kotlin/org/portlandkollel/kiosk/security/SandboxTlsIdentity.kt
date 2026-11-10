package org.portlandkollel.kiosk.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import java.net.Socket
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Principal
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import javax.net.ssl.SSLEngine
import javax.net.ssl.X509ExtendedKeyManager
import javax.security.auth.x500.X500Principal

/** Non-exportable client identity. Existing key policies are immutable: never replace one here. */
class SandboxTlsIdentity(private val alias: String = "sandbox-device") {
    class IncompatibleExistingKey : Exception("Existing device TLS identity requires reviewed repair")
    private fun store() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    fun ensureCreated() {
        if (store().containsAlias(alias)) return
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").apply {
            initialize(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                // Conscrypt signs the TLS prehashed digest with NONEwithECDSA. SHA256 alone rejects it.
                .setDigests(KeyProperties.DIGEST_NONE, KeyProperties.DIGEST_SHA256)
                .setCertificateSubject(X500Principal("CN=Kollel Sandbox Device")).build())
        }.generateKeyPair()
    }

    fun certificate() = store().getCertificate(alias) as X509Certificate
    private fun privateKey() = store().getKey(alias, null) as PrivateKey

    fun requireTlsCompatible() {
        val info = KeyFactory.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
            .getKeySpec(privateKey(), KeyInfo::class.java)
        if (!info.digests.contains(KeyProperties.DIGEST_NONE)) throw IncompatibleExistingKey()
    }

    fun keyManager(): X509ExtendedKeyManager {
        requireTlsCompatible()
        // Do not allow the TLS provider to pick a Square SDK or unrelated Keystore alias.
        return object : X509ExtendedKeyManager() {
            private fun selected(types: Array<out String>?, issuers: Array<out Principal>?): String? =
                if (types?.contains("EC") == true &&
                    (issuers.isNullOrEmpty() || issuers.contains(certificate().issuerX500Principal))) alias else null
            override fun chooseClientAlias(types: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?) = selected(types, issuers)
            override fun chooseEngineClientAlias(types: Array<out String>?, issuers: Array<out Principal>?, engine: SSLEngine?) = selected(types, issuers)
            override fun getClientAliases(type: String?, issuers: Array<out Principal>?) = selected(arrayOf(type ?: ""), issuers)?.let { arrayOf(it) }
            override fun getCertificateChain(requested: String?) = if (requested == alias) arrayOf(certificate()) else null
            override fun getPrivateKey(requested: String?) = if (requested == alias) privateKey() else null
            override fun getServerAliases(type: String?, issuers: Array<out Principal>?): Array<String>? = null
            override fun chooseServerAlias(type: String?, issuers: Array<out Principal>?, socket: Socket?): String? = null
        }
    }
}
