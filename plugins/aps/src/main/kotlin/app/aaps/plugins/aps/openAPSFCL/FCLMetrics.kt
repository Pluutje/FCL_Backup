package app.aaps.plugins.aps.openAPSFCL

import android.content.Context
import android.os.Environment
import app.aaps.core.keys.Preferences
import com.google.gson.Gson
import org.joda.time.DateTime
import org.joda.time.Days
import org.joda.time.Hours
import org.joda.time.Minutes
import org.joda.time.format.DateTimeFormat
import org.joda.time.format.DateTimeFormatter
import java.io.File
import kotlin.math.*
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.DoubleKey

// ★★★ OPTIMALISATIE DATA CLASSES ★★★
data class ParameterSnapshot(
    val timestamp: DateTime,
    // ★★★ NIEUWE 2-FASE PARAMETERS ★★★
    val bolusPercRising: Double,
    val bolusPercPlateau: Double,
    val phaseRisingSlope: Double,
    val phasePlateauSlope: Double,
    // ★★★ BEHOUD DE OVERIGE PARAMETERS ★★★
    val bolusPercDay: Double,
    val bolusPercNight: Double,
    val mealDetectionSensitivity: Double,
    val peakDampingPercentage: Double,
    val hypoRiskPercentage: Double,
    val carbPercentage: Double,
    val IOBcorrPerc: Double
)

data class MealOptimizationSession(
    val mealId: String,
    val startTime: DateTime,
    val startBG: Double,
    val initialIOB: Double,
    val detectedCarbs: Double,
    val activeParameters: ParameterSnapshot,
    val dataPoints: MutableList<OptimizationDataPoint>,
    var optimizationScheduled: Boolean
)

data class OptimizationDataPoint(
    val timestamp: DateTime,
    val bg: Double,
    val iob: Double,
    val carbsOnBoard: Double,
    val insulinDelivered: Double,
    val phase: String,
    val activeParameters: ParameterSnapshot
)

data class OptimizationTask(
    val mealSession: MealOptimizationSession,
    val scheduledTime: DateTime,
    val priority: Int
)


data class QuickMealMetrics(
    val peakBG: Double = 0.0,
    val timeToFirstBolus: Int = 0,
    val postMealHypo: Boolean = false,
    val rapidDeclineDetected: Boolean = false
)



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


    // ★★★ UNIFORME PARAMETER ADVIES CLASS ★★★
    data class ParameterAdvice(
        val parameterName: String,
        val currentValue: Double,
        val recommendedValue: Double,
        val reason: String,
        val confidence: Double,
        val direction: String
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
    // ★★★ OPTIMALISATIE SYSTEEM INIT ★★★
    private val parameterHistory = ParameterHistoryManager(preferences)
    private val optimizationController = FCLOptimizationController(preferences, this, parameterHistory)
    private var fclReference: FCL? = null

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
        loadMealParameterAdviceHistory()
        loadParameterAdjustmentTimestamps()
        loadAutoUpdateConfig()
        loadAutomaticAdjustmentsHistory()
        loadParameterHistories()

        parameterAdviceHistory = loadParameterAdviceHistory()
        cachedConsolidatedAdvice = loadConsolidatedAdvice()

        ensureAdviceAvailable()
    }
    fun setFCLReference(fcl: FCL) {
        this.fclReference = fcl
    }

    // ★★★ PARAMETER HISTORY MANAGER - NIEUWE INNER CLASS ★★★
// ★★★ PARAMETER HISTORY MANAGER - NIEUWE INNER CLASS ★★★
    inner class ParameterHistoryManager(private val preferences: Preferences) {

        private val parameterCache = mutableMapOf<String, ParameterSnapshot>()
        private var lastCacheUpdate: DateTime = DateTime.now()

        fun getCurrentParameterSnapshot(): ParameterSnapshot {
            val now = DateTime.now()

            if (Minutes.minutesBetween(lastCacheUpdate, now).minutes > 1) {
                parameterCache.clear()
            }

            val cacheKey = now.toString("yyyy-MM-dd_HH:mm")
            return parameterCache.getOrPut(cacheKey) {
                ParameterSnapshot(
                    timestamp = now,
                    // ★★★ NIEUWE 2-FASE PARAMETERS ★★★
                    bolusPercRising = getParameterValue("bolus_perc_rising"),
                    bolusPercPlateau = getParameterValue("bolus_perc_plateau"),
                    phaseRisingSlope = getParameterValue("phase_rising_slope"),
                    phasePlateauSlope = getParameterValue("phase_plateau_slope"),
                    // ★★★ OVERIGE PARAMETERS ★★★
                    bolusPercDay = getParameterValue("bolus_perc_day"),
                    bolusPercNight = getParameterValue("bolus_perc_night"),
                    mealDetectionSensitivity = getParameterValue("meal_detection_sensitivity"),
                    peakDampingPercentage = getParameterValue("peak_damping_percentage"),
                    hypoRiskPercentage = getParameterValue("hypo_risk_percentage"),
                    carbPercentage = getParameterValue("carb_percentage"),
                    IOBcorrPerc = getParameterValue("IOB_corr_perc")
                )
            }.also {
                lastCacheUpdate = now
            }
        }

        private fun getParameterValue(parameterName: String): Double {
            return try {
                when (parameterName) {
                    // ★★★ NIEUWE 2-FASE PARAMETERS ★★★
                    "bolus_perc_rising" -> preferences.get(IntKey.bolus_perc_rising).toDouble()
                    "bolus_perc_plateau" -> preferences.get(IntKey.bolus_perc_plateau).toDouble()
                    "phase_rising_slope" -> preferences.get(DoubleKey.phase_rising_slope)
                    "phase_plateau_slope" -> preferences.get(DoubleKey.phase_plateau_slope)
                    // ★★★ OVERIGE PARAMETERS ★★★
                    "bolus_perc_day" -> preferences.get(IntKey.bolus_perc_day).toDouble()
                    "bolus_perc_night" -> preferences.get(IntKey.bolus_perc_night).toDouble()
                    "meal_detection_sensitivity" -> preferences.get(DoubleKey.meal_detection_sensitivity)
                    "peak_damping_percentage" -> preferences.get(IntKey.peak_damping_percentage).toDouble()
                    "hypo_risk_percentage" -> preferences.get(IntKey.hypo_risk_percentage).toDouble()
                    "carb_percentage" -> preferences.get(IntKey.carb_percentage).toDouble()
                    "IOB_corr_perc" -> preferences.get(IntKey.IOB_corr_perc).toDouble()
                    else -> 0.0
                }
            } catch (e: Exception) {
                0.0
            }
        }
    }


