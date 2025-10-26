package app.aaps.plugins.aps.openAPSFCL

import android.content.Context
import android.content.SharedPreferences
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.Preferences

class FCLActivity(
    private val preferences: Preferences,
    private val context: Context  // ← NIEUW: context parameter toegevoegd
) {

    data class ActivityResult(
        val percentage: Double,
        val targetAdjust: Double,
        val log: String
    )

    private var consecutiveStepTriggers: Int = 0

    // ★★★ NIEUW: SharedPreferences voor stap retentie ★★★
    private val prefs: SharedPreferences = context.getSharedPreferences("FCL_Learning_Data", Context.MODE_PRIVATE)

    // ★★★ NIEUW: Stap retentie opslag functies ★★★
    fun saveStapRetentie(retentie: Int) {
        prefs.edit().putInt("stap_retentie", retentie).apply()
    }

    fun loadStapRetentie(): Int {
        return prefs.getInt("stap_retentie", 0)
    }

    fun berekenStappenAdjustment(DetermineStap5min: Int, DetermineStap30min: Int): ActivityResult {
        try {
            var StapRetentie = loadStapRetentie()  // ← NIEUW: gebruik eigen functie
            var log_Stappen = ""
            var stap_perc = 100.0
            var stap_target_adjust = 0.0

            // ★★★ DYNAMISCHE THRESHOLDS ★★★
            val Threshold5 = preferences.get(IntKey.stap_5minuten)
            val retentieStappen = preferences.get(IntKey.stap_retentie)

            log_Stappen += "● 5 minutes: $DetermineStap5min steps\n"
            log_Stappen += "  Threshold: $Threshold5\n"
            log_Stappen += "  Retention: $StapRetentie/$retentieStappen\n"
            log_Stappen += "  Consecutive triggers: $consecutiveStepTriggers/3\n"

            // ★★★ ALLEEN 5-MINUTEN STAPPEN CHECK ★★★
            val heeftVoldoendeStappen = DetermineStap5min > Threshold5

            if (heeftVoldoendeStappen) {
                // Verhoog aaneengesloten triggers
                consecutiveStepTriggers++

                // ★★★ MINIMAAL 3 AANEENGESLOTEN TRIGGERS NODIG ★★★
                if (consecutiveStepTriggers >= 3) {
                    StapRetentie = (StapRetentie + 1).coerceAtMost(retentieStappen)
                    saveStapRetentie(StapRetentie)  // ← NIEUW: gebruik eigen functie

                    when (StapRetentie) {
                        1 -> log_Stappen += "↗ Initial activity detected (3+ consecutive triggers)\n"
                        retentieStappen -> log_Stappen += "↗ Maximum retention reached\n"
                        else -> log_Stappen += "↗ Building retention\n"
                    }
                } else {
                    log_Stappen += "→ Building up consecutive triggers ($consecutiveStepTriggers/3)\n"
                }
            } else {
                // ★★★ RESET AANEENGESLOTEN TRIGGERS BIJ ONVOLDOENDE STAPPEN ★★★
                if (consecutiveStepTriggers > 0) {
                    consecutiveStepTriggers = 0
                    log_Stappen += "→ Reset consecutive triggers (0/3)\n"
                }

                // Afbouw logica alleen als retentie al actief is
                if (StapRetentie > 0) {
                    val afbouwSnelheid = when {
                        StapRetentie >= 3 -> 0.3
                        StapRetentie == 2 -> 0.5
                        else -> 1.0
                    }

                    val shouldDecrease = Math.random() < afbouwSnelheid

                    if (shouldDecrease && StapRetentie > 0) {
                        StapRetentie = StapRetentie - 1
                        saveStapRetentie(StapRetentie)  // ← NIEUW: gebruik eigen functie
                        log_Stappen += when {
                            StapRetentie == 0 -> "↘ Activity stopped\n"
                            else -> "↘ Decreasing retention (slow decay)\n"
                        }
                    } else if (StapRetentie > 0) {
                        log_Stappen += "→ Below threshold but maintaining retention\n"
                    }
                } else {
                    log_Stappen += "→ Below threshold, no active retention\n"
                }
            }

            // ★★★ ACTIVITEIT TOEPASSEN ★★★
            if (StapRetentie > 0) {
                stap_perc = preferences.get(IntKey.stap_activiteteitPerc).toDouble()
                stap_target_adjust = preferences.get(DoubleKey.stap_TT)

                when (StapRetentie) {
                    1 -> log_Stappen += "● Initial activity → Insulin $stap_perc% → Target: ${"%.1f".format(stap_target_adjust)} mmol/L\n"
                    2 -> log_Stappen += "● Medium retention → Insulin $stap_perc% → Target: ${"%.1f".format(stap_target_adjust)} mmol/L\n"
                    else -> log_Stappen += "● High retention → Insulin $stap_perc% → Target: ${"%.1f".format(stap_target_adjust)} mmol/L\n"
                }
            } else {
                stap_perc = 100.0
                log_Stappen += "● No activity → Insulin ${stap_perc.toInt()}% Target ${"%.1f".format(stap_target_adjust)}mmol/l\n"
            }

            return ActivityResult(stap_perc, stap_target_adjust, log_Stappen)

        } catch (e: Exception) {
            // Foutafhandeling - return default values bij error
            return ActivityResult(100.0, 0.0, "Error in step calculation")
        }
    }
}