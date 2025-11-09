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

    data class ParameterAdviceSummary(
        val parameterName: String,
        val currentValue: Double,
        val lastAdvice: ParameterAgressivenessAdvice?,
        val weightedAverage: Double,
        val confidence: Double,
        val trend: String,
        val manuallyAdjusted: Boolean = false,
        val lastManualAdjustment: DateTime? = null  // ★★★ NIEUW: toevoegen ★★★
    )

    data class MealParameterAdvice(
        val mealId: String,
        val timestamp: DateTime,
        val parameterAdvice: List<ParameterAgressivenessAdvice>
    )

    // ★★★ VERBETERDE PARAMETER GESCHIEDENIS ★★★
    data class EnhancedParameterHistory(
        val parameterName: String,
        val adviceHistory: MutableList<HistoricalAdvice> = mutableListOf(),
        val mealBasedAdvice: MutableMap<String, ParameterAgressivenessAdvice> = mutableMapOf(), // per maaltijdtype
        val performanceTrend: String = "STABLE",
        val lastManualReset: DateTime? = null,
        val successRateAfterAdjustment: Double = 0.0
    )

    // ★★★ GECENTRALISEERD ADVIES SYSTEEM ★★★
    data class ConsolidatedAdvice(
        val primaryAdvice: String,
        val parameterAdjustments: List<ParameterAgressivenessAdvice>,
        val confidence: Double,
        val reasoning: String,
        val expectedImprovement: String,
        val timestamp: DateTime = DateTime.now()
    )



    private var parameterHistories: MutableMap<String, EnhancedParameterHistory> = mutableMapOf()

    companion object {
        private const val TARGET_LOW = 4.0
        private const val TARGET_HIGH = 10.0
        private const val VERY_LOW_THRESHOLD = 3.0
        private const val VERY_HIGH_THRESHOLD = 13.9
        private const val MIN_READINGS_PER_HOUR = 8.0
        private const val MEAL_END_TIMEOUT = 180

        private var Target_Bg: Double = 5.2

        private var cached24hMetrics: GlucoseMetrics? = null
        private var cached7dMetrics: GlucoseMetrics? = null
        private var cachedDataQuality: DataQualityMetrics? = null
        private var cachedParameterSummaries: MutableMap<String, ParameterAdviceSummary> = mutableMapOf()
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

    private var lastParameterValues: MutableMap<String, Double> = mutableMapOf()
    private var parameterCacheInitialized = false

    private var cachedConsolidatedAdvice: ConsolidatedAdvice? = null
    private var lastConsolidatedAdviceUpdate: DateTime? = null
    private var parameterAdviceHistory: MutableMap<String, ParameterAgressivenessAdvice> = mutableMapOf()

    init {
        lastParameterAdjustments = loadParameterAdjustments().toMutableList()
        loadMealParameterAdviceHistory() // ★★★ NIEUW: Laad parameter advies geschiedenis
        lastParameterAdjustments = loadParameterAdjustments().toMutableList()
        loadParameterAdjustmentTimestamps()
        loadAutoUpdateConfig() // ★★★ NIEUW: Laad automatische update config
        loadAutomaticAdjustmentsHistory() // ★★★ NIEUW: Laad automatische aanpassingen geschiedenis

        loadParameterHistories()   // ★★★ NIEUW: Laad parameter geschiedenis ★★★


        // ★★★ VERBETERDE ADVIES INITIALISATIE ★★★
        parameterAdviceHistory = loadParameterAdviceHistory()
        cachedConsolidatedAdvice = loadConsolidatedAdvice()

        // ★★★ ZEKER DAT ER ALTIJD EEN ADVIES IS ★★★
        ensureAdviceAvailable()
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


    fun shouldCalculateNewAdvice(): Boolean {
        val lastAdviceTime = getLastAdviceTime()
        val hoursSinceLastAdvice = Hours.hoursBetween(lastAdviceTime, DateTime.now()).hours
        val adviceInterval = try {
            preferences.get(IntKey.Advice_Interval_Hours)
        } catch (e: Exception) {
            12
        }

        // ★★★ ALTIJD ADVIES GENEREREN ALS ER NOG GEEN ADVIES IS ★★★
        val hasNoAdvice = parameterAdviceHistory.isEmpty() &&
            cachedConsolidatedAdvice == null &&
            loadConsolidatedAdvice() == null

        if (hasNoAdvice) {
            return true
        }

        // ★★★ NORMALE INTERVAL CHECK ★★★
        val minIntervalPassed = hoursSinceLastAdvice >= 1
        val fullIntervalPassed = hoursSinceLastAdvice >= adviceInterval

        return fullIntervalPassed && minIntervalPassed
    }

    private fun getLastAdviceTime(): DateTime {
        val lastTimeMillis = prefs.getLong("last_advice_time", 0)
        return if (lastTimeMillis > 0) DateTime(lastTimeMillis) else DateTime.now().minusHours(25)
    }

    private fun setLastAdviceTime() {
        prefs.edit().putLong("last_advice_time", DateTime.now().millis).apply()
    }

    // ★★★ PERSISTENTIE VOOR ADVIES ★★★
    private fun loadConsolidatedAdvice(): ConsolidatedAdvice? {
        return try {
            val json = prefs.getString("consolidated_advice", null)
            if (json != null) {
                gson.fromJson(json, ConsolidatedAdvice::class.java)
            } else null
        } catch (e: Exception) {
            null
        }
    }

    private fun storeConsolidatedAdvice(advice: ConsolidatedAdvice) {
        try {
            val json = gson.toJson(advice)
            prefs.edit().putString("consolidated_advice", json).apply()
        } catch (e: Exception) {
            // Logging
        }
    }

    private fun loadParameterAdviceHistory(): MutableMap<String, ParameterAgressivenessAdvice> {
        return try {
            val json = prefs.getString("parameter_advice_history", null)
            if (json != null) {
                val type = object : com.google.gson.reflect.TypeToken<MutableMap<String, ParameterAgressivenessAdvice>>() {}.type
                gson.fromJson<MutableMap<String, ParameterAgressivenessAdvice>>(json, type) ?: mutableMapOf()
            } else mutableMapOf()
        } catch (e: Exception) {
            mutableMapOf()
        }
    }

    private fun storeParameterAdviceHistory() {
        try {
            val json = gson.toJson(parameterAdviceHistory)
            prefs.edit().putString("parameter_advice_history", json).apply()
        } catch (e: Exception) {
            // Logging
        }
    }


    // ★★★ PARAMETER GESCHIEDENIS MANAGEMENT ★★★
    private fun loadParameterHistories() {
        try {
            val json = prefs.getString("parameter_histories", null)
            if (json != null) {
                val type = object : com.google.gson.reflect.TypeToken<MutableMap<String, EnhancedParameterHistory>>() {}.type
                parameterHistories = gson.fromJson<MutableMap<String, EnhancedParameterHistory>>(json, type) ?: mutableMapOf()
            }
        } catch (e: Exception) {
            parameterHistories = mutableMapOf()
        }
    }

    private fun saveParameterHistories() {
        try {
            val json = gson.toJson(parameterHistories)
            prefs.edit().putString("parameter_histories", json).apply()
        } catch (e: Exception) {
            // Logging
        }
    }

    // ★★★ RESET BIJ HANDMATIGE AANPASSING ★★★
    fun resetParameterHistory(parameterName: String) {
        parameterHistories[parameterName] = EnhancedParameterHistory(
            parameterName = parameterName,
            lastManualReset = DateTime.now()
        )
        saveParameterHistories()

        // Reset ook in cache
        cachedParameterSummaries.remove(parameterName)
    }

    // ★★★ ZORG DAT ER ALTIJD EEN ADVIES BESCHIKBAAR IS ★★★
    private fun ensureAdviceAvailable() {
        if (cachedConsolidatedAdvice == null && loadConsolidatedAdvice() == null) {
            // Creëer een standaard advies als er geen bestaat
            cachedConsolidatedAdvice = createDefaultAdvice()
            storeConsolidatedAdvice(cachedConsolidatedAdvice!!)
        }
    }

    // ★★★ GEWOGEN GEMIDDELDE BEREKENING ★★★
    private fun calculateWeightedAdvice(
        parameterName: String,
        newAdvice: ParameterAgressivenessAdvice
    ): ParameterAgressivenessAdvice {
        val history = parameterHistories.getOrPut(parameterName) { EnhancedParameterHistory(parameterName) }

        // ★★★ RESET CHECK ★★★
        if (history.lastManualReset?.isAfter(DateTime.now().minusDays(1)) == true) {
            // Recent gereset, gebruik nieuw advies
            history.adviceHistory.add(HistoricalAdvice(
                timestamp = DateTime.now(),
                recommendedValue = newAdvice.recommendedValue,
                changeDirection = newAdvice.changeDirection,
                confidence = newAdvice.confidence,
                reason = newAdvice.reason
            ))
            saveParameterHistories()
            return newAdvice
        }

        val recentAdvice = history.adviceHistory
            .filter { it.timestamp.isAfter(DateTime.now().minusDays(14)) } // laatste 14 dagen
            .takeLast(5) // laatste 5 adviezen

        if (recentAdvice.isEmpty()) {
            // Geen geschiedenis, gebruik nieuw advies
            history.adviceHistory.add(HistoricalAdvice(
                timestamp = DateTime.now(),
                recommendedValue = newAdvice.recommendedValue,
                changeDirection = newAdvice.changeDirection,
                confidence = newAdvice.confidence,
                reason = newAdvice.reason
            ))
            saveParameterHistories()
            return newAdvice
        }

        // ★★★ GEWOGEN GEMIDDELDE BEREKENEN ★★★
        val weights = listOf(0.4, 0.25, 0.15, 0.1, 0.1) // Meest recent heeft hoogste gewicht
        var weightedValue = 0.0
        var totalWeight = 0.0

        recentAdvice.forEachIndexed { index, historicalAdvice ->
            val weight = weights.getOrElse(index) { 0.05 }
            weightedValue += historicalAdvice.recommendedValue * weight
            totalWeight += weight
        }

        // ★★★ COMBINEER MET NIEUW ADVIES (70% historie, 30% nieuw) ★★★
        val finalValue = (weightedValue * 0.7) + (newAdvice.recommendedValue * 0.3)

        val weightedAdvice = newAdvice.copy(
            recommendedValue = finalValue,
            confidence = min(0.9, newAdvice.confidence * 0.9) // Iets lager vertrouwen bij historie
        )

        // ★★★ SLA NIEUW ADVIES OP IN GESCHIEDENIS ★★★
        history.adviceHistory.add(HistoricalAdvice(
            timestamp = DateTime.now(),
            recommendedValue = newAdvice.recommendedValue, // Oorspronkelijke waarde
            changeDirection = newAdvice.changeDirection,
            confidence = newAdvice.confidence,
            reason = newAdvice.reason
        ))

        // Beperk geschiedenis tot 10 adviezen
        if (history.adviceHistory.size > 10) {
            history.adviceHistory.removeAt(0)
        }

        saveParameterHistories()
        return weightedAdvice
    }

    // ★★★ DETECTEER PARAMETER WIJZIGINGEN BIJ ELKE RUN ★★★
    private fun detectParameterChanges() {
        if (!parameterCacheInitialized) {
            getAllCurrentParameterValues().forEach { (name, value) ->
                lastParameterValues[name] = value
            }
            parameterCacheInitialized = true
            return
        }

        val currentValues = getAllCurrentParameterValues()
        currentValues.forEach { (name, currentValue) ->
            val lastValue = lastParameterValues[name]
            if (lastValue != null && abs(currentValue - lastValue) > 0.01) {
                // ★★★ PARAMETER GEWIJZIGD - RESET ALLEEN ADVIES VOOR DEZE PARAMETER ★★★
                resetParameterHistory(name)
                parameterAdviceHistory.remove(name)

                // ★★★ UPDATE HET GECONSOLIDEERDE ADVIES ★★★
                cachedConsolidatedAdvice?.let { currentAdvice ->
                    val updatedAdjustments = currentAdvice.parameterAdjustments
                        .filterNot { it.parameterName == name }
                    if (updatedAdjustments.size != currentAdvice.parameterAdjustments.size) {
                        val updatedAdvice = currentAdvice.copy(
                            parameterAdjustments = updatedAdjustments,
                            reasoning = if (updatedAdjustments.isEmpty()) {
                                "Parameter aangepast - nieuwe analyse nodig"
                            } else {
                                currentAdvice.reasoning
                            }
                        )
                        cacheConsolidatedAdvice(updatedAdvice)
                    }
                }

                lastParameterValues[name] = currentValue
            }
        }

        lastParameterValues.clear()
        lastParameterValues.putAll(currentValues)
    }

    // ★★★ RESET ALLE ADVIEZEN ★★★
    fun resetAllAdvice() {
        cachedConsolidatedAdvice = null
        parameterAdviceHistory.clear()
        prefs.edit().remove("consolidated_advice").apply()
        prefs.edit().remove("parameter_advice_history").apply()
        resetAdviceTime()
    }

    private fun resetAdviceTime() {
        prefs.edit().putLong("last_advice_time", DateTime.now().minusHours(25).millis).apply()
    }

    // ★★★ HAAL ALLE HUIDIGE PARAMETER WAARDEN OP ★★★
    private fun getAllCurrentParameterValues(): Map<String, Double> {
        return try {
            // Gebruik fallback waarden als FCLParameters niet beschikbaar is
            mapOf(
                "bolus_perc_early" to preferences.get(IntKey.bolus_perc_early).toDouble(),
                "bolus_perc_mid" to preferences.get(IntKey.bolus_perc_mid).toDouble(),
                "bolus_perc_late" to preferences.get(IntKey.bolus_perc_late).toDouble(),
                "bolus_perc_day" to preferences.get(IntKey.bolus_perc_day).toDouble(),
                "bolus_perc_night" to preferences.get(IntKey.bolus_perc_night).toDouble(),
                "meal_detection_sensitivity" to preferences.get(DoubleKey.meal_detection_sensitivity),
                "phase_early_rise_slope" to preferences.get(DoubleKey.phase_early_rise_slope),
                "phase_mid_rise_slope" to preferences.get(DoubleKey.phase_mid_rise_slope),
                "phase_late_rise_slope" to preferences.get(DoubleKey.phase_late_rise_slope),
                "carb_percentage" to preferences.get(IntKey.carb_percentage).toDouble(),
                "peak_damping_percentage" to preferences.get(IntKey.peak_damping_percentage).toDouble(),
                "hypo_risk_percentage" to preferences.get(IntKey.hypo_risk_percentage).toDouble(),
                "IOB_corr_perc" to preferences.get(IntKey.IOB_corr_perc).toDouble()
            )
        } catch (e: Exception) {
            emptyMap()
        }
    }


    // ★★★ BIDIRECTIONELE PRESTATIE ANALYSE FUNCTIES ★★★
    fun analyzeBidirectionalPerformance(
        metrics: GlucoseMetrics,
        mealMetrics: List<MealPerformanceMetrics>
    ): List<PerformanceAnalysis> {
        val analyses = mutableListOf<PerformanceAnalysis>()

        val recentMeals = mealMetrics.filter { it.mealStartTime.isAfter(DateTime.now().minusDays(7)) }
        val successRate = if (recentMeals.isNotEmpty()) {
            recentMeals.count { it.wasSuccessful }.toDouble() / recentMeals.size * 100.0
        } else { 0.0 }

        // ★★★ VERLAAG DREMPELS VOOR PROBLEEMDETECTIE ★★★

        // ★★★ ANALYSE VOOR TE HOGE BLOEDSUIKER ★★★
        if (metrics.timeAboveRange > 20.0 || metrics.averageGlucose > 8.0 || successRate < 70.0) {
            analyses.add(analyzeHighBGIssues(metrics, recentMeals))
        }

        // ★★★ ANALYSE VOOR TE LAGE BLOEDSUIKER ★★★
        if (metrics.timeBelowRange > 4.0 || metrics.lowEvents + metrics.veryLowEvents > 1) {
            analyses.add(analyzeLowBGIssues(metrics, recentMeals))
        }

        // ★★★ GEMENGDE PROBLEMEN ANALYSE ★★★
        if ((metrics.timeAboveRange > 15.0 && metrics.timeBelowRange > 3.0) || successRate < 60.0) {
            analyses.add(analyzeMixedIssues(metrics, recentMeals))
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



    // ★★★ PUBLIC FUNCTIE ZONDER PARAMETERS ★★★
    fun getParameterAdviceSummary(): List<ParameterAdviceSummary> {
        return getCachedParameterSummary()
    }

    // ★★★ PRIVATE CACHE FUNCTIE ★★★
    private fun getCachedParameterSummary(): List<ParameterAdviceSummary> {
        if (cachedParameterSummaries.isEmpty() || shouldCalculateNewAdvice()) {
            cachedParameterSummaries = calculateParameterAdviceSummary().associateBy { it.parameterName }.toMutableMap()
        }
        return cachedParameterSummaries.values.toList()
    }

    // ★★★ BEREKEN PARAMETER ADVIES SUMMARY ★★★
    private fun calculateParameterAdviceSummary(): List<ParameterAdviceSummary> {
        val essentialParameters = listOf(
            "bolus_perc_early", "bolus_perc_mid", "bolus_perc_late",
            "bolus_perc_day", "bolus_perc_night", "meal_detection_sensitivity",
            "phase_early_rise_slope", "phase_mid_rise_slope", "phase_late_rise_slope",
            "carb_percentage", "peak_damping_percentage", "hypo_risk_percentage", "IOB_corr_perc"
        )

        // ★★★ GENEREER ECHTE ADVIEZEN OP BASIS VAN DATA ★★★
        val currentMetrics = calculateMetrics(24)
        val mealMetrics = calculateMealPerformanceMetrics(168)
        val currentAdvice = calculateAgressivenessAdvice(FCLParameters(preferences), currentMetrics, false)

        return essentialParameters.map { paramName ->
            calculateParameterSummary(paramName, currentAdvice)
        }
    }

    private fun calculateParameterSummary(
        parameterName: String,
        currentAdvice: List<ParameterAgressivenessAdvice>
    ): ParameterAdviceSummary {
        val currentValue = getCurrentParameterValue(parameterName)

        // ★★★ ZOEK ADVIES VOOR DEZE PARAMETER ★★★
        val parameterAdvice = currentAdvice.find { it.parameterName == parameterName }

        // ★★★ REALISTISCHE DREMPEL ★★★
        val hasRealAdvice = parameterAdvice != null &&
            parameterAdvice.parameterName != "DEBUG_INFO" &&
            parameterAdvice.parameterName != "GEEN_ADVIES" &&
            parameterAdvice.confidence > 0.3

        // ★★★ GEBRUIK GEWOGEN GEMIDDELDE UIT GESCHIEDENIS ★★★
        val history = parameterHistories[parameterName]
        val weightedAverage = if (history != null && history.adviceHistory.isNotEmpty()) {
            calculateWeightedAverageFromHistory(history.adviceHistory)
        } else {
            parameterAdvice?.recommendedValue ?: currentValue
        }

        return ParameterAdviceSummary(
            parameterName = parameterName,
            currentValue = currentValue,
            lastAdvice = if (hasRealAdvice) parameterAdvice else null,
            weightedAverage = weightedAverage,
            confidence = if (hasRealAdvice) parameterAdvice!!.confidence else 0.0,
            trend = determineTrendFromHistory(history?.adviceHistory ?: emptyList()),
            manuallyAdjusted = isParameterManuallyAdjusted(parameterName),
            lastManualAdjustment = parameterAdjustmentTimestamps[parameterName]
        )
    }

    // ★★★ HAAL PARAMETER WAARDEN OP ZONDER FCLPARAMETERS ★★★
    private fun getCurrentParameterValue(parameterName: String): Double {
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
                else -> 0.0
            }
        } catch (e: Exception) {
            0.0
        }
    }

    // ★★★ GEWOGEN GEMIDDELDE UIT GESCHIEDENIS ★★★
    private fun calculateWeightedAverageFromHistory(adviceHistory: List<HistoricalAdvice>): Double {
        if (adviceHistory.isEmpty()) return 0.0

        val recentAdvice = adviceHistory.takeLast(5) // laatste 5 adviezen
        val weights = listOf(0.4, 0.25, 0.15, 0.1, 0.1) // gewichten voor laatste 5

        var weightedSum = 0.0
        var totalWeight = 0.0

        recentAdvice.forEachIndexed { index, advice ->
            val weight = weights.getOrElse(index) { 0.05 }
            weightedSum += advice.recommendedValue * weight
            totalWeight += weight
        }

        return if (totalWeight > 0) weightedSum / totalWeight else recentAdvice.last().recommendedValue
    }

    // ★★★ TREND BEPALEN UIT GESCHIEDENIS ★★★
    private fun determineTrendFromHistory(adviceHistory: List<HistoricalAdvice>): String {
        if (adviceHistory.size < 3) return "STABLE"

        val recentValues = adviceHistory.takeLast(3).map { it.recommendedValue }
        val olderValues = adviceHistory.take(3).map { it.recommendedValue }

        val recentAvg = recentValues.average()
        val olderAvg = olderValues.average()

        return when {
            recentAvg > olderAvg * 1.05 -> "INCREASING"
            recentAvg < olderAvg * 0.95 -> "DECREASING"
            else -> "STABLE"
        }
    }


    private fun isParameterManuallyAdjusted(parameterName: String): Boolean {
        val lastAdjustment = parameterAdjustmentTimestamps[parameterName]
        return lastAdjustment != null && lastAdjustment.isAfter(DateTime.now().minusDays(7))
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


    /////////////////////////////////////////////////////////////////////////////////////////////////

    // ★★★ VEREENVOUDIGDE HOOFD ADVIES FUNCTIE ★★★
    fun calculateAgressivenessAdvice(
        parameters: FCLParameters,
        metrics: GlucoseMetrics,
        forceNew: Boolean = false
    ): List<ParameterAgressivenessAdvice> {

        // ★★★ DETECTEER PARAMETER WIJZIGINGEN BIJ ELKE RUN ★★★
        detectParameterChanges()

        if (!forceNew && !shouldCalculateNewAdvice()) {
            return getStoredAdvice()
        }

        // Basis data checks
        if (metrics.totalReadings < 50 || metrics.readingsPerHour < MIN_READINGS_PER_HOUR) {
            val insufficientAdvice = createInsufficientDataAdvice(metrics)
            storeAdvice(insufficientAdvice)
            setLastAdviceTime()
            return insufficientAdvice
        }

        val mealMetrics = calculateMealPerformanceMetrics(168)
        val adviceList = generateModulatedAdvice(parameters, metrics, mealMetrics)

        // Opslaan en teruggeven
        storeAdvice(adviceList)
        storeAdviceHistoryEntries(adviceList)
        setLastAdviceTime()

        // ★★★ CACHE HET GECONSOLIDEERDE ADVIES ★★★
        val consolidatedAdvice = getConsolidatedAdvice(parameters, metrics, metrics, mealMetrics)
        cacheConsolidatedAdvice(consolidatedAdvice)

        return adviceList
    }

    fun getConsolidatedAdvice(
        parameters: FCLParameters,
        metrics24h: GlucoseMetrics,
        metrics7d: GlucoseMetrics,
        mealMetrics: List<MealPerformanceMetrics>
    ): ConsolidatedAdvice {

        // ★★★ ANALYSEER ALLE DATA BRONNEN ★★★
        val performanceAnalyses = analyzeBidirectionalPerformance(metrics24h, mealMetrics)
        val topAnalysis = performanceAnalyses.firstOrNull()
        val parameterAdvice = generateBalancedAdvice(parameters, metrics24h, mealMetrics)

        // ★★★ GEBRUIK HISTORISCHE ADVIEZEN ALS ER GEEN NIEUWE ZIJN ★★★
        val finalParameterAdvice = if (parameterAdvice.isEmpty() && parameterAdviceHistory.isNotEmpty()) {
            // Geen nieuwe adviezen - gebruik historische met verminderd vertrouwen
            parameterAdviceHistory.values.map { advice ->
                advice.copy(
                    confidence = advice.confidence * 0.8,
                    reason = "${advice.reason} (vorig advies - wacht op nieuwe data)"
                )
            }.filter { it.confidence > 0.3 }
                .sortedByDescending { it.confidence }
                .take(3)
        } else {
            // Gebruik nieuwe adviezen
            parameterAdvice.filter { it.confidence > 0.3 }
                .sortedByDescending { it.confidence }
                .take(3)
        }

        // ★★★ BEPAAL HOOFDADVIES ★★★
        val (primaryAdvice, reasoning) = when {
            topAnalysis != null && topAnalysis.severity > 0.6 -> {
                val action = when (topAnalysis.adjustmentDirection) {
                    "INCREASE" -> "verhogen"
                    "DECREASE" -> "verlagen"
                    else -> "aanpassen"
                }
                "${action} ${getParameterDisplayName(topAnalysis.primaryParameter)}" to topAnalysis.reasoning
            }
            finalParameterAdvice.isNotEmpty() -> {
                val mainParam = finalParameterAdvice.first()
                "${getDirectionText(mainParam.changeDirection)} ${getParameterDisplayName(mainParam.parameterName)}" to
                    mainParam.reason
            }
            else -> "Geen aanpassingen nodig" to "Glucosewaarden binnen acceptabele marges"
        }

        // ★★★ BEREKEN VERTROUWEN ★★★
        val overallConfidence = if (finalParameterAdvice.isNotEmpty()) {
            finalParameterAdvice.map { it.confidence }.average().coerceIn(0.0, 1.0)
        } else {
            0.0
        }

        return ConsolidatedAdvice(
            primaryAdvice = primaryAdvice,
            parameterAdjustments = finalParameterAdvice,
            confidence = overallConfidence,
            reasoning = reasoning,
            expectedImprovement = "Verbetering TIR en vermindering pieken >11 mmol/L"
        )
    }

    // ★★★ COMBINEER NIEUWE ADVIEZEN MET HISTORISCHE ★★★
    private fun combineWithHistoricalAdvice(
        newAdvice: List<ParameterAgressivenessAdvice>,
        mealMetrics: List<MealPerformanceMetrics>
    ): List<ParameterAgressivenessAdvice> {

        if (mealMetrics.isEmpty()) {
            // Geen nieuwe maaltijd data, gebruik historische adviezen
            return parameterAdviceHistory.values.toList()
        }

        val combined = mutableListOf<ParameterAgressivenessAdvice>()

        // Voeg nieuwe adviezen toe
        combined.addAll(newAdvice)

        // Voeg historische adviezen toe die niet in nieuwe adviezen zitten
        parameterAdviceHistory.forEach { (paramName, historicalAdvice) ->
            if (newAdvice.none { it.parameterName == paramName }) {
                // Verminder vertrouwen voor oude adviezen, maar behoud ze
                val agedAdvice = historicalAdvice.copy(
                    confidence = historicalAdvice.confidence * 0.7, // Verlaag vertrouwen met 30%
                    reason = "${historicalAdvice.reason} (vorig advies)"
                )
                combined.add(agedAdvice)
            }
        }

        return combined
    }

    fun getCurrentConsolidatedAdvice(): ConsolidatedAdvice {
        // ★★★ GEBRUIK OPGESLAGEN ADVIES OF GENEREER EEN STANDAARD ★★★
        return cachedConsolidatedAdvice ?: loadConsolidatedAdvice() ?: createDefaultAdvice()
    }

    private fun createDefaultAdvice(): ConsolidatedAdvice {
        return ConsolidatedAdvice(
            primaryAdvice = "Wacht op eerste analyse",
            parameterAdjustments = emptyList(),
            confidence = 0.0,
            reasoning = "Er is nog onvoldoende data geanalyseerd voor een advies",
            expectedImprovement = "Eerste advies wordt gegenereerd na voldoende maaltijd data"
        )
    }

    private fun cacheConsolidatedAdvice(advice: ConsolidatedAdvice) {
        cachedConsolidatedAdvice = advice
        lastConsolidatedAdviceUpdate = DateTime.now()

        // ★★★ SLA ADVIES OP ★★★
        storeConsolidatedAdvice(advice)

        // ★★★ UPDATE PARAMETER ADVIES GESCHIEDENIS ★★★
        advice.parameterAdjustments.forEach { paramAdvice ->
            parameterAdviceHistory[paramAdvice.parameterName] = paramAdvice
        }

        // ★★★ VERWIJDER OUDE ADVIEZEN UIT GESCHIEDENIS ★★★
        cleanupOldAdviceHistory()

        storeParameterAdviceHistory()
    }

    // ★★★ OPKUISEN VAN OUDE ADVIES GESCHIEDENIS ★★★
    private fun cleanupOldAdviceHistory() {
        val cutoffTime = DateTime.now().minusDays(30) // Bewaar max 30 dagen
        val iterator = parameterAdviceHistory.iterator()
        while (iterator.hasNext()) {
            val (_, advice) = iterator.next()
            if (advice.timestamp.isBefore(cutoffTime)) {
                iterator.remove()
            }
        }
    }

    // ★★★ FORCEER ADVIES UPDATE ★★★
    fun forceAdviceUpdate(parameters: FCLParameters, metrics: GlucoseMetrics): ConsolidatedAdvice {
        // Reset timing
        prefs.edit().putLong("last_advice_time", DateTime.now().minusHours(25).millis).apply()

        val mealMetrics = calculateMealPerformanceMetrics(168)
        val newAdvice = getConsolidatedAdvice(parameters, metrics, metrics, mealMetrics)

        cacheConsolidatedAdvice(newAdvice)

        return newAdvice
    }

    // ★★★ CHECK OF ADVIES BESCHIKBAAR IS ★★★
    fun isAdviceAvailable(): Boolean {
        return cachedConsolidatedAdvice != null ||
            loadConsolidatedAdvice() != null ||
            parameterAdviceHistory.isNotEmpty()
    }

    private fun createInsufficientDataAdvice(metrics: GlucoseMetrics): List<ParameterAgressivenessAdvice> {
        return listOf(
            ParameterAgressivenessAdvice(
                parameterName = "DATA_ISSUE",
                currentValue = 0.0,
                recommendedValue = 0.0,
                reason = "Onvoldoende data (${metrics.totalReadings} metingen, ${metrics.readingsPerHour.toInt()}/u)",
                confidence = 0.0,
                expectedImprovement = "Meer data verzamelen",
                changeDirection = "STABLE"
            )
        )
    }


    // ★★★ HOOFD ADVIES FUNCTIE - VERVANG DEZE ★★★
    private fun generateModulatedAdvice(
        parameters: FCLParameters,
        metrics: GlucoseMetrics,
        mealMetrics: List<MealPerformanceMetrics>
    ): List<ParameterAgressivenessAdvice> {
        return generateBalancedAdvice(parameters, metrics, mealMetrics)
    }

    // ★★★ BASIS ADVIES FUNCTIES ★★★
    private fun generateBasicStabilityAdvice(
        parameters: FCLParameters,
        metrics: GlucoseMetrics
    ): List<ParameterAgressivenessAdvice> {
        return listOf(
            createParameterAdvice(
                parameters,
                "meal_detection_sensitivity",
                "DECREASE",
                "Startup: wacht op maaltijd data",
                0.4,
                "Verbeter detectie voor betere analyse"
            )
        )
    }

    // ★★★ VEILIGERE PARAMETER ADVIES CREATIE ★★★
    private fun createParameterAdvice(
        parameters: FCLParameters,
        parameterName: String,
        direction: String,
        reason: String,
        confidence: Double,
        expectedImprovement: String
    ): ParameterAgressivenessAdvice {
        val currentValue = getCurrentParameterValue(parameters, parameterName)

        // ★★★ VEILIGERE AANPASSINGSBERKENING ★★★
        val adjustmentFactor = when {
            parameterName.contains("hypo", ignoreCase = true) -> 0.15 // Kleinere stap bij hypo parameters
            direction == "DECREASE" -> 0.12 // Conservatiever bij verlagen
            else -> 0.10
        }

        val newValue = when (direction) {
            "INCREASE" -> currentValue * (1 + adjustmentFactor)
            "DECREASE" -> currentValue * (1 - adjustmentFactor)
            else -> currentValue
        }

        val boundedValue = applyParameterBounds(newValue, parameterName)

        return ParameterAgressivenessAdvice(
            parameterName = parameterName,
            currentValue = currentValue,
            recommendedValue = boundedValue,
            reason = reason,
            confidence = confidence,
            expectedImprovement = expectedImprovement,
            changeDirection = direction
        )
    }

    private fun applyParameterBounds(value: Double, parameterName: String): Double {
        val (min, max) = getParameterBounds(parameterName)
        return value.coerceIn(min, max)
    }

    // ★★★ VERBETERDE PARAMETER GRENZEN ★★★
    private fun getParameterBounds(parameterName: String): Pair<Double, Double> {
        return when (parameterName) {
            "bolus_perc_early", "bolus_perc_mid", "bolus_perc_late", "bolus_perc_day" -> Pair(10.0, 200.0)
            "bolus_perc_night" -> Pair(5.0, 80.0) // ← LAGERE MAX WAARDE VOOR NACHT
            "meal_detection_sensitivity" -> Pair(0.1, 0.5)
            "phase_early_rise_slope", "phase_mid_rise_slope" -> Pair(0.3, 2.5)
            "phase_late_rise_slope" -> Pair(0.1, 1.0)
            "carb_percentage" -> Pair(10.0, 200.0)
            "peak_damping_percentage" -> Pair(10.0, 100.0)
            "hypo_risk_percentage" -> Pair(5.0, 50.0) // ← LAGERE MAX WAARDE VOOR VEILIGHEID
            "IOB_corr_perc" -> Pair(50.0, 150.0)
            else -> Pair(0.0, 100.0)
        }
    }

    // ★★★ KERN ADVIES GENERATIE - GEBALANCEERD ★★★
// ★★★ VERBETERDE HOOFD ADVIES GENERATIE ★★★
    private fun generateBalancedAdvice(
        parameters: FCLParameters,
        metrics: GlucoseMetrics,
        mealMetrics: List<MealPerformanceMetrics>
    ): List<ParameterAgressivenessAdvice> {
        val recentMeals = mealMetrics.filter {
            it.mealStartTime.isAfter(DateTime.now().minusDays(7))
        }

        if (recentMeals.isEmpty()) {
            return generateBasicStabilityAdvice(parameters, metrics)
        }

        val adviceList = mutableListOf<ParameterAgressivenessAdvice>()

        // ★★★ ANALYSEER ALLE PARAMETERS OP MAALTIJD NIVEAU ★★★
        adviceList.addAll(analyzeMealTimingParameters(parameters, recentMeals))
        adviceList.addAll(analyzeBolusAggressiveness(parameters, metrics, recentMeals))
        adviceList.addAll(analyzeSafetyParameters(parameters, metrics, recentMeals))
        adviceList.addAll(analyzeDetectionParameters(parameters, recentMeals))

        // ★★★ PAS GEWOGEN GEMIDDELDE TOE ★★★
        val weightedAdviceList = adviceList.map { calculateWeightedAdvice(it.parameterName, it) }

        return weightedAdviceList
            .filter { it.confidence > 0.4 }
            .distinctBy { it.parameterName }
            .sortedByDescending { it.confidence }
            .take(5) // Meer parameters toestaan
    }

    // ★★★ MAALTIJD TIMING PARAMETERS ★★★
    private fun analyzeMealTimingParameters(
        parameters: FCLParameters,
        recentMeals: List<MealPerformanceMetrics>
    ): List<ParameterAgressivenessAdvice> {
        val adviceList = mutableListOf<ParameterAgressivenessAdvice>()

        if (recentMeals.isEmpty()) return adviceList

        val earlyPeaks = recentMeals.count { it.timeToPeak < 45 && it.peakBG > 10.0 }
        val latePeaks = recentMeals.count { it.timeToPeak > 120 && it.peakBG > 10.0 }
        val avgTimeToPeak = recentMeals.map { it.timeToPeak }.average()

        // Early Rise Bolus - voor vroege pieken
        if (earlyPeaks > recentMeals.size * 0.2) {
            adviceList.add(createParameterAdvice(
                parameters, "bolus_perc_early", "INCREASE",
                "${earlyPeaks}/${recentMeals.size} maaltijden met vroege pieken (<45min)",
                min(0.8, earlyPeaks.toDouble() / recentMeals.size),
                "Verminder vroege pieken door vroegere insuline"
            ))
        }

        // Mid Rise Bolus - voor algemene timing
        if (avgTimeToPeak in 60.0..90.0 && recentMeals.count { it.peakBG > 10.0 } > recentMeals.size * 0.3) {
            adviceList.add(createParameterAdvice(
                parameters, "bolus_perc_mid", "INCREASE",
                "Gemiddelde piektijd ${avgTimeToPeak.toInt()}min met ${recentMeals.count { it.peakBG > 10.0 }}/${recentMeals.size} hoge pieken",
                0.7, "Verlaag pieken door betere mid-fase timing"
            ))
        }

        // Late Rise Bolus - voor late pieken
        if (latePeaks > recentMeals.size * 0.15) {
            adviceList.add(createParameterAdvice(
                parameters, "bolus_perc_late", "INCREASE",
                "${latePeaks}/${recentMeals.size} maaltijden met late pieken (>120min)",
                min(0.7, latePeaks.toDouble() / recentMeals.size),
                "Verminder late pieken door betere late-fase bolus"
            ))
        }

        return adviceList
    }

    // ★★★ BOLUS AGRESSIVITEIT ★★★
    private fun analyzeBolusAggressiveness(
        parameters: FCLParameters,
        metrics: GlucoseMetrics,
        recentMeals: List<MealPerformanceMetrics>
    ): List<ParameterAgressivenessAdvice> {
        val adviceList = mutableListOf<ParameterAgressivenessAdvice>()

        val successRate = calculateSuccessRate(recentMeals)
        val highPeakPercentage = recentMeals.count { it.peakBG > 11.0 }.toDouble() / recentMeals.size * 100

        // Daytime Bolus
        if (metrics.timeAboveRange > 25.0 || highPeakPercentage > 30.0) {
            adviceList.add(createParameterAdvice(
                parameters, "bolus_perc_day", "INCREASE",
                "Hoge glucose (${metrics.timeAboveRange.toInt()}% boven range, ${highPeakPercentage.toInt()}% hoge pieken)",
                min(0.8, max(0.5, metrics.timeAboveRange / 50.0)),
                "Verlaag tijd boven range door hogere dag agressiviteit"
            ))
        }

        // Nighttime Bolus - conservatiever
        val nightMeals = recentMeals.filter { isMealAtNight(it) }
        val nightHypos = nightMeals.count { it.postMealHypo }
        if (nightHypos > 0 && nightMeals.isNotEmpty()) {
            val nightHypoPercentage = (nightHypos.toDouble() / nightMeals.size) * 100
            if (nightHypoPercentage > 10.0) {
                adviceList.add(createParameterAdvice(
                    parameters, "bolus_perc_night", "DECREASE",
                    "${nightHypos}/${nightMeals.size} nachtelijke hypo's (${nightHypoPercentage.toInt()}%)",
                    min(0.8, nightHypoPercentage / 100.0),
                    "Vermijd nachtelijke hypo's door lagere agressiviteit"
                ))
            }
        }

        return adviceList
    }

    // ★★★ VEILIGHEID PARAMETERS ★★★
    private fun analyzeSafetyParameters(
        parameters: FCLParameters,
        metrics: GlucoseMetrics,
        recentMeals: List<MealPerformanceMetrics>
    ): List<ParameterAgressivenessAdvice> {
        val adviceList = mutableListOf<ParameterAgressivenessAdvice>()

        val postMealHypos = recentMeals.count { it.postMealHypo }
        val hypoPercentage = if (recentMeals.isNotEmpty()) (postMealHypos.toDouble() / recentMeals.size) * 100 else 0.0
        val rapidDeclines = recentMeals.count { it.rapidDeclineDetected }

        // Hypo Risk Percentage
        if (hypoPercentage > 10.0 || metrics.timeBelowRange > 8.0) {
            adviceList.add(createParameterAdvice(
                parameters, "hypo_risk_percentage", "INCREASE",
                "${postMealHypos}/${recentMeals.size} hypo's + ${metrics.timeBelowRange.toInt()}% onder range",
                min(0.9, max(0.5, hypoPercentage / 50.0)),
                "Verminder hypo's door hogere risico reductie"
            ))
        }

        // Peak Damping - voor snelle dalingen
        if (rapidDeclines > recentMeals.size * 0.2) {
            adviceList.add(createParameterAdvice(
                parameters, "peak_damping_percentage", "INCREASE",
                "${rapidDeclines}/${recentMeals.size} maaltijden met snelle dalingen",
                min(0.8, rapidDeclines.toDouble() / recentMeals.size),
                "Verminder snelle dalingen door betere piekcontrole"
            ))
        }

        // IOB Safety
        val highIOBMeals = recentMeals.count { it.maxIOBDuringMeal > 3.0 }
        if (highIOBMeals > recentMeals.size * 0.3) {
            adviceList.add(createParameterAdvice(
                parameters, "IOB_corr_perc", "INCREASE",
                "${highIOBMeals}/${recentMeals.size} maaltijden met hoge IOB (>3.0)",
                min(0.7, highIOBMeals.toDouble() / recentMeals.size),
                "Vermijd hoge IOB door strengere correctie"
            ))
        }

        return adviceList
    }

    // ★★★ DETECTIE PARAMETERS ★★★
    private fun analyzeDetectionParameters(
        parameters: FCLParameters,
        recentMeals: List<MealPerformanceMetrics>
    ): List<ParameterAgressivenessAdvice> {
        val adviceList = mutableListOf<ParameterAgressivenessAdvice>()

        val successRate = calculateSuccessRate(recentMeals)
        val avgTimeToFirstBolus = if (recentMeals.any { it.timeToFirstBolus > 0 }) {
            recentMeals.filter { it.timeToFirstBolus > 0 }.map { it.timeToFirstBolus }.average()
        } else {
            0.0
        }

        // Meal Detection Sensitivity
        if (successRate < 70.0) {
            val direction = if (avgTimeToFirstBolus > 20) "DECREASE" else "INCREASE"
            val reason = if (avgTimeToFirstBolus > 20)
                "Lange responstijd (${avgTimeToFirstBolus.toInt()}min) - te late detectie"
            else
                "Lage succesrate (${successRate.toInt()}%) - te vroege detectie"

            adviceList.add(createParameterAdvice(
                parameters, "meal_detection_sensitivity", direction,
                reason,
                min(0.7, (100 - successRate) / 100.0),
                if (direction == "DECREASE") "Verbeter timing" else "Verminder vals positieven"
            ))
        }

        // Carb Detection Percentage
        val underBolusMeals = recentMeals.count { it.peakBG > 10.0 && it.totalInsulinDelivered < 2.0 }
        if (underBolusMeals > recentMeals.size * 0.25) {
            adviceList.add(createParameterAdvice(
                parameters, "carb_percentage", "INCREASE",
                "${underBolusMeals}/${recentMeals.size} maaltijden met hoge pieken en lage insuline",
                min(0.7, underBolusMeals.toDouble() / recentMeals.size),
                "Verhoag gedetecteerde carbs voor adequate bolus"
            ))
        }

        return adviceList
    }



    // ★★★ VEREENVOUDIGDE PARAMETER ACCESS ★★★
    private fun getCurrentParameterValue(parameters: FCLParameters, parameterName: String): Double {
        return try {
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
                else -> 0.0
            }
        } catch (e: Exception) {
            0.0
        }
    }


    // ★★★ HELPER FUNCTIES VOOR ADVIES WEERGAVE ★★★
    private fun getParameterDisplayName(technicalName: String): String {
        return when (technicalName) {
            "bolus_perc_early" -> "Vroege fase bolus %"
            "bolus_perc_mid" -> "Mid fase bolus %"
            "bolus_perc_late" -> "Late fase bolus %"
            "bolus_perc_day" -> "Dag agressiviteit %"
            "bolus_perc_night" -> "Nacht agressiviteit %"
            "meal_detection_sensitivity" -> "Maaltijd detectie gevoeligheid"
            "phase_early_rise_slope" -> "Vroege stijging drempel"
            "phase_mid_rise_slope" -> "Mid stijging drempel"
            "phase_late_rise_slope" -> "Late stijging drempel"
            "carb_percentage" -> "Carb detectie %"
            "peak_damping_percentage" -> "Piek demping %"
            "hypo_risk_percentage" -> "Hypo risico reductie %"
            "IOB_corr_perc" -> "IOB correctie %"
            else -> technicalName
        }
    }

    private fun getDirectionText(direction: String): String {
        return when (direction) {
            "INCREASE" -> "Verhogen"
            "DECREASE" -> "Verlagen"
            else -> "Onveranderd"
        }
    }

    private fun getTrendSymbol(trend: String): String {
        return when (trend) {
            "INCREASING" -> "📈"
            "DECREASING" -> "📉"
            else -> "➡️"
        }
    }

    private fun getConfidenceIndicator(confidence: Double): String {
        return when {
            confidence > 0.8 -> "🟢"
            confidence > 0.6 -> "🟡"
            else -> "🔴"
        }
    }

    private fun formatParameterValue(parameterName: String, value: Double): String {
        return when {
            parameterName.contains("percentage", ignoreCase = true) ||
                parameterName.contains("perc", ignoreCase = true) -> "${value.toInt()}%"
            parameterName.contains("sensitivity", ignoreCase = true) -> String.format("%.2f", value)
            parameterName.contains("slope", ignoreCase = true) -> String.format("%.1f mmol/L/uur", value)
            else -> String.format("%.1f", value)
        }
    }


    // ★★★ ESSENTIËLE HELPER FUNCTIES ★★★
    private fun isMealAtNight(meal: MealPerformanceMetrics): Boolean {
        val hour = meal.mealStartTime.hourOfDay
        return hour < 6 || hour >= 22
    }

    private fun calculateSuccessRate(mealMetrics: List<MealPerformanceMetrics>): Double {
        if (mealMetrics.isEmpty()) return 0.0
        val successful = mealMetrics.count { it.wasSuccessful }
        return (successful.toDouble() / mealMetrics.size) * 100.0
    }

    private fun round(value: Double, digits: Int): Double {
        val scale = Math.pow(10.0, digits.toDouble())
        return Math.round(value * scale) / scale
    }



}