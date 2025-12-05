package app.aaps.plugins.aps.openAPSFCL

import android.R
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
import android.os.Handler
import android.os.Looper
import java.util.Locale


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

// === Suggestie object voor UI (toegevoegd) ===
data class ParameterSuggestion(
    val parameterName: String,
    val currentValue: Double,     // in dezelfde units als prefs (percent of fraction)
    val suggestedValue: Double,   // in dezelfde units als prefs
    val reason: String,
    val direction: String,
    val confidence: Double,
    val timestamp: DateTime = DateTime.now()
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
        var lastManualReset: DateTime? = null,
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
        private var cachedParameterSuggestions: MutableMap<String, ParameterSuggestion> = mutableMapOf()

        // ★★★ NIEUWE CONSTANTEN VOOR MAALTIJD OPTIMALISATIE ★★★
        private const val OPTIMIZATION_DAY_START = 6
        private const val OPTIMIZATION_DAY_END = 23
        private const val MIN_MEALS_FOR_OPTIMIZATION = 3
        private const val OPTIMIZATION_COOLDOWN_HOURS = 4
        private const val OPTIMIZATION_MEALS_COUNT = 5
        private const val OPTIMIZATION_RECENT_MEALS_HOURS = 24
        private const val RECENT_MEALS_FOR_COMPLETED_HOURS = 4

        // ★★★ MAXIMUM PARAMETER CHANGE ★★★
        private const val MAX_PARAMETER_CHANGE = 0.15
        private const val MAX_PERCENTAGE_CHANGE = 0.15  // 15% voor percentage parameters
        private const val MAX_SLOPE_CHANGE = 0.25



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

    private var lastLogTime: DateTime? = null
    private val logLock = Any() // Lock om dubbele logging te voorkomen
    private var lastLoggedValues: Map<String, Double> = emptyMap() // Om wijzigingen te detecteren

    init {
        safeInitialize()
    }

    private fun safeInitialize() {
        try {
            // ★★★ INITIALISEER CACHES EERST ★★★
            cachedParameterSummaries.clear()
            cachedParameterSuggestions.clear()

            // ★★★ LAAD DATA ★★★
            lastParameterAdjustments = loadParameterAdjustments().toMutableList()
            loadMealParameterAdviceHistory()
            loadParameterAdjustmentTimestamps()
            loadAutoUpdateConfig()
            loadAutomaticAdjustmentsHistory()
            loadParameterHistories()

            parameterAdviceHistory = loadParameterAdviceHistory()
            cachedConsolidatedAdvice = loadConsolidatedAdvice()

            // ★★★ INITIALISEER PARAMETER CACHE ★★★
            getAllCurrentParameterValues().forEach { (name, value) ->
                lastParameterValues[name] = value
            }
            parameterCacheInitialized = true // ★★★ CRUCIAAL: ZET DEZE OP TRUE ★★★

            // ★★★ VEILIGE ADVICE INITIALISATIE ★★★
            ensureAdviceAvailableSafe()
            // ★★★ RESET OUDE HANDMATIGE BLOKKADES BIJ OPSTART ★★★
            resetManualAdjustmentBlocks()

        } catch (e: Exception) {
            // ★★★ CRASH PREVENTIE ★★★
            parameterCacheInitialized = false
            cachedParameterSummaries.clear()
            android.util.Log.e("FCLMetrics", "Error in safeInitialize: ${e.message}")
        }
    }

    // ★★★ VEILIGE VERSIE VAN ensureAdviceAvailable ★★★
    private fun ensureAdviceAvailableSafe() {
        if (cachedConsolidatedAdvice == null && loadConsolidatedAdvice() == null) {
            try {
                // ★★★ ALLEEN INITIALISATIE - GEEN ZWARE BEREKENINGEN ★★★
                initializeParameterAdviceSystemSafe()
            } catch (e: Exception) {
                // Fallback naar standaard advies
                cachedConsolidatedAdvice = createDefaultAdvice()
                storeConsolidatedAdvice(cachedConsolidatedAdvice!!)
            }
        }
    }

    // ★★★ VEILIGE INITIALISATIE ★★★
    private fun initializeParameterAdviceSystemSafe() {
        try {
            // ★★★ ALLEEN CACHE CLEAREN - GEEN OPTIMALISATIE TIJDENS INIT ★★★
            cachedParameterSummaries.clear()

            // ★★★ OPTIMALISATIE WORDT LATER GEDAAN VIA onFiveMinuteTick ★★★

        } catch (e: Exception) {
            android.util.Log.e("FCLMetrics", "Error in initializeParameterAdviceSystemSafe: ${e.message}")
        }
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

        val activeMeals = mutableMapOf<String, MealOptimizationSession>()
        private val optimizationQueue = mutableListOf<OptimizationTask>()
        private var lastOptimizationRun: DateTime? = null

        // ★★★ CONSTANTEN - GEEN COMPANION OBJECT ★★★
        private val MEAL_ANALYSIS_DELAY_HOURS = 4
        private val MIN_MEAL_DURATION_MINUTES = 90
        private val OPTIMIZATION_COOLDOWN_HOURS = 1

        // ★★★ HOOFDFUNCTIE - ELKE 5 MINUTEN AANROEPEN ★★★
        fun onNewBGReading(currentBG: Double, currentIOB: Double, context: FCL.FCLContext) {
            val now = DateTime.now()

            checkForMealDetection(context)
            updateActiveMeals(now, context)
            // scheduleOptimizations verwijderd - niet nodig
            executePendingOptimization()
        }

    private fun checkForMealDetection(context: FCL.FCLContext) {
        // ★★★ VERBETERDE MAALTIJD DETECTIE MET MEER SIGNALEN ★★★
        val isRealMeal = context.mealDetected ||
            context.detectedCarbs > 8.0 ||
            hasRecentCarbInput() ||
            (context.currentBG > Target_Bg + 1.5 && context.currentIOB < 1.0) ||
            hasRapidBGrise(context)

        if (isRealMeal) {
            val mealId = "meal_${DateTime.now().millis}"
            val currentParams = parameterHistory.getCurrentParameterSnapshot()

            // ★★★ VERMINDER DUPLICAAT DETECTIE ★★★
            val recentSimilarMeal = activeMeals.values.any {
                Minutes.minutesBetween(it.startTime, DateTime.now()).minutes < 60
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



    private fun runOptimizationForMeal(session: MealOptimizationSession) {
        val optimizer = FCLSimplexOptimizer(parameterHistory)

        val optimizationWeight = getOptimizationWeightForMeal(session)

        val advice = optimizer.optimizeForSingleMeal(session.dataPoints, optimizationWeight)

        // ★★★ GEEN FALLBACK MEER - alleen echte adviezen integreren ★★★
        if (advice.isNotEmpty()) {
            integrateOptimizationAdvice(advice, session, optimizationWeight)
        }
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

        // ★★★ GEEN FALLBACK ADVIES MEER ★★★
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

    private data class ParameterState(
        val name: String,
        var currentValue: Double,
        var smoothedValue: Double = 0.0,
        var lastUpdate: DateTime = DateTime.now(),
        var lastChangeDirection: String = "STABLE",
        var cumulativeConfidence: Double = 0.0,
        var eventCount: Int = 0,
        var ewmaAlpha: Double = 0.2, // EWMA smoothing factor
        var deadbandPercent: Double = 2.0, // 2% deadband
        var maxDailyChangePercent: Double = 5.0, // Max 5% per dag
        var dailyChangeUsed: Double = 0.0,
        var lastDailyReset: DateTime = DateTime.now(),
        var isManuallyAdjusted: Boolean = false,
        var manualAdjustmentTime: DateTime? = null,
        var dwellTimeHours: Int = 24 // Wacht 24u na handmatige aanpassing
    )

    // ★★★ ROBUUST PARAMETER ADVIESSYSTEEM ★★★
    inner class RobustParameterAdvisor {


        private val parameterStates = mutableMapOf<String, ParameterState>()
        private val parameterLimits = mapOf(
            "bolus_perc_rising" to Pair(20.0, 180.0),
            "bolus_perc_plateau" to Pair(20.0, 150.0),
            "phase_rising_slope" to Pair(0.2, 3.0),
            "phase_plateau_slope" to Pair(0.1, 1.5),
            "bolus_perc_day" to Pair(15.0, 200.0),
            "bolus_perc_night" to Pair(10.0, 100.0),
            "meal_detection_sensitivity" to Pair(0.05, 0.5),
            "carb_percentage" to Pair(50.0, 200.0),
            "IOB_corr_perc" to Pair(60.0, 140.0)
        )


        init {
            initializeParameterStates()
        }



        private fun initializeParameterStates() {
            // Haal alle parameterwaarden op via de metrics helper
            val parameterNames = listOf(
                "bolus_perc_rising", "bolus_perc_plateau",
                "phase_rising_slope", "phase_plateau_slope",
                "bolus_perc_day", "bolus_perc_night",
                "meal_detection_sensitivity", "carb_percentage",
                "IOB_corr_perc", "hypo_risk_percentage"
            )

            parameterNames.forEach { name ->
                val value = getCurrentParameterValueFromPrefs(name)
                parameterStates[name] = ParameterState(
                    name = name,
                    currentValue = value,
                    smoothedValue = value,
                    ewmaAlpha = determineAlphaForParameter(name),
                    deadbandPercent = determineDeadbandForParameter(name),
                    maxDailyChangePercent = determineMaxChangeForParameter(name)
                )
            }
        }


        private fun determineAlphaForParameter(name: String): Double {
            return when {
                name.contains("phase_") || name.contains("slope") -> 0.3 // Snellere aanpassing voor detectie
                name.contains("perc") || name.contains("percentage") -> 0.15 // Langzamer voor percentages
                else -> 0.2
            }
        }

        private fun determineDeadbandForParameter(name: String): Double {
            return when {
                name.contains("phase_") || name.contains("slope") -> 1.5 // 1.5% voor slopes
                name.contains("perc") || name.contains("percentage") -> 2.0 // 2% voor percentages
                else -> 2.0
            }
        }

        private fun determineMaxChangeForParameter(name: String): Double {
            return when {
                name.contains("phase_") || name.contains("slope") -> 8.0 // 8% per dag voor detectie
                name.contains("perc") || name.contains("percentage") -> 5.0 // 5% per dag voor percentages
                else -> 5.0
            }
        }

        // ★★★ HOOFDFUNCTIE: Genereer advies met alle robuuste features ★★★
        fun generateRobustAdvice(
            mealMetrics: MealPerformanceMetrics,
            optimizationWeight: Double = 1.0
        ): List<ParameterAdvice> {
            val adviceList = mutableListOf<ParameterAdvice>()
            val now = DateTime.now()

            // Reset dagelijkse change counter indien nodig
            resetDailyChangesIfNeeded(now)

            // Analyseer maaltijd metrics voor verschillende parameters
            analyzeForRisingPhase(mealMetrics, adviceList, optimizationWeight, now)
            analyzeForPlateauPhase(mealMetrics, adviceList, optimizationWeight, now)
            analyzeForDetectionParameters(mealMetrics, adviceList, optimizationWeight, now)
            analyzeForSafetyParameters(mealMetrics, adviceList, optimizationWeight, now)

            // Pas symmetrie toe: controleer of we niet te eenzijdig corrigeren
            applySymmetryCorrection(adviceList)

            // Filter adviezen door deadband en dwell-time
            return filterAdviceThroughRobustness(adviceList, now)
        }

        private fun analyzeForRisingPhase(
            metrics: MealPerformanceMetrics,
            adviceList: MutableList<ParameterAdvice>,
            weight: Double,
            timestamp: DateTime
        ) {
            val state = parameterStates["bolus_perc_rising"] ?: return
            val slopeState = parameterStates["phase_rising_slope"] ?: return

            // BEREKENING 1: Te hoge piek → verhoog rising percentage
            if (metrics.peakBG > 9.5) {
                val peakDelta = metrics.peakBG - 9.5
                val severity = min(1.0, peakDelta / 3.0) // Max 3 mmol/L boven target
                val baseChange = 0.05 + (severity * 0.10) // 5-15% verhoging
                val confidence = min(0.9, 0.4 + (severity * 0.5)) * weight

                addParameterAdvice(
                    paramName = "bolus_perc_rising",
                    currentValue = state.currentValue,
                    suggestedChangePercent = baseChange,
                    direction = "INCREASE",
                    reason = "Piek ${String.format("%.1f", metrics.peakBG)} mmol/L > 9.5",
                    confidence = confidence,
                    adviceList = adviceList,
                    timestamp = timestamp
                )
            }

            // BEREKENING 2: Te late bolus → verlaag slope drempel
            if (metrics.timeToFirstBolus > 20) {
                val delaySeverity = min(1.0, (metrics.timeToFirstBolus - 20) / 40.0)
                val baseChange = -0.08 - (delaySeverity * 0.07) // 8-15% verlaging
                val confidence = min(0.8, 0.3 + (delaySeverity * 0.5)) * weight

                addParameterAdvice(
                    paramName = "phase_rising_slope",
                    currentValue = slopeState.currentValue,
                    suggestedChangePercent = baseChange,
                    direction = "DECREASE",
                    reason = "Eerste bolus ${metrics.timeToFirstBolus}min na start (>20min)",
                    confidence = confidence,
                    adviceList = adviceList,
                    timestamp = timestamp
                )
            }

            // BEREKENING 3: Snelle stijging maar geen piek → mogelijk te lage sensitivity
            val riseRate = calculateRiseRate(metrics)
            if (riseRate > 2.0 && metrics.peakBG < 8.5) {
                val confidence = 0.6 * weight
                addParameterAdvice(
                    paramName = "meal_detection_sensitivity",
                    currentValue = parameterStates["meal_detection_sensitivity"]?.currentValue ?: 0.3,
                    suggestedChangePercent = -0.10, // 10% verlagen voor snellere detectie
                    direction = "DECREASE",
                    reason = "Snelle stijging (${String.format("%.1f", riseRate)} mmol/L/u) maar geen hoge piek",
                    confidence = confidence,
                    adviceList = adviceList,
                    timestamp = timestamp
                )
            }
        }

        private fun analyzeForPlateauPhase(
            metrics: MealPerformanceMetrics,
            adviceList: MutableList<ParameterAdvice>,
            weight: Double,
            timestamp: DateTime
        ) {
            val state = parameterStates["bolus_perc_plateau"] ?: return
            val slopeState = parameterStates["phase_plateau_slope"] ?: return

            // BEREKENING 1: Plateau duurt te lang → verhoog plateau percentage
            val plateauDuration = calculatePlateauDuration(metrics)
            if (plateauDuration > 90 && metrics.peakBG > 8.0) {
                val severity = min(1.0, (plateauDuration - 90) / 120.0)
                val baseChange = 0.04 + (severity * 0.06) // 4-10% verhoging
                val confidence = min(0.8, 0.3 + (severity * 0.5)) * weight

                addParameterAdvice(
                    paramName = "bolus_perc_plateau",
                    currentValue = state.currentValue,
                    suggestedChangePercent = baseChange,
                    direction = "INCREASE",
                    reason = "Plateau fase duurt ${plateauDuration}min (>90min)",
                    confidence = confidence,
                    adviceList = adviceList,
                    timestamp = timestamp
                )
            }

            // BEREKENING 2: Snelle daling na plateau → verhoog plateau slope drempel
            if (metrics.rapidDeclineDetected && metrics.declineRate != null) {
                val declineSeverity = min(1.0, abs(metrics.declineRate!!) / 3.0)
                val baseChange = 0.06 + (declineSeverity * 0.09) // 6-15% verhoging
                val confidence = min(0.85, 0.4 + (declineSeverity * 0.45)) * weight

                addParameterAdvice(
                    paramName = "phase_plateau_slope",
                    currentValue = slopeState.currentValue,
                    suggestedChangePercent = baseChange,
                    direction = "INCREASE",
                    reason = "Snelle daling na plateau (${String.format("%.1f", metrics.declineRate)} mmol/L/u)",
                    confidence = confidence,
                    adviceList = adviceList,
                    timestamp = timestamp
                )
            }
        }

        private fun analyzeForDetectionParameters(
            metrics: MealPerformanceMetrics,
            adviceList: MutableList<ParameterAdvice>,
            weight: Double,
            timestamp: DateTime
        ) {
            // BEREKENING: Te veel false positives/negatives
            val detectionScore = calculateDetectionScore(metrics)

            if (detectionScore < 0.7) { // Slechte detectie score
                val sensitivityState = parameterStates["meal_detection_sensitivity"] ?: return

                val adjustment = if (detectionScore < 0.5) -0.12 else -0.06
                val confidence = min(0.75, 0.5 + ((0.7 - detectionScore) * 0.5)) * weight

                addParameterAdvice(
                    paramName = "meal_detection_sensitivity",
                    currentValue = sensitivityState.currentValue,
                    suggestedChangePercent = adjustment,
                    direction = if (adjustment > 0) "INCREASE" else "DECREASE",
                    reason = "Detectie score ${String.format("%.0f", detectionScore * 100)}% (<70%)",
                    confidence = confidence,
                    adviceList = adviceList,
                    timestamp = timestamp
                )
            }
        }

        private fun analyzeForSafetyParameters(
            metrics: MealPerformanceMetrics,
            adviceList: MutableList<ParameterAdvice>,
            weight: Double,
            timestamp: DateTime
        ) {
            // BEREKENING 1: Post-maaltijd hypo → verhoog IOB correctie
            if (metrics.postMealHypo || metrics.virtualHypoScore > 2.0) {
                val iobState = parameterStates["IOB_corr_perc"] ?: return
                val hypoState = parameterStates["hypo_risk_percentage"] ?: return

                val severity = if (metrics.postMealHypo) 1.0 else metrics.virtualHypoScore / 4.0
                val baseChange = 0.08 + (severity * 0.07) // 8-15% verhoging

                val confidenceIOB = min(0.9, 0.5 + (severity * 0.4)) * weight
                val confidenceHypo = min(0.85, 0.4 + (severity * 0.45)) * weight

                addParameterAdvice(
                    paramName = "IOB_corr_perc",
                    currentValue = iobState.currentValue,
                    suggestedChangePercent = baseChange,
                    direction = "INCREASE",
                    reason = if (metrics.postMealHypo) "Post-maaltijd hypo" else "Hoge virtuele hypo score (${String.format("%.1f", metrics.virtualHypoScore)})",
                    confidence = confidenceIOB,
                    adviceList = adviceList,
                    timestamp = timestamp
                )

                addParameterAdvice(
                    paramName = "hypo_risk_percentage",
                    currentValue = hypoState.currentValue,
                    suggestedChangePercent = baseChange * 0.8, // Iets minder agressief
                    direction = "INCREASE",
                    reason = "Hypo preventie",
                    confidence = confidenceHypo,
                    adviceList = adviceList,
                    timestamp = timestamp
                )
            }
        }

        private fun addParameterAdvice(
            paramName: String,
            currentValue: Double,
            suggestedChangePercent: Double,
            direction: String,
            reason: String,
            confidence: Double,
            adviceList: MutableList<ParameterAdvice>,
            timestamp: DateTime
        ) {
            val state = parameterStates[paramName] ?: return

            // ★★★ CRITICAL: Reset dagelijkse limiet als het een nieuwe dag is ★★★
            if (Days.daysBetween(state.lastDailyReset, timestamp).days >= 1) {
                state.dailyChangeUsed = 0.0
                state.lastDailyReset = timestamp

            }

            // Controleer handmatige aanpassing dwell-time
            if (state.isManuallyAdjusted && state.manualAdjustmentTime != null) {
                val hoursSinceManual = Hours.hoursBetween(state.manualAdjustmentTime!!, timestamp).hours
                if (hoursSinceManual < state.dwellTimeHours) {

                    return // Negeer advies tijdens dwell-time
                } else {
                    // Reset handmatige aanpassing na dwell-time
                    state.isManuallyAdjusted = false
                    state.manualAdjustmentTime = null
                }
            }

            // Bereken voorgestelde nieuwe waarde
            val proposedChange = currentValue * suggestedChangePercent
            val proposedValue = if (direction == "INCREASE") {
                currentValue + proposedChange
            } else {
                currentValue - proposedChange
            }

             // ★★★ PAS 10% MAX CHANGE LIMIET TOE ★★★
            val maxChange = currentValue * getMaxChangeForParameter(paramName)
            val proposedValueLimited = if (direction == "INCREASE") {
                min(proposedValue, currentValue + maxChange)
            } else {
                max(proposedValue, currentValue - maxChange)
            }


            // ★★★ EERST: Pas limieten toe ★★★
            val bounds = parameterLimits[paramName] ?: Pair(0.0, 100.0)
            val clampedValue = proposedValue.coerceIn(bounds.first, bounds.second)



            // ★★★ TWEE: Bereken hoeveel we daadwerkelijk mogen veranderen ★★★
            val desiredChangePercent = ((clampedValue - currentValue) / currentValue) * 100.0
            val desiredAbsoluteChange = abs(clampedValue - currentValue)

            // Controleer dagelijkse limiet
            val remainingDailyChangePercent = state.maxDailyChangePercent - state.dailyChangeUsed
            val maxAllowedChangePercent = max(0.0, remainingDailyChangePercent)
            val maxAllowedAbsoluteChange = currentValue * (maxAllowedChangePercent / 100.0)

            // Bepaal de uiteindelijke verandering (gebruik de kleinste van beide)
            val finalAbsoluteChange = min(desiredAbsoluteChange, maxAllowedAbsoluteChange)

            if (finalAbsoluteChange <= 0) {

                return
            }

            // Bereken de uiteindelijke waarde
            val finalValue = if (clampedValue > currentValue) {
                currentValue + finalAbsoluteChange
            } else {
                currentValue - finalAbsoluteChange
            }

            // ★★★ BEREKEN HET WERKELIJKE PERCENTAGE VERSCHIL ★★★
            val actualChangePercent = ((finalValue - currentValue) / currentValue) * 100.0

            // ★★★ UPDATE STATE VOOR DAGELIJKSE LIMIET ★★★
            state.dailyChangeUsed += abs(actualChangePercent)
            state.lastChangeDirection = direction
            state.cumulativeConfidence += confidence
            state.eventCount++

            // EWMA smoothing
            state.smoothedValue = state.smoothedValue * (1 - state.ewmaAlpha) + finalValue * state.ewmaAlpha

            // Alleen advies geven als we voldoende confidence hebben verzameld
            val minEvents = if (paramName.contains("phase_") || paramName.contains("slope")) 2 else 3
            val minCumulativeConfidence = if (paramName.contains("phase_") || paramName.contains("slope")) 1.2 else 1.5



            if (state.eventCount >= minEvents && state.cumulativeConfidence >= minCumulativeConfidence) {
                val finalConfidence = min(1.0, state.cumulativeConfidence / state.eventCount)

                adviceList.add(ParameterAdvice(
                    parameterName = paramName,
                    currentValue = currentValue,
                    recommendedValue = finalValue,
                    reason = "$reason (${String.format("%.1f", actualChangePercent)}% van ${String.format("%.1f", desiredChangePercent)}% voorgesteld, max ${state.maxDailyChangePercent}%/dag)",
                    confidence = finalConfidence,
                    direction = direction
                ))

                // Reset cumulative counters na advies
                state.cumulativeConfidence = 0.0
                state.eventCount = 0


            }
        }

        private fun applySymmetryCorrection(adviceList: MutableList<ParameterAdvice>) {
            // Groepeer adviezen per parameter
            val paramToAdvice = adviceList.groupBy { it.parameterName }

            // Maak een nieuwe lijst voor gecorrigeerde adviezen
            val correctedAdviceList = mutableListOf<ParameterAdvice>()

            paramToAdvice.forEach { (paramName, advices) ->
                val state = parameterStates[paramName] ?: return@forEach

                // Tel aantal increases vs decreases
                val increases = advices.count { it.direction == "INCREASE" }
                val decreases = advices.count { it.direction == "DECREASE" }

                // Als we te eenzijdig zijn, pas de confidence aan
                if (abs(increases - decreases) > 1) {
                    val bias = if (increases > decreases) 1.0 else -1.0
                    val correctionFactor = 0.8

                    advices.forEach { advice ->
                        val adjustedConfidence = if ((advice.direction == "INCREASE" && bias > 0) ||
                            (advice.direction == "DECREASE" && bias < 0)) {
                            advice.confidence * correctionFactor
                        } else {
                            advice.confidence
                        }

                        // Maak een nieuwe advies met aangepaste confidence
                        correctedAdviceList.add(advice.copy(
                            confidence = adjustedConfidence
                        ))
                    }
                } else {
                    correctedAdviceList.addAll(advices)
                }

                // Update lastChangeDirection voor volgende iteratie
                if (advices.isNotEmpty()) {
                    val lastAdvice = advices.last()
                    state.lastChangeDirection = lastAdvice.direction
                }
            }

            // Vervang de originele lijst
            adviceList.clear()
            adviceList.addAll(correctedAdviceList)
        }

        private fun filterAdviceThroughRobustness(
            adviceList: List<ParameterAdvice>,
            timestamp: DateTime
        ): List<ParameterAdvice> {
            return adviceList.mapNotNull { advice ->
                val state = parameterStates[advice.parameterName] ?: return@mapNotNull null

                // 1. Controleer handmatige aanpassing dwell-time
                if (state.isManuallyAdjusted && state.manualAdjustmentTime != null) {
                    val hoursSinceManual = Hours.hoursBetween(state.manualAdjustmentTime!!, timestamp).hours
                    if (hoursSinceManual < state.dwellTimeHours) {
                        return@mapNotNull null
                    }
                }

                // 2. Minimale confidence drempel
                if (advice.confidence < 0.3) {
                    return@mapNotNull null
                }

                // 3. Controleer of we niet te vaak van richting veranderen
                val directionChanges = countRecentDirectionChanges(advice.parameterName, timestamp)

                // Maak een nieuwe advies met aangepaste confidence indien nodig
                val finalAdvice = if (directionChanges > 2) {
                    advice.copy(confidence = advice.confidence * 0.7)
                } else {
                    advice
                }

                finalAdvice
            }
        }

        private fun countRecentDirectionChanges(paramName: String, timestamp: DateTime): Int {
            // Deze functie zou de parameter history moeten checken
            // Voor nu retourneren we 0 - implementeer later
            return 0
        }

        private fun resetDailyChangesIfNeeded(now: DateTime) {
            parameterStates.values.forEach { state ->
                if (Days.daysBetween(state.lastDailyReset, now).days >= 1) {
                    state.dailyChangeUsed = 0.0
                    state.lastDailyReset = now
                }
            }
        }

        // Helper functies
        private fun calculateRiseRate(metrics: MealPerformanceMetrics): Double {
            if (metrics.timeToPeak <= 0) return 0.0
            val rise = metrics.peakBG - metrics.startBG
            return rise / (metrics.timeToPeak / 60.0) // mmol/L per uur
        }

        private fun calculatePlateauDuration(metrics: MealPerformanceMetrics): Int {
            // Vereenvoudigde berekening - kan later uitgebreid worden
            return max(0, metrics.timeToPeak - 30) // Plateau start ~30min na begin
        }

        private fun calculateDetectionScore(metrics: MealPerformanceMetrics): Double {
            var score = 0.0

            // Goede timing van eerste bolus
            if (metrics.timeToFirstBolus in 10..25) score += 0.3
            else if (metrics.timeToFirstBolus in 5..35) score += 0.2
            else score += 0.1

            // Goede piek controle
            if (metrics.peakBG in 7.0..10.0) score += 0.4
            else if (metrics.peakBG in 6.0..11.0) score += 0.2
            else score += 0.1

            // Geen hypo
            if (!metrics.postMealHypo) score += 0.3

            return score
        }

        // ★★★ FUNCTIE OM HANDMATIGE AANPASSINGEN TE REGISTREREN ★★★
        fun registerManualAdjustment(paramName: String, timestamp: DateTime = DateTime.now()) {
            val state = parameterStates[paramName] ?: return
            state.isManuallyAdjusted = true
            state.manualAdjustmentTime = timestamp
            state.cumulativeConfidence = 0.0
            state.eventCount = 0
            state.dailyChangeUsed = 0.0

            // Reset EWMA smoothing na handmatige aanpassing
            state.smoothedValue = state.currentValue
        }

        // ★★★ FUNCTIE OM PARAMETER UPDATES TE VERWERKEN ★★★
        fun processParameterUpdate(paramName: String, newValue: Double, isManual: Boolean = false) {
            val state = parameterStates[paramName] ?: return

            state.currentValue = newValue

            if (isManual) {
                registerManualAdjustment(paramName)
            } else {
                // Update smoothed value voor automatische updates
                state.smoothedValue = state.smoothedValue * 0.7 + newValue * 0.3
            }
        }
    }

    // ★★★ SIMPLEX OPTIMALISATOR - NIEUWE INNER CLASS ★★★
    inner class FCLSimplexOptimizer(
        private val parameterHistory: ParameterHistoryManager
    ) {

        fun optimizeForSingleMeal(mealData: List<OptimizationDataPoint>, optimizationWeight: Double = 1.0): List<ParameterAdvice> {
            if (mealData.size < 3) {
                return emptyList()
            }

            // Gebruik de verbeterde extractie
            val enhancedMetrics = extractEnhancedMealMetrics(mealData)

            // Gebruik het nieuwe robuuste adviessysteem
            val robustAdvisor = RobustParameterAdvisor()
            val advice = robustAdvisor.generateRobustAdvice(enhancedMetrics, optimizationWeight)

            return advice.take(6) // Max 6 adviezen per maaltijd
        }

        private fun extractEnhancedMealMetrics(mealData: List<OptimizationDataPoint>): MealPerformanceMetrics {
            if (mealData.isEmpty()) return createEmptyMealMetrics()

            val validData = mealData.filter { it.bg in 2.0..25.0 }
            if (validData.isEmpty()) return createEmptyMealMetrics()

            // Basis berekeningen
            val startBG = validData.first().bg
            val peakBG = validData.maxOf { it.bg }
            val endBG = validData.last().bg

            // Zoek piek tijd
            val peakDataPoint = validData.maxByOrNull { it.bg }
            val timeToPeak = peakDataPoint?.let {
                Minutes.minutesBetween(validData.first().timestamp, it.timestamp).minutes
            } ?: 0

            // Bereken tijd tot eerste bolus
            val firstBolusDataPoint = validData.firstOrNull { it.insulinDelivered > 0.1 }
            val timeToFirstBolus = firstBolusDataPoint?.let {
                Minutes.minutesBetween(validData.first().timestamp, it.timestamp).minutes
            } ?: 0

            // Detecteer snelle daling
            val declineRate = calculateDeclineRate(validData)
            val rapidDeclineDetected = declineRate < -2.0

            // Bereken post-meal hypo
            val postMealHypo = validData.any { it.bg < 3.9 }

            // Bereken virtuele hypo score
            val virtualHypoScore = calculateVirtualHypoScoreFromData(validData)

            // Bereken TIR tijdens maaltijd
            val timeInRangeDuringMeal = calculateTIRFromData(validData)

            // Bepaal maaltijdtype
            val mealType = determineMealTypeFromTime(validData.first().timestamp)

            // Bepaal of succesvol
            val wasSuccessful = !postMealHypo && peakBG <= 10.5 && endBG <= startBG + 3.0

            return MealPerformanceMetrics(
                mealId = "meal_${validData.first().timestamp.millis}",
                mealStartTime = validData.first().timestamp,
                mealEndTime = validData.last().timestamp,
                startBG = startBG,
                peakBG = peakBG,
                endBG = endBG,
                timeToPeak = timeToPeak,
                totalCarbsDetected = validData.maxOfOrNull { it.carbsOnBoard } ?: 0.0,
                totalInsulinDelivered = validData.sumOf { it.insulinDelivered },
                peakAboveTarget = max(0.0, peakBG - Target_Bg),
                timeAbove10 = validData.count { it.bg > 10.0 } * 5, // 5 minuten per meting
                postMealHypo = postMealHypo,
                timeInRangeDuringMeal = timeInRangeDuringMeal,
                phaseInsulinBreakdown = emptyMap(),
                firstBolusTime = firstBolusDataPoint?.timestamp,
                timeToFirstBolus = timeToFirstBolus,
                maxIOBDuringMeal = validData.maxOfOrNull { it.iob } ?: 0.0,
                wasSuccessful = wasSuccessful,
                mealType = mealType,
                declineRate = declineRate,
                rapidDeclineDetected = rapidDeclineDetected,
                virtualHypoScore = virtualHypoScore
            )
        }

        private fun createEmptyMealMetrics(): MealPerformanceMetrics {
            return MealPerformanceMetrics(
                mealId = "empty",
                mealStartTime = DateTime.now(),
                mealEndTime = DateTime.now(),
                startBG = 0.0,
                peakBG = 0.0,
                endBG = 0.0,
                timeToPeak = 0,
                totalCarbsDetected = 0.0,
                totalInsulinDelivered = 0.0,
                peakAboveTarget = 0.0,
                timeAbove10 = 0,
                postMealHypo = false,
                timeInRangeDuringMeal = 0.0,
                phaseInsulinBreakdown = emptyMap(),
                firstBolusTime = null,
                timeToFirstBolus = 0,
                maxIOBDuringMeal = 0.0,
                wasSuccessful = false,
                mealType = "unknown"
            )
        }

        private fun calculateDeclineRate(data: List<OptimizationDataPoint>): Double {
            if (data.size < 4) return 0.0
            val recent = data.takeLast(4)
            val timeDiffHours = Hours.hoursBetween(recent.first().timestamp, recent.last().timestamp).hours.toDouble()
            if (timeDiffHours == 0.0) return 0.0
            return (recent.last().bg - recent.first().bg) / timeDiffHours
        }

        private fun calculateVirtualHypoScoreFromData(data: List<OptimizationDataPoint>): Double {
            if (data.size < 3) return 0.0
            var score = 0.0

            // Factor 1: Daalsnelheid
            val declineRate = calculateDeclineRate(data)
            score += min(abs(declineRate) * 0.5, 3.0)

            // Factor 2: IOB tijdens daling
            val avgIOB = data.map { it.iob }.average()
            if (avgIOB > 2.0) score += 1.0
            if (avgIOB > 3.0) score += 1.0

            // Factor 3: Lage glucose waarden
            val minBG = data.minOf { it.bg }
            if (minBG < 4.5) score += 1.0
            if (minBG < 4.0) score += 1.0

            return min(score, 8.0)
        }

        private fun calculateTIRFromData(data: List<OptimizationDataPoint>): Double {
            if (data.isEmpty()) return 0.0
            val inRange = data.count { it.bg in 3.9..10.0 }
            return (inRange.toDouble() / data.size) * 100.0
        }

        private fun determineMealTypeFromTime(timestamp: DateTime): String {
            val hour = timestamp.hourOfDay
            return when {
                hour in 6..10 -> "ontbijt"
                hour in 11..14 -> "lunch"
                hour in 17..21 -> "dinner"
                else -> "snack"
            }
        }

        private fun calculateTimeToPeak(data: List<OptimizationDataPoint>): Int {
            if (data.size < 2) return 0
            val peakIndex = data.indexOfFirst { it.bg == data.maxOf { d -> d.bg } }
            if (peakIndex <= 0) return 0
            return Minutes.minutesBetween(data.first().timestamp, data[peakIndex].timestamp).minutes
        }


        // ★★★ HELPER FUNCTIES VOOR MAALTIJD ANALYSE ★★★
        private fun calculateTimeToFirstBolus(dataPoints: List<OptimizationDataPoint>): Int {
            if (dataPoints.isEmpty()) return 0
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

        private fun extractMealMetricsFromData(mealData: List<OptimizationDataPoint>): QuickMealMetrics {
            if (mealData.isEmpty()) return QuickMealMetrics()

            val validData = mealData.filter { it.bg in 2.0..25.0 }

            return QuickMealMetrics(
                peakBG = validData.maxOfOrNull { it.bg } ?: 0.0,
                timeToFirstBolus = calculateTimeToFirstBolus(validData),
                postMealHypo = validData.any { it.bg < 3.9 },
                rapidDeclineDetected = detectRapidDecline(validData)
            )
        }


        private fun round(value: Double, digits: Int = 1): Double {
            val factor = Math.pow(10.0, digits.toDouble())
            return Math.round(value * factor) / factor
        }

    }


    fun setTargetBg(value: Double) { Target_Bg = value }


    fun resetDataQualityCache() {
        cachedDataQuality = null
    }

    // ★★★ UPDATE PARAMETER ADVIES - DIRECTE UI INTEGRATIE ★★★

    // ★★★ HELPER FUNCTIE VOOR PARAMETER TYPE DETECTIE ★★★
    private fun isSlopeParameter(parameterName: String): Boolean {
        return parameterName.contains("slope", ignoreCase = true) ||
            parameterName.contains("sensitivity", ignoreCase = true) ||
            parameterName.contains("phase_", ignoreCase = true)
    }

    private fun isPercentageParameter(parameterName: String): Boolean {
        return parameterName.contains("perc", ignoreCase = true) ||
            parameterName.contains("percentage", ignoreCase = true) ||
            parameterName.contains("_corr", ignoreCase = true)
    }

    // ★★★ GETE MAX CHANGE OP BASIS VAN PARAMETER TYPE ★★★
    private fun getMaxChangeForParameter(parameterName: String): Double {
        return when {
            isSlopeParameter(parameterName) -> MAX_SLOPE_CHANGE
            isPercentageParameter(parameterName) -> MAX_PERCENTAGE_CHANGE
            else -> MAX_PERCENTAGE_CHANGE // Fallback
        }
    }
    // ★★★  MAX CHANGE HELPER FUNCTION ★★★
    private fun applyMax10PercentChange(currentValue: Double, recommendedValue: Double, parameterName: String): Triple<Double, Boolean, String> {
        if (currentValue == 0.0) return Triple(recommendedValue, false, "")

        val maxChange = abs(currentValue * getMaxChangeForParameter(parameterName)) // 10% van huidige waarde
        val minAllowed = currentValue - maxChange
        val maxAllowed = currentValue + maxChange

        val limitedValue = recommendedValue.coerceIn(minAllowed, maxAllowed)
        val wasLimited = limitedValue != recommendedValue
        val limitationNote = if (wasLimited) " (beperkt tot ${String.format("%.1f", (abs(limitedValue - currentValue)/currentValue)*100)}%)" else ""

        return Triple(limitedValue, wasLimited, limitationNote)
    }

    // ★★★ VEILIGE VERSIE - ZONDER CRASHES ★★★
    private fun updateParameterAdviceInBackground(advice: ParameterAdvice) {
        try {
            // ★★★ EXTRA VEILIGHEIDSCHECK ★★★
            if (!parameterCacheInitialized) {
                return // ★★★ NIET VERDER GAAN TIJDENS INIT ★★★
            }

            // ★★★ PAS 10% MAX CHANGE LIMIET TOE ★★★
            val (limitedRecommendedValue, wasLimited, limitationNote) =
                applyMax10PercentChange(advice.currentValue, advice.recommendedValue, advice.parameterName)

            // Update het advies met de beperkte waarde
            val limitedAdvice = if (wasLimited) {
                advice.copy(
                    recommendedValue = limitedRecommendedValue,
                    reason = "${advice.reason}${limitationNote}"
                )
            } else {
                advice.copy(recommendedValue = limitedRecommendedValue)
            }

            // Bewaar minimale zichtbaarheid
            val enhancedAdvice = if (limitedAdvice.confidence < 0.05) {
                limitedAdvice.copy(confidence = 0.05)
            } else {
                limitedAdvice
            }

            // ★★★ DEBUG LOGGING VOOR LIMIETEN ★★★
            if (wasLimited) {

            }


            // Maak ParameterAgressivenessAdvice
            val agressivenessAdvice = ParameterAgressivenessAdvice(
                parameterName = enhancedAdvice.parameterName,
                currentValue = enhancedAdvice.currentValue,
                recommendedValue = enhancedAdvice.recommendedValue,
                reason = enhancedAdvice.reason,
                confidence = enhancedAdvice.confidence,
                expectedImprovement = "Optimalisatie advies",
                changeDirection = enhancedAdvice.direction,
                timestamp = DateTime.now()
            )

            // ★★★ ESSENTIEEL: UPDATE CENTRALE ADVICE-HISTORY ★★★
            parameterAdviceHistory[agressivenessAdvice.parameterName] = agressivenessAdvice
            storeParameterAdviceHistory()

            // ★★★ UPDATE PARAMETER HISTORIE VOOR GEWOGEN GEMIDDELDE ★★★
            updateParameterHistoryWithIterativeAdvice(enhancedAdvice)

            // ★★★ BEPAAL WEIGHTED AVERAGE ★★★
            val history = parameterHistories[enhancedAdvice.parameterName]
            val weightedAverage = history?.let {
                calculateWeightedAverageFromHistory(it.adviceHistory)
            } ?: enhancedAdvice.recommendedValue

            // ★★★ SLA SUMMARY OP ★★★
            cachedParameterSummaries[enhancedAdvice.parameterName] = ParameterAdviceSummary(
                parameterName = enhancedAdvice.parameterName,
                currentValue = enhancedAdvice.currentValue,
                lastAdvice = agressivenessAdvice,
                weightedAverage = weightedAverage,
                confidence = enhancedAdvice.confidence,
                trend = determineTrendFromHistory(history?.adviceHistory ?: emptyList()),
                manuallyAdjusted = false
            )

            // ★★★ BOUW UI-SUGGESTION OBJECT ★★★
            val suggestion = ParameterSuggestion(
                parameterName = enhancedAdvice.parameterName,
                currentValue = enhancedAdvice.currentValue,
                suggestedValue = enhancedAdvice.recommendedValue,
                reason = enhancedAdvice.reason ?: "Geen reden opgegeven",
                direction = enhancedAdvice.direction ?: "NONE",
                confidence = enhancedAdvice.confidence,
                timestamp = DateTime.now()
            )
            cachedParameterSuggestions[enhancedAdvice.parameterName] = suggestion

            // ★★★ VEILIGE UI UPDATE ★★★
            notifyAdviceUpdatedSafe()


        } catch (e: Exception) {
            // ★★★ CRASH PREVENTIE ★★★
            android.util.Log.e("FCLMetrics", "Error in updateParameterAdviceInBackground: ${e.message}")
        }
    }

    // ★★★ VERBETER updateParameterHistoryWithIterativeAdvice ★★★

    private fun updateParameterHistoryWithIterativeAdvice(advice: ParameterAdvice) {
        try {
            val history = parameterHistories.getOrPut(advice.parameterName) {
                EnhancedParameterHistory(advice.parameterName)
            }

            // ★★★ SPECIALE LOGICA VOOR DETECTIE PARAMETERS ★★★
            val isDetectionParam = advice.parameterName.contains("phase_") ||
                advice.parameterName.contains("detection") ||
                advice.parameterName.contains("slope")


            // ★★★ BETER FILTER: alleen vergelijkbare adviezen binnen bepaalde tijd ★★★
            val now = DateTime.now()
            val recentSimilar = history.adviceHistory.any { existing ->
                val timeDiffHours = Hours.hoursBetween(existing.timestamp, now).hours.toDouble()
                val valueDiff = abs(existing.recommendedValue - advice.recommendedValue)
                val maxDiff = if (isDetectionParam) {
                    // Voor detectieparameters: kleinere tolerantie
                    max(0.05, existing.recommendedValue * 0.05) // 5% of min 0.05
                } else {
                    max(0.5, existing.recommendedValue * 0.1) // 10% of min 0.5
                }

                timeDiffHours < (if (isDetectionParam) 0.5 else 1.0) && // Kortere tijd voor detectie
                    valueDiff < maxDiff
            }

            if (!recentSimilar) {
                val historicalAdvice = HistoricalAdvice(
                    timestamp = now,
                    recommendedValue = advice.recommendedValue,
                    changeDirection = advice.direction,
                    confidence = max(0.1, advice.confidence), // Minimale confidence van 0.1
                    reason = advice.reason.take(100) // Beperk reden lengte
                )

                history.adviceHistory.add(historicalAdvice)

                // ★★★ BEPERK TOT LAATSTE 20 ADVIEZEN ★★★
                if (history.adviceHistory.size > 20) {
                    history.adviceHistory.removeAt(0)
                }

                saveParameterHistories()


            } else {

            }
        } catch (e: Exception) {

        }
    }


    // ★★★ NIEUWE FUNCTIE: BOUW CONSOLIDATED ADVICE VANUIT HUIDIGE AANBEVELINGEN ★★★
    private fun buildConsolidatedAdviceFromCurrentRecommendations() {
        val summaries = getCachedParameterSummary()

        // ★★★ DEBUG: TOON ALLE SUMMARIES ★★★

        summaries.forEach { summary ->

        }

        val adjustments = summaries.mapNotNull { summary ->
            // ★★★ GEBRUIK DIRECT parameterAdviceHistory ALS lastAdvice NULL IS ★★★
            val advice = summary.lastAdvice ?: parameterAdviceHistory[summary.parameterName]

            advice?.let { adv ->
                ParameterAgressivenessAdvice(
                    parameterName = adv.parameterName,
                    currentValue = adv.currentValue,
                    recommendedValue = adv.recommendedValue,
                    reason = adv.reason,
                    confidence = adv.confidence, // ★★★ GEBRUIK DE ORIGINELE CONFIDENCE ★★★
                    expectedImprovement = "Iteratieve optimalisatie",
                    changeDirection = adv.changeDirection,
                    timestamp = adv.timestamp
                )
            }
        }

        // ★★★ DEBUG: TOON ALLE ADJUSTMENTS ★★★

        adjustments.forEach { adjustment ->

        }

        cachedConsolidatedAdvice = ConsolidatedAdvice(
            primaryAdvice = "Iteratieve parameter optimalisatie voltooid",
            parameterAdjustments = adjustments,
            confidence = adjustments.map { it.confidence }.average().coerceIn(0.0, 1.0),
            reasoning = "Gebaseerd op analyse van recente maaltijd prestaties",
            expectedImprovement = "Verbeterde glucose controle na maaltijden",
            timestamp = DateTime.now()
        )

        storeConsolidatedAdvice(cachedConsolidatedAdvice!!)
    }


    // ★★★ VEILIGE VERSIE - ZONDER RECURSIE ★★★
    private fun notifyAdviceUpdatedSafe() {
        try {
            // ★★★ ALLEEN CACHE CLEAREN - GEEN HERBERKENING ★★★
            cachedParameterSummaries.clear()

            // ★★★ GEEN postDelayed MEER - TE GEVAARLIJK TIJDENS INIT ★★★
            // De UI zal automatisch refreshen bij volgende aanroep van getCachedParameterSummary()

        } catch (e: Exception) {
            // ★★★ ABSOLUUT GEEN THROW - ALLEEN LOGGING ★★★
            android.util.Log.e("FCLMetrics", "Error in notifyAdviceUpdatedSafe: ${e.message}")
        }
    }

    // ★★★ PERIODIEKE OPTIMALISATIE - VOOR ELKE 5 MINUTEN ★★★
// ★★★ PERIODIEKE OPTIMALISATIE - VOOR ELKE 5 MINUTEN ★★★
    fun onFiveMinuteTick(currentBG: Double, currentIOB: Double, context: FCL.FCLContext) {
        val now = DateTime.now()
        val currentHour = now.hourOfDay

        try {
            // ★★★ STAP 1: RESET HANDMATIGE BLOKKADES ★★★
            resetManualAdjustmentBlocks()

            // ★★★ STAP 2: ALLEEN OPTIMALISEREN TIJDENS WAKKERE UREN ★★★
            if (currentHour in OPTIMIZATION_DAY_START until OPTIMIZATION_DAY_END) {
                // ★★★ STAP 3: DETECTEER MAALTIJDEN ★★★
                val isMealActive = context.mealDetected || context.detectedCarbs > 15.0 ||
                    (currentBG > Target_Bg + 2.0 && currentIOB < 2.0)

                if (isMealActive) {
                    // Maaltijd gedetecteerd - plan optimalisatie na afloop
                    schedulePostMealOptimization()
                }

                // ★★★ STAP 4: CHECK OF ER MAALTIJDEN ZIJN AFGEROND ★★★
                val recentMeals = calculateEnhancedMealPerformanceMetrics(RECENT_MEALS_FOR_COMPLETED_HOURS)

                // Filter recent afgeronde maaltijden (minder dan 30 minuten geleden)
                val completedMeals = recentMeals.filter {
                    it.mealEndTime.isAfter(now.minusMinutes(30)) &&
                        it.mealStartTime.isAfter(now.minusHours(3))
                }

                if (completedMeals.isNotEmpty()) {
                    // ★★★ STAP 5: VOER OPTIMALISATIE UIT VOOR AFGERONDE MAALTIJDEN ★★★
                    val mealsToAnalyze = min(MIN_MEALS_FOR_OPTIMIZATION, completedMeals.size)
                    if (mealsToAnalyze >= 1) {
                        runOptimizerOverLastNMealsAndAggregate(completedMeals, mealsToAnalyze)


                    }
                } else {
                    // ★★★ STAP 6: PERIODIEKE OPTIMALISATIE (MAX 1x PER OPTIMIZATION_COOLDOWN_HOURS) ★★★
                    val lastOptTime = getLastOptimizationTime()
                    val hoursSinceLast = lastOptTime?.let {
                        Hours.hoursBetween(it, now).hours
                    } ?: 24

                    if (hoursSinceLast >= OPTIMIZATION_COOLDOWN_HOURS) {
                        // Voer algemene optimalisatie uit over laatste uren
                        val recentMealsForOptimization = calculateEnhancedMealPerformanceMetrics(OPTIMIZATION_RECENT_MEALS_HOURS)
                        if (recentMealsForOptimization.size >= MIN_MEALS_FOR_OPTIMIZATION) {
                            runOptimizerOverLastNMealsAndAggregate(
                                recentMealsForOptimization,
                                min(OPTIMIZATION_MEALS_COUNT, recentMealsForOptimization.size)
                            )
                            setLastOptimizationTime(now)
                        }
                    }
                }
            }

            // ★★★ STAP 7: BEHOUD BESTAANDE LOGICA VOOR BACKUP ★★★
            optimizationController.onNewBGReading(currentBG, currentIOB, context)

        } catch (e: Exception) {
            // Fallback naar basis optimalisatie

            optimizationController.onNewBGReading(currentBG, currentIOB, context)
        }
    }

    // ★★★ NIEUWE HELPER FUNCTIES ★★★
    private fun schedulePostMealOptimization() {
        // Wordt aangeroepen bij maaltijd detectie
        // Implementatie: zou kunnen plannen voor 2-3 uur na maaltijd
        // Voor nu: we gebruiken de directe aanpak
    }

    private fun getLastOptimizationTime(): DateTime? {
        val lastTimeMillis = prefs.getLong("last_periodic_optimization", 0)
        return if (lastTimeMillis > 0) DateTime(lastTimeMillis) else null
    }

    private fun setLastOptimizationTime(time: DateTime) {
        prefs.edit().putLong("last_periodic_optimization", time.millis).apply()
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

        }
    }

    // ★★★ VOEG DEBUG TOE AAN DEZE FUNCTIE ★★★
    fun clearParameterAdviceHistoryOnManualAdjustment(parameterName: String) {
        try {
            // 1) RESET ALLE CACHES
            cachedParameterSummaries.remove(parameterName)
            cachedParameterSuggestions.remove(parameterName)
            parameterAdviceHistory.remove(parameterName)

            // 2) RESET PARAMETER HISTORY - EXPLICIET LEEG MAKEN
            parameterHistories[parameterName] = EnhancedParameterHistory(
                parameterName = parameterName,
                adviceHistory = mutableListOf(),
                mealBasedAdvice = mutableMapOf(),
                performanceTrend = "STABLE",
                lastManualReset = DateTime.now(),
                successRateAfterAdjustment = 0.0
            )

            // 3) UPDATE TIMESTAMP (dit markeert de handmatige aanpassing)
            parameterAdjustmentTimestamps[parameterName] = DateTime.now()

            // 4) PERSIST ALLE WIJZIGINGEN
            persistAllChanges()

            // 5) FORCEER NIEUWE BEREKENING DOOR CACHE TE CLEAREN
            cachedParameterSummaries.clear()


        } catch (e: Exception) {

        }
    }

    private fun persistAllChanges() {
        try {
            val editor = prefs.edit()

            // Sla alle timestamps op
            editor.putString("parameter_adjustment_timestamps",
                             gson.toJson(parameterAdjustmentTimestamps.mapValues { it.value.millis }))

            // Sla advice history op
            editor.putString("parameter_advice_history", gson.toJson(parameterAdviceHistory))

            editor.apply()

            // Sla parameter histories op
            saveParameterHistories()

        } catch (e: Exception) {

        }
    }

    // ★★★ NIEUWE FUNCTIE: RESET HANDMATIGE BLOKKADES ★★★
    fun resetManualAdjustmentBlocks() {
        try {
            val now = DateTime.now()
            var resetCount = 0

            // ★★★ VERWIJDER OUDE TIMESTAMPS (> 24 uur) ★★★
            val iterator = parameterAdjustmentTimestamps.iterator()
            while (iterator.hasNext()) {
                val (paramName, timestamp) = iterator.next()
                if (timestamp.isBefore(now.minusHours(24))) {
                    iterator.remove()
                    resetCount++

                }
            }

            if (resetCount > 0) {
                persistAllChanges()

            }
        } catch (e: Exception) {

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
        var changesDetected = false

        currentValues.forEach { (name, currentValue) ->
            val lastValue = lastParameterValues[name]
            if (lastValue != null && abs(currentValue - lastValue) > 0.005) {

                changesDetected = true
                try {
                    clearParameterAdviceHistoryOnManualAdjustment(name)
                } catch (e: Exception) {

                }
            }
        }

        // Werk bij na alle checks
        lastParameterValues.clear()
        lastParameterValues.putAll(currentValues)


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
                            // Run optimizer for this meal & aggregate across previous meals (advice-only)
                            try {
                                // run optimizer for this single meal (immediately)
                                val advsForMeal = runIterativeParameterOptimizerForMeal(mealMetrics)
                                advsForMeal.forEach { a ->
                                    val advice = ParameterAdvice(
                                        parameterName = a.parameterName,
                                        currentValue = a.currentValue,
                                        recommendedValue = a.recommendedValue,
                                        reason = a.reason,
                                        confidence = a.confidence,
                                        direction = a.changeDirection
                                    )
                                    updateParameterAdviceInBackground(advice)
                                }

                                // Run aggregated optimizer across last 5 meals (optional, you can change 5)
                                val allMealsSoFar = cachedMealMetrics.toMutableList().apply { add(0, mealMetrics) } // most recent first
                                runOptimizerOverLastNMealsAndAggregate(allMealsSoFar, 5)
                            } catch (e: Exception) {
                                // swallow/log
                            }
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



    // ★★★ GECACHED PARAMETER SUMMARY - MET PRIORITEIT VOOR KERNPARAMETERS ★★★
    fun getCachedParameterSummary(): List<ParameterAdviceSummary> {
        // ★★★ ALTIJD DETECTIE DOEN ★★★
        detectParameterChanges()

        // ★★★ KERNPARAMETERS EERST ★★★
        val coreParameters = listOf(
            "bolus_perc_rising", "phase_rising_slope",
            "bolus_perc_plateau", "phase_plateau_slope"
        )

        // ★★★ ONDERSTEUNENDE PARAMETERS ★★★
        val supportingParameters = listOf(
            "bolus_perc_day", "bolus_perc_night",
            "meal_detection_sensitivity", "carb_percentage",
            "peak_damping_percentage", "hypo_risk_percentage", "IOB_corr_perc"
        )

        if (cachedParameterSummaries.isEmpty() || shouldCalculateNewAdvice()) {
            // ★★★ BEHANDEL KERNPARAMETERS MET PRIORITEIT ★★★
            val allParameters = coreParameters + supportingParameters

            // ★★★ DIRECT FROM parameterAdviceHistory - NO FILTERING ★★★
            val currentAdvice: List<ParameterAgressivenessAdvice> = parameterAdviceHistory.values.toList()



            cachedParameterSummaries = allParameters.associate { paramName ->
                val summary = calculateParameterSummary(paramName, currentAdvice)

                // ★★★ EXTRA WEIGHT VOOR KERNPARAMETERS ★★★
                val enhancedSummary = if (paramName in coreParameters) {
                    val coreMultiplier = 1.2 // 20% extra gewicht voor kernparameters
                    summary.copy(
                        confidence = min(1.0, summary.confidence * coreMultiplier)
                    )
                } else {
                    summary
                }

                paramName to enhancedSummary
            }.toMutableMap()

            // ★★★ FORCEER UPDATE VAN GEWOGEN GEMIDDELDE ★★★
            cachedParameterSummaries.forEach { (paramName, summary) ->
                val history = parameterHistories[paramName]
                if (history != null && history.adviceHistory.isNotEmpty()) {
                    val weightedAvg = calculateWeightedAverageFromHistory(history.adviceHistory)
                    cachedParameterSummaries[paramName] = summary.copy(weightedAverage = weightedAvg)
                }
            }
        }

        // ★★★ SORTEER: KERNPARAMETERS EERST ★★★
        val sortedSummaries = cachedParameterSummaries.values.sortedBy {
            if (it.parameterName in coreParameters) 0 else 1
        }

        return sortedSummaries
    }



    private fun calculateParameterSummary(
        parameterName: String,
        currentAdvice: List<ParameterAgressivenessAdvice>
    ): ParameterAdviceSummary {
        val currentValue = getCurrentParameterValueFromPrefs(parameterName)

        // ★★★ SPECIALE DEBUG VOOR DETECTIE PARAMETERS ★★★
        val isDetectionParam = parameterName.contains("phase_") ||
            parameterName.contains("detection") ||
            parameterName.contains("slope")

        // ★★★ CHECK HANDMATIGE AANPASSING ★★★
        val isRecentlyManuallyAdjusted = isParameterManuallyAdjusted(parameterName)

        if (isRecentlyManuallyAdjusted) {
            val lastAdjustment = parameterAdjustmentTimestamps[parameterName]
            val hoursAgo = lastAdjustment?.let { Hours.hoursBetween(it, DateTime.now()).hours } ?: 0

            return ParameterAdviceSummary(
                parameterName = parameterName,
                currentValue = currentValue,
                lastAdvice = null,
                weightedAverage = currentValue,  // Gebruik huidige waarde als gewogen gemiddelde
                confidence = 0.0, // Geen confidence bij handmatige aanpassing
                trend = "STABLE",
                manuallyAdjusted = true,
                lastManualAdjustment = lastAdjustment
            )
        }

        // ★★★ ZOEK MEEST RECENTE ADVIES ★★★
        val parameterAdvice = currentAdvice
            .filter { it.parameterName == parameterName }
            .maxByOrNull { it.timestamp }

        val history = parameterHistories.getOrPut(parameterName) {
            EnhancedParameterHistory(parameterName).also { newHistory ->
                // ★★★ INITIALISEER MET HUIDIGE WAARDE ALS HISTORY LEEG IS ★★★
                if (newHistory.adviceHistory.isEmpty() && currentValue > 0.0) {

                    newHistory.adviceHistory.add(HistoricalAdvice(
                        timestamp = DateTime.now().minusHours(1),
                        recommendedValue = currentValue,
                        changeDirection = "STABLE",
                        confidence = 0.5,  // Gemiddelde confidence voor init
                        reason = "Initialisatie"
                    ))
                    saveParameterHistories()
                }
            }
        }

        // ★★★ BEREKEN GEWOGEN GEMIDDELDE ★★★
        val weightedAverage = if (history.adviceHistory.isNotEmpty()) {
            calculateWeightedAverageFromHistory(history.adviceHistory)
        } else {
            parameterAdvice?.recommendedValue ?: currentValue
        }

        // Speciale controle voor detectieparameters
        if (isDetectionParam && weightedAverage == 0.0 && currentValue > 0.0) {
            val fallbackValue = parameterAdvice?.recommendedValue ?: currentValue

            return ParameterAdviceSummary(
                parameterName = parameterName,
                currentValue = currentValue,
                lastAdvice = parameterAdvice,
                weightedAverage = fallbackValue,
                confidence = parameterAdvice?.confidence ?: 0.0,
                trend = determineTrendFromHistory(history.adviceHistory),
                manuallyAdjusted = false,
                lastManualAdjustment = null
            )
        }

        // ★★★ PAS 10% MAX CHANGE LIMIET TOE ★★★
        val maxChange = currentValue * getMaxChangeForParameter(parameterName)
        val limitedWeightedAverage = weightedAverage.coerceIn(
            currentValue - maxChange,
            currentValue + maxChange
        )

        // ★★★ DEBUG LOGGING ★★★
        if (limitedWeightedAverage != weightedAverage) {
            val originalChange = ((weightedAverage - currentValue) / currentValue) * 100
            val limitedChange = ((limitedWeightedAverage - currentValue) / currentValue) * 100

        }

        val trend = determineTrendFromHistory(history.adviceHistory)

        return ParameterAdviceSummary(
            parameterName = parameterName,
            currentValue = currentValue,
            lastAdvice = parameterAdvice,
            weightedAverage = limitedWeightedAverage,
            confidence = parameterAdvice?.confidence ?: 0.0,
            trend = trend,
            manuallyAdjusted = false,
            lastManualAdjustment = null
        )
    }

    // ★★★ VERBETERDE GEWOGEN GEMIDDELDE BEREKENING ★★★


    private fun calculateWeightedAverageFromHistory(adviceHistory: List<HistoricalAdvice>): Double {
        if (adviceHistory.isEmpty()) {

            return 0.0
        }

        val now = DateTime.now()
        var totalWeight = 0.0
        var weightedSum = 0.0

        // Filter ongeldige waarden uit
        val validHistory = adviceHistory.filter { advice ->
            // Basis validatie
            val isValid = advice.recommendedValue > 0.0 &&
                advice.confidence > 0.1 &&
                !advice.recommendedValue.isNaN() &&
                !advice.recommendedValue.isInfinite()

            if (!isValid) {

            }
            isValid
        }

        if (validHistory.isEmpty()) {

            return 0.0
        }

        // Sorteer op tijd (oudste eerst)
        val sortedHistory = validHistory.sortedBy { it.timestamp }

        // Bereken gewichten voor elk historisch advies
        sortedHistory.forEachIndexed { index, advice ->
            // 1. Recency gewicht: nieuwer = zwaarder (exponentiële afname)
            val hoursAgo = Hours.hoursBetween(advice.timestamp, now).hours.toDouble()
            val recencyWeight = Math.exp(-hoursAgo / 72.0) // Halvering na 72 uur

            // 2. Confidence gewicht (kwadratisch voor betere differentiatie)
            val confidenceWeight = advice.confidence * advice.confidence

            // 3. Position boost: recentere adviezen krijgen extra gewicht
            val positionInHistory = (index + 1).toDouble() / sortedHistory.size
            val positionBoost = 0.8 + (positionInHistory * 0.4) // Range: 0.8 - 1.2

            // 4. Combined weight
            val combinedWeight = recencyWeight * confidenceWeight * positionBoost

            weightedSum += advice.recommendedValue * combinedWeight
            totalWeight += combinedWeight


        }

        val result = if (totalWeight > 0.001) {
            val avg = weightedSum / totalWeight

            avg
        } else {

            0.0
        }

        return result
    }


    private fun logWeightedAverages() {
        synchronized(logLock) {
            try {
                val now = DateTime.now()

                // ★★★ COOLDOWN: MAX 1x PER 5 MINUTEN ★★★
                lastLogTime?.let { lastTime ->
                    if (Minutes.minutesBetween(lastTime, now).minutes < 5) {
                        android.util.Log.d("FCL_WEIGHTED_AVG", "Skipping log - cooldown active (5 min)")
                        return
                    }
                }

                // ★★★ HAAL ALLEEN NIEUWE ADVIEZEN OP DIE DAADWERKELIJK ANDERS ZIJN ★★★
                val paramOrder = listOf(
                    "bolus_perc_rising", "phase_rising_slope",
                    "bolus_perc_plateau", "phase_plateau_slope",
                    "IOB_corr_perc"
                )

                // Verzamel alleen gewogen gemiddelden die > 0 zijn EN afwijken van huidige waarde
                val currentValues = mutableMapOf<String, Double>()
                var hasNewAdvice = false

                paramOrder.forEach { paramName ->
                    val summary = cachedParameterSummaries[paramName]
                    val weightedAvg = summary?.weightedAverage ?: 0.0
                    val currentPrefValue = getCurrentParameterValueFromPrefs(paramName)

                    // Alleen loggen als:
                    // 1. Gewogen gemiddelde > 0
                    // 2. Verschil met huidige waarde > 0.5 (voor percentages) of > 0.02 (voor slopes)
                    // 3. Confidence hoog genoeg (> 0.2)
                    val minDiff = when {
                        paramName.startsWith("bolus_perc") || paramName == "IOB_corr_perc" -> 0.5
                        paramName.contains("slope") -> 0.02
                        else -> 0.1
                    }

                    if (weightedAvg > 0.0 &&
                        abs(weightedAvg - currentPrefValue) > minDiff &&
                        (summary?.confidence ?: 0.0) > 0.2) {

                        currentValues[paramName] = weightedAvg
                        hasNewAdvice = true

                        android.util.Log.d("FCL_WEIGHTED_AVG",
                                           "Nieuwe advies voor $paramName: $currentPrefValue → $weightedAvg (diff: ${abs(weightedAvg - currentPrefValue)})")
                    } else {
                        // Gebruik huidige waarde als fallback (geen advies)
                        currentValues[paramName] = currentPrefValue
                    }
                }

                // ★★★ LOG ALLEEN ALS ER EEN NIEUW ADVIES IS ★★★
                if (!hasNewAdvice) {
                    android.util.Log.d("FCL_WEIGHTED_AVG", "Skipping log - geen nieuwe adviezen")
                    return
                }

                // ★★★ VERGELIJK MET LAATSTE GELOGDE WAARDEN ★★★
                val valuesChanged = currentValues.any { (key, value) ->
                    val lastValue = lastLoggedValues[key] ?: 0.0
                    Math.abs(value - lastValue) > 0.01
                }

                if (!valuesChanged) {
                    android.util.Log.d("FCL_WEIGHTED_AVG", "Skipping log - geen wijzigingen t.o.v. laatste log")
                    return
                }

                // ★★★ START LOGGING OP EEN APARTE THREAD ★★★
                Thread {
                    try {
                        val dateStr = now.toString("yyyy-MM-dd HH:mm:ss")
                        val parametersFile = File(Environment.getExternalStorageDirectory().absolutePath + "/Documents/AAPS/ANALYSE/WeightedAverages.csv")

                        // ★★★ HEADER MOET ALLEEN 1x GESCHREVEN WORDEN ★★★
                        val headerRow = "timestamp,bolus_perc_rising,phase_rising_slope,bolus_perc_plateau,phase_plateau_slope,IOB_corr_perc\n"

                        // Format de waarden
                        val valuesList = paramOrder.map { paramName ->
                            val value = currentValues[paramName] ?: 0.0
                            when {
                                paramName.startsWith("bolus_perc") || paramName == "IOB_corr_perc" ->
                                    String.format(Locale.US, "%.0f", value)
                                paramName.contains("slope") ->
                                    String.format(Locale.US, "%.2f", value)
                                else ->
                                    String.format(Locale.US, "%.2f", value)
                            }
                        }

                        val valuesToRecord = "$dateStr," + valuesList.joinToString(",") + "\n"

                        // ★★★ THREAD-SAFE FILE OPERATIONS ★★★
                        synchronized(parametersFile) {
                            if (!parametersFile.exists()) {
                                parametersFile.parentFile?.mkdirs()
                                parametersFile.createNewFile()
                                parametersFile.appendText(headerRow)
                            }

                            // ★★★ SCHRIJF ALLEEN EEN NIEUWE REGEL ALS HET ANDERS IS DAN DE LAATSTE ★★★
                            val existingLines = parametersFile.readLines()
                            if (existingLines.size >= 2) {
                                val lastLine = existingLines.last()
                                if (lastLine.startsWith(dateStr.substring(0, 16))) {
                                    // Zelfde minuut, vervang laatste regel
                                    val newLines = existingLines.dropLast(1) + valuesToRecord.trim()
                                    parametersFile.writeText(newLines.joinToString("\n") + "\n")
                                } else {
                                    // Nieuwe minuut, voeg toe
                                    parametersFile.appendText(valuesToRecord)
                                }
                            } else {
                                parametersFile.appendText(valuesToRecord)
                            }
                        }

                        // Update tracking
                        lastLogTime = now
                        lastLoggedValues = currentValues

                        // Log voor debugging
                        android.util.Log.d("FCL_WEIGHTED_AVG", "Nieuwe advieswaarden gelogd: $dateStr")

                    } catch (e: Exception) {
                        android.util.Log.e("FCL_WEIGHTED_AVG", "File logging error: ${e.message}")
                    }
                }.start()

            } catch (e: Exception) {
                android.util.Log.e("FCLMetrics", "Error in logWeightedAverages: ${e.message}")
            }
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


    // ★★★ VERVANG DEZE FUNCTIE ★★★
    private fun isParameterManuallyAdjusted(parameterName: String): Boolean {
        val lastAdjustment = parameterAdjustmentTimestamps[parameterName]

        // ★★★ VERKORT DE PERIODE NAAR 24 UUR (was 7 dagen) ★★★
        val isAdjusted = lastAdjustment != null && lastAdjustment.isAfter(DateTime.now().minusMinutes(15))

        // ★★★ DEBUG LOGGING ★★★
        if (isAdjusted) {
            val hoursAgo = Hours.hoursBetween(lastAdjustment, DateTime.now()).hours

        }

        return isAdjusted
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
            "IOB_corr_perc" to getCurrentParameterValueFromPrefs("IOB_corr_perc")
        )
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
                "IOB_corr_perc" -> preferences.get(IntKey.IOB_corr_perc).toDouble()
                else -> 0.0
            }
        } catch (e: Exception) {
            0.0
        }
    }


    // -------------------- RELATIVE-STEP OPTIMIZER MET CANDIDATE-LOGGING --------------------
    private var optimizerDebug = true

    private data class OptimizerParamsRaw(
        var risingPercRaw: Double,
        var plateauPercRaw: Double,
        var risingSlope: Double,
        var plateauSlope: Double
    )

    private fun rawPercToFraction(raw: Double): Double = if (raw > 3.0) raw / 100.0 else raw
    private fun fractionToRawIfPrefsUsePercent(frac: Double, prefsUsePercent: Boolean): Double = if (prefsUsePercent) frac * 100.0 else frac

    private fun evaluateParamsOnMealProxyRel(
        candidateRaw: OptimizerParamsRaw,
        originalMetrics: MealPerformanceMetrics
    ): MealPerformanceMetrics {
        // proxy model uses fraction for perc effects
        val rawBaselineR = getCurrentParameterValueFromPrefs("bolus_perc_rising")
        val rawBaselineP = getCurrentParameterValueFromPrefs("bolus_perc_plateau")
        val baselineR = rawPercToFraction(rawBaselineR)
        val baselineP = rawPercToFraction(rawBaselineP)

        val baselineRS = getCurrentParameterValueFromPrefs("phase_rising_slope")
        val baselinePS = getCurrentParameterValueFromPrefs("phase_plateau_slope")

        val candRFrac = rawPercToFraction(candidateRaw.risingPercRaw)
        val candPFrac = rawPercToFraction(candidateRaw.plateauPercRaw)

        var peak = originalMetrics.peakBG
        var tir = originalMetrics.timeInRangeDuringMeal
        var postHypo = originalMetrics.postMealHypo
        var maxIOB = originalMetrics.maxIOBDuringMeal

        val risingEffect = (candRFrac - baselineR)
        peak -= risingEffect * 2.5
        tir += risingEffect * 0.06

        val slopeEffect = (candidateRaw.risingSlope - baselineRS)
        peak += slopeEffect * 6.0
        tir -= slopeEffect * 0.04

        val plateauEffect = (candPFrac - baselineP)
        peak -= plateauEffect * 1.8
        tir += plateauEffect * 0.03

        val plateauSlopeEffect = (candidateRaw.plateauSlope - baselinePS)
        peak -= plateauSlopeEffect * 1.0
        tir += plateauSlopeEffect * 0.02

        // realism clamps
        peak = peak.coerceAtLeast(3.0)
        tir = tir.coerceIn(0.0, 1.0)
        postHypo = postHypo || (candRFrac > 0.6 && slopeEffect < -0.1)
        maxIOB = (originalMetrics.maxIOBDuringMeal + (candRFrac - baselineR) * 0.6).coerceAtLeast(0.0)

        return originalMetrics.copy(
            peakBG = peak,
            peakAboveTarget = max(0.0, peak - 10.5),
            postMealHypo = postHypo,
            timeInRangeDuringMeal = tir,
            maxIOBDuringMeal = maxIOB,
            wasSuccessful = !postHypo && peak <= 10.5
        )
    }
    // ---------------- SCORE FUNCTION MUST BE HERE ----------------
    private fun scoreMealOutcome(metrics: MealPerformanceMetrics): Double {
        val wTIR = 100.0
        val wPeak = 8.0
        val wHypo = 200.0
        val wIOB = 6.0

        val tirScore = metrics.timeInRangeDuringMeal.coerceIn(0.0, 1.0) * wTIR
        val peakPenalty = max(0.0, metrics.peakAboveTarget) * wPeak
        val hypoPenalty = if (metrics.postMealHypo) wHypo else 0.0
        val iobPenalty = max(0.0, metrics.maxIOBDuringMeal - 1.5) * wIOB

        return tirScore - peakPenalty - hypoPenalty - iobPenalty
    }



    // -------------------- RELATIVE-STEP ITERATIVE OPTIMIZER (vervanging) --------------------
    fun runIterativeParameterOptimizerForMeal(
        originalMetrics: MealPerformanceMetrics,
        maxIter: Int = 6,
        baseRelStep: Double = 0.02 // 2% relative step default
    ): List<ParameterAgressivenessAdvice> {

        // Haal huidige (raw) waarden uit prefs — jouw helper gebruikt percentages (int) vaak
        val rawR = getCurrentParameterValueFromPrefs("bolus_perc_rising")
        val rawP = getCurrentParameterValueFromPrefs("bolus_perc_plateau")
        val rawRS = getCurrentParameterValueFromPrefs("phase_rising_slope")
        val rawPS = getCurrentParameterValueFromPrefs("phase_plateau_slope")

        // Detecteer of prefs in procenten zijn (typisch >3 betekent daadwerkelijk percent, niet fractie)
        val prefsUsePercent = rawR > 10.0

        // Representatie van 'current' candidate (raw = dezelfde schaal als prefs)
        var current = OptimizerParamsRaw(rawR, rawP, rawRS, rawPS)

        // Startscore: beoordeel de bestaande meal-metrics
        var currentScore = scoreMealOutcome(originalMetrics)

        // Corrigeer en maak correcte stapreeks: 1..maxIter (dus maxIter niveaus), elk level = n * baseRelStep
        val steps = (1..maxIter).map { it * baseRelStep } // bv [0.02, 0.04, 0.06, ...]

        // Grenzen (raw units, i.e. 10..200 voor % parameters etc.)
        val boundsRaw = mapOf(
            "risingPercRaw" to Pair(10.0, 200.0),
            "plateauPercRaw" to Pair(10.0, 200.0),
            "risingSlope" to Pair(0.3, 3.0),
            "plateauSlope" to Pair(0.0, 1.0)
        )

        // Iterate over steplevels en per coord: try center / +step% / -step%
        for (step in steps) {
            var improved = true
            var iter = 0
            while (improved && iter < maxIter) {
                improved = false
                iter++
                val coords = listOf("risingPercRaw", "plateauPercRaw", "risingSlope", "plateauSlope")
                for (coord in coords) {
                    val origin = when (coord) {
                        "risingPercRaw" -> current.risingPercRaw
                        "plateauPercRaw" -> current.plateauPercRaw
                        "risingSlope" -> current.risingSlope
                        else -> current.plateauSlope
                    }
                    // Kandidaten als multiplicatieve stappen: origin * (1 ± step)
                    val candidatesRaw = if (coord == "risingPercRaw" || coord == "plateauPercRaw") {
                        // percentage-parameters: multiplicatieve wijziging is OK
                        listOf(origin, origin * (1.0 + step), origin * (1.0 - step))
                    } else {
                        // slope-parameters: additive step scaled by parameter range
                        val range = boundsRaw[coord]!!.second - boundsRaw[coord]!!.first
                        val absStep = range * step // step = 0.02 => 2% of range
                        listOf(origin, (origin + absStep), (origin - absStep))
                    }

                    for (candRaw in candidatesRaw) {
                        val candidate = OptimizerParamsRaw(current.risingPercRaw, current.plateauPercRaw, current.risingSlope, current.plateauSlope)
                        when (coord) {
                            "risingPercRaw" -> candidate.risingPercRaw = candRaw.coerceIn(boundsRaw["risingPercRaw"]!!.first, boundsRaw["risingPercRaw"]!!.second)
                            "plateauPercRaw" -> candidate.plateauPercRaw = candRaw.coerceIn(boundsRaw["plateauPercRaw"]!!.first, boundsRaw["plateauPercRaw"]!!.second)
                            "risingSlope" -> candidate.risingSlope = candRaw.coerceIn(boundsRaw["risingSlope"]!!.first, boundsRaw["risingSlope"]!!.second)
                            "plateauSlope" -> candidate.plateauSlope = candRaw.coerceIn(boundsRaw["plateauSlope"]!!.first, boundsRaw["plateauSlope"]!!.second)
                        }

                        // Simuleer meal outcome for candidate (proxy that expects raw percentages for percent-params)
                        val simMetrics = evaluateParamsOnMealProxyRel(candidate, originalMetrics)
                        val simScore = scoreMealOutcome(simMetrics)



                        // kleine verbeteringsdrempel (voorkom kleine ruis)
                        val relativeGainThreshold = 0.02 // 2% van max score
                        val absoluteMinGain = 0.01
                        val gainNeeded = kotlin.math.max(absoluteMinGain, currentScore * relativeGainThreshold)
                        if (simScore > currentScore + gainNeeded) {
                            currentScore = simScore
                            current = candidate
                            improved = true
                        }
                        if (simScore > currentScore + 0.08) {
                            currentScore = simScore
                            current = candidate
                            improved = true
                         }
                    }
                }
            }
        }

        // Bouw adviezen: voeg alleen toe als verschil > drempel (relatief bij percentages)
        val results = mutableListOf<ParameterAgressivenessAdvice>()
        fun addIfChanged(name: String, origRaw: Double, newRaw: Double, reason: String) {
            // ★★★ PAS 10% MAX CHANGE LIMIET TOE ★★★
            val maxChange = origRaw * getMaxChangeForParameter(name)
            val limitedNewRaw = newRaw.coerceIn(origRaw - maxChange, origRaw + maxChange)

            // Controleer of er nog een significant verschil is na beperking
            val threshold = when {
                name.startsWith("bolus_perc") -> (origRaw * 0.01).coerceAtLeast(0.5)
                name.contains("slope") -> 0.01
                else -> 0.05
            }

            if (kotlin.math.abs(origRaw - limitedNewRaw) > threshold) {
                // Bereken het werkelijke percentage verschil na beperking
                val actualChangePercent = ((limitedNewRaw - origRaw) / origRaw) * 100
                val originalChangePercent = ((newRaw - origRaw) / origRaw) * 100

                // Pas de reden aan als er beperking is toegepast
                val adjustedReason = if (limitedNewRaw != newRaw) {
                    "${reason} (origineel ${String.format("%+.1f", originalChangePercent)}%, beperkt tot ${String.format("%+.1f", actualChangePercent)}%)"
                } else {
                    reason
                }

                val conf = (0.25 + kotlin.math.min(0.70,
                                                   kotlin.math.abs(limitedNewRaw - origRaw) / (origRaw.coerceAtLeast(1.0) * 0.15))
                    ).coerceAtMost(0.95)

                results.add(
                    ParameterAgressivenessAdvice(
                        parameterName = name,
                        currentValue = origRaw,
                        recommendedValue = limitedNewRaw,
                        reason = adjustedReason,
                        confidence = conf,
                        expectedImprovement = "Verbeterde meal-score",
                        changeDirection = if (limitedNewRaw > origRaw) "INCREASE" else "DECREASE",
                        timestamp = DateTime.now()
                    )
                )
            }
        }

        addIfChanged("bolus_perc_rising", rawR, current.risingPercRaw, "Optimalisatie stijgfase")
        addIfChanged("bolus_perc_plateau", rawP, current.plateauPercRaw, "Optimalisatie plateaufase")
        addIfChanged("phase_rising_slope", rawRS, current.risingSlope, "Optimalisatie stijgingdetectie")
        addIfChanged("phase_plateau_slope", rawPS, current.plateauSlope, "Optimalisatie plateaudetectie")

        // ★★★ LOG ALLEEN ALS ER RESULTATEN ZIJN ★★★
        if (results.isNotEmpty()) {
            // ★★★ LOG GEWOGEN GEMIDDELDEN NA ITERATIEVE OPTIMALISATIE ★★★
            logWeightedAverages()
        }

        return results
    }

    // ★★★ MAALTIJD-GESTUURDE OPTIMALISATIE ★★★
    fun runMealBasedOptimization(mealMetrics: MealPerformanceMetrics): List<ParameterAgressivenessAdvice> {
        val advice = mutableListOf<ParameterAgressivenessAdvice>()

        // Huidige waarden ophalen
        val currentRisingPerc = getCurrentParameterValueFromPrefs("bolus_perc_rising")
        val currentPlateauPerc = getCurrentParameterValueFromPrefs("bolus_perc_plateau")
        val currentRisingSlope = getCurrentParameterValueFromPrefs("phase_rising_slope")
        val currentPlateauSlope = getCurrentParameterValueFromPrefs("phase_plateau_slope")
        val currentIOBCorr = getCurrentParameterValueFromPrefs("IOB_corr_perc")

        // Helper functie om 10% limiet toe te passen
        fun apply10PercentLimit(current: Double, proposed: Double, parameterName: String): Double {
            val maxChange = current * getMaxChangeForParameter(parameterName)
            return proposed.coerceIn(current - maxChange, current + maxChange)
        }

        // REGEL 1: Timing analyse - eerste bolus te laat?
        if (mealMetrics.timeToFirstBolus > 25) {
            // Bereken voorgestelde nieuwe waarde
            val proposedSlope = max(0.3, currentRisingSlope * 0.85) // Max 15% verlaging
            // Pas 10% limiet toe
            val limitedSlope = apply10PercentLimit(currentRisingSlope, proposedSlope,"phase_rising_slope" )

            val confidence = min(0.8, 1.0 - (mealMetrics.timeToFirstBolus / 60.0))

            // Pas de reden aan als er beperking is
            val reason = if (limitedSlope != proposedSlope) {
                "Eerste bolus ${mealMetrics.timeToFirstBolus}min na start. " +
                    "Drempel beperkt tot max 10% wijziging wegens veiligheidslimiet."
            } else {
                "Eerste bolus ${mealMetrics.timeToFirstBolus}min na start. Verlaag drempel voor snellere detectie."
            }

            advice.add(ParameterAgressivenessAdvice(
                parameterName = "phase_rising_slope",
                currentValue = currentRisingSlope,
                recommendedValue = limitedSlope,
                reason = reason,
                confidence = confidence,
                expectedImprovement = "Snellere maaltijdherkenning",
                changeDirection = "DECREASE",
                timestamp = DateTime.now()
            ))
        }

        // REGEL 2: Te hoge piek?
        if (mealMetrics.peakBG > 10.5) {
            val peakDelta = mealMetrics.peakBG - 10.5
            val increase = min(0.15, peakDelta * 0.05) // Max 15% verhoging
            val proposedPerc = min(120.0, currentRisingPerc * (1.0 + increase))
            // Pas 10% limiet toe
            val limitedPerc = apply10PercentLimit(currentRisingPerc, proposedPerc,"bolus_perc_rising")

            val confidence = min(0.9, 0.5 + (peakDelta * 0.1))

            val reason = if (limitedPerc != proposedPerc) {
                "Piek ${String.format("%.1f", mealMetrics.peakBG)}mmol/L > 10.5. " +
                    "Dosering beperkt tot max 10% wijziging wegens veiligheidslimiet."
            } else {
                "Piek ${String.format("%.1f", mealMetrics.peakBG)}mmol/L > 10.5. " +
                    "Verhoog dosering voor betere piekcontrole."
            }

            advice.add(ParameterAgressivenessAdvice(
                parameterName = "bolus_perc_rising",
                currentValue = currentRisingPerc,
                recommendedValue = limitedPerc,
                reason = reason,
                confidence = confidence,
                expectedImprovement = "Lagere maaltijdpieken",
                changeDirection = "INCREASE",
                timestamp = DateTime.now()
            ))
        }

        // REGEL 3: Snelle daling na piek?
        if (mealMetrics.rapidDeclineDetected) {
            // Voor plateau percentage: max 10% verlaging
            val proposedPlateauPerc = max(10.0, currentPlateauPerc * 0.9) // Max 10% verlaging
            val limitedPlateauPerc = apply10PercentLimit(currentPlateauPerc, proposedPlateauPerc,"bolus_perc_plateau")

            // Voor plateau slope: max 10% verhoging
            val proposedPlateauSlope = min(1.0, currentPlateauSlope * 1.1) // Max 10% verhoging
            val limitedPlateauSlope = apply10PercentLimit(currentPlateauSlope, proposedPlateauSlope,"phase_plateau_slope")

            // Plateau percentage advies
            val reasonPlateauPerc = if (limitedPlateauPerc != proposedPlateauPerc) {
                "Snelle daling na maaltijd. " +
                    "Plateau dosering beperkt tot max 10% wijziging wegens veiligheidslimiet."
            } else {
                "Snelle daling na maaltijd. Verlaag plateau dosering voor stabielere afbouw."
            }

            advice.add(ParameterAgressivenessAdvice(
                parameterName = "bolus_perc_plateau",
                currentValue = currentPlateauPerc,
                recommendedValue = limitedPlateauPerc,
                reason = reasonPlateauPerc,
                confidence = 0.7,
                expectedImprovement = "Stabilere afbouw IOB",
                changeDirection = "DECREASE",
                timestamp = DateTime.now()
            ))

            // Plateau slope advies
            val reasonPlateauSlope = if (limitedPlateauSlope != proposedPlateauSlope) {
                "Snelle daling suggereert te vroege overgang naar plateau. " +
                    "Drempel beperkt tot max 10% wijziging wegens veiligheidslimiet."
            } else {
                "Snelle daling suggereert te vroege overgang naar plateau. Verhoog drempel."
            }

            advice.add(ParameterAgressivenessAdvice(
                parameterName = "phase_plateau_slope",
                currentValue = currentPlateauSlope,
                recommendedValue = limitedPlateauSlope,
                reason = reasonPlateauSlope,
                confidence = 0.6,
                expectedImprovement = "Langere rising fase, minder risico op late hypo",
                changeDirection = "INCREASE",
                timestamp = DateTime.now()
            ))
        }

        // REGEL 4: Te hoge IOB aan einde maaltijd?
        if (mealMetrics.maxIOBDuringMeal > 3.0) {
            val proposedCorr = min(120.0, currentIOBCorr * 1.05)
            val limitedCorr = apply10PercentLimit(currentIOBCorr, proposedCorr,"IOB_corr_perc")

            val confidence = min(0.8, (mealMetrics.maxIOBDuringMeal - 2.0) / 3.0)

            val reason = if (limitedCorr != proposedCorr) {
                "IOB van ${String.format("%.1f", mealMetrics.maxIOBDuringMeal)}U te hoog aan einde maaltijd. " +
                    "Correctie beperkt tot max 10% wijziging wegens veiligheidslimiet."
            } else {
                "IOB van ${String.format("%.1f", mealMetrics.maxIOBDuringMeal)}U te hoog aan einde maaltijd."
            }

            advice.add(ParameterAgressivenessAdvice(
                parameterName = "IOB_corr_perc",
                currentValue = currentIOBCorr,
                recommendedValue = limitedCorr,
                reason = reason,
                confidence = confidence,
                expectedImprovement = "Betere IOB controle tijdens maaltijden",
                changeDirection = "INCREASE",
                timestamp = DateTime.now()
            ))
        }

        // Filter adviezen met te lage confidence
        return advice.filter { it.confidence >= 0.3 }
    }


    // ★★★ OPTIMALISATIE OVER LAATSTE N MAALTIJDEN ★★★
    fun runOptimizerOverLastNMealsAndAggregate(
        meals: List<MealPerformanceMetrics>,
        n: Int = OPTIMIZATION_MEALS_COUNT
    ): List<ParameterAgressivenessAdvice> {
        if (meals.isEmpty()) return emptyList()

        val lastMeals = meals.sortedByDescending { it.mealStartTime }.take(n)
        val allAdvice = mutableListOf<ParameterAgressivenessAdvice>()

        // Groepeer maaltijden per type voor specifieke analyse
        val mealsByType = lastMeals.groupBy { it.mealType }

        mealsByType.forEach { (mealType, typeMeals) ->
            if (typeMeals.size >= 2) { // Minimaal 2 maaltijden van dit type nodig
                // Bereken gemiddelde metrics voor dit maaltijdtype
                val avgMetrics = calculateAverageMealMetrics(typeMeals)

                // 1. Maaltijd-specifieke optimalisatie
                val mealBasedAdvice = runMealBasedOptimization(avgMetrics)
                allAdvice.addAll(mealBasedAdvice)

                // 2. Iteratieve optimalisatie (backup)
                try {
                    val iterativeAdvice = runIterativeParameterOptimizerForMeal(avgMetrics)
                    allAdvice.addAll(iterativeAdvice)
                } catch (e: Exception) {
                    // Fallback naar maaltijd-gebaseerde optimalisatie
                }
            }
        }

        // Aggregeer adviezen per parameter
        val aggregated = aggregateParameterAdvice(allAdvice)

        // Update parameter advies via bestaande flow
        aggregated.forEach { advice ->
            val paramAdvice = ParameterAdvice(
                parameterName = advice.parameterName,
                currentValue = advice.currentValue,
                recommendedValue = advice.recommendedValue,
                reason = advice.reason,
                confidence = advice.confidence,
                direction = advice.changeDirection
            )

            updateParameterAdviceInBackground(paramAdvice)
        }

        Handler(Looper.getMainLooper()).postDelayed({
                      logWeightedAverages()
                    }, 3000)



        return aggregated
    }

    // ★★★ HELPER: BEREKEN GEMIDDELDE MAALTIJD METRICS ★★★
    private fun calculateAverageMealMetrics(meals: List<MealPerformanceMetrics>): MealPerformanceMetrics {
        if (meals.isEmpty()) throw IllegalArgumentException("Lege maaltijdlijst")

        return MealPerformanceMetrics(
            mealId = "average_${DateTime.now().millis}",
            mealStartTime = DateTime.now(),
            mealEndTime = DateTime.now(),
            startBG = meals.map { it.startBG }.average(),
            peakBG = meals.map { it.peakBG }.average(),
            endBG = meals.map { it.endBG }.average(),
            timeToPeak = meals.map { it.timeToPeak }.average().toInt(),
            totalCarbsDetected = meals.map { it.totalCarbsDetected }.average(),
            totalInsulinDelivered = meals.map { it.totalInsulinDelivered }.average(),
            peakAboveTarget = meals.map { it.peakAboveTarget }.average(),
            timeAbove10 = meals.map { it.timeAbove10 }.average().toInt(),
            postMealHypo = meals.any { it.postMealHypo },
            timeInRangeDuringMeal = meals.map { it.timeInRangeDuringMeal }.average(),
            phaseInsulinBreakdown = emptyMap(),
            firstBolusTime = null,
            timeToFirstBolus = meals.map { it.timeToFirstBolus }.average().toInt(),
            maxIOBDuringMeal = meals.map { it.maxIOBDuringMeal }.average(),
            wasSuccessful = meals.count { it.wasSuccessful } > (meals.size / 2),
            mealType = meals.first().mealType,
            declineRate = meals.mapNotNull { it.declineRate }.average(),
            rapidDeclineDetected = meals.any { it.rapidDeclineDetected },
            virtualHypoScore = meals.map { it.virtualHypoScore }.average()
        )
    }

    // ★★★ HELPER: AGGRGEGEER ADVIEZEN ★★★
    private fun aggregateParameterAdvice(allAdvice: List<ParameterAgressivenessAdvice>): List<ParameterAgressivenessAdvice> {
        val paramToAdvices = mutableMapOf<String, MutableList<ParameterAgressivenessAdvice>>()

        allAdvice.forEach { advice ->
            paramToAdvices.getOrPut(advice.parameterName) { mutableListOf() }.add(advice)
        }

        val aggregated = mutableListOf<ParameterAgressivenessAdvice>()

        paramToAdvices.forEach { (paramName, advices) ->
            // Gewogen gemiddelde op basis van confidence en recency
            var weightSum = 0.0
            var weightedVal = 0.0
            var totalConfidence = 0.0
            var reasons = mutableListOf<String>()

            advices.forEach { advice ->
                // Recency weight: nieuwer = zwaarder
                val ageHours = max(0.0, Minutes.minutesBetween(advice.timestamp, DateTime.now()).minutes / 60.0)
                val recencyWeight = exp(-ageHours / 48.0) // Halvering na 48 uur
                val weight = advice.confidence * recencyWeight

                weightedVal += advice.recommendedValue * weight
                weightSum += weight
                totalConfidence += advice.confidence
                if (advice.reason.isNotBlank()) {
                    reasons.add(advice.reason)
                }
            }

            if (weightSum > 0 && advices.isNotEmpty()) {
                val avgValue = weightedVal / weightSum
                val currentValue = advices.first().currentValue

                val avgConfidence = totalConfidence / advices.size
                val uniqueReasons = reasons.distinct().take(3)

                // Alleen toevoegen als verschil significant is
                val threshold = when {
                    paramName.startsWith("bolus_perc") -> (currentValue * 0.02).coerceAtLeast(1.0) // 2% of min 1%
                    paramName.contains("slope") -> 0.02 // Absolute drempel
                    else -> 0.05
                }

                if (abs(avgValue - currentValue) > threshold && avgConfidence >= 0.3) {
                    aggregated.add(ParameterAgressivenessAdvice(
                        parameterName = paramName,
                        currentValue = currentValue,
                        recommendedValue = avgValue,
                        reason = if (uniqueReasons.isNotEmpty()) uniqueReasons.joinToString("; ") else "Gemiddelde optimalisatie over ${advices.size} adviezen",
                        confidence = avgConfidence,
                        expectedImprovement = "Gewogen optimalisatie",
                        changeDirection = if (avgValue > currentValue) "INCREASE" else "DECREASE",
                        timestamp = DateTime.now()
                    ))
                }
            }
        }

        return aggregated
    }




}