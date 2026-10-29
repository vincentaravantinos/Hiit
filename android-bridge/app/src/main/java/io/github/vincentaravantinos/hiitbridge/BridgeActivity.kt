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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
        get() = writePermissions + readPermissions + HealthPermission.getReadPermission(ExerciseSessionRecord::class) +
            (if (backgroundAvailable) setOf(HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND) else emptySet())

    private lateinit var requestPermissions: ActivityResultLauncher<Set<String>>
    private lateinit var requestBtConnect: ActivityResultLauncher<String>
    private lateinit var enableBt: ActivityResultLauncher<Intent>
    private var pending: SessionPayload? = null
    private var setupOnly = false
    private var spotify: SpotifyStarter? = null

    // Le délai de garde ne court que pendant que le pont est au premier plan : il est suspendu
    // tant que l'écran d'autorisation Spotify (première fois) est affiché.
    override fun onResume() { super.onResume(); spotify?.armTimeout() }
    override fun onPause() { super.onPause(); spotify?.disarmTimeout() }
    override fun onDestroy() { spotify?.cancel(); super.onDestroy() }

    @Deprecated("Requis par la bibliothèque d'autorisation Spotify")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == SpotifyStarter.AUTH_REQUEST) spotify?.onAuthResult(resultCode, data)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestPermissions = registerForActivityResult(
            PermissionController.createRequestPermissionResultContract()
        ) { granted ->
            if (granted.containsAll(writePermissions) || pending == null) proceed(granted)
            else done("Permissions Health Connect refusées — rien n'a été écrit.")
        }
        requestBtConnect = registerForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
        ) { ok -> if (ok) askEnableBt() else openBtSettings() }
        enableBt = registerForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
        ) { r ->
            if (r.resultCode == RESULT_OK) done("✓ Bluetooth activé") else finish()
        }
        handle(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun btAdapter(): android.bluetooth.BluetoothAdapter? =
        (getSystemService(BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager)?.adapter

    // Fenêtre système « HIIT Bridge veut activer le Bluetooth » : un seul tap sur Autoriser.
    @Suppress("MissingPermission")
    private fun askEnableBt() {
        try { enableBt.launch(Intent(android.bluetooth.BluetoothAdapter.ACTION_REQUEST_ENABLE)) }
        catch (e: Exception) { openBtSettings() }
    }
    // Repli : l'écran Bluetooth des paramètres, directement.
    private fun openBtSettings() {
        try {
            startActivity(Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            Toast.makeText(this, "Active le Bluetooth, puis reviens à l'app HIIT", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Impossible d'ouvrir les réglages Bluetooth : ${e.message}", Toast.LENGTH_LONG).show()
        }
        finish()
    }

    private fun handle(intent: Intent?) {
        // hiitbridge://bluetooth : allumer le Bluetooth du téléphone (29/10).
        if (intent?.data?.host == "bluetooth") {
            val a = btAdapter()
            when {
                a == null -> done("Ce téléphone n'a pas de Bluetooth.")
                a.isEnabled -> done("✓ Bluetooth déjà activé")
                android.os.Build.VERSION.SDK_INT >= 31 &&
                    checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) != android.content.pm.PackageManager.PERMISSION_GRANTED ->
                    requestBtConnect.launch(android.Manifest.permission.BLUETOOTH_CONNECT)
                else -> askEnableBt()
            }
            return
        }
        // hiitbridge://chrome-reset?pkg=… : ouvrir la fiche système de Chrome (bouton « Forcer l'arrêt »).
        // Android 14+ interdit à une app d'arrêter une autre app : c'est le plus court chemin possible.
        if (intent?.data?.host == "chrome-reset") {
            val pkg = intent.data?.getQueryParameter("pkg")?.takeIf { it.isNotBlank() } ?: "com.android.chrome"
            try {
                startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    android.net.Uri.parse("package:$pkg")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                Toast.makeText(this, "Touche « Forcer l'arrêt », puis rouvre l'app HIIT", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(this, "Impossible d'ouvrir la fiche de Chrome : ${e.message}", Toast.LENGTH_LONG).show()
            }
            finish(); return
        }
        // hiitbridge://spotify?cid=…&uri=… : lancer la playlist au démarrage d'une séance.
        if (intent?.data?.host == "spotify") {
            val cid = intent.data?.getQueryParameter("cid")
            val uri = intent.data?.getQueryParameter("uri")
            if (cid.isNullOrBlank() || uri.isNullOrBlank()) { done("Spotify : réglages incomplets."); return }
            spotify?.cancel()
            spotify = SpotifyStarter(this, cid, uri) { spotify = null; finish() }.also { it.start() }
            return
        }
        // hiitbridge://oura?cid=…&secret=…&code=…&redirect=… : fin de l'autorisation Oura (OAuth).
        if (intent?.data?.host == "oura") {
            val q = intent.data!!
            val cid = q.getQueryParameter("cid"); val secret = q.getQueryParameter("secret")
            val code = q.getQueryParameter("code"); val redirect = q.getQueryParameter("redirect")
            if (cid.isNullOrBlank() || secret.isNullOrBlank() || code.isNullOrBlank() || redirect.isNullOrBlank()) {
                done("Oura : paramètres incomplets."); return
            }
            lifecycleScope.launch {
                val msg = withContext(Dispatchers.IO) {
                    try {
                        OuraSync.connect(this@BridgeActivity, cid, secret, code, redirect)
                        try {
                            HealthSync.run(this@BridgeActivity)
                            val err = getSharedPreferences("hiit-bridge", MODE_PRIVATE).getString("ouraErr", null)
                            if (err == null) "✓ Oura connecté — scores synchronisés" else "Oura connecté, mais lecture impossible : $err"
                        } catch (e: Exception) {
                            "✓ Oura connecté (synchro à la prochaine ouverture : ${e.message ?: e.javaClass.simpleName})"
                        }
                    } catch (e: Exception) {
                        "Connexion Oura impossible : ${e.message ?: e.javaClass.simpleName}"
                    }
                }
                // Fenêtre plutôt qu'un toast : le message d'erreur d'Oura doit être lisible en entier.
                android.app.AlertDialog.Builder(this@BridgeActivity)
                    .setTitle("Oura")
                    .setMessage(msg)
                    .setPositiveButton("OK") { _, _ -> finish() }
                    .setOnCancelListener { finish() }
                    .show()
            }
            return
        }
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
                            (if (canReadInBackground) ", relevé auto chaque matin dès 6h" else "")
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
