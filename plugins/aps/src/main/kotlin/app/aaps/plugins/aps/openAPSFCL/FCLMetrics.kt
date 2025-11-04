package app.aaps.plugins.aps.openAPSFCL

import android.content.Context
import android.os.Environment
import app.aaps.core.keys.Preferences
import com.google.gson.Gson
import org.joda.time.DateTime
import org.joda.time.Hours
import org.joda.time.Minutes
import org.joda.time.format.DateTimeFormat
import org.joda.time.format.DateTimeFormatter
import java.io.File
import kotlin.math.*
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.DoubleKey

class FCLMetrics(private val context: Context, private val preferences: Preferences) {

    data class GlucoseMetrics(
        val period: String,
        val timeInRange: Double,
        val timeBelowRange: Double,
        val timeAboveRange: Double,
        val timeBelowTarget: Double,
        val averageGlucose: Double,
        val gmi: Double,
        val cv: Double,
        val totalReadings: Int,
        val lowEvents: Int,
        val veryLowEvents: Int,
        val highEvents: Int,
        val agressivenessScore: Double,
        val startDate: DateTime,
        val endDate: DateTime,
        val mealDetectionRate: Double,
        val bolusDeliveryRate: Double,
        val averageDetectedCarbs: Double,
        val readingsPerHour: Double
    )

    data class ParameterAgressivenessAdvice(
        val parameterName: String,
        val currentValue: Double,
        val recommendedValue: Double,
        val reason: String,
        val confidence: Double,
        val expectedImprovement: String,
        val changeDirection: String,
        val timestamp: DateTime = DateTime.now()
    )

    data class MealPerformanceMetrics(
        val mealId: String,
        val mealStartTime: DateTime,
        val mealEndTime: DateTime,
        val startBG: Double,
        val peakBG: Double,
        val endBG: Double,
        val timeToPeak: Int,
        val totalCarbsDetected: Double,
        val totalInsulinDelivered: Double,
        val peakAboveTarget: Double,
        val timeAbove10: Int,
        val postMealHypo: Boolean,
        val timeInRangeDuringMeal: Double,
        val phaseInsulinBreakdown: Map<String, Double>,
        val firstBolusTime: DateTime?,
        val timeToFirstBolus: Int,
        val maxIOBDuringMeal: Double,
        val wasSuccessful: Boolean,
        val mealType: String = "unknown",
        val declineRate: Double? = null,
        val rapidDeclineDetected: Boolean = false,
        val declinePhaseDuration: Int = 0, // minuten
        val virtualHypoScore: Double = 0.0
    )

    data class ParameterAdjustmentResult(
        val parameterName: String,
        val oldValue: Double,
        val newValue: Double,
        val adjustmentTime: DateTime,
        val mealMetricsBefore: MealPerformanceMetrics?,
        val mealMetricsAfter: MealPerformanceMetrics?,
        val improvement: Double,
        val learned: Boolean = false,
        val isAutomatic: Boolean = false, // ★★★ NIEUW: Onderscheid handmatig/automatisch
        val confidence: Double = 0.0, // ★★★ NIEUW: Vertrouwen bij aanpassing
        val reason: String = "" // ★★★ NIEUW: Reden voor aanpassing
    )

    data class AutoUpdateConfig(
        val minConfidence: Double = 0.85,
        val minMeals: Int = 10,
        val maxChangePercent: Double = 0.15,
        val enabled: Boolean = false,
        val lastEvaluation: DateTime = DateTime.now()
    )

    // ★★★ GEMODULEERD PARAMETER ADVIES SYSTEEM ★★★
    data class ParameterAdviceHistory(
        val parameterName: String,
        val adviceHistory: MutableList<HistoricalAdvice> = mutableListOf(),
        val currentTrend: String = "STABLE", // INCREASING, DECREASING, STABLE
        val confidenceInTrend: Double = 0.0,
        val lastChangeTime: DateTime = DateTime.now()
    )

    data class HistoricalAdvice(
        val timestamp: DateTime,
        val recommendedValue: Double,
        val changeDirection: String,
        val confidence: Double,
        val reason: String,
        val actualImprovement: Double? = null
    )

    data class DataQualityMetrics(
        val totalReadings: Int,
        val expectedReadings: Int,
        val dataCompleteness: Double,
        val periodHours: Int,
        val hasSufficientData: Boolean
    )

    private data class CSVReading(
        val timestamp: DateTime,
        val currentBG: Double,
        val currentIOB: Double,
        val dose: Double,
        val shouldDeliver: Boolean,
        val mealDetected: Boolean,
        val detectedCarbs: Double,
        val carbsOnBoard: Double
    )
    // ★★★ ADVIES GESCHIEDENIS ★★★
    data class AdviceHistoryEntry(
        val timestamp: DateTime,
        val adviceList: List<ParameterAgressivenessAdvice>,
        val metricsSnapshot: GlucoseMetrics? = null,
        val mealCount: Int = 0
    )

    // ★★★ BIDIRECTIONELE PRESTATIE ANALYSE ★★★
    data class PerformanceAnalysis(
        val issueType: String, // "HIGH_BG", "LOW_BG", "BOTH", "OPTIMAL"
        val severity: Double, // 0.0 - 1.0
        val primaryParameter: String,
        val adjustmentDirection: String, // "INCREASE", "DECREASE"
        val confidence: Double,
        val reasoning: String
    )

    // ★★★ NIEUWE DATA STRUCTURES VOOR PARAMETER ADVIES ★★★
    data class ParameterAdviceSummary(
        val parameterName: String,
        val currentValue: Double,
        val lastAdvice: ParameterAgressivenessAdvice?,
        val weightedAverage: Double,
        val confidence: Double,
        val trend: String,
        val manuallyAdjusted: Boolean = false,
        val lastManualAdjustment: DateTime? = null
    )

    data class MealParameterAdvice(
        val mealId: String,
        val timestamp: DateTime,
        val parameterAdvice: List<ParameterAgressivenessAdvice>
    )

    companion object {
        private const val TARGET_LOW = 3.9
        private const val TARGET_HIGH = 10.0
        private const val VERY_LOW_THRESHOLD = 3.0
        private const val VERY_HIGH_THRESHOLD = 13.9
        private const val MIN_READINGS_PER_HOUR = 8.0
        private const val MEAL_END_TIMEOUT = 180

        private var Target_Bg: Double = 5.2

        private var cached24hMetrics: GlucoseMetrics? = null
        private var cached7dMetrics: GlucoseMetrics? = null
        private var cachedDataQuality: DataQualityMetrics? = null
    }


    private var cachedMealMetrics: MutableList<MealPerformanceMetrics> = mutableListOf()
    private var lastParameterAdjustments: MutableList<ParameterAdjustmentResult> = mutableListOf()
    private val gson = Gson()
    private val dateFormatter: DateTimeFormatter = DateTimeFormat.forPattern("dd-MM-yyyy HH:mm")
    private val prefs = context.getSharedPreferences("FCL_Metrics", Context.MODE_PRIVATE)
    private var mealParameterAdviceHistory: MutableList<MealParameterAdvice> = mutableListOf()
    private var parameterAdjustmentTimestamps: MutableMap<String, DateTime> = mutableMapOf()
    private var autoUpdateConfig: AutoUpdateConfig = AutoUpdateConfig()
    private var automaticAdjustmentsHistory: MutableList<ParameterAdjustmentResult> = mutableListOf()

    init {
        lastParameterAdjustments = loadParameterAdjustments().toMutableList()
        loadMealParameterAdviceHistory() // ★★★ NIEUW: Laad parameter advies geschiedenis
        lastParameterAdjustments = loadParameterAdjustments().toMutableList()
        loadParameterAdjustmentTimestamps()
        loadAutoUpdateConfig() // ★★★ NIEUW: Laad automatische update config
        loadAutomaticAdjustmentsHistory() // ★★★ NIEUW: Laad automatische aanpassingen geschiedenis
    }

    fun setTargetBg(value: Double) { Target_Bg = value }


    fun resetDataQualityCache() {
        cachedDataQuality = null
    }



    // ★★★ GESTANDAARDISEERDE PARAMETER AANPASSINGEN ★★★
    private fun calculateOptimalIncrease(currentValue: Double): Double {
        return min(currentValue * 1.15, currentValue + 10.0)
    }

    private fun calculateOptimalDecrease(currentValue: Double): Double {
        return max(currentValue * 0.85, currentValue - 10.0)
    }

    private fun calculateOptimalIncrease(currentValue: Double, maxValue: Double): Double {
        return min(calculateOptimalIncrease(currentValue), maxValue)
    }

    private fun calculateOptimalDecrease(currentValue: Double, minValue: Double): Double {
        return max(calculateOptimalDecrease(currentValue), minValue)
    }

    // ★★★ INTEGRATIE MET BESTAANDE PARAMETER AANPASSINGS FUNCTIES ★★★
    private fun calculateOptimalIncreaseWithParams(currentValue: Double, parameters: FCLParameters, parameterName: String): Double {
        val definition = getParameterDefinition(parameters, parameterName)
        return if (definition != null) {
            // Gebruik bestaande functie maar met FCLParameters grenzen
            calculateOptimalIncrease(currentValue, definition.maxValue)
        } else {
            // Fallback naar bestaande implementatie zonder grenzen
            calculateOptimalIncrease(currentValue)
        }
    }

    private fun calculateOptimalDecreaseWithParams(currentValue: Double, parameters: FCLParameters, parameterName: String): Double {
        val definition = getParameterDefinition(parameters, parameterName)
        return if (definition != null) {
            // Gebruik bestaande functie maar met FCLParameters grenzen
            calculateOptimalDecrease(currentValue, definition.minValue)
        } else {
            // Fallback naar bestaande implementatie zonder grenzen
            calculateOptimalDecrease(currentValue)
        }
    }

    // ★★★ METRICS BEREKENING ★★★
    fun calculateMetrics(hours: Int = 24, forceRefresh: Boolean = false): GlucoseMetrics {
        if (forceRefresh || shouldCalculateNewMetrics()) {
            return calculateFreshMetrics(hours).also { cacheMetrics(hours, it) }
        }

        return when (hours) {
            24 -> cached24hMetrics ?: calculateFreshMetrics(24)
            168 -> cached7dMetrics ?: calculateFreshMetrics(168)
            else -> calculateFreshMetrics(hours)
        }
    }

    private fun calculateFreshMetrics(hours: Int): GlucoseMetrics {
        return try {
            val data = loadCSVData(hours)
            if (data.isEmpty()) {
                createEmptyMetrics(hours).also { cacheMetrics(hours, it) }
            } else {
                calculateGlucoseMetrics(data, hours).also { cacheMetrics(hours, it) }
            }
        } catch (e: Exception) {
            createEmptyMetrics(hours).also { cacheMetrics(hours, it) }
        }
    }

    private fun shouldCalculateNewMetrics(): Boolean {
        val lastMetricsTime = getLastMetricsTime()
        return Minutes.minutesBetween(lastMetricsTime, DateTime.now()).minutes >= 60
    }

    private fun getLastMetricsTime(): DateTime {
        val lastTimeMillis = prefs.getLong("last_metrics_time", 0)
        return if (lastTimeMillis > 0) DateTime(lastTimeMillis) else DateTime.now().minusHours(2)
    }

    private fun setLastMetricsTime() {
        prefs.edit().putLong("last_metrics_time", DateTime.now().millis).apply()
    }

    private fun cacheMetrics(hours: Int, metrics: GlucoseMetrics) {
        when (hours) {
            24 -> cached24hMetrics = metrics
            168 -> cached7dMetrics = metrics
        }
        setLastMetricsTime()
    }

    // ★★★ DATA QUALITY CACHE INVALIDATIE ★★★
    fun invalidateDataQualityCache() {
        cachedDataQuality = null
        resetDataQualityCache()

        // Forceer nieuwe berekening bij volgende aanroep
        prefs.edit().remove("last_metrics_time").apply()
    }

    // ★★★ AUTOMATISCHE CACHE INVALIDATIE BIJ NIEUWE DATA ★★★
    private fun shouldInvalidateCache(): Boolean {
        val csvFile = getOrCreateCSVFile()
        if (!csvFile.exists()) return true

        val lastModified = DateTime(csvFile.lastModified())
        val lastCacheTime = getLastMetricsTime()

        return lastModified.isAfter(lastCacheTime)
    }

    // ★★★ VERBETERDE DATA QUALITY METRICS ★★★
    fun getDataQualityMetrics(hours: Int = 24, forceRefresh: Boolean = false): DataQualityMetrics {
        // ★★★ AUTOMATISCHE INVALIDATIE BIJ NIEUWE DATA ★★★
        if (shouldInvalidateCache()) {
            invalidateDataQualityCache()
        }

        if (!forceRefresh && !shouldCalculateNewMetrics() && cachedDataQuality != null && hours == 24) {
            return cachedDataQuality!!
        }

        return try {
            val data = loadCSVData(hours)
            if (data.isEmpty()) {
                DataQualityMetrics(
                    totalReadings = 0,
                    expectedReadings = hours * 12,
                    dataCompleteness = 0.0,
                    periodHours = hours,
                    hasSufficientData = false
                )
            } else {
                val expectedReadings = hours * 12
                val completeness = (data.size.toDouble() / expectedReadings) * 100.0

                DataQualityMetrics(
                    totalReadings = data.size,
                    expectedReadings = expectedReadings,
                    dataCompleteness = completeness,
                    periodHours = hours,
                    hasSufficientData = completeness > 60.0
                ).also {
                    if (hours == 24) {
                        cachedDataQuality = it
                        setLastMetricsTime()
                    }
                }
            }
        } catch (e: Exception) {
            DataQualityMetrics(
                totalReadings = 0,
                expectedReadings = hours * 12,
                dataCompleteness = 0.0,
                periodHours = hours,
                hasSufficientData = false
            )
        }
    }



    // ★★★ HOOFD ADVIES FUNCTIE ★★★
    fun calculateAgressivenessAdvice(
        parameters: FCLParameters,
        metrics: GlucoseMetrics,
        forceNew: Boolean = false
    ): List<ParameterAgressivenessAdvice> {

        if (!forceNew && !shouldCalculateNewAdvice()) {
            return getStoredAdvice()
        }

        if (metrics.totalReadings < 50 || metrics.readingsPerHour < MIN_READINGS_PER_HOUR) {
            return createInsufficientDataAdvice(metrics).also {
                storeAdvice(it)
                setLastAdviceTime()
            }
        }

        evaluateParameterAdjustments()

        // ★★★ GEBRUIK GEMODULEERDE ADVIES GENERATIE ★★★
        val mealMetrics = calculateMealPerformanceMetrics(168)
        val adviceList = generateModulatedAdvice(parameters, metrics, mealMetrics)

        // ★★★ UPDATE ADVIES GESCHIEDENIS ★★★
        adviceList.forEach { updateAdviceHistory(it) }

        val finalAdvice = adviceList
            .take(5)
            .distinctBy { it.parameterName }
            .sortedByDescending { it.confidence }

        storeAdvice(finalAdvice)
        storeAdviceHistoryEntries(finalAdvice)   // ★★★ SLA ADVIES GESCHIEDENIS OP ★★★
        setLastAdviceTime()

        // ★★★ OPSLAAN PARAMETER ADVIES PER MAALTIJD ★★★

        val latestMeal = mealMetrics.maxByOrNull { it.mealStartTime }
        latestMeal?.let { meal ->
            saveMealParameterAdvice(meal.mealId, finalAdvice)
        }

        return finalAdvice
    }

    fun shouldCalculateNewAdvice(): Boolean {
        val lastAdviceTime = getLastAdviceTime()
        val hoursSinceLastAdvice = Hours.hoursBetween(lastAdviceTime, DateTime.now()).hours
        val adviceInterval = try {
            preferences.get(IntKey.Advice_Interval_Hours)
        } catch (e: Exception) {
            12
        }
        return hoursSinceLastAdvice >= adviceInterval || getStoredAdvice().isEmpty()
    }

