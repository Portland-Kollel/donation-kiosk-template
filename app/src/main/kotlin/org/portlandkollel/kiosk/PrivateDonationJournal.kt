package org.portlandkollel.kiosk

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Durable intent before network; donor fields are never plaintext in files/backups/logs. */
class PrivateDonationJournal(context: Context, mode: String) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "kiosk-intent-$mode.enc"))
    private val aad = mode.toByteArray()
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!store.containsAlias("kiosk-intent")) KeyGenerator.getInstance("AES", "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("kiosk-intent", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
        return store.getKey("kiosk-intent", null) as SecretKey
    }
    fun read(): JSONObject? {
        val data = try { file.readFully() } catch (e: java.io.FileNotFoundException) {
            if (file.baseFile.exists() || File(file.baseFile.path + ".bak").exists() || File(file.baseFile.path + ".new").exists()) throw e
            return null
        }
        check(data.size > 12)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, data.copyOfRange(0, 12))); cipher.updateAAD(aad)
        return JSONObject(String(cipher.doFinal(data.copyOfRange(12, data.size)), Charsets.UTF_8))
    }
    fun reserve(intent: JSONObject) {
        check(read() == null)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, key()); cipher.updateAAD(aad)
        val bytes = cipher.iv + cipher.doFinal(intent.toString().toByteArray(Charsets.UTF_8))
        val stream = file.startWrite()
        try { stream.write(bytes); stream.fd.sync(); file.finishWrite(stream); check(read()?.getString("request_key") == intent.getString("request_key")) }
        catch (e: Exception) { file.failWrite(stream); throw e }
    }
    /** Caller already received a verified closed response from pinned backend. */
    fun deleteClosed(request: String) { check(read()?.getString("request_key") == request); file.delete() }
}
