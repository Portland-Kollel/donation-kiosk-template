package org.portlandkollel.kiosk

import android.app.Application
import org.json.JSONObject
import org.portlandkollel.kiosk.core.KioskMode
import org.portlandkollel.kiosk.square.KioskSquareController
import java.io.File

class KollelApplication:Application() {
    val vault by lazy {StandaloneVault(this)}
    val ledger by lazy {TabletLedger(vault)}
    internal val session by lazy {TabletSquare(this,vault)}
    val config:JSONObject get()=vault.read().optJSONObject("config")?:JSONObject().put("environment","sandbox")
    val mode:KioskMode get()=if(config.getString("environment")=="sandbox")KioskMode.SANDBOX else KioskMode.READER_SETUP
    private var controller:KioskSquareController?=null
    val square:KioskSquareController get()=controller?:KioskSquareController(this,mode,config.getString("application_id"),BuildConfig.REAL_CHARGES_ENABLED).also {controller=it}
    fun initialized()=controller!=null
    override fun onCreate() {
        super.onCreate()
        // Reuse public config only; no legacy Mac/device credential is imported or required.
        if(!vault.read().has("config")) {
            val old=File(filesDir,"kiosk-config.json")
            if(old.exists()&&old.length()<2048)runCatching {val value=JSONObject(old.readText());vault.update {it.put("config",value)}}
        }
        if(config.optString("application_id").isNotEmpty())square
        TabletSyncWorker.schedule(this)
    }
}
