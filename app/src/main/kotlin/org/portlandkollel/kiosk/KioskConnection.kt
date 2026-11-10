package org.portlandkollel.kiosk

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.net.ssl.*
import org.portlandkollel.kiosk.security.SandboxTlsIdentity

class KioskError(val code: String): Exception(code)
class KioskConnection(private val context: Context) {
    private val deviceIdentity = SandboxTlsIdentity("kollel-device")
    init { deviceKeys(); writePublicCertificate() }
    private fun keys()=KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private fun deviceKeys() {
        deviceIdentity.ensureCreated() // Never replaces an existing identity or its immutable policy.
    }
    private fun writePublicCertificate() {
        val encoded=android.util.Base64.encodeToString(deviceIdentity.certificate().encoded,android.util.Base64.NO_WRAP)
        File(context.filesDir,"kiosk-device.pem").writeText("-----BEGIN CERTIFICATE-----\n"+encoded.chunked(64).joinToString("\n")+"\n-----END CERTIFICATE-----\n")
    }
    private fun pairing():JSONObject {
        val store=keys()
        if(!store.containsAlias("kiosk-pairing")) KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("kiosk-pairing",KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
        val key=store.getKey("kiosk-pairing",null) as SecretKey
        val target=AtomicFile(File(context.noBackupFilesDir,"kiosk-pairing.enc"))
        val incoming=File(context.filesDir,"kiosk-pairing.json")
        if(incoming.exists()) {
            check(incoming.length()<=4096)
            val cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key)
            val data=cipher.iv+cipher.doFinal(incoming.readBytes())
            val stream=target.startWrite()
            try { stream.write(data);stream.fd.sync();target.finishWrite(stream);check(target.readFully().contentEquals(data));check(incoming.delete()) }
            catch(e:Exception) { target.failWrite(stream);throw e }
        }
        val data=target.readFully();check(data.size>12)
        val cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,key,GCMParameterSpec(128,data.copyOfRange(0,12)))
        return JSONObject(String(cipher.doFinal(data.copyOfRange(12,data.size)),Charsets.UTF_8))
    }
    fun request(path:String,body:JSONObject?=null):JSONObject {
        deviceIdentity.requireTlsCompatible()
        val pair=pairing();val base=java.net.URI(pair.getString("backend_url"))
        check(base.scheme=="https" && base.host=="localhost" && base.userInfo==null && base.query==null && base.fragment==null && base.path.isNullOrEmpty())
        val context=SSLContext.getInstance("TLS")
        val manager=deviceIdentity.keyManager()
        val expectedPin=pair.getString("server_cert_sha256")
        check(Regex("[0-9a-f]{64}").matches(expectedPin))
        // Automated pairing supplies an app-private CA over the trusted USB channel.
        // This avoids installing a system/user CA and does not trust arbitrary certificates.
        val anchors=if(pair.has("server_ca_pem")) {
            val pem=pair.getString("server_ca_pem");check(pem.length<=2048)
            val cert=java.security.cert.CertificateFactory.getInstance("X.509")
                .generateCertificate(pem.byteInputStream()) as java.security.cert.X509Certificate
            check(cert.basicConstraints>=0);cert.checkValidity()
            KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null);setCertificateEntry("sandbox-host",cert) }
        } else null // Preserve the existing manual pairing workflow.
        val trusted=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(anchors) }
            .trustManagers.filterIsInstance<X509TrustManager>().first()
        val pinned=object:X509TrustManager {
            override fun getAcceptedIssuers()=trusted.acceptedIssuers
            override fun checkClientTrusted(chain:Array<java.security.cert.X509Certificate>,authType:String)=trusted.checkClientTrusted(chain,authType)
            override fun checkServerTrusted(chain:Array<java.security.cert.X509Certificate>,authType:String) {
                trusted.checkServerTrusted(chain,authType)
                val actual=java.security.MessageDigest.getInstance("SHA-256").digest(chain.first().encoded).joinToString("") { "%02x".format(it) }
                if(actual!=expectedPin) throw java.security.cert.CertificateException("Pairing certificate mismatch")
            }
        }
        context.init(arrayOf<KeyManager>(manager),arrayOf<TrustManager>(pinned),SecureRandom())
        val connection=base.resolve(path).toURL().openConnection() as HttpsURLConnection
        connection.sslSocketFactory=context.socketFactory // CA chain, leaf pin and hostname validation stay enabled.
        connection.instanceFollowRedirects=false;connection.connectTimeout=10000;connection.readTimeout=45000
        connection.setRequestProperty("Authorization","Bearer "+pair.getString("device_token"))
        connection.setRequestProperty("Accept","application/json")
        try {
            if(body!=null) {
                connection.requestMethod="POST";connection.doOutput=true
                connection.setRequestProperty("Content-Type","application/json")
                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            if(connection.responseCode!=200) {
                val code=connection.responseCode
                val allowed=setOf("AUTHORIZATION_REQUIRED","LIVE_CHARGES_DISABLED","RECONCILIATION_REQUIRED","VERIFIER_NOT_READY","NOT_FOUND","INVALID_DONATION","VERIFICATION_UNAVAILABLE")
                val remote=runCatching {
                    val bytes=connection.errorStream?.use { input ->
                        val output=java.io.ByteArrayOutputStream();val buffer=ByteArray(256)
                        while(output.size()<=1024) {
                            val count=input.read(buffer,0,minOf(buffer.size,1025-output.size()))
                            if(count<0)break
                            output.write(buffer,0,count)
                        }
                        output.toByteArray()
                    }
                    if(bytes==null || bytes.size>1024)null else JSONObject(String(bytes,Charsets.UTF_8)).optString("error").takeIf { it in allowed }
                }.getOrNull()
                throw KioskError("BACKEND_HTTP_$code"+(remote?.let { " · $it" }?:""))
            }
            val bytes=connection.inputStream.use { input ->
                val output=java.io.ByteArrayOutputStream();val buffer=ByteArray(4096)
                while(true) { val count=input.read(buffer);if(count<0)break;check(output.size()+count<=65536);output.write(buffer,0,count) }
                output.toByteArray()
            }
            return JSONObject(String(bytes,Charsets.UTF_8))
        } finally { connection.disconnect() }
    }
}
