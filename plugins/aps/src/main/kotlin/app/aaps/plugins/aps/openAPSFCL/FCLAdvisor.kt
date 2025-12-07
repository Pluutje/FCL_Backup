package app.aaps.plugins.aps.openAPSFCL

import app.aaps.core.keys.Preferences
import app.aaps.core.keys.*
import kotlin.math.*
import java.util.Locale
import org.joda.time.DateTime
import org.joda.time.Hours

object FCLAdvisor {

    // Injected from FCL
    private lateinit var preferences: Preferences
    private var autoParameterUpdate: Boolean = false


    fun initialize(prefs: Preferences) {
        this.preferences = prefs
        this.autoParameterUpdate = prefs.get(BooleanKey.auto_parameter_update)

        // CORRECTE INITIALISATIE
        this.parameterHelper = FCLParameters(preferences)
    }

    private var lastMeal: FCLMetrics.MealPerformanceMetrics? = null

    // buffer voor combinatie-detectie
    private val phaseBuffer = mutableMapOf<String, FCLMetrics.ParameterAdvice>()

    private var lastAutoUpdateTime: DateTime? = null

    private var metricsHelper: FCLMetrics? = null
    private lateinit var parameterHelper: FCLParameters

    fun registerMealMetrics(m: FCLMetrics.MealPerformanceMetrics) {
        lastMeal = m
    }
    fun registerMetricsHelper(helper: FCLMetrics) {
        metricsHelper = helper
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
                abs(
                    (adjusted.recommendedValue - adjusted.currentValue) /
                        max(1.0, adjusted.currentValue)
                ) * 100.0

            if (diffPct < 3.0)
                return null

            adjusted = adjusted.copy(
                confidence = max(0.15, adjusted.confidence * 0.6),
                reason = adjusted.reason + " | goede maaltijd → lagere urgentie"
            )
        }

// ------------------------------------------------------------------
// 4. GEZAMENLIJKE LOGICA rising & plateau → bolus_perc_day (CORRECTED)
// ------------------------------------------------------------------

        val affected = setOf("bolus_perc_rising", "bolus_perc_plateau")

        if (affected.contains(adjusted.parameterName)) {

            // buffer opslaan
            phaseBuffer[adjusted.parameterName] = adjusted

            // pas verwerken wanneer rising + plateau beiden binnen zijn
            if (phaseBuffer.size < 2)
                return null

            val rising = phaseBuffer["bolus_perc_rising"]!!
            val plateau = phaseBuffer["bolus_perc_plateau"]!!
            phaseBuffer.clear()

            // 1) Als richting verschilt → NIET combineren
            if (rising.direction != plateau.direction) {
                return null   // dag blijft staan, beide individuele adviezen worden los verwerkt
            }

            val dir = rising.direction

            // relatieve veranderingen
            val Dr = (rising.recommendedValue - rising.currentValue) /
                max(1.0, rising.currentValue)

            val Dp = (plateau.recommendedValue - plateau.currentValue) /
                max(1.0, plateau.currentValue)

            // kleinste en grootste relatieve verandering
            val small = min(Dr, Dp)
            val big   = max(Dr, Dp)

            // 2) DAY krijgt de minimale wijziging
            val dayCurrent = preferences.get(IntKey.bolus_perc_day).toDouble()
            val newDay = dayCurrent * (1 + small)

            // 3) De grootste krijgt de rest bovenop small
            val extra = ((1 + big) / (1 + small)) - 1

            // bepaal de grote fase
            val (targetName, targetCurrent, targetConf) =
                if (Dr == big)
                    Triple("bolus_perc_rising", rising.currentValue, rising.confidence)
                else
                    Triple("bolus_perc_plateau", plateau.currentValue, plateau.confidence)

            val newTargetValue = targetCurrent * (1 + extra)

            // RETURN het eerste advies: bolus_perc_day
            return FCLMetrics.ParameterAdvice(
                parameterName = "bolus_perc_day",
                currentValue = dayCurrent,
                recommendedValue = newDay,
                direction = dir,
                confidence = min(rising.confidence, plateau.confidence),
                reason = "Dag-percentage aangepast met ${"%.1f".format(small * 100)}%"
            ).also {

                // tweede advies opslaan voor metrics
                phaseBuffer[targetName] = FCLMetrics.ParameterAdvice(
                    parameterName = targetName,
                    currentValue = targetCurrent,
                    recommendedValue = newTargetValue,
                    direction = dir,
                    confidence = targetConf,
                    reason = "Extra correctie boven dag-aanpassing: ${"%.1f".format(extra * 100)}%"
                )
            }
        }


