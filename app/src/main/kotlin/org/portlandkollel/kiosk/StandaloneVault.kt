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

/** One serialized, fsynced encrypted state: tokens, ledger, active intent and independent outboxes.
 * Never clears/replaces a missing Keystore key when encrypted data already exists. */
class StandaloneVault(context:Context,fileName:String="standalone.enc") {
    private val file=AtomicFile(File(context.noBackupFilesDir,fileName))
    private val aad="kollel-standalone-v1".toByteArray()
    private fun key():SecretKey {
        val store=KeyStore.getInstance("AndroidKeyStore").apply {load(null)}
        if(!store.containsAlias("kollel-standalone")) {
            check(!file.baseFile.exists() && !File(file.baseFile.path+".bak").exists()) {"VAULT_KEY_MISSING"}
            KeyGenerator.getInstance("AES","AndroidKeyStore").apply {
                init(KeyGenParameterSpec.Builder("kollel-standalone",KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
            }.generateKey()
        }
        return store.getKey("kollel-standalone",null) as SecretKey
    }
    @Synchronized fun read():JSONObject {
        val raw=try {file.readFully()} catch(e:java.io.FileNotFoundException) {
            check(!file.baseFile.exists()&&!File(file.baseFile.path+".bak").exists()&&!File(file.baseFile.path+".new").exists())
            return JSONObject().put("version",1).put("records",org.json.JSONArray()).put("next_row",2)
        }
        check(raw.size>28)
        val cipher=Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE,key(),GCMParameterSpec(128,raw.copyOfRange(0,12)));cipher.updateAAD(aad)
        return JSONObject(String(cipher.doFinal(raw.copyOfRange(12,raw.size)),Charsets.UTF_8))
    }
    @Synchronized fun <T> update(block:(JSONObject)->T):T {
        val state=read();val result=block(state)
        val cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key());cipher.updateAAD(aad)
        val raw=cipher.iv+cipher.doFinal(state.toString().toByteArray(Charsets.UTF_8))
        val stream=file.startWrite()
        try {stream.write(raw);stream.fd.sync();file.finishWrite(stream)} catch(e:Exception){file.failWrite(stream);throw e}
        return result
    }
}