    private fun getLastAdviceTime(): DateTime {
        val lastTimeMillis = prefs.getLong("last_advice_time", 0)
        return if (lastTimeMillis > 0) DateTime(lastTimeMillis) else DateTime.now().minusHours(25)
    }

    private fun setLastAdviceTime() {
        prefs.edit().putLong("last_advice_time", DateTime.now().millis).apply()
    }

    // ★★★ VERBETERDE PARAMETER ACCESS MET FCLPARAMETERS INTEGRATIE ★★★
    private fun getCurrentParameterValue(parameters: FCLParameters, parameterName: String): Double {
        return try {
            // Probeer eerst via FCLParameters
            parameters.getParameterValue(parameterName) ?: getFallbackParameterValue(parameterName)
        } catch (e: Exception) {
            getFallbackParameterValue(parameterName)
        }
    }

    private fun getFallbackParameterValue(parameterName: String): Double {
        return try {
            when (parameterName) {
                "bolus_perc_early" -> preferences.get(IntKey.bolus_perc_early).toDouble()
                "bolus_perc_mid" -> preferences.get(IntKey.bolus_perc_mid).toDouble()
                "bolus_perc_late" -> preferences.get(IntKey.bolus_perc_late).toDouble()
                "bolus_perc_day" -> preferences.get(IntKey.bolus_perc_day).toDouble()
                "bolus_perc_night" -> preferences.get(IntKey.bolus_perc_night).toDouble()
                "meal_detection_sensitivity" -> preferences.get(DoubleKey.meal_detection_sensitivity)
                "phase_early_rise_slope" -> preferences.get(DoubleKey.phase_early_rise_slope)
                "phase_mid_rise_slope" -> preferences.get(DoubleKey.phase_mid_rise_slope)
                "phase_late_rise_slope" -> preferences.get(DoubleKey.phase_late_rise_slope)
                "carb_percentage" -> preferences.get(IntKey.carb_percentage).toDouble()
                "peak_damping_percentage" -> preferences.get(IntKey.peak_damping_percentage).toDouble()
                "hypo_risk_percentage" -> preferences.get(IntKey.hypo_risk_percentage).toDouble()
                "IOB_corr_perc" -> preferences.get(IntKey.IOB_corr_perc).toDouble()
                else -> {
                    // Probeer technische naam mapping
                    when (parameterName) {
                        "Early Rise Bolus %" -> preferences.get(IntKey.bolus_perc_early).toDouble()
                        "Mid Rise Bolus %" -> preferences.get(IntKey.bolus_perc_mid).toDouble()
                        "Late Rise Bolus %" -> preferences.get(IntKey.bolus_perc_late).toDouble()
                        "Daytime Bolus %" -> preferences.get(IntKey.bolus_perc_day).toDouble()
                        "Nighttime Bolus %" -> preferences.get(IntKey.bolus_perc_night).toDouble()
                        "Meal Detection Sensitivity" -> preferences.get(DoubleKey.meal_detection_sensitivity)
                        "Early Rise Slope" -> preferences.get(DoubleKey.phase_early_rise_slope)
                        "Mid Rise Slope" -> preferences.get(DoubleKey.phase_mid_rise_slope)
                        "Late Rise Slope" -> preferences.get(DoubleKey.phase_late_rise_slope)
                        "Carb Detection %" -> preferences.get(IntKey.carb_percentage).toDouble()
                        "Peak Damping %" -> preferences.get(IntKey.peak_damping_percentage).toDouble()
                        "Hypo Risk Bolus %" -> preferences.get(IntKey.hypo_risk_percentage).toDouble()
                        "IOB Safety %" -> preferences.get(IntKey.IOB_corr_perc).toDouble()
                        else -> 0.0
                    }
                }
            }
        } catch (e: Exception) {
            0.0
        }
    }


    // ★★★ BIDIRECTIONELE PRESTATIE ANALYSE FUNCTIES ★★★
    fun analyzeBidirectionalPerformance(
        metrics: GlucoseMetrics,
        mealMetrics: List<MealPerformanceMetrics>
    ): List<PerformanceAnalysis> {
        val analyses = mutableListOf<PerformanceAnalysis>()

        // ★★★ ANALYSE VOOR TE HOGE BLOEDSUIKER ★★★
        if (metrics.timeAboveRange > 25.0 || metrics.averageGlucose > 8.5) {
            analyses.add(analyzeHighBGIssues(metrics, mealMetrics))
        }

        // ★★★ ANALYSE VOOR TE LAGE BLOEDSUIKER ★★★
        if (metrics.timeBelowRange > 5.0 || metrics.lowEvents + metrics.veryLowEvents > 2) {
            analyses.add(analyzeLowBGIssues(metrics, mealMetrics))
        }

        // ★★★ GEMENGDE PROBLEMEN ANALYSE ★★★
        if (metrics.timeAboveRange > 20.0 && metrics.timeBelowRange > 4.0) {
            analyses.add(analyzeMixedIssues(metrics, mealMetrics))
        }

        return analyses.sortedByDescending { it.severity }
    }

    private fun analyzeHighBGIssues(
        metrics: GlucoseMetrics,
        mealMetrics: List<MealPerformanceMetrics>
    ): PerformanceAnalysis {
        val highPeakMeals = mealMetrics.count { it.peakBG > 11.0 }
        val highPeakPercentage = if (mealMetrics.isNotEmpty()) (highPeakMeals.toDouble() / mealMetrics.size) * 100 else 0.0

        val severity = calculateHighBGSeverity(metrics, highPeakPercentage)

        return when {
            highPeakPercentage > 40 -> PerformanceAnalysis(
                issueType = "HIGH_PEAKS",
                severity = severity,
                primaryParameter = "Early Rise Bolus %",
                adjustmentDirection = "INCREASE",
                confidence = min(0.9, severity * 1.2),
                reasoning = "${highPeakPercentage.toInt()}% van maaltijden heeft pieken >11 mmol/L - verhoog vroege bolus"
            )
            metrics.timeAboveRange > 30 -> PerformanceAnalysis(
                issueType = "PERSISTENT_HIGH",
                severity = severity,
                primaryParameter = "Daytime Bolus %",
                adjustmentDirection = "INCREASE",
                confidence = min(0.8, severity * 1.1),
                reasoning = "Te veel tijd boven range (${metrics.timeAboveRange.toInt()}%) - verhoog algemene agressiviteit"
            )
            else -> PerformanceAnalysis(
                issueType = "MODERATE_HIGH",
                severity = severity,
                primaryParameter = "Mid Rise Bolus %",
                adjustmentDirection = "INCREASE",
                confidence = min(0.7, severity),
                reasoning = "Gemiddelde glucose te hoog (${round(metrics.averageGlucose, 1)} mmol/L) - verhoog mid fase bolus"
            )
        }
    }

    private fun analyzeLowBGIssues(
        metrics: GlucoseMetrics,
        mealMetrics: List<MealPerformanceMetrics>
    ): PerformanceAnalysis {
        val postMealHypos = mealMetrics.count { it.postMealHypo }
        val hypoPercentage = if (mealMetrics.isNotEmpty()) (postMealHypos.toDouble() / mealMetrics.size) * 100 else 0.0
        val virtualHypos = mealMetrics.count { it.rapidDeclineDetected }

        val severity = calculateLowBGSeverity(metrics, hypoPercentage, virtualHypos)

        return when {
            hypoPercentage > 15 -> PerformanceAnalysis(
                issueType = "FREQUENT_HYPOS",
                severity = severity,
                primaryParameter = "Hypo Risk Reduction %",
                adjustmentDirection = "INCREASE",
                confidence = min(0.9, severity * 1.3),
                reasoning = "Te veel post-maaltijd hypo's (${hypoPercentage.toInt()}%) - verhoog hypo bescherming"
            )
            metrics.timeBelowRange > 8 -> PerformanceAnalysis(
                issueType = "PERSISTENT_LOW",
                severity = severity,
                primaryParameter = "Daytime Bolus %",
                adjustmentDirection = "DECREASE",
                confidence = min(0.85, severity * 1.1),
                reasoning = "Te veel tijd onder range (${metrics.timeBelowRange.toInt()}%) - verlaag algemene agressiviteit"
            )
            virtualHypos > mealMetrics.size * 0.3 -> PerformanceAnalysis(
                issueType = "RAPID_DECLINES",
                severity = severity,
                primaryParameter = "Peak Damping %",
                adjustmentDirection = "INCREASE",
                confidence = min(0.8, severity),
                reasoning = "Te veel snelle dalingen (${virtualHypos} van ${mealMetrics.size}) - verhoog piek demping"
            )
            else -> PerformanceAnalysis(
                issueType = "MODERATE_LOW",
                severity = severity,
                primaryParameter = "Nighttime Bolus %",
                adjustmentDirection = "DECREASE",
                confidence = min(0.75, severity),
                reasoning = "Lage glucose events (${metrics.lowEvents} laag, ${metrics.veryLowEvents} zeer laag) - verlaag nacht agressiviteit"
            )
        }
    }

    private fun analyzeMixedIssues(
        metrics: GlucoseMetrics,
        mealMetrics: List<MealPerformanceMetrics>
    ): PerformanceAnalysis {
        val severity = (calculateHighBGSeverity(metrics, 0.0) + calculateLowBGSeverity(metrics, 0.0, 0)) / 2.0

        return PerformanceAnalysis(
            issueType = "MIXED_HIGH_LOW",
            severity = severity,
            primaryParameter = "Meal Detection Sensitivity",
            adjustmentDirection = "INCREASE",
            confidence = min(0.7, severity),
            reasoning = "Zowel hoge als lage glucosewaarden - optimaliseer maaltijd detectie timing"
        )
    }

    private fun calculateHighBGSeverity(metrics: GlucoseMetrics, highPeakPercentage: Double): Double {
        var severity = 0.0

        // Time Above Range component
        severity += min(metrics.timeAboveRange / 50.0, 0.4)

        // Average Glucose component
        severity += min((metrics.averageGlucose - 7.0) / 5.0, 0.3)

        // High Peaks component
        severity += min(highPeakPercentage / 100.0, 0.3)

        return severity.coerceIn(0.0, 1.0)
    }

    private fun calculateLowBGSeverity(metrics: GlucoseMetrics, hypoPercentage: Double, virtualHypos: Int): Double {
        var severity = 0.0

        // Time Below Range component
        severity += min(metrics.timeBelowRange / 15.0, 0.4)

        // Hypo Events component
        severity += min((metrics.lowEvents + metrics.veryLowEvents * 2) / 10.0, 0.3)

        // Post-meal Hypos component
        severity += min(hypoPercentage / 50.0, 0.2)

        // Virtual Hypos component
        severity += min(virtualHypos / 10.0, 0.1)

        return severity.coerceIn(0.0, 1.0)
    }

    // ★★★ BIDIRECTIONEEL ADVIES SYSTEEM ★★★
    private fun generateBidirectionalAdvice(
        parameters: FCLParameters,
        metrics: GlucoseMetrics,
        mealMetrics: List<MealPerformanceMetrics>
    ): List<ParameterAgressivenessAdvice> {
        val adviceList = mutableListOf<ParameterAgressivenessAdvice>()

        // 1. Performance-based advies (nieuw bidirectioneel systeem)
        val performanceAnalyses = analyzeBidirectionalPerformance(metrics, mealMetrics)
        adviceList.addAll(generatePerformanceBasedAdvice(parameters, performanceAnalyses))

        // 2. Learning-based advies (bestaand)
        adviceList.addAll(generateLearningBasedAdvice(parameters))

        // 3. Meal-specifiek advies (bestaand)
        adviceList.addAll(generateMealBasedAdvice(parameters))

        return adviceList
            .take(5)
            .distinctBy { it.parameterName }
            .sortedByDescending { it.confidence }
    }

    // ★★★ PRESTATIE-GEBASEERD ADVIES ★★★
    private fun generatePerformanceBasedAdvice(
        parameters: FCLParameters,
        analyses: List<PerformanceAnalysis>
    ): List<ParameterAgressivenessAdvice> {
        if (analyses.isEmpty()) return emptyList()

        return analyses.mapNotNull { analysis ->
            when (analysis.issueType) {
                "HIGH_PEAKS", "PERSISTENT_HIGH", "MODERATE_HIGH" ->
                    createBidirectionalParameterAdvice(parameters, analysis)
                "FREQUENT_HYPOS", "PERSISTENT_LOW", "RAPID_DECLINES", "MODERATE_LOW" ->
                    createBidirectionalParameterAdvice(parameters, analysis)
                "MIXED_HIGH_LOW" ->
                    createMixedIssueAdvice(parameters, analysis)
                else -> null
            }
        }
    }

    private fun createBidirectionalParameterAdvice(
        parameters: FCLParameters,
        analysis: PerformanceAnalysis
    ): ParameterAgressivenessAdvice {
        val currentValue = getCurrentParameterValue(parameters, analysis.primaryParameter)
        val definition = getParameterDefinition(parameters, analysis.primaryParameter)

        val minValue = definition?.minValue ?: getDefaultMinValue(analysis.primaryParameter)
        val maxValue = definition?.maxValue ?: getDefaultMaxValue(analysis.primaryParameter)

        val recommendedValue = when (analysis.adjustmentDirection) {
            "INCREASE" -> calculateOptimalIncrease(currentValue, maxValue)
            "DECREASE" -> calculateOptimalDecrease(currentValue, minValue)
            else -> currentValue
        }

        // ★★★ DYNAMISCHE VERWACHTE VERBETERING ★★★
        val expectedImprovement = when (analysis.adjustmentDirection) {
            "INCREASE" -> calculateExpectedImprovement(analysis, "increase")
            "DECREASE" -> calculateExpectedImprovement(analysis, "decrease")
            else -> "Geen verandering"
        }

        return ParameterAgressivenessAdvice(
            parameterName = analysis.primaryParameter,
            currentValue = currentValue,
            recommendedValue = recommendedValue,
            reason = analysis.reasoning,
            confidence = analysis.confidence,
            expectedImprovement = expectedImprovement,
            changeDirection = analysis.adjustmentDirection
        )
    }

    private fun createMixedIssueAdvice(
        parameters: FCLParameters,
        analysis: PerformanceAnalysis
    ): ParameterAgressivenessAdvice {
        val currentValue = getCurrentParameterValue(parameters, analysis.primaryParameter)
        val definition = getParameterDefinition(parameters, analysis.primaryParameter)

        val minValue = definition?.minValue ?: getDefaultMinValue(analysis.primaryParameter)
        val maxValue = definition?.maxValue ?: getDefaultMaxValue(analysis.primaryParameter)

        // Voor gemengde problemen: kleinere, conservatieve aanpassing
        val conservativeChange = (calculateOptimalIncrease(currentValue, maxValue) - currentValue) * 0.3
        val recommendedValue = currentValue + conservativeChange

        return ParameterAgressivenessAdvice(
            parameterName = analysis.primaryParameter,
            currentValue = currentValue,
            recommendedValue = recommendedValue,
            reason = analysis.reasoning,
            confidence = analysis.confidence * 0.8, // Lager vertrouwen bij gemengde problemen
            expectedImprovement = "Betere timing van maaltijd detectie voor stabielere glucose",
            changeDirection = "INCREASE"
        )
    }

    private fun calculateExpectedImprovement(analysis: PerformanceAnalysis, direction: String): String {
        val severity = analysis.severity

        return when (analysis.issueType) {
            "HIGH_PEAKS" -> "Verwacht ${(severity * 30).toInt()}% minder hoge pieken"
            "PERSISTENT_HIGH" -> "Verwacht ${(severity * 25).toInt()}% minder tijd boven range"
            "FREQUENT_HYPOS" -> "Verwacht ${(severity * 40).toInt()}% minder hypo's"
            "PERSISTENT_LOW" -> "Verwacht ${(severity * 35).toInt()}% minder tijd onder range"
            "RAPID_DECLINES" -> "Verwacht ${(severity * 20).toInt()}% minder snelle dalingen"
            "MIXED_HIGH_LOW" -> "Verwacht betere balans tussen hoge en lage glucose"
            else -> "Verwacht algemene verbetering"
        }
    }

