package io.github.vincentaravantinos.hiitbridge

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectFeatures
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class BridgeActivity : ComponentActivity() {

    private val writePermissions = setOf(
        HealthPermission.getWritePermission(ExerciseSessionRecord::class),
        HealthPermission.getWritePermission(HeartRateRecord::class),
        HealthPermission.getWritePermission(ActiveCaloriesBurnedRecord::class),
    )
    private val readPermissions = setOf(
        HealthPermission.getReadPermission(SleepSessionRecord::class),
        HealthPermission.getReadPermission(HeartRateVariabilityRmssdRecord::class),
        HealthPermission.getReadPermission(RestingHeartRateRecord::class),
        HealthPermission.getReadPermission(HeartRateRecord::class),
        HealthPermission.getReadPermission(WeightRecord::class),
    )
    private var backgroundAvailable = false
    private val wanted: Set<String>
        get() = writePermissions + readPermissions +
            (if (backgroundAvailable) setOf(HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND) else emptySet())

    private lateinit var requestPermissions: ActivityResultLauncher<Set<String>>
    private var pending: SessionPayload? = null
    private var setupOnly = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestPermissions = registerForActivityResult(
            PermissionController.createRequestPermissionResultContract()
        ) { granted ->
            if (granted.containsAll(writePermissions) || pending == null) proceed(granted)
            else done("Permissions Health Connect refusées — rien n'a été écrit.")
        }
        handle(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        val d = intent?.data?.getQueryParameter("d")
        try {
            val o = d?.let { SessionPayload.decode(it) }
            o?.let { SessionPayload.cfgOf(it) }?.let { HealthSync.saveConfig(this, it) }
            pending = o?.let { SessionPayload.sessionOf(it) }
            setupOnly = pending == null
        } catch (e: Exception) {
            done("Données de séance illisibles : ${e.message}"); return
        }

        if (HealthConnectClient.getSdkStatus(this) != HealthConnectClient.SDK_AVAILABLE) {
            done("Health Connect n'est pas disponible sur ce téléphone."); return
        }

        val client = HealthConnectClient.getOrCreate(this)
        backgroundAvailable = client.features.getFeatureStatus(
            HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_IN_BACKGROUND
        ) == HealthConnectFeatures.FEATURE_STATUS_AVAILABLE
        lifecycleScope.launch {
            val granted = client.permissionController.getGrantedPermissions()
            if (granted.containsAll(wanted)) proceed(granted)
            else requestPermissions.launch(wanted)
        }
    }

    private fun proceed(granted: Set<String>) {
        val client = HealthConnectClient.getOrCreate(this)
        val canRead = granted.containsAll(readPermissions) && HealthSync.config(this) != null
        val canReadInBackground = canRead && backgroundAvailable &&
            HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND in granted
        lifecycleScope.launch {
            val msgs = mutableListOf<String>()
            pending?.let { p ->
                try {
                    val r = HealthWriter.write(client, p)
                    val extra = if (r.fallbackSegments > 0) " (${r.fallbackSegments} en « autre »)" else ""
                    msgs += "✓ Health Connect : séance, ${r.segments} segments$extra, ${r.hrSamples} pts FC"
                } catch (e: Exception) {
                    msgs += "Échec d'écriture Health Connect : ${e.javaClass.simpleName} ${e.message ?: ""}"
                }
            }
            when {
                canReadInBackground && !setupOnly -> SyncWorker.schedule(this@BridgeActivity, now = true)
                canRead -> {
                    // Pendant que l'app est au premier plan, la lecture est toujours permise : on en
                    // profite (réglage, ou téléphone sans lecture en arrière-plan).
                    if (canReadInBackground) SyncWorker.schedule(this@BridgeActivity, now = false)
                    try {
                        val n = HealthSync.run(this@BridgeActivity)
                        if (setupOnly) msgs += "✓ Forme du jour : $n nuit(s) synchronisée(s)" +
                            (if (canReadInBackground) ", mise à jour auto à 6h, 7h, 8h, 9h et 10h" else "")
                    } catch (e: Exception) {
                        msgs += "Synchro santé impossible : ${e.message ?: e.javaClass.simpleName}"
                    }
                }
                setupOnly -> msgs += "Lecture santé non autorisée — la forme du jour restera vide."
            }
            done(msgs.joinToString("\n").ifEmpty { "HIIT Bridge est prêt ✓" })
        }
    }

    private fun done(msg: String) {
        Toast.makeText(applicationContext, msg, Toast.LENGTH_LONG).show()
        finish()
    }
}
