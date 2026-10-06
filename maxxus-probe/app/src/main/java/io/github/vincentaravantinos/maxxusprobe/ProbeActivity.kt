package io.github.vincentaravantinos.maxxusprobe

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

@SuppressLint("MissingPermission", "SetTextI18n")
class ProbeActivity : Activity() {

    private val SPP: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var logView: TextView
    private lateinit var scroll: ScrollView
    private lateinit var deviceList: LinearLayout
    private val adapter: BluetoothAdapter? by lazy {
        (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    }
    private val found = LinkedHashMap<String, BluetoothDevice>()
    private var target: BluetoothDevice? = null
    private var socket: BluetoothSocket? = null
    private var out: OutputStream? = null
    @Volatile private var polling = false
    private val t0 = System.currentTimeMillis()

    // ---------- UI ----------
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 48, 24, 24) }
        root.addView(TextView(this).apply {
            text = "Maxxus Probe — allume la console, ferme les autres apps, puis « Chercher »."
            textSize = 15f
        })
        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row1.addView(btn("Chercher") { startDiscovery() })
        row1.addView(btn("Appairer 0000") { pairWithPin() })
        row1.addView(btn("Copier log") { copyLog() })
        root.addView(row1)
        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row2.addView(btn("Connecter") { connect() })
        row2.addView(btn("Protocole iConsole") { runIConsole() })
        row2.addView(btn("Stop") { disconnect() })
        root.addView(row2)
        deviceList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(deviceList)
        logView = TextView(this).apply { typeface = Typeface.MONOSPACE; textSize = 11f; setTextIsSelectable(true) }
        scroll = ScrollView(this).apply { addView(logView) }
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        registerReceiver(receiver, IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
            addAction(BluetoothDevice.ACTION_PAIRING_REQUEST)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        })
        log("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}")
        askPermissions()
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(receiver) } catch (_: Exception) {}
        disconnect()
    }

    private fun btn(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label; isAllCaps = false; setOnClickListener { onClick() }
    }

    private fun log(s: String) {
        val t = "%6.1f".format((System.currentTimeMillis() - t0) / 1000.0)
        ui.post {
            logView.append("[$t] $s\n")
            scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }

    private fun copyLog() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("maxxus-probe", logView.text))
        log("Log copié dans le presse-papiers")
    }

    private fun askPermissions() {
        val perms = if (Build.VERSION.SDK_INT >= 31)
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        requestPermissions(perms, 1)
    }

    override fun onRequestPermissionsResult(rc: Int, p: Array<out String>, r: IntArray) {
        super.onRequestPermissionsResult(rc, p, r)
        p.forEachIndexed { i, n -> log("Permission ${n.substringAfterLast('.')}: ${if (r.getOrNull(i) == 0) "OK" else "REFUSÉE"}") }
        listBonded()
    }

    // ---------- Discovery ----------
    private fun typeName(d: BluetoothDevice) = when (d.type) {
        BluetoothDevice.DEVICE_TYPE_CLASSIC -> "classic"
        BluetoothDevice.DEVICE_TYPE_LE -> "BLE"
        BluetoothDevice.DEVICE_TYPE_DUAL -> "dual"
        else -> "?"
    }

    private fun listBonded() {
        val b = adapter?.bondedDevices ?: return
        log("${b.size} appareil(s) déjà appairé(s)")
        b.forEach { addDevice(it, "appairé") }
    }

    private fun startDiscovery() {
        val a = adapter ?: run { log("Pas d'adaptateur Bluetooth"); return }
        if (!a.isEnabled) { log("Bluetooth désactivé"); return }
        if (a.isDiscovering) a.cancelDiscovery()
        log("Recherche (≈12 s)…")
        log(if (a.startDiscovery()) "Recherche lancée" else "startDiscovery a échoué (permissions ?)")
    }

    private fun addDevice(d: BluetoothDevice, why: String) {
        val name = d.name ?: "(sans nom)"
        if (found.containsKey(d.address)) return
        found[d.address] = d
        log("Trouvé [$why]: $name  ${d.address}  type=${typeName(d)}")
        val isFal = name.uppercase().startsWith("FAL")
        if (isFal && target == null) { target = d; log(">>> Cible sélectionnée : $name") }
        ui.post {
            deviceList.addView(btn("${if (isFal) "★ " else ""}$name  (${typeName(d)})") {
                target = d; log("Cible : $name ${d.address}")
            })
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                BluetoothDevice.ACTION_FOUND -> dev(i)?.let { addDevice(it, "scan") }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> log("Recherche terminée")
                BluetoothDevice.ACTION_PAIRING_REQUEST -> {
                    val d = dev(i)
                    val variant = i.getIntExtra(BluetoothDevice.EXTRA_PAIRING_VARIANT, -1)
                    log("Demande d'appairage de ${d?.name}, variante=$variant")
                    try {
                        val ok = d?.setPin("0000".toByteArray())
                        log("setPin(0000) → $ok")
                    } catch (e: Exception) { log("setPin erreur: ${e.message}") }
                }
                BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                    val s = i.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1)
                    log("État d'appairage ${dev(i)?.name}: " + when (s) { 10 -> "aucun"; 11 -> "en cours"; 12 -> "APPAIRÉ"; else -> "$s" })
                }
            }
        }
        @Suppress("DEPRECATION")
        private fun dev(i: Intent): BluetoothDevice? =
            if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            else i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
    }

    private fun pairWithPin() {
        val d = target ?: run { log("Pas de cible — lance « Chercher » d'abord"); return }
        adapter?.cancelDiscovery()
        log("createBond(${d.name}) → ${d.createBond()}")
    }

    // ---------- Connection ----------
    private fun connect() {
        val d = target ?: run { log("Pas de cible — lance « Chercher » d'abord"); return }
        adapter?.cancelDiscovery()
        Thread {
            disconnectQuiet()
            try { d.fetchUuidsWithSdp() } catch (_: Exception) {}
            log("UUIDs connus : ${d.uuids?.joinToString() ?: "aucun"}")
            val attempts: List<Pair<String, () -> BluetoothSocket>> = listOf(
                "SPP non sécurisé (sans appairage)" to { d.createInsecureRfcommSocketToServiceRecord(SPP) },
                "SPP sécurisé" to { d.createRfcommSocketToServiceRecord(SPP) },
                "RFCOMM canal 1 non sécurisé" to {
                    d.javaClass.getMethod("createInsecureRfcommSocket", Int::class.javaPrimitiveType).invoke(d, 1) as BluetoothSocket
                },
                "RFCOMM canal 1" to {
                    d.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType).invoke(d, 1) as BluetoothSocket
                },
            )
            for ((label, make) in attempts) {
                try {
                    log("Essai : $label…")
                    val s = make()
                    s.connect()
                    socket = s; out = s.outputStream
                    log(">>> CONNECTÉ via $label")
                    startReader(s.inputStream)
                    return@Thread
                } catch (e: Exception) {
                    log("   échec : ${e.javaClass.simpleName}: ${e.message}")
                }
            }
            log(">>> Aucune méthode n'a fonctionné")
        }.start()
    }

    private fun startReader(input: InputStream) = Thread {
        val buf = ByteArray(256)
        try {
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                log("RX ${hex(buf, n)}")
            }
        } catch (e: Exception) { log("Lecture terminée : ${e.message}") }
    }.start()

    private fun send(bytes: ByteArray) {
        try { out?.write(bytes); out?.flush(); log("TX ${hex(bytes, bytes.size)}") }
        catch (e: Exception) { log("TX erreur : ${e.message}") }
    }

    /** Trame iConsole : F0, commande, 01, 01, [données…], somme modulo 256. */
    private fun frame(vararg b: Int): ByteArray {
        val body = intArrayOf(0xF0) + b
        return (body + (body.sum() and 0xFF)).map { it.toByte() }.toByteArray()
    }

    private fun runIConsole() {
        if (socket == null) { log("Pas connecté"); return }
        Thread {
            log("--- Séquence d'initialisation iConsole ---")
            val init = listOf(
                frame(0xA0, 0x01, 0x01), frame(0xA5, 0x01, 0x01, 0x02),
                frame(0xA0, 0x01, 0x01), frame(0xA1, 0x01, 0x01),
                frame(0xA0, 0x01, 0x01), frame(0xA3, 0x01, 0x01, 0x01),
                frame(0xA4, 0x01, 0x01, 0x01),
            )
            for (f in init) { send(f); Thread.sleep(400) }
            log("--- Interrogation toutes les 0,5 s : pédale maintenant ---")
            polling = true
            var n = 0
            while (polling && socket != null && n < 120) {
                send(frame(0xA2, 0x01, 0x01)); n++
                Thread.sleep(500)
            }
            log("--- Fin de l'interrogation ---")
        }.start()
    }

    private fun disconnectQuiet() {
        polling = false
        try { socket?.close() } catch (_: Exception) {}
        socket = null; out = null
    }

    private fun disconnect() { if (socket != null) log("Déconnexion"); disconnectQuiet() }

    private fun hex(b: ByteArray, n: Int) = (0 until n).joinToString(" ") { "%02x".format(b[it]) }
}
