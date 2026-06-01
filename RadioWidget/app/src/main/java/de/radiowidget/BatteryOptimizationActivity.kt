package de.radiowidget

import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.content.Intent
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

class BatteryOptimizationActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            finish(); return
        }

        AlertDialog.Builder(this)
            .setTitle("Stream-Unterbrechungen verhindern")
            .setMessage(
                "Damit der Radio-Stream nicht unterbrochen wird, sind zwei Einstellungen empfohlen:\n\n" +
                "1. Im nächsten Bildschirm \"Nicht optimieren\" auswählen.\n\n" +
                "2. Danach unter Einstellungen → Apps → Radio Widget → Akku → " +
                "\"Nicht eingeschränkt\" auswählen (Samsung-spezifisch).\n\n" +
                "Diese Frage wird nicht erneut gestellt."
            )
            .setPositiveButton("Jetzt einstellen") { _, _ ->
                startActivity(Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName")
                ))
                finish()
            }
            .setNegativeButton("Überspringen") { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }
}
