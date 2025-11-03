package app.aaps.plugins.aps.openAPSFCL

import android.annotation.SuppressLint
import android.content.Context
import app.aaps.core.data.time.T
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.utils.fabric.FabricPrivacy
import app.aaps.core.keys.*
import app.aaps.plugins.aps.openAPSFCL.FCLActivity
import app.aaps.plugins.aps.openAPSFCL.FCLLogging
import app.aaps.plugins.aps.openAPSFCL.FCLParameters
import app.aaps.plugins.aps.openAPSFCL.FCLMetrics
import app.aaps.plugins.aps.openAPSFCL.FCLPersistent
import app.aaps.plugins.aps.openAPSFCL.BGDataPoint
import app.aaps.plugins.aps.openAPSFCL.FCLMetrics.MealPerformanceMetrics
import app.aaps.plugins.aps.openAPSFCL.FCLMetrics.ParameterAdjustmentResult
import com.google.gson.Gson
import org.joda.time.DateTime
import org.joda.time.Hours
import org.joda.time.Minutes
import org.joda.time.Days
import kotlin.math.*
import javax.inject.Inject

class FCL @Inject constructor(
    private val profileUtil: ProfileUtil,
    private val fabricPrivacy: FabricPrivacy,
    private val preferences: Preferences,
    private val dateUtil: DateUtil,
    private val persistenceLayer: PersistenceLayer,
    private val context: Context
) {
    // ★★★ HARDCODE HYPO PROTECTION PARAMETERS ★★★
    companion object {
        // Hypo detection (mmol/L)
        const val HYPO_THRESHOLD_DAY = 4.3
        const val HYPO_THRESHOLD_NIGHT = 4.6
        const val HYPO_RECOVERY_BG_RANGE = 2.2

        // Recovery timing
        const val HYPO_RECOVERY_MINUTES = 70
        const val MIN_RECOVERY_DAYS = 2
        const val MAX_RECOVERY_DAYS = 5

        // Recovery behavior (0.0-1.0)
        const val HYPO_RECOVERY_AGGRESSIVENESS = 0.75
    }

    // Data classes (alleen die nodig zijn voor FCL)
    data class EnhancedInsulinAdvice(
        val dose: Double,
        val reason: String,
        val confidence: Double,
        val predictedValue: Double? = null,
        val mealDetected: Boolean = false,
        val detectedCarbs: Double = 0.0,
        val shouldDeliverBolus: Boolean = false,
        val phase: String = "auto",
        val learningMetrics: FCLLearningEngine.LearningMetrics? = null,
        val reservedDose: Double = 0.0,
        val carbsOnBoard: Double = 0.0,
        val Target_adjust: Double = 0.0,
        val ISF_adjust: Double = 0.0,
        val activityLog: String = "",
        val resistentieLog: String = "",
        val effectiveISF: Double = 0.0,
        val MathBolusAdvice: String = "",
        val mathPhase: String = "uncertain",
        val mathSlope: Double = 0.0,
        val mathAcceleration: Double = 0.0,
        val mathConsistency: Double = 0.0,
        val mathDirectionConsistency: Double = 0.0,
        val mathMagnitudeConsistency: Double = 0.0,
        val mathPatternConsistency: Double = 0.0,
        val mathDataPoints: Int = 0,
        val debugLog: String = ""
    )

    data class ActiveCarbs(
        val timestamp: DateTime,
        var totalCarbs: Double,
        var absorbedCarbs: Double = 0.0,
        val tau: Double = 40.0
    ) {
        fun getActiveCarbs(now: DateTime): Double {
            val minutes = (now.millis - timestamp.millis) / 60000.0
            val absorbed = totalCarbs * (1 - exp(-minutes / tau))
            return absorbed.coerceAtMost(totalCarbs)
        }

        fun getRemainingCarbs(now: DateTime): Double {
            return totalCarbs - getActiveCarbs(now)
        }
    }

    data class PredictionResult(
        val value: Double,
        val trend: Double,
        val mealDetected: Boolean,
        val mealInProgress: Boolean,
        val phase: String
    )

    data class InsulinAdvice(
        val dose: Double,
        val reason: String,
        val confidence: Double,
        val predictedValue: Double? = null,
        val mealDetected: Boolean = false,
        val phase: String = "stable"
    )

    data class TrendAnalysis(
        val recentTrend: Double,
        val shortTermTrend: Double,
        val acceleration: Double
    )

    data class RobustTrendAnalysis(
        val firstDerivative: Double,
        val secondDerivative: Double,
        val consistency: Double,
        val phase: String = "uncertain"
    )

    data class PhaseTransitionResult(
        val phase: String,
        val transitionFactor: Double,
        val debugInfo: String
    )

    data class MathematicalBolusAdvice(
        val immediatePercentage: Double,
        val reservedPercentage: Double,
        val reason: String
    )

    // ★★★ DISPLAY DATA CLASS ★★★
    data class ParameterAdviceDisplay(
        val parameterName: String,
        val currentValue: String,
        val recommendedValue: String,
        val reason: String,
        val confidence: Int,
        val expectedImprovement: String,
        val changeDirection: String
    )

    private data class UnifiedCarbsResult(
        val detectedCarbs: Double,
        val detectionReason: String,
        val confidence: Double
    )

    private data class DynamicFactors(
        val trendFactor: Double,
        val safetyFactor: Double,
        val confidenceFactor: Double
    )

    // Configuration properties
    private var currentBg: Double = 5.5
    private var currentCR: Double = 7.0
    private var currentISF: Double = 8.0
    private var Target_Bg: Double = 5.2

    private val minIOBForEffect = 0.3
    private val insulinSensitivityFactor = 3.0
    private val dailyReductionFactor = 0.7

    // State tracking
    private var lastMealTime: DateTime? = null
    private var mealInProgress = false
    private var peakDetected = false
    private var mealDetectionState = MealDetectionState.NONE

    // ★★★  State tracking voor metrics en advies ★★★
    private var lastMetricsUpdate: DateTime? = null
    private var lastAdviceUpdate: DateTime? = null
    private val METRICS_UPDATE_INTERVAL = 60 // minuten
    private var cachedParameterSummary: String = "Nog niet berekend"
    private var lastParameterSummaryUpdate: DateTime? = null

    // Debug variabelen
    private var lastMealDetectionDebug: String = ""
    private var lastCOBDebug: String = ""
    private var lastReservedBolusDebug: String = ""
    private var recentDataForAnalysis: List<BGDataPoint> = emptyList()

    // Reserved bolus tracking
    private var pendingReservedBolus: Double = 0.0
    private var pendingReservedCarbs: Double = 0.0
    private var pendingReservedTimestamp: DateTime? = null
    private var pendingReservedPhase: String = "stable"

    // Progressieve bolus tracking
    private val activeMeals = mutableListOf<ActiveCarbs>()

    // Stappen variabelen
    private var currentStappenPercentage: Double = 100.0
    private var currentStappenTargetAdjust: Double = 0.0
    private var currentStappenLog: String = "--- Steps activity ---\nStep counter switched OFF"
    // ★★★ ROBUUSTE STAPPEN VARIABELEN ★★★
    private lateinit var fclActivity: FCLActivity
    private var lastStepDataTime: Long = 0
    private var stepDataQuality: String = "UNKNOWN"
    private var steps5min: Int = 0
    private var steps30min: Int = 0


    private var DetermineStap5min: Int = 1
    private var DetermineStap30min: Int = 1

    // Carbs tracking
    private var lastDetectedCarbs: Double = 0.0
    private var lastCarbsOnBoard: Double = 0.0
    private var lastCOBUpdateTime: DateTime? = null

    // Wiskundige fase state tracking
    private var lastRobustTrends: RobustTrendAnalysis? = null
    private var lastMathBolusAdvice: String = ""
    private var lastMathAnalysisTime: DateTime? = null
    private var lastPhaseTransitionFactor: Double = 1.0

    // Bolus tracking
    private var lastDeliveredBolus: Double = 0.0
    private var lastBolusReason: String = ""
    private var lastBolusTime: DateTime? = null
    private val MEAL_DETECTION_COOLDOWN_MINUTES = 45
    private var lastCalculatedBolus: Double = 0.0
    private var lastShouldDeliver: Boolean = false

    // CSV logging
    private var lastCleanupCheck: DateTime? = null
    private val CLEANUP_CHECK_INTERVAL = 24 * 60 * 60 * 1000L

    // Helpers
    private val resistanceHelper = FCLResistance(preferences, persistenceLayer, context)
    private val activityHelper = FCLActivity(preferences, context)
    private val loggingHelper = FCLLogging(context)
    private val parametersHelper = FCLParameters(preferences)
    private val metricsHelper = FCLMetrics(context, preferences)
    private val persistentHelper = FCLPersistent(preferences, context)
    private val learningEngine = FCLLearningEngine(preferences, context)

    // Storage property voor compatibiliteit
    private val storage: FCLLearningEngine.FCLStorage
        get() = learningEngine.getStorage()

    // Learning profile property voor compatibiliteit
    private var learningProfile: FCLLearningEngine.FCLLearningProfile
        get() = learningEngine.getLearningProfile()
        set(value) { /* Read-only in FCL */ }

    init {
        resetLearningDataIfNeeded()
        initializeActivitySystem()
        // Robuust laden van learning profile
        try {
            val loadedProfile = storage.loadLearningProfile()
            learningProfile = loadedProfile ?: FCLLearningEngine.FCLLearningProfile()
        } catch (e: Exception) {
            learningProfile = FCLLearningEngine.FCLLearningProfile()
        }


        processPendingLearningUpdates()
        processPendingCorrectionUpdates()
        detectPeaksFromHistoricalData()
        processFallbackLearning()
    }

    private fun initializeActivitySystem() {
        try {
            fclActivity = FCLActivity(preferences, context)
            currentStappenPercentage = 100.0
            currentStappenTargetAdjust = 0.0
            currentStappenLog = "Activity system initialized"
        } catch (e: Exception) {
            currentStappenPercentage = 100.0
            currentStappenTargetAdjust = 0.0
            currentStappenLog = "Activity system init failed: ${e.message}"
        }
    }

    // ★★★ UNIFORME CARB DETECTIE METHODE ★★★
    private fun calculateUnifiedCarbsDetection(
        historicalData: List<BGDataPoint>,
        robustTrends: RobustTrendAnalysis,
        currentBG: Double,
        targetBG: Double,
        currentIOB: Double,
        maxIOB: Double,
        effectiveCR: Double
    ): UnifiedCarbsResult {
        if (historicalData.size < 4) return UnifiedCarbsResult(0.0, "Insufficient data", 0.0)

        val recent = historicalData.takeLast(4)
        val bg10minAgo = recent.getOrNull(recent.size - 3)?.bg ?: currentBG
        val delta10 = currentBG - bg10minAgo
        val slope10 = delta10 / 10.0 * 60.0 // mmol/L per uur

        // ★★★ BASIS CARBS BEREKENING ★★★
        var detectedCarbs = 0.0
        var detectionReason = "No carb detection"
        var confidence = 0.0

        // 1. Onverklaarde stijging
        val predictedRiseFromCOB = estimateRiseFromCOB(
            effectiveCR = effectiveCR,
            tauAbsorptionMinutes = preferences.get(IntKey.tau_absorption_minutes),
            detectionWindowMinutes = 45
        )
        val unexplainedDelta = delta10 - predictedRiseFromCOB
        val mealDetectionSensitivity = preferences.get(DoubleKey.meal_detection_sensitivity)

        // 2. Wiskundige trend-based detectie
        val mathCarbs = calculateMathematicalCarbsComponent(robustTrends, slope10)

        // 3. COB-gecorrigeerde detectie
        val cobAdjustedCarbs = calculateCOBAdjustedCarbs(unexplainedDelta, effectiveCR, mealDetectionSensitivity)

// ★★★ BESLISSINGSLOGICA - VERBETERDE VROEGE DETECTIE ★★★
// ★★★ NIEUWE DREMPELS VOOR VROEGERE HERKENNING ★★★
        val earlyRiseThreshold = 0.5  // was impliciet 0.7 via mealDetectionSensitivity * 0.7
        val moderateRiseThreshold = 1.0 // was 1.5

        when {
            // Zeer sterke stijging - prioriteit 1 (ONGEWIJZIGD)
            slope10 > 5.0 -> {
                detectedCarbs = slope10 * 12.0
                detectionReason = "Rapid rise detection: slope=${"%.1f".format(slope10)} mmol/L/h"
                confidence = 0.8
            }

            // Hoge wiskundige carbs met goede consistentie - prioriteit 2 (ONGEWIJZIGD)
            mathCarbs > 20.0 && robustTrends.consistency > 0.6 -> {
                detectedCarbs = mathCarbs
                detectionReason = "Mathematical detection: ${robustTrends.phase}, slope=${"%.1f".format(robustTrends.firstDerivative)}"
                confidence = robustTrends.consistency
            }

            // ★★★ NIEUW: VROEGE STIJGING DETECTIE - LAGERE DREMPEL ★★★
            slope10 > moderateRiseThreshold && currentBG > targetBG + 0.5 && robustTrends.consistency > 0.3 -> {
                detectedCarbs = slope10 * 10.0  // Verhoogde multiplier voor vroegere respons
                detectionReason = "Early rise detection: slope=${"%.1f".format(slope10)} mmol/L/h"
                confidence = 0.6
            }

            // Onverklaarde stijging boven drempel - prioriteit 4 (LAGERE DREMPEL)
            unexplainedDelta > mealDetectionSensitivity * 0.5 -> {  // was 0.7
                detectedCarbs = cobAdjustedCarbs
                detectionReason = "Unexplained rise: ${"%.1f".format(unexplainedDelta)} mmol/L"
                confidence = 0.6
            }

            // ★★★ NIEUW: ZEER VROEGE STIJGING BIJ CONSISTENT PATROON (GECORRIGEERD) ★★★
            historicalData.size >= 4 && hasRecentRise(historicalData, 2) && currentBG > targetBG + 0.8 -> {
                detectedCarbs = 15.0 + (slope10 * 5.0)  // Basis 15g + extra voor slope
                detectionReason = "Very early consistent rise pattern"
                confidence = 0.5
            }

            // Matige stijging met consistente trend - prioriteit 6 (LAGERE DREMPEL)
            slope10 > earlyRiseThreshold && currentBG > targetBG + 0.3 && robustTrends.consistency > 0.4 -> {
                detectedCarbs = slope10 * 8.0
                detectionReason = "Moderate rise with consistent trend"
                confidence = 0.5
            }

            else -> {
                detectedCarbs = 0.0
                detectionReason = "No significant carb detection signals"
                confidence = 0.0
            }
        }

        // ★★★ IOB-BASED REDUCTIE ★★★
        val iobRatio = currentIOB / maxIOB
        val iobCarbReduction = when {
            iobRatio > 0.8 -> 0.3
            iobRatio > 0.6 -> 0.5
            iobRatio > 0.4 -> 0.7
            iobRatio > 0.2 -> 0.85
            else -> 1.0
        }

        detectedCarbs *= iobCarbReduction

        // ★★★ CARB PERCENTAGE INSTELLING TOEPASSEN ★★★
        detectedCarbs *= preferences.get(IntKey.carb_percentage).toDouble() / 100.0

        // ★★★ DYNAMISCHE BEGRENSING OP BASIS VAN SLOPE ★★★
        val maxCarbs = when {
            robustTrends.firstDerivative > 8.0 -> 50.0
            robustTrends.firstDerivative > 5.0 -> 40.0
            robustTrends.firstDerivative > 3.0 -> 30.0
            else -> 20.0
        } * (preferences.get(IntKey.carb_percentage).toDouble() / 100.0)

        detectedCarbs = detectedCarbs.coerceIn(0.0, maxCarbs)

        // ★★★ CONFIDENCE AFSTEMMING ★★★
        confidence *= when {
            detectedCarbs > 30.0 -> 0.9
            detectedCarbs > 20.0 -> 0.8
            detectedCarbs > 10.0 -> 0.7
            else -> 0.5
        }
        if (iobCarbReduction < 1.0) {
            detectionReason += " (IOB reduced: ${(iobCarbReduction * 100).toInt()}%)"
        }
        return UnifiedCarbsResult(detectedCarbs, detectionReason, confidence)
    }

    // ★★★ HULPFUNCTIES VOOR UNIFORME METHODE ★★★
    private fun calculateMathematicalCarbsComponent(
        robustTrends: RobustTrendAnalysis,
        slope10: Double
    ): Double {
        return when (robustTrends.phase) {
            "early_rise" -> 10.0 + (robustTrends.firstDerivative * 7.0).coerceAtMost(30.0)
            "mid_rise" -> 5.0 + (robustTrends.firstDerivative * 5.0).coerceAtMost(25.0)
            "late_rise" -> 0.0 + (robustTrends.firstDerivative * 4.0).coerceAtMost(20.0)
            else -> 0.0
        }
    }

    private fun calculateCOBAdjustedCarbs(
        unexplainedDelta: Double,
        effectiveCR: Double,
        mealDetectionSensitivity: Double
    ): Double {
        return unexplainedDelta * effectiveCR * preferences.get(IntKey.carb_percentage).toDouble() / 100.0
    }

    // ★★★ HULPFUNCTIES VOOR VERBETERDE FASE DETECTIE ★★★
    private fun calculateSlopeHistory(data: List<BGDataPoint>): List<Double> {
        val slopes = mutableListOf<Double>()
        for (i in 1 until data.size) {
            val timeDiff = Minutes.minutesBetween(data[i-1].timestamp, data[i].timestamp).minutes / 60.0
            if (timeDiff > 0) {
                slopes.add((data[i].bg - data[i-1].bg) / timeDiff)
            }
        }
        return slopes
    }

    private fun hasConsistentRise(slopes: List<Double>, minRising: Int): Boolean {
        if (slopes.size < minRising) return false
        val risingCount = slopes.count { it > 0.1 }
        return risingCount >= minRising
    }

    private fun calculateSlopeConsistency(slopes: List<Double>): Double {
        if (slopes.size < 2) return 0.0
        val mean = slopes.average()
        val variance = slopes.map { (it - mean) * (it - mean) }.average()
        return exp(-variance * 2.0).coerceIn(0.0, 1.0)
    }

    private fun getPhasePercentage(phase: String): Double {
        return when (phase) {
            "early_rise" -> preferences.get(IntKey.bolus_perc_early).toDouble() / 100.0
            "mid_rise" -> preferences.get(IntKey.bolus_perc_mid).toDouble() / 100.0
            "late_rise" -> preferences.get(IntKey.bolus_perc_late).toDouble() / 100.0
            else -> 1.0
        }
    }

    // ★★★ PUBLIC INTERFACE FUNCTIES ★★★
    fun setCurrentCR(value: Double) { currentCR = value }
    fun setCurrentISF(value: Double) { currentISF = value }
    fun setTargetBg(value: Double) { Target_Bg = value }
    fun set5minStap(value: Int) {
        DetermineStap5min = value
        lastStepDataTime = System.currentTimeMillis()
        updateActivityFromSteps()
    }
    fun set30minStap(value: Int) {
        DetermineStap30min = value
        lastStepDataTime = System.currentTimeMillis()
        updateActivityFromSteps()
    }




    // ★★★ STAPPEN BEREKENING ★★★
    fun berekenStappenAdjustment() {
        val result = activityHelper.berekenStappenAdjustment(DetermineStap5min, DetermineStap30min)
        currentStappenPercentage = result.percentage
        currentStappenTargetAdjust = result.targetAdjust
        currentStappenLog = result.log
    }



    // ★★★ RESISTENTIE MANAGEMENT ★★★
    private fun updateResistentieIndienNodig() {
        resistanceHelper.updateResistentieIndienNodig(isNachtTime())
    }

    // ★★★ LEARNING MANAGEMENT ★★★
    private fun resetLearningDataIfNeeded() {
        learningEngine.resetLearningDataIfNeeded()
    }

    private fun processPendingLearningUpdates() {
        learningEngine.processPendingLearningUpdates()
    }

    private fun processPendingCorrectionUpdates() {
        learningEngine.processPendingCorrectionUpdates()
    }

    private fun detectPeaksFromHistoricalData() {
        val historicalData = storage.loadPeakDetectionData()
        if (historicalData.isEmpty()) return

        val pendingUpdates = storage.loadPendingLearningUpdates()
        if (pendingUpdates.isEmpty()) return

        // Gebruik de nieuwe geavanceerde piekdetectie
        val peaks = advancedPeakDetection(
            historicalData.map {
                BGDataPoint(timestamp = it.timestamp, bg = it.bg, iob = 0.0)
            }
        )

        if (peaks.isEmpty()) return

        val processed = mutableListOf<FCLLearningEngine.LearningUpdate>()

        // Loop over pending learning updates
        for (update in ArrayList(pendingUpdates)) {
            var bestMatch: FCLLearningEngine.PeakDetectionData? = null
            var bestScore = 0.0

            for (peak in peaks) {
                val minutesDiff = Minutes.minutesBetween(update.timestamp, peak.timestamp).minutes
                val timeScore = when {
                    minutesDiff in 45..150 -> 1.0  // Optimale timing
                    minutesDiff in 30..180 -> 0.7  // Acceptabele timing
                    else -> 0.0
                }

                // BG patroon matching
                val bgScore = 1.0 - min(1.0, abs(peak.bg - update.expectedPeak) / 5.0)
                val totalScore = timeScore * 0.6 + bgScore * 0.4

                if (totalScore > bestScore && totalScore > 0.5) {
                    bestScore = totalScore
                    bestMatch = peak
                }
            }

            bestMatch?.let { peak ->
                try {
                    updateLearningFromMealResponse(
                        detectedCarbs = update.detectedCarbs,
                        givenDose = update.givenDose,
                        predictedPeak = update.expectedPeak,
                        actualPeak = peak.bg,
                        bgStart = update.startBG,
                        bgEnd = peak.bg,
                        mealType = update.mealType,
                        startTimestamp = update.timestamp,
                        peakTimestamp = peak.timestamp
                    )
                    processed.add(update)
                } catch (ex: Exception) {
                    // Logging
                }
            }
        }

        if (processed.isNotEmpty()) {
            storage.clearPendingLearningUpdates()
            val remainingUpdates = pendingUpdates - processed.toSet()
            remainingUpdates.forEach { storage.savePendingLearningUpdate(it) }
        }
    }

    // ★★★ NIEUWE GEAVANCEERDE PIEKDETECTIE ★★★
    private fun advancedPeakDetection(historicalData: List<BGDataPoint>): List<FCLLearningEngine.PeakDetectionData> {
        if (historicalData.size < 5) return emptyList()

        val smoothedData = applyExponentialSmoothing(historicalData, alpha = 0.3)
        val peaks = mutableListOf<FCLLearningEngine.PeakDetectionData>()

        for (i in 2 until smoothedData.size - 2) {
            val window = smoothedData.subList(i-2, i+3)
            val (isPeak, confidence) = analyzePeakCharacteristics(window)

            if (isPeak && confidence > 0.7) {
                val originalData = historicalData[i]
                peaks.add(FCLLearningEngine.PeakDetectionData(
                    timestamp = originalData.timestamp,
                    bg = originalData.bg,
                    trend = calculateTrendBetweenPoints(historicalData[i-1], historicalData[i]),
                    acceleration = calculateAcceleration(historicalData, 2),
                    isPeak = true
                ))
            }
        }

        return filterFalsePeaks(peaks, historicalData)
    }

    private fun applyExponentialSmoothing(data: List<BGDataPoint>, alpha: Double): List<BGDataPoint> {
        if (data.isEmpty()) return emptyList()

        val smoothed = mutableListOf<BGDataPoint>()
        var smoothedBG = data.first().bg

        data.forEach { point ->
            smoothedBG = alpha * point.bg + (1 - alpha) * smoothedBG
            smoothed.add(point.copy(bg = smoothedBG))
        }

        return smoothed
    }

    private fun analyzePeakCharacteristics(window: List<BGDataPoint>): Pair<Boolean, Double> {
        if (window.size < 5) return Pair(false, 0.0)

        val preRise = window[2].bg - window[0].bg
        val postDecline = window[2].bg - window[4].bg

        // Vermijd deling door nul
        val maxChange = max(preRise, postDecline).coerceAtLeast(0.1)
        val symmetry = abs(preRise - postDecline) / maxChange

        // Echte pieken hebben symmetrische opbouw en afbouw
        val isSymmetric = symmetry < 0.6
        val hasAdequateRise = preRise > 1.2  // Minstens 1.2 mmol/L stijging
        val hasAdequateDecline = postDecline > 0.6  // Minstens 0.6 mmol/L daling

        val confidence = when {
            isSymmetric && hasAdequateRise && hasAdequateDecline -> 0.9
            isSymmetric && hasAdequateRise -> 0.75
            hasAdequateRise && hasAdequateDecline -> 0.7
            else -> 0.3
        }

        val isPeak = confidence > 0.6 && window[2].bg > window[1].bg && window[2].bg > window[3].bg

        return Pair(isPeak, confidence)
    }

    private fun filterFalsePeaks(
        detectedPeaks: List<FCLLearningEngine.PeakDetectionData>,
        historicalData: List<BGDataPoint>
    ): List<FCLLearningEngine.PeakDetectionData> {
        val filtered = mutableListOf<FCLLearningEngine.PeakDetectionData>()
        val timeThreshold = 30 // minuten tussen pieken

        detectedPeaks.forEach { peak ->
            val isTooClose = filtered.any { existing ->
                Minutes.minutesBetween(existing.timestamp, peak.timestamp).minutes < timeThreshold
            }

            val isSignificant = peak.bg > (historicalData.firstOrNull()?.bg ?: 0.0) + 1.0

            if (!isTooClose && isSignificant) {
                filtered.add(peak)
            }
        }

        return filtered
    }

    private fun processFallbackLearning() {
        val pendingUpdates = storage.loadPendingLearningUpdates()
        if (pendingUpdates.isEmpty()) return

        val now = DateTime.now()
        val historicalData = storage.loadPeakDetectionData()
        if (historicalData.size < 5) return

        val processed = mutableListOf<FCLLearningEngine.LearningUpdate>()

        for (update in ArrayList(pendingUpdates)) {
            val minutesSinceUpdate = Minutes.minutesBetween(update.timestamp, now).minutes

            if (minutesSinceUpdate > 120 && minutesSinceUpdate < 360) {
                // zoek hoogste BG in conservatieve window (60..180)
                val bgWindow = historicalData.filter { data ->
                    val m = Minutes.minutesBetween(update.timestamp, data.timestamp).minutes
                    m in 60..180
                }
                val peakEntry = bgWindow.maxByOrNull { it.bg }

                val actualPeak = peakEntry?.bg ?: (update.startBG + 3.0)
                val peakTimestamp = peakEntry?.timestamp ?: update.timestamp.plusMinutes(90)

                try {
                    updateLearningFromMealResponse(
                        detectedCarbs = update.detectedCarbs,
                        givenDose = update.givenDose,
                        predictedPeak = update.expectedPeak,
                        actualPeak = actualPeak,
                        bgStart = update.startBG,
                        bgEnd = actualPeak,
                        mealType = update.mealType,
                        startTimestamp = update.timestamp,
                        peakTimestamp = peakTimestamp
                    )
                    processed.add(update)
                } catch (ex: Exception) {
                    // Logging
                }
            }
        }

        if (processed.isNotEmpty()) {
            storage.clearPendingLearningUpdates()
            val remainingUpdates = pendingUpdates - processed.toSet()
            remainingUpdates.forEach { storage.savePendingLearningUpdate(it) }
        }
    }

    // ★★★ LEARNING FUNCTIES ★★★
    private fun updateLearningFromMealResponse(
        detectedCarbs: Double,
        givenDose: Double,
        predictedPeak: Double,
        actualPeak: Double,
        bgStart: Double,
        bgEnd: Double,
        mealType: String,
        startTimestamp: DateTime,
        peakTimestamp: DateTime
    ) {
        learningEngine.updateLearningFromMealResponse(
            detectedCarbs, givenDose, predictedPeak, actualPeak,
            bgStart, bgEnd, mealType, startTimestamp, peakTimestamp, currentCR
        )
    }

    private fun storeMealForLearning(
        detectedCarbs: Double,
        givenDose: Double,
        startBG: Double,
        expectedPeak: Double,
        mealType: String
    ) {
        learningEngine.storeMealForLearning(detectedCarbs, givenDose, startBG, expectedPeak, mealType)
    }

    private fun getHypoAdjustedMealFactor(mealType: String, hour: Int): Double {
        return learningEngine.getHypoAdjustedMealFactor(mealType, hour)
    }

    private fun updateActivityFromSteps() {
        try {
            if (!preferences.get(BooleanKey.stappenAanUit)) {
                currentStappenPercentage = 100.0
                currentStappenTargetAdjust = 0.0
                currentStappenLog = "Step counter switched OFF"
                stepDataQuality = "DISABLED"
                return
            }

            val minutesSinceUpdate = (System.currentTimeMillis() - lastStepDataTime) / (1000 * 60)
            stepDataQuality = when {
                minutesSinceUpdate > 30 -> "STALE (>30min)"
                minutesSinceUpdate > 15 -> "AGED (>15min)"
                minutesSinceUpdate > 5 -> "RECENT"
                else -> "FRESH"
            }

            val activityResult = fclActivity.berekenStappenAdjustment(
                DetermineStap5min = DetermineStap5min,
                DetermineStap30min = DetermineStap30min,
                lastStepUpdateTime = lastStepDataTime
            )

            currentStappenPercentage = activityResult.percentage
            currentStappenTargetAdjust = activityResult.targetAdjust
            currentStappenLog = activityResult.log + " | Data: $stepDataQuality"

            // ★★★ GECORRIGEERDE LOGGING - VERWIJDERD ★★★
            // We verwijderen deze logging omdat de benodigde parameters niet beschikbaar zijn
            // in deze context. De activiteit wordt gelogd via de normale FCL logging.

        } catch (e: Exception) {
            currentStappenPercentage = 100.0
            currentStappenTargetAdjust = 0.0
            currentStappenLog = "Activity update error: ${e.message}"
            stepDataQuality = "UPDATE_ERROR"
        }
    }

    // ★★★ DAG/NACHT HELPER FUNCTIES ★★★
    private fun isNachtTime(): Boolean {
        val now = DateTime.now()
        val currentHour = now.hourOfDay
        val currentMinute = now.minuteOfHour
        val currentDayOfWeek = now.dayOfWeek

        val isWeekend = isWeekendDay(currentDayOfWeek)
        val ochtendStart = preferences.get(StringKey.OchtendStart)
        val ochtendStartWeekend = preferences.get(StringKey.OchtendStartWeekend)
        val nachtStart = preferences.get(StringKey.NachtStart)

        val (ochtendStartUur, ochtendStartMinuut) = if (isWeekend) {
            parseTime(ochtendStartWeekend)
        } else {
            parseTime(ochtendStart)
        }
        val (nachtStartUur, nachtStartMinuut) = parseTime(nachtStart)

        return isInTijdBereik(
            currentHour, currentMinute,
            nachtStartUur, nachtStartMinuut,
            ochtendStartUur, ochtendStartMinuut
        )
    }

    private fun isWeekendDay(dayOfWeek: Int): Boolean {
        val dayMapping = mapOf(
            1 to "ma", 2 to "di", 3 to "wo", 4 to "do", 5 to "vr", 6 to "za", 7 to "zo"
        )
        val currentDayAbbr = dayMapping[dayOfWeek] ?: return false
        val weekendDagen = preferences.get(StringKey.WeekendDagen)
        return weekendDagen.split(",").any {
            it.trim().equals(currentDayAbbr, ignoreCase = true)
        }
    }

    private fun parseTime(timeStr: String): Pair<Int, Int> {
        return try {
            val parts = timeStr.split(":")
            val uur = parts[0].toInt()
            val minuut = if (parts.size > 1) parts[1].toInt() else 0
            Pair(uur, minuut)
        } catch (e: Exception) {
            Pair(6, 0)
        }
    }

    private fun isInTijdBereik(
        hh: Int, mm: Int,
        startUur: Int, startMinuut: Int,
        eindUur: Int, eindMinuut: Int
    ): Boolean {
        val startInMinuten = startUur * 60 + startMinuut
        val eindInMinuten = eindUur * 60 + eindMinuut
        val huidigeTijdInMinuten = hh * 60 + mm

        return if (eindInMinuten < startInMinuten) {
            huidigeTijdInMinuten >= startInMinuten || huidigeTijdInMinuten < eindInMinuten
        } else {
            huidigeTijdInMinuten in startInMinuten..eindInMinuten
        }
    }

    private fun getCurrentBolusAggressiveness(): Double {
        return if (isNachtTime()) preferences.get(IntKey.bolus_perc_night).toDouble() else preferences.get(IntKey.bolus_perc_day).toDouble()
    }

    private fun gethypoThreshold(): Double {
        return if (isNachtTime()) HYPO_THRESHOLD_NIGHT else HYPO_THRESHOLD_DAY
    }

    // ★★★ SAFETY CHECK FUNCTIES ★★★
    private fun canDetectMealAboveTarget(
        currentBG: Double,
        targetBG: Double,
        trends: TrendAnalysis,
        currentIOB: Double,
        MaxIOB: Double
    ): Boolean {
        return when {
            currentBG > targetBG + 5.0 -> false
            trends.recentTrend < -2.0 -> false
            currentIOB > MaxIOB * 0.8 -> false
            else -> true
        }
    }

    fun estimateRiseFromCOB(
        effectiveCR: Double,
        tauAbsorptionMinutes: Int,
        detectionWindowMinutes: Int = 60
    ): Double {
        val now = DateTime.now()
        val remainingCarbs = activeMeals.sumOf { it.getRemainingCarbs(now) }

        // omzetten koolhydraten -> mmol/L stijging
        val mmolPerGram = if (effectiveCR > 0.0) (1.0 / effectiveCR) else 0.0

        // fractie van resterende carbs die in de detectionWindow absorbeert
        val absorptionFraction = min(
            1.0,
            detectionWindowMinutes.toDouble() / tauAbsorptionMinutes.toDouble()
        )

        return remainingCarbs * mmolPerGram * absorptionFraction
    }

    // ★★★ EFFECTIEVE PARAMETERS ★★★
    private fun getEffectiveCarbRatio(): Double {
        val base = currentCR * learningProfile.personalCarbRatio
        return base / resistanceHelper.getCurrentResistanceFactor()
    }

    private fun getEffectiveISF(): Double {
        val baseISF = currentISF * learningProfile.personalISF
        val activityAdjustment = currentStappenPercentage / 100.0

        return when {
            activityAdjustment < 1.0 -> {
                val boostFactor = 1.0 + (1.0 - activityAdjustment) * 0.3
                (baseISF * boostFactor) / resistanceHelper.getCurrentResistanceFactor()
            }
            activityAdjustment > 1.0 -> {
                baseISF / (activityAdjustment * resistanceHelper.getCurrentResistanceFactor())
            }
            else -> baseISF / resistanceHelper.getCurrentResistanceFactor()
        }
    }

    private fun getEffectiveTarget(): Double {
        val baseTarget = Target_Bg
        val activityAdjustment = currentStappenTargetAdjust

        return when {
            activityAdjustment > 0 -> baseTarget + activityAdjustment
            else -> baseTarget
        }
    }
    private fun getEffectiveBolusAggressiveness(): Double {
        val baseAggressiveness = getCurrentBolusAggressiveness()
        val activityFactor = currentStappenPercentage / 100.0

        return when {
            activityFactor < 0.8 -> baseAggressiveness * 0.7
            activityFactor > 1.2 -> baseAggressiveness * 1.1
            else -> baseAggressiveness
        }
    }

    fun getActivityStatus(): String {
        return fclActivity.getCurrentActivityStatus()
    }

    fun resetActivitySystem() {
        fclActivity.resetActivity()
        currentStappenPercentage = 100.0
        currentStappenTargetAdjust = 0.0
        currentStappenLog = "Activity system reset"
    }

    // ★★★ TREND ANALYSIS ★★★
    private fun analyzeTrends(data: List<BGDataPoint>): TrendAnalysis {
        if (data.isEmpty()) return TrendAnalysis(0.0, 0.0, 0.0)

        val smoothed = smoothBGSeries(data, alpha = 0.35)
        val smoothPoints = smoothed.map { (ts, bg) ->
            BGDataPoint(timestamp = ts, bg = bg, iob = data.find { it.timestamp == ts }?.iob ?: 0.0)
        }

        val recentTrend = calculateRecentTrend(smoothPoints, 4)
        val shortTermTrend = calculateShortTermTrend(smoothPoints)
        val acceleration = calculateAcceleration(smoothPoints, 3)

        // Store peak-detection data
        val lastPeakSave = storage.loadPeakDetectionData().lastOrNull()
        val shouldSave = lastPeakSave == null ||
            Minutes.minutesBetween(lastPeakSave.timestamp, data.last().timestamp).minutes >= 5 ||
            acceleration < -0.5

        if (shouldSave) {
            storePeakDetectionData(data.last(), TrendAnalysis(recentTrend, shortTermTrend, acceleration))
            try {
                detectPeaksFromHistoricalData()
            } catch (ex: Exception) {
                // Logging
            }
        }

        return TrendAnalysis(recentTrend, shortTermTrend, acceleration)
    }

    private fun smoothBGSeries(data: List<BGDataPoint>, alpha: Double): List<Pair<DateTime, Double>> {
        if (data.isEmpty()) return emptyList()
        val res = mutableListOf<Pair<DateTime, Double>>()
        var s = data.first().bg
        res.add(Pair(data.first().timestamp, s))
        for (i in 1 until data.size) {
            s = alpha * data[i].bg + (1 - alpha) * s
            res.add(Pair(data[i].timestamp, s))
        }
        return res
    }

    // ★★★ SENSOR ISSUE DETECTION ★★★
    private fun detectSensorIssue(historicalData: List<BGDataPoint>): SensorIssueType? {
        if (historicalData.size < 3) return null

        // Grote sprongen
        val recent3 = historicalData.takeLast(3)
        val d1 = recent3[1].bg - recent3[0].bg
        val d2 = recent3[2].bg - recent3[1].bg
        if (abs(d1) > 3.0 || abs(d2) > 3.0) {
            return SensorIssueType.JUMP_TOO_LARGE
        }

        // Oscillaties
        val oscillation = (
            (recent3[0].bg < recent3[1].bg && recent3[2].bg < recent3[1].bg) || // piek
                (recent3[0].bg > recent3[1].bg && recent3[2].bg > recent3[1].bg)    // dal
            ) && (abs(d1) >= 0.5 && abs(d2) >= 0.5)

        if (oscillation) {
            return SensorIssueType.OSCILLATION
        }

        // Compression lows
        if (historicalData.size >= 5) {
            val recent5 = historicalData.takeLast(5)
            val first = recent5.first()
            val minPoint = recent5.minByOrNull { it.bg } ?: return null
            val drop = first.bg - minPoint.bg
            val minutesToMin = Minutes.minutesBetween(first.timestamp, minPoint.timestamp).minutes
            val rapidDrop = drop > 2.0 && minutesToMin in 5..15 && minPoint.bg < 4.0

            val last = recent5.last()
            val rebound = last.bg - minPoint.bg
            val minutesToLast = Minutes.minutesBetween(minPoint.timestamp, last.timestamp).minutes
            val rapidRebound = rebound > 1.5 && minutesToLast in 5..20

            if (rapidDrop && rapidRebound) {
                return SensorIssueType.COMPRESSION_LOW
            }
        }

        return null
    }

    // ★★★ MEAL PATTERN VALIDATION ★★★
    private fun validateMealPattern(historicalData: List<BGDataPoint>): MealConfidenceLevel {
        if (historicalData.size < 6) return MealConfidenceLevel.SUSPECTED

        val recent = historicalData.takeLast(6)
        val risingCount = recent.zipWithNext { a, b -> b.bg > a.bg + 0.1 }.count { it }

        val slopes = recent.zipWithNext { a, b ->
            val minutesDiff = Minutes.minutesBetween(a.timestamp, b.timestamp).minutes.toDouble()
            if (minutesDiff > 0) (b.bg - a.bg) / minutesDiff * 60.0 else 0.0
        }

        val slopeVariance = if (slopes.size > 1) {
            val average = slopes.average()
            slopes.map { (it - average) * (it - average) }.average()
        } else 0.0

        return when {
            risingCount >= 4 && slopeVariance < 0.1 -> MealConfidenceLevel.HIGH_CONFIDENCE
            risingCount >= 3 -> MealConfidenceLevel.CONFIRMED
            else -> MealConfidenceLevel.SUSPECTED
        }
    }

    // ★★★ VERBETERDE CONSISTENCY BEREKENING ★★★
    private fun calculateEnhancedConsistency(data: List<BGDataPoint>): Double {
        if (data.size < 4) return 0.0

        val slopes = mutableListOf<Double>()
        for (i in 1 until data.size) {
            val timeDiff = Minutes.minutesBetween(data[i-1].timestamp, data[i].timestamp).minutes / 60.0
            if (timeDiff > 0) {
                slopes.add((data[i].bg - data[i-1].bg) / timeDiff)
            }
        }

        if (slopes.size < 2) return 0.0

        val directionConsistency = calculateDirectionConsistency(slopes)
        val magnitudeConsistency = calculateMagnitudeConsistency(slopes)
        val patternConsistency = calculatePatternConsistency(data)

        return (directionConsistency * 0.5 + magnitudeConsistency * 0.3 + patternConsistency * 0.2)
    }

    private fun calculateDirectionConsistency(slopes: List<Double>): Double {
        if (slopes.isEmpty()) return 0.0
        val positiveSlopes = slopes.count { it > 0.05 }
        val negativeSlopes = slopes.count { it < -0.05 }
        val totalValidSlopes = positiveSlopes + negativeSlopes

        if (totalValidSlopes == 0) return 0.0
        val maxDirection = max(positiveSlopes, negativeSlopes)
        return maxDirection.toDouble() / totalValidSlopes
    }

    private fun calculateMagnitudeConsistency(slopes: List<Double>): Double {
        if (slopes.size < 2) return 0.0
        val significantSlopes = slopes.filter { abs(it) > 0.05 }
        if (significantSlopes.size < 2) return 0.0

        val mean = significantSlopes.average()
        val variance = significantSlopes.map { (it - mean) * (it - mean) }.average()
        return exp(-variance * 5.0).coerceIn(0.0, 1.0)
    }

    private fun calculatePatternConsistency(data: List<BGDataPoint>): Double {
        if (data.size < 4) return 0.0

        val isMonotonicRise = data.zipWithNext().all { (a, b) -> b.bg >= a.bg - 0.1 }
        val isMonotonicFall = data.zipWithNext().all { (a, b) -> b.bg <= a.bg + 0.1 }

        if (isMonotonicRise || isMonotonicFall) return 0.9

        val secondDifferences = mutableListOf<Double>()
        for (i in 1 until data.size - 1) {
            val firstDiff = data[i].bg - data[i-1].bg
            val secondDiff = data[i+1].bg - data[i].bg
            secondDifferences.add(secondDiff - firstDiff)
        }

        val consistentAcceleration = secondDifferences.all { it > -0.1 && it < 0.1 }
        if (consistentAcceleration && secondDifferences.isNotEmpty()) return 0.7

        return 0.3
    }

    // ★★★ WISKUNDIGE FASE HERKENNING ★★★
    private fun calculateRobustTrends(historicalData: List<BGDataPoint>): RobustTrendAnalysis {
        if (historicalData.size < 5) {
            val result = RobustTrendAnalysis(0.0, 0.0, 0.0, "uncertain")
            lastRobustTrends = result
            return result
        }

        recentDataForAnalysis = historicalData.takeLast(6).filter { it.bg > 3.0 && it.bg < 20.0 }
        if (recentDataForAnalysis.size < 4) {
            val result = RobustTrendAnalysis(0.0, 0.0, 0.0, "uncertain")
            lastRobustTrends = result
            return result
        }

        val smoothed = smoothBGSeries(recentDataForAnalysis, 0.4)
        if (smoothed.size < 3) {
            val result = RobustTrendAnalysis(0.0, 0.0, 0.0, "uncertain")
            lastRobustTrends = result
            return result
        }

        val firstDerivative = calculateWeightedFirstDerivative(smoothed)
        val secondDerivative = calculateRobustSecondDerivative(smoothed)
        val consistency = calculateEnhancedConsistency(recentDataForAnalysis)

        val phaseTransition = calculateEnhancedPhaseDetection(
            robustTrends = RobustTrendAnalysis(firstDerivative, secondDerivative, consistency, "stable"),
            historicalData = historicalData,
            currentBG = recentDataForAnalysis.last().bg,
            previousPhase = lastRobustTrends?.phase ?: "stable"
        )

        val phase = phaseTransition.phase
        val result = RobustTrendAnalysis(firstDerivative, secondDerivative, consistency, phase)
        lastRobustTrends = result
        lastPhaseTransitionFactor = phaseTransition.transitionFactor

        return result
    }

    private fun calculateWeightedFirstDerivative(smoothedData: List<Pair<DateTime, Double>>): Double {
        if (smoothedData.size < 3) return calculateSimpleFirstDerivative(smoothedData)

        val slopes = mutableListOf<Double>()
        val weights = mutableListOf<Double>()

        for (i in 1 until smoothedData.size) {
            val timeDiff = Minutes.minutesBetween(smoothedData[i-1].first, smoothedData[i].first).minutes / 60.0
            if (timeDiff > 0) {
                slopes.add((smoothedData[i].second - smoothedData[i-1].second) / timeDiff)
                weights.add(1.0 / (1.0 + (smoothedData.size - 1 - i)))
            }
        }

        return if (slopes.isNotEmpty()) {
            val totalWeight = weights.sum()
            slopes.zip(weights).sumByDouble { it.first * it.second } / totalWeight
        } else 0.0
    }

    private fun calculateSimpleFirstDerivative(smoothedData: List<Pair<DateTime, Double>>): Double {
        if (smoothedData.size < 2) return 0.0
        val current = smoothedData.last()
        val previous = smoothedData[smoothedData.size - 2]
        val timeDiff = Minutes.minutesBetween(previous.first, current.first).minutes / 60.0
        return if (timeDiff > 0) (current.second - previous.second) / timeDiff else 0.0
    }

    private fun calculateRobustSecondDerivative(smoothedData: List<Pair<DateTime, Double>>): Double {
        if (smoothedData.size < 4) return 0.0

        val t1 = smoothedData[smoothedData.size - 4]
        val t2 = smoothedData[smoothedData.size - 3]
        val t3 = smoothedData[smoothedData.size - 2]
        val t4 = smoothedData[smoothedData.size - 1]

        val dt1 = Minutes.minutesBetween(t1.first, t2.first).minutes / 60.0
        val dt2 = Minutes.minutesBetween(t2.first, t3.first).minutes / 60.0
        val dt3 = Minutes.minutesBetween(t3.first, t4.first).minutes / 60.0

        if (dt1 > 0 && dt2 > 0 && dt3 > 0) {
            val slope1 = (t2.second - t1.second) / dt1
            val slope2 = (t3.second - t2.second) / dt2
            val slope3 = (t4.second - t3.second) / dt3
            return ((slope2 - slope1) + (slope3 - slope2)) / 2.0
        }
        return 0.0
    }

    // ★★★ VERBETERDE FASE DETECTIE MET GLADDE OVERGANGEN ★★★
    private fun calculateEnhancedPhaseDetection(
        robustTrends: RobustTrendAnalysis,
        historicalData: List<BGDataPoint>,
        currentBG: Double,
        previousPhase: String
    ): PhaseTransitionResult {
        if (historicalData.size < 6) {
            return PhaseTransitionResult(robustTrends.phase, 1.0, "Insufficient data")
        }

        val recentData = historicalData.takeLast(6)
        val slopes = calculateSlopeHistory(recentData)
        val currentSlope = robustTrends.firstDerivative

        val earlyRiseSlope = preferences.get(DoubleKey.phase_early_rise_slope)
        val midRiseSlope = preferences.get(DoubleKey.phase_mid_rise_slope)
        val lateRiseSlope = preferences.get(DoubleKey.phase_late_rise_slope)

        val proposedPhase = when {
            currentSlope < -1.0 -> "declining"
            currentSlope < -0.3 -> "declining"
            hasConsistentRise(slopes, 3) && currentSlope > earlyRiseSlope * 0.7 -> "early_rise"
            currentSlope > earlyRiseSlope -> "early_rise"
            currentSlope > midRiseSlope && robustTrends.secondDerivative > 0.1 -> "mid_rise"
            currentSlope > midRiseSlope -> "mid_rise"
            currentSlope > lateRiseSlope -> "late_rise"
            else -> "stable"
        }

        val (finalPhase, transitionFactor) = calculatePhaseTransition(
            currentPhase = previousPhase,
            proposedPhase = proposedPhase,
            currentSlope = currentSlope,
            slopes = slopes
        )

        val debugInfo = "Phase: $previousPhase → $proposedPhase → $finalPhase (factor: ${"%.2f".format(transitionFactor)})"
        return PhaseTransitionResult(finalPhase, transitionFactor, debugInfo)
    }

    private fun calculatePhaseTransition(
        currentPhase: String,
        proposedPhase: String,
        currentSlope: Double,
        slopes: List<Double>
    ): Pair<String, Double> {
        val phaseOrder = listOf("stable", "early_rise", "mid_rise", "late_rise", "peak", "declining")
        val currentIndex = phaseOrder.indexOf(currentPhase)
        val proposedIndex = phaseOrder.indexOf(proposedPhase)

        if (currentIndex == -1 || proposedIndex == -1) {
            return Pair(proposedPhase, 1.0)
        }

        if (currentPhase == proposedPhase) {
            return Pair(currentPhase, 1.0)
        }

        if (proposedIndex > currentIndex) {
            return Pair(proposedPhase, 1.0)
        }

        if (proposedIndex < currentIndex) {
            val transitionFactor = calculateBackwardTransitionFactor(
                fromPhase = currentPhase,
                toPhase = proposedPhase,
                currentSlope = currentSlope,
                slopeConsistency = calculateSlopeConsistency(slopes)
            )
            return Pair(proposedPhase, transitionFactor)
        }

        return Pair(proposedPhase, 1.0)
    }

    private fun calculateBackwardTransitionFactor(
        fromPhase: String,
        toPhase: String,
        currentSlope: Double,
        slopeConsistency: Double
    ): Double {
        val fromPercentage = getPhasePercentage(fromPhase)
        val toPercentage = getPhasePercentage(toPhase)
        val averagePercentage = (fromPercentage + toPercentage) / 2.0
        val consistencyFactor = 0.5 + (slopeConsistency * 0.5)
        return averagePercentage * consistencyFactor
    }

    // ★★★ WISKUNDIGE BOLUS ADVIES ★★★
    private fun getMathematicalBolusAdvice(
        robustTrends: RobustTrendAnalysis,
        detectedCarbs: Double,
        currentBG: Double,
        targetBG: Double,
        historicalData: List<BGDataPoint>,
        currentIOB: Double,
        maxIOB: Double
    ): MathematicalBolusAdvice {
        val iobRatio = currentIOB / maxIOB
        val IOB_safety_perc = preferences.get(IntKey.IOB_corr_perc)
        val iobAggressivenessFactor = when {
            iobRatio > 0.8 -> 0.2 * (IOB_safety_perc / 100.0)
            iobRatio > 0.6 -> 0.4 * (IOB_safety_perc / 100.0)
            iobRatio > 0.4 -> 0.6 * (IOB_safety_perc / 100.0)
            iobRatio > 0.2 -> 0.8 * (IOB_safety_perc / 100.0)
            else -> 1.0 * (IOB_safety_perc / 100.0)
        }

        if (shouldBlockMathematicalBolusForHighIOB(currentIOB, maxIOB, robustTrends, detectedCarbs > 0)) {
            return MathematicalBolusAdvice(
                immediatePercentage = 0.0,
                reservedPercentage = 0.0,
                reason = "Math: Blocked due to high IOB (${"%.1f".format(currentIOB)}U)"
            )
        }

        val transitionFactor = lastPhaseTransitionFactor
        val baseEarlyPerc = (preferences.get(IntKey.bolus_perc_early).toDouble() / 100.0) * transitionFactor * iobAggressivenessFactor
        val baseMidPerc = (preferences.get(IntKey.bolus_perc_mid).toDouble() / 100.0) * transitionFactor * iobAggressivenessFactor
        val baseLatePerc = (preferences.get(IntKey.bolus_perc_late).toDouble() / 100.0) * transitionFactor * iobAggressivenessFactor

        val consistencyFactor = calculateConsistencyBasedScaling(robustTrends.consistency)
        val consistentEarlyPerc = baseEarlyPerc * consistencyFactor
        val consistentMidPerc = baseMidPerc * consistencyFactor
        val consistentLatePerc = baseLatePerc * consistencyFactor

        val overallAggressiveness = getCurrentBolusAggressiveness() / 100.0
        val combinedEarlyPerc = consistentEarlyPerc * overallAggressiveness
        val combinedMidPerc = consistentMidPerc * overallAggressiveness
        val combinedLatePerc = consistentLatePerc * overallAggressiveness

        val dynamicFactors = calculateDynamicFactors(
            robustTrends = robustTrends,
            currentBG = currentBG,
            targetBG = targetBG,
            historicalData = historicalData,
            currentIOB = currentIOB,
            maxIOB = maxIOB
        )
        val trendFactor = dynamicFactors.trendFactor
        val safetyFactor = dynamicFactors.safetyFactor
        val confidenceFactor = dynamicFactors.confidenceFactor
        val totalDynamicFactor = trendFactor * safetyFactor * confidenceFactor

        return when (robustTrends.phase) {
            "early_rise" -> {
                val earlyRiseBoost = 1.3
                val boostedEarlyPerc = combinedEarlyPerc * earlyRiseBoost
                val finalImmediate = (boostedEarlyPerc * totalDynamicFactor).coerceIn(0.0, 1.5)
                MathematicalBolusAdvice(
                    immediatePercentage = finalImmediate,
                    reservedPercentage = 0.2,
                    reason = "Math: Early Rise BOOSTED (base=${(baseEarlyPerc*100).toInt()}% × trans=${(transitionFactor*100).toInt()}% × overall=${(overallAggressiveness*100).toInt()}% × boost=${earlyRiseBoost} → ${(finalImmediate*100).toInt()}%, IOB=${"%.1f".format(currentIOB)}U, trend=${"%.1f".format(robustTrends.firstDerivative)})"
                )
            }
            "mid_rise" -> {
                val finalImmediate = (combinedMidPerc * totalDynamicFactor).coerceIn(0.0, 1.5)
                MathematicalBolusAdvice(
                    immediatePercentage = finalImmediate,
                    reservedPercentage = 0.15,
                    reason = "Math: Mid Rise (base=${(baseMidPerc*100).toInt()}% × trans=${(transitionFactor*100).toInt()}% × overall=${(overallAggressiveness*100).toInt()}% → ${(finalImmediate*100).toInt()}%, IOB=${"%.1f".format(currentIOB)}U, trend=${"%.1f".format(robustTrends.firstDerivative)})"
                )
            }
            "late_rise" -> {
                val finalImmediate = (combinedLatePerc * totalDynamicFactor).coerceIn(0.0, 1.2)
                MathematicalBolusAdvice(
                    immediatePercentage = finalImmediate,
                    reservedPercentage = 0.1,
                    reason = "Math: Late Rise (base=${(baseLatePerc*100).toInt()}% × trans=${(transitionFactor*100).toInt()}% × overall=${(overallAggressiveness*100).toInt()}% → ${(finalImmediate*100).toInt()}%, IOB=${"%.1f".format(currentIOB)}U, trend=${"%.1f".format(robustTrends.firstDerivative)})"
                )
            }
            "peak" -> MathematicalBolusAdvice(0.0, 0.0, "Math: Peak")
            "declining" -> MathematicalBolusAdvice(0.0, 0.0, "Math: Declining")
            "stable" -> MathematicalBolusAdvice(0.0, 0.0, "Math: Stable")
            else -> MathematicalBolusAdvice(0.0, 0.0, "Math: ${robustTrends.phase}")
        }
    }

    private fun calculateConsistencyBasedScaling(consistency: Double): Double {
        return when {
            consistency > 0.8 -> 1.0
            consistency > 0.6 -> 0.8
            consistency > 0.4 -> 0.6
            consistency > 0.2 -> 0.4
            else -> 0.2
        }
    }

    private fun shouldBlockMathematicalBolusForHighIOB(
        currentIOB: Double,
        maxIOB: Double,
        robustTrends: RobustTrendAnalysis,
        mealDetected: Boolean = false
    ): Boolean {
        // ★★★ DYNAMISCHE IOB CAP TOEPASSEN ★★★
        val dynamicMaxIOB = calculateDynamicIOBCap(currentIOB, maxIOB, robustTrends.firstDerivative)

        if (mealDetected) {
            return when {
                currentIOB > dynamicMaxIOB * 1.05 -> true
                currentIOB > dynamicMaxIOB * 0.90 && robustTrends.firstDerivative < 1.5 -> true
                currentIOB > dynamicMaxIOB * 0.80 && robustTrends.firstDerivative < 0.5 -> true
                else -> false
            }
        } else {
            return when {
                currentIOB > dynamicMaxIOB * 1.05 -> true
                currentIOB > dynamicMaxIOB * 0.90 && robustTrends.firstDerivative < 2.0 -> true
                currentIOB > dynamicMaxIOB * 0.80 && robustTrends.firstDerivative < 1.0 -> true
                else -> false
            }
        }
    }

    private fun calculateDynamicFactors(
        robustTrends: RobustTrendAnalysis,
        currentBG: Double,
        targetBG: Double,
        historicalData: List<BGDataPoint>,
        currentIOB: Double,
        maxIOB: Double
    ): DynamicFactors {
        val trendFactor = calculateTrendFactor(robustTrends.firstDerivative)
        val safetyFactor = calculateSafetyFactorWithIOB(currentBG, targetBG, historicalData, currentIOB, maxIOB)
        val confidenceFactor = calculateConfidenceFactor(robustTrends.consistency, historicalData)
        return DynamicFactors(trendFactor, safetyFactor, confidenceFactor)
    }

    private fun calculateTrendFactor(slope: Double): Double {
        return when {
            slope > 3.0 -> 1.2
            slope > 2.0 -> 1.1
            slope > 1.0 -> 1.0
            slope > 0.5 -> 0.9
            else -> 0.8
        }
    }



    private fun calculateSafetyFactorWithIOB(
        currentBG: Double,
        targetBG: Double,
        historicalData: List<BGDataPoint>,
        currentIOB: Double,
        maxIOB: Double
    ): Double {
        val bgAboveTarget = currentBG - targetBG

        // ★★★ DYNAMISCHE IOB CAP TOEPASSEN ★★★
        val currentSlope = lastRobustTrends?.firstDerivative ?: 0.0
        val dynamicMaxIOB = calculateDynamicIOBCap(currentIOB, maxIOB, currentSlope)

        val iobRatio = currentIOB / dynamicMaxIOB  // Gebruik dynamicMaxIOB i.p.v. maxIOB
        val IOB_safety_perc = preferences.get(IntKey.IOB_corr_perc)

        val iobPenalty = when {
            iobRatio > 0.8 -> 0.6 * (IOB_safety_perc / 100.0)
            iobRatio > 0.6 -> 0.75 * (IOB_safety_perc / 100.0)
            iobRatio > 0.4 -> 0.85 * (IOB_safety_perc / 100.0)
            iobRatio > 0.2 -> 0.9 * (IOB_safety_perc / 100.0)
            else -> 1.0 * (IOB_safety_perc / 100.0)
        }

        // Rest van de functie blijft hetzelfde...
        val baseSafety = when {
            currentBG < targetBG -> 0.2
            bgAboveTarget > 3.0 -> 1.0
            bgAboveTarget > 2.0 -> 0.8
            bgAboveTarget > 1.0 -> 0.6
            else -> 0.4
        }

        val volatility = calculateVolatility(historicalData)
        val volatilityPenalty = if (volatility > 1.0) 0.7 else 1.0
        val finalSafety = (baseSafety * iobPenalty * volatilityPenalty).coerceIn(0.1, 1.0)

        return finalSafety
    }

    private fun calculateConfidenceFactor(consistency: Double, historicalData: List<BGDataPoint>): Double {
        val dataPoints = historicalData.takeLast(6).size
        val dataFactor = if (dataPoints >= 4) 1.0 else 0.7
        return (consistency * dataFactor).coerceIn(0.5, 1.0)
    }

    private fun calculateVolatility(data: List<BGDataPoint>): Double {
        if (data.size < 2) return 0.0
        val changes = data.zipWithNext().map { (a, b) -> abs(b.bg - a.bg) }
        return changes.average()
    }

    // ★★★ DYNAMISCHE IOB CAP BIJ STERKE STIJGING ★★★
    private fun calculateDynamicIOBCap(
        currentIOB: Double,
        maxIOB: Double,
        slope: Double
    ): Double {
        // Hogere IOB cap bij sterke stijging
        val correction = preferences.get(IntKey.tau_absorption_minutes)/100.0
        return when {
            slope > 6.5 -> maxIOB * correction * 1.1  // Tot 20% hoger bij extreme stijging
            slope > 4.0 -> maxIOB * correction
            else -> maxIOB
        }
    }

    // ★★★ WISKUNDIGE METHODE ALS ENIGE METHODE ★★★
    private fun getMathematicalBolusAsOnlyMethod(
        robustTrends: RobustTrendAnalysis,
        detectedCarbs: Double,
        currentBG: Double,
        targetBG: Double,
        historicalData: List<BGDataPoint>,
        currentIOB: Double,
        maxIOB: Double,
        effectiveCR: Double
    ): Triple<Double, Double, String> {
        val mathAdvice = getMathematicalBolusAdvice(
            robustTrends = robustTrends,
            detectedCarbs = detectedCarbs,
            currentBG = currentBG,
            targetBG = targetBG,
            historicalData = historicalData,
            currentIOB = currentIOB,
            maxIOB = maxIOB
        )

        val totalCarbBolus = detectedCarbs / effectiveCR
        val immediateBolus = totalCarbBolus * mathAdvice.immediatePercentage
        val reservedBolus = totalCarbBolus * mathAdvice.reservedPercentage

        return Triple(immediateBolus, reservedBolus, mathAdvice.reason)
    }

    // ★★★ WISKUNDIGE CORRECTIE METHODE ★★★
    private fun getMathematicalCorrectionDose(
        robustTrends: RobustTrendAnalysis,
        currentBG: Double,
        targetBG: Double,
        effectiveISF: Double,
        currentIOB: Double,
        maxIOB: Double
    ): Double {
        val mathAdvice = getMathematicalBolusAdvice(
            robustTrends = robustTrends,
            detectedCarbs = 0.0,
            currentBG = currentBG,
            targetBG = targetBG,
            historicalData = listOf(),
            currentIOB = currentIOB,
            maxIOB = maxIOB
        )

        val bgAboveTarget = currentBG - targetBG
        val requiredCorrection = bgAboveTarget / effectiveISF
        return requiredCorrection * mathAdvice.immediatePercentage
    }

    // ★★★ MEAL DETECTION HELPER FUNCTIES ★★★
    private fun hasSustainedRisePattern(historicalData: List<BGDataPoint>): Boolean {
        if (historicalData.size < 6) return false
        val recent = historicalData.takeLast(6)
        val risingCount = recent.zipWithNext { a, b -> b.bg > a.bg + 0.1 }.count { it }
        return risingCount >= 4
    }

    private fun calculateVariance(values: List<Double>): Double {
        if (values.size < 2) return 0.0
        val mean = values.average()
        return values.map { (it - mean) * (it - mean) }.average()
    }

    private fun calculatePeakConfidence(
        historicalData: List<BGDataPoint>,
        detectedCarbs: Double
    ): Double {
        if (historicalData.size < 6) return 0.5
        val recentData = historicalData.takeLast(6)
        val rises = recentData.zipWithNext { a, b -> b.bg - a.bg }
        val risingCount = rises.count { it > 0.1 }
        val riseConsistency = risingCount.toDouble() / rises.size
        val riseVariance = calculateVariance(rises)

        return when {
            riseConsistency > 0.7 && riseVariance < 0.1 && detectedCarbs > 30 -> 0.9
            riseConsistency > 0.6 && detectedCarbs > 20 -> 0.75
            detectedCarbs > 15 -> 0.6
            else -> 0.5
        }
    }

    private fun calculateMealConfidence(
        historicalData: List<BGDataPoint>,
        detectedCarbs: Double
    ): Double {
        var confidence = 0.3
        if (historicalData.size < 4) return if (detectedCarbs > 20) 0.4 else 0.2

        val recent = historicalData.takeLast(4)
        val totalRise = recent.last().bg - recent.first().bg
        val hasQuickRise = hasRecentRise(historicalData, 2)
        val hasStrongRise = totalRise > 2.0
        val hasConsistentPattern = hasSustainedRisePattern(historicalData)

        if (hasConsistentPattern && hasStrongRise) {
            confidence += 0.4
        } else if (hasQuickRise && totalRise > 1.0) {
            confidence += 0.2
        }

        if (detectedCarbs > 25) {
            confidence += 0.2
        } else if (detectedCarbs > 15) {
            confidence += 0.1
        }

        return confidence.coerceIn(0.0, 0.8)
    }

    private fun distinguishMealFromSnack(
        historicalData: List<BGDataPoint>,
        detectedCarbs: Double
    ): Boolean {
        if (historicalData.size < 4) return detectedCarbs > 20

        val recent = historicalData.takeLast(4)
        val totalRise = recent.last().bg - recent.first().bg
        val rises = recent.zipWithNext { a, b -> b.bg - a.bg }
        val consistentRise = rises.all { it > 0.1 }
        val riseVariance = if (rises.size > 1) calculateVariance(rises) else 1.0

        return when {
            detectedCarbs > 30 && consistentRise && totalRise > 1.5 -> true
            detectedCarbs > 20 && riseVariance < 0.1 && totalRise > 1.0 -> true
            detectedCarbs > 15 && hasSustainedRisePattern(historicalData) -> true
            else -> false
        }
    }

    // ★★★ REAL-TIME BIJSTURING ★★★
    private fun shouldAdjustOrCancelBolus(
        historicalData: List<BGDataPoint>,
        initialDetection: MealDetectionState
    ): Boolean {
        if (historicalData.size < 4) return false
        val recent = historicalData.takeLast(4)
        val isStillRising = hasRecentRise(historicalData, 1)
        val plateauOrDecline = recent.zipWithNext { a, b -> b.bg <= a.bg + 0.1 }.count { it } >= 2 && !isStillRising
        val unexpectedDrop = recent.last().bg < recent[recent.size - 2].bg - 0.5
        return plateauOrDecline || unexpectedDrop
    }

    // ★★★ TREND CALCULATIE FUNCTIES ★★★
    private fun calculateRecentTrend(data: List<BGDataPoint>, pointsBack: Int): Double {
        if (data.size <= pointsBack) return 0.0
        val currentIndex = data.lastIndex
        val pastIndex = max(0, currentIndex - pointsBack)
        val currentPoint = data[currentIndex]
        val pastPoint = data[pastIndex]
        val timeDiffMinutes = Minutes.minutesBetween(pastPoint.timestamp, currentPoint.timestamp).minutes
        val timeDiffHours = timeDiffMinutes / 60.0
        if (timeDiffHours <= 0) return 0.0
        val bgDiff = currentPoint.bg - pastPoint.bg
        return bgDiff / timeDiffHours
    }

    private fun calculateShortTermTrend(data: List<BGDataPoint>): Double {
        if (data.size < 4) return 0.0
        val current = data.last()
        val twentyMinAgo = current.timestamp.minusMinutes(20)
        val fifteenMinAgo = current.timestamp.minusMinutes(15)
        val pastPoint = data.findLast {
            it.timestamp.isAfter(twentyMinAgo) && it.timestamp.isBefore(fifteenMinAgo)
        } ?: data.findLast {
            it.timestamp.isBefore(current.timestamp.minusMinutes(10))
        } ?: return 0.0

        val timeDiffMinutes = Minutes.minutesBetween(pastPoint.timestamp, current.timestamp).minutes
        val timeDiffHours = timeDiffMinutes / 60.0
        if (timeDiffHours <= 0) return 0.0
        val bgDiff = current.bg - pastPoint.bg
        return bgDiff / timeDiffHours
    }

    private fun calculateAcceleration(data: List<BGDataPoint>, points: Int): Double {
        if (data.size <= points * 2) return 0.0
        val currentIndex = data.lastIndex

        // Recente trend (laatste 2 punten)
        val recentSlice = if (data.size >= 2) data.subList(data.lastIndex - 1, data.lastIndex + 1) else emptyList()
        val recentTrend = if (recentSlice.size >= 2) {
            calculateTrendBetweenPoints(recentSlice[0], recentSlice[1])
        } else {
            0.0
        }

        // Vorige trend (punten verder terug)
        val prevStart = max(0, currentIndex - points)
        val prevEnd = min(data.size, prevStart + 2)
        val prevSlice = if (prevEnd > prevStart) data.subList(prevStart, prevEnd) else emptyList()
        val previousTrend = if (prevSlice.size >= 2) {
            calculateTrendBetweenPoints(prevSlice[0], prevSlice[1])
        } else {
            0.0
        }

        val timeDiffMinutes = if (prevSlice.isNotEmpty() && recentSlice.isNotEmpty()) {
            Minutes.minutesBetween(prevSlice.last().timestamp, recentSlice.last().timestamp).minutes.toDouble()
        } else {
            0.0
        }

        if (timeDiffMinutes <= 0) return 0.0
        return (recentTrend - previousTrend) / (timeDiffMinutes / 60.0)
    }

    private fun calculateTrendBetweenPoints(point1: BGDataPoint, point2: BGDataPoint): Double {
        val timeDiffHours = Minutes.minutesBetween(point1.timestamp, point2.timestamp).minutes / 60.0
        if (timeDiffHours <= 0) return 0.0
        return (point2.bg - point1.bg) / timeDiffHours
    }

    // ★★★ HYPO RECOVERY DETECTION ★★★
    private fun isLikelyHypoRecovery(
        currentBG: Double,
        historicalData: List<BGDataPoint>,
        trends: TrendAnalysis
    ): Boolean {
        if (historicalData.size < 4) return false

        val recentHypo = historicalData.any {
            it.bg < gethypoThreshold() &&
                Minutes.minutesBetween(it.timestamp, DateTime.now()).minutes <= HYPO_RECOVERY_MINUTES
        }

        if (!recentHypo) return false

        val recoveryRangeMin = gethypoThreshold()
        val recoveryRangeMax = gethypoThreshold() + HYPO_RECOVERY_BG_RANGE
        val isInRecoveryPhase = currentBG in recoveryRangeMin..recoveryRangeMax
        val hasRapidRise = hasRapidRisePatternFromLow(historicalData)
        val isStableHighBG = currentBG > recoveryRangeMax && trends.recentTrend < 1.0

        return recentHypo && isInRecoveryPhase && hasRapidRise && !isStableHighBG
    }

    private fun hasRapidRisePatternFromLow(historicalData: List<BGDataPoint>): Boolean {
        if (historicalData.size < 4) return false
        val recoveryWindowAgo = DateTime.now().minusMinutes(HYPO_RECOVERY_MINUTES)
        val recentData = historicalData.filter { it.timestamp.isAfter(recoveryWindowAgo) }
        val minPoint = recentData.minByOrNull { it.bg }
        val current = historicalData.last()

        minPoint?.let { lowPoint ->
            val minutesSinceLow = Minutes.minutesBetween(lowPoint.timestamp, current.timestamp).minutes
            val totalRise = current.bg - lowPoint.bg
            val minRecoveryTime = 15
            val maxRecoveryTime = HYPO_RECOVERY_MINUTES
            val minRiseRequired = 2.0
            val isRapidRecovery = minutesSinceLow in minRecoveryTime..maxRecoveryTime && totalRise > minRiseRequired

            val pointsAfterLow = recentData.filter { it.timestamp.isAfter(lowPoint.timestamp) }
            if (pointsAfterLow.size >= 3) {
                val risingCount = pointsAfterLow.zipWithNext { a, b -> b.bg > a.bg + 0.1 }.count { it }
                val isConsistentRise = risingCount >= pointsAfterLow.size * 0.6
                return isRapidRecovery && isConsistentRise
            }
        }
        return false
    }

    private fun shouldBlockMealDetectionForHypoRecovery(
        currentBG: Double,
        historicalData: List<BGDataPoint>,
        trends: TrendAnalysis
    ): Boolean {
        if (isLikelyHypoRecovery(currentBG, historicalData, trends)) {
            return true
        }

        if (currentBG < gethypoThreshold() + 1.5 && trends.recentTrend > 1.0) {
            val recentLow = historicalData.any {
                it.bg < gethypoThreshold() + 0.5 &&
                    Minutes.minutesBetween(it.timestamp, DateTime.now()).minutes <= HYPO_RECOVERY_MINUTES
            }
            if (recentLow) {
                return true
            }
        }
        return false
    }

    // ★★★ SAFETY CHECKS ★★★
    private fun checkConsistentDecline(data: List<BGDataPoint>): Boolean {
        if (data.size < 3) return false
        val recentPoints = data.takeLast(3)
        var declineCount = 0
        for (i in 0 until recentPoints.size - 1) {
            if (recentPoints[i + 1].bg < recentPoints[i].bg - 0.1) {
                declineCount++
            }
        }
        return declineCount >= 2
    }

    private fun shouldBlockBolusForShortTermTrend(
        currentData: BGDataPoint,
        historicalData: List<BGDataPoint>,
        trends: TrendAnalysis,
        maxIOB: Double
    ): Boolean {
        if (historicalData.size < 4) return false
        val iobRatio = currentData.iob / maxIOB
        val shortTermTrend = calculateShortTermTrend(historicalData)
        val isConsistentDecline = checkConsistentDecline(historicalData)

        return when {
            shortTermTrend < -3.0 -> true
            shortTermTrend < -2.0 && isConsistentDecline -> true
            shortTermTrend < -1.0 && iobRatio > 0.5 -> true
            isConsistentDecline && shortTermTrend < -0.5 -> true
            else -> false
        }
    }

    private fun isTrendReversingToDecline(data: List<BGDataPoint>, trends: TrendAnalysis): Boolean {
        if (data.size < 5) return false
        val isDecelerating = trends.acceleration < -0.3
        val shortTermTrend = calculateShortTermTrend(data)
        val isDiverging = shortTermTrend < 0 && trends.recentTrend > 1.0
        val lastThree = data.takeLast(3)
        val decliningCount = lastThree.zipWithNext().count { (first, second) -> second.bg < first.bg - 0.1 }
        return isDecelerating || isDiverging || decliningCount >= 2
    }

    private fun shouldBlockCorrectionForTrendReversal(
        currentData: BGDataPoint,
        historicalData: List<BGDataPoint>,
        trends: TrendAnalysis,
        maxIOB: Double
    ): Boolean {
        if (!isTrendReversingToDecline(historicalData, trends)) return false
        val iobRatio = currentData.iob / maxIOB

        return when {
            iobRatio > 0.8 -> true
            iobRatio > 0.7 && trends.recentTrend < -1.0 -> true
            iobRatio > 0.6 && trends.recentTrend < -2.0 -> true
            else -> false
        }
    }

    private fun isAtPeakOrDeclining(historicalData: List<BGDataPoint>, trends: TrendAnalysis): Boolean {
        if (historicalData.size < 6) return false
        val recentPoints = historicalData.takeLast(6)
        val maxIndex = recentPoints.withIndex().maxByOrNull { it.value.bg }?.index ?: -1
        val isClearPeak = maxIndex in 2..4 && recentPoints.last().bg < recentPoints[maxIndex].bg - 0.5

        val plateauPoints = recentPoints.takeLast(4)
        val maxBG = plateauPoints.maxOf { it.bg }
        val minBG = plateauPoints.minOf { it.bg }
        val isPlateau = (maxBG - minBG) < 0.4 && trends.recentTrend < 0.8

        val isDecelerating = trends.acceleration < -0.3 && trends.recentTrend < 1.5

        val decliningCount = recentPoints.zipWithNext().count { (first, second) -> second.bg < first.bg - 0.1 }
        val isConsistentDecline = decliningCount >= 3

        return isClearPeak || isPlateau || isDecelerating || isConsistentDecline
    }

    // ★★★ HULPFUNCTIES ★★★
    private fun hasRecentRise(historicalData: List<BGDataPoint>, minRisingPoints: Int = 2): Boolean {
        if (historicalData.size < minRisingPoints + 1) return false
        val recent = historicalData.takeLast(minRisingPoints + 1)
        val risingCount = recent.zipWithNext { a, b -> b.bg > a.bg + 0.15 }.count { it }
        return risingCount >= minRisingPoints
    }

    private fun checkConsistentRise(data: List<BGDataPoint>, points: Int): Boolean {
        if (data.size < points + 1) return false
        var risingCount = 0
        val startIndex = maxOf(0, data.size - points - 1)
        for (i in startIndex until data.size - 1) {
            if (data[i + 1].bg > data[i].bg + 0.1) {
                risingCount++
            }
        }
        return risingCount >= points - 1
    }

    // ★★★ COB MANAGEMENT ★★★
    fun getCarbsOnBoard(): Double {
        val now = DateTime.now()
        return activeMeals.sumOf { it.getRemainingCarbs(now) }
    }

    private fun cleanUpMeals() {
        val now = DateTime.now()
        activeMeals.removeIf { it.getRemainingCarbs(now) < 0.1 }
    }

    private fun addOrUpdateActiveMeal(detectedCarbs: Double, timestamp: DateTime) {
        val now = DateTime.now()
        cleanUpMeals()

        val recentMeal = activeMeals.firstOrNull {
            Minutes.minutesBetween(it.timestamp, now).minutes < preferences.get(IntKey.tau_absorption_minutes)
        }

        if (recentMeal == null) {
            val newMeal = ActiveCarbs(
                timestamp = timestamp,
                totalCarbs = detectedCarbs,
                tau = preferences.get(IntKey.tau_absorption_minutes).toDouble()
            )
            activeMeals.add(newMeal)
            lastCOBDebug = "NEW_MEAL: ${detectedCarbs}g at ${timestamp.toString("HH:mm")}"
            storage.saveCurrentCOB(detectedCarbs)
        } else {
            val oldCarbs = recentMeal.totalCarbs
            recentMeal.totalCarbs = max(oldCarbs, detectedCarbs)
            lastCOBDebug = "UPDATE_MEAL: ${oldCarbs}g -> ${recentMeal.totalCarbs}g"
            val currentCOB = getCarbsOnBoard()
            storage.saveCurrentCOB(currentCOB)
        }

        val currentCOB = getCarbsOnBoard()
        lastCOBDebug += " | TOTAL_COB: ${currentCOB}g, ACTIVE_MEALS: ${activeMeals.size}"
        storage.saveCurrentCOB(currentCOB)
    }

    // ★★★ RESERVED BOLUS MANAGEMENT ★★★
    private fun shouldReleaseReservedBolus(
        currentBG: Double,
        targetBG: Double,
        trends: TrendAnalysis,
        historicalData: List<BGDataPoint>,
        currentIOB: Double,
        maxIOB: Double
    ): Boolean {
        if (historicalData.size < 3) return false

        // ★★★ DYNAMISCHE IOB CAP TOEPASSEN ★★★
        val currentSlope = trends.recentTrend
        val dynamicMaxIOB = calculateDynamicIOBCap(currentIOB, maxIOB, currentSlope)

        val isRecentlyRising = hasRecentRise(historicalData, 2)
        val shortTermTrend = calculateShortTermTrend(historicalData)
        val iobCapacity = dynamicMaxIOB - currentIOB  // Gebruik dynamicMaxIOB

        val isVeryHighBG = currentBG > targetBG + 5.0
        val hasMinIOBCapacity = iobCapacity > 0.3
        val isRising = trends.recentTrend > 0.5 || shortTermTrend > 1.0
        val isRapidRise = trends.recentTrend > 2.0 || shortTermTrend > 2.5

        return when {
            isVeryHighBG && hasMinIOBCapacity && isRising -> true
            currentBG > targetBG + 4.0 && isRising && hasMinIOBCapacity -> true
            currentBG > targetBG + 2.0 && isRapidRise && hasMinIOBCapacity -> true
            else -> false
        } && !isAtPeakOrDeclining(historicalData, trends) && currentIOB < dynamicMaxIOB * 0.7
    }

    private fun calculateReservedBolusRelease(
        currentBG: Double,
        targetBG: Double,
        currentIOB: Double,
        maxIOB: Double,
        maxBolus: Double
    ): Double {
        if (pendingReservedBolus <= 0.0) return 0.0

        // ★★★ DYNAMISCHE IOB CAP TOEPASSEN ★★★
        val currentSlope = lastRobustTrends?.firstDerivative ?: 0.0
        val dynamicMaxIOB = calculateDynamicIOBCap(currentIOB, maxIOB, currentSlope)

        val bgAboveTarget = currentBG - targetBG
        val iobCapacity = dynamicMaxIOB - currentIOB  // Gebruik dynamicMaxIOB

        // Rest van de functie blijft hetzelfde...
        val releasePercentage = when {
            bgAboveTarget > 5.0 && iobCapacity > 1.0 -> 0.8
            bgAboveTarget > 5.0 && iobCapacity > 0.5 -> 0.6
            bgAboveTarget > 3.0 && iobCapacity > 0.8 -> 0.7
            bgAboveTarget > 3.0 && iobCapacity > 0.4 -> 0.5
            bgAboveTarget > 2.0 && iobCapacity > 0.6 -> 0.4
            else -> 0.2
        }

        val releaseAmount = pendingReservedBolus * releasePercentage
        val cappedRelease = minOf(releaseAmount, maxBolus, iobCapacity)

        if (cappedRelease > 0.1) {
            pendingReservedBolus -= cappedRelease
            pendingReservedCarbs = pendingReservedCarbs * (1 - releasePercentage)

            if (pendingReservedBolus < 0.1) {
                pendingReservedBolus = 0.0
                pendingReservedCarbs = 0.0
                pendingReservedTimestamp = null
            }

            return cappedRelease
        }

        return 0.0
    }

    private fun decayReservedBolusOverTime() {
        val now = DateTime.now()
        pendingReservedTimestamp?.let { reservedTime ->
            val minutesPassed = Minutes.minutesBetween(reservedTime, now).minutes

            if (minutesPassed > 90) {
                pendingReservedBolus = 0.0
                pendingReservedCarbs = 0.0
                pendingReservedTimestamp = null
            } else {
                val hoursPassed = minutesPassed / 60.0
                val decayFactor = exp(-hoursPassed * 0.5)
                pendingReservedBolus *= decayFactor
                pendingReservedCarbs *= decayFactor

                if (pendingReservedBolus < 0.1) {
                    pendingReservedBolus = 0.0
                    pendingReservedCarbs = 0.0
                    pendingReservedTimestamp = null
                }
            }
        }
    }

    // ★★★ PREDICTION FUNCTIES ★★★
    private fun predictMealResponse(currentBG: Double, trends: TrendAnalysis, phase: String, minutesAhead: Int): Double {
        val dynamicMaxRise = calculateDynamicMaxRise(currentBG)
        val predictedRise = when (phase) {
            "early_rise" -> min(dynamicMaxRise * 0.6, trends.recentTrend * 0.8)
            "mid_rise" -> min(dynamicMaxRise * 0.8, trends.recentTrend * 0.6)
            "late_rise" -> min(dynamicMaxRise * 0.4, trends.recentTrend * 0.3)
            "peak" -> trends.recentTrend * 0.1
            else -> trends.recentTrend * 0.2
        }
        return currentBG + (predictedRise * minutesAhead / 60)
    }

    private fun predictIOBEffect(currentBG: Double, iob: Double, isf: Double, minutesAhead: Int): Double {
        if (iob <= 0.0 || isf <= 0.0) return currentBG
        val hours = minutesAhead / 60.0
        val baseDrop = (iob / max(1.0, isf)) * insulinSensitivityFactor
        val effectiveDrop = baseDrop * (1 - exp(-hours / 1.5))
        return currentBG - effectiveDrop
    }

    private fun predictBasalResponse(currentBG: Double, trends: TrendAnalysis, minutesAhead: Int): Double {
        return currentBG + (trends.recentTrend * minutesAhead / 60 * 0.3)
    }

    private fun calculateDynamicMaxRise(startBG: Double): Double {
        return when {
            startBG <= 4.0 -> 6.5
            startBG <= 5.0 -> 6.0
            startBG <= 6.0 -> 5.5
            startBG <= 7.0 -> 5.0
            startBG <= 8.0 -> 4.5
            startBG <= 9.0 -> 4.0
            startBG <= 10.0 -> 3.5
            startBG <= 12.0 -> 3.0
            startBG <= 14.0 -> 2.5
            else -> 2.0
        }
    }

    private fun isHypoRiskWithin(minutesAhead: Int, currentBG: Double, iob: Double, isf: Double, thresholdMmol: Double = 4.0): Boolean {
        val predicted = predictIOBEffect(currentBG, iob, isf, minutesAhead)
        return predicted < thresholdMmol
    }

    // ★★★ HOOFD PREDICTIE FUNCTIE ★★★
    fun predictRealTime(
        currentData: BGDataPoint,
        historicalData: List<BGDataPoint>,
        ISF: Double,
        minutesAhead: Int = 60,
        carbRatio: Double,
        targetBG: Double,
        currentIOB: Double,
        maxIOB: Double
    ): PredictionResult {
        val trends = analyzeTrends(historicalData)

        val carbsResult = calculateUnifiedCarbsDetection(
            historicalData = historicalData,
            robustTrends = calculateRobustTrends(historicalData),
            currentBG = currentData.bg,
            targetBG = targetBG,
            currentIOB = currentData.iob,
            maxIOB = maxIOB,
            effectiveCR = carbRatio
        )

        val detectedCarbs = carbsResult.detectedCarbs
        val mealState = if (detectedCarbs > 10.0) MealDetectionState.DETECTED else MealDetectionState.NONE
        val mealDetected = mealState != MealDetectionState.NONE
        val robustPhase = lastRobustTrends?.phase ?: "stable"

        updateMealStatusAutomatically(currentData, historicalData, trends, mealState)

        val prediction = when {
            mealInProgress -> predictMealResponse(currentData.bg, trends, robustPhase, minutesAhead)
            currentData.iob > minIOBForEffect -> predictIOBEffect(currentData.bg, currentData.iob, ISF, minutesAhead)
            else -> predictBasalResponse(currentData.bg, trends, minutesAhead)
        }

        return PredictionResult(
            value = prediction.coerceIn(3.5, 20.0),
            trend = trends.recentTrend,
            mealDetected = mealDetected,
            mealInProgress = mealInProgress,
            phase = robustPhase
        )
    }

    // ★★★ MEAL STATUS MANAGEMENT ★★★
    private fun updateMealStatusAutomatically(
        currentData: BGDataPoint,
        historicalData: List<BGDataPoint>,
        trends: TrendAnalysis,
        currentMealState: MealDetectionState
    ) {
        val currentTime = currentData.timestamp
        val robustPhase = lastRobustTrends?.phase ?: "stable"

        if (currentMealState != MealDetectionState.NONE) {
            mealDetectionState = currentMealState
        }

        if (peakDetected && trends.recentTrend < -0.5) {
            peakDetected = false
        }

        if (!mealInProgress && shouldStartMealPhase(historicalData, trends)) {
            mealInProgress = true
            lastMealTime = currentTime
            peakDetected = false
        }

        if (mealInProgress && !peakDetected && robustPhase == "peak") {
            peakDetected = true
        }

        if (mealInProgress && shouldEndMealPhase(currentData, historicalData, trends)) {
            val recentUpdate = storage.loadPendingLearningUpdates().lastOrNull {
                it.timestamp.isAfter(currentTime.minusMinutes(180))
            }
            val detectedCarbs = recentUpdate?.detectedCarbs ?: 0.0
            val peakConfidence = calculatePeakConfidence(historicalData, detectedCarbs)
            val mealType = getMealTypeFromHour()
            logMealPerformanceResult(currentData, historicalData, peakConfidence, mealType)
            mealInProgress = false
            lastMealTime = null
            peakDetected = false
        } else if (!mealInProgress && shouldStartMealPhase(historicalData, trends)) {
            mealInProgress = true
            lastMealTime = currentTime
            peakDetected = false
        }
    }

    private fun shouldStartMealPhase(historicalData: List<BGDataPoint>, trends: TrendAnalysis): Boolean {
        if (historicalData.size < 6) return false
        val consistentRise = checkConsistentRise(historicalData, 3)
        val strongRise = trends.recentTrend > 2.0 && trends.acceleration > 0.1
        val recentLow = historicalData.takeLast(6).any { it.bg < 4.0 }
        return (consistentRise || strongRise) && !recentLow
    }

    private fun shouldEndMealPhase(
        currentData: BGDataPoint,
        historicalData: List<BGDataPoint>,
        trends: TrendAnalysis
    ): Boolean {
        if (lastMealTime == null) return true
        val minutesSinceMeal = Minutes.minutesBetween(lastMealTime, currentData.timestamp).minutes

        if (minutesSinceMeal > 240) return true
        if (peakDetected && trends.recentTrend < -1.0 && minutesSinceMeal > 120) return true

        val startBG = historicalData.firstOrNull { it.timestamp == lastMealTime }?.bg ?: return false
        if (currentData.bg <= startBG && minutesSinceMeal > 90) return true
        if (minutesSinceMeal > 150 && abs(trends.recentTrend) < 0.3) return true

        return false
    }

    private fun getMealTypeFromHour(): String {
        val hour = DateTime.now().hourOfDay
        return when (hour) {
            in 6..10 -> "ontbijt"
            in 11..14 -> "lunch"
            in 17..21 -> "dinner"
            else -> "snack"
        }
    }

    // ★★★ LEARNING PERFORMANCE LOGGING ★★★
    private fun logMealPerformanceResult(
        currentData: BGDataPoint,
        historicalData: List<BGDataPoint>,
        peakConfidence: Double = 0.5,
        mealType: String = "unknown"
    ) {
        try {
            val recentUpdates = storage.loadPendingLearningUpdates().sortedByDescending { it.timestamp }
            val latestUpdate = recentUpdates.firstOrNull {
                Minutes.minutesBetween(it.timestamp, DateTime.now()).minutes < 240
            } ?: return

            val mealStart = latestUpdate.timestamp
            val peakData = storage.loadPeakDetectionData().filter {
                it.timestamp.isAfter(mealStart) && Minutes.minutesBetween(mealStart, it.timestamp).minutes < 240
            }.maxByOrNull { it.bg }

            val actualPeak = peakData?.bg ?: currentData.bg
            val timeToPeak = if (peakData != null) {
                Minutes.minutesBetween(mealStart, peakData.timestamp).minutes
            } else {
                Minutes.minutesBetween(mealStart, DateTime.now()).minutes
            }

            val outcome = when {
                actualPeak > 11.0 -> "TOO_HIGH"
                actualPeak < 6.0 -> "TOO_LOW"
                else -> "SUCCESS"
            }

            val finalPeakConfidence = if (peakConfidence == 0.5) {
                calculatePeakConfidence(historicalData, latestUpdate.detectedCarbs)
            } else {
                peakConfidence
            }

            val finalMealType = if (mealType == "unknown") {
                latestUpdate.mealType
            } else {
                mealType
            }

            if (outcome == "TOO_LOW") {
                learningEngine.updateLearningFromHypoAfterMeal(
                    mealType = finalMealType,
                    bgEnd = currentData.bg
                )
            }

            val performanceResult = FCLLearningEngine.MealPerformanceResult(
                timestamp = DateTime.now(),
                detectedCarbs = latestUpdate.detectedCarbs,
                givenDose = latestUpdate.givenDose,
                startBG = latestUpdate.startBG,
                predictedPeak = latestUpdate.expectedPeak,
                actualPeak = actualPeak,
                timeToPeak = timeToPeak,
                parameters = FCLLearningEngine.MealParameters(
                    bolusPercEarly = preferences.get(IntKey.bolus_perc_early).toDouble(),
                    bolusPercDay = preferences.get(IntKey.bolus_perc_day).toDouble(),
                    bolusPercNight = preferences.get(IntKey.bolus_perc_night).toDouble(),
                    peakDampingFactor = preferences.get(IntKey.peak_damping_percentage).toDouble()/100.0,
                    hypoRiskFactor = preferences.get(IntKey.hypo_risk_percentage).toDouble()/100.0,
                    timestamp = DateTime.now()
                ),
                outcome = outcome,
                peakConfidence = finalPeakConfidence,
                mealType = finalMealType
            )

            storage.saveMealPerformanceResult(performanceResult)

        } catch (e: Exception) {
            // Logging
        }
    }

    // ★★★ INSULINE ADVIES FUNCTIES ★★★
    fun getInsulinAdvice(
        currentData: BGDataPoint,
        historicalData: List<BGDataPoint>,
        ISF: Double,
        targetBG: Double,
        carbRatio: Double,
        currentIOB: Double,
        maxIOB: Double
    ): InsulinAdvice {
        if (historicalData.size < 10) {
            return InsulinAdvice(0.0, "Insufficient data", 0.0)
        }

        val trends = analyzeTrends(historicalData)
        val realTimePrediction = predictRealTime(currentData, historicalData, ISF, 60, carbRatio, targetBG, currentIOB, maxIOB)

        if (shouldWithholdInsulin(currentData, trends, targetBG, maxIOB, historicalData)) {
            return InsulinAdvice(0.0, "Safety: BG too low or falling", 0.9)
        }

        if (checkForCarbCorrection(historicalData)) {
            return InsulinAdvice(0.0, "Likely carb correction rise", 0.7)
        }

        if (realTimePrediction.value > targetBG) {
            val dose = calculateDynamicDose(currentData, realTimePrediction.value, ISF, targetBG)
            if (dose > 0) {
                return InsulinAdvice(
                    dose = dose,
                    reason = "Preventive dose for predicted high: ${"%.1f".format(realTimePrediction.value)} mmol/L",
                    confidence = calculateConfidence(trends),
                    predictedValue = realTimePrediction.value,
                    mealDetected = realTimePrediction.mealDetected,
                    phase = realTimePrediction.phase
                )
            }
        }

        return InsulinAdvice(0.0, "No action needed - within target range", 0.8)
    }

    private fun calculateDynamicDose(
        currentData: BGDataPoint,
        predictedValue: Double,
        ISF: Double,
        targetBG: Double
    ): Double {
        val excess = predictedValue - targetBG
        val requiredCorrection = excess / ISF
        val effectiveIOB = max(0.0, currentData.iob - 0.5)
        val netCorrection = max(0.0, requiredCorrection - effectiveIOB)
        val overallAggressiveness = getCurrentBolusAggressiveness() / 100.0
        val conservativeDose = netCorrection * 0.6 * dailyReductionFactor * overallAggressiveness
        val limitedDose = min(conservativeDose, preferences.get(DoubleKey.max_bolus))
        return roundDose(limitedDose)
    }

    private fun shouldWithholdInsulin(
        currentData: BGDataPoint,
        trends: TrendAnalysis,
        targetBG: Double,
        maxIOB: Double,
        historicalData: List<BGDataPoint>
    ): Boolean {
        if (isLikelyHypoRecovery(currentData.bg, historicalData, trends)) {
            return true
        }

        val isHighBG = currentData.bg > targetBG + 3.0

        return when {
            currentData.bg < gethypoThreshold() + 1.0 -> true
            currentData.iob > maxIOB * (if (isHighBG) 0.7 else 0.45) && currentData.bg < targetBG + (if (isHighBG) 2.0 else 1.0) -> true
            currentData.bg < gethypoThreshold() + 2.0 && trends.recentTrend < -1.0 -> true
            else -> false
        }
    }

    private fun explainWithholdReason(currentData: BGDataPoint, trends: TrendAnalysis, targetBG: Double, maxIOB: Double): String {
        val iobRatio = currentData.iob / maxIOB
        return when {
            currentData.bg < 5.0 -> "Withheld: BG ${"%.1f".format(currentData.bg)} < 5.0 mmol/L (hypo risk)"
            currentData.bg < 5.8 && trends.recentTrend < -0.3 -> "Withheld: BG ${"%.1f".format(currentData.bg)} and falling fast (${"%.2f".format(trends.recentTrend)} mmol/L/h)"
            currentData.bg < 6.5 && trends.recentTrend < -0.5 -> "Withheld: BG ${"%.1f".format(currentData.bg)} with strong downward trend (${"%.2f".format(trends.recentTrend)} mmol/L/h)"
            iobRatio > 0.5 && currentData.bg < targetBG + 1.0 -> "Withheld: IOB ${"%.2f".format(currentData.iob)} > 1.8U and BG ${"%.1f".format(currentData.bg)} < target+1.0 (${"%.1f".format(targetBG + 1.0)})"
            iobRatio > 0.25 && trends.recentTrend < -0.3 -> "Withheld: IOB ${"%.2f".format(currentData.iob)} and falling trend (${"%.2f".format(trends.recentTrend)} mmol/L/h)"
            else -> "Withheld: unspecified safety condition"
        }
    }

    private fun checkForCarbCorrection(historicalData: List<BGDataPoint>): Boolean {
        if (historicalData.size < 6) return false
        val recentLow = historicalData.takeLast(6).any { it.bg < gethypoThreshold() }
        val currentRising = calculateRecentTrend(historicalData, 2) > 2.0
        return recentLow && currentRising
    }

    private fun calculateConfidence(trends: TrendAnalysis): Double {
        return when {
            abs(trends.recentTrend) > 1.0 && abs(trends.acceleration) < 0.5 -> 0.85
            abs(trends.acceleration) > 1.0 -> 0.6
            else -> 0.7
        }
    }

    // ★★★ HOOFD ADVIES FUNCTIE ★★★
    fun getEnhancedInsulinAdvice(
        currentData: BGDataPoint,
        historicalData: List<BGDataPoint>,
        currentISF: Double,
        targetBG: Double,
        carbRatio: Double,
        currentIOB: Double,
        maxBolus: Double,
        maxIOB: Double
    ): EnhancedInsulinAdvice {
        try {
            val trends = analyzeTrends(historicalData)

            fun logAndReturn(advice: EnhancedInsulinAdvice): EnhancedInsulinAdvice {
                loggingHelper.logToAnalysisCSV(
                    fclAdvice = advice,
                    currentData = currentData,
                    currentISF = currentISF,
                    currentIOB = currentIOB
                )
                return advice
            }

            // ★★★ EÉNMALIGE UPDATE ★★★
            updateActivityFromSteps()
            updateResistentieIndienNodig()
          //  berekenStappenAdjustment()

            val effectiveISF = getEffectiveISF()
            val effectiveTarget = getEffectiveTarget()

            // VALIDATIE VAN LEARNING PARAMETERS
            if (learningProfile.personalCarbRatio.isNaN() || learningProfile.personalCarbRatio <= 0) {
                learningProfile = learningProfile.copy(personalCarbRatio = 1.0)
            }
            if (learningProfile.personalISF.isNaN() || learningProfile.personalISF <= 0) {
                learningProfile = learningProfile.copy(personalISF = 1.0)
            }

            resetLearningDataIfNeeded()

            // ★★★ ALTIJD ROBUUSTE FASE GEBRUIKEN ★★★
            val robustTrends = calculateRobustTrends(historicalData)
            val robustPhase = robustTrends.phase

            processPendingLearningUpdates()
            processPendingCorrectionUpdates()
            cleanUpMeals()

            val cobNow = getCarbsOnBoard()
            val basicAdvice = getInsulinAdvice(
                currentData, historicalData,
                effectiveISF, effectiveTarget, carbRatio, currentIOB, maxIOB
            )

            processPendingLearningUpdates()
            processPendingCorrectionUpdates()
            storage.saveLearningProfile(learningProfile)

            // Placeholders
            var finalDose = 0.0
            var finalReason = ""
            var finalDeliver = false
            var finalMealDetected = false
            var finalDetectedCarbs = 0.0
            var finalReservedBolus = 0.0
            var finalPhase = "stable"
            var finalConfidence = learningProfile.learningConfidence
            var predictedPeak = basicAdvice.predictedValue ?: currentData.bg
            var finalCOB = cobNow

            // ★★★ PERSISTENT HIGH BG CHECK ★★★
            val persistentResult = persistentHelper.checkPersistentHighBG(
                historicalData,
                currentIOB,
                maxIOB,
                ::isNachtTime
            )

            val hasPersistentBolus = persistentResult.shouldDeliver && persistentResult.extraBolus > 0.05
            val persistentBolusAmount = if (hasPersistentBolus) persistentResult.extraBolus else 0.0

            if (hasPersistentBolus) {
                storeMealForLearning(
                    detectedCarbs = 0.0,
                    givenDose = persistentBolusAmount,
                    startBG = currentData.bg,
                    expectedPeak = currentData.bg,
                    mealType = "persistent_correction"
                )

                val persistentAdvice = EnhancedInsulinAdvice(
                    dose = persistentBolusAmount,
                    reason = "Persistent High BG",
                    confidence = finalConfidence,
                    predictedValue = predictedPeak,
                    mealDetected = finalMealDetected,
                    detectedCarbs = finalDetectedCarbs,
                    shouldDeliverBolus = true,
                    phase = finalPhase,
                    learningMetrics = FCLLearningEngine.LearningMetrics(
                        confidence = learningProfile.learningConfidence,
                        samples = learningProfile.totalLearningSamples,
                        carbRatioAdjustment = learningProfile.personalCarbRatio,
                        isfAdjustment = learningProfile.personalISF,
                        mealTimeFactors = learningProfile.mealTimingFactors,
                    ),
                    reservedDose = finalReservedBolus,
                    carbsOnBoard = finalCOB,
                    Target_adjust = currentStappenTargetAdjust,
                    ISF_adjust = currentStappenPercentage,
                    activityLog = currentStappenLog,
                    resistentieLog = resistanceHelper.getCurrentResistanceLog(),
                    effectiveISF = effectiveISF,
                    MathBolusAdvice = lastMathBolusAdvice,
                    mathPhase = lastRobustTrends?.phase ?: "uncertain",
                    mathSlope = lastRobustTrends?.firstDerivative ?: 0.0,
                    mathAcceleration = lastRobustTrends?.secondDerivative ?: 0.0,
                    mathConsistency = lastRobustTrends?.consistency ?: 0.0,
                    mathDirectionConsistency = if (recentDataForAnalysis.isNotEmpty()) calculateDirectionConsistencyFromData(recentDataForAnalysis) else 0.0,
                    mathMagnitudeConsistency = if (recentDataForAnalysis.isNotEmpty()) calculateMagnitudeConsistencyFromData(recentDataForAnalysis) else 0.0,
                    mathPatternConsistency = if (recentDataForAnalysis.isNotEmpty()) calculatePatternConsistency(recentDataForAnalysis) else 0.0,
                    mathDataPoints = recentDataForAnalysis.size,
                    debugLog = "Persistent",
                )

                return logAndReturn(persistentAdvice)
            }

            // Safety checks
            val safetyBlock = shouldWithholdInsulin(currentData, trends, effectiveTarget, maxIOB, historicalData)
            if (safetyBlock) {
                finalDose = 0.0
                finalReason = "Safety: ${explainWithholdReason(currentData, trends, effectiveTarget, maxIOB)}"
                finalDeliver = false
                finalPhase = "safety"
            }

            if (finalDeliver && finalDose > 0) {
                if (shouldBlockBolusForShortTermTrend(currentData, historicalData, trends, maxIOB)) {
                    val shortTermTrend = calculateShortTermTrend(historicalData)
                    finalDose = 0.0
                    finalDeliver = false
                    finalReason = "Safety: Strong short-term decline (${"%.1f".format(shortTermTrend)} mmol/L/h)"
                    finalPhase = "safety"
                }
            }

            if (finalDeliver && finalDose > 0) {
                if (shouldBlockCorrectionForTrendReversal(currentData, historicalData, trends, maxIOB)) {
                    finalDose = 0.0
                    finalDeliver = false
                    finalReason = "Safety: Trend reversing to decline (IOB=${"%.1f".format(currentData.iob)}U)"
                    finalPhase = "safety"
                }
            }

            // ★★★ HYPO RECOVERY SAFETY CHECK ★★★
            if (shouldBlockMealDetectionForHypoRecovery(currentData.bg, historicalData, trends)) {
                finalMealDetected = false
                finalDetectedCarbs = 0.0
                finalReason = "Safety: Meal detection blocked (hypo recovery)"
                finalPhase = "safety_monitoring"
                finalDeliver = false
            }

            // ★★★ UNIFORME CARB DETECTIE ★★★
            val carbsResult = calculateUnifiedCarbsDetection(
                historicalData = historicalData,
                robustTrends = robustTrends,
                currentBG = currentData.bg,
                targetBG = effectiveTarget,
                currentIOB = currentIOB,
                maxIOB = maxIOB,
                effectiveCR = getEffectiveCarbRatio()
            )

            val detectedCarbs = carbsResult.detectedCarbs
            val mealState = if (detectedCarbs > 10.0 && carbsResult.confidence > 0.4) {
                MealDetectionState.DETECTED
            } else {
                MealDetectionState.NONE
            }

            lastMealDetectionDebug = carbsResult.detectionReason

        /*    // ★★★ LOG MAALTIJD DATA VOOR ANALYSE ★★★
            logMealDataForAnalysis(
                currentData = currentData,
                detectedCarbs = detectedCarbs,
                mealDetected = mealState != MealDetectionState.NONE,
                dose = 0.0, // Wordt later bijgewerkt met finale dose
                reason = carbsResult.detectionReason
            )   */

            // ★★★ WISKUNDIGE FASE HERKENNING ★★★
            val mathBolusAdvice = getMathematicalBolusAdvice(
                robustTrends = robustTrends,
                detectedCarbs = detectedCarbs,
                currentBG = currentData.bg,
                targetBG = effectiveTarget,
                historicalData = historicalData,
                currentIOB = currentIOB,
                maxIOB = maxIOB
            )

            // ★★★ OPSLAAN VOOR STATUS WEERGAVE ★★★
            lastRobustTrends = robustTrends
            lastMathBolusAdvice = "Phase: ${robustTrends.phase} | " +
                "Immediate: ${(mathBolusAdvice.immediatePercentage * 100).toInt()}% | " +
                "Reserved: ${(mathBolusAdvice.reservedPercentage * 100).toInt()}% | " +
                "Reason: ${mathBolusAdvice.reason}"
            lastMathAnalysisTime = DateTime.now()

            // ★★★ ALTIJD COB BIJWERKEN BIJ GEDETECTEERDE CARBS ★★★
            if (detectedCarbs > 5.0 && carbsResult.confidence > 0.3) {
                addOrUpdateActiveMeal(detectedCarbs, DateTime.now())
                finalCOB = getCarbsOnBoard()
                finalDetectedCarbs = detectedCarbs
                finalMealDetected = mealState != MealDetectionState.NONE
            }

            // Wiskundige bolus logica
            if (robustTrends.consistency > preferences.get(DoubleKey.phase_min_consistency) &&
                mathBolusAdvice.immediatePercentage > 0 && detectedCarbs > 0) {

                val effectiveCR = getEffectiveCarbRatio()
                val totalCarbBolus = detectedCarbs / effectiveCR
                val mealType = getMealTypeFromHour()
                val currentHour = DateTime.now().hourOfDay
                val hypoAdjustedFactor = getHypoAdjustedMealFactor(mealType, currentHour)
                val adjustedTotalCarbBolus = totalCarbBolus * hypoAdjustedFactor

                val mathImmediateBolus = adjustedTotalCarbBolus * mathBolusAdvice.immediatePercentage
                val mathReservedBolus = adjustedTotalCarbBolus * mathBolusAdvice.reservedPercentage

                if (robustTrends.consistency > 0.7 || detectedCarbs > 20) {
                    finalDose = mathImmediateBolus
                    finalReservedBolus = mathReservedBolus
                    finalReason = mathBolusAdvice.reason
                    finalPhase = robustTrends.phase

                    if (finalReservedBolus > 0.1 && finalDetectedCarbs > 5) {
                        pendingReservedBolus = finalReservedBolus
                        pendingReservedCarbs = finalDetectedCarbs
                        pendingReservedTimestamp = DateTime.now()
                        pendingReservedPhase = robustTrends.phase
                        lastReservedBolusDebug = "RESERVED: ${round(finalReservedBolus,1)}U for ${finalDetectedCarbs.toInt()}g carbs"
                    } else {
                        val ResBolus = round(finalReservedBolus,1)
                        lastReservedBolusDebug = "NO_RESERVED: carbs=${finalDetectedCarbs.toInt()}, reservedBolus=$ResBolus"
                    }

                    storeMealForLearning(
                        detectedCarbs = detectedCarbs,
                        givenDose = finalDose,
                        startBG = currentData.bg,
                        expectedPeak = predictedPeak,
                        mealType = robustTrends.phase
                    )
                }
            }

            // Safety checks voor false positives
            val sensorIssue = detectSensorIssue(historicalData)
            val sensorError = sensorIssue != null
            val mealConfidenceLevel = validateMealPattern(historicalData)
            val isLikelyMeal = distinguishMealFromSnack(historicalData, detectedCarbs)
            val shouldAdjust = shouldAdjustOrCancelBolus(historicalData, mealState)

            // Safety: blokkeer bij sensor errors
            if (sensorIssue != null) {
                finalDose = 0.0
                finalDeliver = false
                finalMealDetected = false

                when (sensorIssue) {
                    SensorIssueType.COMPRESSION_LOW -> {
                        finalReason = "Safety: Compression low detected - withholding insulin"
                        finalPhase = "safety_compression_low"
                    }
                    SensorIssueType.JUMP_TOO_LARGE -> {
                        finalReason = "Safety: Sensor error (jump too large) - withholding insulin"
                        finalPhase = "safety_sensor_error"
                    }
                    SensorIssueType.OSCILLATION -> {
                        finalReason = "Safety: Sensor error (oscillation) - withholding insulin"
                        finalPhase = "safety_sensor_error"
                    }
                }
            }

            // Meal detection wanneer BG boven target is
            else if (mealState != MealDetectionState.NONE && !sensorError && finalDeliver) {
                val mealConfidence = calculateMealConfidence(historicalData, detectedCarbs)

                if (mealConfidence > 0.4 && isLikelyMeal && detectedCarbs > 15 &&
                    canDetectMealAboveTarget(currentData.bg, effectiveTarget, trends, currentIOB, maxIOB)) {

                    finalCOB = getCarbsOnBoard()

                    val (immediateBolus, reservedBolus, bolusReason) = getMathematicalBolusAsOnlyMethod(
                        robustTrends = robustTrends,
                        detectedCarbs = detectedCarbs,
                        currentBG = currentData.bg,
                        targetBG = effectiveTarget,
                        historicalData = historicalData,
                        currentIOB = currentIOB,
                        maxIOB = maxIOB,
                        effectiveCR = getEffectiveCarbRatio()
                    )

                    val correctionComponent = max(0.0, (currentData.bg - effectiveTarget) / effectiveISF) * 0.3

                    finalDose = immediateBolus + correctionComponent
                    finalReason = "Meal+Correction: ${"%.1f".format(detectedCarbs)}g + BG=${"%.1f".format(currentData.bg)} | $bolusReason"
                    finalMealDetected = true
                    finalDetectedCarbs = detectedCarbs
                    finalPhase = "meal_correction_combination"

                    storeMealForLearning(
                        detectedCarbs = detectedCarbs,
                        givenDose = finalDose,
                        startBG = currentData.bg,
                        expectedPeak = predictedPeak,
                        mealType = getMealTypeFromHour()
                    )

                    if (reservedBolus > 0.1) {
                        pendingReservedBolus = reservedBolus
                        pendingReservedCarbs = detectedCarbs
                        pendingReservedTimestamp = DateTime.now()
                        pendingReservedPhase = robustPhase
                        finalReason += " | Reserved: ${"%.2f".format(reservedBolus)}U"
                    }
                }
            }

            // Safety check voor extreme stijgingen
            else if (!isLikelyMeal && detectedCarbs < 15) {
                val iobCapacity = maxIOB - currentIOB
                val hasIOBCapacity = iobCapacity > 0.5
                val isExtremeRise = robustTrends.phase in listOf("early_rise", "mid_rise") &&
                    robustTrends.firstDerivative > 5.0 &&
                    robustTrends.consistency > 0.6
                val isVeryHighBG = currentData.bg > 10.0

                val shouldBlock = when {
                    !hasIOBCapacity && !isExtremeRise && !isVeryHighBG -> true
                    robustTrends.phase in listOf("declining", "peak", "stable") && detectedCarbs < 10 -> true
                    hasIOBCapacity && isExtremeRise -> false
                    hasIOBCapacity && isVeryHighBG -> false
                    else -> true
                }

                if (shouldBlock) {
                    finalDose = 0.0
                    finalReason = "Safety: Uncertain pattern (IOB: ${"%.1f".format(currentIOB)}/${maxIOB}U, carbs: ${"%.1f".format(detectedCarbs)}g)"
                    finalDeliver = false
                    finalPhase = "safety_monitoring"
                    finalMealDetected = false
                } else {
                    finalDetectedCarbs = maxOf(detectedCarbs, 25.0)
                    finalMealDetected = true
                    finalReason = "Extreme rise override (slope: ${"%.1f".format(robustTrends.firstDerivative)}, IOB cap: ${"%.1f".format(iobCapacity)}U)"
                }
            }

            // Safety: pas aan bij tegenvallende stijging
            else if (shouldAdjust && mealInProgress) {
                finalMealDetected = true
                finalDetectedCarbs = detectedCarbs * 0.5
            }

            // GEFASEERDE MEAL PROCESSING MET IOB REDUCTIE
            else if (mealState != MealDetectionState.NONE && !sensorError && !finalMealDetected) {
                val mealConfidence = calculateMealConfidence(historicalData, detectedCarbs)

                if (mealConfidence > 0.4 && detectedCarbs > 10) {
                    addOrUpdateActiveMeal(detectedCarbs, DateTime.now())
                    finalCOB = getCarbsOnBoard()

                    val (immediateBolus, reservedBolus, bolusReason) = getMathematicalBolusAsOnlyMethod(
                        robustTrends = robustTrends,
                        detectedCarbs = detectedCarbs,
                        currentBG = currentData.bg,
                        targetBG = effectiveTarget,
                        historicalData = historicalData,
                        currentIOB = currentIOB,
                        maxIOB = maxIOB,
                        effectiveCR = getEffectiveCarbRatio()
                    )

                    if (immediateBolus <= 0.0) {
                        finalDose = 0.0
                        finalReason = "Math-Safety: ${bolusReason}"
                        finalDeliver = false
                        finalPhase = "safety_math_blocked"
                        finalMealDetected = false
                    } else {
                        val finalImmediateBolus = immediateBolus
                        val finalReservedBolus = reservedBolus

                        if (finalReservedBolus > 0.1) {
                            pendingReservedBolus = finalReservedBolus
                            pendingReservedCarbs = detectedCarbs
                            pendingReservedTimestamp = DateTime.now()
                            pendingReservedPhase = robustPhase
                        }

                        finalDose = finalImmediateBolus
                        finalReason = bolusReason
                        finalDeliver = finalImmediateBolus > 0.05
                        finalMealDetected = true
                        finalDetectedCarbs = detectedCarbs
                        finalPhase = if (mealConfidence > 0.7) "meal_high_confidence" else "meal_medium_confidence"
                        finalConfidence = mealConfidence

                        if (finalReservedBolus > 0.1) {
                            finalReason += " | Reserved: ${"%.2f".format(finalReservedBolus)}U"
                        }

                        if (finalDeliver && finalDose > 0.0) {
                            storeMealForLearning(
                                detectedCarbs = detectedCarbs,
                                givenDose = finalDose,
                                startBG = currentData.bg,
                                expectedPeak = predictedPeak,
                                mealType = getMealTypeFromHour()
                            )
                        }
                    }
                } else {
                    finalDose = 0.0
                    finalReason = "Monitoring uncertain pattern (confidence: ${"%.0f".format(mealConfidence * 100)}%)"
                    finalDeliver = false
                    finalPhase = "safety_monitoring"
                    finalMealDetected = false
                }
            }

            // ★★★ RESERVED BOLUS RELEASE LOGIC ★★★
            decayReservedBolusOverTime()

            if (pendingReservedBolus > 0.1 && shouldReleaseReservedBolus(
                    currentData.bg, effectiveTarget, trends, historicalData, currentIOB, maxIOB)) {

                val minutesSinceLastBolus = lastBolusTime?.let {
                    Minutes.minutesBetween(it, DateTime.now()).minutes
                } ?: Int.MAX_VALUE

                if (minutesSinceLastBolus >= 10) {
                    val releasedBolus = calculateReservedBolusRelease(
                        currentData.bg,
                        effectiveTarget,
                        currentIOB,
                        maxIOB,
                        maxBolus
                    )
                    if (releasedBolus > 0.05) {
                        finalDose += releasedBolus
                        finalReason += if (finalReason.contains("Reserved")) {
                            " | +${"%.2f".format(releasedBolus)}U released"
                        } else {
                            " | Released reserved: ${"%.2f".format(releasedBolus)}U"
                        }

                        storeMealForLearning(
                            detectedCarbs = pendingReservedCarbs,
                            givenDose = releasedBolus,
                            startBG = currentData.bg,
                            expectedPeak = predictedPeak,
                            mealType = getMealTypeFromHour()
                        )

                        finalDeliver = true
                        lastBolusTime = DateTime.now()
                    }
                }
            }

            // Correction (alleen als GEEN maaltijd gedetecteerd)
            else if (!finalMealDetected && currentData.bg > effectiveTarget + 0.5) {
                var correctionDose = getMathematicalCorrectionDose(
                    robustTrends = robustTrends,
                    currentBG = currentData.bg,
                    targetBG = effectiveTarget,
                    effectiveISF = effectiveISF,
                    currentIOB = currentIOB,
                    maxIOB = maxIOB
                )

                if (trends.recentTrend > 0.2) {
                    val factor = 1.0 + (trends.recentTrend / 0.3).coerceAtMost(2.0)
                    correctionDose *= factor
                }

                correctionDose = getSafeDoseWithLearning(
                    correctionDose, null,
                    learningProfile.learningConfidence, currentIOB, trends, robustPhase, maxIOB
                )

                if (trends.recentTrend <= 0.0 && currentData.bg < effectiveTarget + 3.0) {
                    correctionDose *= preferences.get(IntKey.peak_damping_percentage).toDouble()/100.0
                }

                if (isHypoRiskWithin(120, currentData.bg, currentIOB, effectiveISF)) {
                    correctionDose *= preferences.get(IntKey.hypo_risk_percentage).toDouble()/100.0
                }

                val deltaCorr = (predictedPeak - currentData.bg).coerceAtLeast(0.0)
                val startBoostFactorCorr = 1.0 + (deltaCorr / 10.0).coerceIn(0.0, 0.3)
                if (startBoostFactorCorr > 1.0) {
                    correctionDose *= startBoostFactorCorr
                    finalReason += " | StartCorrectionBoost(x${"%.2f".format(startBoostFactorCorr)})"
                }

                val cappedCorrection = min(roundDose(correctionDose), maxBolus)
                val deliverCorrection = (trends.recentTrend > 0.5 && currentData.bg > effectiveTarget + 1.0 &&
                    !isTrendReversingToDecline(historicalData, trends))

                finalDose = cappedCorrection
                finalPhase = "correction"
                finalDeliver = deliverCorrection
                finalReason =
                    "Correction: BG=${"%.1f".format(currentData.bg)} > target=${"%.1f".format(effectiveTarget)}" +
                        if (!finalDeliver) " | Stable/decline -> no bolus" else ""

                finalConfidence = basicAdvice.confidence

                if (currentIOB >= maxIOB * 0.9 && trends.recentTrend <= 0.0) {
                    finalDose = 0.0
                    finalDeliver = false
                    finalReason += " | Blocked by high IOB safety"
                }

                try {
                    if (finalDeliver && finalDose > 0.0) {
                        val predictedDrop = finalDose * effectiveISF
                        val corrUpdate = FCLLearningEngine.CorrectionUpdate(
                            insulinGiven = finalDose,
                            predictedDrop = predictedDrop,
                            bgStart = currentData.bg,
                            timestamp = DateTime.now()
                        )
                        storage.savePendingCorrectionUpdate(corrUpdate)
                    }
                } catch (ex: Exception) {
                    // Logging
                }
            }

            // Geen actie
            else if (!finalMealDetected) {
                finalDose = 0.0
                finalReason = "No action: BG=${"%.1f".format(currentData.bg)} ~ target=${"%.1f".format(effectiveTarget)}"
                finalPhase = "stable"
                finalDeliver = false
                finalConfidence = learningProfile.learningConfidence
            }

            // Early Boost logic
            predictedPeak = basicAdvice.predictedValue ?: (currentData.bg + 2.0)

            if (currentData.bg in 8.0..9.9 && predictedPeak > 10.0) {
                val delta = (predictedPeak - currentData.bg).coerceAtLeast(0.0)
                val boostFactor = (getCurrentBolusAggressiveness()/100.0) * (1.0 + (delta / 10.0).coerceIn(0.0, 0.3))

                val proposed = (finalDose * boostFactor).coerceAtMost(maxBolus)
                val overTarget = (predictedPeak - 10.0).coerceAtLeast(0.0)
                val iobBoostPercent = (overTarget / 5.0).coerceIn(0.0, 0.5)
                val dynamicIOBcap = maxIOB * (1.0 + iobBoostPercent)

                if (currentIOB >= maxIOB * 0.9 && trends.recentTrend <= 0.0) {
                    finalReason += " | EarlyBoost blocked by high IOB safety"
                } else {
                    if (currentIOB + proposed <= dynamicIOBcap) {
                        finalDose = proposed
                        if (finalMealDetected) {
                            finalReason += " | EarlyMealBoost(x${"%.2f".format(boostFactor)}) peak=${"%.1f".format(predictedPeak)}"
                        } else {
                            finalReason += " | EarlyCorrectionBoost(x${"%.2f".format(boostFactor)}) peak=${"%.1f".format(predictedPeak)}"
                        }
                    } else {
                        val allowed = (dynamicIOBcap - currentIOB).coerceAtLeast(0.0)
                        finalDose = allowed.coerceAtMost(maxBolus)
                        finalReason += " | EarlyBoost capped by dynamic IOB cap"
                    }
                }
            }

            // Voorspelling leegmaken bij dalende trend
            if (trends.recentTrend <= 0.0) {
                predictedPeak = currentData.bg
                finalReason += " | No prediction (falling/stable trend)"
            }

            // ★★★ SCHRIJF COB NAAR SHAREDPREFERENCES ★★★
            try {
                storage.saveCurrentCOB(finalCOB)
            } catch (e: Exception) {
                // Logging
            }

            // ★★★ VEILIGHEID: Controleer of de totale dosis niet te hoog is ★★★
            if (finalDeliver && finalDose > maxBolus) {
                finalDose = maxBolus
                finalReason += " | Capped at maxBolus ${"%.2f".format(maxBolus)}U"
            }

            // ★★★ Track de afgegeven bolus voor status weergave ★★★
            if (finalDeliver && finalDose > 0.05) {
                lastDeliveredBolus = finalDose
                lastBolusReason = finalReason
                lastBolusTime = DateTime.now()
            }

            // ★★★ BIJWERKEN CARBS TRACKING VOOR STATUS ★★★
            lastDetectedCarbs = finalDetectedCarbs
            lastCarbsOnBoard = finalCOB
            lastCOBUpdateTime = DateTime.now()
            lastCalculatedBolus = finalDose
            lastShouldDeliver = finalDeliver

            // ★★★ BOUW DEBUG LOG VOOR CSV ★★★
            val debugLog = StringBuilder().apply {
                append("MealDetect: $lastMealDetectionDebug")
                append(" | COB: $lastCOBDebug")
                append(" | Reserved: $lastReservedBolusDebug")
                if (detectedCarbs > 0) {
                    append(" | MathCarbs: ${detectedCarbs.toInt()}g")
                }
            }.toString()


            // === Centrale return ===
            val enhancedAdvice = EnhancedInsulinAdvice(
                dose = finalDose,
                reason = finalReason,
                confidence = finalConfidence,
                predictedValue = predictedPeak,
                mealDetected = finalMealDetected,
                detectedCarbs = finalDetectedCarbs,
                shouldDeliverBolus = finalDeliver,
                phase = finalPhase,
                learningMetrics = FCLLearningEngine.LearningMetrics(
                    confidence = learningProfile.learningConfidence,
                    samples = learningProfile.totalLearningSamples,
                    carbRatioAdjustment = learningProfile.personalCarbRatio,
                    isfAdjustment = learningProfile.personalISF,
                    mealTimeFactors = learningProfile.mealTimingFactors,
                ),
                reservedDose = finalReservedBolus,
                carbsOnBoard = finalCOB,
                Target_adjust = currentStappenTargetAdjust,
                ISF_adjust = currentStappenPercentage,
                activityLog = currentStappenLog,
                resistentieLog = resistanceHelper.getCurrentResistanceLog(),
                effectiveISF = effectiveISF,
                MathBolusAdvice = if (hasPersistentBolus) "Persistent " else "" + lastMathBolusAdvice,
                mathPhase = lastRobustTrends?.phase ?: "uncertain",
                mathSlope = lastRobustTrends?.firstDerivative ?: 0.0,
                mathAcceleration = lastRobustTrends?.secondDerivative ?: 0.0,
                mathConsistency = lastRobustTrends?.consistency ?: 0.0,
                mathDirectionConsistency = if (recentDataForAnalysis.isNotEmpty()) calculateDirectionConsistencyFromData(recentDataForAnalysis) else 0.0,
                mathMagnitudeConsistency = if (recentDataForAnalysis.isNotEmpty()) calculateMagnitudeConsistencyFromData(recentDataForAnalysis) else 0.0,
                mathPatternConsistency = if (recentDataForAnalysis.isNotEmpty()) calculatePatternConsistency(recentDataForAnalysis) else 0.0,
                mathDataPoints = recentDataForAnalysis.size,
                debugLog = debugLog,
            )

            return logAndReturn(enhancedAdvice)

        } catch (e: Exception) {
            return EnhancedInsulinAdvice(
                dose = 0.0,
                reason = "FCL error: ${e.message}",
                confidence = 0.0,
                predictedValue = null,
                mealDetected = false,
                detectedCarbs = 0.0,
                shouldDeliverBolus = false,
                phase = "error",
                learningMetrics = null,
                reservedDose = 0.0,
                carbsOnBoard = 0.0,
                Target_adjust = 0.0,
                ISF_adjust = 100.0,
                activityLog = "Error",
                resistentieLog = "Error",
                effectiveISF = currentISF,
                MathBolusAdvice = "Error in calculation",
                mathPhase = "error",
                mathSlope = 0.0,
                mathAcceleration = 0.0,
                mathConsistency = 0.0,
                mathDirectionConsistency = 0.0,
                mathMagnitudeConsistency = 0.0,
                mathPatternConsistency = 0.0,
                mathDataPoints = 0,
                debugLog = "Exception: ${e.message}"
            )
        }
    }

    // ★★★ HELPER FUNCTIES ★★★
    private fun getPhaseSpecificAggressiveness(phase: String): Double {
        val overallAggressiveness = getCurrentBolusAggressiveness() / 100.0
        return when (phase) {
            "early_rise" -> (preferences.get(IntKey.bolus_perc_early).toDouble() / 100.0) * overallAggressiveness
            "mid_rise" -> (preferences.get(IntKey.bolus_perc_mid).toDouble() / 100.0) * overallAggressiveness
            "late_rise" -> (preferences.get(IntKey.bolus_perc_late).toDouble() / 100.0) * overallAggressiveness
            "peak" -> 0.0
            else -> overallAggressiveness
        }
    }

    private fun getSafeDoseWithLearning(
        calculatedDose: Double,
        learnedDose: Double?,
        confidence: Double,
        currentIOB: Double,
        trends: TrendAnalysis,
        phase: String = "stable",
        MaxIOB: Double
    ): Double {
        val base = when {
            confidence > 0.8 -> learnedDose ?: calculatedDose
            confidence > 0.6 -> (learnedDose ?: calculatedDose) * 0.85
            else -> calculatedDose * 0.7
        }

        val overallAggressiveness = getCurrentBolusAggressiveness() / 100.0
        val phaseFactor = getPhaseSpecificAggressiveness(phase)
        val iobFactor = when {
            currentIOB > MaxIOB * 0.5 -> 0.45
            currentIOB > MaxIOB * 0.25 -> 0.7
            else -> 1.0
        }

        val accelPenalty = if (trends.acceleration > 1.0) 1.1 else 1.0
        val trendPenalty = if (trends.recentTrend > 2.5) 0.95 else 1.0

        return (base * iobFactor / accelPenalty * trendPenalty * phaseFactor * overallAggressiveness).coerceAtLeast(0.0)
    }

    private fun storePeakDetectionData(currentData: BGDataPoint, trends: TrendAnalysis) {
        val deltaThreshold = 0.5
        val minMinutes = 5

        val previous = storage.loadPeakDetectionData().lastOrNull()
        val shouldSave = when {
            previous == null -> true
            abs(currentData.bg - previous.bg) >= deltaThreshold -> true
            Minutes.minutesBetween(previous.timestamp, currentData.timestamp).minutes >= minMinutes -> true
            else -> false
        }

        if (!shouldSave) return

        val peakData = FCLLearningEngine.PeakDetectionData(
            timestamp = currentData.timestamp,
            bg = currentData.bg,
            trend = trends.recentTrend,
            acceleration = trends.acceleration,
            isPeak = false
        )
        storage.savePeakDetectionData(peakData)
    }

    private fun calculateDirectionConsistencyFromData(data: List<BGDataPoint>): Double {
        val slopes = mutableListOf<Double>()
        for (i in 1 until data.size) {
            val timeDiff = Minutes.minutesBetween(data[i-1].timestamp, data[i].timestamp).minutes / 60.0
            if (timeDiff > 0) slopes.add((data[i].bg - data[i-1].bg) / timeDiff)
        }
        return calculateDirectionConsistency(slopes)
    }

    private fun calculateMagnitudeConsistencyFromData(data: List<BGDataPoint>): Double {
        val slopes = mutableListOf<Double>()
        for (i in 1 until data.size) {
            val timeDiff = Minutes.minutesBetween(data[i-1].timestamp, data[i].timestamp).minutes / 60.0
            if (timeDiff > 0) slopes.add((data[i].bg - data[i-1].bg) / timeDiff)
        }
        return calculateMagnitudeConsistency(slopes)
    }

    private fun roundDose(dose: Double): Double {
        return round(dose * 20) / 20
    }

    // ★★★ NIEUW: Helper functies voor conditionele updates ★★★
    private fun shouldUpdateMetrics(): Boolean {
        return lastMetricsUpdate == null ||
            Minutes.minutesBetween(lastMetricsUpdate, DateTime.now()).minutes >= METRICS_UPDATE_INTERVAL
    }

    private fun shouldUpdateAdvice(): Boolean {
        val adviceIntervalHours = try {
            preferences.get(IntKey.Advice_Interval_Hours)
        } catch (e: Exception) {
            12 // Fallback waarde
        }

        return lastAdviceUpdate == null ||
            Hours.hoursBetween(lastAdviceUpdate, DateTime.now()).hours >= adviceIntervalHours
    }

    private fun getCachedParameterSummary(): String {
        if (lastParameterSummaryUpdate == null ||
            Minutes.minutesBetween(lastParameterSummaryUpdate, DateTime.now()).minutes >= 60) {
            cachedParameterSummary = parametersHelper.getParameterSummary()
            lastParameterSummaryUpdate = DateTime.now()
        }
        return cachedParameterSummary
    }



/*    // ★★★ NIEUWE HELPER FUNCTIES VOOR MAALTIJD ANALYSE ★★★
    private fun getMealPerformanceSummary(meals: List<MealPerformanceMetrics>): String {
        if (meals.isEmpty()) return "  Geen maaltijd data beschikbaar"

        val recentMeals = meals.takeLast(5).reversed()
        val successRate = (meals.count { it.wasSuccessful }.toDouble() / meals.size) * 100
        val avgPeak = meals.map { it.peakBG }.average()
        val mealsWithBolus = meals.filter { it.timeToFirstBolus > 0 }
        val avgResponseTime = if (mealsWithBolus.isNotEmpty()) mealsWithBolus.map { it.timeToFirstBolus }.average() else 0.0

        return """
        • Totale maaltijden: ${meals.size} (laatste 7 dagen)
      • Succesrate: ${successRate.toInt()}%
      • Gem. piek: ${round(avgPeak, 1)} mmol/L
      • Gem. responstijd: ${avgResponseTime.toInt()} min
        
[ RECENTE MAALTIJDEN ]
${recentMeals.joinToString("\n ") { meal ->
"${meal.mealStartTime.toString("HH:mm")} | ${meal.mealType.padEnd(9)} | " +
"Piek: ${round(meal.peakBG, 1)} | Bolus: ${round(meal.totalInsulinDelivered, 2)}U | " +
"${if (meal.wasSuccessful) "✅" else "❌"} ${meal.timeToFirstBolus}min"}}
 """.trimIndent()
    }    */

    // ★★★ ADVIES PRESENTATIE ★★★
    fun getParameterAdviceForDisplay(): List<ParameterAdviceDisplay> {
        return try {
            val currentAdvice = metricsHelper.getCurrentAdvice()
            currentAdvice.map { advice ->
                ParameterAdviceDisplay(
                    parameterName = getParameterDisplayName(advice.parameterName),
                    currentValue = formatParameterValue(advice.parameterName, advice.currentValue),
                    recommendedValue = formatParameterValue(advice.parameterName, advice.recommendedValue),
                    reason = advice.reason,
                    confidence = (advice.confidence * 100).toInt(),
                    expectedImprovement = advice.expectedImprovement,
                    changeDirection = advice.changeDirection
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }



    // ★★★ PARAMETER DISPLAY HELPER FUNCTIES ★★★
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

    private fun formatParameterValue(parameterName: String, value: Double): String {
        return when {
            parameterName.contains("percentage", ignoreCase = true) ||
                parameterName.contains("perc", ignoreCase = true) -> "${value.toInt()}%"
            parameterName.contains("sensitivity", ignoreCase = true) -> String.format("%.2f", value)
            parameterName.contains("slope", ignoreCase = true) -> String.format("%.1f mmol/L/uur", value)
            else -> String.format("%.1f", value)
        }
    }


    // ★★★ LEARNING STATUS ★★★
    fun getLearningStatus(): String {
        val recentMeals = metricsHelper.calculateMealPerformanceMetrics(168)
        val successRate = if (recentMeals.isNotEmpty()) {
            recentMeals.count { it.wasSuccessful }.toDouble() / recentMeals.size * 100.0
        } else {
            0.0
        }

        val learningStatus = metricsHelper.getParameterLearningStatus()

        return """
        Learning Status:
        • Maaltijd succesrate: ${successRate.toInt()}%
        • Totale maaltijden geanalyseerd: ${recentMeals.size}
        • Monitoring periode: 7 dagen
        $learningStatus
    """.trimIndent()
    }

    // ★★★ ADVIES GESCHIEDENIS WEERGAVE ★★★
    private fun getAdviceHistorySection(): String {
        val history = metricsHelper.getAdviceHistoryEntries(5) // Laatste 5 dagen

        if (history.isEmpty()) {
            return """📜 ADVIES GESCHIEDENIS
─────────────────────
Geen adviezen in de afgelopen 5 dagen"""
        }

        return buildString {
            append("📜 ADVIES GESCHIEDENIS (laatste 5 dagen)\n")
            append("─────────────────────\n")

            history.take(10).forEachIndexed { index, entry ->
                val timeAgo = when {
                    Minutes.minutesBetween(entry.timestamp, DateTime.now()).minutes < 60 ->
                        "${Minutes.minutesBetween(entry.timestamp, DateTime.now()).minutes} min geleden"
                    Hours.hoursBetween(entry.timestamp, DateTime.now()).hours < 24 ->
                        "${Hours.hoursBetween(entry.timestamp, DateTime.now()).hours} uur geleden"
                    else ->
                        "${Days.daysBetween(entry.timestamp, DateTime.now()).days} dagen geleden"
                }

                append("${index + 1}. [${entry.timestamp.toString("dd-MM HH:mm")}] - $timeAgo\n")
                append("   Maaltijden: ${entry.mealCount} | TIR: ${entry.metricsSnapshot?.timeInRange?.toInt() ?: 0}%\n")

                entry.adviceList.take(3).forEach { advice ->
                    val arrow = when (advice.changeDirection) {
                        "INCREASE" -> "⬆️"
                        "DECREASE" -> "⬇️"
                        else -> "➡️"
                    }
                    append("   $arrow ${advice.parameterName}: ${advice.currentValue} → ${advice.recommendedValue}\n")
                    append("      Reden: ${advice.reason.take(60)}${if (advice.reason.length > 60) "..." else ""}\n")
                }

                if (entry.adviceList.size > 3) {
                    append("   ... +${entry.adviceList.size - 3} meer adviezen\n")
                }
                append("\n")
            }

            if (history.size > 10) {
                append("... en ${history.size - 10} eerdere adviezen\n")
            }
        }
    }



    // ★★★ MAALTIJD-GERICHTE STATUS WEERGAVE ★★★
    fun getFCLStatus(): String {
        val currentHour = DateTime.now().hourOfDay
        val Day_Night = if (isNachtTime()) "Nacht" else "Dag"

        // ★★★ CONDITIONELE METRICS BEREKENING ★★★
        val (metrics24h, metrics7d) = if (shouldUpdateMetrics()) {
            lastMetricsUpdate = DateTime.now()
            val m24 = metricsHelper.calculateMetrics(24)
            val m168 = metricsHelper.calculateMetrics(168)
            Pair(m24, m168)
        } else {
            val m24 = metricsHelper.calculateMetrics(24, forceRefresh = false)
            val m168 = metricsHelper.calculateMetrics(168, forceRefresh = false)
            Pair(m24, m168)
        }

        metricsHelper.invalidateDataQualityCache()
        val dataQuality24h = metricsHelper.getDataQualityMetrics(24, true)

        // ★★★ MAALTIJD METRICS ★★★
        val mealMetrics = metricsHelper.calculateMealPerformanceMetrics(168)
        val recentMeals = mealMetrics.filter { it.mealStartTime.isAfter(DateTime.now().minusDays(7)) }
        val successRate = if (recentMeals.isNotEmpty()) {
            recentMeals.count { it.wasSuccessful }.toDouble() / recentMeals.size * 100.0
        } else {
            0.0
        }

        // ★★★ CONDITIONEEL ADVIES BEREKENING ★★★
        val agressivenessAdvice = if (shouldUpdateAdvice()) {
            lastAdviceUpdate = DateTime.now()
            metricsHelper.calculateAgressivenessAdvice(parametersHelper, metrics24h, forceNew = true)
        } else {
            metricsHelper.getCurrentAdvice()
        }

        // ★★★ TIMING INFO ★★★
        val metricsAge = lastMetricsUpdate?.let {
            val minutes = Minutes.minutesBetween(it, DateTime.now()).minutes
            when {
                minutes < 1 -> "net"
                minutes < 60 -> "$minutes minuten"
                else -> "${minutes / 60} uur"
            }
        } ?: "nooit"

        val adviceAge = lastAdviceUpdate?.let {
            val hours = Hours.hoursBetween(it, DateTime.now()).hours
            when {
                hours < 1 -> "minder dan 1 uur"
                hours == 1 -> "1 uur"
                else -> "$hours uur"
            }
        } ?: "nooit"

        val adviceInterval = try {
            preferences.get(IntKey.Advice_Interval_Hours)
        } catch (e: Exception) {
            12
        }

        val nextMetricsUpdate = METRICS_UPDATE_INTERVAL - (lastMetricsUpdate?.let {
            Minutes.minutesBetween(it, DateTime.now()).minutes
        } ?: METRICS_UPDATE_INTERVAL)

        val nextAdviceUpdate = adviceInterval - (lastAdviceUpdate?.let {
            Hours.hoursBetween(it, DateTime.now()).hours
        } ?: adviceInterval)

        // ★★★ GECACHED PARAMETER SUMMARY ★★★
        val parameterSummary = getCachedParameterSummary()

        // ★★★ VERBETERDE ACTIVITEIT STATUS ★★★
        val activityStatus = buildString {

            val retention = fclActivity.loadStapRetentie()
            val maxRetention = preferences.get(IntKey.stap_retentie)

            append("• Huidige retentie: $retention/$maxRetention\n")
            append("• ISF aanpassing: ${currentStappenPercentage}%\n")
            append("• Target aanpassing: ${"%.1f".format(currentStappenTargetAdjust)} mmol/L\n")
            append("• Data kwaliteit: $stepDataQuality\n")
            append("• Laatste update: ${if (lastStepDataTime > 0) "${((System.currentTimeMillis() - lastStepDataTime) / (1000 * 60)).toInt()} min geleden" else "Nooit"}\n")
            append("• Status: ${getActivityStatusText(retention)}\n")

            if (currentStappenLog.isNotBlank()) {
                append("\n[ ACTIVITEIT LOG ]\n")
                currentStappenLog.split("\n").takeLast(5).forEach { line ->
                    if (line.isNotBlank()) append("  $line\n")
                }
            }
        }

        // ★★★ LEARNING STATUS ★★★
        val learningStatus = learningEngine.getLearningStatus()

// ★★★ VERBETERDE ADVIES SECTIE - MAALTIJD GERICHT ★★★
        val adviceList = getParameterAdviceForDisplay()
        val adviceSection = if (adviceList.isNotEmpty()) {
            """🎯 MAALTIJD-GERICHT PARAMETER ADVIES 
─────────────────────
✅ ${adviceList.size} actieve adviezen beschikbaar:

${adviceList.joinToString("\n\n") { advice ->
                """📊 ${advice.parameterName}
   Huidig: ${advice.currentValue} → Aanbevolen: ${advice.recommendedValue}
   Reden: ${advice.reason}
   Vertrouwen: ${advice.confidence}% | Verbetering: ${advice.expectedImprovement}"""
            }}"""
        } else {
            if (recentMeals.size < 3) {
                """🎯 MAALTIJD-GERICHT PARAMETER ADVIES
─────────────────────
🟡 Wacht op meer data: ${recentMeals.size}/3 maaltijden geanalyseerd
   (Minimaal 3 maaltijden nodig voor gedetailleerd advies)"""
            } else {
                """🎯 MAALTIJD-GERICHT PARAMETER ADVIES
─────────────────────
✅ Geen parameter aanpassingen nodig
   Huidige instellingen presteren goed bij ${recentMeals.size} geanalyseerde maaltijden"""
            }
        }

        // ★★★ VERBETERDE MAALTIJD PRESTATIE ANALYSE ★★★
        val mealPerformanceSummary = if (recentMeals.isNotEmpty()) {
            val successfulMeals = recentMeals.count { it.wasSuccessful }
            val highPeakMeals = recentMeals.count { it.peakBG > 11.0 }
            val hypoMeals = recentMeals.count { it.postMealHypo }

        val recentMealsDisplay = recentMeals.takeLast(7).reversed().joinToString("\n") { meal ->
            "${meal.mealStartTime.toString("HH:mm")} | ${meal.mealType.padEnd(9)}| " +
             "Piek: ${round(meal.peakBG, 1)} | Ins.:${round(meal.totalInsulinDelivered, 1)}U | " +
             "${if (meal.wasSuccessful) "✅" else "❌"} ${meal.timeToFirstBolus}min"
            }

            """• Totale maaltijden: ${recentMeals.size} (laatste 7 dagen)
     • Succesrate: ${successRate.toInt()}% ($successfulMeals/${recentMeals.size})
     • Te hoge pieken (>11): ${highPeakMeals} maaltijden
     • Post-maaltijd hypo's: ${hypoMeals} maaltijden
     • Gem. piek: ${round(recentMeals.map { it.peakBG }.average(), 1)} mmol/L
     • Gem. responstijd: ${if (recentMeals.any { it.timeToFirstBolus > 0 }) recentMeals.filter { it.timeToFirstBolus > 0 }.map { it.timeToFirstBolus }.average().toInt() else 0} min
        
[ RECENTE MAALTIJDEN ]
$recentMealsDisplay"""
  } else {
"  Geen maaltijd data beschikbaar - wacht op volgende maaltijd"
        }


        // ★★★ BOUW PARAMETER ADVIES SECTIE ★★★
        val parameterAdviceSection = buildString {
            if (agressivenessAdvice.isNotEmpty()) {
                val newAdviceAvailable = metricsHelper.shouldCalculateNewAdvice()

                append("🕒 Laatste advies: $adviceAge\n")
                append(if (newAdviceAvailable) "🟢 NIEUW ADVIES BESCHIKBAAR\n" else "🟡 Toon opgeslagen advies\n")
                append("\n[ AANBEVELINGEN ]\n")

                agressivenessAdvice.forEach { advice ->
                    val arrow = when (advice.changeDirection) {
                        "INCREASE" -> "⬆️ VERHOGEN"
                        "DECREASE" -> "⬇️ VERLAGEN"
                        else -> "➡️ HANDHAVEN"
                    }
                    val currentValueFormatted = formatParameterValue(advice.parameterName, advice.currentValue)
                    val recommendedValueFormatted = formatParameterValue(advice.parameterName, advice.recommendedValue)

                    append("$arrow ${getParameterDisplayName(advice.parameterName)}:\n")
                    append("   Huidig: $currentValueFormatted → Aanbevolen: $recommendedValueFormatted\n")
                    append("   Reden: ${advice.reason}\n")
                    append("   Vertrouwen: ${(advice.confidence * 100).toInt()}% | ${advice.expectedImprovement}\n\n")
                }
            } else {
                when {
                    recentMeals.size < 1 -> {
                        append("🟡 Wacht op eerste maaltijd data\n")
                        append("   Advies wordt gegenereerd na eerste gedetecteerde maaltijd")
                    }
                    recentMeals.size < 3 -> {
                        append("🟡 Beperkte data: ${recentMeals.size}/3 maaltijden\n")
                        append("   Basis advies beschikbaar, gedetailleerd advies na meer maaltijden")
                    }
                    else -> {
                        append("✅ Geen parameter aanpassingen aanbevolen\n")
                        append("   Huidige instellingen presteren goed bij ${recentMeals.size} geanalyseerde maaltijden\n")
                        append("   Succesratio: ${successRate.toInt()}%")
                    }
                }
            }
        }

        return """
╔═══════════════════
║  ══ FCL v2.6.4 ══ 
╚═══════════════════

🎯 LAATSTE BOLUS BESLISSING
─────────────────────
• Fase/advies: ${lastMathBolusAdvice.take(100)}${if (lastMathBolusAdvice.length > 100) "..." else ""}
• Laatste update: ${lastMathAnalysisTime?.toString("HH:mm:ss") ?: "Nooit"}
• Bolus: ${"%.2f".format(lastCalculatedBolus)}U     Afgegeven: ${if (lastShouldDeliver) "Ja" else "Nee"}

[💉 AFGEGEVEN BOLUS]
• Laatste bolus: ${"%.2f".format(lastDeliveredBolus)}U
• Reden: ${lastBolusReason.take(80)}${if (lastBolusReason.length > 80) "..." else ""}
• Tijd: ${lastBolusTime?.toString("HH:mm:ss") ?: "Geen"}

[💾 GERESERVEERDE BOLUS]
• Huidig gereserveerd: ${"%.2f".format(pendingReservedBolus)}U
• Bijbehorende carbs: ${"%.1f".format(pendingReservedCarbs)}g
• Sinds: ${pendingReservedTimestamp?.toString("HH:mm") ?: "Geen"}

[🍽️ KOOLHYDRATEN DETECTIE]
• Laatste detectie: ${"%.1f".format(lastDetectedCarbs)}g
• Huidige COB: ${"%.1f".format(lastCarbsOnBoard)}g
• Actieve maaltijden: ${activeMeals.size}
• Laatste COB update: ${lastCOBUpdateTime?.toString("HH:mm:ss") ?: "Nooit"}

📈 FASE DETECTIE & BEREKENINGEN
─────────────────────
[ WISKUNDIGE ANALYSE ]
• Fase: ${lastRobustTrends?.phase ?: "Niet berekend"}
• Helling: ${"%.2f".format(lastRobustTrends?.firstDerivative ?: 0.0)} mmol/L/uur
• Versnelling: ${"%.2f".format(lastRobustTrends?.secondDerivative ?: 0.0)} mmol/L/uur²
• Consistentie: ${((lastRobustTrends?.consistency ?: 0.0) * 100).toInt()}%
• Datapunten gebruikt: ${recentDataForAnalysis.size}

[ BOLUS ADVIES DETAILS ]
${lastMathBolusAdvice}

🛡️ VEILIGHEIDSSYSTEEM
─────────────────────
• Max bolus: ${round(preferences.get(DoubleKey.max_bolus), 2)}U
• Max basaal: ${round(preferences.get(DoubleKey.ApsMaxBasal), 2)}U/h
• Max IOB: ${round(preferences.get(DoubleKey.ApsSmbMaxIob), 2)}U
• IOB correctie %: ${preferences.get(IntKey.IOB_corr_perc)}%

${persistentHelper.getPersistentStatus()}

⚙️ INSTELLINGEN & CONFIGURATIE
─────────────────────
[ BOLUS INSTELLINGEN ]
• Overall Aggressiveness: $Day_Night → ${getCurrentBolusAggressiveness().toInt()}% 
• Early Rise: ${preferences.get(IntKey.bolus_perc_early)}% → ${(preferences.get(IntKey.bolus_perc_early).toDouble() * getCurrentBolusAggressiveness() / 100.0).toInt()}%
• Mid Rise: ${preferences.get(IntKey.bolus_perc_mid)}% → ${(preferences.get(IntKey.bolus_perc_mid).toDouble() * getCurrentBolusAggressiveness() / 100.0).toInt()}%
• Late Rise: ${preferences.get(IntKey.bolus_perc_late)}% → ${(preferences.get(IntKey.bolus_perc_late).toDouble() * getCurrentBolusAggressiveness() / 100.0).toInt()}%

[ FASE DETECTIE INSTELLINGEN ]
• Vroege stijging: ${round(preferences.get(DoubleKey.phase_early_rise_slope), 1)} mmol/L/uur
• Mid stijging: ${round(preferences.get(DoubleKey.phase_mid_rise_slope), 1)} mmol/L/uur  
• Late stijging: ${round(preferences.get(DoubleKey.phase_late_rise_slope), 1)} mmol/L/uur
• Piekgrens: ${round(preferences.get(DoubleKey.phase_peak_slope), 1)} mmol/L/uur
• Vroege versnelling: ${round(preferences.get(DoubleKey.phase_early_rise_accel), 1)}
• Minimale consistentie: ${(preferences.get(DoubleKey.phase_min_consistency) * 100).toInt()}%

[ MAALTIJD INSTELLINGEN ]
• Carb berekening: ${preferences.get(IntKey.carb_percentage)}%
• Absorptietijd: ${preferences.get(IntKey.tau_absorption_minutes)} min
• Detectie sensitiviteit: ${round(preferences.get(DoubleKey.meal_detection_sensitivity), 2)} mmol/L/5min
• Piek demping: ${preferences.get(IntKey.peak_damping_percentage)}%
• Hypo risico: ${preferences.get(IntKey.hypo_risk_percentage)}%
• CR/ISF aanpassingsbereik: ${round(preferences.get(DoubleKey.CarbISF_min_Factor), 2)} - ${round(preferences.get(DoubleKey.CarbISF_max_Factor), 2)}

[ TIJDINSTELLINGEN ]
• Ochtend start: ${preferences.get(StringKey.OchtendStart)} (weekend: ${preferences.get(StringKey.OchtendStartWeekend)})
• Nacht start: ${preferences.get(StringKey.NachtStart)}
• Weekend dagen: ${preferences.get(StringKey.WeekendDagen)}

📊 LEARNING SYSTEEM
─────────────────────
• Laatste update: ${learningProfile.lastUpdated.toString("dd-MM-yyyy HH:mm")}
• Betrouwbaarheid: ${(learningProfile.learningConfidence * 100).toInt()}%
• Leersamples: ${learningProfile.totalLearningSamples}
• Carb ratio aanpassing: ${round(learningProfile.personalCarbRatio, 2)}
• Huidige maaltijdfactor: ${round(learningProfile.getMealTimeFactor(currentHour), 2)}
• Reset learning: ${if (preferences.get(BooleanKey.ResetLearning)) "Ja" else "Nee"}

[ MAALTIJD FACTOREN ]
${learningProfile.mealTimingFactors.entries.joinToString("\n  ") { "${it.key.padEnd(10)}: ${round(it.value, 2)}" }}

🚶 ACTIVITEIT en BEWEGING
─────────────────────
${activityStatus.trim()}

🔄 RESISTENTIE ANALYSE
─────────────────────
${resistanceHelper.getCurrentResistanceLog().split("\n").joinToString("\n  ") { it }}

📊 MAALTIJD PRESTATIE ANALYSE
─────────────────────
$mealPerformanceSummary

📊 GLUCOSE METRICS & PERFORMANCE
─────────────────────
[⏰ TIMING & CACHING]
• Volgende metrics: over ${nextMetricsUpdate} minuten
• Maaltijden geanalyseerd: ${recentMeals.size}

[ DATA KWALITEIT - 24U ]
• Metingen: ${dataQuality24h.totalReadings}/${dataQuality24h.expectedReadings}
• Completeheid: ${dataQuality24h.dataCompleteness.toInt()}% ${if (!dataQuality24h.hasSufficientData) "⚠️" else "✅"}
• Metingen per uur: ${metrics24h.readingsPerHour.toInt()}/12 ${if (metrics24h.readingsPerHour < 8) "⚠️" else "✅"}

[ LAATSTE 24 UUR - INFORMATIEF ]
• Time in Range: ${metrics24h.timeInRange.toInt()}% (3.9-10.0 mmol/L)
• Time Below Range: ${metrics24h.timeBelowRange.toInt()}% (<3.9 mmol/L) ${if (metrics24h.timeBelowRange > 5) "⚠️" else ""}
• Time Above Range: ${metrics24h.timeAboveRange.toInt()}% (>10.0 mmol/L) ${if (metrics24h.timeAboveRange > 25) "⚠️" else ""}
• Gemiddelde glucose: ${round(metrics24h.averageGlucose, 1)} mmol/L
• GMI (HbA1c): ${round(metrics24h.gmi, 1)}% (${(metrics24h.gmi*10.93-23.5).toInt()} mmol/mol)
• Variatie (CV): ${metrics24h.cv.toInt()}% ${if (metrics24h.cv > 36) "⚠️" else ""}

[ LAATSTE 7 DAGEN - INFORMATIEF ]
• Time in Range: ${metrics7d.timeInRange.toInt()}%
• Time Below Range: ${metrics7d.timeBelowRange.toInt()}%
• Time Above Range: ${metrics7d.timeAboveRange.toInt()}%
• Gemiddelde glucose: ${round(metrics7d.averageGlucose, 1)} mmol/L

$adviceSection

🎯 PARAMETER OPTIMALISATIE ADVIES
─────────────────────
${parameterAdviceSection}

 PARAMETER LEARNING SYSTEEM  
─────────────────────
${metricsHelper.getParameterLearningStatus()}

 PARAMETERS CONFIGURATIE OVERZICHT
─────────────────────
$parameterSummary

${getAdviceHistorySection()}
        
        
""".trimIndent()
    }
//    Backup maaltijden
 //   [⏰ TIMING & CACHING]
 //   • laatste Metrics: $metricsAge geleden
 //   • Volgende metrics: over ${nextMetricsUpdate} minuten
 //   • laatste Advies: $adviceAge
 //   • Volgende advies: over ${nextAdviceUpdate} uur
 //   • Advies interval: $adviceInterval uur
 //   • Maaltijden geanalyseerd: ${recentMeals.size}

    private fun getActivityStatusText(retention: Int): String {
        return when (retention) {
            0 -> "🔵 Geen activiteit"
            1 -> "🟡 Licht actief"
            2 -> "🟠 Matig actief"
            else -> "🔴 Hoog actief"
        }
    }

    // ★★★ VERBETERDE MAALTIJD LOGGING VOOR ANALYSE ★★★
    private fun logMealDataForAnalysis(
        currentData: BGDataPoint,
        detectedCarbs: Double,
        mealDetected: Boolean,
        dose: Double,
        reason: String
    ) {
        try {
            loggingHelper.logToAnalysisCSV(
                timestamp = DateTime.now(),
                bg = currentData.bg,
                iob = currentData.iob,
                detectedCarbs = detectedCarbs,
                mealDetected = mealDetected,
                dose = dose,
                reason = reason,
                target = getEffectiveTarget(),
                phase = lastRobustTrends?.phase ?: "unknown"
            )
        } catch (e: Exception) {
            // Negeer logging errors
        }
    }

    // ★★★ FORCEER NIEUW ADVIES ★★★
    fun forceNewParameterAdvice(): String {
        return try {
            // Reset caches
            metricsHelper.resetAllCaches()
            lastMetricsUpdate = null
            lastAdviceUpdate = DateTime.now().minusHours(13)

            val metrics24h = metricsHelper.calculateMetrics(24, true)
            val metrics7d = metricsHelper.calculateMetrics(168, true)

            // Forceer nieuwe advies berekening
            metricsHelper.calculateAgressivenessAdvice(
                parametersHelper,
                metrics24h,
                true // forceNew
            )

            "✅ Nieuw parameter advies gegenereerd op basis van recente data\n" +
                "• Alle caches gereset\n" +
                "• Metrics opnieuw berekend\n" +
                "• Vernieuw de status weergave"

        } catch (e: Exception) {
            "❌ Fout bij genereren nieuw advies: " + (e.message ?: "Onbekende fout")
        }
    }

    // ★★★ FORCEER MAALTIJD ANALYSE EN ADVIES UPDATE ★★★
    fun forceMealAnalysisUpdate(): String {
        return try {
            // Reset alle caches in metrics helper
            metricsHelper.resetAllCaches()

            // Forceer nieuwe metrics berekening
            val metrics24h = metricsHelper.calculateMetrics(24, true)
            val metrics7d = metricsHelper.calculateMetrics(168, true)

            // Forceer nieuwe maaltijd analyse
            val mealMetrics = metricsHelper.calculateMealPerformanceMetrics(168)

            // Forceer nieuw advies
            lastAdviceUpdate = DateTime.now().minusHours(13)
            val advice = metricsHelper.calculateAgressivenessAdvice(parametersHelper, metrics24h, true)

            // Reset eigen caches in FCL
            lastMetricsUpdate = null
            lastAdviceUpdate = DateTime.now().minusHours(13) // Forceer update
            lastParameterSummaryUpdate = null

            buildString {
                append("✅ Maaltijd analyse geforceerd\n")
                append("• ").append(mealMetrics.size).append(" maaltijden geanalyseerd\n")
                append("• ").append(advice.size).append(" adviezen gegenereerd\n")
                append("• Alle caches gereset\n")
                append("• Vernieuw de status weergave")
            }

        } catch (e: Exception) {
            "❌ Fout bij forceren analyse: " + (e.message ?: "Onbekende fout")
        }
    }


    // Helper functie voor rounding

    private fun round(value: Double, digits: Int): Double {
        val scale = Math.pow(10.0, digits.toDouble())
        return Math.round(value * scale) / scale
    }

    // Enum definitions
    enum class MealDetectionState { NONE, EARLY_RISE, RISING, PEAK, DECLINING, DETECTED }
    enum class MealConfidenceLevel { SUSPECTED, CONFIRMED, HIGH_CONFIDENCE }
    enum class SensorIssueType { JUMP_TOO_LARGE, OSCILLATION, COMPRESSION_LOW }
}