// ★★★ OPTIMALISATIE CONTROLLER - NIEUWE INNER CLASS ★★★
    inner class FCLOptimizationController(
        private val preferences: Preferences,
        private val metricsHelper: FCLMetrics,
        private val parameterHistory: ParameterHistoryManager
    ) {

        private val activeMeals = mutableMapOf<String, MealOptimizationSession>()
        private val optimizationQueue = mutableListOf<OptimizationTask>()
        private var lastOptimizationRun: DateTime? = null

        // ★★★ CONSTANTEN - GEEN COMPANION OBJECT ★★★
        private val MEAL_ANALYSIS_DELAY_HOURS = 4
        private val MIN_MEAL_DURATION_MINUTES = 90
        private val OPTIMIZATION_COOLDOWN_HOURS = 6

        // ★★★ HOOFDFUNCTIE - ELKE 5 MINUTEN AANROEPEN ★★★
        fun onNewBGReading(currentBG: Double, currentIOB: Double, context: FCL.FCLContext) {
            val now = DateTime.now()

            checkForMealDetection(context)
            updateActiveMeals(now, context)
            // scheduleOptimizations verwijderd - niet nodig
            executePendingOptimization()
        }

    private fun checkForMealDetection(context: FCL.FCLContext) {
        // ★★★ VERBETERDE MAALTIJD DETECTIE MET LAGERE DREMPELS ★★★
        val isRealMeal = context.mealDetected ||
            context.detectedCarbs > 8.0 || // ★★★ VERLAAGD: 10 → 8 ★★★
            hasRecentCarbInput() ||
            (context.currentBG > Target_Bg + 1.0 && context.currentIOB < 0.5) // ★★★ VERLAAGDE DREMPELS ★★★

        if (isRealMeal) {
            val mealId = "meal_${DateTime.now().millis}"
            val currentParams = parameterHistory.getCurrentParameterSnapshot()

            // ★★★ VERMINDER DUPLICAAT DETECTIE ★★★
            val recentSimilarMeal = activeMeals.values.any {
                Minutes.minutesBetween(it.startTime, DateTime.now()).minutes < 60 // ★★★ VERHOOGD: 30 → 60 ★★★
            }

            if (!recentSimilarMeal) {
                activeMeals[mealId] = MealOptimizationSession(
                    mealId = mealId,
                    startTime = DateTime.now(),
                    startBG = context.currentBG,
                    initialIOB = context.currentIOB,
                    detectedCarbs = context.detectedCarbs,
                    activeParameters = currentParams,
                    dataPoints = mutableListOf(),
                    optimizationScheduled = false
                )
            }
        }
    }

    // ★★★ NIEUWE FUNCTIE: Snelle BG stijging detectie ★★★
    private fun hasRapidBGrise(context: FCL.FCLContext): Boolean {
        val recentData = loadCSVData(2) // Laatste 2 uur
        if (recentData.size < 6) return false // Minimaal 6 datapunten (30 min)

        val recentBG = recentData.takeLast(6).map { it.currentBG }
        if (recentBG.size < 6) return false

        val firstThreeAvg = recentBG.take(3).average()
        val lastThreeAvg = recentBG.takeLast(3).average()

        return (lastThreeAvg - firstThreeAvg) > 1.2 // ★★★ Stijging > 1.2 mmol/L in 15 min ★★★
    }

    // ★★★ NIEUWE HELPER FUNCTIE TOEVOEGEN ★★★
    private fun hasRecentCarbInput(): Boolean {
        // Controleer op recente carb input in FCL (laatste 15 minuten)
        val recentCarbs = loadCSVData(1) // Laad laatste uur
            .any {
                it.detectedCarbs > 10.0 &&
                    it.timestamp.isAfter(DateTime.now().minusMinutes(15))
            }
        return recentCarbs
    }

        private fun updateActiveMeals(now: DateTime, context: FCL.FCLContext) {
            val currentParams = parameterHistory.getCurrentParameterSnapshot()
            val currentDataPoint = createOptimizationDataPoint(context, currentParams)

            activeMeals.values.forEach { session ->
                if (!session.optimizationScheduled) {
                    session.dataPoints.add(currentDataPoint)

                    if (isMealComplete(session, now, context)) {
                        scheduleMealOptimization(session, now)
                    }
                }
            }
        }

        private fun isMealComplete(session: MealOptimizationSession, now: DateTime, context: FCL.FCLContext): Boolean {
            val mealDuration = Minutes.minutesBetween(session.startTime, now).minutes

            if (mealDuration < MIN_MEAL_DURATION_MINUTES) return false

            return when {
                context.currentBG <= session.startBG + 1.0 && mealDuration > 120 -> true
                context.carbsOnBoard < session.detectedCarbs * 0.2 -> true
                hasQuietPeriod(session.dataPoints, 30) -> true
                mealDuration > 240 -> true
                else -> false
            }
        }

        private fun scheduleMealOptimization(session: MealOptimizationSession, now: DateTime) {
            val optimizationTime = now.plusHours(MEAL_ANALYSIS_DELAY_HOURS)

            optimizationQueue.add(OptimizationTask(
                mealSession = session,
                scheduledTime = optimizationTime,
                priority = calculateOptimizationPriority(session)
            ))

            session.optimizationScheduled = true
        }

        private fun calculateOptimizationPriority(session: MealOptimizationSession): Int {
            var priority = 50
            val metrics = calculateQuickMealMetrics(session.dataPoints)

            if (metrics.peakBG > 11.0) priority += 20
            if (metrics.postMealHypo) priority += 30
            if (metrics.timeToFirstBolus > 30) priority += 15
            if (metrics.rapidDeclineDetected) priority += 25

            return priority.coerceIn(1, 100)
        }

        private fun executePendingOptimization() {
            val now = DateTime.now()

            lastOptimizationRun?.let {
                if (Hours.hoursBetween(it, now).hours < OPTIMIZATION_COOLDOWN_HOURS) {
                    return
                }
            }

            val readyTask = optimizationQueue
                .filter { it.scheduledTime.isBefore(now) }
                .maxByOrNull { it.priority }

            readyTask?.let { task ->
                runOptimizationForMeal(task.mealSession)
                optimizationQueue.remove(task)
                lastOptimizationRun = now
            }
        }

    // ★★★ HAAL OPTIMALISATIE GEWICHT OP VOOR MAALTIJD ★★★
    private fun getOptimizationWeightForMeal(session: MealOptimizationSession): Double {
        return try {
            fclReference?.getOptimizationWeightForMeal(session.startTime) ?: 1.0
        } catch (e: Exception) {
            1.0 // Fallback naar vol gewicht bij fouten
        }
    }

/*    private fun isSameMeal(mealTime1: DateTime, mealTime2: DateTime): Boolean {
        val timeDiff = Minutes.minutesBetween(mealTime1, mealTime2).minutes
        return timeDiff < 10 // Binnen 10 minuten =zelfde maaltijd
    }   */

    private fun runOptimizationForMeal(session: MealOptimizationSession) {
        val optimizer = FCLSimplexOptimizer(parameterHistory)

        val optimizationWeight = getOptimizationWeightForMeal(session)

        val advice = optimizer.optimizeForSingleMeal(session.dataPoints, optimizationWeight)

        // ★★★ FALLBACK BIJ GEEN ADVIES ★★★
        val finalAdvice = if (advice.isEmpty()) {
            generateFallbackAdvice()
        } else {
            advice
        }

        integrateOptimizationAdvice(finalAdvice, session, optimizationWeight)
    }

    // ★★★ ALTERNATIEVE SIMPELE VERSIE ★★★
    private fun integrateOptimizationAdvice(advice: List<ParameterAdvice>, session: MealOptimizationSession, optimizationWeight: Double) {
        advice.forEach { newAdvice ->
            var adjustedConfidence = newAdvice.confidence * optimizationWeight

            // ★★★ MINIMUM CONFIDENCE GARANTIE ★★★
            adjustedConfidence = adjustedConfidence.coerceAtLeast(0.15)

            val finalConfidence = if (optimizationWeight < 0.5) {
                adjustedConfidence * 2.0
            } else {
                adjustedConfidence
            }.coerceAtMost(1.0)

            val adjustedAdvice = newAdvice.copy(
                confidence = finalConfidence,
                reason = if (optimizationWeight < 1.0) {
                    "${newAdvice.reason} (aangepast gewicht: ${(optimizationWeight * 100).toInt()}%)"
                } else {
                    newAdvice.reason
                }
            )

            // ★★★ VERLAAGDE DREMPEL: 0.1 → 0.05 ★★★
            if (adjustedAdvice.confidence >= 0.05) {
                updateParameterAdviceInBackground(adjustedAdvice)
            }
        }
    }


        // ★★★ HELPER FUNCTIES - TOEGEVOEGD ★★★
        private fun createOptimizationDataPoint(context: FCL.FCLContext, parameters: ParameterSnapshot): OptimizationDataPoint {
            return OptimizationDataPoint(
                timestamp = DateTime.now(),
                bg = context.currentBG,
                iob = context.currentIOB,
                carbsOnBoard = context.carbsOnBoard,
                insulinDelivered = context.lastBolusAmount,
                phase = context.currentPhase,
                activeParameters = parameters
            )
        }

        private fun calculateQuickMealMetrics(dataPoints: List<OptimizationDataPoint>): QuickMealMetrics {
            if (dataPoints.isEmpty()) return QuickMealMetrics()

            return QuickMealMetrics(
                peakBG = dataPoints.maxOf { it.bg },
                timeToFirstBolus = calculateTimeToFirstBolus(dataPoints),
                postMealHypo = dataPoints.any { it.bg < 3.9 },
                rapidDeclineDetected = detectRapidDecline(dataPoints)
            )
        }

        private fun hasQuietPeriod(dataPoints: List<OptimizationDataPoint>, minutes: Int): Boolean {
            if (dataPoints.size < 2) return false
            val recentPoints = dataPoints.takeLast(6) // 30 minuten bij 5-min data
            return recentPoints.all { it.insulinDelivered < 0.1 }
        }

        // ★★★ IMPLEMENTATIE VAN ONTBREKENDE FUNCTIES ★★★
        private fun calculateTimeToFirstBolus(dataPoints: List<OptimizationDataPoint>): Int {
            val firstBolus = dataPoints.firstOrNull { it.insulinDelivered > 0.1 }
            return firstBolus?.let {
                Minutes.minutesBetween(dataPoints.first().timestamp, it.timestamp).minutes
            } ?: 0
        }

        private fun detectRapidDecline(dataPoints: List<OptimizationDataPoint>): Boolean {
            if (dataPoints.size < 4) return false

            val recent = dataPoints.takeLast(4)
            val timeDiffHours = Hours.hoursBetween(recent.first().timestamp, recent.last().timestamp).hours.toDouble()
            if (timeDiffHours == 0.0) return false

            val bgDiff = recent.last().bg - recent.first().bg
            val declineRate = bgDiff / timeDiffHours
            return declineRate < -2.0
        }


    }

    // ★★★ SIMPLEX OPTIMALISATOR - NIEUWE INNER CLASS ★★★
    inner class FCLSimplexOptimizer(
        private val parameterHistory: ParameterHistoryManager
    ) {

        fun optimizeForSingleMeal(mealData: List<OptimizationDataPoint>, optimizationWeight: Double = 1.0): List<ParameterAdvice> {
            // ★★★ VERLAAGDE MINIMUM DATAPUNTEN: 5 → 3 ★★★
            if (mealData.size < 3) {
                return generateFallbackAdvice().map { advice ->
                    advice.copy(
                        confidence = advice.confidence * optimizationWeight,
                        reason = "${advice.reason} (beperkte data: ${mealData.size} punten)"
                    )
                }
            }

            val usedParameters = mealData.first().activeParameters
            val metrics = extractMealMetricsFromData(mealData)

            // ★★★ GENEREER ADVIES ZELFS BIJ WEINIG DATA ★★★
            val baseAdvice = generateSimpleAdvice(usedParameters, metrics)

            // ★★★ PAS CONFIDENCE AAN OP BASIS VAN GEWICHT ★★★
            return baseAdvice.map { advice ->
                val adjustedConfidence = (advice.confidence * optimizationWeight).coerceAtLeast(0.1)
                advice.copy(confidence = adjustedConfidence)
            }
        }

        private fun extractMealMetricsFromData(mealData: List<OptimizationDataPoint>): QuickMealMetrics {
            if (mealData.isEmpty()) return QuickMealMetrics()

            // ★★★ VERMINDER FILTERING - ACCEPTEER MEER DATA ★★★
            val validData = mealData.filter {
                it.bg in 2.0..25.0 // ★★★ VERBREDE ACCEPTABILE BEREIK ★★★
            }

            return QuickMealMetrics(
                peakBG = validData.maxOfOrNull { it.bg } ?: 0.0,
                timeToFirstBolus = calculateTimeToFirstBolus(validData),
                postMealHypo = validData.any { it.bg < 3.9 },
                rapidDeclineDetected = detectRapidDecline(validData)
            )
        }

        private fun generateSimpleAdvice(usedParameters: ParameterSnapshot, metrics: QuickMealMetrics): List<ParameterAdvice> {
            val advice = mutableListOf<ParameterAdvice>()

            // ★★★ VERBETERDE CONFIDENCE BEREKENING - MINDER STRENG ★★★
            fun calculateAdjustedConfidence(baseConfidence: Double): Double {
                // ★★★ ACCEPTEER OOK DATA MET WEINIG PUNTEN ★★★
                return baseConfidence.coerceAtLeast(0.3) // ★★★ MINIMUM CONFIDENCE ★★★
            }

            // ★★★ VERLAAGDE PIEK DREMPELS VOOR BETERE DETECTIE ★★★
            if (metrics.peakBG > 10.0) { // ★★★ VERLAAGD: 15.0 → 10.0 ★★★
                val confidence = calculateAdjustedConfidence(calculatePeakConfidence(metrics.peakBG))

                advice.add(
                    ParameterAdvice(
                        parameterName = "bolus_perc_rising",
                        currentValue = usedParameters.bolusPercRising,
                        recommendedValue = (usedParameters.bolusPercRising * 1.12).coerceAtMost(170.0),
                        reason = "Piek gedetecteerd (${round(metrics.peakBG, 1)} mmol/L)",
                        confidence = confidence,
                        direction = "INCREASE"
                    )
                )

                // ★★★ EXTRA: Meal detection sensitivity bij pieken ★★★
                if (metrics.peakBG > 11.0) {
                    advice.add(
                        ParameterAdvice(
                            parameterName = "meal_detection_sensitivity",
                            currentValue = usedParameters.mealDetectionSensitivity,
                            recommendedValue = (usedParameters.mealDetectionSensitivity * 0.85).coerceAtLeast(0.05),
                            reason = "Hoge piek suggereert late detectie",
                            confidence = confidence * 0.8,
                            direction = "DECREASE"
                        )
                    )
                }
            }

            // ★★★ NIEUW: Respons tijd optimalisatie ★★★
            if (metrics.timeToFirstBolus > 20) {
                advice.add(
                    ParameterAdvice(
                        parameterName = "phase_rising_slope",
                        currentValue = usedParameters.phaseRisingSlope,
                        recommendedValue = (usedParameters.phaseRisingSlope * 0.9).coerceAtLeast(0.2),
                        reason = "Vertraagde bolus (${metrics.timeToFirstBolus} min) - verlaag detectie drempel",
                        confidence = 0.6,
                        direction = "DECREASE"
                    )
                )
            }

            // ★★★ VEILIGHEID OPTIMALISATIE ★★★
            if (metrics.postMealHypo || metrics.rapidDeclineDetected) {
                advice.add(
                    ParameterAdvice(
                        parameterName = "hypo_risk_percentage",
                        currentValue = usedParameters.hypoRiskPercentage,
                        recommendedValue = (usedParameters.hypoRiskPercentage * 1.15).coerceAtMost(35.0),
                        reason = if (metrics.postMealHypo) "Post-maaltijd hypo" else "Snelle daling gedetecteerd",
                        confidence = 0.7,
                        direction = "INCREASE"
                    )
                )
            }

            return advice.take(5) // ★★★ BEPERK TOT 5 ADVIEZEN ★★★
        }



        // ★★★ NIEUWE FUNCTIE: Extreme piek advies ★★★
        private fun generateExtremePeakAdvice(usedParameters: ParameterSnapshot, metrics: QuickMealMetrics): List<ParameterAdvice> {
            val extremeAdvice = mutableListOf<ParameterAdvice>()

            val extremeConfidence = 0.9

            extremeAdvice.add(
                ParameterAdvice(
                    parameterName = "bolus_perc_rising",
                    currentValue = usedParameters.bolusPercRising,
                    recommendedValue = (usedParameters.bolusPercRising * 1.3).coerceAtMost(200.0),
                    reason = "EXTREME PIEK (${round(metrics.peakBG, 1)} mmol/L) - significante verhoging nodig",
                    confidence = extremeConfidence,
                    direction = "INCREASE"
                )
            )

            extremeAdvice.add(
                ParameterAdvice(
                    parameterName = "meal_detection_sensitivity",
                    currentValue = usedParameters.mealDetectionSensitivity,
                    recommendedValue = (usedParameters.mealDetectionSensitivity * 0.7).coerceAtLeast(0.05),
                    reason = "EXTREME PIEK suggereert te late detectie",
                    confidence = extremeConfidence,
                    direction = "DECREASE"
                )
            )

            extremeAdvice.add(
                ParameterAdvice(
                    parameterName = "phase_rising_slope",
                    currentValue = usedParameters.phaseRisingSlope,
                    recommendedValue = (usedParameters.phaseRisingSlope * 0.8).coerceAtLeast(0.3),
                    reason = "EXTREME PIEK - lagere detectie drempel nodig",
                    confidence = extremeConfidence,
                    direction = "DECREASE"
                )
            )

            return extremeAdvice
        }

        // ★★★ NIEUWE FUNCTIE: Confidence berekening voor pieken ★★★
        private fun calculatePeakConfidence(peakBG: Double): Double {
            return when {
                peakBG > 13.0 -> 0.9
                peakBG > 11.0 -> 0.8
                peakBG > 9.0 -> 0.7
                peakBG > 7.0 -> 0.6
                else -> 0.5
            }
        }

        // ★★★ HELPER FUNCTIES ★★★
        private fun calculateTimeToFirstBolus(dataPoints: List<OptimizationDataPoint>): Int {
            val firstBolus = dataPoints.firstOrNull { it.insulinDelivered > 0.1 }
            return firstBolus?.let {
                Minutes.minutesBetween(dataPoints.first().timestamp, it.timestamp).minutes
            } ?: 0
        }

        private fun detectRapidDecline(dataPoints: List<OptimizationDataPoint>): Boolean {
            if (dataPoints.size < 4) return false

            val recent = dataPoints.takeLast(4)
            val timeDiffHours = Hours.hoursBetween(recent.first().timestamp, recent.last().timestamp).hours.toDouble()
            if (timeDiffHours == 0.0) return false

            val bgDiff = recent.last().bg - recent.first().bg
            val declineRate = bgDiff / timeDiffHours
            return declineRate < -2.0
        }

        private fun round(value: Double, digits: Int = 1): Double {
            val factor = Math.pow(10.0, digits.toDouble())
            return Math.round(value * factor) / factor
        }


    }

    private fun generateFallbackAdvice(): List<ParameterAdvice> {
        val currentParams = parameterHistory.getCurrentParameterSnapshot()
        val advice = mutableListOf<ParameterAdvice>()

        // ★★★ MEER DIVERSE FALLBACK ADVIEZEN ★★★
        advice.add(
            ParameterAdvice(
                parameterName = "bolus_perc_rising",
                currentValue = currentParams.bolusPercRising,
                recommendedValue = (currentParams.bolusPercRising * 1.08).coerceAtMost(160.0),
                reason = "TEST optimalisatie advies - wacht op maaltijd data",
                confidence = 0.7,  // ★★★ VERHOOGDE CONFIDENCE ★★★
                direction = "INCREASE"
            )
        )

        advice.add(
            ParameterAdvice(
                parameterName = "meal_detection_sensitivity",
                currentValue = currentParams.mealDetectionSensitivity,
                recommendedValue = (currentParams.mealDetectionSensitivity * 0.92).coerceAtLeast(0.08), // ★★★ MINDER STRENG ★★★
                reason = "TEST Optimalisatie loop - verfijning detectie gevoeligheid",
                confidence = 0.6,
                direction = "DECREASE"
            )
        )

        // ★★★ NIEUW: Plateau fase optimalisatie ★★★
        advice.add(
            ParameterAdvice(
                parameterName = "bolus_perc_plateau",
                currentValue = currentParams.bolusPercPlateau,
                recommendedValue = (currentParams.bolusPercPlateau * 1.05).coerceAtMost(140.0),
                reason = "TEST plateau fase optimalisatie",
                confidence = 0.5,
                direction = "INCREASE"
            )
        )

        return advice
    }


    fun setTargetBg(value: Double) { Target_Bg = value }


    fun resetDataQualityCache() {
        cachedDataQuality = null
    }

    // ★★★ UPDATE PARAMETER ADVIES - DIRECTE UI INTEGRATIE ★★★

    fun updateParameterAdviceInBackground(advice: ParameterAdvice) {
        try {
            // ★★★ VERHOOG CONFIDENCE VOOR TESTDOELEINDEN ★★★
            val enhancedAdvice = if (advice.confidence < 0.3) {
                advice.copy(confidence = 0.3) // Minimum confidence voor testing
            } else {
                advice
            }

            val agressivenessAdvice = ParameterAgressivenessAdvice(
                parameterName = enhancedAdvice.parameterName,
                currentValue = enhancedAdvice.currentValue,
                recommendedValue = enhancedAdvice.recommendedValue,
                reason = enhancedAdvice.reason,
                confidence = enhancedAdvice.confidence,
                expectedImprovement = "Simplex optimalisatie",
                changeDirection = enhancedAdvice.direction,
                timestamp = DateTime.now()
            )

            updateParameterHistoryWithSimplexAdvice(agressivenessAdvice)

            val history = parameterHistories[advice.parameterName]
            val weightedAverage = history?.let {
                calculateWeightedAverageFromHistory(it.adviceHistory)
            } ?: enhancedAdvice.recommendedValue

            cachedParameterSummaries[enhancedAdvice.parameterName] = ParameterAdviceSummary(
                parameterName = enhancedAdvice.parameterName,
                currentValue = enhancedAdvice.currentValue,
                lastAdvice = agressivenessAdvice,
                weightedAverage = weightedAverage,
                confidence = enhancedAdvice.confidence,
                trend = determineTrendFromHistory(history?.adviceHistory ?: emptyList()),
                manuallyAdjusted = false
            )

            notifyAdviceUpdated()

        } catch (e: Exception) {

        }
    }

    // ★★★ PLAATS DEZE FUNCTIE IN FCLMetrics.kt ★★★
    private fun notifyAdviceUpdated() {
        // ★★★ TRIGGER UI UPDATE DOOR CACHE TE VERNIEUWEN ★★★
        cachedParameterSummaries.clear()
        getParameterAdviceSummary() // Forceer herberekening

        // ★★★ LOG UPDATE ★★★
       // loggingHelper.logDebug("Parameter adviezen bijgewerkt - ${cachedParameterSummaries.size} parameters")
    }

    fun onFiveMinuteTick(currentBG: Double, currentIOB: Double, context: FCL.FCLContext) {
        optimizationController.onNewBGReading(currentBG, currentIOB, context)
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


    // ★★★ VERBETERDE TIMING LOGICA ★★★
    fun shouldCalculateNewAdvice(): Boolean {
        val lastAdviceTime = getLastAdviceTime()
        val hoursSinceLastAdvice = lastAdviceTime?.let {
            Hours.hoursBetween(it, DateTime.now()).hours
        } ?: 24 // Geen advies = altijd updaten

        val adviceInterval = try {
            preferences.get(IntKey.Advice_Interval_Hours)
        } catch (e: Exception) {
            1 // Korter interval voor testing
        }

        // ★★★ ALTIJD ADVIES GENEREREN BIJ GEEN ADVIES OF NIEUWE DATA ★★★
        val hasNoAdvice = parameterAdviceHistory.isEmpty() &&
            cachedConsolidatedAdvice == null &&
            loadConsolidatedAdvice() == null

        // ★★★ FORCEER UPDATE BIJ NIEUWE MAALTIJD DATA ★★★
        val mealMetrics = calculateMealPerformanceMetrics(168)
        val recentMeals = mealMetrics.filter {
            it.mealStartTime.isAfter(DateTime.now().minusDays(2))
        }
        val hasNewMeals = recentMeals.isNotEmpty() &&
            lastAdviceTime?.let { lastTime ->
                recentMeals.any { it.mealStartTime.isAfter(lastTime) }
            } ?: true

        return hasNoAdvice || hasNewMeals || hoursSinceLastAdvice >= adviceInterval
    }



    fun setLastAdviceTime() {
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

    // ★★★ VERVANG DEZE in FCLMetrics.kt (rond regel 1550) ★★★
    private fun ensureAdviceAvailable() {
        if (cachedConsolidatedAdvice == null && loadConsolidatedAdvice() == null) {
            // ★★★ ACTIEVE OPTIMALISATIE i.p.v. standaard advies ★★★
            try {
                // Genereer echte adviezen zonder FCLParameters dependency
                val metrics24h = calculateMetrics(24)
                val metrics7d = calculateMetrics(168)
                val mealMetrics = calculateMealPerformanceMetrics(168)

                val newAdvice = getConsolidatedAdviceWithoutParameters(metrics24h, metrics7d, mealMetrics)
                cacheConsolidatedAdvice(newAdvice)

                // ★★★ ACTIVEER PARAMETER ADVIES SYSTEEM ★★★
                initializeParameterAdviceSystem()

            } catch (e: Exception) {
                // Fallback naar standaard advies bij fouten
                cachedConsolidatedAdvice = createDefaultAdvice()
                storeConsolidatedAdvice(cachedConsolidatedAdvice!!)
            }
        }
    }

    // ★★★ TOEVOEGEN in FCLMetrics.kt ★★★
    private fun getConsolidatedAdviceWithoutParameters(
        metrics24h: GlucoseMetrics,
        metrics7d: GlucoseMetrics,
        mealMetrics: List<MealPerformanceMetrics>
    ): ConsolidatedAdvice {

        // ★★★ UPDATE TIMESTAMP ★★★
        setLastAdviceTime()

        // ★★★ HAAL SIMPLEX ADVIEZEN OP ★★★
        val summaries = getParameterAdviceSummary()
        val validSummaries = summaries.filter { it.confidence > 0.3 }

        val parameterAdjustments = validSummaries
            .mapNotNull { it.lastAdvice }
            .sortedByDescending { it.confidence }

        return if (parameterAdjustments.isNotEmpty()) {
            val primary = parameterAdjustments.first()
            ConsolidatedAdvice(
                primaryAdvice = "Simplex optimalisatie: ${parameterAdjustments.size} parameters",
                parameterAdjustments = parameterAdjustments,
                confidence = parameterAdjustments.map { it.confidence }.average().coerceIn(0.0, 1.0),
                reasoning = "Gebaseerd op simplex analyse van ${mealMetrics.size} maaltijden",
                expectedImprovement = "TIR >90%, pieken <11.0 mmol/L"
            )
        } else {
            // ★★★ GENEREER FALLBACK ADVIEZEN ★★★
            val fallbackAdvice = generateFallbackAdvice()
            val agressivenessAdviceList = fallbackAdvice.map { advice ->
                ParameterAgressivenessAdvice(
                    parameterName = advice.parameterName,
                    currentValue = advice.currentValue,
                    recommendedValue = advice.recommendedValue,
                    reason = advice.reason,
                    confidence = advice.confidence,
                    expectedImprovement = "Eerste optimalisatie",
                    changeDirection = advice.direction
                )
            }

            ConsolidatedAdvice(
                primaryAdvice = "Eerste simplex optimalisatie gestart",
                parameterAdjustments = agressivenessAdviceList,
                confidence = 0.4,
                reasoning = "Initialisatie optimalisatie systeem - wacht op maaltijd data",
                expectedImprovement = "Automatische parameter optimalisatie"
            )
        }
    }

    // ★★★ TOEVOEGEN in FCLMetrics.kt ★★★
    private fun initializeParameterAdviceSystem() {
        try {
            // ★★★ ACTIVEER FALLBACK ADVIEZEN VOOR TESTING ★★★
            val fallbackAdvice = generateFallbackAdvice()

            fallbackAdvice.forEach { advice ->
                if (advice.confidence > 0.1) {
                    updateParameterAdviceInBackground(advice)
                }
            }

            // ★★★ FORCEER CACHE UPDATE ★★★
            cachedParameterSummaries.clear()
            getParameterAdviceSummary() // Forceer herberekening

            // ★★★ LOG INITIALISATIE ★★★
            // Gebruik je bestaande logging mechanisme hier
            // bijv: aapsLogger.debug("FCL Optimalisatie systeem geïnitialiseerd")

        } catch (e: Exception) {
            // Gebruik je bestaande logging mechanisme hier
            // bijv: aapsLogger.error("Fout bij initialisatie optimalisatie systeem", e)
        }
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
                // ★★★ PARAMETER GEWIJZIGD - LOG EN RESET ★★★
                println("Parameter wijziging gedetecteerd: $name van $lastValue naar $currentValue")

                // ★★★ RESET ALLEEN ADVIES VOOR DEZE PARAMETER ★★★
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
                                "Parameter $name aangepast - nieuwe analyse nodig"
                            } else {
                                currentAdvice.reasoning
                            }
                        )
                        cacheConsolidatedAdvice(updatedAdvice)
                    }
                }

                // ★★★ UPDATE TIMESTAMP VOOR HANDMATIGE AANPASSING ★★★
                parameterAdjustmentTimestamps[name] = DateTime.now()

                // ★★★ CLEAR CACHE VOOR DEZE PARAMETER ★★★
                cachedParameterSummaries.remove(name)

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


    fun getParameterAdviceSummary(): List<ParameterAdviceSummary> {
        return getCachedParameterSummary()
    }


    private fun getCachedParameterSummary(): List<ParameterAdviceSummary> {
        // ★★★ DETECTEER PARAMETER WIJZIGINGEN VOOR WE SAMENVATTING MAKEN ★★★
        detectParameterChanges()

        if (cachedParameterSummaries.isEmpty() || shouldCalculateNewAdvice()) {
            val essentialParameters = listOf(
                // ★★★ NIEUWE 2-FASE PARAMETERS ★★★
                "bolus_perc_rising", "bolus_perc_plateau",
                "phase_rising_slope", "phase_plateau_slope",
                // ★★★ OVERIGE PARAMETERS ★★★
                "bolus_perc_day", "bolus_perc_night",
                "meal_detection_sensitivity",
                "carb_percentage", "peak_damping_percentage",
                "hypo_risk_percentage", "IOB_corr_perc"
            )

            // ★★★ CORRECTIE: Gebruik parameterAdviceHistory i.p.v. cachedParameterSummaries ★★★
            val currentAdvice: List<ParameterAgressivenessAdvice> =
                if (parameterAdviceHistory.isNotEmpty()) {
                    parameterAdviceHistory.values
                        .filter { it.timestamp.isAfter(DateTime.now().minusDays(7)) }
                        .toList()
                } else {
                    getStoredAdvice()
                        .filter { it.timestamp.isAfter(DateTime.now().minusDays(7)) }
                }

            cachedParameterSummaries = essentialParameters.map { paramName ->
                paramName to calculateParameterSummary(paramName, currentAdvice)
            }.toMap().toMutableMap()
        }
        return cachedParameterSummaries.values.toList()
    }



    // ★★★ VERVANG DEZE FUNCTIE IN FCLMetrics.kt (rond regel 1470) ★★★
    private fun calculateParameterSummary(
        parameterName: String,
        currentAdvice: List<ParameterAgressivenessAdvice>
    ): ParameterAdviceSummary {
        val currentValue = getCurrentParameterValueFromPrefs(parameterName)

        // ★★★ VERLAAGDE CONFIDENCE DREMPEL: 0.1 → 0.05 ★★★
        val parameterAdvice = currentAdvice.find { it.parameterName == parameterName }

        val history = parameterHistories.getOrPut(parameterName) { EnhancedParameterHistory(parameterName) }

        val weightedAverage = if (history.adviceHistory.isNotEmpty()) {
            calculateWeightedAverageFromHistory(history.adviceHistory)
        } else {
            parameterAdvice?.recommendedValue ?: currentValue
        }

        val trend = determineTrendFromHistory(history.adviceHistory)

        return ParameterAdviceSummary(
            parameterName = parameterName,
            currentValue = currentValue,
            // ★★★ NIEUWE DREMPEL: 0.05 ★★★
            lastAdvice = if (parameterAdvice != null && parameterAdvice.confidence > 0.01) parameterAdvice else null,
            weightedAverage = weightedAverage,
            confidence = parameterAdvice?.confidence ?: 0.0,
            trend = trend,
            manuallyAdjusted = isParameterManuallyAdjusted(parameterName),
            lastManualAdjustment = parameterAdjustmentTimestamps[parameterName]
        )
    }

    // ★★★ VERBETERDE GEWOGEN GEMIDDELDE BEREKENING ★★★

    private fun calculateWeightedAverageFromHistory(adviceHistory: List<HistoricalAdvice>): Double {
        if (adviceHistory.isEmpty()) return 0.0

        val sortedHistory = adviceHistory.sortedBy { it.timestamp }
        val now = DateTime.now()

        var totalWeight = 0.0
        var weightedSum = 0.0

        sortedHistory.forEachIndexed { index, advice ->
            // Gewicht op basis van:
            // 1. Recency (nieuwer = zwaarder gewicht)
            val daysAgo = Days.daysBetween(advice.timestamp, now).days
            val recencyWeight = exp(-daysAgo / 7.0) // Halvering na 7 dagen

            // 2. Confidence van het originele advies
            val confidenceWeight = advice.confidence

            // 3. Positie in lijst (recentere krijgen extra gewicht)
            val positionWeight = (index + 1) / sortedHistory.size.toDouble()

            val combinedWeight = recencyWeight * confidenceWeight * (0.7 + 0.3 * positionWeight)

            weightedSum += advice.recommendedValue * combinedWeight
            totalWeight += combinedWeight
        }

        return if (totalWeight > 0) weightedSum / totalWeight else 0.0
    }

    // ★★★ HAAL PARAMETER WAARDEN OP ZONDER FCLPARAMETERS ★★★
    private fun getCurrentParameterValue(parameterName: String): Double {
        return try {
            when (parameterName) {
                 "bolus_perc_day" -> preferences.get(IntKey.bolus_perc_day).toDouble()
                "bolus_perc_night" -> preferences.get(IntKey.bolus_perc_night).toDouble()
                "meal_detection_sensitivity" -> preferences.get(DoubleKey.meal_detection_sensitivity)
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


    // 2. Voeg deze public functie toe voor UI toegang:
    fun getLastAdviceTime(): DateTime? {
        val lastTimeMillis = prefs.getLong("last_advice_time", 0)
        return if (lastTimeMillis > 0) DateTime(lastTimeMillis) else null
    }

    // 3. Voeg deze helper functie toe:
    private fun getAllCurrentParameterValues(): Map<String, Double> {
        return mapOf(
            // ★★★ NIEUWE 2-FASE PARAMETERS ★★★
            "bolus_perc_rising" to getCurrentParameterValueFromPrefs("bolus_perc_rising"),
            "bolus_perc_plateau" to getCurrentParameterValueFromPrefs("bolus_perc_plateau"),
            "phase_rising_slope" to getCurrentParameterValueFromPrefs("phase_rising_slope"),
            "phase_plateau_slope" to getCurrentParameterValueFromPrefs("phase_plateau_slope"),
            // ★★★ OVERIGE PARAMETERS ★★★
            "bolus_perc_day" to getCurrentParameterValueFromPrefs("bolus_perc_day"),
            "bolus_perc_night" to getCurrentParameterValueFromPrefs("bolus_perc_night"),
            "meal_detection_sensitivity" to getCurrentParameterValueFromPrefs("meal_detection_sensitivity"),
            "carb_percentage" to getCurrentParameterValueFromPrefs("carb_percentage"),
            "peak_damping_percentage" to getCurrentParameterValueFromPrefs("peak_damping_percentage"),
            "hypo_risk_percentage" to getCurrentParameterValueFromPrefs("hypo_risk_percentage"),
            "IOB_corr_perc" to getCurrentParameterValueFromPrefs("IOB_corr_perc")
        )
    }

    private fun updateParameterHistoryWithSimplexAdvice(advice: ParameterAgressivenessAdvice) {
        val history = parameterHistories.getOrPut(advice.parameterName) {
            EnhancedParameterHistory(advice.parameterName)
        }

        // Filter dubbele adviezen binnen korte tijd
        val recentSimilar = history.adviceHistory.any {
            it.timestamp.isAfter(DateTime.now().minusHours(2)) &&
                abs(it.recommendedValue - advice.recommendedValue) < 0.1
        }

        if (!recentSimilar) {
            history.adviceHistory.add(HistoricalAdvice(
                timestamp = advice.timestamp,
                recommendedValue = advice.recommendedValue,
                changeDirection = advice.changeDirection,
                confidence = advice.confidence,
                reason = advice.reason
            ))

            // Beperk tot laatste 20 adviezen
            if (history.adviceHistory.size > 20) {
                history.adviceHistory.removeAt(0)
            }
            saveParameterHistories()
        }
    }

    // ★★★ VERVANG DEZE FUNCTIE - SIMPLEX ONLY ★★★
/*    fun getConsolidatedAdvice(
        parameters: FCLParameters,
        metrics24h: GlucoseMetrics,
        metrics7d: GlucoseMetrics,
        mealMetrics: List<MealPerformanceMetrics>
    ): ConsolidatedAdvice {

        // ★★★ UPDATE TIMESTAMP ★★★
        setLastAdviceTime()

        // ★★★ HAAL SIMPLEX ADVIEZEN OP ★★★
        val summaries = getParameterAdviceSummary()
        val validSummaries = summaries.filter { it.confidence > 0.3 }

        val parameterAdjustments = validSummaries
            .mapNotNull { it.lastAdvice }
            .sortedByDescending { it.confidence }

        return if (parameterAdjustments.isNotEmpty()) {
            val primary = parameterAdjustments.first()
            ConsolidatedAdvice(
                primaryAdvice = "Simplex optimalisatie: ${parameterAdjustments.size} parameters",
                parameterAdjustments = parameterAdjustments,
                confidence = parameterAdjustments.map { it.confidence }.average().coerceIn(0.0, 1.0),
                reasoning = "Gebaseerd op simplex analyse van ${mealMetrics.size} maaltijden",
                expectedImprovement = "TIR >90%, pieken <11.0 mmol/L"
            )
        } else {
            ConsolidatedAdvice(
                primaryAdvice = "Wacht op eerste simplex analyse",
                parameterAdjustments = emptyList(),
                confidence = 0.0,
                reasoning = "Simplex optimalisatie loopt - eerste advies volgt na maaltijd data",
                expectedImprovement = "Automatische parameter optimalisatie"
            )
        }
    }    */


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

    // ★★★ VERBETERDE PARAMETER GRENZEN ★★★
    private fun getParameterBounds(parameterName: String): Pair<Double, Double> {
        return when (parameterName) {
            // ★★★ NIEUWE 2-FASE PARAMETERS ★★★
            "bolus_perc_rising", "bolus_perc_plateau" -> Pair(10.0, 200.0)
            "phase_rising_slope", "phase_plateau_slope" -> Pair(0.3, 2.5)
            // ★★★ OVERIGE PARAMETERS ★★★
            "bolus_perc_day" -> Pair(10.0, 200.0)
            "bolus_perc_night" -> Pair(5.0, 80.0)
            "meal_detection_sensitivity" -> Pair(0.1, 0.5)
            "carb_percentage" -> Pair(10.0, 200.0)
            "peak_damping_percentage" -> Pair(10.0, 100.0)
            "hypo_risk_percentage" -> Pair(5.0, 50.0)
            "IOB_corr_perc" -> Pair(50.0, 150.0)
            else -> Pair(0.0, 100.0)
        }
    }



    // ★★★ VEILIGERE PARAMETER ACCESS ★★★
    // ★★★ FUNCTIE 1: Voor directe preference access (zonder FCLParameters) ★★★
    private fun getCurrentParameterValueFromPrefs(parameterName: String): Double {
        return try {
            when (parameterName) {
                // ★★★ NIEUWE 2-FASE PARAMETERS ★★★
                "bolus_perc_rising" -> preferences.get(IntKey.bolus_perc_rising).toDouble()
                "bolus_perc_plateau" -> preferences.get(IntKey.bolus_perc_plateau).toDouble()
                "phase_rising_slope" -> preferences.get(DoubleKey.phase_rising_slope)
                "phase_plateau_slope" -> preferences.get(DoubleKey.phase_plateau_slope)
                // ★★★ OVERIGE PARAMETERS ★★★
                "bolus_perc_day" -> preferences.get(IntKey.bolus_perc_day).toDouble()
                "bolus_perc_night" -> preferences.get(IntKey.bolus_perc_night).toDouble()
                "meal_detection_sensitivity" -> preferences.get(DoubleKey.meal_detection_sensitivity)
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

    // ★★★ DEBUG FUNCTIE ★★★
    fun debugAdviceGeneration(): String {
        val recentMeals = calculateMealPerformanceMetrics(168)
        val activeSessions = (optimizationController::class.java.getDeclaredField("activeMeals").apply { isAccessible = true }.get(optimizationController) as Map<*, *>).size
        val pendingOptimizations = (optimizationController::class.java.getDeclaredField("optimizationQueue").apply { isAccessible = true }.get(optimizationController) as List<*>).size

        return buildString {
            append("=== FCL OPTIMIZATION DEBUG ===\n")
            append("• Recente maaltijden (7d): ${recentMeals.size}\n")
            append("• Actieve sessions: $activeSessions\n")
            append("• Pending optimizations: $pendingOptimizations\n")
            append("• Parameter advies history: ${parameterAdviceHistory.size}\n")
            append("• Cached summaries: ${cachedParameterSummaries.size}\n")

            // Toon laatste 3 adviezen
            val recentAdvice = parameterAdviceHistory.values
                .sortedByDescending { it.timestamp }
                .take(3)

            append("• Laatste adviezen:\n")
            recentAdvice.forEach { advice ->
                append("  - ${advice.parameterName}: ${advice.recommendedValue} (conf: ${advice.confidence})\n")
            }
        }
    }



}