package app.aaps.plugins.aps.openAPSFCL

import org.joda.time.DateTime
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.max
import kotlin.math.sign
import app.aaps.core.keys.Preferences
import app.aaps.core.keys.*
import org.joda.time.Minutes

/**
 * A3.2 – Pure advisor engine
 * Geen side-effects, geen prefs writes
 */
class FCLAdvisorAdv(
    private val preferences: Preferences
) {

   // =========================
// Telemetrie (A4)
// =========================
    data class AdvisorTelemetry(
        val timestamp: DateTime,
        val adviceCount: Int,
        val parametersAdvised: Set<String>
   )

    // =========================
    // Interne state per parameter
    // =========================
    private data class ParameterState(
        val name: String,
        var cumulativeConfidence: Double = 0.0,
        var eventCount: Int = 0,
        var lastDirection: String = "STABLE"
    )

    private val states = mutableMapOf<String, ParameterState>()
    private var lastTelemetry: AdvisorTelemetry? = null
    private var lastGeneratedAdvice: List<FCLMetrics.ParameterAdvice> = emptyList()

    private var lastMeal: FCLMetrics.MealPerformanceMetrics? = null
    private var lastAdvice: List<FCLMetrics.ParameterAdvice> = emptyList()
    private var lastAdviceTime: DateTime? = null
    private var lastEvaluationTime: DateTime? = null


    fun onMealCompleted(meal: FCLMetrics.MealPerformanceMetrics) {
        val advice = generateAdvice(meal)

        if (advice.isNotEmpty()) {
            lastGeneratedAdvice = advice
            lastAdviceTime = DateTime.now()
        }
    }


    // =========================
    // State evaluatie helpers
    // =========================
    private fun shouldEmit(state: ParameterState): Boolean {
        return state.eventCount >= 2 && state.cumulativeConfidence >= 1.2
    }

    private fun resetState(state: ParameterState) {
        state.cumulativeConfidence = 0.0
        state.eventCount = 0
        state.lastDirection = "STABLE"
    }


    private fun state(name: String): ParameterState =
        states.getOrPut(name) { ParameterState(name) }

    fun registerMeal(meal: FCLMetrics.MealPerformanceMetrics) {
        lastMeal = meal
    }

    fun registerAdvice(advice: List<FCLMetrics.ParameterAdvice>) {
        if (advice.isNotEmpty()) {
            lastAdvice = advice
            lastAdviceTime = DateTime.now()
        }
    }

    fun getNextAdviceTimeFormatted(preferences: Preferences): String {
        val intervalHours = preferences.get(IntKey.Advice_Interval_Hours)
        val now = DateTime.now()

        // 🔑 Vast ankerpunt
        val baseTime =
            lastAdviceTime
                ?: lastEvaluationTime
                ?: return "--:--"   // nog nooit geëvalueerd

        var next = baseTime.plusHours(intervalHours)
        while (next.isBefore(now)) {
            next = next.plusHours(intervalHours)
        }

        val minutes = Minutes.minutesBetween(now, next)
            .minutes
            .coerceAtLeast(0)

        val h = minutes / 60
        val m = minutes % 60

        return String.format("%02d:%02d", h, m)
    }


    // =========================
    // Publieke API
    // =========================
    fun generateAdvice(
        meal: FCLMetrics.MealPerformanceMetrics,
        optimizationWeight: Double = 1.0
    ): List<FCLMetrics.ParameterAdvice> {
        lastEvaluationTime = DateTime.now()
        val advice = mutableListOf<FCLMetrics.ParameterAdvice>()
        val now = DateTime.now()

        analyzeRisingPhase(meal, advice, optimizationWeight, now)
        analyzePlateauPhase(meal, advice, optimizationWeight, now)
        analyzeDetection(meal, advice, optimizationWeight, now)
        analyzeSafety(meal, advice, optimizationWeight, now)

        val finalAdvice = consolidate(advice)

        // A3.21 — telemetry vastleggen
        lastTelemetry = AdvisorTelemetry(
            timestamp = now,
            adviceCount = finalAdvice.size,
            parametersAdvised = finalAdvice.map { it.parameterName }.toSet()
        )
        lastGeneratedAdvice = finalAdvice
        return finalAdvice
    }

    // =========================
// A3.9 – Publieke entrypoint
// =========================
    fun generateAdviceForMeal(
        meal: FCLMetrics.MealPerformanceMetrics
    ): List<FCLMetrics.ParameterAdvice> {

        // centrale plek voor eventuele globale checks
        // (bijv. min aantal maaltijden, debug flags, etc.)

        return generateAdvice(
            meal = meal,
            optimizationWeight = 1.0
        )
    }


    // =========================
    // Analyseblokken
    // =========================

    private fun analyzeRisingPhase(
        meal: FCLMetrics.MealPerformanceMetrics,
        out: MutableList<FCLMetrics.ParameterAdvice>,
        w: Double,
        ts: DateTime
    ) {
        // Hoge piek
        if (meal.peakBG > 9.5) {
            val severity = min(1.0, (meal.peakBG - 9.5) / 3.0)
            val confidence = (0.4 + severity * 0.5) * w

            add(
                out,
                "bolus_perc_rising",
                meal,
                +0.10 * severity,
                "INCREASE",
                "Hoge piek ${meal.peakBG}",
                confidence,
                ts
            )
        }

        // Eerste bolus te laat
        if (meal.timeToFirstBolus > 20) {
            val severity = min(1.0, (meal.timeToFirstBolus - 20) / 40.0)
            val confidence = (0.3 + severity * 0.5) * w

            add(
                out,
                "phase_rising_slope",
                meal,
                -0.12 * severity,
                "DECREASE",
                "Eerste bolus ${meal.timeToFirstBolus}min na start",
                confidence,
                ts
            )
        }
    }

    private fun analyzePlateauPhase(
        meal: FCLMetrics.MealPerformanceMetrics,
        out: MutableList<FCLMetrics.ParameterAdvice>,
        w: Double,
        ts: DateTime
    ) {
        if (meal.rapidDeclineDetected && meal.declineRate != null) {
            val severity = min(1.0, abs(meal.declineRate!!) / 3.0)
            val confidence = (0.4 + severity * 0.4) * w

            add(
                out,
                "phase_plateau_slope",
                meal,
                +0.10 * severity,
                "INCREASE",
                "Snelle daling na plateau",
                confidence,
                ts
            )
        }
    }

    private fun analyzeDetection(
        meal: FCLMetrics.MealPerformanceMetrics,
        out: MutableList<FCLMetrics.ParameterAdvice>,
        w: Double,
        ts: DateTime
    ) {
        val detectionScore =
            (if (meal.timeToFirstBolus in 10..25) 0.4 else 0.2) +
                (if (meal.peakBG in 7.0..10.0) 0.4 else 0.2) +
                (if (!meal.postMealHypo) 0.2 else 0.0)

        if (detectionScore < 0.7) {
            val confidence = (0.5 + (0.7 - detectionScore)) * w

            add(
                out,
                "meal_detection_sensitivity",
                meal,
                -0.10,
                "DECREASE",
                "Detectiescore ${"%.0f".format(detectionScore * 100)}%",
                confidence,
                ts
            )
        }
    }

    private fun analyzeSafety(
        meal: FCLMetrics.MealPerformanceMetrics,
        out: MutableList<FCLMetrics.ParameterAdvice>,
        w: Double,
        ts: DateTime
    ) {
        if (meal.postMealHypo || meal.virtualHypoScore > 2.0) {
            val confidence = (0.5 + min(0.5, meal.virtualHypoScore / 4.0)) * w

            add(
                out,
                "IOB_corr_perc",
                meal,
                +0.10,
                "INCREASE",
                "Hypo-risico na maaltijd",
                confidence,
                ts
            )
        }
    }

    // =========================
// A3.3 – Parameter metadata
// =========================

    private data class ParameterDef(
        val min: Double,
        val max: Double,
        val maxRelativeChange: Double
    )

    private val parameterDefs = mapOf(
        "bolus_perc_rising"      to ParameterDef(20.0, 200.0, 0.15),
        "bolus_perc_plateau"     to ParameterDef(20.0, 150.0, 0.15),
        "bolus_perc_day"         to ParameterDef(20.0, 200.0, 0.10),
        "phase_rising_slope"     to ParameterDef(0.2, 3.0,   0.25),
        "phase_plateau_slope"    to ParameterDef(0.1, 1.5,   0.25),
        "meal_detection_sensitivity" to ParameterDef(0.05, 0.5, 0.20),
        "IOB_corr_perc"          to ParameterDef(60.0, 140.0, 0.10)
    )

    private fun getCurrentValue(param: String): Double {
        return try {
            when (param) {
                "bolus_perc_rising" ->
                    preferences.get(IntKey.bolus_perc_rising).toDouble()

                "bolus_perc_plateau" ->
                    preferences.get(IntKey.bolus_perc_plateau).toDouble()

                "bolus_perc_day" ->
                    preferences.get(IntKey.bolus_perc_day).toDouble()

                "phase_rising_slope" ->
                    preferences.get(DoubleKey.phase_rising_slope)

                "phase_plateau_slope" ->
                    preferences.get(DoubleKey.phase_plateau_slope)

                "meal_detection_sensitivity" ->
                    preferences.get(DoubleKey.meal_detection_sensitivity)

                "IOB_corr_perc" ->
                    preferences.get(IntKey.IOB_corr_perc).toDouble()

                else -> 0.0
            }
        } catch (_: Exception) {
            0.0
        }
    }




    // =========================
    // Helper
    // =========================
    private fun add(
        out: MutableList<FCLMetrics.ParameterAdvice>,
        param: String,
        meal: FCLMetrics.MealPerformanceMetrics,
        relativeChange: Double,
        direction: String,
        reason: String,
        confidence: Double,
        ts: DateTime
    ) {
        val def = parameterDefs[param] ?: return
        val current = getCurrentValue(param)

// ruwe voorstel
        val rawRecommended = current * (1.0 + relativeChange)

// max relatieve wijziging
        val maxDelta = current * def.maxRelativeChange
        val limitedRecommended =
            rawRecommended.coerceIn(
                current - maxDelta,
                current + maxDelta
            )

// absolute min/max
        val recommended =
            limitedRecommended.coerceIn(def.min, def.max)


        val s = state(param)
        s.cumulativeConfidence += confidence
        s.eventCount++
        // A3.5 — Richtingstabiliteit (anti flip-flop)
        // Als richting wisselt t.o.v. vorige event → demp confidence
        if (s.lastDirection != "STABLE" && s.lastDirection != direction) {
            s.cumulativeConfidence *= 0.6
        }
        s.lastDirection = direction

        if (shouldEmit(s)) {
            out += FCLMetrics.ParameterAdvice(
                parameterName = param,
                currentValue = current,
                recommendedValue = recommended,
                reason = reason,
                confidence = min(1.0, s.cumulativeConfidence / s.eventCount),
                direction = direction
            )
            resetState(s)
        }
    }

    // =========================
// A3.4 – Consolidatie & conflict-resolutie
// =========================
    private fun consolidate(
        input: List<FCLMetrics.ParameterAdvice>
    ): List<FCLMetrics.ParameterAdvice> {

        // ---------- Stap 1: standaard consolidatie ----------
        val result = input
            .groupBy { it.parameterName }
            .mapNotNull { (_, advices) ->

                val valid = advices.filter { it.confidence >= 0.25 }
                if (valid.isEmpty()) return@mapNotNull null
                if (valid.size == 1) return@mapNotNull valid.first()

                val directions = valid.groupBy { it.direction }

                // conflicterend → hoogste confidence wint
                if (directions.size > 1) {
                    return@mapNotNull valid.maxByOrNull { it.confidence }
                }

                val ref = valid.first()
                val totalWeight = valid.sumOf { it.confidence }
                val weightedValue =
                    valid.sumOf { it.recommendedValue * it.confidence } / totalWeight

                ref.copy(
                    recommendedValue = weightedValue,
                    confidence = min(1.0, totalWeight / valid.size)
                )
            }
            .toMutableList()

        // ---------- Stap 2: A3.6 dag / rising / plateau coherentie ----------
        val rising = result.find { it.parameterName == "bolus_perc_rising" }
        val plateau = result.find { it.parameterName == "bolus_perc_plateau" }
        val day = result.find { it.parameterName == "bolus_perc_day" }

        if (day != null && rising != null && plateau != null) {

            // relatieve wijzigingen
            val dR = (rising.recommendedValue - rising.currentValue) / rising.currentValue
            val dP = (plateau.recommendedValue - plateau.currentValue) / plateau.currentValue

            // alleen als richting gelijk is
            if (dR.sign == dP.sign) {

                val small = min(abs(dR), abs(dP)) * dR.sign
                val bigR = dR - small
                val bigP = dP - small

                // dag krijgt kleinste gezamenlijke wijziging
                val newDay = day.currentValue * (1.0 + small)

                result.remove(day)
                result += day.copy(
                    recommendedValue = newDay,
                    confidence = min(day.confidence, 0.6),
                    reason = day.reason + " | afgestemd op rising/plateau"
                )

                // rising/plateau krijgen rest
                result.remove(rising)
                result += rising.copy(
                    recommendedValue = rising.currentValue * (1.0 + bigR)
                )

                result.remove(plateau)
                result += plateau.copy(
                    recommendedValue = plateau.currentValue * (1.0 + bigP)
                )
            }
            // anders: tegengestelde richting → niets doen
        }

        return result
    }

    // =========================
    // A3.21 — Telemetry access
    // =========================
    fun getLastTelemetry(): AdvisorTelemetry? = lastTelemetry
    fun getLastGeneratedAdvice(): List<FCLMetrics.ParameterAdvice> = lastGeneratedAdvice

    // ======================================================
    // UI — Advies build string
    // ======================================================
// ======================================================
// UI — Advies build string (met telemetrie)
// ======================================================
    fun buildAdviceUI(
        lastMeal: FCLMetrics.MealPerformanceMetrics?,
        advice: List<FCLMetrics.ParameterAdvice>,
        nextAdviceIn: String
    ): String {

        val telemetry = getLastTelemetry()

        fun line(label: String, from: Double, to: Double): String =
            "• ${label.padEnd(22)} ${"%.2f".format(from)} → ${"%.2f".format(to)}"

        return buildString {
            appendLine("📊 Parameter advies")
            appendLine("────────────────────")

            // =========================
            // Telemetrie (altijd tonen)
            // =========================
            if (telemetry != null) {
                appendLine()
                appendLine("🧠 Advisor status")
                appendLine("• Engine: FCLAdvisorAdv (nieuw)")
                appendLine("• Laatste run: ${telemetry.timestamp.toString("HH:mm:ss")}")
                appendLine("• Adviezen: ${telemetry.adviceCount}")

                if (telemetry.parametersAdvised.isNotEmpty()) {
                    appendLine(
                        "• Parameters: ${
                            telemetry.parametersAdvised.joinToString(", ")
                        }"
                    )
                }
            } else {
                appendLine()
                appendLine("🧠 Advisor status")
                appendLine("• Engine: FCLAdvisorAdv (nieuw)")
                appendLine("• Status: wacht op eerste analyse")
            }

            // =========================
            // Adviezen
            // =========================
            if (advice.isEmpty()) {
                appendLine()
                appendLine("Nog geen voldoende betrouwbare adviezen beschikbaar.")
            } else {
                appendLine()
                advice.forEach { a ->
                    appendLine(
                        line(
                            a.parameterName,
                            a.currentValue,
                            a.recommendedValue
                        )
                    )
                }
            }

            // =========================
            // Laatste maaltijd (optioneel)
            // =========================
            if (lastMeal != null) {
                appendLine()
                appendLine("🍽 Laatste maaltijd")
                appendLine("• Piek BG: ${"%.1f".format(lastMeal.peakBG)} mmol/L")
                appendLine("• TIR: ${"%.1f".format(lastMeal.timeInRangeDuringMeal)} %")
                appendLine(
                    "• Hypo-signalen: ${
                        if (lastMeal.postMealHypo || lastMeal.virtualHypoScore > 2.0)
                            "⚠️ Ja" else "Nee"
                    }"
                )
            }

            appendLine()
            appendLine("🕒 Volgend advies: $nextAdviceIn")
        }
    }

    // ======================================================
// UI — Publieke helper voor FCL.kt
// ======================================================
    fun buildAdviceUIForFCL(preferences: Preferences): String {
        return buildAdviceUI(
            lastMeal = lastMeal,
            advice = lastAdvice,
            nextAdviceIn = getNextAdviceTimeFormatted(preferences)
        )
    }


}
