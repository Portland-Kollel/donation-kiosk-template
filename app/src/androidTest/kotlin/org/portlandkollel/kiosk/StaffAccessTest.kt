package org.portlandkollel.kiosk

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import android.content.Context
import org.json.JSONObject
import java.io.File
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StaffAccessTest {
    @Test fun staffPinHasNoDefaultAndThrottlesFailedAttempts() {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val file=File(context.filesDir,"kiosk-staff.json")
        val original=file.takeIf { it.exists() }?.readBytes()
        val prefs=context.getSharedPreferences("staff-lockout",Context.MODE_PRIVATE)
        try {
            file.delete();prefs.edit().clear().commit()
            val access=StaffAccess(context)
            assertFalse(access.verify("123456".toCharArray()))
            val salt=ByteArray(16) { it.toByte() }
            val spec=PBEKeySpec("246810".toCharArray(),salt,210000,256)
            val hash=SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            file.writeText(JSONObject().put("salt",salt.joinToString("") {"%02x".format(it)})
                .put("hash",hash.joinToString("") {"%02x".format(it)}).put("iterations",210000).toString())
            val input="246810".toCharArray();assertTrue(access.verify(input));assertTrue(input.all {it=='\u0000'})
            repeat(5) { assertFalse(access.verify("135791".toCharArray())) }
            assertFalse(access.verify("246810".toCharArray()))
        } finally {
            if(original!=null)file.writeBytes(original) else file.delete()
            prefs.edit().clear().commit()
        }
    }
}
