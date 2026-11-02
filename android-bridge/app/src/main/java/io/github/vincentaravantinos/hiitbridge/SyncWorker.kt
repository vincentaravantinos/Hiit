package io.github.vincentaravantinos.hiitbridge

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.time.Duration
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

/**
 * Synchro santé en tâche de fond (permission « lecture en arrière-plan ») : à 6h, puis toutes
 * les heures jusqu'à ce que la nuit du jour soit captée (Oura l'écrit dans Health Connect à
 * son propre rythme), au plus tard midi ; ensuite rien jusqu'au lendemain 6h.
 * Chaque passage programme le suivant. Android peut décaler un réveil de quelques minutes.
 *
 * Filet de sécurité : si un maillon de la chaîne est perdu (Android l'a abandonné), plus rien ne
 * tourne jusqu'à la prochaine ouverture du pont. Une tâche périodique horaire vérifie donc que
 * la nuit a bien été captée, lance la synchro sinon, et remet la chaîne en place.
 */
class SyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val slot = inputData.getBoolean(KEY_SLOT, false)
        if (inputData.getBoolean(KEY_SAFETY, false)) return safetyCheck()
        try {
            HealthSync.run(applicationContext)
        } catch (e: Exception) {
            // Pas de réessai : le créneau suivant est dans une heure au plus tard.
        } finally {
            if (slot) scheduleNextSlot(applicationContext, ExistingWorkPolicy.APPEND_OR_REPLACE)
        }
        return Result.success()
    }

    private suspend fun safetyCheck(): Result {
        val ctx = applicationContext
        if (HealthSync.config(ctx) == null || HealthSync.hasTodaySleep(ctx)) return Result.success()
        val now = ZonedDateTime.now(ZoneId.systemDefault())
        val last = HealthSync.lastRun(ctx)
        val today6 = now.toLocalDate().atTime(SLOT_HOURS.first(), 0).atZone(now.zone).toInstant().toEpochMilli()
        val chainBroken = last < today6 // aucun passage depuis 6h ce matin
        val inWindow = now.hour in SLOT_HOURS
        val recent = System.currentTimeMillis() - last < 50 * 60_000L // la chaîne vient de passer
        if (now.hour >= SLOT_HOURS.first() && (inWindow || chainBroken) && !recent) {
            try { HealthSync.run(ctx) } catch (e: Exception) { /* prochain contrôle dans 1 h */ }
        }
        scheduleNextSlot(ctx, ExistingWorkPolicy.KEEP) // ne recrée la chaîne que si elle a disparu
        return Result.success()
    }

    companion object {
        private const val KEY_SLOT = "slot"
        private const val KEY_SAFETY = "safety"
        private const val SLOT_WORK = "health-sync-slot"
        private const val SAFETY_WORK = "health-sync-safety"
        val SLOT_HOURS = (6..12).toList()
        private val net = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        fun nextSlot(now: ZonedDateTime, gotToday: Boolean): ZonedDateTime {
            if (!gotToday) for (h in SLOT_HOURS) {
                val t = now.toLocalDate().atTime(h, 0).atZone(now.zone)
                if (t.isAfter(now.plusMinutes(2))) return t
            }
            return now.toLocalDate().plusDays(1).atTime(SLOT_HOURS.first(), 0).atZone(now.zone)
        }

        private fun scheduleNextSlot(ctx: Context, policy: ExistingWorkPolicy) {
            val now = ZonedDateTime.now(ZoneId.systemDefault())
            val delay = Duration.between(now, nextSlot(now, HealthSync.hasTodaySleep(ctx)))
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
            wm.enqueueUniquePeriodicWork(
                SAFETY_WORK, ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<SyncWorker>(1, TimeUnit.HOURS)
                    .setConstraints(net)
                    .setInputData(androidx.work.workDataOf(KEY_SAFETY to true))
                    .build()
            )
            if (now) wm.enqueueUniqueWork(
                "health-sync-now", ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<SyncWorker>().setConstraints(net).build()
            )
        }
    }
}
