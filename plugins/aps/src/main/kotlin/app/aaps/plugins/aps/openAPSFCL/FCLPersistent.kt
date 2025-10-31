package app.aaps.plugins.aps.openAPSFCL

import android.content.Context
import org.joda.time.DateTime
import org.joda.time.Minutes
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.Preferences
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.round

class FCLPersistent(
    private val preferences: Preferences,
    private val context: Context
) {

    // ★★★ PERSISTENT HIGH BG DATA CLASS ★★★
    data class PersistentResult(
        val extraBolus: Double,
        val log: String,
        val shouldDeliver: Boolean
    )

    // State tracking variables
    private val persistentLogHistory = mutableListOf<String>()
    private val MAX_LOG_HISTORY = 5

    private var lastPersistentBolusTime: DateTime? = null
    private var persistentBolusCount: Int = 0
    private var persistentGrens: Double = 0.6

    // ★★★ PERSISTENT HIGH BG DETECTIE ★★★
    fun checkPersistentHighBG(
        historicalData: List<BGDataPoint>,  // ← Nu herkend!
        currentIOB: Double,
        maxIOB: Double,
        isNachtTime: () -> Boolean
    ): PersistentResult {

        if (!preferences.get(BooleanKey.PersistentAanUit)) {
            return PersistentResult(0.0, "Persistent high BG correction uitgeschakeld", false)
        }

        val now = DateTime.now()

        // Cooldown check
        lastPersistentBolusTime?.let { lastTime ->
            val minutesSinceLast = Minutes.minutesBetween(lastTime, now).minutes
            if (minutesSinceLast < preferences.get(IntKey.persistent_CoolDown)) {
                return PersistentResult(0.0,
                                        "Persistent: Cooldown (${preferences.get(IntKey.persistent_CoolDown) - minutesSinceLast} min)",
                                        false)
            }
        }

        // Check voldoende data
        if (historicalData.size < 7) {
            return PersistentResult(0.0, "Persistent: Onvoldoende data", false)
        }

        val currentBG = historicalData.last().bg
        val delta5 = historicalData.last().bg - historicalData[historicalData.size - 2].bg
        val delta15 = historicalData.last().bg - historicalData[historicalData.size - 4].bg
        val delta30 = historicalData.last().bg - historicalData[historicalData.size - 7].bg

        val isNacht = isNachtTime()
        val persistentDrempel = if (isNacht) preferences.get(DoubleKey.persistent_Nachtdrempel) else preferences.get(DoubleKey.persistent_Dagdrempel)
        val maxBolus = if (isNacht) preferences.get(DoubleKey.persistent_Nacht_MaxBolus) else preferences.get(DoubleKey.persistent_Dag_MaxBolus)
        val dagDeel = if (isNacht) "NightTime" else "DayTime"

        var logEntries = mutableListOf<String>()
        logEntries.add("Persistent BG Analysis - $dagDeel")

        // Stabiliteitscheck
        val isStableHighBG = (abs(delta5) < persistentGrens &&
            abs(delta15) < persistentGrens + 0.5 &&
            abs(delta30) < persistentGrens + 1.0 &&
            currentBG > persistentDrempel)

        if (!isStableHighBG) {
            logEntries.add("No persistent high BG detected")
            logEntries.add("BG: ${"%.1f".format(currentBG)}, threshold: ${"%.1f".format(persistentDrempel)}")
            logEntries.add("Stability: 5min=${"%.1f".format(delta5)}, 15min=${"%.1f".format(delta15)}")
            return PersistentResult(0.0, logEntries.joinToString("\n"), false)
        }

        // ★★★ LINEAIRE PROGRESSIE TUSSEN TARGET EN TARGET+2 ★★★
        val bgAboveThreshold = currentBG - persistentDrempel
        val linearRange = 1.0 // mmol/L range voor lineaire progressie

        // Bereken lineaire factor (0.0 - 1.0) binnen het bereik persistentDrempel tot persistentDrempel+2
        val linearFactor = (bgAboveThreshold / linearRange).coerceIn(0.0, 1.0)

        // Basis bolus = lineaire progressie van 0 tot maxBolus
        val baseBolus = maxBolus * linearFactor

        logEntries.add("Persistent high BG detected!")
        logEntries.add("BG: ${"%.1f".format(currentBG)} > threshold: ${"%.1f".format(persistentDrempel)}")
        logEntries.add("BG above threshold: ${"%.1f".format(bgAboveThreshold)} mmol/L")
        logEntries.add("Linear factor: ${(linearFactor * 100).toInt()}%")
        logEntries.add("Base bolus: ${"%.2f".format(baseBolus)}U")

        // ★★★ IOB CORRECTIE ★★★
        val iobFactor = when {
            currentIOB > maxIOB * 0.75 -> 0.6
            currentIOB > maxIOB * 0.5 -> 0.7
            currentIOB > maxIOB * 0.25 -> 0.8
            currentIOB > maxIOB * 0.1 -> 0.9
            else -> 1.0
        }

        // ★★★ TOEPASSEN IOB CORRECTIE ★★★
        var calculatedBolus = baseBolus * iobFactor

        // Begrens tot max bolus (veiligheidsnet)
        val finalBolus = min(calculatedBolus, maxBolus)

        if (finalBolus < 0.1) {
            logEntries.add("No extra bolus: Too small after safety checks")
            logEntries.add("Berekend: ${"%.2f".format(calculatedBolus)}U, IOB: ${"%.1f".format(currentIOB)}")
            return PersistentResult(0.0, logEntries.joinToString("\n"), false)
        }

        // Update tracking
        persistentBolusCount++
        lastPersistentBolusTime = now

        logEntries.add("Extra bolus: ${"%.2f".format(finalBolus)}U")
        logEntries.add("Max allowed: ${"%.2f".format(maxBolus)}U")
        logEntries.add("IOB factor: ${(iobFactor * 100).toInt()}%")
        logEntries.add("Linear progress: ${(linearFactor * 100).toInt()}%")
        logEntries.add("Nr boluses: $persistentBolusCount")

        val logEntry = "${DateTime.now().toString("HH:mm")} | BG: ${"%.1f".format(currentBG)} | BOLUS: ${"%.2f".format(finalBolus)}U"
        persistentLogHistory.add(0, logEntry)
        if (persistentLogHistory.size > MAX_LOG_HISTORY) {
            persistentLogHistory.removeAt(persistentLogHistory.lastIndex)
        }

        return PersistentResult(roundDose(finalBolus), logEntries.joinToString("\n"), true)
    }

    // Helper functie voor status weergave
    fun getPersistentStatus(): String {
        var PersiOnOff = ""
        if (preferences.get(BooleanKey.PersistentAanUit)) {
            PersiOnOff = " - Persistent Bg detection switched ON"
        } else {
            PersiOnOff = " - Persistent Bg detection switched OFF"
        }

        return """
🔥 PERSISTENTE HOGE BG 
─────────────────────
${PersiOnOff}
• Dag drempel: ${"%.1f".format(preferences.get(DoubleKey.persistent_Dagdrempel))} mmol/L
• Nacht drempel: ${"%.1f".format(preferences.get(DoubleKey.persistent_Nachtdrempel))} mmol/L
• Max bolus: Dag ${"%.2f".format(preferences.get(DoubleKey.persistent_Dag_MaxBolus))}U, Nacht ${"%.2f".format(preferences.get(DoubleKey.persistent_Nacht_MaxBolus))}U
• Stabiliteitsgrens: ${"%.1f".format(persistentGrens)} mmol/L
• Cooldown: ${preferences.get(IntKey.persistent_CoolDown)} min

[ PERSISTENTE CHECKS (laatste ${MAX_LOG_HISTORY}) ]
${if (persistentLogHistory.isEmpty()) "Geen recente checks" else persistentLogHistory.joinToString("\n  ") { it.trim() }}
"""
    }

    // Helper functie voor dose rounding
    private fun roundDose(dose: Double): Double {
        return round(dose * 20) / 20
    }
}
