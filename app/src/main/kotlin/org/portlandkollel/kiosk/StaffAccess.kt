package org.portlandkollel.kiosk

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** No default PIN. Owner provisions only PBKDF2 salt/hash over trusted USB. */
class StaffAccess(private val context:Context) {
    fun configured()=File(context.filesDir,"kiosk-staff.json").exists()
    fun create(pin:CharArray,confirm:CharArray) {
        check(!configured());write(pin,confirm)
    }
    /** Replaces the PIN only after the current PIN verifies; the old file is atomically overwritten, never deleted first. */
    fun change(current:CharArray,pin:CharArray,confirm:CharArray) {
        try {check(verify(current)) {"STAFF_PIN_NOT_ACCEPTED"};write(pin,confirm)} finally {current.fill('\u0000');pin.fill('\u0000');confirm.fill('\u0000')}
    }
    private fun write(pin:CharArray,confirm:CharArray) {
        try {
            require(pin.size in 6..12 && pin.all {it in '0'..'9'} && pin.contentEquals(confirm))
            val salt=ByteArray(16).also {java.security.SecureRandom().nextBytes(it)}
            val spec=PBEKeySpec(pin,salt,210000,256)
            val hash=try {SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded} finally {spec.clearPassword()}
            val value=JSONObject().put("salt",salt.joinToString("") {"%02x".format(it)}).put("hash",hash.joinToString("") {"%02x".format(it)}).put("iterations",210000)
            val target=android.util.AtomicFile(File(context.filesDir,"kiosk-staff.json"));val stream=target.startWrite()
            try {stream.write(value.toString().toByteArray());stream.fd.sync();target.finishWrite(stream)} catch(e:Exception){target.failWrite(stream);throw e}
        } finally {pin.fill('\u0000');confirm.fill('\u0000')}
    }
    private fun hex(value:String):ByteArray {
        check(value.length%2==0 && Regex("[0-9a-f]+").matches(value))
        return value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
    @Synchronized fun verify(pin:CharArray):Boolean {
        val prefs=context.getSharedPreferences("staff-lockout",Context.MODE_PRIVATE)
        val now=System.currentTimeMillis()
        try {
            if(prefs.getLong("until",0)>now || pin.size !in 6..12 || pin.any { it !in '0'..'9' })return false
            val file=File(context.filesDir,"kiosk-staff.json")
            if(!file.exists() || file.length()>1024)return false
            val value=JSONObject(file.readText());check(value.getInt("iterations")==210000)
            val salt=hex(value.getString("salt"));val expected=hex(value.getString("hash"));check(salt.size==16 && expected.size==32)
            val spec=PBEKeySpec(pin,salt,210000,256)
            val actual=try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded } finally { spec.clearPassword() }
            val success=MessageDigest.isEqual(expected,actual);actual.fill(0)
            if(success)prefs.edit().clear().commit() else {
                val failures=prefs.getInt("failures",0)+1
                check(prefs.edit().putInt("failures",failures).putLong("until",if(failures>=5)now+60000 else 0).commit())
            }
            return success
        } catch(_:Exception) {return false}
        finally {pin.fill('\u0000')}
    }
}
