package io.github.vincentaravantinos.hiitbridge

import android.app.Activity
import android.os.Bundle
import android.widget.TextView

class PrivacyActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val tv = TextView(this)
        tv.setPadding(48, 96, 48, 48)
        tv.textSize = 16f
        tv.text = "HIIT Bridge écrit dans Health Connect les séances enregistrées par l'app HIIT " +
            "(séance, exercices et répétitions, fréquence cardiaque, calories actives). " +
            "Aucune donnée n'est envoyée ailleurs : tout reste sur ce téléphone."
        setContentView(tv)
    }
}
