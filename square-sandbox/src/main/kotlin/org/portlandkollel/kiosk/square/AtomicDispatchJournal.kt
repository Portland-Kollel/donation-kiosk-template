package org.portlandkollel.kiosk.square

import android.content.Context
import android.util.AtomicFile
import org.json.JSONObject
import org.portlandkollel.kiosk.core.SquareDispatch
import org.portlandkollel.kiosk.core.SquareDispatchJournal
import java.io.File

/** No donor data or credentials; excluded from backups by app policy and noBackupFilesDir. */
internal class AtomicDispatchJournal(context: Context) : SquareDispatchJournal {
    private val file = AtomicFile(File(context.noBackupFilesDir, "square-pending.json"))
    @Synchronized override fun pending(): SquareDispatch? {
        // Corruption/I/O failures propagate and prevent dispatch, never interpreted as an empty journal.
        val raw = try { file.readFully() } catch (error: java.io.FileNotFoundException) {
            if (file.baseFile.exists() || File(file.baseFile.path + ".bak").exists() ||
                File(file.baseFile.path + ".new").exists()) throw error
            return null
        }
        val value = JSONObject(String(raw, Charsets.UTF_8))
        return SquareDispatch(value.getString("reference_id"), value.getString("sdk_attempt_id"), value.getLong("cents"))
    }
    @Synchronized override fun reserve(dispatch: SquareDispatch) {
        // Cross-controller/process exclusion as well as this object's monitor.
        java.io.RandomAccessFile(File(file.baseFile.parentFile, "square-dispatch.lock"), "rw").use { lock ->
            lock.channel.lock().use {
                commit(dispatch)
            }
        }
    }
    private fun commit(dispatch: SquareDispatch) {
        check(pending() == null)
        val value = JSONObject().put("reference_id", dispatch.referenceId)
            .put("sdk_attempt_id", dispatch.sdkAttemptId).put("cents", dispatch.cents)
        val stream = file.startWrite()
        try {
            stream.write(value.toString().toByteArray(Charsets.UTF_8))
            stream.fd.sync() // Surface durability errors before allowing an SDK side effect.
            file.finishWrite(stream)
            check(pending() == dispatch) { "Dispatch journal commit failed." }
        } catch (error: Exception) {
            file.failWrite(stream)
            throw error
        }
    }
}
