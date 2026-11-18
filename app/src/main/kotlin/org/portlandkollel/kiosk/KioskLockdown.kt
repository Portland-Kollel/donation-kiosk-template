package org.portlandkollel.kiosk

import android.app.Activity
import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.provider.Settings

/** Registered once over ADB as the tablet's kiosk manager (device owner); grants no other policies. */
class KioskAdminReceiver:DeviceAdminReceiver() {
    // Claim the home screen and lock-task allowlist immediately, without waiting for someone to open the app.
    override fun onEnabled(context:Context,intent:Intent){KioskLockdown.record(context,"enabled");KioskLockdown.applyPolicies(context)}
    /** Records every loss of kiosk manager and whether the staff Remove button caused it. */
    override fun onDisabled(context:Context,intent:Intent){KioskLockdown.record(context,if(KioskLockdown.removingByStaff)"removed by staff button" else "removed outside the app")}
}

/**
 * Self-locking kiosk. When this app is the device owner it pins itself on every start with no prompt
 * and no escape gesture, stays the home screen, and keeps the screen on while plugged in. Staff exit
 * (PIN) pauses lock-down until the next restart or until staff pin it again.
 */
internal object KioskLockdown {
    private fun prefs(context:Context)=context.getSharedPreferences("kiosk-lockdown",Context.MODE_PRIVATE)
    private fun admin(context:Context)=ComponentName(context,KioskAdminReceiver::class.java)
    private fun dpm(context:Context)=context.getSystemService(DevicePolicyManager::class.java)
    fun managed(context:Context)=dpm(context).isDeviceOwnerApp(context.packageName)
    @Volatile var removingByStaff=false
    fun record(context:Context,event:String) {
        val stamp=java.time.ZonedDateTime.now(java.time.ZoneId.of("America/Los_Angeles")).format(java.time.format.DateTimeFormatter.ofPattern("MMM d h:mm a",java.util.Locale.US))
        prefs(context).edit().putString("last_event","Kiosk manager $event $stamp").commit()
    }
    /** Staff-visible status: current kiosk manager state plus the last recorded change. */
    fun status(context:Context)=(if(managed(context))"Kiosk manager: ON" else "Kiosk manager: OFF")+
        prefs(context).getString("last_event",null)?.let {" · last change: $it"}.orEmpty()
    fun paused(context:Context)=prefs(context).getBoolean("paused",false)
    fun setPaused(context:Context,value:Boolean){prefs(context).edit().putBoolean("paused",value).commit()}
    /**
     * Device-wide policies; needs no activity, so it also runs when kiosk manager is enabled and at boot.
     * Each policy is applied independently so one refusal never skips the others (e.g. the home screen).
     */
    fun applyPolicies(context:Context) {
        if(!managed(context))return
        val dpm=dpm(context);val admin=admin(context)
        runCatching {dpm.setLockTaskPackages(admin,arrayOf(context.packageName))}
        runCatching {dpm.setLockTaskFeatures(admin,DevicePolicyManager.LOCK_TASK_FEATURE_NONE)}
        // Regular mode (staff exit) gives the normal home screen back until restart or Lock kiosk screen.
        if(!paused(context))runCatching {
            prefs(context).getString("regular_home",null)?.let {other ->
                runCatching {dpm.clearPackagePersistentPreferredActivities(admin,other)}
                prefs(context).edit().remove("regular_home").commit()
            }
            dpm.addPersistentPreferredActivity(admin,homeFilter(),ComponentName(context,MainActivity::class.java))
        }
        runCatching {dpm.setGlobalSetting(admin,Settings.Global.STAY_ON_WHILE_PLUGGED_IN,"7")}
        runCatching {dpm.setKeyguardDisabled(admin,true)} // Only succeeds when no secure screen lock is set.
    }
    /** Called on every activity resume. Never throws: a policy failure must not block donations. */
    fun apply(activity:Activity) {
        if(!managed(activity))return
        applyPolicies(activity)
        if(!paused(activity))runCatching {activity.startLockTask()}
    }
    private fun homeFilter()=IntentFilter(Intent.ACTION_MAIN).apply {addCategory(Intent.CATEGORY_HOME);addCategory(Intent.CATEGORY_DEFAULT)}
    /** The tablet's normal launcher (e.g. Pixel Launcher), never this app or Android's fallback home screen. */
    fun regularLauncher(context:Context):ComponentName? {
        val homes=context.packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),android.content.pm.PackageManager.MATCH_ALL)
            .map {it.activityInfo}.filter {it.packageName!=context.packageName && it.packageName!="com.android.settings"}
        val pick=homes.firstOrNull {it.packageName=="com.google.android.apps.nexuslauncher"}?:homes.firstOrNull()
        return pick?.let {ComponentName(it.packageName,it.name)}
    }
    /** Lets staff use the browser/Google consent screens; lock-down resumes on restart or Lock kiosk screen. */
    fun pause(activity:Activity) {
        setPaused(activity,true);runCatching {activity.stopLockTask()}
        if(managed(activity))runCatching {
            val dpm=dpm(activity);val admin=admin(activity)
            dpm.clearPackagePersistentPreferredActivities(admin,activity.packageName)
            // The kiosk is still the tablet's default home app; point Home at the normal launcher while paused,
            // otherwise Staff exit and the Home button land straight back in the kiosk.
            regularLauncher(activity)?.let {
                dpm.addPersistentPreferredActivity(admin,homeFilter(),it)
                prefs(activity).edit().putString("regular_home",it.packageName).commit()
            }
        }
    }
    /** Set when staff leave for regular mode or tablet Settings: opening the kiosk again re-locks it. */
    fun setRelockOnReturn(context:Context,value:Boolean){prefs(context).edit().putBoolean("relock_on_return",value).commit()}
    fun relockOnReturn(context:Context)=prefs(context).getBoolean("relock_on_return",false)
    fun resume(activity:Activity){setPaused(activity,false);if(managed(activity))apply(activity) else runCatching {activity.startLockTask()}}
    /** After a staff sign-in returns: re-lock only when the kiosk manager is active (no pinning prompt otherwise). */
    fun resumeIfManaged(activity:Activity){if(managed(activity)){setPaused(activity,false);apply(activity)}}
    /** Removes kiosk-manager status entirely so the tablet behaves normally again. */
    fun remove(activity:Activity) {
        if(!managed(activity))return
        runCatching {
            val dpm=dpm(activity);val admin=admin(activity)
            activity.stopLockTask()
            dpm.clearPackagePersistentPreferredActivities(admin,activity.packageName)
            runCatching {dpm.setKeyguardDisabled(admin,false)}
            dpm.setLockTaskPackages(admin,emptyArray())
            removingByStaff=true
            try {dpm.clearDeviceOwnerApp(activity.packageName)} finally {removingByStaff=false}
            record(activity,"removed by staff button")
        }
    }
}
