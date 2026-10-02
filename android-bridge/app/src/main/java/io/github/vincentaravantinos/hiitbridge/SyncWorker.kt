package io.github.vincentaravantinos.hiitbridge

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.time.Duration
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Synchro santé en tâche de fond (permission « lecture en arrière-plan ») à heures fixes :
 * 6h, 7h, 8h, 9h et 10h, puis rien jusqu'au lendemain 6h. Chaque passage programme le suivant.
 * Android peut décaler un réveil de quelques minutes (économie d'énergie).
 */
class SyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val slot = inputData.getBoolean(KEY_SLOT, false)
        try {
            HealthSync.run(applicationContext)
        } catch (e: Exception) {
            // Pas de réessai : le créneau suivant est dans une heure au plus tard.
        } finally {
            if (slot) scheduleNextSlot(applicationContext, ExistingWorkPolicy.APPEND_OR_REPLACE)
        }
        return Result.success()
    }

    companion object {
        private const val KEY_SLOT = "slot"
        private const val SLOT_WORK = "health-sync-slot"
        val SLOT_HOURS = listOf(6, 7, 8, 9, 10)
        private val net = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        fun nextSlot(now: ZonedDateTime): ZonedDateTime {
            for (h in SLOT_HOURS) {
                val t = now.toLocalDate().atTime(h, 0).atZone(now.zone)
                if (t.isAfter(now.plusMinutes(2))) return t
            }
            return now.toLocalDate().plusDays(1).atTime(SLOT_HOURS.first(), 0).atZone(now.zone)
        }

        private fun scheduleNextSlot(ctx: Context, policy: ExistingWorkPolicy) {
            val now = ZonedDateTime.now(ZoneId.systemDefault())
            val delay = Duration.between(now, nextSlot(now))
            WorkManager.getInstance(ctx).enqueueUniqueWork(
                SLOT_WORK, policy,
                OneTimeWorkRequestBuilder<SyncWorker>()
                    .setInitialDelay(delay)
                    .setConstraints(net)
                    .setInputData(androidx.work.workDataOf(KEY_SLOT to true))
                    .build()
            )
        }

        /** Remet en place la série de créneaux ; `now` = synchro immédiate en plus (fin de séance). */
        fun schedule(ctx: Context, now: Boolean) {
            val wm = WorkManager.getInstance(ctx)
            wm.cancelUniqueWork("health-sync") // ancienne synchro toutes les 3 h
            scheduleNextSlot(ctx, ExistingWorkPolicy.REPLACE)
            if (now) wm.enqueueUniqueWork(
                "health-sync-now", ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<SyncWorker>().setConstraints(net).build()
            )
        }
    }
}