    private fun getFallbackParameterValue(parameters: FCLParameters, parameterName: String): Double {
        return try {
            when (parameterName) {
                "bolus_perc_early" -> preferences.get(IntKey.bolus_perc_early).toDouble()
                "bolus_perc_mid" -> preferences.get(IntKey.bolus_perc_mid).toDouble()
                "bolus_perc_late" -> preferences.get(IntKey.bolus_perc_late).toDouble()
                "bolus_perc_day" -> preferences.get(IntKey.bolus_perc_day).toDouble()
                "bolus_perc_night" -> preferences.get(IntKey.bolus_perc_night).toDouble()
                "meal_detection_sensitivity" -> preferences.get(DoubleKey.meal_detection_sensitivity)
                "phase_early_rise_slope" -> preferences.get(DoubleKey.phase_early_rise_slope)
                "phase_mid_rise_slope" -> preferences.get(DoubleKey.phase_mid_rise_slope)
                "phase_late_rise_slope" -> preferences.get(DoubleKey.phase_late_rise_slope)
                "carb_percentage" -> preferences.get(IntKey.carb_percentage).toDouble()
                "peak_damping_percentage" -> preferences.get(IntKey.peak_damping_percentage).toDouble()
                "hypo_risk_percentage" -> preferences.get(IntKey.hypo_risk_percentage).toDouble()
                "IOB_corr_perc" -> preferences.get(IntKey.IOB_corr_perc).toDouble()
                else -> {
                    // Probeer parameter via FCLParameters naam
                    val allParams = parameters.getAllParameters()
                    allParams[parameterName]?.current ?: getHardcodedFallback(parameterName)
                }
            }
        } catch (e: Exception) {
            getHardcodedFallback(parameterName)
        }
    }

    private fun getHardcodedFallback(parameterName: String): Double {
        return when (parameterName) {
            "bolus_perc_early" -> 100.0
            "bolus_perc_mid" -> 100.0
            "bolus_perc_late" -> 100.0
            "bolus_perc_day" -> 100.0
            "bolus_perc_night" -> 80.0
            "meal_detection_sensitivity" -> 0.3
            "phase_early_rise_slope" -> 1.0
            "phase_mid_rise_slope" -> 0.5
            "phase_late_rise_slope" -> 0.2
            "carb_percentage" -> 100.0
            "peak_damping_percentage" -> 30.0
            "hypo_risk_percentage" -> 20.0
            "IOB_corr_perc" -> 100.0
            else -> 0.0
        }
    }

    // ★★★ VERBETERDE PARAMETER DEFINITIE RETRIEVAL ★★★
    private fun getParameterDefinition(parameters: FCLParameters, parameterName: String): FCLParameters.ParameterDefinition? {
        return try {
            // Probeer directe naam
            parameters.getParameterDefinition(parameterName) ?:
            // Probeer technische naam mapping
            mapTechnicalToDisplayName(parameterName)?.let { displayName ->
                parameters.getParameterDefinition(displayName)
            }
        } catch (e: Exception) {
            null
        }
    }

    // ★★★ TECHNICAL TO DISPLAY NAME MAPPING ★★★
    private fun mapTechnicalToDisplayName(technicalName: String): String? {
        return when (technicalName) {
            "bolus_perc_early" -> "Early Rise Bolus %"
            "bolus_perc_mid" -> "Mid Rise Bolus %"
            "bolus_perc_late" -> "Late Rise Bolus %"
            "bolus_perc_day" -> "Daytime Bolus %"
            "bolus_perc_night" -> "Nighttime Bolus %"
            "meal_detection_sensitivity" -> "Meal Detection Sensitivity"
            "phase_early_rise_slope" -> "Early Rise Slope"
            "phase_mid_rise_slope" -> "Mid Rise Slope"
            "phase_late_rise_slope" -> "Late Rise Slope"
            "carb_percentage" -> "Carb Detection %"
            "peak_damping_percentage" -> "Peak Damping %"
            "hypo_risk_percentage" -> "Hypo Risk Bolus %"
            "IOB_corr_perc" -> "IOB Safety %"
            else -> null
        }
    }

    // ★★★ LEARNING-BASED ADVIES IMPLEMENTATIE ★★★
    private fun generateLearningBasedAdvice(parameters: FCLParameters): List<ParameterAgressivenessAdvice> {
        val learnedAdjustments = lastParameterAdjustments
            .filter { it.learned && it.improvement > 15.0 }
            .sortedByDescending { it.improvement }

        return learnedAdjustments.take(2).mapNotNull { adjustment ->
            val currentValue = getCurrentParameterValue(parameters, adjustment.parameterName)

            if (abs(currentValue - adjustment.newValue) > 0.1) {
                ParameterAgressivenessAdvice(
                    parameterName = adjustment.parameterName,
                    currentValue = currentValue,
                    recommendedValue = adjustment.newValue,
                    reason = "Bewezen verbetering: ${adjustment.improvement.toInt()}% effectiviteit in eerdere aanpassing",
                    confidence = min(0.9, adjustment.improvement / 100.0),
                    expectedImprovement = "Verwacht ${adjustment.improvement.toInt()}% verbetering gebaseerd op historische data",
                    changeDirection = if (adjustment.newValue > currentValue) "INCREASE" else "DECREASE"
                )
            } else {
                null
            }
        }
    }


/*    private fun generateModulatedAdvice(
        parameters: FCLParameters,
        metrics: GlucoseMetrics,
        mealMetrics: List<MealPerformanceMetrics>
    ): List<ParameterAgressivenessAdvice> {
        // ★★★ GEBRUIK BIDIRECTIONEEL ADVIES SYSTEEM ★★★
        return generateBidirectionalAdvice(parameters, metrics, mealMetrics)
    }    */

    private fun generateModulatedAdvice(
        parameters: FCLParameters,
        metrics: GlucoseMetrics,
        mealMetrics: List<MealPerformanceMetrics>
    ): List<ParameterAgressivenessAdvice> {
        // ★★★ GEBRUIK ECHTE ADVIEZEN OP BASIS VAN METRICS ★★★
        return generateRealAdvice(parameters, metrics, mealMetrics)
    }

    private fun modulateAdviceWithHistory(
        rawAdvice: ParameterAgressivenessAdvice,
        history: Map<String, ParameterAdviceHistory>,
        mealMetrics: List<MealPerformanceMetrics>
    ): ParameterAgressivenessAdvice {

        val parameterHistory = history[rawAdvice.parameterName]
        if (parameterHistory == null || parameterHistory.adviceHistory.size < 2) {
            return rawAdvice // Geen geschiedenis, gebruik raw advies
        }

        val recentHistory = parameterHistory.adviceHistory.takeLast(3)
        val currentTrend = analyzeParameterTrend(recentHistory)

        return when {
            // ★★★ CONFLICT DETECTIE: Nieuw advies gaat tegen trend in ★★★
            isConflictingWithTrend(rawAdvice, currentTrend) -> {
                createStabilizedAdvice(rawAdvice, currentTrend, recentHistory)
            }

            // ★★★ TREND BEVESTIGING: Nieuw advies bevestigt bestaande trend ★★★
            isConfirmingTrend(rawAdvice, currentTrend) -> {
                createReinforcedAdvice(rawAdvice, currentTrend, recentHistory)
            }

            // ★★★ NEUTRAAL: Geen duidelijke trend ★★★
            else -> {
                createConservativeAdvice(rawAdvice, recentHistory)
            }
        }
    }



    private fun analyzeParameterTrend(history: List<HistoricalAdvice>): String {
        if (history.size < 2) return "STABLE"

        val changes = history.zipWithNext().map { (prev, current) ->
            current.recommendedValue - prev.recommendedValue
        }

        val avgChange = changes.average()
        return when {
            avgChange > 0.5 -> "INCREASING"
            avgChange < -0.5 -> "DECREASING"
            else -> "STABLE"
        }
    }

    private fun isConflictingWithTrend(
        advice: ParameterAgressivenessAdvice,
        trend: String
    ): Boolean {
        return when (trend) {
            "INCREASING" -> advice.changeDirection == "DECREASE"
            "DECREASING" -> advice.changeDirection == "INCREASE"
            else -> false
        }
    }

    private fun isConfirmingTrend(
        advice: ParameterAgressivenessAdvice,
        trend: String
    ): Boolean {
        return when (trend) {
            "INCREASING" -> advice.changeDirection == "INCREASE"
            "DECREASING" -> advice.changeDirection == "DECREASE"
            else -> false
        }
    }

    private fun createStabilizedAdvice(
        rawAdvice: ParameterAgressivenessAdvice,
        trend: String,
        history: List<HistoricalAdvice>
    ): ParameterAgressivenessAdvice {
        // ★★★ BIJ CONFLICT: KLEINERE AANPASSING EN LAGER VERTROUWEN ★★★
        val historicalValues = history.map { it.recommendedValue }
        val avgHistoricalValue = historicalValues.average()

        // Bereken gemoduleerde waarde (50% van voorgestelde verandering)
        val proposedChange = rawAdvice.recommendedValue - rawAdvice.currentValue
        val modulatedChange = proposedChange * 0.5
        val modulatedValue = rawAdvice.currentValue + modulatedChange

        // Verlaag vertrouwen bij tegenstrijdige adviezen
        val modulatedConfidence = rawAdvice.confidence * 0.6

        return rawAdvice.copy(
            recommendedValue = modulatedValue,
            confidence = modulatedConfidence,
            reason = rawAdvice.reason + " (gemoduleerd: conflict met eerdere trend)",
            expectedImprovement = "Kleinere aanpassing wegens tegenstrijdige trends"
        )
    }

    private fun createReinforcedAdvice(
        rawAdvice: ParameterAgressivenessAdvice,
        trend: String,
        history: List<HistoricalAdvice>
    ): ParameterAgressivenessAdvice {
        // ★★★ BIJ BEVESTIGING: BEHOUD VERTROUWEN ★★★
        return rawAdvice.copy(
            confidence = min(rawAdvice.confidence * 1.1, 0.95),
            reason = rawAdvice.reason + " (bevestigt eerdere trend)",
            expectedImprovement = rawAdvice.expectedImprovement + " - trend bevestigd"
        )
    }

    private fun createConservativeAdvice(
        rawAdvice: ParameterAgressivenessAdvice,
        history: List<HistoricalAdvice>
    ): ParameterAgressivenessAdvice {
        // ★★★ BIJ GEEN TREND: CONSERVATIEVE AANPASSING ★★★
        val modulatedChange = (rawAdvice.recommendedValue - rawAdvice.currentValue) * 0.8
        val modulatedValue = rawAdvice.currentValue + modulatedChange

        return rawAdvice.copy(
            recommendedValue = modulatedValue,
            reason = rawAdvice.reason + " (conservatieve aanpassing)"
        )
    }

    // ★★★ MAALTIJD-GEBASEERD ADVIES ★★★
    private fun generateMealBasedAdvice(parameters: FCLParameters): List<ParameterAgressivenessAdvice> {
        val mealMetrics = calculateMealPerformanceMetrics(168)
        val recentMeals = mealMetrics.filter { it.mealStartTime.isAfter(DateTime.now().minusDays(7)) }

        // ★★★ VERMINDER DREMPEL NAAR 1 MAALTIJD VOOR INITIEEL ADVIES ★★★
        if (recentMeals.size < 1) {
            return listOf(createInitialSetupAdvice(parameters))
        }

        if (recentMeals.size < 3) {
            return analyzeLimitedMealData(parameters, recentMeals)
        }

        return analyzeMealPerformance(parameters, recentMeals)
    }

    private fun createInitialSetupAdvice(parameters: FCLParameters): ParameterAgressivenessAdvice {
        return ParameterAgressivenessAdvice(
            parameterName = "Meal Detection Sensitivity",
            currentValue = getCurrentParameterValue(parameters, "Meal Detection Sensitivity"),
            recommendedValue = 0.3,
            reason = "Startup advice: Verhoog maaltijd detectie gevoeligheid voor betere herkenning",
            confidence = 0.6,
            expectedImprovement = "Betere herkenning van maaltijd start",
            changeDirection = "INCREASE"
        )
    }

    private fun analyzeLimitedMealData(
        parameters: FCLParameters,
        meals: List<MealPerformanceMetrics>
    ): List<ParameterAgressivenessAdvice> {
        val advice = mutableListOf<ParameterAgressivenessAdvice>()

        if (meals.isNotEmpty()) {
            val successRate = calculateSuccessRate(meals)
            val avgPeak = meals.map { it.peakBG }.average()

            if (avgPeak > 11.0) {
                advice.add(createParameterAdvice(
                    parameters, "Early Rise Bolus %", "INCREASE",
                    "Hoge piekwaarden (gem: ${round(avgPeak, 1)} mmol/L) bij beperkte data",
                    successRate
                ))
            }

            if (successRate < 0.5) {
                advice.add(createParameterAdvice(
                    parameters, "Meal Detection Sensitivity", "INCREASE",
                    "Lage succesrate (${(successRate * 100).toInt()}%) bij eerste maaltijden",
                    successRate
                ))
            }
        }

        return advice.ifEmpty {
            listOf(createInitialSetupAdvice(parameters))
        }
    }



    private fun analyzeMealTiming(
        parameters: FCLParameters,
        mealMetrics: List<MealPerformanceMetrics>
    ): List<ParameterAgressivenessAdvice> {
        val mealsWithBolus = mealMetrics.filter { it.timeToFirstBolus > 0 }
        if (mealsWithBolus.isEmpty()) return emptyList()

        val avgTimeToFirstBolus = mealsWithBolus.map { it.timeToFirstBolus }.average()

        return if (avgTimeToFirstBolus > 20) {
            listOf(createTimingAdvice(parameters, avgTimeToFirstBolus, calculateSuccessRate(mealMetrics)))
        } else {
            emptyList()
        }
    }

    private fun analyzePeakControl(
        parameters: FCLParameters,
        mealMetrics: List<MealPerformanceMetrics>
    ): List<ParameterAgressivenessAdvice> {
        val highPeaks = mealMetrics.count { it.peakBG > 11.0 }
        val peakPercentage = (highPeaks.toDouble() / mealMetrics.size) * 100

        return if (peakPercentage > 30) {
            listOf(createPeakReductionAdvice(parameters, peakPercentage, calculateSuccessRate(mealMetrics)))
        } else {
            emptyList()
        }
    }

    private fun analyzeHypoSafety(
        parameters: FCLParameters,
        mealMetrics: List<MealPerformanceMetrics>
    ): List<ParameterAgressivenessAdvice> {
        val postMealHypos = mealMetrics.count { it.postMealHypo }
        val virtualHypos = mealMetrics.count { it.rapidDeclineDetected }
        val totalHypos = postMealHypos + virtualHypos
        val hypoPercentage = (totalHypos.toDouble() / mealMetrics.size) * 100

        // ★★★ AANGEPASTE DREMPEL MET VIRTUELE HYPO'S ★★★
        return if (hypoPercentage > 10) { // Lagere drempel omdat virtuele hypo's meegenomen worden
            listOf(createHypoSafetyAdvice(parameters, hypoPercentage, calculateSuccessRate(mealMetrics)))
        } else {
            emptyList()
        }
    }



    private fun analyzeSuccessRate(
        parameters: FCLParameters,
        mealMetrics: List<MealPerformanceMetrics>
    ): List<ParameterAgressivenessAdvice> {
        val successRate = calculateSuccessRate(mealMetrics)

        return if (successRate < 0.6) {
            analyzeCommonMealProblems(parameters, mealMetrics, successRate)
        } else {
            emptyList()
        }
    }

