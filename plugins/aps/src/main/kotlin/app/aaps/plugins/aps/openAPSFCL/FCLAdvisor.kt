package app.aaps.plugins.aps.openAPSFCL

import kotlin.math.*

object FCLAdvisor {

    private var lastMeal: FCLMetrics.MealPerformanceMetrics? = null

    // buffer voor combinatie-detectie
    private val phaseBuffer = mutableMapOf<String, FCLMetrics.ParameterAdvice>()

    fun registerMealMetrics(m: FCLMetrics.MealPerformanceMetrics) {
        lastMeal = m
    }

    /**
     *  ***A3 FILTERLAAG***
     *  - Mag NOOIT richting veranderen
     *  - Mag alleen magnitude / confidence dempen
     *  - Mag gecombineerde adviezen vertalen naar bolus_perc_day
     */
    fun filterAdvice(advice: FCLMetrics.ParameterAdvice): FCLMetrics.ParameterAdvice? {

        val meal = lastMeal ?: return advice
        val isDoseParam =
            advice.parameterName.startsWith("bolus_perc") ||
                advice.parameterName == "IOB_corr_perc"

        val isTimingParam = advice.parameterName.startsWith("phase_")

        // Hypo signalen
        val hypoRisk =
            meal.postMealHypo ||
                meal.rapidDeclineDetected ||
                meal.virtualHypoScore > 3.0

        val bigPeak = meal.peakBG > 11.0
        val longAbove10 = meal.timeAbove10 > 60
        val tirGood =
            meal.timeInRangeDuringMeal >= 90 &&
                meal.peakBG <= 10.5 &&
                !meal.postMealHypo

        var adjusted = advice

        // ------------------------------------------------------------------
        // 1. HYPO → demp alleen "INCREASE" dose-adviezen (richting blijft)
        // ------------------------------------------------------------------
        if (hypoRisk && isDoseParam && advice.direction == "INCREASE") {
            val midpoint = (advice.currentValue + advice.recommendedValue) / 2.0
            val limited = max(advice.currentValue, midpoint)

            adjusted = adjusted.copy(
                recommendedValue = limited,
                confidence = max(0.15, advice.confidence * 0.5),
                reason = advice.reason + " | hypo → verhoging afgezwakt"
            )
        }

        // Timing parameters bij hypo
        if (hypoRisk && isTimingParam) {
            val midpoint = (adjusted.currentValue + adjusted.recommendedValue) / 2.0
            adjusted = adjusted.copy(
                recommendedValue = midpoint,
                confidence = max(0.15, adjusted.confidence * 0.7),
                reason = adjusted.reason + " | hypo → timing afgezwakt"
            )
        }

        // ------------------------------------------------------------------
        // 2. Hoge pieken → demp alleen DECREASE
        // ------------------------------------------------------------------
        if ((bigPeak || longAbove10) && isDoseParam && adjusted.direction == "DECREASE") {
            val diff = adjusted.recommendedValue - adjusted.currentValue
            val weakened = adjusted.currentValue + diff * 0.3

            adjusted = adjusted.copy(
                recommendedValue = weakened,
                confidence = max(0.15, adjusted.confidence * 0.4),
                reason = adjusted.reason + " | hoge piek → verlaging afgezwakt"
            )
        }

        // ------------------------------------------------------------------
        // 3. Goede maaltijd → kleine adviezen (<3%) negeren
        // ------------------------------------------------------------------
        if (tirGood) {
            val diffPct =
                abs((adjusted.recommendedValue - adjusted.currentValue) /
                        max(1.0, adjusted.currentValue)) * 100.0

            if (diffPct < 3.0)
                return null

            adjusted = adjusted.copy(
                confidence = max(0.15, adjusted.confidence * 0.6),
                reason = adjusted.reason + " | goede maaltijd → lagere urgentie"
            )
        }

        // ------------------------------------------------------------------
        // 4. GEZAMENLIJKE LOGICA rising & plateau → bolus_perc_day
        // ------------------------------------------------------------------

        val affected = setOf("bolus_perc_rising", "bolus_perc_plateau", "bolus_perc_day")

        if (affected.contains(adjusted.parameterName)) {

            // bolus_perc_day komt direct door
            if (adjusted.parameterName == "bolus_perc_day") {
                return adjusted
            }

            // BUFFER rising & plateau totdat we beide hebben
            phaseBuffer[adjusted.parameterName] = adjusted

            // ❗ BELANGRIJKE WIJZIGING:
            // Zolang we nog maar 1 van de 2 hebben, NOG NIETS naar buiten sturen.
            if (phaseBuffer.size < 2)
                return null   // wacht op tweede advies


            val rising = phaseBuffer["bolus_perc_rising"]!!
            val plateau = phaseBuffer["bolus_perc_plateau"]!!

            val sameDirection = rising.direction == plateau.direction

            if (!sameDirection) {
                phaseBuffer.clear()
                return adjusted
            }

            // --- Beide omhoog → bolus_perc_day verhogen ---
            if (rising.direction == "INCREASE") {

                val newDay = rising.currentValue +
                    (rising.recommendedValue - rising.currentValue) * 0.8

                phaseBuffer.clear()
                return FCLMetrics.ParameterAdvice(
                    parameterName = "bolus_perc_day",
                    currentValue = rising.currentValue,
                    recommendedValue = newDay,
                    direction = "INCREASE",
                    confidence = min(rising.confidence, plateau.confidence),
                    reason = "Rising + plateau → gezamenlijke verhoging via bolus_perc_day"
                )
            }

            // --- Beide omlaag → bolus_perc_day verlagen ---
            if (rising.direction == "DECREASE") {

                val newDay = rising.currentValue -
                    (rising.currentValue - rising.recommendedValue) * 0.8

                phaseBuffer.clear()
                return FCLMetrics.ParameterAdvice(
                    parameterName = "bolus_perc_day",
                    currentValue = rising.currentValue,
                    recommendedValue = newDay,
                    direction = "DECREASE",
                    confidence = min(rising.confidence, plateau.confidence),
                    reason = "Rising + plateau → gezamenlijke verlaging via bolus_perc_day"
                )
            }
        }

        // te lage confidence → skip
        if (adjusted.confidence < 0.15)
            return null

        return adjusted
    }

    // ----------------------------------------------------------------------
    // UI: alleen maaltijdrapport, geen parameteradviezen
    // ----------------------------------------------------------------------
    fun buildMealReport(): String {

        val m = lastMeal ?: return """
🍽️ Maaltijd-analyse
────────────────────
Geen recente maaltijddata.
""".trimIndent()

        return """
🍽️ Maaltijd-analyse
────────────────────
Piek BG: ${round1(m.peakBG)} mmol/L
Tijd boven 10: ${m.timeAbove10} min
TIR tijdens maaltijd: ${round1(m.timeInRangeDuringMeal)}%
Hypo-signalen: ${if (m.postMealHypo || m.rapidDeclineDetected || m.virtualHypoScore > 3.0) "⚠️ Ja" else "Nee"}

📌 Parameteroptimalisatie staat in ‘Parameter Advies’.
""".trimIndent()
    }

    private fun round1(v: Double) = (v * 10).roundToInt() / 10.0
}
