package app.aaps.plugins.aps.openAPSFCL

import android.content.Context
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.utils.fabric.FabricPrivacy
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.Preferences
import app.aaps.core.keys.StringKey
import org.joda.time.DateTime
import org.joda.time.Minutes
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round

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
        val debugLog: String = "",
        val basalRate: Double = 0.0,           // NIEUW: U/uur - temp basaal
        val bolusAmount: Double = 0.0,         // NIEUW: Aangepaste bolus hoeveelheid
        val hybridPercentage: Int = 0,         // NIEUW: % dat als basaal wordt gegeven
    )

    data class SafetyResult(
        val allowed: Boolean,
        val reason: String = "",
        val phase: String = "stable"
    )

    private data class MealBolusResult(
        val dose: Double,
        val reservedBolus: Double,
        val deliver: Boolean,
        val reason: String,
        val phase: String,
        val detectedCarbs: Double,
        val mealDetected: Boolean,
        val confidence: Double
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


    // ★★★ FCL CONTEXT VOOR OPTIMALISATIE ★★★
    data class FCLContext(
        val currentBG: Double,
        val currentIOB: Double,
        val mealDetected: Boolean,
        val detectedCarbs: Double,
        val carbsOnBoard: Double,
        val lastBolusAmount: Double,
        val currentPhase: String,
        val finalBasalRate: Double,
        val finalBolusAmount: Double,
        val finalShouldDeliverBolus: Boolean
    )


    // ★★★ PHASED BOLUS MANAGEMENT DATA CLASSES ★★★
    data class MealTimeIOBLimits(
        val maxSingleBolus: Double,
        val maxIOB: Double,
        val minMinutesBetweenBoluses: Int,
        val maxConsecutiveBoluses: Int
    )

    data class BolusEvent(
        val timestamp: DateTime,
        val amount: Double,
        val reason: String
    )

    data class DynamicMealLimits(
        val maxTotalMultiplier: Double,
        val iobUtilization: Double,
        val requiredPercentage: Double
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

    // ★★★ COOLDOWN NA MAALTIJD BOLUSSEN voor persistent hoog bolus ★★★
    private val MEAL_BOLUS_COOLDOWN_MINUTES = 45 //  cooldown
    private var lastMealBolusTime: DateTime? = null

    // ★★★  State tracking voor metrics en advies ★★★
    private var lastMetricsUpdate: DateTime? = null
    private var lastAdviceUpdate: DateTime? = null
    private val METRICS_UPDATE_INTERVAL = 60 // minuten


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

    // ★★★ HYBRIDE BASAAL STATE TRACKING ★★★
    private var hybridBasalActiveUntil: DateTime? = null
    private var remainingHybridBasalAmount: Double = 0.0
    private var initialHybridBasalAmount: Double = 0.0 // ★★★ NIEUW: bewaar initiële basaal hoeveelheid ★★★

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
    private val lastBolusHistory = ArrayDeque<Pair<DateTime, Double>>(3)
    private var finalBasalRate = 0.0


// vervallen bijna niet gebruikte parameters
    private val peak_damping_percentage = 25 // %
    private val hypo_risk_percentage = 50 // %
    private val dynamic_night_aggressiveness_threshold = 2.0
    private val enhanced_early_boost_perc =40

    // Helpers
    private val resistanceHelper = FCLResistance(preferences, persistenceLayer, context)
    private val activityHelper = FCLActivity(preferences, context)
    private val loggingHelper = FCLLogging(context)
    private val parametersHelper = FCLParameters(preferences)
    private val metricsHelper = FCLMetrics(context, preferences, persistenceLayer)
    private val persistentHelper = FCLPersistent(preferences, context)
    private val advisorAdv = FCLAdvisorAdv(preferences)

    // ★★★ EXTERNE METRICS-REFERENTIE (wordt vanuit DetermineBasalFCL gezet) ★★★


    // ★★★ PHASED BOLUS MANAGER ★★★
    private val phasedBolusManager = PhasedBolusManager()

    private class PhasedBolusManager {
        private val recentBoluses = mutableListOf<BolusEvent>()
        private var mealStartTime: DateTime? = null
        private var totalDetectedCarbs: Double = 0.0

        fun startMealPhase(detectedCarbs: Double) {
            mealStartTime = DateTime.now()
            totalDetectedCarbs = detectedCarbs
            recentBoluses.clear()
        }


        fun canDeliverBolus(mealTimeLimits: MealTimeIOBLimits): Boolean {
            val now = DateTime.now()

            // Verwijder oude bolussen uit history
            recentBoluses.removeAll {
                Minutes.minutesBetween(it.timestamp, now).minutes > 180
            }

            // Check maximum aantal bolussen
            if (recentBoluses.size >= mealTimeLimits.maxConsecutiveBoluses) return false

            // Check minimale tijd sinds laatste bolus
            val lastBolus = recentBoluses.maxByOrNull { it.timestamp }
            lastBolus?.let {
                val minutesSinceLast = Minutes.minutesBetween(it.timestamp, now).minutes
                if (minutesSinceLast < mealTimeLimits.minMinutesBetweenBoluses) return false
            }

            return true
        }

        fun recordBolusDelivery(amount: Double, reason: String) {
            recentBoluses.add(BolusEvent(DateTime.now(), amount, reason))
        }

        fun getConsecutiveBolusesCount(): Int = recentBoluses.size

        fun getLastBolusTime(): DateTime? = recentBoluses.maxByOrNull { it.timestamp }?.timestamp

        fun getMealStartTime(): DateTime? = mealStartTime

        fun getTotalInsulinDelivered(): Double = recentBoluses.sumByDouble { it.amount }

        fun getAverageBolusSize(): Double =
            if (recentBoluses.isNotEmpty()) getTotalInsulinDelivered() / recentBoluses.size else 0.0
    }



    private val cobPrefs by lazy {
        context.getSharedPreferences("fcl_cob", Context.MODE_PRIVATE)
    }

    private fun saveCob(value: Double) {
        cobPrefs.edit().putFloat("cob", value.toFloat()).apply()
    }

    private fun loadCob(): Double {
        return cobPrefs.getFloat("cob", 0f).toDouble()
    }

    init {

        initializeActivitySystem()



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


    // ★★★ NIEUWE FUNCTIE: CONSERVATIEVE NACHTELIJKE CARBS DETECTIE ★★★
    private fun calculateNightTimeCarbsDetection(
        historicalData: List<BGDataPoint>,
        robustTrends: RobustTrendAnalysis,
        currentBG: Double,
        targetBG: Double,
        currentIOB: Double,
        maxIOB: Double,
        effectiveCR: Double
    ): UnifiedCarbsResult {
        if (historicalData.size < 6) {
            return UnifiedCarbsResult(0.0, "Night: Insufficient data", 0.0)
        }

        // ★★★ COMPRESSION LOW DETECTIE IN NACHT ★★★
        if (isLikelyCompressionLow(historicalData)) {
            return UnifiedCarbsResult(0.0, "Night: Compression low detected - ignoring carbs", 0.0)
        }

        val recent = historicalData.takeLast(6)
        val bg20minAgo = recent.first().bg
        val delta20 = currentBG - bg20minAgo
        val slope20 = delta20 / 20.0 * 60.0 // mmol/L per uur over 20 minuten

        // ★★★ VASTE DREMPELS VOOR NACHT ★★★
        val shouldDetect = slope20 > 4.0 &&           // Alleen bij sterke stijging (>4.0 mmol/L/uur)
            robustTrends.consistency > 0.8 &&
            currentBG > 7.0 &&          // Alleen bij hoge start BG
            currentIOB < maxIOB * 0.4   // Alleen bij lage IOB

        if (!shouldDetect) {
            return UnifiedCarbsResult(0.0, "Night: No carb detection - too conservative", 0.0)
        }

        // Conservatieve carbs berekening
        val baseCarbs = slope20 * 6.0
        val detectedCarbs = baseCarbs.coerceAtMost(25.0) // Max 25g in nacht

        return UnifiedCarbsResult(
            detectedCarbs = detectedCarbs,
            detectionReason = "Night: Strong rise detected (slope=${"%.1f".format(slope20)})",
            confidence = 0.6
        )
    }

    /**
     * Berekent op basis van de huidige BG (mmol/L) en trends of er een maaltijd gedetecteerd wordt.
     *
     * Let op:
     * - Alle logica en drempels zijn 1-op-1 overgenomen uit de bestaande
     *   "UNIFORME CARB DETECTIE"-sectie in getEnhancedInsulinAdvice().
     * - Er worden hier GEEN bolus- of veiligheidsbeslissingen genomen.
     *   Dit is puur detectie: hoeveel carbs en of dat een maaltijd is.
     *
     * @param historicalData  BG-historie in mmol/L
     * @param robustTrends    trendanalyse resultaat van analyzeTrends(historicalData)
     * @param currentBG       huidige BG in mmol/L
     * @param effectiveTarget doel-BG in mmol/L (effectiveTarget uit getEnhancedInsulinAdvice)
     * @param currentIOB      actuele IOB in U
     * @param maxIOB          maximale IOB in U
     *
     * @return Pair(mealState, carbsResult)
     *         - mealState: DETECTED of NONE (zoals voorheen bepaald)
     *         - carbsResult: UnifiedCarbsResult met detectedCarbs, reason, confidence
     */
    private fun updateMealStateFromBG(
        historicalData: List<BGDataPoint>,
        robustTrends: RobustTrendAnalysis,
        currentBG: Double,
        effectiveTarget: Double,
        currentIOB: Double,
        maxIOB: Double
    ): Pair<MealDetectionState, UnifiedCarbsResult> {

        // ★★★ UNIFORME CARB DETECTIE ★★★
        val carbsResult = calculateUnifiedCarbsDetection(
            historicalData = historicalData,
            robustTrends = robustTrends,
            currentBG = currentBG,
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

        return mealState to carbsResult
    }

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

        val isNight = isNachtTime()

        // ★★★ NACHTELIJKE CARBS DETECTIE ★★★
        if (isNight) {
            val nightCarbsResult = calculateNightTimeCarbsDetection(
                historicalData, robustTrends, currentBG, targetBG, currentIOB, maxIOB, effectiveCR
            )
            return nightCarbsResult
        }

        // ★★★ DAG MODUS - VERBETERDE LOGICA ★★★
        val recent = historicalData.takeLast(4)
        val bg10minAgo = recent.getOrNull(recent.size - 3)?.bg ?: currentBG
        val delta10 = currentBG - bg10minAgo
        val slope10 = delta10 / 10.0 * 60.0 // mmol/L per uur

        // ★★★ VERLAAGDE DREMPELS ★★★
        val earlyRiseThreshold = 0.05  // VERLAAGD van 0.15
        val moderateRiseThreshold = 0.1  // VERLAAGD van 0.3
        val veryEarlyRiseThreshold = 0.02  // NIEUW

        var detectedCarbs = 0.0
        var detectionReason = "No carb detection"
        var confidence = 0.0

        // ★★★ AGGRESSIEVERE DETECTIE MET MINDER EXTREME MULTIPLIERS ★★★
        when {
            // Zeer sterke stijging (>2.5 mmol/L/uur)
            slope10 > 4.0 -> {
                detectedCarbs = slope10 * 8.0  // VERLAAGD van 15.0
                detectionReason = "RAPID RISE: slope=${"%.1f".format(slope10)} mmol/L/h"
                confidence = 0.95
            }

            // Sterke stijging (>2.5 mmol/L/uur)
            slope10 > 2.5 -> {
                detectedCarbs = slope10 * 6.0  // VERLAAGD van 12.0
                detectionReason = "STRONG RISE: slope=${"%.1f".format(slope10)} mmol/L/h"
                confidence = 0.9
            }

            // Matige stijging (>1.0 mmol/L/uur)
            slope10 > 1.0 -> {
                detectedCarbs = slope10 * 4.0  // VERLAAGD van 8.0
                detectionReason = "MODERATE RISE: slope=${"%.1f".format(slope10)} mmol/L/h"
                confidence = 0.8
            }

            // Vroege stijging detectie
            slope10 > earlyRiseThreshold && currentBG > targetBG + 0.1 && robustTrends.consistency > 0.2 -> {
                detectedCarbs = slope10 * 3.0  // VERLAAGD van 12.0
                detectionReason = "EARLY RISE: slope=${"%.1f".format(slope10)} mmol/L/h"
                confidence = 0.7
            }

            // Zeer vroege stijging bij consistent patroon
            slope10 > veryEarlyRiseThreshold && currentBG > targetBG + 0.05 && robustTrends.consistency > 0.1 -> {
                detectedCarbs = slope10 * 2.0  // VERLAAGD van 8.0
                detectionReason = "VERY EARLY RISE: slope=${"%.1f".format(slope10)} mmol/L/h"
                confidence = 0.6
            }

            else -> {
                detectedCarbs = 0.0
                detectionReason = "No significant carb detection signals"
                confidence = 0.0
            }
        }

        // ★★★ IOB-BASED REDUCTIE EN CAPS ★★★
        val iobRatio = currentIOB / maxIOB
        val iobCarbReduction = when {
            iobRatio > 0.9 -> 0.3  // 70% reductie
            iobRatio > 0.7 -> 0.5  // 50% reductie
            iobRatio > 0.5 -> 0.7  // 30% reductie
            iobRatio > 0.3 -> 0.85 // 15% reductie
            iobRatio > 0.1 -> 0.95 // 5% reductie
            else -> 1.0
        }

        // ★★★ IOB-BASED CARB CAPS ★★★
        val iobCarbCap = when {
            iobRatio > 0.7 -> 40.0  // Max 40g bij zeer hoge IOB
            iobRatio > 0.5 -> 60.0  // Max 60g bij hoge IOB
            iobRatio > 0.3 -> 80.0  // Max 80g bij matige IOB
            else -> 100.0
        }

        detectedCarbs *= iobCarbReduction
        detectedCarbs = detectedCarbs.coerceAtMost(iobCarbCap)

        // ★★★ DYNAMISCHE CARB MULTIPLIER ★★★
        val dynamicMealMultiplier = when {
            detectedCarbs > 80 -> 1.4  // VERLAAGD van 1.8
            detectedCarbs > 60 -> 1.3  // VERLAAGD van 1.6
            detectedCarbs > 40 -> 1.2  // VERLAAGD van 1.4
            detectedCarbs > 20 -> 1.1  // VERLAAGD van 1.2
            else -> 1.0
        }

        // ★★★ COMBINEER MET PARAMETER PERCENTAGE ★★★
        val baseCarbPercentage = preferences.get(IntKey.carb_percentage).toDouble() / 100.0
        val combinedMultiplier = baseCarbPercentage * dynamicMealMultiplier

        detectedCarbs *= combinedMultiplier

        // ★★★ CONFIDENCE AFSTEMMING ★★★
        confidence *= when {
            detectedCarbs > 25.0 -> 0.95
            detectedCarbs > 15.0 -> 0.85
            detectedCarbs > 8.0 -> 0.75
            else -> 0.6
        }

        // ★★★ DEBUG INFO ★★★
        if (iobCarbReduction < 1.0) {
            detectionReason += " (IOB reduction: ${(iobCarbReduction * 100).toInt()}%)"
        }

        if (dynamicMealMultiplier > 1.0) {
            detectionReason += " | MealMulti:${dynamicMealMultiplier.round(1)}x"
        }

        if (detectedCarbs >= iobCarbCap) {
            detectionReason += " | IOB cap:${iobCarbCap.toInt()}g"
        }

        return UnifiedCarbsResult(detectedCarbs, detectionReason, confidence)
    }

    // Helper functie voor rounding
    private fun Double.round(decimals: Int): String {
        return "%.${decimals}f".format(this)
    }

    private fun logFinalAdvice(
        advice: EnhancedInsulinAdvice,
        currentData: BGDataPoint,
        currentISF: Double,
        currentIOB: Double,
        target: Double
    ) {
        try {
            loggingHelper.logToAnalysisCSV(
                fclAdvice = advice,
                currentData = currentData,
                currentISF = currentISF,
                currentIOB = currentIOB,
                target = target
            )
        } catch (e: Exception) {
            // nooit laten crashen
        }
    }





    // ★★★ COMPRESSION LOW DETECTIE FUNCTIE ★★★
    private fun isLikelyCompressionLow(historicalData: List<BGDataPoint>): Boolean {
        if (historicalData.size < 6) return false

        val recent = historicalData.takeLast(6)
        val currentBG = recent.last().bg
        val minBG = recent.minByOrNull { it.bg }?.bg ?: currentBG
        val maxBG = recent.maxByOrNull { it.bg }?.bg ?: currentBG

        // Compression low kenmerken:
        // 1. Snelle daling gevolgd door snelle stijging
        // 2. Gebeurt vaak 's nachts
        // 3. Kortdurend patroon

        val hasRapidDrop = (maxBG - minBG) > 2.0 // Minimaal 2.0 mmol/L verschil
        val hasQuickRecovery = (currentBG - minBG) > 1.5 // Snel herstel

        // Bepaal timing van dieptepunt
        val minIndex = recent.indexOfFirst { it.bg == minBG }
        val timeToMin = minIndex * 5 // Geschatte minuten naar dieptepunt (5-min intervals)
        val timeFromMin = (recent.size - minIndex - 1) * 5 // Geschatte minuten sinds dieptepunt

        val isCompressionPattern = hasRapidDrop &&
            hasQuickRecovery &&
            timeToMin in 10..20 && // Dieptepunt binnen 10-20 minuten
            timeFromMin in 5..15 // Herstel binnen 5-15 minuten

        // Extra nachtelijke checks
        val isNightTime = isNachtTime()
        val hasTypicalNightPattern = currentBG in 4.0..6.0 && maxBG < 8.0


        return isCompressionPattern && isNightTime && hasTypicalNightPattern
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


    // ★★★ RESISTENTIE MANAGEMENT ★★★
    private fun updateResistentieIndienNodig() {
        resistanceHelper.updateResistentieIndienNodig(isNachtTime())
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
        val baseDay = preferences.get(IntKey.bolus_perc_day).toDouble()
        val baseNight = preferences.get(IntKey.bolus_perc_night).toDouble()

        return if (isNachtTime()) {
            getDynamicNightAggressiveness(
                baseNightAggressiveness = baseNight,
                baseDayAggressiveness = baseDay,
                robustTrends = lastRobustTrends,
                currentBG = currentBg,
                targetBG = Target_Bg,
                detectedCarbs = lastDetectedCarbs
            )
        } else {
            baseDay
        }
    }

    // ★★★ MAX BOLUS HELPER FUNCTIE ★★★
    private fun getMaxBolus(): Double {
        return if (isNachtTime()) {
            preferences.get(DoubleKey.max_bolus_night)
        } else {
            preferences.get(DoubleKey.max_bolus_day)
        }
    }

    // ★★★ PLAATS DIRECT HIERONDER - NIEUWE FUNCTIE ★★★
    private fun getDynamicNightAggressiveness(
        baseNightAggressiveness: Double,
        baseDayAggressiveness: Double,
        robustTrends: RobustTrendAnalysis?,
        currentBG: Double,
        targetBG: Double,
        detectedCarbs: Double
    ): Double {
        if (!isNachtTime()) return baseDayAggressiveness

        // Basis nacht agressiviteit
        var dynamicAggressiveness = baseNightAggressiveness

        // ★★★ VERBETERDE SIGNIFICANTIE DETECTIE ★★★
        val threshold = dynamic_night_aggressiveness_threshold
        val isSignificantRise = when {
            robustTrends?.firstDerivative ?: 0.0 > threshold -> true
            currentBG > targetBG + 2.0 && robustTrends?.consistency ?: 0.0 > 0.6 -> true
            detectedCarbs > 25.0 -> true
            currentBG > 8.0 && robustTrends?.firstDerivative ?: 0.0 > 1.5 -> true
            else -> false
        }

        if (isSignificantRise) {
            val riseFactor = when {
                robustTrends?.firstDerivative ?: 0.0 > 4.0 -> 1.0
                robustTrends?.firstDerivative ?: 0.0 > 3.0 -> 0.8
                robustTrends?.firstDerivative ?: 0.0 > 2.0 -> 0.6
                currentBG > 9.0 -> 0.7
                detectedCarbs > 30.0 -> 0.8
                else -> 0.5
            }

            dynamicAggressiveness = baseNightAggressiveness +
                (baseDayAggressiveness - baseNightAggressiveness) * riseFactor

            if (robustTrends?.firstDerivative ?: 0.0 > 5.0) {
                dynamicAggressiveness = dynamicAggressiveness.coerceAtLeast(baseDayAggressiveness * 0.8)
            }
        }

        return dynamicAggressiveness.coerceIn(baseNightAggressiveness, baseDayAggressiveness)
    }

    // ★★★ VERBETERDE EARLY BOOST LOGICA ★★★
    private fun calculateEnhancedEarlyBoost(
        currentBG: Double,
        predictedPeak: Double,
        baseDose: Double,
        currentIOB: Double,
        maxIOB: Double,
        maxBolus: Double,
        robustTrends: RobustTrendAnalysis?
    ): Double {
        if (currentBG !in 8.0..9.9 || predictedPeak <= 10.0) return baseDose

        val delta = (predictedPeak - currentBG).coerceAtLeast(0.0)

        // ★★★ VERHOOGDE BOOST FACTOR ★★★
        val baseBoostFactor = (getCurrentBolusAggressiveness() / 100.0) *
            (1.0 + (delta / 8.0).coerceIn(0.0, enhanced_early_boost_perc.toDouble()/100.0))

        val consistencyBoost = when (robustTrends?.consistency ?: 0.0) {
            in 0.8..1.0 -> 1.2
            in 0.6..0.8 -> 1.1
            else -> 1.0
        }

        val totalBoostFactor = baseBoostFactor * consistencyBoost
        val proposed = (baseDose * totalBoostFactor).coerceAtMost(maxBolus)

        // Dynamische IOB cap
        val overTarget = (predictedPeak - 10.0).coerceAtLeast(0.0)
        val iobBoostPercent = (overTarget / 4.0).coerceIn(0.0, 0.6)
        val dynamicIOBcap = maxIOB * (1.0 + iobBoostPercent)

        return if (currentIOB + proposed <= dynamicIOBcap) {
            proposed
        } else {
            val allowed = (dynamicIOBcap - currentIOB).coerceAtLeast(0.0)
            allowed.coerceAtMost(maxBolus)
        }
    }

    private fun gethypoThreshold(): Double {
        return if (isNachtTime()) HYPO_THRESHOLD_NIGHT else HYPO_THRESHOLD_DAY
    }

    // ★★★ SAFETY CHECK FUNCTIES ★★★
    private fun runCentralSafetyGate(
        stage: String, // "pre" of "post"
        currentData: BGDataPoint,
        historicalData: List<BGDataPoint>,
        trends: TrendAnalysis,
        robustTrends: RobustTrendAnalysis,
        effectiveTarget: Double,
        effectiveISF: Double,
        currentIOB: Double,
        maxIOB: Double,
        mealDetected: Boolean,
        detectedCarbs: Double,
        carbsOnBoard: Double,
        plannedDose: Double
    ): SafetyResult {

        // 1) Hypo recovery blokkeert meal-detectie/insulin
        if (shouldBlockMealDetectionForHypoRecovery(currentData.bg, historicalData, trends)) {
            return SafetyResult(false, "Safety: Meal detection blocked (hypo recovery)", "safety_monitoring")
        }

        // 2) Night safety (alleen als je überhaupt iets wil geven)
        if (isNachtTime() && plannedDose > 0.0) {
            val nightBlock = shouldBlockBolusForNightTime(
                currentIOB = currentIOB,
                maxIOB = maxIOB,
                consecutiveBoluses = phasedBolusManager.getConsecutiveBolusesCount(),
                lastBolusTime = phasedBolusManager.getLastBolusTime(),
                currentSlope = robustTrends.firstDerivative
            )
            if (nightBlock) return SafetyResult(false, "Night safety: Conservative settings prevent bolus", "night_safety")
        }

        // 3) Short term decline block (alleen bij plannen van bolus)
        if (plannedDose > 0.0 && shouldBlockBolusForShortTermTrend(currentData, historicalData, trends, maxIOB)) {
            val st = calculateShortTermTrend(historicalData)
            return SafetyResult(false, "Safety: Strong short-term decline (${String.format("%.1f", st)} mmol/L/h)", "safety")
        }

        // 4) Trend reversal (alleen bij plannen van bolus)
        if (plannedDose > 0.0 && shouldBlockCorrectionForTrendReversal(currentData, historicalData, trends, maxIOB)) {
            return SafetyResult(false, "Safety: Trend reversing to decline (IOB=${String.format("%.1f", currentData.iob)}U)", "safety")
        }

        // 5) De ‘harde’ stopcheck (post-check of als je al iets wil geven)
        if (plannedDose > 0.0 || stage == "post") {
            val shouldStop = shouldStopInsulinDelivery(
                robustTrends = robustTrends,
                historicalData = historicalData,
                phasedBolusManager = phasedBolusManager,
                currentBG = currentData.bg,
                currentIOB = currentIOB,
                effectiveISF = effectiveISF,
                mealDetected = mealDetected,
                detectedCarbs = detectedCarbs,
                maxIOB = maxIOB,
                carbsOnBoard = carbsOnBoard
            )
            if (shouldStop) {
                return SafetyResult(false, "Safety: Insulin delivery stopped by central stop check", "safety_stop")
            }
        }

        return SafetyResult(true)
    }




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


    // ★★★ EFFECTIEVE PARAMETERS ★★★
    private fun getEffectiveCarbRatio(): Double {
        val base = currentCR
        return base / resistanceHelper.getCurrentResistanceFactor()
    }

    private fun getEffectiveISF(): Double {
        val baseISF = currentISF
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

    // ★★★ TREND ANALYSIS ★★★
    private fun analyzeTrends(data: List<BGDataPoint>): TrendAnalysis {
        if (data.isEmpty()) return TrendAnalysis(0.0, 0.0, 0.0)

        val smoothed = smoothBGSeries(data)
        val smoothPoints = smoothed.map { (ts, bg) ->
            BGDataPoint(timestamp = ts, bg = bg, iob = data.find { it.timestamp == ts }?.iob ?: 0.0)
        }

        val recentTrend = calculateRecentTrend(smoothPoints, 4)
        val shortTermTrend = calculateShortTermTrend(smoothPoints)
        val acceleration = calculateAcceleration(smoothPoints, 3)

        return TrendAnalysis(recentTrend, shortTermTrend, acceleration)
    }

    private fun smoothBGSeries(data: List<BGDataPoint>): List<Pair<DateTime, Double>> {
        val alpha = preferences.get(DoubleKey.data_smoothing_alpha)
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

        // Map: 0.3 → 0.1, 0.9 → 0.3, lineair ertussen
        val consistencySetting = preferences.get(DoubleKey.direction_consistency_threshold)
        val dynamicThreshold = 0.1 + (consistencySetting - 0.3) * 0.333 // 0.1-0.3 range

        val positiveSlopes = slopes.count { it > dynamicThreshold }
        val negativeSlopes = slopes.count { it < -dynamicThreshold }
        val totalValidSlopes = positiveSlopes + negativeSlopes

        if (totalValidSlopes == 0) return 0.0
        val maxDirection = max(positiveSlopes, negativeSlopes)
        return maxDirection.toDouble() / totalValidSlopes
    }

    private fun calculateMagnitudeConsistency(slopes: List<Double>): Double {
        if (slopes.size < 2) return 0.0

        // Gebruik dezelfde dynamische threshold
        val consistencySetting = preferences.get(DoubleKey.direction_consistency_threshold)
        val dynamicThreshold = 0.1 + (consistencySetting - 0.3) * 0.333 // 0.1-0.3 range

        val significantSlopes = slopes.filter { abs(it) > dynamicThreshold }
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

        val smoothed = smoothBGSeries(recentDataForAnalysis)
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

    private fun calculateEnhancedPhaseDetection(
        robustTrends: RobustTrendAnalysis,
        historicalData: List<BGDataPoint>,
        currentBG: Double,
        previousPhase: String
    ): PhaseTransitionResult {

        if (historicalData.size < 6) {
            return PhaseTransitionResult(
                robustTrends.phase,
                1.0,
                "Insufficient data"
            )
        }

        val recentData = historicalData.takeLast(6)
        val slopes = calculateSlopeHistory(recentData)
        val currentSlope = robustTrends.firstDerivative

        // ★★★ DREMPELS UIT PREFERENCES ★★★
        val risingSlopeThreshold = preferences.get(DoubleKey.phase_rising_slope)
        val plateauSlopeThreshold = preferences.get(DoubleKey.phase_plateau_slope)

        // ★★★ VOORGESTELDE FASE (MINDER OVERLAP, MEER PLATEAU BIJ TWIJFEL) ★★★
        val proposedPhase = when {

            // Duidelijke stijging → rising
            currentSlope > risingSlopeThreshold ->
                "rising"

            // Nauwelijks verandering → plateau
            abs(currentSlope) <= plateauSlopeThreshold ->
                "plateau"

            // Alleen echte, overtuigende daling
            currentSlope < -1.0 ->
                "declining"

            // ★★★ TWIJFELGEBIED: EXPLICIET AFHANDELEN ★★★
            else -> when {

                // Rising alleen bij duidelijke + consistente trend
                hasConsistentRise(slopes, 3) &&
                    currentSlope > risingSlopeThreshold * 0.8 ->
                    "rising"

                // Kleine variaties → plateau
                slopes.all { abs(it) < plateauSlopeThreshold * 1.2 } ->
                    "plateau"

                // Geen harde daling → ook plateau
                currentSlope > -1.5 ->
                    "plateau"

                // Pas hier declining
                else ->
                    "declining"
            }
        }

        // ★★★ OVERGANGSSNELHEID (ONVERANDERD) ★★★
        val transitionFactor = when {
            currentSlope > 3.0 -> 1.0
            currentSlope > 2.0 -> 0.9
            currentSlope > 1.0 -> 0.8
            else -> 0.7
        }

        val debugInfo =
            "2-Fase: $previousPhase → $proposedPhase " +
                "(slope=${"%.2f".format(currentSlope)}, factor=$transitionFactor)"

        return PhaseTransitionResult(
            proposedPhase,
            transitionFactor,
            debugInfo
        )
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

        if (shouldBlockMathematicalBolusForHighIOB(
                currentIOB = currentIOB,
                maxIOB = maxIOB,
                robustTrends = robustTrends,
                detectedCarbs = detectedCarbs,
                mealDetected = detectedCarbs > 0
            )) {
            return MathematicalBolusAdvice(
                immediatePercentage = 0.0,
                reservedPercentage = 0.0,
                reason = "Math: Blocked due to high IOB (${"%.1f".format(currentIOB)}U)"
            )
        }

        val transitionFactor = lastPhaseTransitionFactor
        val baseRisingPerc = (preferences.get(IntKey.bolus_perc_rising).toDouble() / 100.0) * transitionFactor * iobAggressivenessFactor
        val basePlateauPerc = (preferences.get(IntKey.bolus_perc_plateau).toDouble() / 100.0) * transitionFactor * iobAggressivenessFactor

        val consistencyFactor = calculateConsistencyBasedScaling(robustTrends.consistency)
        val consistentRisingPerc = baseRisingPerc * consistencyFactor
        val consistentPlateauPerc = basePlateauPerc * consistencyFactor

        val overallAggressiveness = getCurrentBolusAggressiveness() / 100.0
        val combinedRisingPerc = consistentRisingPerc * overallAggressiveness
        val combinedPlateauPerc = consistentPlateauPerc * overallAggressiveness

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
            "rising" -> {
                val risingBoost = 1.2 // Lichte boost voor stijgende fase
                val boostedRisingPerc = combinedRisingPerc * risingBoost
                val finalImmediate = (boostedRisingPerc * totalDynamicFactor).coerceIn(0.0, 1.5)
                MathematicalBolusAdvice(
                    immediatePercentage = finalImmediate,
                    reservedPercentage = 0.15,
                    reason = "Math: Rising Phase (base=${(baseRisingPerc*100).toInt()}% × boost=${risingBoost} → ${(finalImmediate*100).toInt()}%, IOB=${"%.1f".format(currentIOB)}U, slope=${"%.1f".format(robustTrends.firstDerivative)})"
                )
            }
            "plateau" -> {
                val finalImmediate = (combinedPlateauPerc * totalDynamicFactor).coerceIn(0.0, 1.0)
                MathematicalBolusAdvice(
                    immediatePercentage = finalImmediate,
                    reservedPercentage = 0.05,
                    reason = "Math: Plateau Phase (${(finalImmediate*100).toInt()}%, IOB=${"%.1f".format(currentIOB)}U, slope=${"%.1f".format(robustTrends.firstDerivative)})"
                )
            }
            "declining" -> MathematicalBolusAdvice(0.0, 0.0, "Math: Declining - no bolus")
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
        detectedCarbs: Double = 0.0, // ★★★ NIEUW: maaltijd context
        mealDetected: Boolean = false // ★★★ NIEUW: maaltijd status
    ): Boolean {
        // ★★★ DYNAMISCHE IOB CAP TOEPASSEN ★★★
        val dynamicMaxIOB = calculateDynamicIOBCap(
            currentIOB = currentIOB,
            maxIOB = maxIOB,
            slope = robustTrends.firstDerivative,
            detectedCarbs = detectedCarbs,
            mealDetected = mealDetected
        )

        // ★★★ OPTIMALE IOB LIMIETEN VOOR 120% CORRECTIE ★★★
        if (mealDetected) {
            return when {
                currentIOB > dynamicMaxIOB * 1.15 -> true
                currentIOB > dynamicMaxIOB * 0.95 && robustTrends.firstDerivative < 1.0 -> true
                currentIOB > dynamicMaxIOB * 0.85 && robustTrends.firstDerivative < 0.5 -> true
                currentIOB > dynamicMaxIOB * 0.70 && robustTrends.firstDerivative < 0.2 -> true
                else -> false
            }
        } else {
            return when {
                currentIOB > dynamicMaxIOB * 1.15 -> true
                currentIOB > dynamicMaxIOB * 0.95 && robustTrends.firstDerivative < 2.0 -> true
                currentIOB > dynamicMaxIOB * 0.85 && robustTrends.firstDerivative < 1.0 -> true
                currentIOB > dynamicMaxIOB * 0.70 && robustTrends.firstDerivative < 0.5 -> true
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
        val dynamicMaxIOB = calculateDynamicIOBCap(
            currentIOB = currentIOB,
            maxIOB = maxIOB,
            slope = currentSlope,
            detectedCarbs = 0.0,  // correction heeft geen detected carbs
            mealDetected = false
        )

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

    private fun calculateDynamicIOBCap(
        currentIOB: Double,
        maxIOB: Double,
        slope: Double,
        detectedCarbs: Double = 0.0,
        mealDetected: Boolean = false
    ): Double {
        val isNight = isNachtTime()

        // ★★★ NACHT MODUS: VASTE 50% IOB CAP ★★★
        if (isNight && !mealDetected) {
            var nightCap = maxIOB * 0.5  // Vaste 50% van normale cap

            // Extra reductie bij dalende trend
            if (slope < -0.5) {
                nightCap = maxIOB * 0.3  // 30% bij dalende trend
            }

            return nightCap
        }

        // ★★★ DAG MODUS - ORIGINELE LOGICA ★★★
        if (mealDetected && detectedCarbs > 15) {
            val mealBoost = when {
                detectedCarbs > 80 -> 2.2
                detectedCarbs > 60 -> 2.0
                detectedCarbs > 40 -> 1.8
                detectedCarbs > 20 -> 1.6
                else -> 1.4
            }
            return maxIOB * mealBoost
        }

        val baseMaxIOB = maxIOB
        return when {
            slope > 8.0 -> baseMaxIOB * 2.0
            slope > 6.0 -> baseMaxIOB * 1.8
            slope > 4.0 -> baseMaxIOB * 1.6
            slope > 2.0 -> baseMaxIOB * 1.4
            slope > 1.0 -> baseMaxIOB * 1.2
            else -> baseMaxIOB
        }
    }

    // ★★★ FREQUENT BOLUS MANAGEMENT FUNCTIONS ★★★
    private fun calculateDynamicBolusIntervals(
        detectedCarbs: Double,
        baseMinMinutes: Int
    ): MealTimeIOBLimits {
        val isNight = isNachtTime()
        val maxBolus = getMaxBolus()
        // ★★★ NACHT MODUS: VASTE 20 MINUTEN INTERVAL ★★★
        if (isNight) {
            return MealTimeIOBLimits(
                maxSingleBolus = maxBolus * 0.7,  // 30% reductie in nacht
                maxIOB = preferences.get(DoubleKey.ApsSmbMaxIob) * 0.6,      // 40% lagere IOB cap
                minMinutesBetweenBoluses = 20,                               // Vaste 20 minuten in nacht
                maxConsecutiveBoluses = 2                                    // Max 2 bolussen in nacht
            )
        }

        // ★★★ DAG MODUS - ORIGINELE LOGICA ★★★
        return when {
            detectedCarbs > 80 -> MealTimeIOBLimits(
                maxSingleBolus = maxBolus,
                maxIOB = preferences.get(DoubleKey.ApsSmbMaxIob) * 1.3,
                minMinutesBetweenBoluses = (baseMinMinutes * 0.6).toInt().coerceAtLeast(5),
                maxConsecutiveBoluses = 5
            )
            detectedCarbs > 60 -> MealTimeIOBLimits(
                maxSingleBolus = maxBolus,
                maxIOB = preferences.get(DoubleKey.ApsSmbMaxIob) * 1.2,
                minMinutesBetweenBoluses = (baseMinMinutes * 0.75).toInt().coerceAtLeast(6),
                maxConsecutiveBoluses = 4
            )
            detectedCarbs > 40 -> MealTimeIOBLimits(
                maxSingleBolus = maxBolus,
                maxIOB = preferences.get(DoubleKey.ApsSmbMaxIob) * 1.1,
                minMinutesBetweenBoluses = (baseMinMinutes * 0.9).toInt().coerceAtLeast(7),
                maxConsecutiveBoluses = 3
            )
            else -> MealTimeIOBLimits(
                maxSingleBolus = maxBolus,
                maxIOB = preferences.get(DoubleKey.ApsSmbMaxIob),
                minMinutesBetweenBoluses = baseMinMinutes,
                maxConsecutiveBoluses = 2
            )
        }
    }

    private fun calculateProgressiveInterval(
        baseInterval: Int,
        consecutiveBoluses: Int
    ): Int {
        return when (consecutiveBoluses) {
            0 -> baseInterval
            1 -> (baseInterval * 1.2).toInt()
            2 -> (baseInterval * 1.5).toInt()
            3 -> (baseInterval * 2.0).toInt()
            else -> (baseInterval * 2.5).toInt()
        }
    }

    private fun calculateDynamicMaxTotalMealInsulin(
        detectedCarbs: Double,
        effectiveCR: Double,
        currentIOB: Double,
        maxBolus: Double,
        maxIOB: Double
    ): Double {
        // ★★★ NOOIT 0 TERUGGEVEN BIJ MAALTIJDEN ★★★
        if (detectedCarbs < 5.0) return 0.0

        val requiredInsulin = detectedCarbs / effectiveCR

        // ★★★ REALISTISCHE LIMIETEN ★★★
        val dynamicLimits = when {
            detectedCarbs > 80 -> DynamicMealLimits(2.0, 0.8, 0.7)   // 2.0x maxBolus, 80% IOB, 70% required
            detectedCarbs > 60 -> DynamicMealLimits(1.8, 0.75, 0.65)
            detectedCarbs > 40 -> DynamicMealLimits(1.6, 0.7, 0.6)
            detectedCarbs > 20 -> DynamicMealLimits(1.4, 0.65, 0.55)
            else -> DynamicMealLimits(1.2, 0.6, 0.5)
        }

        val maxBolusBased = maxBolus * dynamicLimits.maxTotalMultiplier
        val maxIOBBased = (maxIOB * dynamicLimits.iobUtilization) - currentIOB
        val requiredBased = requiredInsulin * dynamicLimits.requiredPercentage

        // ★★★ ZORG DAT RESULTAAT NOOIT NEGATIEF IS ★★★
        val result = max(0.5, minOf(maxBolusBased, maxIOBBased, requiredBased))

        return result
    }

    private fun shouldAllowFrequentBolusForLargeMeal(
        currentIOB: Double,
        mealTimeLimits: MealTimeIOBLimits,
        robustTrends: RobustTrendAnalysis?,
        currentBG: Double,
        targetBG: Double,
        lastBolusTime: DateTime?,
        consecutiveBoluses: Int
    ): Boolean {
        if (currentIOB > mealTimeLimits.maxIOB) return false
        if (consecutiveBoluses >= mealTimeLimits.maxConsecutiveBoluses) return false

        val minutesSinceLastBolus = lastBolusTime?.let {
            Minutes.minutesBetween(it, DateTime.now()).minutes
        } ?: Int.MAX_VALUE

        if (minutesSinceLastBolus < mealTimeLimits.minMinutesBetweenBoluses) return false

        return when {
            robustTrends?.firstDerivative ?: 0.0 > 6.0 -> true
            currentBG > targetBG + 8.0 && robustTrends?.firstDerivative ?: 0.0 > 2.0 -> true
            currentBG > targetBG + 6.0 && robustTrends?.firstDerivative ?: 0.0 > 3.0 -> true
            currentBG > targetBG + 4.0 && robustTrends?.firstDerivative ?: 0.0 > 4.0 -> true
            mealTimeLimits.maxIOB > 6.0 && robustTrends?.consistency ?: 0.0 > 0.6 -> true
            else -> false
        }
    }

    private fun shouldAllowVeryFrequentBolus(
        currentSlope: Double,
        currentBG: Double,
        targetBG: Double,
        consecutiveBoluses: Int
    ): Boolean {
        return when {
            consecutiveBoluses >= 2 && currentSlope < 2.0 -> false
            currentBG < targetBG + 2.0 -> false
            currentSlope < 1.0 -> false
            else -> true
        }
    }

    private fun predictHypoRiskWithMealContext(
        currentBG: Double,
        currentIOB: Double,
        effectiveISF: Double,
        carbsOnBoard: Double,
        detectedCarbs: Double,
        currentSlope: Double,
        minutesAhead: Int
    ): Double {
        val hours = minutesAhead / 60.0

        // ★★★ MEER REALISTISCHE INSULINE EFFECT ★★★
        val insulinEffect = currentIOB * effectiveISF * (1 - exp(-hours / 2.0)) // ← VERKORT van 3.0

        // ★★★ VERBETERDE CARB ABSORPTIE ★★★
        val carbAbsorptionRate = 0.05  // ← VERHOOGD van 0.035
        val carbEffect = carbsOnBoard * carbAbsorptionRate * hours

        // ★★★ TREND EFFECT MET COB COMPENSATIE ★★★
        val trendEffect = currentSlope * hours * 0.9  // ← VERMINDERDE DEMPING van 0.7

        val predictedBG = currentBG - insulinEffect + carbEffect + trendEffect

        // ★★★ LAGERE VEILIGHEIDSRANDEN TIJDENS MAALTIJDEN ★★★
        val mealTimeMinBG = when {
            detectedCarbs > 40 -> 3.8  // ← VERLAAGD van 4.5
            detectedCarbs > 20 -> 3.6  // ← VERLAAGD van 4.3
            detectedCarbs > 10 -> 3.5  // ← NIEUW
            else -> 3.4                // ← VERLAAGD van 4.0
        }

        // ★★★ EXTRA VEILIGHEID: VOORKOM TE LAGE VOORSPELLINGEN ★★★
        return predictedBG.coerceAtLeast(mealTimeMinBG).coerceAtMost(20.0)
    }

    // ★★★  STOP FUNCTIE MET MAALTIJD CONTEXT ★★★
    private fun shouldStopInsulinDelivery(
        robustTrends: RobustTrendAnalysis,
        historicalData: List<BGDataPoint>,
        phasedBolusManager: PhasedBolusManager,
        currentBG: Double,
        currentIOB: Double,
        effectiveISF: Double,
        mealDetected: Boolean = false,
        detectedCarbs: Double = 0.0,
        maxIOB: Double,
        carbsOnBoard: Double // ★★★ NIEUW: COB parameter voor betere predictie
    ): Boolean {
        // ★★★ MAALTIJD MODUS: MINDER RESTRICTIEF ★★★
        if (mealDetected && detectedCarbs > 15) {
            return shouldStopDuringMealMode(
                currentBG = currentBG,
                currentIOB = currentIOB,
                effectiveISF = effectiveISF,
                detectedCarbs = detectedCarbs,
                carbsOnBoard = carbsOnBoard,
                currentSlope = robustTrends.firstDerivative,
                phasedBolusManager = phasedBolusManager,
                maxIOB = maxIOB
            )
        }

        // ★★★ STANDARD NON-MEAL MODUS (behoud originele veiligheid) ★★★
        return shouldStopNormalMode(
            currentBG = currentBG,
            currentIOB = currentIOB,
            effectiveISF = effectiveISF,
            robustTrends = robustTrends,
            phasedBolusManager = phasedBolusManager,
            maxIOB = maxIOB
        )
    }

    private fun shouldStopDuringMealMode(
        currentBG: Double,
        currentIOB: Double,
        effectiveISF: Double,
        detectedCarbs: Double,
        carbsOnBoard: Double,
        currentSlope: Double,
        phasedBolusManager: PhasedBolusManager,
        maxIOB: Double
    ): Boolean {
        val isNight = isNachtTime()

        // ★★★ NACHT MODUS: CONSERVATIEVER ★★★
        if (isNight) {
            // Stop bij matige daling in nacht
            if (currentSlope < -0.5) {
                return true
            }

            // Stop bij 2+ opeenvolgende bolussen in nacht
            if (phasedBolusManager.getConsecutiveBolusesCount() >= 2) {
                return true
            }

            // Strengere hypo predictie in nacht
            val predictedBG = predictHypoRiskWithMealContext(
                currentBG = currentBG,
                currentIOB = currentIOB,
                effectiveISF = effectiveISF,
                carbsOnBoard = carbsOnBoard,
                detectedCarbs = detectedCarbs,
                currentSlope = currentSlope,
                minutesAhead = 120
            )

            if (predictedBG < 5.0) {  // Hogere drempel in nacht (5.0 i.p.v. 3.4-3.8)
                return true
            }

            // Stop bij hoge IOB in nacht
            if (currentIOB > maxIOB * 0.5) {
                return true
            }

            return false
        }

        // ★★★ DAG MODUS: MINDER RESTRICTIEF TIJDENS MAALTIJD ★★★

        // NOOIT stoppen bij sterke stijging tijdens maaltijd
        if (currentSlope > 2.0 && detectedCarbs > 20) {
            return false
        }

        // ★★★ NIEUW: CUMULATIEVE DOSIS CONTROLE ★★★
        val totalMealInsulin = phasedBolusManager.getTotalInsulinDelivered()
        // Max 0.12U per gram carbs (i.p.v. 0.15) - meer conservatief
        val maxRecommendedMealInsulin = max(1.0, detectedCarbs * 0.12)

        if (totalMealInsulin > maxRecommendedMealInsulin) {
            return true
        }

        // ★★★ NIEUW: VEEL BOLUSSEN IN KORTE TIJD ★★★
        val bolusCount = phasedBolusManager.getConsecutiveBolusesCount()
        val lastBolusTime = phasedBolusManager.getLastBolusTime()
        val mealStartTime = phasedBolusManager.getMealStartTime()

        if (bolusCount >= 4 && mealStartTime != null) {
            val minutesSinceMealStart = Minutes.minutesBetween(mealStartTime, DateTime.now()).minutes

            // 6+ bolussen in minder dan 45 minuten = te veel
            if (minutesSinceMealStart < 45 && bolusCount >= 6) {
                return true
            }

            // 8+ bolussen in minder dan 90 minuten = te veel
            if (minutesSinceMealStart < 90 && bolusCount >= 8) {
                return true
            }
        }

        // ★★★ BEHOUD EXISTENDE MEER OPEENVOLGENDE BOLUSSEN LOGICA ★★★
        if (phasedBolusManager.getConsecutiveBolusesCount() >= 8) {
            return true
        }

        // ★★★ MINDER RESTRICTIEVE IOB CHECK ★★★
        if (currentIOB > maxIOB * 0.95) {
            return true
        }

        // ★★★ VEILIGERE HYPO PREDICTIE TIJDENS MAALTIJD ★★★
        val predictedBG = predictHypoRiskWithMealContext(
            currentBG = currentBG,
            currentIOB = currentIOB,
            effectiveISF = effectiveISF,
            carbsOnBoard = carbsOnBoard,
            detectedCarbs = detectedCarbs,
            currentSlope = currentSlope,
            minutesAhead = 60  // KORTERE PREDICTIE TIJDENS MAALTIJD
        )

        val mealHypoThreshold = when {
            detectedCarbs > 40 -> 3.8  // VERHOOGD van 3.5
            detectedCarbs > 20 -> 3.6  // VERHOOGD van 3.4
            detectedCarbs > 10 -> 3.4  // NIEUW
            else -> 3.2
        }

        // ★★★ BEHOUD BESTAANDE HYPO PREDICTIE LOGICA ★★★
        val shouldStop = predictedBG < mealHypoThreshold &&
            currentSlope < -3.0 &&  // VERLAAGD van -2.0 (meer daling nodig)
            currentIOB > maxIOB * 0.9  // VERHOOGD van 0.8

        return shouldStop
    }

    private fun shouldAllowInsulinForHighBG(
        currentBG: Double,
        targetBG: Double,
        currentIOB: Double,
        maxIOB: Double,
        robustTrends: RobustTrendAnalysis?,
        detectedCarbs: Double
    ): Boolean {
        val bgAboveTarget = currentBG - targetBG

        // ★★★ EXTREME OVERRIDE VOOR HOGE BG TIJDENS MAALTIJD ★★★
        if (detectedCarbs > 30 && currentBG > 9.0) {
            return true  // Bij grote maaltijd en hoge BG altijd toestaan
        }

        return when {
            // Bij zeer hoge BG (>12), altijd insulin toestaan
            currentBG > 12.0 -> true

            // Bij hoge BG (10-12) en stijgende trend, insulin toestaan
            currentBG > 10.0 && (robustTrends?.firstDerivative ?: 0.0) > 1.0 -> true

            // Bij matige BG (8-10) met gedetecteerde carbs, insulin toestaan
            currentBG > 8.0 && detectedCarbs > 20 -> true

            // Bij stijgende trend en carbs, insulin toestaan
            currentBG > 7.0 && (robustTrends?.firstDerivative ?: 0.0) > 2.0 && detectedCarbs > 15 -> true

            // Anders standaard veiligheidschecks
            else -> currentIOB < maxIOB * 0.9 && bgAboveTarget > 0.5
        }
    }

    private fun calculateDynamicBolusForMeal(
        currentBG: Double,
        targetBG: Double,
        detectedCarbs: Double,
        currentSlope: Double,
        effectiveCR: Double,
        maxBolus: Double,
        consecutiveBoluses: Int
    ): Double {
        val lowBGFactor = when {
            currentBG < targetBG -> 0.1  // Onder target (5.2): 80% reductie
            currentBG < targetBG + 0.3 -> 0.15  // Onder (5.5): 70% reductie
            currentBG < targetBG + 0.5 -> 0.2  // Onder (5.7): 60% reductie
            currentBG < targetBG + 0.7 -> 0.3  // Onder (5.9): 40% reductie
            currentBG < targetBG + 1.0 -> 0.4  // Onder (6.2): 20% reductie
            else -> 0.8
        }

        // ★★★ BASIS BOLUS GEBASEERD OP CARBS ★★★
        val baseCarbsBolus = (detectedCarbs / effectiveCR) * lowBGFactor

        // ★★★ STIJGING CORRECTION ★★★
        val slopeCorrection = when {
            currentSlope > 5.0 -> 0.4 * maxBolus
            currentSlope > 3.0 -> 0.3 * maxBolus
            currentSlope > 2.0 -> 0.2 * maxBolus
            currentSlope > 1.0 -> 0.1 * maxBolus
            else -> 0.0
        }

        // ★★★ BG BOVEN TARGET CORRECTION ★★★
        val bgAboveTarget = max(0.0, currentBG - targetBG)
        val bgCorrection = bgAboveTarget * 0.15  // 15% van BG overschot

        // ★★★ PROGRESSIVE REDUCTION VOOR OPEENVOLGENDE BOLUSSEN ★★★
        val progressFactor = when (consecutiveBoluses) {
            0 -> 1.0  // Eerste bolus: 100%
            1 -> 1.0  // Tweede bolus: 100%
            2 -> 0.9  // Derde bolus: 90%
            3 -> 0.8  // Vierde bolus: 80%
            4 -> 0.7  // Vijfde bolus: 70%
            5 -> 0.6  // Zesde bolus: 60%
            6 -> 0.5  // Zevende bolus: 50%
            else -> 0.4  // Achtste en meer: 40%
        }

        val totalBolus = (baseCarbsBolus + slopeCorrection + bgCorrection) * progressFactor

        // ★★★ VEILIGHEIDSLIMIETEN ★★★
        val safeBolus = totalBolus
            .coerceAtMost(maxBolus * 1.5)  // Max 1.5x maxBolus
            .coerceAtLeast(0.1)  // Minimaal 0.1E

        return safeBolus
    }

    // ★★★ NIEUWE FUNCTIE: NORMALE MODUS ★★★
    private fun shouldStopNormalMode(
        currentBG: Double,
        currentIOB: Double,
        effectiveISF: Double,
        robustTrends: RobustTrendAnalysis,
        phasedBolusManager: PhasedBolusManager,
        maxIOB: Double
    ): Boolean {
        // ★★★ HYPO RISICO CHECK - BEHOUD ORIGINELE VEILIGHEID ★★★
        val safetyMargin = hypo_risk_percentage / 100.0
        val predictedBGLow = predictHypoRiskWithMealContext(
            currentBG = currentBG,
            currentIOB = currentIOB,
            effectiveISF = effectiveISF,
            carbsOnBoard = 0.0,
            detectedCarbs = 0.0,
            currentSlope = robustTrends.firstDerivative,
            minutesAhead = 90
        )
        val safetyThreshold = 4.5 + (1.0 - safetyMargin)

        if (predictedBGLow < safetyThreshold) {
            return true
        }

        // ★★★ 2. TREND + BG CONDITIES ★★★
        val currentSlope = robustTrends.firstDerivative
        val targetBG = getEffectiveTarget()

        // Originele dynamische drempels
        val (declineThreshold, stopOnLowSlope) = calculateDynamicThresholds(
            mealDetected = false,
            detectedCarbs = 0.0,
            currentSlope = currentSlope,
            currentBG = currentBG,
            mealConfidenceFactor = 0.0,
            overallStopFactor = 0.0,
            targetBG = targetBG
        )

        if (currentSlope < declineThreshold) return true
        if (stopOnLowSlope) return true

        // ★★★ 3. CONSECUTIVE BOLUS LIMIET ★★★
        if (shouldStopForConsecutiveBoluses(phasedBolusManager, false, 0.0, 0.0)) {
            return true
        }

        // ★★★ 4. IOB LIMIET ★★★
        if (shouldStopForHighIOB(currentIOB, currentSlope, false, 0.0, 0.0, maxIOB)) {
            return true
        }

        return false
    }

    // ★★★ HELPER FUNCTIES ★★★
    private fun calculateDynamicThresholds(
        mealDetected: Boolean,
        detectedCarbs: Double,
        currentSlope: Double,
        currentBG: Double,
        mealConfidenceFactor: Double,
        overallStopFactor: Double,
        targetBG: Double
    ): Pair<Double, Boolean> {
        return when {
            // ★★★ MAALTIJD MODUS - MINIMALE BEPERKINGEN ★★★
            mealDetected && detectedCarbs > 20 -> {
                // Hogere mealConfidence = minder snel stoppen
                val declineThreshold = -3.0 + (overallStopFactor * 2.0) // -3.0 tot -1.0
                val stopOnLowSlope = currentSlope < 0 && currentBG < targetBG + 0.5
                Pair(declineThreshold, stopOnLowSlope)
            }

            // ★★★ STERKE STIJGING - NOOIT STOPPEN ★★★
            currentSlope > 3.0 && currentBG > 8.0 -> {
                Pair(-10.0, false) // Vrijwel onmogelijke drempel
            }

            // ★★★ STANDARD MODUS ★★★
            else -> {
                val baseDecline = -1.5 + (overallStopFactor * 1.0) // -1.5 tot -0.5
                val stopOnLowSlope = currentBG < targetBG + 1.0 && currentSlope < 0
                Pair(baseDecline, stopOnLowSlope)
            }
        }
    }

    private fun shouldStopForConsecutiveBoluses(
        phasedBolusManager: PhasedBolusManager,
        mealDetected: Boolean,
        detectedCarbs: Double,
        overallStopFactor: Double
    ): Boolean {
        val baseMaxConsecutive = when {
            mealDetected && detectedCarbs > 40 -> 6
            mealDetected -> 5
            else -> 4
        }

        // Hogere stopFactor = minder consecutive boluses toegestaan
        val adjustedMax = max(2, (baseMaxConsecutive * (1.0 - overallStopFactor * 0.5)).toInt())
        return phasedBolusManager.getConsecutiveBolusesCount() >= adjustedMax
    }

    private fun shouldStopForHighIOB(
        currentIOB: Double,
        currentSlope: Double,
        mealDetected: Boolean,
        detectedCarbs: Double,
        overallStopFactor: Double,
        maxIOB: Double
    ): Boolean {
        val iobRatio = currentIOB / maxIOB

        val iobStopThreshold = when {
            mealDetected && detectedCarbs > 30 -> 0.9 - (overallStopFactor * 0.2) // 0.9-0.7
            mealDetected -> 0.8 - (overallStopFactor * 0.15) // 0.8-0.65
            currentSlope > 2.0 -> 0.85 - (overallStopFactor * 0.15) // 0.85-0.7
            // ★★★ AANPASSING: Minder restrictief bij dalende BG na maaltijd ★★★
            currentSlope < -0.5 && iobRatio > 0.4 -> 0.6 // Lagere drempel bij dalende trend + IOB
            else -> 0.7 - (overallStopFactor * 0.1) // 0.7-0.6
        }

        return iobRatio > iobStopThreshold && currentSlope < 1.0
    }
    private fun calculateDynamicReservedBolusRelease(
        currentBG: Double,
        targetBG: Double,
        currentIOB: Double,
        mealTimeLimits: MealTimeIOBLimits,
        phasedBolusManager: PhasedBolusManager
    ): Double {
        if (pendingReservedBolus <= 0.0) return 0.0

        if (!phasedBolusManager.canDeliverBolus(mealTimeLimits)) return 0.0

        val currentSlope = lastRobustTrends?.firstDerivative ?: 0.0
        val bgAboveTarget = currentBG - targetBG

        // ★★★ MEER AGGRESSIEVE RELEASE BIJ HOGE BG ★★★
        val baseReleasePercentage = when {
            currentBG > 11.0 -> 0.8
            currentBG > 10.0 -> 0.6
            currentBG > 9.0 && currentSlope > 2.0 -> 0.5
            currentSlope > 4.0 -> 0.7
            currentSlope > 3.0 && bgAboveTarget > 4.0 -> 0.6
            bgAboveTarget > 6.0 -> 0.4
            bgAboveTarget > 4.0 -> 0.3
            else -> 0.2
        }.coerceIn(0.1, 1.0)

        val releaseAmount = pendingReservedBolus * baseReleasePercentage
        val cappedRelease = minOf(releaseAmount, mealTimeLimits.maxSingleBolus)

        // ★★★ OVERRIDE: BIJ ZEER HOGE BG, FORCEER RELEASE ★★★
        val finalRelease = when {
            currentBG > 12.0 -> minOf(pendingReservedBolus, getMaxBolus() * 0.8)
            currentBG > 10.0 && pendingReservedCarbs > 30 -> cappedRelease * 1.2
            else -> cappedRelease
        }.coerceAtMost(pendingReservedBolus)

        if (finalRelease > 0.1) {
            pendingReservedBolus -= finalRelease
            phasedBolusManager.recordBolusDelivery(finalRelease, "Reserved bolus release - high BG override")

            if (pendingReservedBolus < 0.1) {
                pendingReservedBolus = 0.0
                pendingReservedCarbs = 0.0
                pendingReservedTimestamp = null
            }

            return finalRelease
        }

        return 0.0
    }


    private fun getMathematicalBolusAsOnlyMethod(
        robustTrends: RobustTrendAnalysis,
        detectedCarbs: Double,
        currentBG: Double,
        targetBG: Double,
        historicalData: List<BGDataPoint>,
        currentIOB: Double,
        maxIOB: Double,
        effectiveCR: Double,
        maxBolus: Double
    ): Triple<Double, Double, String> {
        val iobRatio = currentIOB / maxIOB

        // ★★★ AGGRESSIEVE IOB REDUCTIE ★★★
        val iobReductionFactor = when {
            iobRatio > 0.7 -> 0.4  // 60% reductie bij hoge IOB
            iobRatio > 0.5 -> 0.6  // 40% reductie
            iobRatio > 0.3 -> 0.8  // 20% reductie
            iobRatio > 0.1 -> 0.9  // 10% reductie
            else -> 1.0
        }

        // ★★★ CAP DETECTED CARBS OP BASIS VAN IOB ★★★
        val iobBasedCarbCap = when {
            iobRatio > 0.6 -> 40.0  // Max 40g carbs bij hoge IOB
            iobRatio > 0.4 -> 60.0  // Max 60g bij matige IOB
            iobRatio > 0.2 -> 80.0  // Max 80g bij lage IOB
            else -> 100.0
        }

        val safeDetectedCarbs = detectedCarbs.coerceAtMost(iobBasedCarbCap)

        // ★★★ VERLAAGDE BOOST FACTORS BIJ HOGE IOB ★★★
        val baseBoost = 1.0 + (robustTrends.firstDerivative / 10.0).coerceAtMost(0.5)
        val iobAdjustedBoost = baseBoost * iobReductionFactor

        val totalCarbBolus = safeDetectedCarbs / effectiveCR
        val boostedTotalCarbBolus = totalCarbBolus * iobAdjustedBoost

        val mathAdvice = getMathematicalBolusAdvice(
            robustTrends = robustTrends,
            detectedCarbs = safeDetectedCarbs,
            currentBG = currentBG,
            targetBG = targetBG,
            historicalData = historicalData,
            currentIOB = currentIOB,
            maxIOB = maxIOB
        )

        // === ROUTE A – STEP 2.1: EARLY-RISE TIMING BOOST ===
        var immediateFactor = 1.0
        var reservedFactor = 1.0

        if (
            robustTrends.phase == "early_rise" &&
            robustTrends.firstDerivative > 1.2 &&
            robustTrends.consistency > 0.6
        ) {
            // Vroeger insulineren, niet méér insulineren
            immediateFactor = 1.15
            reservedFactor = 0.85
        }

        val rawImmediateBolus =
            boostedTotalCarbBolus * mathAdvice.immediatePercentage * immediateFactor

        val rawReservedBolus =
            boostedTotalCarbBolus * mathAdvice.reservedPercentage * reservedFactor
        // === MAX BOLUS CAP AWARE SPLIT ===
        val cappedImmediate = rawImmediateBolus.coerceAtMost(maxBolus)
        val overflow = (rawImmediateBolus - cappedImmediate).coerceAtLeast(0.0)

// Overflow niet verliezen → naar reserved
        val immediateBolus = cappedImmediate
        val reservedBolus = rawReservedBolus + overflow

        if (overflow > 0.05) {
            lastReservedBolusDebug +=
                " | EARLY-CAP: ${"%.2f".format(overflow)}U → reserved"
        }


        // ★★★ APPLY MAX BOLUS SAFETY ★★★
        val safeImmediate = enforceMaxBolusSafety(immediateBolus, maxBolus, currentBG, currentIOB, maxIOB)
        val safeReserved = reservedBolus.coerceAtMost(maxBolus * 0.3)

        val reason = "IOB-AWARE-Math: ${robustTrends.phase} " +
            "(carbs=${safeDetectedCarbs.toInt()}g→${totalCarbBolus.round(2)}U, " +
            "IOB=${currentIOB.round(1)}U/${maxIOB}U, " +
            "reduction=${(iobReductionFactor*100).toInt()}%, " +
            "boost=${iobAdjustedBoost.round(2)}x)"

        return Triple(safeImmediate, safeReserved, reason)
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

    // ★★★ NIEUWE FUNCTIE: NACHTELIJKE BOLUS BLOKKADE ★★★
    private fun shouldBlockBolusForNightTime(
        currentIOB: Double,
        maxIOB: Double,
        consecutiveBoluses: Int,
        lastBolusTime: DateTime?,
        currentSlope: Double
    ): Boolean {
        if (!isNachtTime()) return false

        // Te veel IOB in nacht
        if (currentIOB > maxIOB * 0.5) {
            return true
        }

        // Te veel opeenvolgende bolussen in nacht
        if (consecutiveBoluses >= 2) {
            return true
        }

        // 20 minuten interval in nacht
        lastBolusTime?.let {
            val minutesSinceLast = Minutes.minutesBetween(it, DateTime.now()).minutes
            if (minutesSinceLast < 20) {
                return true
            }
        }

        // Dalende trend in nacht
        if (currentSlope < -0.5) {
            return true
        }

        return false
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

    // ★★★ COOLDOWN HELPER FUNCTIES voor persistent★★★
    private fun shouldAllowPersistentBolusAfterMeal(): Boolean {
        lastMealBolusTime?.let { lastMealTime ->
            val minutesSinceMeal = Minutes.minutesBetween(lastMealTime, DateTime.now()).minutes
            return minutesSinceMeal > MEAL_BOLUS_COOLDOWN_MINUTES
        }
        return true // Geen maaltijdbolus gegeven, dus toegestaan
    }

    private fun updateLastMealBolusTime() {
        lastMealBolusTime = DateTime.now()
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
            saveCob(detectedCarbs)
        } else {
            val oldCarbs = recentMeal.totalCarbs
            recentMeal.totalCarbs = max(oldCarbs, detectedCarbs)
            lastCOBDebug = "UPDATE_MEAL: ${oldCarbs}g -> ${recentMeal.totalCarbs}g"
            val currentCOB = getCarbsOnBoard()
            saveCob(currentCOB)
        }

        val currentCOB = getCarbsOnBoard()
        lastCOBDebug += " | TOTAL_COB: ${currentCOB}g, ACTIVE_MEALS: ${activeMeals.size}"
        saveCob(currentCOB)
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

       //     logMealPerformanceResult(currentData, historicalData, peakConfidence, mealType)
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
        val limitedDose = min(conservativeDose, getMaxBolus())
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

    private fun enforceMaxBolusSafety(
        proposedDose: Double,
        maxBolus: Double,
        currentBG: Double,
        currentIOB: Double,
        maxIOB: Double
    ): Double {
        val iobRatio = currentIOB / maxIOB

        // ★★★ STRENGERE CAPPING BIJ HOGE IOB ★★★
        val dynamicMaxBolus = when {
            iobRatio > 0.8 -> maxBolus * 0.5   // 50% bij zeer hoge IOB
            iobRatio > 0.6 -> maxBolus * 0.7   // 70% bij hoge IOB
            iobRatio > 0.4 -> maxBolus * 0.85  // 85% bij matige IOB
            else -> maxBolus
        }

        // ★★★ EXTREME BG OVERRIDE ALLEEN BIJ >15 ★★★
        val absoluteMax = when {
            currentBG > 15.0 && iobRatio < 0.3 -> dynamicMaxBolus * 1.5
            currentBG > 12.0 && iobRatio < 0.4 -> dynamicMaxBolus * 1.2
            else -> dynamicMaxBolus
        }

        val safeDose = proposedDose.coerceAtMost(absoluteMax)

        if (safeDose < proposedDose) {
            lastReservedBolusDebug = "MAX_BOLUS_SAFETY: ${round(proposedDose, 2)}U → ${round(safeDose, 2)}U (IOB:${round(iobRatio*100,0)}%)"
        }

        return safeDose
    }

    // ★★★ VEILIGE MAX BOLUS MULTIPLIER BEPALING ★★★
    private fun determineSafeMaxBolusMultiplier(
        currentBG: Double,
        currentIOB: Double,
        maxIOB: Double,
        currentSlope: Double,
        isNachtTime: Boolean
    ): Double {
        val iobRatio = currentIOB / maxIOB

        return when {
            // ★★★ CONDITIE 1: Zeer hoge BG + zeer lage IOB ★★★
            currentBG > 15.0 && iobRatio < 0.2 -> 1.3

            // ★★★ CONDITIE 2: Extreme stijging + ruime IOB headroom ★★★
            currentSlope > 8.0 && iobRatio < 0.3 && currentBG > 10.0 -> 1.2

            // ★★★ CONDITIE 3: Nachttijd + zeer hoge BG ★★★
            isNachtTime && currentBG > 16.0 && iobRatio < 0.25 -> 1.25

            // ★★★ STANDAARD: Geen verhoging ★★★
            else -> 1.0
        }
    }

    // ★★★ SAFE HYBRID BASAL CALCULATION - VEILIGE TOTALE INSULIN SPLITSING ★★★
    private fun calculateSafeHybridAmount(
        totalInsulin: Double,
        hybridPercentage: Int,
        maxBolus: Double,
        currentIOB: Double,
        maxIOB: Double,
        currentBG: Double,
        currentSlope: Double,
        isNachtTime: Boolean
    ): Triple<Double, Double, Double> {

        // ★★★ BEPAAL MAXIMUM TOTALE INSULIN MULTIPLIER ★★★
        val maxTotalMultiplier = determineSafeMaxBolusMultiplier(
            currentBG = currentBG,
            currentIOB = currentIOB,
            maxIOB = maxIOB,
            currentSlope = currentSlope,
            isNachtTime = isNachtTime
        )

        // ★★★ BEREKEN VEILIGE TOTALE INSULIN ★★★
        val maxTotalInsulin = maxBolus * maxTotalMultiplier
        val safeTotalInsulin = totalInsulin.coerceAtMost(maxTotalInsulin)

        // ★★★ SPLITS IN BOLUS EN BASAAL VOLGENS HYBRIDE PERCENTAGE ★★★
        val basalAmount = safeTotalInsulin * (hybridPercentage / 100.0)
        val bolusAmount = safeTotalInsulin - basalAmount

        // ★★★ ENFORCE ABSOLUTE BOLUS LIMIT ★★★
        val safeBolus = bolusAmount.coerceAtMost(maxBolus)

        // ★★★ BEREKEN IOB HEADROOM ★★★
        val iobHeadroom = (maxIOB - currentIOB).coerceAtLeast(0.0)

        // ★★★ ZORG DAT TOTAAL BINNEN IOB LIMIET BLIJFT ★★★
        val maxAllowedByIOB = iobHeadroom * 0.75 // 75% veiligheidsmarge
        val finalTotal = (safeBolus + basalAmount).coerceAtMost(maxAllowedByIOB)

        // ★★★ HERBEREEKEN BASAAL OP BASIS VAN VEILIG TOTAAL ★★★
        val finalBasal = (finalTotal - safeBolus).coerceAtLeast(0.0)
        val finalBolus = safeBolus.coerceAtMost(maxBolus)

        // ★★★ DEBUG LOGGING ★★★
        if (maxTotalMultiplier > 1.0) {
            lastReservedBolusDebug = "MAX_BOLUS_OVERRIDE: ${round(maxTotalMultiplier, 2)}x (BG:${round(currentBG, 1)}, IOB:${round(currentIOB, 1)}U)"
        }

        return Triple(finalBolus, finalBasal, finalTotal)
    }


    // ★★★ SAFE HIGH BG OVERRIDE ★★★
    private fun calculateHighBGOverrideDose(
        proposedDose: Double,
        maxBolus: Double,
        currentBG: Double,
        currentIOB: Double,  // ★★★ NIEUW: currentIOB parameter toegevoegd
        maxIOB: Double       // ★★★ NIEUW: maxIOB parameter toegevoegd
    ): Double {
        // Alleen override in extreme situaties met strikte limieten
        val shouldOverride = currentBG > 14.0 &&           // Alleen bij zeer hoge BG
            currentIOB < maxIOB * 0.3 &&  // Alleen bij lage IOB
            proposedDose < maxBolus * 2.0 // Nooit meer dan 2x max bolus

        if (!shouldOverride) {
            return proposedDose.coerceAtMost(maxBolus) // Geen override, gebruik normale limiet
        }

        // Bereken veilige override dosis
        val overrideFactor = when {
            currentBG > 18.0 -> 1.5
            currentBG > 16.0 -> 1.4
            currentBG > 14.0 -> 1.3
            else -> 1.0
        }

        return (proposedDose.coerceAtMost(maxBolus * overrideFactor))
    }

    private fun trackCumulativeDose(currentDose: Double, maxBolus: Double): Double {
        val totalRecentInsulin = phasedBolusManager.getTotalInsulinDelivered()
        val consecutiveBoluses = phasedBolusManager.getConsecutiveBolusesCount()

        // ★★★ DYNAMISCHE CUMULATIVE LIMIET ★★★
        val dynamicLimitMultiplier = when (consecutiveBoluses) {
            0 -> 2.5  // Eerste bolus: 2.5x maxBolus
            1 -> 2.0  // Tweede bolus: 2.0x
            2 -> 1.5  // Derde bolus: 1.5x
            3 -> 1.2  // Vierde bolus: 1.2x
            else -> 1.0  // Verdere bolussen: 1.0x
        }

        val safeCumulativeLimit = maxBolus * dynamicLimitMultiplier
        val proposedTotal = totalRecentInsulin + currentDose

        return if (proposedTotal > safeCumulativeLimit) {
            val allowedDose = (safeCumulativeLimit - totalRecentInsulin).coerceAtLeast(0.0)
            lastReservedBolusDebug = "CUMULATIVE_SAFETY: ${round(currentDose,2)}U→${round(allowedDose,2)}U (recent:${round(totalRecentInsulin,2)}U, limit:${round(safeCumulativeLimit,2)}U)"
            allowedDose
        } else {
            currentDose
        }
    }

    private fun shouldLimitBolusForHighIOB(
        currentIOB: Double,
        maxIOB: Double,
        proposedDose: Double,
        currentBG: Double,
        targetBG: Double,
        detectedCarbs: Double
    ): Pair<Boolean, Double> {
        val iobRatio = currentIOB / maxIOB
        val bgAboveTarget = currentBG - targetBG

        // ★★★ NOOIT BLOKKEEREN BIJ ZEER HOGE BG EN LAGE IOB ★★★
        if (currentBG > 14.0 && iobRatio < 0.3) {
            return Pair(false, proposedDose)
        }

        // ★★★ DYNAMISCHE LIMIETEN ★★★
        return when {
            // ★★★ CRITICAL: BIJ EXTREEM HOGE IOB, BLOKKEER ★★★
            iobRatio > 1.0 -> Pair(true, 0.0)

            // ★★★ HOGE IOB + LAGE BG = BLOKKEER ★★★
            iobRatio > 0.8 && bgAboveTarget < 2.0 -> Pair(true, 0.0)

            // ★★★ MATIGE IOB + MATIGE BG = REDUCEER ★★★
            iobRatio > 0.7 -> {
                val maxAllowed = maxIOB * 0.05  // Max 5% van maxIOB
                Pair(true, proposedDose.coerceAtMost(maxAllowed))
            }

            iobRatio > 0.6 -> {
                val maxAllowed = maxIOB * 0.08  // Max 8% van maxIOB
                Pair(true, proposedDose.coerceAtMost(maxAllowed))
            }

            iobRatio > 0.5 -> {
                val maxAllowed = maxIOB * 0.12  // Max 12% van maxIOB
                Pair(true, proposedDose.coerceAtMost(maxAllowed))
            }

            // ★★★ BIJ MAALTIJDEN, MINDER RESTRICTIEF ★★★
            detectedCarbs > 20 && bgAboveTarget > 3.0 -> {
                // Tijdens maaltijd met hoge BG: minder restrictief
                val multiplier = 1.0 - (iobRatio * 0.5)  // Max 50% reductie
                Pair(true, proposedDose * multiplier)
            }

            else -> Pair(false, proposedDose)
        }
    }

    // ★★★ HOOFD ADVIES FUNCTIE ★★★
    fun getEnhancedInsulinAdvice(
        currentData: BGDataPoint,
        historicalData: List<BGDataPoint>,
        currentISF: Double,
        carbRatio: Double,
        currentIOB: Double,
        maxIOB: Double
    ): EnhancedInsulinAdvice {
// ★★★ METRICS LOGGING — ALTIJD, ONAFHANKELIJK VAN BESLISSING ★★★
        try {
            metricsHelper.logMetricsTick(
                timestamp = DateTime.now(),
                bg = currentData.bg,
                iob = currentIOB,
                target = getEffectiveTarget()  // of getEffectiveTarget()
            )
        } catch (_: Exception) {
            // metrics logging mag NOOIT FCL blokkeren
        }

        // ★★★ EÉNMALIGE UPDATE ★★★
        updateActivityFromSteps()
        updateResistentieIndienNodig()
        //  berekenStappenAdjustment()

  // ─────────────────────────────────────────────
  // FASE 0 — SETUP, METRICS & INPUT
  // ─────────────────────────────────────────────

        try {
            val trends = analyzeTrends(historicalData)
 // ─────────────────────────────────────────────
 // DECISION LOCK — voorkomt overschrijven
 // ─────────────────────────────────────────────

            var maxBolus = getMaxBolus()
            val effectiveISF = getEffectiveISF()
            val effectiveTarget = getEffectiveTarget()

            // ★★★ ALTIJD ROBUUSTE FASE GEBRUIKEN ★★★
            val robustTrends = calculateRobustTrends(historicalData)
            val robustPhase = robustTrends.phase

            cleanUpMeals()

            val cobNow = getCarbsOnBoard()
            val basicAdvice = getInsulinAdvice(
                currentData, historicalData,
                effectiveISF, effectiveTarget, carbRatio, currentIOB, maxIOB
            )

            // Placeholders
            var finalDose = 0.0
            var finalBolusAmount = 0.0
            var finalReason = ""
            var finalDeliver = false
            var finalMealDetected = false
            var finalDetectedCarbs = 0.0
            var finalReservedBolus = 0.0
            var finalPhase = "stable"
            var finalConfidence = 0.7  // vaste confidence, learning uit
            var predictedPeak = basicAdvice.predictedValue ?: currentData.bg
            var finalCOB = cobNow

            var hybridPercentage = 0 // geen bolus als basaal default
            var debugLog: String = ""

            var decisionLocked = false
            var decisionSource = ""
            data class LockedDecision(
                val dose: Double,
                val deliver: Boolean,
                val reason: String,
                val phase: String,
                val confidence: Double,
                val mealDetected: Boolean,
                val detectedCarbs: Double
            )

            var lockedDecision: LockedDecision? = null

            fun lockDecision(
                dose: Double,
                deliver: Boolean,
                reason: String,
                phase: String,
                confidence: Double,
                mealDetected: Boolean,
                detectedCarbs: Double
            ) {
                if (decisionLocked) return

                lockedDecision = LockedDecision(
                    dose = dose,
                    deliver = deliver,
                    reason = reason,
                    phase = phase,
                    confidence = confidence,
                    mealDetected = mealDetected,
                    detectedCarbs = detectedCarbs
                )

                // schrijf ook naar finals (backwards compatible)
                finalDose = dose
                finalDeliver = deliver
                finalReason = reason
                finalPhase = phase
                finalConfidence = confidence
                finalMealDetected = mealDetected
                finalDetectedCarbs = detectedCarbs

                decisionLocked = true
                decisionSource = phase
            }

// ─────────────────────────────────────────────
// FASE 1 — HARD DECISIONS (LOCK)
// pre-safety & persistent
// ─────────────────────────────────────────────

            val preSafety = runCentralSafetyGate(
                stage = "pre",
                currentData = currentData,
                historicalData = historicalData,
                trends = trends,
                robustTrends = robustTrends,
                effectiveTarget = effectiveTarget,
                effectiveISF = effectiveISF,
                currentIOB = currentIOB,
                maxIOB = maxIOB,
                mealDetected = false,
                detectedCarbs = 0.0,
                carbsOnBoard = cobNow,
                plannedDose = 0.0
            )

            if (!preSafety.allowed) {
                lockDecision(
                    dose = 0.0,
                    deliver = false,
                    reason = preSafety.reason,
                    phase = preSafety.phase,
                    confidence = 1.0,
                    mealDetected = false,
                    detectedCarbs = 0.0
                )
            }


            val persistentAdvice = handlePersistentHighBG(
                currentData = currentData,
                historicalData = historicalData,
                trends = trends,
                robustTrends = robustTrends,
                effectiveISF = effectiveISF,
                effectiveTarget = effectiveTarget,
                currentIOB = currentIOB,
                maxIOB = maxIOB,
                finalConfidence = 0.7,
                cobNow = cobNow
            )

            if (persistentAdvice != null) {
                lockDecision(
                    dose = persistentAdvice.dose,
                    deliver = persistentAdvice.shouldDeliverBolus,
                    reason = persistentAdvice.reason,
                    phase = persistentAdvice.phase,
                    confidence = persistentAdvice.confidence,
                    mealDetected = persistentAdvice.mealDetected,
                    detectedCarbs = persistentAdvice.detectedCarbs
                )
            }

        if (!decisionLocked) {

// ─────────────────────────────────────────────
// FASE 2 — NORMALE BESLISSINGEN
// (alleen uitvoeren als NIET gelockt)
// ─────────────────────────────────────────────

            val mealResult = handleMealAndBolusDecision(
                robustTrends = robustTrends,
                historicalData = historicalData,
                currentData = currentData,
                effectiveISF = effectiveISF,
                effectiveTarget = effectiveTarget,
                carbRatio = carbRatio,
                currentIOB = currentIOB,
                maxIOB = maxIOB,
                maxBolus = maxBolus
            )


                finalDose = mealResult.dose
                finalReservedBolus = mealResult.reservedBolus
                finalDeliver = mealResult.deliver
                finalReason = mealResult.reason
                finalPhase = mealResult.phase
                finalDetectedCarbs = mealResult.detectedCarbs
                finalMealDetected = mealResult.mealDetected
                finalConfidence = mealResult.confidence




           // ★★★ UNIFORME CARB DETECTIE (GEÏSOLEERD IN HELPER) ★★★
            val (mealState, carbsResult) = updateMealStateFromBG(
                historicalData = historicalData,
                robustTrends = robustTrends,
                currentBG = currentData.bg,
                effectiveTarget = effectiveTarget,
                currentIOB = currentIOB,
                maxIOB = maxIOB
            )

            val detectedCarbs = carbsResult.detectedCarbs
            lastMealDetectionDebug = carbsResult.detectionReason



            // ★★★ INITIEER PHASED BOLUS MANAGER BIJ MAALTIJD DETECTIE ★★★
            if (detectedCarbs > 10.0 && carbsResult.confidence > 0.3) {
                val mealStartTime = phasedBolusManager.getMealStartTime()
                if (mealStartTime == null ||
                    Minutes.minutesBetween(mealStartTime, DateTime.now()).minutes > 120) {
                    phasedBolusManager.startMealPhase(detectedCarbs)
                }
            }



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

            // ★★★ DYNAMISCHE MAALTIJD INSTELLINGEN ★★★
            val baseMinMinutes = preferences.get(IntKey.min_minutes_between_bolus)
            val mealTimeLimits = calculateDynamicBolusIntervals(
                detectedCarbs = detectedCarbs,
                baseMinMinutes = baseMinMinutes
            )

// ★★★ PROGRESSIEVE INTERVAL BEREKENING ★★★
            val actualInterval = calculateProgressiveInterval(
                baseInterval = mealTimeLimits.minMinutesBetweenBoluses,
                consecutiveBoluses = phasedBolusManager.getConsecutiveBolusesCount()
            )

            val adjustedLimits = mealTimeLimits.copy(
                minMinutesBetweenBoluses = actualInterval
            )

// ★★★ DYNAMISCHE MAXIMUM TOTALE INSULIN ★★★
            val maxTotalMealInsulin = calculateDynamicMaxTotalMealInsulin(
                detectedCarbs = detectedCarbs,
                effectiveCR = getEffectiveCarbRatio(),
                currentIOB = currentIOB,
                maxBolus = maxBolus,
                maxIOB = maxIOB
            )

// ★★★ CHECK FREQUENTE BOLUS TOESTEMMING ★★★
            val canDeliverFrequentBolus = shouldAllowFrequentBolusForLargeMeal(
                currentIOB = currentIOB,
                mealTimeLimits = adjustedLimits,
                robustTrends = robustTrends,
                currentBG = currentData.bg,
                targetBG = effectiveTarget,
                lastBolusTime = phasedBolusManager.getLastBolusTime(),
                consecutiveBoluses = phasedBolusManager.getConsecutiveBolusesCount()
            )

// ★★★ EXTRA CHECK VOOR ZEER FREQUENTE BOLUSSEN ★★★
            val canDeliverVeryFrequent = shouldAllowVeryFrequentBolus(
                currentSlope = robustTrends.firstDerivative,
                currentBG = currentData.bg,
                targetBG = effectiveTarget,
                consecutiveBoluses = phasedBolusManager.getConsecutiveBolusesCount()
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

            // ★★★ DYNAMISCHE IOB SAFETY CHECK - VOORKOM TE HOGE DOSIS BIJ HOGE IOB ★★★
            val iobRatio = currentIOB / maxIOB
            val shouldLimitForHighIOB = iobRatio > 0.6 && finalDetectedCarbs > 20

            if (shouldLimitForHighIOB) {
                // Bereken max toegestane bolus gebaseerd op IOB
                val iobBasedMaxBolus = when {
                    iobRatio > 0.8 -> maxBolus * 0.4  // 40% van maxBolus bij zeer hoge IOB
                    iobRatio > 0.7 -> maxBolus * 0.6  // 60% bij hoge IOB
                    iobRatio > 0.6 -> maxBolus * 0.8  // 80% bij matige IOB
                    else -> maxBolus
                }

                // Pas de max aan voor verdere berekeningen
                val originalMaxBolus = maxBolus
                maxBolus = min(maxBolus, iobBasedMaxBolus)

                if (maxBolus < originalMaxBolus) {
                    lastReservedBolusDebug += " | IOB-max:${maxBolus.round(2)}U (was ${originalMaxBolus.round(2)}U)"
                }
            }

// ★★★ CHECK TOTALE MAALTIJD INSULIN ★★★
            val totalMealInsulinSoFar = phasedBolusManager.getTotalInsulinDelivered()
            val maxMealInsulin = calculateDynamicMaxTotalMealInsulin(
                detectedCarbs = finalDetectedCarbs,
                effectiveCR = getEffectiveCarbRatio(),
                currentIOB = currentIOB,
                maxBolus = maxBolus,
                maxIOB = maxIOB
            )

// ★★★ WARNING ALS WE BIJNA DE LIMIET BEREIKEN ★★★
            if (totalMealInsulinSoFar > maxMealInsulin * 0.8) {
                lastReservedBolusDebug += " | WARNING: ${(totalMealInsulinSoFar/maxMealInsulin*100).toInt()}% meal limit"
            }


// ★★★ VEILIGE WISKUNDIGE BOLUS LOGICA ★★★
            if (robustTrends.consistency > preferences.get(DoubleKey.phase_min_consistency) &&
                mathBolusAdvice.immediatePercentage > 0 &&
                detectedCarbs > 0
            ) {
                // ★★★ USE SAFE MATHEMATICAL METHOD ★★★
                val (immediateBolus, reservedBolus, bolusReason) = getMathematicalBolusAsOnlyMethod(
                    robustTrends = robustTrends,
                    detectedCarbs = detectedCarbs,
                    currentBG = currentData.bg,
                    targetBG = effectiveTarget,
                    historicalData = historicalData,
                    currentIOB = currentIOB,
                    maxIOB = maxIOB,
                    effectiveCR = getEffectiveCarbRatio(),
                    maxBolus = maxBolus // ★★★ NIEUW: maxBolus parameter toegevoegd ★★★
                )

                // ★★★ APPLY CUMULATIVE SAFETY ★★★
                val cumulativeSafeBolus = trackCumulativeDose(immediateBolus, maxBolus)

                // ★★★ APPLY HIGH BG OVERRIDE SAFETY ★★★
                val finalImmediateBolus = calculateHighBGOverrideDose(
                    cumulativeSafeBolus,
                    maxBolus,
                    currentData.bg,
                    currentIOB,  // ★★★ NIEUW: currentIOB parameter
                    maxIOB       // ★★★ NIEUW: maxIOB parameter
                )
                var finalReservedBolus = reservedBolus

                if ((robustTrends.consistency > 0.7 || detectedCarbs > 20)) {
                    finalDose = finalImmediateBolus
                    finalReservedBolus = finalReservedBolus
                    finalReason = bolusReason
                    finalPhase = robustTrends.phase

                    if (finalReservedBolus > 0.1 && finalDetectedCarbs > 5) {
                        pendingReservedBolus = finalReservedBolus
                        pendingReservedCarbs = finalDetectedCarbs
                        pendingReservedTimestamp = DateTime.now()
                        pendingReservedPhase = robustTrends.phase
                        lastReservedBolusDebug = "SAFE-RESERVED: ${round(finalReservedBolus,1)}U for ${finalDetectedCarbs.toInt()}g carbs"
                    } else {
                        val ResBolus = round(finalReservedBolus,1)
                        lastReservedBolusDebug = "SAFE-NO_RESERVED: carbs=${finalDetectedCarbs.toInt()}, reservedBolus=$ResBolus"
                    }
                    // ★★★ UPDATE MAALTIJD TIJD ★★★
                    updateLastMealBolusTime()

                }
            }

            // ★★★ APPLY IOB-BASED SAFETY ★★★
            val (shouldLimitByIOB, iobSafeDose) = shouldLimitBolusForHighIOB(
                currentIOB = currentIOB,
                maxIOB = maxIOB,
                proposedDose = finalDose,
                currentBG = currentData.bg,
                targetBG = effectiveTarget,
                detectedCarbs = finalDetectedCarbs
            )

            if (shouldLimitByIOB) {
                val originalDose = finalDose
                finalDose = iobSafeDose

                if (iobSafeDose < originalDose * 0.9) { // Meer dan 10% reductie
                    finalReason += " | IOB-LIMITED: ${originalDose.round(2)}U→${iobSafeDose.round(2)}U"
                    lastReservedBolusDebug += " | IOB safety: -${((originalDose-iobSafeDose)/originalDose*100).toInt()}%"
                }
            }

// ★★★ DYNAMISCHE MAALTIJD BOLUS OVERRIDE VOOR BETERE TIMING ★★★
// ★★★ NIEUVE VEILIGHEIDSCHECK: GEEN OVERRIDE BIJ LAGE BG ★★★
            val shouldAllowMealOverride = when {
                !finalMealDetected -> false
                detectedCarbs <= 15 -> false
                robustTrends.firstDerivative <= 0.5 -> false
                // ★★★ CRITIEK: GEEN OVERRIDE BIJ LAGE BG ★★★
                currentData.bg < effectiveTarget -> false  // Onder target: geen override
                currentData.bg < effectiveTarget + 0.5 -> false  // Minder dan 0.5 boven target: geen override
                currentData.bg < effectiveTarget + 1.0 && robustTrends.firstDerivative < 2.0 -> false  // Alleen bij sterke stijging
                else -> true
            }

            if (shouldAllowMealOverride) {
                val dynamicMealBolus = calculateDynamicBolusForMeal(
                    currentBG = currentData.bg,
                    targetBG = effectiveTarget,
                    detectedCarbs = detectedCarbs,
                    currentSlope = robustTrends.firstDerivative,
                    effectiveCR = getEffectiveCarbRatio(),
                    maxBolus = maxBolus,
                    consecutiveBoluses = phasedBolusManager.getConsecutiveBolusesCount()
                )

                // ★★★ APPLY LOW BG REDUCTION EVEN IF OVERRIDE IS ALLOWED ★★★

                val lowBGFactor = when {
                    currentData.bg < effectiveTarget -> 0.1  // Onder target (5.2): 80% reductie
                    currentData.bg < effectiveTarget + 0.3 -> 0.15  // Onder (5.5): 70% reductie
                    currentData.bg < effectiveTarget + 0.5 -> 0.2  // Onder (5.7): 60% reductie
                    currentData.bg < effectiveTarget + 0.7 -> 0.3  // Onder (5.9): 40% reductie
                    currentData.bg < effectiveTarget + 1.0 -> 0.4  // Onder (6.2): 20% reductie
                    else -> 0.8
                }
                val dynamicMealBolusAdjusted = dynamicMealBolus * lowBGFactor

                // ★★★ COMBINEER MET EXISTENDE BOLUS VOOR VEILIGHEID ★★★
                val currentMathBolus = finalDose
                val combinedBolus = max(dynamicMealBolusAdjusted, currentMathBolus)

                // ★★★ PAS ALLEEN AAN ALS DYNAMISCHE BOLUS GROTER IS ★★★
                if (dynamicMealBolusAdjusted > currentMathBolus * 1.2) {  // Minstens 20% groter
                    finalDose = combinedBolus

                    // ★★★ UPDATE REASON MET BEIDE METHODES ★★★
                    val originalDoseStr = round(currentMathBolus, 2)
                    val newDoseStr = round(combinedBolus, 2)
                    finalReason = "DYNAMIC-MEAL OVERRIDE: ${detectedCarbs.toInt()}g " +
                        "slope=${round(robustTrends.firstDerivative, 1)} " +
                        "→ ${newDoseStr}U (was ${originalDoseStr}U) | " +
                        finalReason.split(" | ").firstOrNull() ?: ""

                    // ★★★ LOG VOOR DEBUGGING ★★★
                    lastReservedBolusDebug += " | DynMeal:${round(dynamicMealBolusAdjusted,2)}U > Math:${round(currentMathBolus,2)}U"
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
                lockDecision(
                    dose = 0.0,
                    deliver = false,
                    reason = when (sensorIssue) {
                        SensorIssueType.COMPRESSION_LOW -> "Safety: Compression low detected"
                        SensorIssueType.JUMP_TOO_LARGE -> "Safety: Sensor error (jump too large)"
                        SensorIssueType.OSCILLATION -> "Safety: Sensor error (oscillation)"
                    },
                    phase = "safety_sensor_error",
                    confidence = 1.0,
                    mealDetected = isLikelyMeal,
                    detectedCarbs = detectedCarbs
                )
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
                        effectiveCR = getEffectiveCarbRatio(),
                        maxBolus = maxBolus // ★★★ NIEUW: maxBolus parameter toegevoegd ★★★
                    )

                    val correctionComponent = max(0.0, (currentData.bg - effectiveTarget) / effectiveISF) * 0.3

                    finalDose = immediateBolus + correctionComponent
                    finalReason = "Meal+Correction: ${"%.1f".format(detectedCarbs)}g + BG=${"%.1f".format(currentData.bg)} | $bolusReason"
                    finalMealDetected = true
                    finalDetectedCarbs = detectedCarbs
                    finalPhase = "meal_correction_combination"

                    // ★★★ UPDATE MAALTIJD TIJD ★★★
                    updateLastMealBolusTime()


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
                        effectiveCR = getEffectiveCarbRatio(),
                        maxBolus = maxBolus // ★★★ NIEUW: maxBolus parameter toegevoegd ★★★
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
                            // ★★★ UPDATE MAALTIJD TIJD ★★★
                            updateLastMealBolusTime()

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

// ★★★ FREQUENTE BOLUS CHECK MET PHASED MANAGEMENT ★★★
            if (!canDeliverFrequentBolus && finalDeliver && finalDose > 0) {
                // Alleen blokkeren bij DALENDE trend of zeer frequente bolussen
                if (robustTrends.firstDerivative < 0.5 || !canDeliverVeryFrequent) {
                    finalDose = 0.0
                    finalDeliver = false
                    finalReason += " | Limited by frequent bolus safety"
                } else {
                    // Bij stijging: kleinere reductie i.p.v. volledige blokkering
                    val reductionFactor = when {
                        currentIOB > adjustedLimits.maxIOB * 0.9 -> 0.6
                        currentIOB > adjustedLimits.maxIOB * 0.7 -> 0.8
                        else -> 1.0
                    }
                    val originalDose = finalDose
                    finalDose *= reductionFactor
                    if (reductionFactor < 1.0) {
                        finalReason += " | Reduced ${"%.2f".format(originalDose)}→${"%.2f".format(finalDose)}U"
                    }
                }
            }

            // ★★★ RESERVED BOLUS RELEASE LOGIC ★★★
            decayReservedBolusOverTime()

// ★★★ FREQUENTE RESERVED BOLUS RELEASE ★★★
            if (pendingReservedBolus > 0.1) {
                val releasedBolus = calculateDynamicReservedBolusRelease(
                    currentBG = currentData.bg,
                    targetBG = effectiveTarget,
                    currentIOB = currentIOB,
                    mealTimeLimits = adjustedLimits,
                    phasedBolusManager = phasedBolusManager
                )

                if (releasedBolus > 0.05) {
                    finalDose += releasedBolus
                    finalReason += " | +${"%.2f".format(releasedBolus)}U reserved"
                    finalDeliver = true

                    // ★★★ UPDATE MAALTIJD TIJD ★★★
                    updateLastMealBolusTime()

                }
            }

            // ★★★ IOB VERIFICATION SYSTEM - Voorkom missed bolus ★★★
            if (finalDeliver && finalDose > 0.1) {
                val shouldActuallyDeliver = verifyBolusDelivery(
                    expectedBolus = finalDose,
                    currentIOB = currentIOB,
                    lastBolusTime = lastBolusTime,
                    currentSlope = robustTrends.firstDerivative,
                    lastCalculatedBolus = lastCalculatedBolus
                )

                if (!shouldActuallyDeliver && robustTrends.firstDerivative < 3.0) {
                    // Alleen blokkeren bij niet-extreme stijging
                    finalDeliver = false
                    finalReason += " | IOB VERIFICATION: Possible missed bolus detection"
                } else if (!shouldActuallyDeliver && robustTrends.firstDerivative >= 3.0) {
                    // Bij extreme stijging: verminder dosis maar geef wel af
                    finalDose *= 0.7
                    finalReason += " | IOB VERIFICATION: Reduced due to possible missed bolus"
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

                correctionDose = getSafeDoseNoLearning(
                    calculatedDose = correctionDose,
                    currentIOB = currentIOB,
                    trends = trends,
                    phase = robustPhase,
                    MaxIOB = maxIOB
                )

                if (trends.recentTrend <= 0.0 && currentData.bg < effectiveTarget + 3.0) {
                    correctionDose *= peak_damping_percentage.toDouble()/100.0
                }

                if (isHypoRiskWithin(120, currentData.bg, currentIOB, effectiveISF)) {
                    correctionDose *= hypo_risk_percentage.toDouble()/100.0
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
                finalConfidence = 1.0
            }

            // Early Boost logic

             predictedPeak = basicAdvice.predictedValue ?: (currentData.bg + 2.0)


            if (currentData.bg in 8.0..9.9 && predictedPeak > 10.0) {
                val doseBeforeBoost = finalDose // Bewaar de dosis voor vergelijking
                finalDose = calculateEnhancedEarlyBoost(
                    currentBG = currentData.bg,
                    predictedPeak = predictedPeak,
                    baseDose = finalDose,
                    currentIOB = currentIOB,
                    maxIOB = maxIOB,
                    maxBolus = maxBolus,
                    robustTrends = robustTrends
                )

                if (finalDose > doseBeforeBoost) {
                    finalReason += " | EnhancedEarlyBoost(peak=${"%.1f".format(predictedPeak)})"
                }
            }

            // Voorspelling leegmaken bij dalende trend
            if (trends.recentTrend <= 0.0) {
                predictedPeak = currentData.bg
                finalReason += " | No prediction (falling/stable trend)"
            }

            // ★★★ SCHRIJF COB NAAR SHAREDPREFERENCES ★★★
            try {
                saveCob(finalCOB)
            } catch (e: Exception) {
                // Logging
            }

            // ★★★ VEILIGHEID: Controleer of de totale dosis niet te hoog is ★★★
            if (finalDeliver && finalDose > maxBolus) {
                finalDose = maxBolus
                finalReason += " | Capped at maxBolus ${"%.2f".format(maxBolus)}U"
            }

            // ★★★ RECORD BOLUS DELIVERY EN UPDATE MAALTIJD TIJD ★★★
            if (finalDeliver && finalDose > 0.05) {
                phasedBolusManager.recordBolusDelivery(finalDose, finalReason)

                // ★★★ UPDATE LAATSTE MAALTIJD BOLUS TIJD ★★★
                if (finalMealDetected && finalDetectedCarbs > 10.0) {
                    updateLastMealBolusTime()

                    // ★★★ DEBUG INFO ★★★
                    val debugMsg = "📝 Meal bolus: ${"%.2f".format(finalDose)}U for ${finalDetectedCarbs.toInt()}g"
                    currentStappenLog += "\n$debugMsg"
                    lastReservedBolusDebug += " | Cooldown:${MEAL_BOLUS_COOLDOWN_MINUTES}min"
                }
            }

            // ★★★ Track de afgegeven bolus voor status weergave ★★★
            if (finalDeliver && finalDose > 0.05) {
                lastDeliveredBolus = finalDose
                lastBolusReason = finalReason
                lastBolusTime = DateTime.now()
                // ➕ history bijwerken
                lastBolusHistory.addFirst(DateTime.now() to finalDose)
                if (lastBolusHistory.size > 3) {
                    lastBolusHistory.removeLast()
                }
            }

            // ★★★ BIJWERKEN CARBS TRACKING VOOR STATUS ★★★
            lastDetectedCarbs = finalDetectedCarbs
            lastCarbsOnBoard = finalCOB
            lastCOBUpdateTime = DateTime.now()
            lastCalculatedBolus = finalDose
            lastShouldDeliver = finalDeliver

            // ★★★ BOUW DEBUG LOG VOOR CSV ★★★
            debugLog = StringBuilder().apply {
                append("MealDetect: $lastMealDetectionDebug")
                append(" | COB: $lastCOBDebug")
                append(" | Reserved: $lastReservedBolusDebug")
                if (detectedCarbs > 0) {
                    append(" | MathCarbs: ${detectedCarbs.toInt()}g")
                }
            }.toString()



            // **********************************************************************************88888
// ★★★ HYBRIDE BASAAL BEREKENING - VERBETERDE CONTINUÏTEIT ★★★
            finalBolusAmount = finalDose
            finalBasalRate = 0.0
            hybridPercentage = preferences.get(IntKey.hybrid_basal_perc)

            val now = DateTime.now()

// ★★★ EXPLICIETE RESET LOGICA ★★★
            if (hybridBasalActiveUntil != null && now.isAfter(hybridBasalActiveUntil)) {
                hybridBasalActiveUntil = null
                initialHybridBasalAmount = 0.0
                remainingHybridBasalAmount = 0.0
            }

// ★★★ HYBRIDE MODUS ACTIEF ★★★
            if (hybridBasalActiveUntil != null && now.isBefore(hybridBasalActiveUntil)) {
                val minutesRemaining = Minutes.minutesBetween(now, hybridBasalActiveUntil).minutes

                // ★★★ CRITICAL: ALWAYS CONTINUE BASAL UNLESS SAFETY STOP ★★★
                if (minutesRemaining > 0) {
                    finalBasalRate = initialHybridBasalAmount * 6.0
                    finalBolusAmount = 0.0

                    // ★★★ ONLY STOP BASAL FOR CRITICAL SAFETY ISSUES ★★★
                    val criticalStop = shouldStopInsulinDelivery(
                        robustTrends = robustTrends,
                        historicalData = historicalData,
                        phasedBolusManager = phasedBolusManager,
                        currentBG = currentData.bg,
                        currentIOB = currentIOB,
                        effectiveISF = effectiveISF,
                        mealDetected = finalMealDetected,
                        detectedCarbs = finalDetectedCarbs,
                        maxIOB = maxIOB,
                        carbsOnBoard = finalCOB
                    )

                    if (!criticalStop) {
                        finalDeliver = true
                        // Bereken hoeveel basaal er al is afgegeven
                        val elapsedMinutes = 10 - minutesRemaining
                        val progress = elapsedMinutes / 10.0
                        val basalAlreadyDelivered = initialHybridBasalAmount * progress
                        finalReason += " | Hybrid basal continuing: ${round(basalAlreadyDelivered, 2)}U/${round(initialHybridBasalAmount, 2)}U delivered (${minutesRemaining}min left)"

                    } else {
                        // veiligheid mag hybrid state stoppen, maar beslissing niet overschrijven als locked
                        hybridBasalActiveUntil = null
                        finalBasalRate = 0.0
                        finalDeliver = false
                        finalReason += " | CRITICAL: Hybrid basal stopped for safety"

                    }

                }

            } else if (hybridPercentage > 0 && finalMealDetected && finalDose > 0.3) {
                // ★★★ SAFE HYBRIDE MODUS START ★★★
                val totalInsulin = finalDose

                // ★★★ USE SAFE HYBRID CALCULATION ★★★
                val (safeBolusAmount, safeBasalAmount, safeTotal) = calculateSafeHybridAmount(
                    totalInsulin = totalInsulin,
                    hybridPercentage = hybridPercentage,
                    maxBolus = maxBolus,
                    currentIOB = currentIOB,
                    maxIOB = maxIOB,
                    currentBG = currentData.bg,
                    currentSlope = robustTrends.firstDerivative,
                    isNachtTime = isNachtTime()
                )

                finalBolusAmount = safeBolusAmount
                finalBasalRate = safeBasalAmount * 6.0 // U/uur - geeft basalAmount in 10 minuten

                // ★★★ FORCEER SHOULD_DELIVER = TRUE BIJ HYBRIDE MODUS ★★★
                finalDeliver = true

                // ★★★ INTERNE TIMER: 10 MINUTEN EN ONTHOUD INITIËLE BASAAL ★★★
                hybridBasalActiveUntil = now.plusMinutes(10)
                initialHybridBasalAmount = safeBasalAmount // Bewaar voor volgende cycles
                remainingHybridBasalAmount = safeBasalAmount

                finalReason += " | SAFE-Hybrid: ${hybridPercentage}% basaal (${round(safeBasalAmount, 2)}U in 10min @ ${round(finalBasalRate, 1)}U/h, bolus: ${round(safeBolusAmount, 2)}U)"
            } else {
                // ★★★ GEEN HYBRIDE MODUS - ZORG VOOR COMPLETE RESET ★★★
                hybridBasalActiveUntil = null
                initialHybridBasalAmount = 0.0
                remainingHybridBasalAmount = 0.0
            }

            // ★★★ ZORG DAT bolusAmount ALTIJD GEDEFINIEERD IS ★★★
            val finalBolusAmountForAdvice = finalBolusAmount


            // **********************************************************************************88888


            // ★★★ MAXIMUM TOTALE INSULIN CHECK - MET HOGE BG OVERRIDE ★★★
            val totalInsulinSoFar = phasedBolusManager.getTotalInsulinDelivered()
            if (totalInsulinSoFar >= maxTotalMealInsulin) {
                // ★★★ OVERRIDE: TOESTAAN BIJ ZEER HOGE BG ★★★
                val shouldOverride = shouldAllowInsulinForHighBG(
                    currentBG = currentData.bg,
                    targetBG = effectiveTarget,
                    currentIOB = currentIOB,
                    maxIOB = maxIOB,
                    robustTrends = robustTrends,
                    detectedCarbs = detectedCarbs
                )

                if (!shouldOverride) {
                    finalDose = 0.0
                    finalDeliver = false
                    finalReason = "Safety: Maximum meal insulin reached (${"%.1f".format(maxTotalMealInsulin)}U)"
                } else {
                    // Toestaan met verminderde dosis
                    finalDose *= 0.7
                    finalReason += " | High BG override (reduced to ${"%.2f".format(finalDose)}U)"
                }
            }




        }
// ─────────────────────────────────────────────
// FASE 4 — LOCK OVERDRACHT (ABSOLUUT)
// ─────────────────────────────────────────────


            lockedDecision?.let {
                finalDose = it.dose
                finalDeliver = it.deliver
                finalReason = it.reason
                finalPhase = it.phase
                finalConfidence = it.confidence
                finalMealDetected = it.mealDetected
                finalDetectedCarbs = it.detectedCarbs
            }

            if (!decisionLocked) {
                val postSafety = runCentralSafetyGate(
                    stage = "post",
                    currentData = currentData,
                    historicalData = historicalData,
                    trends = trends,
                    robustTrends = robustTrends,
                    effectiveTarget = effectiveTarget,
                    effectiveISF = effectiveISF,
                    currentIOB = currentIOB,
                    maxIOB = maxIOB,
                    mealDetected = finalMealDetected,
                    detectedCarbs = finalDetectedCarbs,
                    carbsOnBoard = finalCOB,
                    plannedDose = finalDose
                )

                val relaxSafety = shouldRelaxSafetyForPhase(
                    robustTrends = robustTrends,
                    transitionFactor = lastPhaseTransitionFactor
                )

                if (!postSafety.allowed && relaxSafety) {
                    // Safety wordt NIET opgeheven, alleen verzacht
                    if (finalDose > 0.0) {
                        finalDose *= 0.5   // 50% reductie i.p.v. volledige blokkade
                        finalDeliver = true
                        finalReason += " | Rising-phase safety relaxed (50%)"
                        finalPhase = "rising_safety_relaxed"
                    }
                }
            }

            if (isNachtTime() && preferences.get(BooleanKey.BlockSMBnacht)) {
                finalDose = 0.0
                finalBolusAmount = 0.0
                finalBasalRate = 0.0
                finalDeliver = false
            }


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
                debugLog = debugLog,
                basalRate = finalBasalRate,
                bolusAmount = finalBolusAmount,
                hybridPercentage = hybridPercentage
            )

            metricsHelper.updateLastAdvice(enhancedAdvice)

// Bouw nu de FCLContext met de *definitieve* waarden
            val fclContext = FCLContext(
                currentBG = currentData.bg,
                currentIOB = currentIOB,
                mealDetected = finalMealDetected,
                detectedCarbs = finalDetectedCarbs,
                carbsOnBoard = finalCOB,
                lastBolusAmount = finalBolusAmount,
                currentPhase = finalPhase,
                finalBasalRate = finalBasalRate,
                finalBolusAmount = finalBolusAmount,
                finalShouldDeliverBolus = finalDeliver
            )

            try {
                metricsHelper.onFiveMinuteTick(
                    currentBG = currentData.bg,
                    currentIOB = currentIOB,
                    context = fclContext
                )
            } catch (e: Exception) {
                // Silent fail
            }

            logFinalAdvice(
                advice = enhancedAdvice,
                currentData = currentData,
                currentISF = effectiveISF,
                currentIOB = currentIOB,
                target = effectiveTarget
            )

            return enhancedAdvice

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

// ==============================
// STAP 2 – PERSISTENT HIGH BG
// ==============================
    private fun handlePersistentHighBG(
        currentData: BGDataPoint,
        historicalData: List<BGDataPoint>,
        trends: TrendAnalysis,
        robustTrends: RobustTrendAnalysis,
        effectiveISF: Double,
        effectiveTarget: Double,
        currentIOB: Double,
        maxIOB: Double,
        finalConfidence: Double,
        cobNow: Double
    ): EnhancedInsulinAdvice? {

        val shouldCheckPersistent = shouldAllowPersistentBolusAfterMeal() &&
            hybridBasalActiveUntil == null

        var hasPersistentBolus = false
        var persistentBolusAmount = 0.0

        if (shouldCheckPersistent) {
            val persistentResult = persistentHelper.checkPersistentHighBG(
                historicalData,
                currentIOB,
                maxIOB,
                ::isNachtTime,
                effectiveISF = effectiveISF,
                currentTrend = trends.recentTrend,
                robustTrends = lastRobustTrends
            )

            hasPersistentBolus =
                persistentResult.shouldDeliver && persistentResult.extraBolus > 0.05
            persistentBolusAmount =
                if (hasPersistentBolus) persistentResult.extraBolus else 0.0

        } else {
            val minutesSinceLastMeal = lastMealBolusTime?.let {
                Minutes.minutesBetween(it, DateTime.now()).minutes
            } ?: 0

            val debugMessage = if (lastMealBolusTime != null) {
                "Persistent blocked: $minutesSinceLastMeal/$MEAL_BOLUS_COOLDOWN_MINUTES min cooldown"
            } else {
                "Persistent blocked: Hybrid basal active"
            }

            currentStappenLog += "\n$debugMessage"
        }

        if (!hasPersistentBolus) {
            return null
        }

        val minutesSinceLastMeal = lastMealBolusTime?.let {
            Minutes.minutesBetween(it, DateTime.now()).minutes
        }

        val persistentReason = if (minutesSinceLastMeal != null) {
            "Persistent High BG (${minutesSinceLastMeal} min sinds maaltijd)"
        } else {
            "Persistent High BG"
        }

        var shouldDeliver = true
        var dose = persistentBolusAmount

        if (isNachtTime() && preferences.get(BooleanKey.BlockSMBnacht)) {
            dose = 0.0
            shouldDeliver = false
        }

        val advice = EnhancedInsulinAdvice(
            dose = dose,
            reason = persistentReason,
            confidence = finalConfidence,
            predictedValue = currentData.bg,
            mealDetected = false,
            detectedCarbs = 0.0,
            shouldDeliverBolus = shouldDeliver,
            phase = "persistent_correction",
            reservedDose = 0.0,
            carbsOnBoard = cobNow,
            Target_adjust = currentStappenTargetAdjust,
            ISF_adjust = currentStappenPercentage,
            activityLog = currentStappenLog,
            resistentieLog = resistanceHelper.getCurrentResistanceLog(),
            effectiveISF = effectiveISF,
            MathBolusAdvice = lastMathBolusAdvice,
            mathPhase = robustTrends.phase,
            mathSlope = robustTrends.firstDerivative,
            mathAcceleration = robustTrends.secondDerivative,
            mathConsistency = robustTrends.consistency,
            mathDirectionConsistency =
                if (recentDataForAnalysis.isNotEmpty())
                    calculateDirectionConsistencyFromData(recentDataForAnalysis)
                else 0.0,
            mathMagnitudeConsistency =
                if (recentDataForAnalysis.isNotEmpty())
                    calculateMagnitudeConsistencyFromData(recentDataForAnalysis)
                else 0.0,
            mathPatternConsistency =
                if (recentDataForAnalysis.isNotEmpty())
                    calculatePatternConsistency(recentDataForAnalysis)
                else 0.0,
            mathDataPoints = recentDataForAnalysis.size,
            debugLog = "Persistent",
            bolusAmount = dose,
            basalRate = 1.0
        )

        metricsHelper.updateLastAdvice(advice)

        val context = FCLContext(
            currentBG = currentData.bg,
            currentIOB = currentIOB,
            mealDetected = false,
            detectedCarbs = 0.0,
            carbsOnBoard = cobNow,
            lastBolusAmount = dose,
            currentPhase = "persistent_correction",
            finalBasalRate = 1.0,
            finalBolusAmount = dose,
            finalShouldDeliverBolus = shouldDeliver
        )

        try {
            metricsHelper.onFiveMinuteTick(
                currentBG = currentData.bg,
                currentIOB = currentIOB,
                context = context
            )
        } catch (_: Exception) {
            // silent fail
        }

        return advice
    }

    private fun handleSafetyStop(
        robustTrends: RobustTrendAnalysis,
        historicalData: List<BGDataPoint>,
        phasedBolusManager: PhasedBolusManager,
        currentData: BGDataPoint,
        effectiveISF: Double,
        finalMealDetected: Boolean,
        finalDetectedCarbs: Double,
        finalCOB: Double,
        maxIOB: Double,
        hypoRiskPercentage: Int
    ): Pair<Boolean, String> {


        val currentSlope = robustTrends.firstDerivative
        val iobRatio = currentData.iob / maxIOB

        val predictedBGLow = predictHypoRiskWithMealContext(
            currentBG = currentData.bg,
            currentIOB = currentData.iob,
            effectiveISF = effectiveISF,
            carbsOnBoard = finalCOB,
            detectedCarbs = finalDetectedCarbs,
            currentSlope = currentSlope,
            minutesAhead = 90
        )

        val safetyThreshold = 4.5 + (1.0 - (hypoRiskPercentage / 100.0))

        val stopReason = when {
            predictedBGLow < safetyThreshold ->
                "hypo risk (predicted ${"%.1f".format(predictedBGLow)} mmol/L)"
            currentSlope < -2.0 ->
                "strong decline (${String.format("%.1f", currentSlope)} mmol/L/h)"
            currentSlope < -1.0 ->
                "moderate decline (${String.format("%.1f", currentSlope)} mmol/L/h)"
            iobRatio > 0.7 ->
                "high IOB (${String.format("%.1f", currentData.iob)}/${maxIOB}U)"
            currentData.bg < getEffectiveTarget() + 1.0 ->
                "BG close to target (${String.format("%.1f", currentData.bg)})"
            phasedBolusManager.getConsecutiveBolusesCount() >= 4 ->
                "max consecutive boluses reached"
            else ->
                "general safety limits"
        }

        return true to "Safety stop: $stopReason"
    }

    private fun handleMealAndBolusDecision(
        robustTrends: RobustTrendAnalysis,
        historicalData: List<BGDataPoint>,
        currentData: BGDataPoint,
        effectiveISF: Double,
        effectiveTarget: Double,
        carbRatio: Double,
        currentIOB: Double,
        maxIOB: Double,
        maxBolus: Double
    ): MealBolusResult {

        // === DEFAULT RESULT (do nothing) ===
        var finalDose = 0.0
        var finalReservedBolus = 0.0
        var finalDeliver = false
        var finalReason = "No meal action"
        var finalPhase = "stable"
        var finalDetectedCarbs = 0.0
        var finalMealDetected = false
        var finalConfidence = 0.7

        // === EXISTING MEAL DETECTION ===
        val (mealState, carbsResult) = updateMealStateFromBG(
            historicalData = historicalData,
            robustTrends = robustTrends,
            currentBG = currentData.bg,
            effectiveTarget = effectiveTarget,
            currentIOB = currentIOB,
            maxIOB = maxIOB
        )

        val detectedCarbs = carbsResult.detectedCarbs

        if (detectedCarbs > 5.0 && carbsResult.confidence > 0.3) {
            finalDetectedCarbs = detectedCarbs
            finalMealDetected = true
        }

        // === MATHEMATICAL BOLUS (UNCHANGED) ===
        if (finalMealDetected) {
            val mathBolusAdvice = getMathematicalBolusAdvice(
                robustTrends = robustTrends,
                detectedCarbs = detectedCarbs,
                currentBG = currentData.bg,
                targetBG = effectiveTarget,
                historicalData = historicalData,
                currentIOB = currentIOB,
                maxIOB = maxIOB
            )

            if (mathBolusAdvice.immediatePercentage > 0 && detectedCarbs > 0) {
                val (immediateBolus, reservedBolus, reason) =
                    getMathematicalBolusAsOnlyMethod(
                        robustTrends = robustTrends,
                        detectedCarbs = detectedCarbs,
                        currentBG = currentData.bg,
                        targetBG = effectiveTarget,
                        historicalData = historicalData,
                        currentIOB = currentIOB,
                        maxIOB = maxIOB,
                        effectiveCR = carbRatio,
                        maxBolus = maxBolus
                    )

                finalDose = immediateBolus
                finalReservedBolus = reservedBolus
                finalDeliver = immediateBolus > 0.05
                finalReason = reason
                finalPhase = robustTrends.phase
            }
        }

        return MealBolusResult(
            dose = finalDose,
            reservedBolus = finalReservedBolus,
            deliver = finalDeliver,
            reason = finalReason,
            phase = finalPhase,
            detectedCarbs = finalDetectedCarbs,
            mealDetected = finalMealDetected,
            confidence = finalConfidence
        )
    }






    private fun getPhaseSpecificAggressiveness(phase: String): Double {
        val overallAggressiveness = getCurrentBolusAggressiveness() / 100.0
        return when (phase) {
            "rising" -> (preferences.get(IntKey.bolus_perc_rising).toDouble() / 100.0) * overallAggressiveness
            "plateau" -> (preferences.get(IntKey.bolus_perc_plateau).toDouble() / 100.0) * overallAggressiveness
            "declining" -> 0.0
            else -> overallAggressiveness
        }
    }

    private fun getSafeDoseNoLearning(
        calculatedDose: Double,
        currentIOB: Double,
        trends: TrendAnalysis,
        phase: String = "stable",
        MaxIOB: Double
    ): Double {
        val base = calculatedDose * 0.7  // hetzelfde "conservatieve" effect dat je al had bij lage confidence

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

    private fun shouldRelaxSafetyForPhase(
        robustTrends: RobustTrendAnalysis,
        transitionFactor: Double
    ): Boolean {
        return robustTrends.phase == "rising" &&
            robustTrends.consistency > 0.6 &&
            transitionFactor >= 0.9
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




    // ★★★ HYBRIDE STATUS VOOR UI ★★★
    fun getHybridStatusString(): String {
        val hybridPercentage = preferences.get(IntKey.hybrid_basal_perc)
        val status = if (hybridPercentage > 0) "AAN" else "UIT"

        return buildString {
            append("[🔄 HYBRIDE AFGIFTE MODUS]\n")
            append("• Basaal percentage: $hybridPercentage%\n")
            append("• Status: $status\n")
            if (hybridBasalActiveUntil != null) {
                append("• Actief: ${round(remainingHybridBasalAmount, 2)}U resterend\n")
                append("• Loopt tot: ${hybridBasalActiveUntil?.toString("HH:mm") ?: "Onbekend"}")
            }
        }
    }

    // ★★★ MAALTIJD-GERICHTE STATUS WEERGAVE ★★★
    fun getFCLStatus(): String {
        return FCLStatusFormatter().buildFCLStatus(this)
    }
// ─────────────────────────────────────────────
// UI READ-ONLY ACCESSORS
// ─────────────────────────────────────────────

    // voorkeuren (alleen lezen)
    fun uiPreferences() = preferences

    // dag / nacht
    fun uiIsNight(): Boolean = isNachtTime()

    // laatste bolusbeslissing
    fun uiLastAnalysisTime(): DateTime? = lastMathAnalysisTime
    fun uiLastBolusAdvice(): String = lastMathBolusAdvice
    fun uiLastCalculatedBolus(): Double = lastCalculatedBolus
    fun uiLastShouldDeliver(): Boolean = lastShouldDeliver
    fun uiLastBoluses(): List<Pair<DateTime, Double>> = lastBolusHistory.toList()

// hybride status bestaat al
// → getHybridStatusString()

    // gekoppelde modules (read-only)
    fun uiActivity(): FCLActivity? = activityHelper
    fun uiPersistent(): FCLPersistent? = persistentHelper
    fun uiResistance(): FCLResistance? = resistanceHelper
    fun uiMetrics(): FCLMetrics? = metricsHelper
// ─────────────────────────────────────────────
// UI HELPERS — HYBRIDE BASAAL
// ─────────────────────────────────────────────

    fun uiIsBasalActive(): Boolean {
        val now = DateTime.now()
        return hybridBasalActiveUntil != null && now.isBefore(hybridBasalActiveUntil)
    }
    fun uiBasalRemainingMinutes(): Int {
        val until = hybridBasalActiveUntil ?: return 0
        val now = DateTime.now()
        return if (now.isBefore(until)) {
            Minutes.minutesBetween(now, until).minutes.coerceAtLeast(0)
        } else {
            0
        }
    }
    fun uiCurrentBasalRate(): Double {
        return if (uiIsBasalActive()) {
            finalBasalRate   // dit is jouw definitieve basaal uit de centrale return
        } else {
            0.0
        }
    }





    // Helper functie voor rounding

    private fun round(value: Double, digits: Int): Double {
        val scale = Math.pow(10.0, digits.toDouble())
        return Math.round(value * scale) / scale
    }

    // ★★★ IOB VERIFICATION - Controleer of vorige bolus daadwerkelijk is afgegeven ★★★
    private fun verifyBolusDelivery(
        expectedBolus: Double,
        currentIOB: Double,
        lastBolusTime: DateTime?,
        currentSlope: Double,
        lastCalculatedBolus: Double
    ): Boolean {
        val minutesSinceLastBolus = lastBolusTime?.let {
            Minutes.minutesBetween(it, DateTime.now()).minutes
        } ?: 30

        // Als er recent een bolus berekend werd maar IOB niet stijgt
        if (minutesSinceLastBolus < 15 && lastCalculatedBolus > 0.5 &&
            currentIOB < lastCalculatedBolus * 0.5) {

            // ★★★ BIJ STERKE STIJGING: TOCH AFGEVEN ONDANKS VERMOEDELIJKE MISSED BOLUS ★★★
            return currentSlope > 2.0
        }

        return true
    }

// ==============================
// FCL – INTERNE CONTEXT (STAP 1)
// ==============================

    private data class FCLContextInternal(
        val currentBG: Double,
        val currentIOB: Double,
        val effectiveISF: Double,
        val effectiveTarget: Double,

        val trends: TrendAnalysis,
        val robustTrends: RobustTrendAnalysis,

        var carbsOnBoard: Double,

        // Flags & status
        var mealDetected: Boolean = false,
        var detectedCarbs: Double = 0.0,
        var phase: String = "stable",

        // Output placeholders (nog NIET gebruikt)
        var finalDose: Double = 0.0,
        var finalBasalRate: Double = 0.0,
        var shouldDeliver: Boolean = false
    )
// ==============================
// FCL – RESULT TYPES (STAP 1)
// ==============================

    private data class MealDetectionResult(
        val state: MealDetectionState,
        val detectedCarbs: Double,
        val confidence: Double
    )

    private data class BolusComputationResult(
        val dose: Double,
        val reservedDose: Double,
        val reason: String,
        val phase: String
    )

    private data class HybridResult(
        val bolusAmount: Double,
        val basalRate: Double,
        val hybridActive: Boolean
    )



    // Enum definitions
    enum class MealDetectionState { NONE, EARLY_RISE, RISING, PEAK, DECLINING, DETECTED }
    enum class MealConfidenceLevel { SUSPECTED, CONFIRMED, HIGH_CONFIDENCE }
    enum class SensorIssueType { JUMP_TOO_LARGE, OSCILLATION, COMPRESSION_LOW }
}