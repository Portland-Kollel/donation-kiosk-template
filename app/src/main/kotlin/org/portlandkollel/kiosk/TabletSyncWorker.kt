package org.portlandkollel.kiosk

import android.content.Context
import androidx.work.*
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class TabletSyncWorker(context:Context,params:WorkerParameters):CoroutineWorker(context,params) {
    override suspend fun doWork():Result=single.withLock {withContext(Dispatchers.IO) {
        val app=applicationContext as KollelApplication
        var retry=false
        fun code(e:Exception)=when(e){is RemoteFailure->"HTTP_${e.status}";is KioskError->e.code;else->"NETWORK_OR_ACCOUNT_UNAVAILABLE"}
        fun status(key:String,value:String)=app.vault.update {it.put(key,value)}
        try {if(app.session.hasCredentials())app.session.token();status("square_sync_error","")}
        catch(e:kotlinx.coroutines.CancellationException){throw e}catch(e:Exception){retry=true;status("square_sync_error",code(e))}
        val active=app.ledger.active()
        if(active!=null && active.getString("status") !in setOf("COMPLETED","FAILED","CANCELED"))try {
            val result=app.session.verify(active);app.ledger.verified(active.getString("id"),result);status("recovery_error","")
        } catch(e:kotlinx.coroutines.CancellationException){throw e}catch(e:Exception){retry=true;status("recovery_error",code(e))}
        try {
            if(app.vault.read().has("google_account"))GoogleDonations(applicationContext,app.vault).sync()
            status("google_sync_error","")
        } catch(e:kotlinx.coroutines.CancellationException){throw e}catch(e:Exception){retry=true;status("google_sync_error",code(e))}
        if(retry)Result.retry() else Result.success()
    }}
    companion object {
        private val single=Mutex()
        fun schedule(context:Context) {
            val constraints=Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork("kiosk-maintenance",ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<TabletSyncWorker>(1,TimeUnit.HOURS).setConstraints(constraints).build())
        }
        fun now(context:Context) {
            WorkManager.getInstance(context).enqueueUniqueWork("kiosk-sync",ExistingWorkPolicy.APPEND_OR_REPLACE,
                OneTimeWorkRequestBuilder<TabletSyncWorker>().setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL,30,TimeUnit.SECONDS).build())
        }
    }
}
