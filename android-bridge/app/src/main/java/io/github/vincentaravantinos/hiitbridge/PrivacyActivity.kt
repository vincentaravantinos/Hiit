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
            "(séance, exercices et répétitions, fréquence cardiaque, calories actives), et lit " +
            "sommeil, HRV, FC de repos et poids pour conseiller la séance du jour. " +
            "Les données lues sont chiffrées (AES-256) sur le téléphone avant d'être déposées " +
            "dans le dépôt GitHub de l'app ; seule l'app HIIT possède la clé pour les relire."
        setContentView(tv)
    }
}
