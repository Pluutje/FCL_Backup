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
        val mealType: String = "unknown"
    )

    data class ParameterAdjustmentResult(
        val parameterName: String,
        val oldValue: Double,
        val newValue: Double,
        val adjustmentTime: DateTime,
        val mealMetricsBefore: MealPerformanceMetrics?,
        val mealMetricsAfter: MealPerformanceMetrics?,
        val improvement: Double,
        val learned: Boolean = false
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

    init {
        lastParameterAdjustments = loadParameterAdjustments().toMutableList()
    }

    fun setTargetBg(value: Double) { Target_Bg = value }

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

    // ★★★ DATA QUALITY METRICS ★★★
    fun getDataQualityMetrics(hours: Int = 24): DataQualityMetrics {
        if (!shouldCalculateNewMetrics() && cachedDataQuality != null && hours == 24) {
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

        val adviceList = mutableListOf<ParameterAgressivenessAdvice>()

        // 1. LEARNING-BASED ADVIES
        adviceList.addAll(generateLearningBasedAdvice(parameters))

        // 2. MAALTIJD-SPECIFIEKE ANALYSE
        adviceList.addAll(generateMealBasedAdvice(parameters))

        val finalAdvice = adviceList
            .take(5)
            .distinctBy { it.parameterName }
            .sortedByDescending { it.confidence }

        storeAdvice(finalAdvice)
        setLastAdviceTime()

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
            // Gebruik FCLParameters voor de waarde
            parameters.getParameterValue(parameterName) ?: getFallbackParameterValue(parameters, parameterName)
        } catch (e: Exception) {
            getFallbackParameterValue(parameters, parameterName)
        }
    }

    private fun getFallbackParameterValue(parameters: FCLParameters, parameterName: String): Double {
        return try {
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
                "Hypo Risk Reduction %" -> preferences.get(IntKey.hypo_risk_percentage).toDouble()
                "IOB Safety %" -> preferences.get(IntKey.IOB_corr_perc).toDouble()
                else -> {
                    // Probeer parameter via FCLParameters naam
                    val allParams = parameters.getAllParameters()
                    allParams[parameterName]?.current ?: 0.0
                }
            }
        } catch (e: Exception) {
            0.0
        }
    }

    private fun getParameterDefinition(parameters: FCLParameters, parameterName: String): FCLParameters.ParameterDefinition? {
        return try {
            parameters.getParameterDefinition(parameterName)
        } catch (e: Exception) {
            null
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

    // ★★★ MAALTIJD-GEBASEERD ADVIES ★★★
    private fun generateMealBasedAdvice(parameters: FCLParameters): List<ParameterAgressivenessAdvice> {
        val mealMetrics = calculateMealPerformanceMetrics(168)
        val recentMeals = mealMetrics.filter { it.mealStartTime.isAfter(DateTime.now().minusDays(7)) }

        if (recentMeals.size < 3) {
            return listOf(createInsufficientMealDataAdvice(parameters, recentMeals.size))
        }

        return analyzeMealPerformance(parameters, recentMeals)
    }

    private fun analyzeMealPerformance(
        parameters: FCLParameters,
        mealMetrics: List<MealPerformanceMetrics>
    ): List<ParameterAgressivenessAdvice> {
        val advice = mutableListOf<ParameterAgressivenessAdvice>()

        advice.addAll(analyzeMealTiming(parameters, mealMetrics))
        advice.addAll(analyzePeakControl(parameters, mealMetrics))
        advice.addAll(analyzeHypoSafety(parameters, mealMetrics))
        advice.addAll(analyzeSuccessRate(parameters, mealMetrics))

        return advice
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
        val hypoPercentage = (postMealHypos.toDouble() / mealMetrics.size) * 100

        return if (hypoPercentage > 15) {
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
        val parameterName = "Hypo Risk Reduction %"
        val currentValue = getCurrentParameterValue(parameters, parameterName)
        val definition = getParameterDefinition(parameters, parameterName)

        val minValue = definition?.minValue ?: 20.0
        val maxValue = definition?.maxValue ?: 50.0

        return ParameterAgressivenessAdvice(
            parameterName = parameterName,
            currentValue = currentValue,
            recommendedValue = calculateOptimalIncrease(currentValue, maxValue),
            reason = "Hoge hypo ratio: ${hypoPercentage.toInt()}% van maaltijden met hypo (successRate: ${(successRate * 100).toInt()}%)",
            confidence = 0.8,
            expectedImprovement = "Verlaag hypo's met ~${(hypoPercentage * 0.5).toInt()}%",
            changeDirection = "INCREASE"
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
                parameters, "Hypo Risk Reduction %", "INCREASE",
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
            "Hypo Risk Reduction %" -> 20.0
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
            "Hypo Risk Reduction %" -> 50.0
            "IOB Safety %" -> 150.0
            else -> 100.0
        }
    }

    // ★★★ HELPER FUNCTIES ★★★
    private fun calculateSuccessRate(mealMetrics: List<MealPerformanceMetrics>): Double {
        if (mealMetrics.isEmpty()) return 0.0
        return mealMetrics.count { it.wasSuccessful }.toDouble() / mealMetrics.size
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
                if (parts.size >= 15) {
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

    private fun calculateTimeInRange(data: List<CSVReading>): Double {
        if (data.isEmpty()) return 0.0
        val inRange = data.count { it.currentBG in TARGET_LOW..TARGET_HIGH }
        return (inRange.toDouble() / data.size) * 100.0
    }

    private fun calculateTimeBelowRange(data: List<CSVReading>): Double {
        if (data.isEmpty()) return 0.0
        val belowRange = data.count { it.currentBG < TARGET_LOW }
        return (belowRange.toDouble() / data.size) * 100.0
    }

    private fun calculateTimeAboveRange(data: List<CSVReading>): Double {
        if (data.isEmpty()) return 0.0
        val aboveRange = data.count { it.currentBG > TARGET_HIGH }
        return (aboveRange.toDouble() / data.size) * 100.0
    }

    private fun calculateTimeBelowTarget(data: List<CSVReading>): Double {
        if (data.isEmpty()) return 0.0
        val belowTarget = data.count { it.currentBG < Target_Bg }
        return (belowTarget.toDouble() / data.size) * 100.0
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

    // ★★★ MAALTIJD METRICS BEREKENING ★★★
    fun calculateMealPerformanceMetrics(hours: Int = 24): List<MealPerformanceMetrics> {
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
            in 6..10 -> "breakfast"
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

    // ★★★ PARAMETER LEARNING SYSTEEM ★★★
    fun trackParameterAdjustment(
        parameterName: String,
        oldValue: Double,
        newValue: Double
    ) {
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
}