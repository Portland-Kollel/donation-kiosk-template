package org.portlandkollel.kiosk.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.UUID
import java.net.InetAddress
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import org.junit.Assert.*
import org.junit.Test

/** Executes against AndroidKeyStore/Conscrypt, not a desktop substitute. No keys are exported. */
class SandboxTlsIdentityTest {
    private fun withAlias(test: (String, KeyStore) -> Unit) {
        val alias = "kollel-regression-" + UUID.randomUUID()
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        try { test(alias, store) } finally { store.deleteEntry(alias) } // Test-only randomly named key.
    }

    @Test fun prehashedTlsSignatureWorksAndIdentitySurvivesRestart() = withAlias { alias, store ->
        val identity = SandboxTlsIdentity(alias)
        identity.ensureCreated()
        val certificate = identity.certificate()
        val manager = identity.keyManager()
        assertEquals(alias, manager.chooseClientAlias(arrayOf("EC"), null, null))
        assertEquals(alias, manager.chooseEngineClientAlias(arrayOf("EC"), arrayOf(certificate.issuerX500Principal), null))
        assertNull(manager.chooseClientAlias(arrayOf("RSA"), null, null))
        assertNull(manager.getPrivateKey("unrelated-alias"))
        val privateKey = manager.getPrivateKey(alias)!!
        assertNull(privateKey.encoded)
        val digest = MessageDigest.getInstance("SHA-256").digest("public TLS regression fixture".toByteArray())
        val signature = Signature.getInstance("NONEwithECDSA").run { initSign(privateKey); update(digest); sign() }
        assertTrue(Signature.getInstance("NONEwithECDSA").run { initVerify(certificate); update(digest); verify(signature) })
        SandboxTlsIdentity(alias).ensureCreated()
        assertArrayEquals(certificate.encoded, store.getCertificate(alias).encoded)
    }

    @Test fun legacyDigestPolicyIsRejectedWithoutReplacingTheKey() = withAlias { alias, store ->
        KeyPairGenerator.getInstance("EC", "AndroidKeyStore").apply {
            initialize(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256).build())
        }.generateKeyPair()
        val original = store.getCertificate(alias).encoded
        val identity = SandboxTlsIdentity(alias)
        identity.ensureCreated()
        try { identity.keyManager(); fail("Legacy digest policy must not reach TLS") }
        catch (_: SandboxTlsIdentity.IncompatibleExistingKey) { /* Expected fixed safe failure. */ }
        assertArrayEquals(original, store.getCertificate(alias).encoded)
    }

    @Test fun androidKeystoreCompletesMutualTls12And13() = withAlias { alias, store ->
        val identity = SandboxTlsIdentity(alias).apply { ensureCreated() }
        val anchors = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null); setCertificateEntry("fixture", identity.certificate())
        }
        val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(anchors) }
        val serverKeys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(store, null) }
        val serverContext = SSLContext.getInstance("TLS").apply { init(serverKeys.keyManagers, trust.trustManagers, null) }
        val clientContext = SSLContext.getInstance("TLS").apply { init(arrayOf(identity.keyManager()), trust.trustManagers, null) }
        val executor = Executors.newSingleThreadExecutor()
        try {
            for (protocol in listOf("TLSv1.2", "TLSv1.3")) {
                (serverContext.serverSocketFactory.createServerSocket(0, 1, InetAddress.getLoopbackAddress()) as SSLServerSocket).use { server ->
                    server.enabledProtocols = arrayOf(protocol); server.needClientAuth = true; server.soTimeout = 5000
                    val received = executor.submit<Boolean> {
                        (server.accept() as SSLSocket).use { socket ->
                            socket.soTimeout = 5000; socket.startHandshake()
                            assertArrayEquals(identity.certificate().encoded, socket.session.peerCertificates.first().encoded)
                            socket.outputStream.write(42)
                            true
                        }
                    }
                    (clientContext.socketFactory.createSocket(InetAddress.getLoopbackAddress(), server.localPort) as SSLSocket).use { client ->
                        client.enabledProtocols = arrayOf(protocol); client.soTimeout = 5000; client.startHandshake()
                        assertEquals(42, client.inputStream.read())
                    }
                    assertTrue(received.get(10, TimeUnit.SECONDS))
                }
            }
        } finally { executor.shutdownNow() }
    }
}
