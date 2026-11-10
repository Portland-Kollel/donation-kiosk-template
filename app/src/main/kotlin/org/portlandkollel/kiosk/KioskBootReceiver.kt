package org.portlandkollel.kiosk

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build

/** Pixel forbids background Activity launch after boot for ordinary apps.
 * Post a private tap-to-open startup notification (or, as kiosk manager, open the kiosk directly);
 * overlay, foreground-service bypass, or initiate payment/authorization on boot. */
class KioskBootReceiver:BroadcastReceiver() {
    override fun onReceive(context:Context,intent:Intent) {
        if(intent.action!=Intent.ACTION_BOOT_COMPLETED)return
        KioskLockdown.setPaused(context,false) // Every restart returns to full lock-down.
        if(KioskLockdown.managed(context)) {
            // Kiosk manager: re-assert the home screen and open the kiosk now (device owners may start activities from the background).
            KioskLockdown.applyPolicies(context)
            runCatching {context.startActivity(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))}
            return
        }
        if(Build.VERSION.SDK_INT>=29 && context.getSystemService(android.app.role.RoleManager::class.java).isRoleHeld(android.app.role.RoleManager.ROLE_HOME))return
        if(Build.VERSION.SDK_INT>=33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)return
        val manager=context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("kiosk-start","Kiosk startup",NotificationManager.IMPORTANCE_DEFAULT))
        val launch=PendingIntent.getActivity(context,0,Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        manager.notify(1,android.app.Notification.Builder(context,"kiosk-start").setSmallIcon(R.drawable.ic_kollel)
            .setContentTitle("Open Kollel Giving Kiosk").setContentText("Tap to resume. Staff can pin the screen after setup.")
            .setContentIntent(launch).setAutoCancel(true).setVisibility(android.app.Notification.VISIBILITY_PRIVATE).build())
    }
}
