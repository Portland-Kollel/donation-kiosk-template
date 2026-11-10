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
        // Regular mode (staff exit) gives the normal home screen back until restart or Pin kiosk screen.
        if(!paused(context))runCatching {
            val home=IntentFilter(Intent.ACTION_MAIN).apply {addCategory(Intent.CATEGORY_HOME);addCategory(Intent.CATEGORY_DEFAULT)}
            dpm.addPersistentPreferredActivity(admin,home,ComponentName(context,MainActivity::class.java))
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
    /** Lets staff use the browser/Google consent screens; lock-down resumes on restart or Pin kiosk screen. */
    fun pause(activity:Activity) {
        setPaused(activity,true);runCatching {activity.stopLockTask()}
        if(managed(activity))runCatching {dpm(activity).clearPackagePersistentPreferredActivities(admin(activity),activity.packageName)}
    }
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
