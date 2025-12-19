package app.aaps.plugins.aps.openAPSFCL

import android.content.Context
import android.content.SharedPreferences
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.Preferences
import org.joda.time.DateTime

/**
 * FCL Activity Module
 *
 * Verantwoordelijkheden:
 * - uitlezen stappen uit persistenceLayer
 * - retentiebeheer
 * - berekening ISF-percentage en target-aanpassing
 *
 * determineBasal roept slechts evaluate() aan
 */
class FCLActivityModule(
    private val preferences: Preferences,
    private val persistenceLayer: PersistenceLayer,
    context: Context
) {

    // ─────────────────────────────────────────
    // RESULT
    // ─────────────────────────────────────────

    data class ActivityResult(
        val insulinPercentage: Double, // bijv 80 = 80%
        val targetAdjust: Double,       // mmol/L
        val isActive: Boolean,
        val log: String
    )

    // ─────────────────────────────────────────
    // STATE
    // ─────────────────────────────────────────

    private var consecutiveStepTriggers = 0
    private var consecutiveLowTriggers = 0

    private val prefs: SharedPreferences =
        context.getSharedPreferences("FCL_Activity_State", Context.MODE_PRIVATE)

    private val STEP_DATA_TIMEOUT_MIN = 15L

    // ─────────────────────────────────────────
    // PUBLIC API (ENIGE INGANG)
    // ─────────────────────────────────────────

    fun evaluate(): ActivityResult {

        if (!preferences.get(BooleanKey.stappenAanUit)) {
            reset()
            return ActivityResult(
                insulinPercentage = 100.0,
                targetAdjust = 0.0,
                isActive = false,
                log = "Activity OFF"
            )
        }

        return try {
            val now = System.currentTimeMillis()
            val since = now - 60 * 60 * 1000

            val stepData = persistenceLayer.getStepsCountFromTime(since)

            if (stepData.isEmpty()) {
                return safeFallback("No step data")
            }

            val latest = stepData.last()

            calculateActivity(
                steps5min = latest.steps5min,
                lastUpdateTime = latest.timestamp
            )

        } catch (e: Exception) {
            safeFallback("Activity error: ${e.message}")
        }
    }

    // ─────────────────────────────────────────
    // CORE LOGICA (gebaseerd op jouw bestaande code)
    // ─────────────────────────────────────────

    private fun calculateActivity(
        steps5min: Int,
        lastUpdateTime: Long
    ): ActivityResult {

        val log = StringBuilder()
        var retention = loadRetention()

        val threshold5 = preferences.get(IntKey.stap_5minuten)
        val maxRetention = preferences.get(IntKey.stap_retentie)

        val minutesOld =
            (DateTime.now().millis - lastUpdateTime) / (1000 * 60)

        if (minutesOld > STEP_DATA_TIMEOUT_MIN) {
            log.append("⚠️ Step data stale ($minutesOld min)\n")
            return fallbackFromRetention(retention, log.toString())
        }

        log.append("Steps 5min=$steps5min threshold=$threshold5\n")
        log.append("Retention=$retention/$maxRetention\n")

        if (steps5min > threshold5) {
            consecutiveStepTriggers++
            consecutiveLowTriggers = 0

            val needed = if (retention == 0) 2 else 1

            if (consecutiveStepTriggers >= needed && retention < maxRetention) {
                retention++
                saveRetention(retention)
                log.append("↗ Retention increased to $retention\n")
            }
        } else {
            consecutiveStepTriggers = 0
            consecutiveLowTriggers++

            if (retention > 0 && consecutiveLowTriggers >= 2) {
                retention--
                saveRetention(retention)
                log.append("↘ Retention decreased to $retention\n")
            }
        }

        val isActive = retention > 0

        val insulinPerc =
            if (isActive) preferences.get(IntKey.stap_activiteteitPerc).toDouble()
            else 100.0

        val targetAdj =
            if (isActive) preferences.get(DoubleKey.stap_TT)
            else 0.0

        log.append(
            if (isActive)
                "Activity ACTIVE → Insulin $insulinPerc% Target +$targetAdj mmol/L\n"
            else
                "Activity INACTIVE\n"
        )

        return ActivityResult(
            insulinPercentage = insulinPerc,
            targetAdjust = targetAdj,
            isActive = isActive,
            log = log.toString()
        )
    }

    // ─────────────────────────────────────────
    // FALLBACKS & STATE
    // ─────────────────────────────────────────

    private fun safeFallback(reason: String): ActivityResult =
        ActivityResult(
            insulinPercentage = 100.0,
            targetAdjust = 0.0,
            isActive = false,
            log = reason
        )

    private fun fallbackFromRetention(retention: Int, prefix: String): ActivityResult {
        val active = retention > 0
        return ActivityResult(
            insulinPercentage =
                if (active) preferences.get(IntKey.stap_activiteteitPerc).toDouble() else 100.0,
            targetAdjust =
                if (active) preferences.get(DoubleKey.stap_TT) else 0.0,
            isActive = active,
            log = prefix
        )
    }

    private fun loadRetention(): Int =
        prefs.getInt("retention", 0)

    private fun saveRetention(value: Int) {
        prefs.edit().putInt("retention", value).apply()
    }

    private fun reset() {
        saveRetention(0)
        consecutiveStepTriggers = 0
        consecutiveLowTriggers = 0
    }
}
