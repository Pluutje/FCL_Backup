package app.aaps.plugins.aps.openAPSFCL

import kotlin.math.max
import kotlin.math.min
import kotlin.math.round

/**
 * FCLAdvisor (A3-variant)
 *
 * - Filterlaag vóór alle parameteradviezen
 * - Maakt GEEN eigen adviezen meer
 * - Geeft dus nooit richting-conflicten
 * - Biedt alleen maaltijd-analyse (rapportage)
 */
object FCLAdvisor {

    private var lastMeal: FCLMetrics.MealPerformanceMetrics? = null

    fun registerMealMetrics(m: FCLMetrics.MealPerformanceMetrics) {
        lastMeal = m
    }

    /**
     * Filtert bestaande ParameterAdvice uit FCLMetrics.
     * Mag alleen magnitude & confidence aanpassen, NOOIT richting veranderen.
     */
    fun filterAdvice(advice: FCLMetrics.ParameterAdvice): FCLMetrics.ParameterAdvice? {

        val meal = lastMeal ?: return advice

        val isDoseParam =
            advice.parameterName.startsWith("bolus_perc") ||
                advice.parameterName == "IOB_corr_perc"

        val isTimingParam =
            advice.parameterName.startsWith("phase_")

        val hypoRisk =
            meal.postMealHypo ||
                meal.rapidDeclineDetected ||
                meal.virtualHypoScore > 3.0

        val bigPeak = meal.peakBG > 11.0
        val longAbove10 = meal.timeAbove10 > 60
        val tirGood = meal.timeInRangeDuringMeal >= 90.0 &&
            meal.peakBG <= 10.5 &&
            !meal.postMealHypo

        var adjusted = advice

        // ──────────────────────────────────────────────────────────
        // 1. HYPO-VEILIGHEID (direction-preserving)
        // ──────────────────────────────────────────────────────────

        if (hypoRisk && isDoseParam && advice.direction == "INCREASE") {
            val midpoint = (advice.currentValue + advice.recommendedValue) / 2.0

            // ❗ Zorg dat waarde altijd ≥ currentValue blijft
            val limited = max(advice.currentValue, midpoint)

            adjusted = adjusted.copy(
                recommendedValue = limited,
                confidence = max(0.15, advice.confidence * 0.5),
                reason = advice.reason + " | Advisor: hypo → verhoging afgezwakt."
            )
        }

        if (hypoRisk && isTimingParam) {
            val midpoint = (advice.currentValue + advice.recommendedValue) / 2.0
            adjusted = adjusted.copy(
                recommendedValue = midpoint,
                confidence = max(0.15, advice.confidence * 0.7),
                reason = adjusted.reason + " | Advisor: hypo-risico → timing minder scherp."
            )
        }

        // ──────────────────────────────────────────────────────────
        // 2. HOOGTE PIEKEN – voorkom te sterke verlagingen
        // ──────────────────────────────────────────────────────────

        if ((bigPeak || longAbove10) && isDoseParam && advice.direction == "DECREASE") {

            val diff = advice.recommendedValue - advice.currentValue
            val weakened = advice.currentValue + diff * 0.3  // slechts 30% van verlaging

            adjusted = adjusted.copy(
                recommendedValue = weakened,
                confidence = max(0.15, advice.confidence * 0.4),
                reason = adjusted.reason + " | Advisor: hoge pieken → verlaging afgezwakt."
            )
        }

        // ──────────────────────────────────────────────────────────
        // 3. GOEDE MAALTIJD → kleine adviezen wegfilteren
        // ──────────────────────────────────────────────────────────

        if (tirGood) {
            val rel =
                if (advice.currentValue != 0.0)
                    kotlin.math.abs((advice.recommendedValue - advice.currentValue) / advice.currentValue) * 100.0
                else 0.0

            if (rel < 3.0) {
                // Kleine wijziging → niet nodig
                return null
            }

            adjusted = adjusted.copy(
                confidence = max(0.15, adjusted.confidence * 0.6),
                reason = adjusted.reason + " | Advisor: maaltijd goed → wijziging minder dringend."
            )
        }

        // ──────────────────────────────────────────────────────────
        // 4. Confidence onder 15% → advies weggooien
        // ──────────────────────────────────────────────────────────

        if (adjusted.confidence < 0.15) {
            return null
        }

        return adjusted
    }

    /**
     * Maaltijd rapportage (GEEN parameteradviezen!)
     */
    fun buildMealReport(): String {

        val m = lastMeal ?: return """
🍽️ Maaltijd-analyse
────────────────────
Geen recente maaltijddata gevonden.
""".trimIndent()

        return """
🍽️ Maaltijd-analyse
────────────────────
• Piek: ${round1(m.peakBG)} mmol/L
• Tijd boven 10: ${m.timeAbove10} min
• TIR in maaltijd: ${round1(m.timeInRangeDuringMeal)}%
• Hypo-signalen: ${if (m.postMealHypo || m.rapidDeclineDetected || m.virtualHypoScore > 3.0) "⚠️ aanwezig" else "geen"}

📌 Alle parameteroptimalisatie is verwerkt in het 'Parameter Advies' blok.
""".trimIndent()
    }

    private fun round1(v: Double) = round(v * 10.0) / 10.0
}
