package io.github.vincentaravantinos.hiitbridge

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class BridgeActivity : ComponentActivity() {

    private val permissions = setOf(
        HealthPermission.getWritePermission(ExerciseSessionRecord::class),
        HealthPermission.getWritePermission(HeartRateRecord::class),
        HealthPermission.getWritePermission(ActiveCaloriesBurnedRecord::class),
    )

    private lateinit var requestPermissions: ActivityResultLauncher<Set<String>>
    private var pending: SessionPayload? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestPermissions = registerForActivityResult(
            PermissionController.createRequestPermissionResultContract()
        ) { granted ->
            if (granted.containsAll(permissions)) proceed()
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
        pending = try { d?.let { SessionPayload.parse(it) } } catch (e: Exception) {
            done("Données de séance illisibles : ${e.message}"); return
        }

        when (HealthConnectClient.getSdkStatus(this)) {
            HealthConnectClient.SDK_AVAILABLE -> {}
            else -> { done("Health Connect n'est pas disponible sur ce téléphone."); return }
        }

        val client = HealthConnectClient.getOrCreate(this)
        lifecycleScope.launch {
            val granted = client.permissionController.getGrantedPermissions()
            if (granted.containsAll(permissions)) proceed()
            else requestPermissions.launch(permissions)
        }
    }

    private fun proceed() {
        val p = pending
        if (p == null) { done("HIIT Bridge est prêt ✓ (permissions accordées)"); return }
        val client = HealthConnectClient.getOrCreate(this)
        lifecycleScope.launch {
            try {
                val r = HealthWriter.write(client, p)
                val extra = if (r.fallbackSegments > 0) " (${r.fallbackSegments} en « autre »)" else ""
                done("✓ Health Connect : séance, ${r.segments} segments$extra, ${r.hrSamples} pts FC")
            } catch (e: Exception) {
                done("Échec d'écriture Health Connect : ${e.javaClass.simpleName} ${e.message ?: ""}")
            }
        }
    }

    private fun done(msg: String) {
        Toast.makeText(applicationContext, msg, Toast.LENGTH_LONG).show()
        finish()
    }
}