    // ★★★ GESTANDAARDISEERDE ADVIES CREATIE MET CORRECTE MIN/MAX WAARDEN ★★★
    private fun createTimingAdvice(
        parameters: FCLParameters,
        avgTimeToFirstBolus: Double,
        successRate: Double
    ): ParameterAgressivenessAdvice {
        val parameterName = "Meal Detection Sensitivity"
        val currentValue = getCurrentParameterValue(parameters, parameterName)
        val definition = getParameterDefinition(parameters, parameterName)

        val minValue = definition?.minValue ?: 0.1
        val maxValue = definition?.maxValue ?: 0.5

        return ParameterAgressivenessAdvice(
            parameterName = parameterName,
            currentValue = currentValue,
            recommendedValue = calculateOptimalDecrease(currentValue, minValue),
            reason = "Lange responstijd: ${avgTimeToFirstBolus.toInt()} min tot eerste bolus (successRate: ${(successRate * 100).toInt()}%)",
            confidence = 0.7,
            expectedImprovement = "Verlaag responstijd met ~${(avgTimeToFirstBolus * 0.3).toInt()} minuten",
            changeDirection = "DECREASE"
        )
    }

    private fun createPeakReductionAdvice(
        parameters: FCLParameters,
        peakPercentage: Double,
        successRate: Double
    ): ParameterAgressivenessAdvice {
        val parameterName = "Early Rise Bolus %"
        val currentValue = getCurrentParameterValue(parameters, parameterName)
        val definition = getParameterDefinition(parameters, parameterName)

        val minValue = definition?.minValue ?: 10.0
        val maxValue = definition?.maxValue ?: 200.0

        return ParameterAgressivenessAdvice(
            parameterName = parameterName,
            currentValue = currentValue,
            recommendedValue = calculateOptimalIncrease(currentValue, maxValue),
            reason = "Hoge pieken: ${peakPercentage.toInt()}% van maaltijden > 11 mmol/L (successRate: ${(successRate * 100).toInt()}%)",
            confidence = 0.8,
            expectedImprovement = "Verlaag pieken met ~${(peakPercentage * 0.4).toInt()}%",
            changeDirection = "INCREASE"
        )
    }


    private fun createHypoSafetyAdvice(
        parameters: FCLParameters,
        hypoPercentage: Double,
        successRate: Double
    ): ParameterAgressivenessAdvice {
        val parameterName = "Hypo Risk Bolus %"
        val currentValue = getCurrentParameterValue(parameters, parameterName)
        val definition = getParameterDefinition(parameters, parameterName)

        val minValue = definition?.minValue ?: 10.0
        val maxValue = definition?.maxValue ?: 50.0

        return ParameterAgressivenessAdvice(
            parameterName = parameterName,
            currentValue = currentValue,
            recommendedValue = calculateOptimalDecrease(currentValue, minValue), // ← CORRECTIE: gebruik DECREASE
            reason = "Hoge hypo ratio: ${hypoPercentage.toInt()}% van maaltijden met hypo (successRate: ${(successRate * 100).toInt()}%)",
            confidence = 0.8,
            expectedImprovement = "Verlaag hypo's met ~${(hypoPercentage * 0.5).toInt()}%",
            changeDirection = "DECREASE"
        )
    }

    // ★★★ PROBLEEM-SPECIFIEK ADVIES ★★★
    private fun analyzeCommonMealProblems(
        parameters: FCLParameters,
        mealMetrics: List<MealPerformanceMetrics>,
        successRate: Double
    ): List<ParameterAgressivenessAdvice> {
        val problems = detectCommonProblems(mealMetrics)
        return problems.map { problem -> createProblemSpecificAdvice(parameters, problem, mealMetrics, successRate) }
    }

    private fun detectCommonProblems(mealMetrics: List<MealPerformanceMetrics>): List<String> {
        val problems = mutableListOf<String>()

        val latePeaks = mealMetrics.count { it.timeToPeak > 90 }
        val earlyPeaks = mealMetrics.count { it.timeToPeak < 45 }
        val hypos = mealMetrics.count { it.postMealHypo }
        val highPeaks = mealMetrics.count { it.peakBG > 11.0 }

        if (latePeaks > mealMetrics.size * 0.3) problems.add("late_peaks")
        if (earlyPeaks > mealMetrics.size * 0.3) problems.add("early_peaks")
        if (hypos > mealMetrics.size * 0.2) problems.add("post_meal_hypos")
        if (highPeaks > mealMetrics.size * 0.4) problems.add("high_peaks")

        return problems
    }

    private fun createProblemSpecificAdvice(
        parameters: FCLParameters,
        problem: String,
        mealMetrics: List<MealPerformanceMetrics>,
        successRate: Double
    ): ParameterAgressivenessAdvice {
        return when (problem) {
            "late_peaks" -> createParameterAdvice(
                parameters, "Early Rise Bolus %", "INCREASE",
                "Te late pieken in ${(mealMetrics.count { it.timeToPeak > 90 }.toDouble() / mealMetrics.size * 100).toInt()}% van maaltijden",
                successRate
            )
            "early_peaks" -> createParameterAdvice(
                parameters, "Mid Rise Bolus %", "INCREASE",
                "Te vroege pieken in ${(mealMetrics.count { it.timeToPeak < 45 }.toDouble() / mealMetrics.size * 100).toInt()}% van maaltijden",
                successRate
            )
            "post_meal_hypos" -> createParameterAdvice(
                parameters, "Hypo Risk Bolus %", "DECREASE",
                "Post-maaltijd hypo's in ${(mealMetrics.count { it.postMealHypo }.toDouble() / mealMetrics.size * 100).toInt()}% van maaltijden",
                successRate
            )
            "high_peaks" -> createParameterAdvice(
                parameters, "Peak Damping %", "INCREASE",
                "Hoge pieken (>11 mmol/L) in ${(mealMetrics.count { it.peakBG > 11.0 }.toDouble() / mealMetrics.size * 100).toInt()}% van maaltijden",
                successRate
            )
            else -> createGenericAdvice(parameters, successRate)
        }
    }

    private fun createParameterAdvice(
        parameters: FCLParameters,
        parameterName: String,
        direction: String,
        reason: String,
        successRate: Double
    ): ParameterAgressivenessAdvice {
        val currentValue = getCurrentParameterValue(parameters, parameterName)
        val definition = getParameterDefinition(parameters, parameterName)

        val minValue = definition?.minValue ?: getDefaultMinValue(parameterName)
        val maxValue = definition?.maxValue ?: getDefaultMaxValue(parameterName)

        val recommendedValue = when (direction) {
            "INCREASE" -> calculateOptimalIncrease(currentValue, maxValue)
            "DECREASE" -> calculateOptimalDecrease(currentValue, minValue)
            else -> currentValue
        }

        return ParameterAgressivenessAdvice(
            parameterName = parameterName,
            currentValue = currentValue,
            recommendedValue = recommendedValue,
            reason = "$reason (successRate: ${(successRate * 100).toInt()}%)",
            confidence = 0.7,
            expectedImprovement = "Verbeter ${parameterName.replace("_", " ")}",
            changeDirection = direction
        )
    }

    // ★★★ DEFAULT WAARDEN VOOR PARAMETERS ★★★
    private fun getDefaultMinValue(parameterName: String): Double {
        return when (parameterName) {
            "Early Rise Bolus %", "Mid Rise Bolus %", "Late Rise Bolus %", "Daytime Bolus %" -> 10.0
            "Nighttime Bolus %" -> 5.0
            "Meal Detection Sensitivity" -> 0.1
            "Early Rise Slope", "Mid Rise Slope" -> 0.3
            "Late Rise Slope" -> 0.1
            "Carb Detection %" -> 10.0
            "Peak Damping %" -> 10.0
            "Hypo Risk Bolus %" -> 10.0
            "IOB Safety %" -> 50.0
            else -> 0.0
        }
    }

    private fun getDefaultMaxValue(parameterName: String): Double {
        return when (parameterName) {
            "Early Rise Bolus %", "Mid Rise Bolus %", "Late Rise Bolus %", "Daytime Bolus %" -> 200.0
            "Nighttime Bolus %" -> 100.0
            "Meal Detection Sensitivity" -> 0.5
            "Early Rise Slope", "Mid Rise Slope" -> 2.5
            "Late Rise Slope" -> 1.0
            "Carb Detection %" -> 200.0
            "Peak Damping %" -> 100.0
            "Hypo Risk Bolus %" -> 50.0
            "IOB Safety %" -> 150.0
            else -> 100.0
        }
    }



    private fun createInsufficientDataAdvice(metrics: GlucoseMetrics): List<ParameterAgressivenessAdvice> {
        return listOf(
            ParameterAgressivenessAdvice(
                parameterName = "Alle parameters",
                currentValue = 0.0,
                recommendedValue = 0.0,
                reason = "Onvoldoende data voor analyse (${metrics.totalReadings} metingen, ${metrics.readingsPerHour.toInt()}/u)",
                confidence = 0.0,
                expectedImprovement = "Meer data verzamelen",
                changeDirection = "OPTIMAL"
            )
        )
    }

    private fun createInsufficientMealDataAdvice(parameters: FCLParameters, mealCount: Int): ParameterAgressivenessAdvice {
        return createParameterAdvice(
            parameters, "Meal Detection Sensitivity", "INCREASE",
            "Onvoldoende maaltijd data ($mealCount van 3 vereist)", 0.4
        )
    }

    private fun createGenericAdvice(parameters: FCLParameters, successRate: Double): ParameterAgressivenessAdvice {
        return createParameterAdvice(
            parameters, "Meal Detection Sensitivity", "INCREASE",
            "Algemene prestatie verbetering nodig", successRate
        )
    }

