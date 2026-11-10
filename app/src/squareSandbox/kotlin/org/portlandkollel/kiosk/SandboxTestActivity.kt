package org.portlandkollel.kiosk

import android.Manifest
import android.os.Bundle
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.portlandkollel.kiosk.security.SandboxTlsIdentity
import org.portlandkollel.kiosk.core.SquareDispatch
import org.portlandkollel.kiosk.square.SandboxSdkOutcome
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.net.ssl.*

/** Separate debug Sandbox APK. No access token input, embedded credentials or production ID. */
class SandboxTestActivity : ComponentActivity() {
    private val sdk get() = (application as SquareSandboxApplication).square
    private var message by mutableStateOf("Attended Sandbox test only. Complete private USB pairing first.")
    private var authorized by mutableStateOf(false)
    private var busy by mutableStateOf(false)
    private var dollars by mutableStateOf("18")
    private var verifiedStatus by mutableStateOf<String?>(null)
    private val pending by lazy { AtomicFile(File(noBackupFilesDir,"sandbox-request.json")) }
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        message = if(it.values.all { granted -> granted }) "Required grants accepted." else "Some permissions were denied; SDK operations remain blocked."
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        deviceKeys()
        writePublicCertificate() // USB helper reads only this public certificate, never a private key.
        if(pending.baseFile.exists()) runCatching {
            val cents=JSONObject(String(pending.readFully(),Charsets.UTF_8)).getLong("cents")
            dollars=(cents/100).toString()
            message="Original test attempt is preserved. Reconcile it; another charge is blocked."
        }
        setContent {
            SandboxGivingScreen(dollars,{dollars=it},busy,pending.baseFile.exists()||sdk.pendingDispatch()!=null,
                authorized,message,verifiedStatus,::donate,::recover) {
                Text(message)
                Button(onClick={requestPermissions()},enabled=!busy) {Text("Grant test permissions")}
                Button(onClick={connect()},enabled=!busy) {Text("Connect and authorize Sandbox SDK")}
                Button(onClick={runCatching {sdk.showMockReaderForAttendedTest()}.onFailure {message="Reader UI unavailable; verify authorization and permissions."}},enabled=authorized&&!busy) {Text("Open Square simulated reader")}
                OutlinedButton(onClick={recover()},enabled=!busy) {Text("Reconcile original test")}
            }
        }
    }
    private fun requestPermissions() {
        val requested=mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION,Manifest.permission.READ_PHONE_STATE)
        if(Build.VERSION.SDK_INT>=31) requested.addAll(listOf(Manifest.permission.BLUETOOTH_CONNECT,Manifest.permission.BLUETOOTH_SCAN))
        if(Build.VERSION.SDK_INT>=33) requested.add(Manifest.permission.POST_NOTIFICATIONS)
        permissions.launch(requested.toTypedArray())
    }
    private fun keys()=KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private val deviceIdentity = SandboxTlsIdentity()
    private fun deviceKeys() {
        deviceIdentity.ensureCreated() // Never replaces an existing identity or its immutable policy.
    }
    private fun exportCertificate() {
        writePublicCertificate()
        message="Device public certificate ready for private USB pairing. Private key stays in Android Keystore."
    }
    private fun writePublicCertificate() {
        val encoded=android.util.Base64.encodeToString(deviceIdentity.certificate().encoded,android.util.Base64.NO_WRAP)
        File(filesDir,"sandbox-device.pem").writeText("-----BEGIN CERTIFICATE-----\n"+encoded.chunked(64).joinToString("\n")+"\n-----END CERTIFICATE-----\n")
    }
    private fun pairing():JSONObject {
        val store=keys()
        if(!store.containsAlias("sandbox-pairing")) KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("sandbox-pairing",KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
        val key=store.getKey("sandbox-pairing",null) as SecretKey
        val target=AtomicFile(File(noBackupFilesDir,"sandbox-pairing.enc"))
        val incoming=File(filesDir,"pairing.json")
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
    private fun request(path:String,body:JSONObject?=null):JSONObject {
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
            check(connection.responseCode==200) { "Backend request unavailable" }
            val bytes=connection.inputStream.use { input ->
                val output=java.io.ByteArrayOutputStream();val buffer=ByteArray(4096)
                while(true) { val count=input.read(buffer);if(count<0)break;check(output.size()+count<=65536);output.write(buffer,0,count) }
                output.toByteArray()
            }
            return JSONObject(String(bytes,Charsets.UTF_8))
        } finally { connection.disconnect() }
    }
    private fun task(action:suspend ()->Unit) {
        if(busy)return
        busy=true
        lifecycleScope.launch {
            try { action() } catch(_:SandboxTlsIdentity.IncompatibleExistingKey) {
                message="DEVICE_TLS_KEY_POLICY: existing identity needs a reviewed repair. Keep app data and payment journals intact."
            } catch(_:Exception) { message="Test operation unavailable. Check pairing, TLS, session/attendance and backend configuration. Pending state is preserved." }
            finally { busy=false }
        }
    }
    private fun connect()=task {
        check(sdk.pendingDispatch()==null && !pending.baseFile.exists()) { "Reconcile pending test first" }
        val response=withContext(Dispatchers.IO) { request("/test/sdk-authorization") }
        check(response.getString("location_id")==BuildConfig.LEGACY_SANDBOX_LOCATION_ID)
        sdk.authorizeFromTrustedRuntime(response.getString("access_token")) { success ->
            authorized=success;message=if(success) "Square SDK authorized in Sandbox. Open the simulated reader." else "SDK authorization failed; no credential details displayed."
        }
    }
    private fun donate()=task {
        check(!pending.baseFile.exists() && sdk.pendingDispatch()==null) { "Reconcile pending attempt" }
        val wholeDollars=dollars.toLongOrNull()
        check(wholeDollars!=null && wholeDollars in 1..10000)
        val intent=JSONObject().put("request_key",UUID.randomUUID().toString()).put("cents",wholeDollars*100).put("purpose","GENERAL")
        val stream=pending.startWrite()
        try { stream.write(intent.toString().toByteArray());stream.fd.sync();pending.finishWrite(stream) }
        catch(e:Exception) { pending.failWrite(stream);throw e }
        val response=withContext(Dispatchers.IO) { request("/test/dispatch",intent) }
        check(response.getBoolean("launch_permitted"))
        check(response.getLong("cents")==intent.getLong("cents"))
        val dispatch=SquareDispatch(response.getString("reference_id"),response.getString("sdk_attempt_id"),response.getLong("cents"))
        message="Use Square's simulated reader to tap a virtual card. Do not use a physical card."
        sdk.startReservedPayment(dispatch) { result ->
            lifecycleScope.launch {
                busy=true
                try {
                    val status=withContext(Dispatchers.IO) {
                        when(result) {
                            is SandboxSdkOutcome.NeedsBackendVerification -> request("/v1/sandbox/attempts/${result.referenceId}/square-verification",JSONObject().put("payment_id",result.paymentId))
                            is SandboxSdkOutcome.NeedsRecovery -> request("/v1/sandbox/attempts/${result.referenceId}/square-recovery",JSONObject())
                            is SandboxSdkOutcome.SdkReturnedNoPayment -> request("/v1/sandbox/attempts/${result.referenceId}/square-recovery",JSONObject())
                        }
                    }
                    verifiedStatus=status.getString("square_status")
                    message="Backend verified Square Sandbox status: "+verifiedStatus+". No real money collected. Journal remains locked for review."
                } catch(_:Exception) { message="Status uncertain. Keep the original attempt; use Reconcile, never start another charge." }
                finally { busy=false }
            }
        }
    }
    private fun recover()=task {
        val dispatch=sdk.pendingDispatch()
        val result=withContext(Dispatchers.IO) {
            val reference=dispatch?.referenceId ?: run {
                val intent=JSONObject(String(pending.readFully(),Charsets.UTF_8))
                request("/v1/sandbox/requests/"+intent.getString("request_key")).getString("id")
            }
            request("/v1/sandbox/attempts/$reference/square-recovery",JSONObject())
        }
        verifiedStatus=result.getString("square_status")
        message="Backend verified Sandbox status: "+verifiedStatus+". No new payment started."
    }
}