        // ------------------------------------------------------------
       // 5. CONFIDENCE FILTER (correcte positie)
       // ------------------------------------------------------------
        if (adjusted.confidence < 0.15) {
            return null
        }


      // ------------------------------------------------------------
      // 7. EINDRESULTAAT
      // ------------------------------------------------------------
        return adjusted
    }


    // ------------------------------------------------------------
// 📌 UNIFIED UI BUILDER — volledig vernieuwde versie met:
// - Auto-update weergave
// - Tijd tot volgende update
// - Markdown-stijl (optie C)
// ------------------------------------------------------------
    fun buildUnifiedUI(
        summaries: List<FCLMetrics.ParameterAdviceSummary>,
        nextAdviceIn: String
    ): String {

        val autoEnabled = preferences.get(BooleanKey.auto_parameter_update)
        val freqHours   = preferences.get(IntKey.parameter_update_frequentie)
        val lastUpdate  = lastAutoUpdateTime

        val nextUpdateStr =
           // if (!autoEnabled) "⚠️ Automatische updates staan UIT"
            if (lastUpdate == null) "Volgende update mogelijk zodra voldoende data beschikbaar is"
            else {
                val next = lastUpdate.plusHours(freqHours)
                val mins = org.joda.time.Minutes.minutesBetween(DateTime.now(), next).minutes
                if (mins <= 0) "Update wordt nu uitgevoerd..."
                else {
                    val h = mins / 60
                    val m = mins % 60
                    "Volgende automatische update over ${"%02d".format(h)}:${"%02d".format(m)} uur"
                }
            }

        // ---------------------------------------------
        // FILTERING (zoals eerder)
        // ---------------------------------------------
        val validSummaries = summaries.filter { s ->
            val isDetectionParam =
                s.parameterName.contains("phase_") ||
                    s.parameterName.contains("slope")

            val enoughConfidence = if (isDetectionParam)
                s.confidence >= 0.15 || s.lastAdvice != null
            else
                s.confidence >= 0.25 || s.lastAdvice != null

            val hasValue = s.weightedAverage > 0 ||
                (s.lastAdvice?.recommendedValue ?: 0.0) > 0.0

            enoughConfidence && hasValue && !s.manuallyAdjusted
        }

        fun p(name: String) =
            validSummaries.find { it.parameterName == name }

        val bolusDay      = p("bolus_perc_day")
        val bolusRising   = p("bolus_perc_rising")
        val bolusPlateau  = p("bolus_perc_plateau")
        val slopeRising   = p("phase_rising_slope")
        val slopePlateau  = p("phase_plateau_slope")

        fun percentLine(label: String, s: FCLMetrics.ParameterAdviceSummary?) =
            s?.let {
                val from = it.currentValue.roundToInt()
                val to   = it.weightedAverage.roundToInt()
                "• ${label.padEnd(18)} ${from.toString().padStart(4)} → ${to.toString().padStart(4)}"
            }

        fun slopeLine(label: String, s: FCLMetrics.ParameterAdviceSummary?) =
            s?.let {
                val from = String.format(Locale.US, "%.2f", it.currentValue)
                val to   = String.format(Locale.US, "%.2f", it.weightedAverage)
                "• ${label.padEnd(18)} ${from.padStart(5)} → ${to.padStart(5)}"
            }

        // ---------------------------------------------
        // Meal score
        // ---------------------------------------------
        val meal = lastMeal
        val mealScoreLine = if (meal == null) {
            "🍽 Meal score: n.v.t. (nog onvoldoende maaltijddata)"
        } else {
            var score = meal.timeInRangeDuringMeal / 10.0
            if (meal.postMealHypo || meal.rapidDeclineDetected || meal.virtualHypoScore > 3.0) score -= 2.0
            if (meal.peakBG > 11.0) score -= 1.0
            val txt = String.format(Locale.US, "%.1f", score.coerceIn(0.0,10.0))
            "🍽 Meal score: $txt / 10"
        }

        // ---------------------------------------------
        // Geen adviezen beschikbaar
        // ---------------------------------------------
        if (validSummaries.isEmpty()) {
            return """
📊 **Parameter advies**
─────────────────────
Status: wacht op voldoende maaltijddata (minimaal 3 maaltijden)

$mealScoreLine

⏱ $nextAdviceIn
$nextUpdateStr
""".trimIndent()
        }

        // ---------------------------------------------
        // UI opbouw
        // ---------------------------------------------
        return buildString {

            appendLine("📊 **Parameter advies**")
            appendLine("─────────────────────")

            appendLine()
            appendLine("⚙️  **Update-instellingen**")
            appendLine("• Automatisch updaten: " + if (autoEnabled) "🟢 AAN" else "🔴 UIT")
            appendLine("• $nextUpdateStr")

            appendLine()
            appendLine("💉 **Bolusparameters**")
            percentLine("Perc. Dag", bolusDay)?.let { appendLine(it) }
            percentLine("Perc. stijging", bolusRising)?.let { appendLine(it) }
            percentLine("Perc. Plateau", bolusPlateau)?.let { appendLine(it) }

            appendLine()
            appendLine("⏱ **Timingparameters**")
            slopeLine("Stijging drempel", slopeRising)?.let { appendLine(it) }
            slopeLine("Plateau drempel", slopePlateau)?.let { appendLine(it) }

            appendLine()
            appendLine(mealScoreLine)

            appendLine()
            appendLine("🕒 Volgend advies: $nextAdviceIn")
        }
    }


    fun applyAutomaticUpdatesFromSummaries(
        summaries: Collection<FCLMetrics.ParameterAdviceSummary>
    ) {
        try {
            // 1) Guard: prefs + feature-flag
            if (!this::preferences.isInitialized) return

            val autoUpdateEnabled = try {
                preferences.get(BooleanKey.auto_parameter_update)
            } catch (e: Exception) {
                false
            }
            if (!autoUpdateEnabled) return

            // 2) Frequentie-beveiliging (minimale uren tussen twee auto-updates)
            val now = DateTime.now()
            val minHours = try {
                preferences.get(IntKey.parameter_update_frequentie)
            } catch (e: Exception) {
                24    // default: 1x per dag
            }
            lastAutoUpdateTime?.let { last ->
                val diffHours = Hours.hoursBetween(last, now).hours
                if (diffHours < minHours) {
                    return
                }
            }

            // 3) Alleen kernparameters auto-updaten
            val coreParams = setOf(
                "bolus_perc_day",
                "bolus_perc_rising",
                "bolus_perc_plateau",
                "phase_rising_slope",
                "phase_plateau_slope",
                "IOB_corr_perc"
            )

            val updatedParams = mutableListOf<String>()

            for (summary in summaries) {
                val name = summary.parameterName
                if (!coreParams.contains(name)) continue

                // Alleen als er überhaupt een zinvolle target is
                val targetRaw = summary.weightedAverage
                if (targetRaw <= 0.0) continue

                // Genoeg vertrouwen
                if (summary.confidence < 0.25) continue

                // Recent handmatig aangepast? Dan niet overschrijven
                if (summary.manuallyAdjusted) continue

                val current = summary.currentValue

                // Deadband: minimaal 3% of 1 absoluut punt
                val diffAbs = kotlin.math.abs(targetRaw - current)
                val diffRel = if (current != 0.0) diffAbs / kotlin.math.abs(current) else 1.0
                if (diffAbs < 1.0 && diffRel < 0.03) continue

                // 4) Clamp met FCLParameters-min/max


                val def = parameterHelper.getDefinitionByTechnicalName(name) ?: continue
                val target = targetRaw.coerceIn(def.minValue, def.maxValue)

                // 5) Wegschrijven naar de juiste preference
                when (name) {
                    "bolus_perc_day"      -> preferences.put(IntKey.bolus_perc_day, target.toInt())
                    "bolus_perc_rising"   -> preferences.put(IntKey.bolus_perc_rising, target.toInt())
                    "bolus_perc_plateau"  -> preferences.put(IntKey.bolus_perc_plateau, target.toInt())
                    "phase_rising_slope"  -> preferences.put(DoubleKey.phase_rising_slope, target)
                    "phase_plateau_slope" -> preferences.put(DoubleKey.phase_plateau_slope, target)
                    "IOB_corr_perc"       -> preferences.put(IntKey.IOB_corr_perc, target.toInt())
                }

                // 6) Historie resetten alsof het een handmatige aanpassing was
                metricsHelper?.clearParameterAdviceHistoryOnManualAdjustment(name)

                updatedParams += name
            }

            if (updatedParams.isNotEmpty()) {
                lastAutoUpdateTime = DateTime.now()
            }

        } catch (e: Exception) {
            // nooit de hoofdlogica breken
        }
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
─────────────────────
Piek BG: ${round1(m.peakBG)} mmol/L
Tijd boven 10: ${m.timeAbove10} min
TIR tijdens maaltijd: ${round1(m.timeInRangeDuringMeal)}%
Hypo-signalen: ${if (m.postMealHypo || m.rapidDeclineDetected || m.virtualHypoScore > 3.0) "⚠️ Ja" else "Nee"}


""".trimIndent()
    }

    private fun round1(v: Double) = (v * 10).roundToInt() / 10.0
}