    // ★★★ DATA OPSLAG EN RETRIEVAL ★★★
    private fun getStoredAdvice(): List<ParameterAgressivenessAdvice> {
        return try {
            val json = prefs.getString("stored_advice", null)
            if (json != null) {
                val type = object : com.google.gson.reflect.TypeToken<List<ParameterAgressivenessAdvice>>() {}.type
                gson.fromJson<List<ParameterAgressivenessAdvice>>(json, type) ?: emptyList()
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun storeAdvice(advice: List<ParameterAgressivenessAdvice>) {
        try {
            val json = gson.toJson(advice)
            prefs.edit().putString("stored_advice", json).apply()
        } catch (e: Exception) {
            // Logging
        }
    }

    // ★★★ PUBLIC FUNCTIES ★★★
    fun getCurrentAdvice(): List<ParameterAgressivenessAdvice> {
        return getStoredAdvice()
    }

    fun getLastUpdateTimes(): Pair<DateTime, DateTime> {
        return Pair(getLastMetricsTime(), getLastAdviceTime())
    }

    // ★★★ CSV DATA VERWERKING ★★★
    private fun getOrCreateCSVFile(): File {
        val externalDir = File(Environment.getExternalStorageDirectory().absolutePath + "/Documents/AAPS/")
        val analyseDir = File(externalDir, "ANALYSE")
        if (!analyseDir.exists()) {
            analyseDir.mkdirs()
        }
        return File(analyseDir, "FCL_Analysis.csv")
    }

    private fun loadCSVData(hours: Int): List<CSVReading> {
        val csvFile = getOrCreateCSVFile()
        if (!csvFile.exists()) {
            return emptyList()
        }

        val readings = mutableListOf<CSVReading>()
        val cutoffTime = DateTime.now().minusHours(hours)

        try {
            var isFirstLine = true
            csvFile.forEachLine { line ->
                if (isFirstLine) {
                    isFirstLine = false
                    return@forEachLine
                }

                val parts = line.split(",")
                // ★★★ AANGEPAST: Nu 17 kolommen verwachten i.p.v. 15 ★★★
                if (parts.size >= 17) {
                    try {
                        val timestamp = dateFormatter.parseDateTime(parts[0])
                        if (timestamp.isAfter(cutoffTime)) {
                            val currentBG = parts[1].toDoubleOrNull() ?: return@forEachLine
                            val currentIOB = parts[2].toDoubleOrNull() ?: 0.0
                            val dose = parts[4].toDoubleOrNull() ?: 0.0

                            val shouldDeliver = when {
                                parts[6].equals("true", ignoreCase = true) -> true
                                parts[6].equals("false", ignoreCase = true) -> false
                                else -> false
                            }

                            val mealDetected = when {
                                parts[11].equals("true", ignoreCase = true) -> true
                                parts[11].equals("false", ignoreCase = true) -> false
                                else -> false
                            }

                            // ★★★ AANGEPAST: Kolom indices aangepast voor nieuwe structuur ★★★
                            val detectedCarbs = parts[13].toDoubleOrNull() ?: 0.0
                            val carbsOnBoard = parts[14].toDoubleOrNull() ?: 0.0

                            readings.add(
                                CSVReading(
                                    timestamp = timestamp,
                                    currentBG = currentBG,
                                    currentIOB = currentIOB,
                                    dose = dose,
                                    shouldDeliver = shouldDeliver,
                                    mealDetected = mealDetected,
                                    detectedCarbs = detectedCarbs,
                                    carbsOnBoard = carbsOnBoard
                                )
                            )
                        }
                    } catch (e: Exception) {
                        // Skip invalid lines
                    }
                }
            }
        } catch (e: Exception) {
            // Error reading file
        }

        return readings.sortedBy { it.timestamp }
    }


    // ★★★ VERBETERDE MAALTIJD DETECTIE - DIRECT INTEGRATIE MET FCL ★★★
    private fun calculateEnhancedMealPerformanceMetrics(hours: Int = 168): List<MealPerformanceMetrics> {
        val meals = mutableListOf<MealPerformanceMetrics>()
        val cutoffTime = DateTime.now().minusHours(hours)

        try {
            val csvData = loadCSVData(hours)
            if (csvData.isEmpty()) return emptyList()

            // Zoek naar maaltijd patronen in de data
            var currentMealStart: CSVReading? = null
            var mealPeakBG = 0.0
            var mealPeakTime: DateTime? = null
            var totalCarbsDetected = 0.0
            var totalInsulinDelivered = 0.0
            var firstBolusTime: DateTime? = null

            for (i in 0 until csvData.size - 1) {
                val current = csvData[i]
                val next = csvData[i + 1]

                // ★★★ VERBETERDE MAALTIJD START DETECTIE ★★★
                val isMealStart = when {
                    current.mealDetected -> true
                    current.detectedCarbs > 15.0 -> true
                    current.dose > 0.1 && current.currentBG > Target_Bg + 1.0 -> true
                    next.currentBG - current.currentBG > 1.5 && current.currentIOB < 1.0 -> true
                    else -> false
                }

                if (isMealStart && currentMealStart == null && current.timestamp.isAfter(cutoffTime)) {
                    currentMealStart = current
                    mealPeakBG = current.currentBG
                    mealPeakTime = current.timestamp
                    totalCarbsDetected = current.detectedCarbs
                    totalInsulinDelivered = 0.0
                    firstBolusTime = null
                }

                // Update huidige maaltijd
                currentMealStart?.let { mealStart ->
                    if (current.currentBG > mealPeakBG) {
                        mealPeakBG = current.currentBG
                        mealPeakTime = current.timestamp
                    }

                    totalCarbsDetected = max(totalCarbsDetected, current.detectedCarbs)
                    if (current.dose > 0.05) {
                        totalInsulinDelivered += current.dose
                        if (firstBolusTime == null && current.shouldDeliver) {
                            firstBolusTime = current.timestamp
                        }
                    }

                    // ★★★ VERBETERDE MAALTIJD EINDE DETECTIE ★★★
                    val mealDuration = Minutes.minutesBetween(mealStart.timestamp, current.timestamp).minutes
                    val isMealEnd = when {
                        mealDuration > 240 -> true
                        current.currentBG <= mealStart.currentBG + 0.5 && mealDuration > 90 -> true
                        mealPeakTime != null && current.currentBG < mealPeakBG - 2.0 -> true
                        i < csvData.size - 10 && isQuietPeriod(csvData.subList(i, min(i + 10, csvData.size))) -> true
                        else -> false
                    }

                    if (isMealEnd) {
                        val mealMetrics = createMealPerformanceMetrics(
                            start = mealStart,
                            end = current,
                            peakBG = mealPeakBG,
                            peakTime = mealPeakTime ?: mealStart.timestamp,
                            totalCarbs = totalCarbsDetected,
                            totalInsulin = totalInsulinDelivered,
                            firstBolusTime = firstBolusTime,
                            historicalData = csvData // NIEUWE PARAMETER
                        )

                        if (isValidMeal(mealMetrics)) {
                            meals.add(mealMetrics)
                        }

                        currentMealStart = null
                    }
                }
            }

            currentMealStart?.let { mealStart ->
                val lastReading = csvData.last()
                val mealMetrics = createMealPerformanceMetrics(
                    start = mealStart,
                    end = lastReading,
                    peakBG = mealPeakBG,
                    peakTime = mealPeakTime ?: mealStart.timestamp,
                    totalCarbs = totalCarbsDetected,
                    totalInsulin = totalInsulinDelivered,
                    firstBolusTime = firstBolusTime,
                    historicalData = csvData // ★★★ NIEUWE PARAMETER TOEVOEGEN ★★★
                )

                if (isValidMeal(mealMetrics)) {
                    meals.add(mealMetrics)
                }
            }

        } catch (e: Exception) {
            // Log de fout
        }

        return meals.sortedBy { it.mealStartTime }
    }



    // ★★★ NIEUWE HELPER FUNCTIES VOOR MAALTIJD ANALYSE ★★★
    private fun calculateTimeAbove10(start: DateTime, end: DateTime): Int {
        val data = getCSVDataBetween(start, end)
        val readingsAbove10 = data.count { it.currentBG > 10.0 }
        return readingsAbove10 * 5 // 5 minuten per meting
    }

    private fun calculateMaxIOB(start: DateTime, end: DateTime): Double {
        val data = getCSVDataBetween(start, end)
        return data.map { it.currentIOB }.maxOrNull() ?: 0.0
    }

    private fun getCSVDataBetween(start: DateTime, end: DateTime): List<CSVReading> {
        val allData = loadCSVData(168) // Laad voldoende data
        return allData.filter { it.timestamp in start..end }
    }

    private fun calculateTIRDuringMeal(start: DateTime, end: DateTime): Double {
        val mealData = getCSVDataBetween(start, end)
        if (mealData.isEmpty()) return 0.0
        val inRange = mealData.count { it.currentBG in TARGET_LOW..TARGET_HIGH }
        return (inRange.toDouble() / mealData.size) * 100.0
    }

    private fun hasPostMealHypo(start: CSVReading, end: CSVReading, peakTime: DateTime): Boolean {
        // Check op hypo binnen 4 uur na piek
        val hypoCutoff = peakTime.plusHours(4)
        val relevantReadings = getCSVDataBetween(peakTime, hypoCutoff)
        return relevantReadings.any { it.currentBG < 3.9 }
    }

    // ★★★ VIRTUELE HYPO DETECTIE OP BASIS VAN DAALSNELHEID ★★★
    private fun calculateVirtualHypoMetrics(
        start: CSVReading,
        end: CSVReading,
        peakTime: DateTime,
        historicalData: List<CSVReading>
    ): Triple<Double?, Boolean, Double> {
        val declineStart = peakTime
        val declineEnd = if (peakTime.plusMinutes(120).isBefore(end.timestamp)) {
            peakTime.plusMinutes(120)
        } else {
            end.timestamp
        }

        val declineData = historicalData.filter {
            it.timestamp in declineStart..declineEnd
        }

        if (declineData.size < 4) return Triple(null, false, 0.0)

        // Bereken daalsnelheid in mmol/L per uur
        val timeDiffHours = Hours.hoursBetween(declineStart, declineEnd).hours.toDouble()
        val bgDiff = declineData.last().currentBG - declineData.first().currentBG
        val declineRate = if (timeDiffHours > 0) bgDiff / timeDiffHours else 0.0

        // Detecteer snelle daling (>2 mmol/L per uur)
        val rapidDecline = declineRate < -2.0

        // Bereken virtuele hypo score
        val virtualHypoScore = calculateVirtualHypoScore(declineData, declineRate)

        return Triple(declineRate, rapidDecline, virtualHypoScore)
    }

    private fun calculateVirtualHypoScore(
        declineData: List<CSVReading>,
        declineRate: Double
    ): Double {
        if (declineData.size < 3) return 0.0

        var score = 0.0

        // Factor 1: Daalsnelheid
        score += min(abs(declineRate) * 0.5, 3.0)

        // Factor 2: IOB tijdens daling
        val avgIOB = declineData.map { it.currentIOB }.average()
        if (avgIOB > 2.0) score += 1.0
        if (avgIOB > 3.0) score += 1.0

        // Factor 3: Consistentie van daling
        val slopes = declineData.zipWithNext().map { (a, b) ->
            val timeDiff = Minutes.minutesBetween(a.timestamp, b.timestamp).minutes / 60.0
            if (timeDiff > 0) (b.currentBG - a.currentBG) / timeDiff else 0.0
        }
        val consistentDecline = slopes.all { it < -0.5 }
        if (consistentDecline) score += 1.0

        // Factor 4: Nabijheid van hypo drempel
        val minBG = declineData.minOf { it.currentBG }
        if (minBG < 4.5) score += 1.0
        if (minBG < 4.0) score += 1.0

        return min(score, 8.0) // Max score 8
    }

    private fun isValidMeal(metrics: MealPerformanceMetrics): Boolean {
        return metrics.totalCarbsDetected >= 10.0 ||
            metrics.totalInsulinDelivered >= 0.5 ||
            (metrics.peakBG - metrics.startBG) >= 2.0
    }

    private fun isQuietPeriod(readings: List<CSVReading>): Boolean {
        if (readings.size < 3) return false
        return readings.all { it.detectedCarbs < 5.0 && it.dose < 0.05 }
    }

    private fun createMealPerformanceMetrics(
        start: CSVReading,
        end: CSVReading,
        peakBG: Double,
        peakTime: DateTime,
        totalCarbs: Double,
        totalInsulin: Double,
        firstBolusTime: DateTime?,
        historicalData: List<CSVReading> // NIEUWE PARAMETER
    ): MealPerformanceMetrics {

        // ★★★ NIEUW: Bereken virtuele hypo metrics ★★★
        val (declineRate, rapidDecline, virtualHypoScore) = calculateVirtualHypoMetrics(
            start, end, peakTime, historicalData
        )

        val timeToPeak = Minutes.minutesBetween(start.timestamp, peakTime).minutes
        val timeToFirstBolus = firstBolusTime?.let {
            Minutes.minutesBetween(start.timestamp, it).minutes
        } ?: 0

        // ★★★ VERBETERDE SUCCESS BEREKENING MET VIRTUELE HYPO ★★★
        val wasSuccessful = !hasPostMealHypo(start, end, peakTime) &&
            !rapidDecline && // Voeg virtuele hypo detectie toe
            peakBG <= 11.0 &&
            end.currentBG <= start.currentBG + 3.0 &&
            virtualHypoScore < 3.0 // Max acceptabele virtuele hypo score

        return MealPerformanceMetrics(
            mealId = "meal_${start.timestamp.millis}",
            mealStartTime = start.timestamp,
            mealEndTime = end.timestamp,
            startBG = start.currentBG,
            peakBG = peakBG,
            endBG = end.currentBG,
            timeToPeak = timeToPeak,
            totalCarbsDetected = totalCarbs,
            totalInsulinDelivered = totalInsulin,
            peakAboveTarget = max(0.0, peakBG - Target_Bg),
            timeAbove10 = calculateTimeAbove10(start.timestamp, end.timestamp),
            postMealHypo = hasPostMealHypo(start, end, peakTime),
            timeInRangeDuringMeal = calculateTIRDuringMeal(start.timestamp, end.timestamp),
            phaseInsulinBreakdown = emptyMap(),
            firstBolusTime = firstBolusTime,
            timeToFirstBolus = timeToFirstBolus,
            maxIOBDuringMeal = calculateMaxIOB(start.timestamp, end.timestamp),
            wasSuccessful = wasSuccessful,
            mealType = determineMealType(start.timestamp),
            declineRate = declineRate,
            rapidDeclineDetected = rapidDecline,
            virtualHypoScore = virtualHypoScore
        )
    }



    // ★★★ GLUCOSE METRICS BEREKENING ★★★
    private fun calculateGlucoseMetrics(data: List<CSVReading>, hours: Int): GlucoseMetrics {
        val bgValues = data.map { it.currentBG }

        val timeInRange = calculateTimeInRange(data)
        val timeBelowRange = calculateTimeBelowRange(data)
        val timeAboveRange = calculateTimeAboveRange(data)
        val timeBelowTarget = calculateTimeBelowTarget(data)
        val averageGlucose = if (bgValues.isNotEmpty()) bgValues.average() else 0.0
        val gmi = calculateGMI(averageGlucose)
        val cv = calculateCV(bgValues)

        val (lowEvents, veryLowEvents) = countLowEvents(data)
        val highEvents = countHighEvents(data)

        val agressivenessScore = calculateAgressivenessScore(timeBelowTarget, lowEvents, veryLowEvents)

        val mealDetectionRate = calculateMealDetectionRate(data)
        val bolusDeliveryRate = calculateBolusDeliveryRate(data)
        val averageDetectedCarbs = calculateAverageDetectedCarbs(data)

        val readingsPerHour = if (hours > 0) data.size.toDouble() / hours else 0.0

        return GlucoseMetrics(
            period = "${hours}u",
            timeInRange = timeInRange,
            timeBelowRange = timeBelowRange,
            timeAboveRange = timeAboveRange,
            timeBelowTarget = timeBelowTarget,
            averageGlucose = averageGlucose,
            gmi = gmi,
            cv = cv,
            totalReadings = data.size,
            lowEvents = lowEvents,
            veryLowEvents = veryLowEvents,
            highEvents = highEvents,
            agressivenessScore = agressivenessScore,
            startDate = if (data.isNotEmpty()) data.first().timestamp else DateTime.now(),
            endDate = if (data.isNotEmpty()) data.last().timestamp else DateTime.now(),
            mealDetectionRate = mealDetectionRate,
            bolusDeliveryRate = bolusDeliveryRate,
            averageDetectedCarbs = averageDetectedCarbs,
            readingsPerHour = readingsPerHour
        )
    }


    // ★★★ CORRECTE TIJD-BASED METRICS BEREKENING ★★★
    private fun calculateTimeInRange(data: List<CSVReading>): Double {
        if (data.size < 2) return 0.0

        var totalTimeInRange = 0.0
        val sortedData = data.sortedBy { it.timestamp }

        for (i in 0 until sortedData.size - 1) {
            val current = sortedData[i]
            val next = sortedData[i + 1]

            // Bereken tijd tussen metingen in minuten
            val minutesBetween = Minutes.minutesBetween(current.timestamp, next.timestamp).minutes
            val hoursBetween = minutesBetween / 60.0

            // Bepaal of deze periode in range is (gebruik gemiddelde van beide metingen)
            val avgBG = (current.currentBG + next.currentBG) / 2.0

            if (avgBG in TARGET_LOW..TARGET_HIGH) {
                totalTimeInRange += hoursBetween
            }
        }

        val totalPeriodHours = calculateTotalPeriodHours(sortedData)
        return if (totalPeriodHours > 0) (totalTimeInRange / totalPeriodHours) * 100.0 else 0.0
    }

    private fun calculateTimeBelowRange(data: List<CSVReading>): Double {
        if (data.size < 2) return 0.0

        var totalTimeBelow = 0.0
        val sortedData = data.sortedBy { it.timestamp }

        for (i in 0 until sortedData.size - 1) {
            val current = sortedData[i]
            val next = sortedData[i + 1]

            val minutesBetween = Minutes.minutesBetween(current.timestamp, next.timestamp).minutes
            val hoursBetween = minutesBetween / 60.0
            val avgBG = (current.currentBG + next.currentBG) / 2.0

            if (avgBG < TARGET_LOW) {
                totalTimeBelow += hoursBetween
            }
        }

        val totalPeriodHours = calculateTotalPeriodHours(sortedData)
        return if (totalPeriodHours > 0) (totalTimeBelow / totalPeriodHours) * 100.0 else 0.0
    }

    private fun calculateTimeAboveRange(data: List<CSVReading>): Double {
        if (data.size < 2) return 0.0

        var totalTimeAbove = 0.0
        val sortedData = data.sortedBy { it.timestamp }

        for (i in 0 until sortedData.size - 1) {
            val current = sortedData[i]
            val next = sortedData[i + 1]

            val minutesBetween = Minutes.minutesBetween(current.timestamp, next.timestamp).minutes
            val hoursBetween = minutesBetween / 60.0
            val avgBG = (current.currentBG + next.currentBG) / 2.0

            if (avgBG > TARGET_HIGH) {
                totalTimeAbove += hoursBetween
            }
        }

        val totalPeriodHours = calculateTotalPeriodHours(sortedData)
        return if (totalPeriodHours > 0) (totalTimeAbove / totalPeriodHours) * 100.0 else 0.0
    }

    private fun calculateTimeBelowTarget(data: List<CSVReading>): Double {
        if (data.size < 2) return 0.0

        var totalTimeBelow = 0.0
        val sortedData = data.sortedBy { it.timestamp }

        for (i in 0 until sortedData.size - 1) {
            val current = sortedData[i]
            val next = sortedData[i + 1]

            val minutesBetween = Minutes.minutesBetween(current.timestamp, next.timestamp).minutes
            val hoursBetween = minutesBetween / 60.0
            val avgBG = (current.currentBG + next.currentBG) / 2.0

            if (avgBG < Target_Bg) {
                totalTimeBelow += hoursBetween
            }
        }

        val totalPeriodHours = calculateTotalPeriodHours(sortedData)
        return if (totalPeriodHours > 0) (totalTimeBelow / totalPeriodHours) * 100.0 else 0.0
    }

    // Helper functie om totale periode in uren te berekenen
    private fun calculateTotalPeriodHours(sortedData: List<CSVReading>): Double {
        if (sortedData.size < 2) return 0.0
        val startTime = sortedData.first().timestamp
        val endTime = sortedData.last().timestamp
        val totalMinutes = Minutes.minutesBetween(startTime, endTime).minutes
        return totalMinutes / 60.0
    }

    private fun calculateGMI(averageGlucose: Double): Double {
        if (averageGlucose <= 0.0) return 0.0
        return 3.31 + (0.02392 * averageGlucose * 18) / 1.0
    }

    private fun calculateCV(bgValues: List<Double>): Double {
        if (bgValues.size < 2) return 0.0
        val mean = bgValues.average()
        val variance = bgValues.map { (it - mean) * (it - mean) }.average()
        val stdDev = sqrt(variance)
        return (stdDev / mean) * 100.0
    }

    private fun countLowEvents(data: List<CSVReading>): Pair<Int, Int> {
        if (data.isEmpty()) return Pair(0, 0)

        var lowEvents = 0
        var veryLowEvents = 0
        var inLowEvent = false
        var inVeryLowEvent = false

        data.sortedBy { it.timestamp }.forEach { reading ->
            when {
                reading.currentBG < VERY_LOW_THRESHOLD && !inVeryLowEvent -> {
                    veryLowEvents++
                    inVeryLowEvent = true
                    inLowEvent = true
                }
                reading.currentBG < TARGET_LOW && !inLowEvent -> {
                    lowEvents++
                    inLowEvent = true
                }
                reading.currentBG >= TARGET_LOW -> {
                    inLowEvent = false
                    inVeryLowEvent = false
                }
            }
        }

        return Pair(lowEvents, veryLowEvents)
    }

    private fun countHighEvents(data: List<CSVReading>): Int {
        if (data.isEmpty()) return 0

        var highEvents = 0
        var inHighEvent = false

        data.sortedBy { it.timestamp }.forEach { reading ->
            when {
                reading.currentBG > VERY_HIGH_THRESHOLD && !inHighEvent -> {
                    highEvents++
                    inHighEvent = true
                }
                reading.currentBG <= TARGET_HIGH -> {
                    inHighEvent = false
                }
            }
        }

        return highEvents
    }

    private fun calculateAgressivenessScore(
        timeBelowTarget: Double,
        lowEvents: Int,
        veryLowEvents: Int
    ): Double {
        var score = 0.0
        score += min(timeBelowTarget / 10.0, 5.0)
        score += lowEvents * 0.5
        score += veryLowEvents * 2.0
        return min(score, 10.0)
    }

    private fun calculateMealDetectionRate(data: List<CSVReading>): Double {
        if (data.isEmpty()) return 0.0
        val mealDetectedCount = data.count { it.mealDetected }
        return (mealDetectedCount.toDouble() / data.size) * 100.0
    }

    private fun calculateBolusDeliveryRate(data: List<CSVReading>): Double {
        if (data.isEmpty()) return 0.0
        val bolusGivenCount = data.count { it.shouldDeliver && it.dose > 0.05 }
        val bolusAdviceCount = data.count { it.dose > 0.05 }

        return if (bolusAdviceCount > 0) {
            (bolusGivenCount.toDouble() / bolusAdviceCount) * 100.0
        } else {
            0.0
        }
    }

    private fun calculateAverageDetectedCarbs(data: List<CSVReading>): Double {
        if (data.isEmpty()) return 0.0
        val carbsReadings = data.map { it.detectedCarbs }.filter { it > 0 }
        return if (carbsReadings.isNotEmpty()) carbsReadings.average() else 0.0
    }

    private fun createEmptyMetrics(hours: Int): GlucoseMetrics {
        return GlucoseMetrics(
            period = "${hours}u",
            timeInRange = 0.0,
            timeBelowRange = 0.0,
            timeAboveRange = 0.0,
            timeBelowTarget = 0.0,
            averageGlucose = 0.0,
            gmi = 0.0,
            cv = 0.0,
            totalReadings = 0,
            lowEvents = 0,
            veryLowEvents = 0,
            highEvents = 0,
            agressivenessScore = 0.0,
            startDate = DateTime.now(),
            endDate = DateTime.now(),
            mealDetectionRate = 0.0,
            bolusDeliveryRate = 0.0,
            averageDetectedCarbs = 0.0,
            readingsPerHour = 0.0
        )
    }


    // ★★★ VERBETERDE MAALTIJD METRICS BEREKENING ★★★
    fun calculateMealPerformanceMetrics(hours: Int = 168): List<MealPerformanceMetrics> {
        // Probeer eerst de verbeterde methode
        val enhancedMeals = calculateEnhancedMealPerformanceMetrics(hours)
        if (enhancedMeals.isNotEmpty()) {
            cachedMealMetrics = enhancedMeals.toMutableList()
            return enhancedMeals
        }

        // Fallback naar oude methode
        return parseMealMetricsFromCSV(hours).also {
            cachedMealMetrics = it.toMutableList()
        }
    }

    private fun parseMealMetricsFromCSV(hours: Int): List<MealPerformanceMetrics> {
        val meals = mutableListOf<MealPerformanceMetrics>()
        val cutoffTime = DateTime.now().minusHours(hours)

        try {
            val csvData = loadCSVData(hours)
            if (csvData.isEmpty()) return emptyList()

            var currentMeal: MealPerformanceMetrics? = null
            var mealStartData: CSVReading? = null
            var mealPeakBG = 0.0
            var mealPeakTime: DateTime? = null
            var totalInsulin = 0.0
            var totalCarbs = 0.0
            var timeAbove10 = 0
            var phaseInsulin = mutableMapOf<String, Double>()
            var firstBolusTime: DateTime? = null
            var maxIOB = 0.0

            for (reading in csvData) {
                val isMealDetected = reading.mealDetected || reading.detectedCarbs > 10.0
                val hasBolus = reading.dose > 0.05

                // Update max IOB
                if (reading.currentIOB > maxIOB) maxIOB = reading.currentIOB

                // Start nieuwe maaltijd
                if (isMealDetected && currentMeal == null && reading.timestamp.isAfter(cutoffTime)) {
                    mealStartData = reading
                    mealPeakBG = reading.currentBG
                    mealPeakTime = reading.timestamp
                    totalInsulin = 0.0
                    totalCarbs = 0.0
                    timeAbove10 = 0
                    phaseInsulin.clear()
                    firstBolusTime = null
                    maxIOB = reading.currentIOB

                    currentMeal = MealPerformanceMetrics(
                        mealId = "meal_${reading.timestamp.millis}",
                        mealStartTime = reading.timestamp,
                        mealEndTime = reading.timestamp,
                        startBG = reading.currentBG,
                        peakBG = reading.currentBG,
                        endBG = reading.currentBG,
                        timeToPeak = 0,
                        totalCarbsDetected = 0.0,
                        totalInsulinDelivered = 0.0,
                        peakAboveTarget = max(0.0, reading.currentBG - Target_Bg),
                        timeAbove10 = 0,
                        postMealHypo = false,
                        timeInRangeDuringMeal = 0.0,
                        phaseInsulinBreakdown = emptyMap(),
                        firstBolusTime = null,
                        timeToFirstBolus = 0,
                        maxIOBDuringMeal = 0.0,
                        wasSuccessful = false,
                        mealType = determineMealType(reading.timestamp)
                    )
                }

                // Update huidige maaltijd
                currentMeal?.let { meal ->
                    // Update piek BG
                    if (reading.currentBG > mealPeakBG) {
                        mealPeakBG = reading.currentBG
                        mealPeakTime = reading.timestamp
                    }

                    // Tel tijd boven 10 mmol/L
                    if (reading.currentBG > 10.0) timeAbove10 += 5

                    // Tel insulin en carbs
                    if (hasBolus) {
                        totalInsulin += reading.dose
                        if (firstBolusTime == null) {
                            firstBolusTime = reading.timestamp
                        }
                    }
                    totalCarbs += reading.detectedCarbs

                    // Check voor maaltijd einde
                    val minutesSinceLastAction = Minutes.minutesBetween(
                        mealPeakTime ?: meal.mealStartTime, reading.timestamp
                    ).minutes

                    val shouldEndMeal = minutesSinceLastAction > MEAL_END_TIMEOUT &&
                        reading.currentBG < meal.startBG + 2.0

                    if (shouldEndMeal) {
                        // Sla maaltijd op
                        val finalMeal = meal.copy(
                            mealEndTime = reading.timestamp,
                            peakBG = mealPeakBG,
                            endBG = reading.currentBG,
                            timeToPeak = Minutes.minutesBetween(meal.mealStartTime, mealPeakTime!!).minutes,
                            totalCarbsDetected = totalCarbs,
                            totalInsulinDelivered = totalInsulin,
                            peakAboveTarget = max(0.0, mealPeakBG - Target_Bg),
                            timeAbove10 = timeAbove10,
                            postMealHypo = reading.currentBG < 4.0,
                            timeInRangeDuringMeal = calculateTIRDuringMeal(csvData, meal.mealStartTime, reading.timestamp),
                            phaseInsulinBreakdown = phaseInsulin.toMap(),
                            firstBolusTime = firstBolusTime,
                            timeToFirstBolus = firstBolusTime?.let {
                                Minutes.minutesBetween(meal.mealStartTime, it).minutes
                            } ?: 0,
                            maxIOBDuringMeal = maxIOB,
                            wasSuccessful = calculateMealSuccess(
                                startBG = meal.startBG,
                                peakBG = mealPeakBG,
                                endBG = reading.currentBG,
                                postMealHypo = reading.currentBG < 4.0
                            )
                        )

                        meals.add(finalMeal)
                        currentMeal = null
                    }
                }
            }

            // Sla eventuele lopende maaltijd op
            currentMeal?.let {
                val finalMeal = it.copy(
                    mealEndTime = DateTime.now(),
                    peakBG = mealPeakBG,
                    endBG = csvData.lastOrNull()?.currentBG ?: it.startBG,
                    timeToPeak = Minutes.minutesBetween(it.mealStartTime, mealPeakTime!!).minutes,
                    totalCarbsDetected = totalCarbs,
                    totalInsulinDelivered = totalInsulin,
                    peakAboveTarget = max(0.0, mealPeakBG - Target_Bg),
                    timeAbove10 = timeAbove10,
                    postMealHypo = (csvData.lastOrNull()?.currentBG ?: it.startBG) < 4.0,
                    timeInRangeDuringMeal = calculateTIRDuringMeal(csvData, it.mealStartTime, DateTime.now()),
                    phaseInsulinBreakdown = phaseInsulin.toMap(),
                    firstBolusTime = firstBolusTime,
                    timeToFirstBolus = firstBolusTime?.let { ft ->
                        Minutes.minutesBetween(it.mealStartTime, ft).minutes
                    } ?: 0,
                    maxIOBDuringMeal = maxIOB,
                    wasSuccessful = calculateMealSuccess(
                        startBG = it.startBG,
                        peakBG = mealPeakBG,
                        endBG = csvData.lastOrNull()?.currentBG ?: it.startBG,
                        postMealHypo = (csvData.lastOrNull()?.currentBG ?: it.startBG) < 4.0
                    )
                )
                meals.add(finalMeal)
            }

        } catch (e: Exception) {
            // Logging
        }

        return meals
    }

    private fun determineMealType(timestamp: DateTime): String {
        val hour = timestamp.hourOfDay
        return when (hour) {
            in 6..10 -> "ontbijt"
            in 11..14 -> "lunch"
            in 17..21 -> "dinner"
            else -> "snack"
        }
    }

    private fun calculateTIRDuringMeal(
        data: List<CSVReading>,
        start: DateTime,
        end: DateTime
    ): Double {
        val mealData = data.filter { it.timestamp in start..end }
        if (mealData.isEmpty()) return 0.0

        val inRange = mealData.count { it.currentBG in TARGET_LOW..TARGET_HIGH }
        return (inRange.toDouble() / mealData.size) * 100.0
    }

    private fun calculateMealSuccess(
        startBG: Double,
        peakBG: Double,
        endBG: Double,
        postMealHypo: Boolean
    ): Boolean {
        return !postMealHypo && peakBG <= 11.0 && endBG <= startBG + 3.0
    }



    fun evaluateParameterAdjustments(): List<ParameterAdjustmentResult> {
        val recentAdjustments = lastParameterAdjustments.filter {
            it.adjustmentTime.isAfter(DateTime.now().minusDays(14))
        }

        val results = mutableListOf<ParameterAdjustmentResult>()

        recentAdjustments.forEach { adjustment ->
            val effectiveness = calculateAdjustmentEffectiveness(adjustment)
            if (effectiveness != null) {
                val learnedAdjustment = adjustment.copy(
                    improvement = effectiveness,
                    learned = effectiveness > 10.0
                )
                results.add(learnedAdjustment)
            }
        }

        lastParameterAdjustments.removeAll { it.adjustmentTime.isAfter(DateTime.now().minusDays(14)) }
        lastParameterAdjustments.addAll(results)
        storeParameterAdjustments()

        return results
    }

    private fun calculateAdjustmentEffectiveness(adjustment: ParameterAdjustmentResult): Double? {
        val recentMeals = calculateMealPerformanceMetrics(7)
        if (recentMeals.isEmpty()) return null

        val successRate = recentMeals.count { it.wasSuccessful }.toDouble() / recentMeals.size * 100.0
        val avgPeak = recentMeals.map { it.peakBG }.average()
        val hypoRate = recentMeals.count { it.postMealHypo }.toDouble() / recentMeals.size * 100.0

        val peakScore = max(0.0, 11.0 - avgPeak) * 10
        val successScore = successRate
        val hypoScore = max(0.0, 100.0 - hypoRate * 10)

        return (peakScore * 0.4 + successScore * 0.4 + hypoScore * 0.2)
    }

    private fun storeParameterAdjustments() {
        try {
            val json = gson.toJson(lastParameterAdjustments)
            prefs.edit().putString("parameter_adjustments", json).apply()
        } catch (e: Exception) {
            // Logging
        }
    }


    private fun storeAdviceHistoryEntries(advice: List<ParameterAgressivenessAdvice>) {
        try {
            val history = loadAdviceHistoryEntries().toMutableList()

            val newEntry = AdviceHistoryEntry(
                timestamp = DateTime.now(),
                adviceList = advice,
                metricsSnapshot = calculateMetrics(24), // Houd metrics snapshot bij
                mealCount = calculateMealPerformanceMetrics(168).size
            )

            history.add(0, newEntry) // Nieuwe entries bovenaan

            // Beperk tot laatste 50 adviezen of 7 dagen
            val cutoffTime = DateTime.now().minusDays(7)
            val filteredHistory = history
                .filter { it.timestamp.isAfter(cutoffTime) }
                .take(50)

            val json = gson.toJson(filteredHistory)
            prefs.edit().putString("advice_history_entries", json).apply() // Andere preference key
        } catch (e: Exception) {
            // Logging
        }
    }

    private fun loadAdviceHistoryEntries(): List<AdviceHistoryEntry> {
        return try {
            val json = prefs.getString("advice_history_entries", null)
            if (json != null) {
                val type = object : com.google.gson.reflect.TypeToken<List<AdviceHistoryEntry>>() {}.type
                gson.fromJson<List<AdviceHistoryEntry>>(json, type) ?: emptyList()
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    // Public functie om adviesgeschiedenis op te halen
    fun getAdviceHistoryEntries(days: Int = 5): List<AdviceHistoryEntry> {
        val cutoffTime = DateTime.now().minusDays(days)
        return loadAdviceHistoryEntries().filter { it.timestamp.isAfter(cutoffTime) }
    }

    // Functie om specifiek advies te vinden voor een parameter
    fun getParameterAdviceHistory(parameterName: String, days: Int = 7): List<ParameterAgressivenessAdvice> {
        return getAdviceHistoryEntries(days).flatMap { entry ->
            entry.adviceList.filter { it.parameterName == parameterName }
        }
    }


    private fun loadAdviceHistory(): Map<String, ParameterAdviceHistory> {
        return try {
            val json = prefs.getString("parameter_advice_history", null)
            if (json != null) {
                val type = object : com.google.gson.reflect.TypeToken<Map<String, ParameterAdviceHistory>>() {}.type
                gson.fromJson<Map<String, ParameterAdviceHistory>>(json, type) ?: emptyMap()
            } else {
                emptyMap()
            }
        } catch (e: Exception) {
            emptyMap()
        }
    }

    private fun saveAdviceHistory(history: Map<String, ParameterAdviceHistory>) {
        try {
            val json = gson.toJson(history)
            prefs.edit().putString("parameter_advice_history", json).apply()
        } catch (e: Exception) {
            // Logging
        }
    }

    private fun updateAdviceHistory(newAdvice: ParameterAgressivenessAdvice) {
        val history = loadAdviceHistory().toMutableMap()
        val parameterHistory = history.getOrPut(newAdvice.parameterName) {
            ParameterAdviceHistory(parameterName = newAdvice.parameterName)
        }

        val historicalAdvice = HistoricalAdvice(
            timestamp = DateTime.now(),
            recommendedValue = newAdvice.recommendedValue,
            changeDirection = newAdvice.changeDirection,
            confidence = newAdvice.confidence,
            reason = newAdvice.reason
        )

        parameterHistory.adviceHistory.add(historicalAdvice)

        // Beperk geschiedenis tot laatste 10 adviezen
        if (parameterHistory.adviceHistory.size > 10) {
            parameterHistory.adviceHistory.removeAt(0)
        }

        saveAdviceHistory(history)
    }

    private fun loadParameterAdjustments(): List<ParameterAdjustmentResult> {
        return try {
            val json = prefs.getString("parameter_adjustments", null)
            if (json != null) {
                val type = object : com.google.gson.reflect.TypeToken<List<ParameterAdjustmentResult>>() {}.type
                gson.fromJson<List<ParameterAdjustmentResult>>(json, type) ?: emptyList()
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    // ★★★ PARAMETER ADVIES GESCHIEDENIS MANAGEMENT ★★★
    fun saveMealParameterAdvice(mealId: String, advice: List<ParameterAgressivenessAdvice>) {
        val mealAdvice = MealParameterAdvice(
            mealId = mealId,
            timestamp = DateTime.now(),
            parameterAdvice = advice
        )
        mealParameterAdviceHistory.add(mealAdvice)

        // Beperk tot laatste 10 maaltijden
        if (mealParameterAdviceHistory.size > 10) {
            mealParameterAdviceHistory.removeAt(0)
        }

        storeMealParameterAdviceHistory()
    }

    private fun storeMealParameterAdviceHistory() {
        try {
            val json = gson.toJson(mealParameterAdviceHistory)
            prefs.edit().putString("meal_parameter_advice_history", json).apply()
        } catch (e: Exception) {
            // Logging
        }
    }

    private fun loadMealParameterAdviceHistory() {
        try {
            val json = prefs.getString("meal_parameter_advice_history", null)
            if (json != null) {
                val type = object : com.google.gson.reflect.TypeToken<List<MealParameterAdvice>>() {}.type
                mealParameterAdviceHistory = gson.fromJson<List<MealParameterAdvice>>(json, type)?.toMutableList() ?: mutableListOf()
            }
        } catch (e: Exception) {
            mealParameterAdviceHistory = mutableListOf()
        }
    }

    // ★★★ GEWOGEN GEMIDDELDE BEREKENING ★★★
    // ★★★ UPDATE: Gebruik bestaande getCurrentParameterValue ★★★
    fun getParameterAdviceSummary(parameters: FCLParameters): List<ParameterAdviceSummary> {
        val essentialParameters = listOf(
            "bolus_perc_early", "bolus_perc_mid", "bolus_perc_late",
            "bolus_perc_day", "bolus_perc_night", "meal_detection_sensitivity",
            "phase_early_rise_slope", "phase_mid_rise_slope", "phase_late_rise_slope",
            "carb_percentage", "peak_damping_percentage", "hypo_risk_percentage", "IOB_corr_perc"
        )

        return essentialParameters.map { paramName ->
            calculateParameterSummary(parameters, paramName)
        }
    }

    private fun calculateParameterSummary(parameters: FCLParameters, parameterName: String): ParameterAdviceSummary {
        val currentValue = getCurrentParameterValue(parameters, parameterName)
        val recentAdvice = getRecentParameterAdvice(parameterName, 10)
        val lastAdvice = recentAdvice.maxByOrNull { it.timestamp }

        // Gebruik echte metrics voor betrouwbaarheidsberekening
        val metrics24h = calculateMetrics(24)
        val confidence = calculateParameterConfidence(parameterName, metrics24h, recentAdvice)

        return ParameterAdviceSummary(
            parameterName = parameterName,
            currentValue = currentValue,
            lastAdvice = lastAdvice,
            weightedAverage = calculateWeightedAverage(recentAdvice),
            confidence = confidence,
            trend = determineTrend(recentAdvice),
            manuallyAdjusted = isParameterManuallyAdjusted(parameterName)
        )
    }

    private fun calculateParameterConfidence(
        parameterName: String,
        metrics: GlucoseMetrics,
        recentAdvice: List<ParameterAgressivenessAdvice>
    ): Double {
        if (recentAdvice.isEmpty()) return 0.3

        // Baseer confidence op data kwaliteit en consistentie van adviezen
        val dataQuality = min(metrics.readingsPerHour / 12.0, 1.0)
        val adviceConsistency = calculateAdviceConfidence(recentAdvice)

        return (dataQuality * 0.6 + adviceConsistency * 0.4).coerceIn(0.1, 0.9)
    }

    private fun isParameterManuallyAdjusted(parameterName: String): Boolean {
        val lastAdjustment = parameterAdjustmentTimestamps[parameterName]
        return lastAdjustment != null && lastAdjustment.isAfter(DateTime.now().minusDays(7))
    }



    private fun getRecentParameterAdvice(parameterName: String, maxEntries: Int): List<ParameterAgressivenessAdvice> {
        // Gebruik echte adviezen in plaats van demo adviezen
        val currentAdvice = getCurrentAdvice()
        return currentAdvice
            .filter { it.parameterName == parameterName }
            .take(maxEntries)
    }

    // ★★★ VERBETERDE ADVIES GENERATIE OP BASIS VAN ECHTE METRICS ★★★
    // ★★★ VERBETERDE ADVIES GENERATIE MET FCLPARAMETERS GRENZEN ★★★
    private fun generateRealAdvice(
        parameters: FCLParameters,
        metrics: GlucoseMetrics,
        mealMetrics: List<MealPerformanceMetrics>
    ): List<ParameterAgressivenessAdvice> {
        val adviceList = mutableListOf<ParameterAgressivenessAdvice>()

        // 1. Algemene glucose controle analyse
        adviceList.addAll(analyzeGlucoseControl(parameters, metrics))

        // 2. Maaltijd-specifieke analyse
        adviceList.addAll(analyzeMealPerformance(parameters, mealMetrics))

        // 3. Veiligheidsanalyse
        adviceList.addAll(analyzeSafetyParameters(parameters, metrics, mealMetrics))

        return adviceList
            .take(5)
            .sortedByDescending { it.confidence }
            .map { ensureWithinBounds(it, parameters) } // ★★★ NIEUW: Controleer grenzen
    }

    // ★★★ GRENS CONTROLE FUNCTIE ★★★
    private fun ensureWithinBounds(
        advice: ParameterAgressivenessAdvice,
        parameters: FCLParameters
    ): ParameterAgressivenessAdvice {
        val definition = getParameterDefinition(parameters, advice.parameterName)
        if (definition == null) {
            return advice.copy(
                confidence = advice.confidence * 0.7, // Lager vertrouwen bij ontbrekende definitie
                reason = advice.reason + " (geen grenzen gedefinieerd)"
            )
        }

        val boundedValue = advice.recommendedValue.coerceIn(definition.minValue, definition.maxValue)

        return if (boundedValue != advice.recommendedValue) {
            advice.copy(
                recommendedValue = boundedValue,
                reason = advice.reason + " (aangepast naar ${if (boundedValue > advice.recommendedValue) "minimum" else "maximum"} grens)",
                confidence = advice.confidence * 0.9 // Iets lager vertrouwen bij grenscorrectie
            )
        } else {
            advice
        }
    }

    private fun analyzeGlucoseControl(
        parameters: FCLParameters,
        metrics: GlucoseMetrics
    ): List<ParameterAgressivenessAdvice> {
        val advice = mutableListOf<ParameterAgressivenessAdvice>()

        // Analyse op basis van Time in Range
        if (metrics.timeAboveRange > 25.0) {
            advice.add(createHighGlucoseAdvice(parameters, metrics))
        }

        if (metrics.timeBelowRange > 5.0) {
            advice.add(createLowGlucoseAdvice(parameters, metrics))
        }

        // Analyse op basis van gemiddelde glucose
        if (metrics.averageGlucose > 8.5) {
            advice.add(createAverageGlucoseAdvice(parameters, metrics))
        }

        return advice
    }

    private fun analyzeMealPerformance(
        parameters: FCLParameters,
        recentMeals: List<MealPerformanceMetrics>
    ): List<ParameterAgressivenessAdvice> {
        if (recentMeals.isEmpty()) return emptyList()

        val advice = mutableListOf<ParameterAgressivenessAdvice>()
        val successRate = calculateSuccessRate(recentMeals)

        // Analyseer pieken (>11.0 mmol/L)
        val highPeaks = recentMeals.count { it.peakBG > 11.0 }
        val highPeakPercentage = (highPeaks.toDouble() / recentMeals.size) * 100

        if (highPeakPercentage > 30.0) {
            advice.add(createHighPeakAdvice(parameters, highPeakPercentage, successRate))
        }

        // Analyseer post-maaltijd hypo's
        val postMealHypos = recentMeals.count { it.postMealHypo }
        val hypoPercentage = (postMealHypos.toDouble() / recentMeals.size) * 100

        if (hypoPercentage > 10.0) {
            advice.add(createPostMealHypoAdvice(parameters, hypoPercentage, successRate))
        }

        return advice
    }

    /*   private fun analyzeMealPerformance(
        parameters: FCLParameters,
        mealMetrics: List<MealPerformanceMetrics>
    ): List<ParameterAgressivenessAdvice> {
        val advice = mutableListOf<ParameterAgressivenessAdvice>()

        advice.addAll(analyzeMealTiming(parameters, mealMetrics))
        advice.addAll(analyzePeakControl(parameters, mealMetrics))
        advice.addAll(analyzeHypoSafety(parameters, mealMetrics))
        advice.addAll(analyzeSuccessRate(parameters, mealMetrics))

        return advice
    }   */

    private fun analyzeSafetyParameters(
        parameters: FCLParameters,
        metrics: GlucoseMetrics,
        recentMeals: List<MealPerformanceMetrics>
    ): List<ParameterAgressivenessAdvice> {
        val advice = mutableListOf<ParameterAgressivenessAdvice>()

        // Analyseer hypo risico op basis van time below range
        if (metrics.timeBelowRange > 8.0 || metrics.lowEvents > 3) {
            advice.add(createHypoRiskAdvice(parameters, metrics))
        }

        // Analyseer variabiliteit (CV)
        if (metrics.cv > 36.0) {
            advice.add(createVariabilityAdvice(parameters, metrics))
        }

        // Analyseer snelle dalingen in maaltijden
        val rapidDeclines = recentMeals.count { it.rapidDeclineDetected }
        if (rapidDeclines > recentMeals.size * 0.2 && recentMeals.isNotEmpty()) {
            advice.add(createRapidDeclineAdvice(parameters, rapidDeclines, recentMeals.size))
        }

        return advice
    }

    // ★★★ NIEUWE VEILIGHEIDSADVIES FUNCTIES ★★★
    private fun createVariabilityAdvice(
        parameters: FCLParameters,
        metrics: GlucoseMetrics
    ): ParameterAgressivenessAdvice {
        val parameterName = "peak_damping_percentage"
        val currentValue = getCurrentParameterValue(parameters, parameterName)

        val recommendedValue = calculateOptimalIncreaseWithParams(currentValue, parameters, parameterName)

        return ParameterAgressivenessAdvice(
            parameterName = parameterName,
            currentValue = currentValue,
            recommendedValue = recommendedValue,
            reason = "Hoge glucose variabiliteit (CV: ${metrics.cv.toInt()}%) - verhoog piek demping voor stabiliteit",
            confidence = calculateConfidence(metrics.cv, 36.0, 50.0),
            expectedImprovement = "Verwacht ${(metrics.cv * 0.2).toInt()}% lagere variabiliteit",
            changeDirection = "INCREASE"
        )
    }

    private fun createRapidDeclineAdvice(
        parameters: FCLParameters,
        rapidDeclines: Int,
        totalMeals: Int
    ): ParameterAgressivenessAdvice {
        val parameterName = "peak_damping_percentage"
        val currentValue = getCurrentParameterValue(parameters, parameterName)

        val recommendedValue = calculateOptimalIncreaseWithParams(currentValue, parameters, parameterName)
        val rapidDeclinePercentage = (rapidDeclines.toDouble() / totalMeals) * 100

        return ParameterAgressivenessAdvice(
            parameterName = parameterName,
            currentValue = currentValue,
            recommendedValue = recommendedValue,
            reason = "Snelle dalingen in ${rapidDeclinePercentage.toInt()}% van maaltijden - verhoog piek demping",
            confidence = calculateConfidence(rapidDeclinePercentage, 20.0, 50.0),
            expectedImprovement = "Verwacht ${(rapidDeclinePercentage * 0.4).toInt()}% minder snelle dalingen",
            changeDirection = "INCREASE"
        )
    }

    // ★★★ SPECIFIEKE ADVIES CREATIE MET FCLPARAMETERS GRENZEN ★★★
    private fun createHighGlucoseAdvice(
        parameters: FCLParameters,
        metrics: GlucoseMetrics
    ): ParameterAgressivenessAdvice {
        val parameterName = "bolus_perc_day"
        val currentValue = getCurrentParameterValue(parameters, parameterName)

        // ★★★ GEBRUIK DE NIEUWE FUNCTIE MET PARAMETERS ★★★
        val recommendedValue = calculateOptimalIncreaseWithParams(currentValue, parameters, parameterName)

        return ParameterAgressivenessAdvice(
            parameterName = parameterName,
            currentValue = currentValue,
            recommendedValue = recommendedValue,
            reason = "Te veel tijd boven range (${metrics.timeAboveRange.toInt()}%) - verhoog dag agressiviteit",
            confidence = calculateConfidence(metrics.timeAboveRange, 25.0, 50.0),
            expectedImprovement = "Verwacht ${(metrics.timeAboveRange * 0.3).toInt()}% minder tijd boven range",
            changeDirection = "INCREASE"
        )
    }

    private fun createLowGlucoseAdvice(
        parameters: FCLParameters,
        metrics: GlucoseMetrics
    ): ParameterAgressivenessAdvice {
        // ★★★ CORRECT: bij hypo's willen we MEER bescherming, dus VERLAG hypo_risk_percentage ★★★
        val parameterName = "hypo_risk_percentage"
        val currentValue = getCurrentParameterValue(parameters, parameterName)

        // ★★★ CORRECT: DECREASE voor meer bescherming ★★★
        val recommendedValue = calculateOptimalDecreaseWithParams(currentValue, parameters, parameterName)

        return ParameterAgressivenessAdvice(
            parameterName = parameterName,
            currentValue = currentValue,
            recommendedValue = recommendedValue,
            reason = "Te veel tijd onder range (${metrics.timeBelowRange.toInt()}%) - verhoog hypo bescherming door lagere correctiebolus bij risico",
            confidence = calculateConfidence(metrics.timeBelowRange, 5.0, 15.0),
            expectedImprovement = "Verwacht ${(metrics.timeBelowRange * 0.3).toInt()}% minder tijd onder range door lagere correctiebolus bij hypo risico",
            changeDirection = "DECREASE" // ★★★ CORRECT: DECREASE voor meer bescherming ★★★
        )
    }

    private fun createHighPeakAdvice(
        parameters: FCLParameters,
        highPeakPercentage: Double,
        successRate: Double
    ): ParameterAgressivenessAdvice {
        val parameterName = "bolus_perc_early"
        val currentValue = getCurrentParameterValue(parameters, parameterName)

        // ★★★ GEBRUIK DE NIEUWE FUNCTIE MET PARAMETERS ★★★
        val recommendedValue = calculateOptimalIncreaseWithParams(currentValue, parameters, parameterName)

        return ParameterAgressivenessAdvice(
            parameterName = parameterName,
            currentValue = currentValue,
            recommendedValue = recommendedValue,
            reason = "Hoge pieken in ${highPeakPercentage.toInt()}% van maaltijden - verhoog vroege fase bolus",
            confidence = calculateConfidence(highPeakPercentage, 30.0, 60.0),
            expectedImprovement = "Verwacht ${(highPeakPercentage * 0.35).toInt()}% minder hoge pieken",
            changeDirection = "INCREASE"
        )
    }


    private fun createPostMealHypoAdvice(
        parameters: FCLParameters,
        hypoPercentage: Double,
        successRate: Double
    ): ParameterAgressivenessAdvice {
        val parameterName = "hypo_risk_percentage"
        val currentValue = getCurrentParameterValue(parameters, parameterName)

        // ★★★ CORRECT: DECREASE voor meer bescherming ★★★
        val recommendedValue = calculateOptimalDecreaseWithParams(currentValue, parameters, parameterName)

        return ParameterAgressivenessAdvice(
            parameterName = parameterName,
            currentValue = currentValue,
            recommendedValue = recommendedValue,
            reason = "Post-maaltijd hypo's in ${hypoPercentage.toInt()}% van maaltijden - verhoog hypo bescherming",
            confidence = calculateConfidence(hypoPercentage, 10.0, 30.0),
            expectedImprovement = "Verwacht ${(hypoPercentage * 0.5).toInt()}% minder post-maaltijd hypo's door lagere correctiebolus bij risico",
            changeDirection = "DECREASE" // ★★★ CORRECT: DECREASE voor meer bescherming ★★★
        )
    }

    // ★★★ HELPER FUNCTIES VOOR ADVIES GENERATIE ★★★
    private fun calculateSuccessRate(mealMetrics: List<MealPerformanceMetrics>): Double {
        if (mealMetrics.isEmpty()) return 0.0
        return mealMetrics.count { it.wasSuccessful }.toDouble() / mealMetrics.size * 100.0
    }

 /*   // ★★★ HELPER FUNCTIES ★★★
    private fun calculateSuccessRate(mealMetrics: List<MealPerformanceMetrics>): Double {
        if (mealMetrics.isEmpty()) return 0.0
        return mealMetrics.count { it.wasSuccessful }.toDouble() / mealMetrics.size
    }  */

    private fun calculateConfidence(currentValue: Double, threshold: Double, maxValue: Double): Double {
        val normalized = (currentValue - threshold) / (maxValue - threshold)
        return normalized.coerceIn(0.3, 0.9)
    }

    private fun createHypoRiskAdvice(
        parameters: FCLParameters,
        metrics: GlucoseMetrics
    ): ParameterAgressivenessAdvice {
        val parameterName = "hypo_risk_percentage"
        val currentValue = getCurrentParameterValue(parameters, parameterName)

        // ★★★ CORRECT: DECREASE voor meer bescherming ★★★
        val recommendedValue = calculateOptimalDecreaseWithParams(currentValue, parameters, parameterName)

        return ParameterAgressivenessAdvice(
            parameterName = parameterName,
            currentValue = currentValue,
            recommendedValue = recommendedValue,
            reason = "Verhoogd hypo risico (${metrics.timeBelowRange.toInt()}% onder range, ${metrics.lowEvents} lage events) - verhoog hypo bescherming",
            confidence = calculateConfidence(metrics.timeBelowRange, 8.0, 20.0),
            expectedImprovement = "Verwacht ${(metrics.timeBelowRange * 0.4).toInt()}% minder tijd onder range door lagere correctiebolus bij risico",
            changeDirection = "DECREASE" // ★★★ CORRECT: DECREASE voor meer bescherming ★★★
        )
    }

    private fun createAverageGlucoseAdvice(
        parameters: FCLParameters,
        metrics: GlucoseMetrics
    ): ParameterAgressivenessAdvice {
        val parameterName = "bolus_perc_day"
        val currentValue = getCurrentParameterValue(parameters, parameterName)

        // ★★★ GEBRUIK DE NIEUWE FUNCTIE MET PARAMETERS ★★★
        val recommendedValue = calculateOptimalIncreaseWithParams(currentValue, parameters, parameterName)

        return ParameterAgressivenessAdvice(
            parameterName = parameterName,
            currentValue = currentValue,
            recommendedValue = recommendedValue,
            reason = "Gemiddelde glucose te hoog (${round(metrics.averageGlucose, 1)} mmol/L) - verhoog algemene agressiviteit",
            confidence = calculateConfidence(metrics.averageGlucose, 8.5, 12.0),
            expectedImprovement = "Verwacht daling gemiddelde glucose met ${round(metrics.averageGlucose - 7.5, 1)} mmol/L",
            changeDirection = "INCREASE"
        )
    }


    private fun calculateWeightedAverage(adviceList: List<ParameterAgressivenessAdvice>): Double {
        if (adviceList.isEmpty()) return 0.0

        var totalWeight = 0.0
        var weightedSum = 0.0

        adviceList.sortedBy { it.timestamp }.forEachIndexed { index, advice ->
            val weight = Math.exp(index * 0.2) // Exponentiële weging: recenter = zwaarder
            weightedSum += advice.recommendedValue * weight
            totalWeight += weight
        }

        return if (totalWeight > 0) weightedSum / totalWeight else 0.0
    }

    private fun calculateAdviceConfidence(adviceList: List<ParameterAgressivenessAdvice>): Double {
        if (adviceList.size < 2) return 0.3

        val values = adviceList.map { it.recommendedValue }
        val mean = values.average()
        val variance = values.map { (it - mean) * (it - mean) }.average()
        val stdDev = Math.sqrt(variance)

        // Hoger vertrouwen bij lage variantie
        val consistency = 1.0 - (stdDev / mean).coerceIn(0.0, 1.0)

        // Neem ook gemiddeld vertrouwen van individuele adviezen mee
        val avgConfidence = adviceList.map { it.confidence }.average()

        return (consistency * 0.6 + avgConfidence * 0.4).coerceIn(0.0, 1.0)
    }

    private fun determineTrend(adviceList: List<ParameterAgressivenessAdvice>): String {
        if (adviceList.size < 3) return "STABLE"

        val sortedAdvice = adviceList.sortedBy { it.timestamp }
        val recentValues = sortedAdvice.takeLast(3).map { it.recommendedValue }
        val olderValues = sortedAdvice.take(3).map { it.recommendedValue }

        val recentAvg = recentValues.average()
        val olderAvg = olderValues.average()

        return when {
            recentAvg > olderAvg * 1.05 -> "INCREASING"
            recentAvg < olderAvg * 0.95 -> "DECREASING"
            else -> "STABLE"
        }
    }

    // ★★★ AUTOMATISCHE UPDATE CONFIGURATIE MANAGEMENT ★★★
    fun getAutoUpdateConfig(): AutoUpdateConfig {
        return autoUpdateConfig
    }

    fun updateAutoUpdateConfig(
        minConfidence: Double? = null,
        minMeals: Int? = null,
        maxChangePercent: Double? = null,
        enabled: Boolean? = null
    ) {
        autoUpdateConfig = autoUpdateConfig.copy(
            minConfidence = minConfidence ?: autoUpdateConfig.minConfidence,
            minMeals = minMeals ?: autoUpdateConfig.minMeals,
            maxChangePercent = maxChangePercent ?: autoUpdateConfig.maxChangePercent,
            enabled = enabled ?: autoUpdateConfig.enabled,
            lastEvaluation = DateTime.now()
        )
        storeAutoUpdateConfig()
    }

    private fun storeAutoUpdateConfig() {
        try {
            val json = gson.toJson(autoUpdateConfig)
            prefs.edit().putString("auto_update_config", json).apply()
        } catch (e: Exception) {
            // Logging
        }
    }

    private fun loadAutoUpdateConfig() {
        try {
            val json = prefs.getString("auto_update_config", null)
            if (json != null) {
                autoUpdateConfig = gson.fromJson(json, AutoUpdateConfig::class.java)
            }
        } catch (e: Exception) {
            autoUpdateConfig = AutoUpdateConfig() // Gebruik default config
        }
    }

    // ★★★ UPDATE TRACKPARAMETERADJUSTMENT ★★★
    fun trackParameterAdjustment(parameterName: String, oldValue: Double, newValue: Double) {
        parameterAdjustmentTimestamps[parameterName] = DateTime.now()

        // Bestaande implementatie behouden
        val adjustment = ParameterAdjustmentResult(
            parameterName = parameterName,
            oldValue = oldValue,
            newValue = newValue,
            adjustmentTime = DateTime.now(),
            mealMetricsBefore = null,
            mealMetricsAfter = null,
            improvement = 0.0
        )

        lastParameterAdjustments.add(adjustment)
        storeParameterAdjustments()

        // Sla ook de aanpassingstijd op
        storeParameterAdjustmentTimestamps()
    }

    private fun storeParameterAdjustmentTimestamps() {
        try {
            val timestampsMap = parameterAdjustmentTimestamps.mapValues { it.value.millis }
            val json = gson.toJson(timestampsMap)
            prefs.edit().putString("parameter_adjustment_timestamps", json).apply()
        } catch (e: Exception) {
            // Logging
        }
    }

    private fun loadParameterAdjustmentTimestamps() {
        try {
            val json = prefs.getString("parameter_adjustment_timestamps", null)
            if (json != null) {
                val type = object : com.google.gson.reflect.TypeToken<Map<String, Long>>() {}.type
                val timestampsMap = gson.fromJson<Map<String, Long>>(json, type) ?: emptyMap()
                parameterAdjustmentTimestamps = timestampsMap.mapValues { DateTime(it.value) }.toMutableMap()
            }
        } catch (e: Exception) {
            parameterAdjustmentTimestamps = mutableMapOf()
        }
    }



    private fun round(value: Double, digits: Int): Double {
        val scale = Math.pow(10.0, digits.toDouble())
        return Math.round(value * scale) / scale
    }

    fun getParameterLearningStatus(): String {
        val adjustments = loadParameterAdjustments()
        if (adjustments.isEmpty()) return "Geen parameter aanpassingen getracked"

        val successfulAdjustments = adjustments.count { it.improvement > 0 }
        val successRate = (successfulAdjustments.toDouble() / adjustments.size) * 100

        return """
        Parameter Learning Status:
        • Totaal aanpassingen: ${adjustments.size}
        • Succesrate: ${successRate.toInt()}%
        • Laatste aanpassing: ${adjustments.last().parameterName}
        • Verbetering: ${adjustments.last().improvement.toInt()}%
    """.trimIndent()
    }

    // ★★★ VERTROUWENS- EN VEILIGHEIDSFUNCTIES ★★★
    private fun shouldApplyAutomaticAdjustment(summary: ParameterAdviceSummary): Boolean {
        if (!autoUpdateConfig.enabled) return false

        return summary.confidence >= autoUpdateConfig.minConfidence &&
            !summary.manuallyAdjusted &&
            abs(summary.weightedAverage - summary.currentValue) > calculateMinimumChangeThreshold(summary.parameterName) &&
            hasSufficientDataForParameter(summary.parameterName) &&
            isChangeSafe(summary)
    }

    private fun calculateMinimumChangeThreshold(parameterName: String): Double {
        return when (parameterName) {
            "bolus_perc_early", "bolus_perc_mid", "bolus_perc_late",
            "bolus_perc_day", "bolus_perc_night" -> 2.0 // 2% minimale verandering
            "meal_detection_sensitivity" -> 0.02
            "phase_early_rise_slope", "phase_mid_rise_slope", "phase_late_rise_slope" -> 0.05
            "carb_percentage", "peak_damping_percentage", "hypo_risk_percentage", "IOB_corr_perc" -> 1.0
            else -> 0.5
        }
    }

    private fun getMaxChangePercentage(parameterName: String): Double {
        return when (parameterName) {
            "bolus_perc_early", "bolus_perc_mid", "bolus_perc_late" -> autoUpdateConfig.maxChangePercent
            "bolus_perc_day", "bolus_perc_night" -> autoUpdateConfig.maxChangePercent * 0.8
            "meal_detection_sensitivity" -> autoUpdateConfig.maxChangePercent * 1.5
            else -> autoUpdateConfig.maxChangePercent
        }
    }

    private fun hasSufficientDataForParameter(parameterName: String): Boolean {
        val recentAdvice = getRecentParameterAdvice(parameterName, autoUpdateConfig.minMeals)
        return recentAdvice.size >= (autoUpdateConfig.minMeals / 2) // Minimaal 50% van vereiste maaltijden
    }

    private fun isChangeSafe(summary: ParameterAdviceSummary): Boolean {
        // Voeg hier extra veiligheidschecks toe
        val proposedChange = summary.weightedAverage - summary.currentValue
        val changePercentage = abs(proposedChange) / summary.currentValue

        return changePercentage <= getMaxChangePercentage(summary.parameterName)
    }

    // ★★★ AUTOMATISCHE PARAMETER UPDATES ★★★
    fun evaluateAutomaticParameterAdjustments(parameters: FCLParameters): List<ParameterAdjustmentResult> {
        if (!autoUpdateConfig.enabled) return emptyList()

        val adjustments = mutableListOf<ParameterAdjustmentResult>()
        val summaries = getParameterAdviceSummary(parameters)

        summaries.forEach { summary ->
            if (shouldApplyAutomaticAdjustment(summary)) {
                val adjustment = createAutomaticAdjustment(summary)
                adjustments.add(adjustment)
            }
        }

        if (adjustments.isNotEmpty()) {
            automaticAdjustmentsHistory.addAll(adjustments)
            storeAutomaticAdjustmentsHistory()
        }

        autoUpdateConfig = autoUpdateConfig.copy(lastEvaluation = DateTime.now())
        storeAutoUpdateConfig()

        return adjustments
    }

    private fun createAutomaticAdjustment(summary: ParameterAdviceSummary): ParameterAdjustmentResult {
        val oldValue = summary.currentValue
        val newValue = calculateSafeAdjustedValue(summary)

        // Pas parameter aan in preferences
   //     applyParameterAdjustment(summary.parameterName, newValue)

        // Track de automatische aanpassing
        trackParameterAdjustment(summary.parameterName, oldValue, newValue)

        return ParameterAdjustmentResult(
            parameterName = summary.parameterName,
            oldValue = oldValue,
            newValue = newValue,
            adjustmentTime = DateTime.now(),
            mealMetricsBefore = null,
            mealMetricsAfter = null,
            improvement = summary.confidence * 100,
            learned = true,
            isAutomatic = true,
            confidence = summary.confidence,
            reason = "Automatische aanpassing gebaseerd op ${getRecentParameterAdvice(summary.parameterName, 10).size} maaltijden met ${(summary.confidence * 100).toInt()}% vertrouwen"
        )
    }

    private fun calculateSafeAdjustedValue(summary: ParameterAdviceSummary): Double {
        val targetValue = summary.weightedAverage
        val currentValue = summary.currentValue
        val maxChangePercent = getMaxChangePercentage(summary.parameterName)

        // Beperk de maximale verandering per aanpassing
        val maxChange = currentValue * maxChangePercent
        val proposedChange = targetValue - currentValue

        val safeChange = when {
            abs(proposedChange) > maxChange -> if (proposedChange > 0) maxChange else -maxChange
            else -> proposedChange
        }

        return (currentValue + safeChange).let { adjustedValue ->
            // Zorg ervoor dat de waarde binnen redelijke grenzen blijft
            when (summary.parameterName) {
                "bolus_perc_early", "bolus_perc_mid", "bolus_perc_late",
                "bolus_perc_day", "bolus_perc_night" -> adjustedValue.coerceIn(10.0, 200.0)
                "meal_detection_sensitivity" -> adjustedValue.coerceIn(0.1, 0.5)
                "phase_early_rise_slope", "phase_mid_rise_slope", "phase_late_rise_slope" -> adjustedValue.coerceIn(0.1, 3.0)
                "carb_percentage" -> adjustedValue.coerceIn(10.0, 200.0)
                "peak_damping_percentage", "hypo_risk_percentage", "IOB_corr_perc" -> adjustedValue.coerceIn(10.0, 100.0)
                else -> adjustedValue
            }
        }
    }



    // ★★★ AUTOMATISCHE AANPASSINGEN GESCHIEDENIS ★★★
    private fun storeAutomaticAdjustmentsHistory() {
        try {
            val json = gson.toJson(automaticAdjustmentsHistory)
            prefs.edit().putString("automatic_adjustments_history", json).apply()
        } catch (e: Exception) {
            // Logging
        }
    }

    private fun loadAutomaticAdjustmentsHistory() {
        try {
            val json = prefs.getString("automatic_adjustments_history", null)
            if (json != null) {
                val type = object : com.google.gson.reflect.TypeToken<List<ParameterAdjustmentResult>>() {}.type
                automaticAdjustmentsHistory = gson.fromJson<List<ParameterAdjustmentResult>>(json, type)?.toMutableList() ?: mutableListOf()
            }
        } catch (e: Exception) {
            automaticAdjustmentsHistory = mutableListOf()
        }
    }




    fun getAutomaticAdjustmentsHistory(days: Int = 30): List<ParameterAdjustmentResult> {
        val cutoff = DateTime.now().minusDays(days)
        return automaticAdjustmentsHistory.filter { it.adjustmentTime.isAfter(cutoff) }
    }
}