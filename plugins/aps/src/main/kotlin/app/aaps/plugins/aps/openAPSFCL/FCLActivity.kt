package app.aaps.plugins.aps.openAPSFCL

import android.content.Context
import android.content.SharedPreferences
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.Preferences
import org.joda.time.DateTime

class FCLActivity(
    private val preferences: Preferences,
    private val context: Context
) {

    data class ActivityResult(
        val percentage: Double,
        val targetAdjust: Double,
        val log: String,
        val retention: Int,
        val isActive: Boolean
    )

    private var consecutiveStepTriggers: Int = 0
    private var consecutiveLowTriggers: Int = 0
    private var lastStepUpdate: DateTime = DateTime.now()

    // ★★★ ROBUUSTE DATA VALIDATIE ★★★
    private val prefs: SharedPreferences = context.getSharedPreferences("FCL_Learning_Data", Context.MODE_PRIVATE)
    private val STEP_DATA_TIMEOUT_MINUTES = 15L // Timeout voor verouderde stapdata

    // ★★★ RETENTIE OPSLAG MET TIMESTAMP ★★★
    fun saveStapRetentie(retentie: Int) {
        prefs.edit().putInt("stap_retentie", retentie).apply()
        prefs.edit().putLong("last_retention_update", DateTime.now().millis).apply()
    }

    fun loadStapRetentie(): Int {
        return prefs.getInt("stap_retentie", 0)
    }

    // ★★★ NIEUW: Controleer of stapdata recent is ★★★
    private fun isStepDataRecent(lastUpdateTime: Long): Boolean {
        val minutesSinceUpdate = (DateTime.now().millis - lastUpdateTime) / (1000 * 60)
        return minutesSinceUpdate <= STEP_DATA_TIMEOUT_MINUTES
    }

    // ★★★ NIEUW: Auto-reset bij verouderde data ★★★
    private fun autoResetIfStale() {
        val lastUpdate = prefs.getLong("last_retention_update", 0)
        if (lastUpdate > 0) {
            val minutesSinceUpdate = (DateTime.now().millis - lastUpdate) / (1000 * 60)
            if (minutesSinceUpdate > STEP_DATA_TIMEOUT_MINUTES * 2) {
                // Data is te oud, reset retentie
                saveStapRetentie(0)
                consecutiveStepTriggers = 0
                consecutiveLowTriggers = 0
            }
        }
    }

    // ★★★ VERBETERDE HOOFDFUNCTIE MET DATA VALIDATIE ★★★
    fun berekenStappenAdjustment(
        DetermineStap5min: Int,
        DetermineStap30min: Int,
        lastStepUpdateTime: Long? = null
    ): ActivityResult {
        try {
            // ★★★ AUTO-RESET BIJ VEROUDERDE DATA ★★★
            autoResetIfStale()

            var StapRetentie = loadStapRetentie()
            var log_Stappen = ""
            var stap_perc = 100.0
            var stap_target_adjust = 0.0

            val Threshold5 = preferences.get(IntKey.stap_5minuten)
            val retentieStappen = preferences.get(IntKey.stap_retentie)

            // ★★★ DATA VALIDATIE ★★★
            val isDataValid = lastStepUpdateTime == null || isStepDataRecent(lastStepUpdateTime)

            if (!isDataValid) {
                log_Stappen += "⚠️ Verouderde stapdata - gebruik veilige modus\n"
                // Behoud huidige retentie maar forceer geen veranderingen
                return ActivityResult(
                    percentage = if (StapRetentie > 0) preferences.get(IntKey.stap_activiteteitPerc).toDouble() else 100.0,
                    targetAdjust = if (StapRetentie > 0) preferences.get(DoubleKey.stap_TT) else 0.0,
                    log = log_Stappen,
                    retention = StapRetentie,
                    isActive = StapRetentie > 0
                )
            }

            log_Stappen += "● 5 minutes: $DetermineStap5min steps\n"
            log_Stappen += "  Threshold: $Threshold5\n"
            log_Stappen += "  Retention: $StapRetentie/$retentieStappen\n"
            log_Stappen += "  Consecutive triggers: $consecutiveStepTriggers/2\n"
            log_Stappen += "  Low triggers: $consecutiveLowTriggers/2\n"

            val heeftVoldoendeStappen = DetermineStap5min > Threshold5

            if (heeftVoldoendeStappen) {
                consecutiveStepTriggers++
                consecutiveLowTriggers = 0

                val benodigdeTriggers = when {
                    StapRetentie == 0 -> 2  // Snellere initiële activering
                    else -> 1               // Behoud sneller
                }

                if (consecutiveStepTriggers >= benodigdeTriggers) {
                    val nieuweRetentie = (StapRetentie + 1).coerceAtMost(retentieStappen)
                    if (nieuweRetentie > StapRetentie) {
                        StapRetentie = nieuweRetentie
                        saveStapRetentie(StapRetentie)

                        when (StapRetentie) {
                            1 -> log_Stappen += "↗ Snelle activiteit gedetecteerd (${consecutiveStepTriggers} triggers)\n"
                            retentieStappen -> log_Stappen += "↗ Maximale retentie bereikt\n"
                            else -> log_Stappen += "↗ Retentie opbouw\n"
                        }
                    }
                } else {
                    log_Stappen += "→ Opbouw triggers ($consecutiveStepTriggers/$benodigdeTriggers)\n"
                }
            } else {
                consecutiveStepTriggers = 0
                consecutiveLowTriggers++

                log_Stappen += "→ Reset triggers (0/2), lage teller: $consecutiveLowTriggers/2\n"

                if (StapRetentie > 0) {
                    val shouldDecrease = when {
                        consecutiveLowTriggers >= 3 -> true
                        consecutiveLowTriggers >= 2 -> Math.random() < 0.8
                        else -> Math.random() < 0.5
                    }

                    if (shouldDecrease && StapRetentie > 0) {
                        StapRetentie = StapRetentie - 1
                        saveStapRetentie(StapRetentie)

                        when {
                            StapRetentie == 0 -> {
                                log_Stappen += "↘ Activiteit gestopt\n"
                                consecutiveLowTriggers = 0
                            }
                            else -> log_Stappen += "↘ Afbouw retentie\n"
                        }
                    } else if (StapRetentie > 0) {
                        log_Stappen += "→ Onder threshold maar behoud retentie\n"
                    }
                } else {
                    log_Stappen += "→ Onder threshold, geen actieve retentie\n"
                }
            }

            // ★★★ ACTIVITEIT TOEPASSEN ★★★
            val isActive = StapRetentie > 0
            if (isActive) {
                stap_perc = preferences.get(IntKey.stap_activiteteitPerc).toDouble()
                stap_target_adjust = preferences.get(DoubleKey.stap_TT)

                when (StapRetentie) {
                    1 -> log_Stappen += "● Initiële activiteit → Insulin $stap_perc% → Target: ${"%.1f".format(stap_target_adjust)} mmol/L\n"
                    2 -> log_Stappen += "● Medium retentie → Insulin $stap_perc% → Target: ${"%.1f".format(stap_target_adjust)} mmol/L\n"
                    else -> log_Stappen += "● Hoge retentie → Insulin $stap_perc% → Target: ${"%.1f".format(stap_target_adjust)} mmol/L\n"
                }
            } else {
                stap_perc = 100.0
                stap_target_adjust = 0.0
                log_Stappen += "● Geen activiteit → Insulin ${stap_perc.toInt()}% Target ${"%.1f".format(stap_target_adjust)}mmol/l\n"
            }

            // ★★★ UPDATE LAATSTE ACTIVITEIT ★★★
            lastStepUpdate = DateTime.now()

            return ActivityResult(stap_perc, stap_target_adjust, log_Stappen, StapRetentie, isActive)

        } catch (e: Exception) {
            return ActivityResult(100.0, 0.0, "Error in step calculation: ${e.message}", 0, false)
        }
    }

    // ★★★ NIEUW: Forceer reset van activiteit ★★★
    fun resetActivity() {
        saveStapRetentie(0)
        consecutiveStepTriggers = 0
        consecutiveLowTriggers = 0
    }

    // ★★★ NIEUW: Haal huidige retentie status op ★★★
    fun getCurrentActivityStatus(): String {
        val retention = loadStapRetentie()
        val maxRetention = preferences.get(IntKey.stap_retentie)
        return "Retentie: $retention/$maxRetention, Triggers: $consecutiveStepTriggers, Lage triggers: $consecutiveLowTriggers"
    }
}