package app.aaps.plugins.aps.openAPSFCL

import android.annotation.SuppressLint
import android.content.Context
import android.os.Environment
import app.aaps.core.data.time.T
import com.google.gson.Gson
import org.joda.time.DateTime
import org.joda.time.Hours
import org.joda.time.Minutes
import org.joda.time.Days
import kotlin.math.*
import com.google.gson.reflect.TypeToken
import java.io.File
import java.util.Date
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.utils.fabric.FabricPrivacy
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.StringKey
import javax.inject.Inject
import app.aaps.core.keys.Preferences
import kotlin.math.max
import kotlin.math.min
import app.aaps.plugins.aps.openAPSFCL.FCLActivity
import app.aaps.plugins.aps.openAPSFCL.FCLLogging
import app.aaps.plugins.aps.openAPSFCL.FCLParameters
import app.aaps.plugins.aps.openAPSFCL.FCLMetrics


class FCL@Inject constructor(
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

    // Storage interface voor learning data
    interface FCLStorage {

        fun saveLearningProfile(profile: FCLLearningProfile)
        fun loadLearningProfile(): FCLLearningProfile?
        fun saveMealResponseData(mealData: MealResponseData)
        fun loadHistoricalMealData(): List<MealResponseData>
        fun savePendingLearningUpdate(update: LearningUpdate)
        fun loadPendingLearningUpdates(): List<LearningUpdate>
        fun clearPendingLearningUpdates()
        fun savePeakDetectionData(peakData: PeakDetectionData)
        fun loadPeakDetectionData(): List<PeakDetectionData>
        fun savePendingCorrectionUpdate(update: CorrectionUpdate)
        fun loadPendingCorrectionUpdates(): List<CorrectionUpdate>
        fun clearPendingCorrectionUpdates()
        fun saveMealPerformanceResult(result: MealPerformanceResult)
        fun loadMealPerformanceResults(): List<MealPerformanceResult>
        fun saveCorrectionPerformanceResult(result: CorrectionPerformanceResult)
        fun loadCorrectionPerformanceResults(): List<CorrectionPerformanceResult>
        fun resetAllLearningData()
        fun saveCurrentCOB(cob: Double)

    }

    // Android implementatie met geïsoleerde storage

    class AndroidFCLStorage(private val context: Context ) : FCLStorage {
        private val prefs = context.getSharedPreferences("FCL_Learning_Data", Context.MODE_PRIVATE)
        private val gson = Gson()
        private val externalDir = File(Environment.getExternalStorageDirectory().absolutePath + "/Documents/AAPS/")
        private val backupFile = File(externalDir, "ANALYSE/fcl_learning_backup.json")
        private val backupMeta = File(externalDir, "ANALYSE/fcl_learning_backup.meta")
        private val backupIntervalMs = 4 * 60 * 60 * 1000L

        // serializable DTOs (timestamp in millis)
        private data class SProfile(
            val personalCarbRatio: Double,
            val personalISF: Double,
            val mealTimingFactors: Map<String, Double>,
            val lastUpdatedMillis: Long,
            val learningConfidence: Double,
            val totalLearningSamples: Int,
        )

        private data class SMealResponse(
            val timestamp: Long,
            val carbs: Double,
            val insulinGiven: Double,
            val predictedPeak: Double,
            val actualPeak: Double,
            val timeToPeak: Int,
            val bgStart: Double,
            val bgEnd: Double
        )

        private data class SPendingUpdate(
            val timestamp: Long,
            val detectedCarbs: Double,
            val givenDose: Double,
            val startBG: Double,
            val expectedPeak: Double,
            val mealType: String
        )

        private data class SPeakData(
            val timestamp: Long,
            val bg: Double,
            val trend: Double,
            val acceleration: Double,
            val isPeak: Boolean
        )

        // PERFORMANCE TRACKING STORAGE
        private data class SMealPerformance(
            val timestamp: Long,
            val detectedCarbs: Double,
            val givenDose: Double,
            val startBG: Double,
            val predictedPeak: Double,
            val actualPeak: Double,
            val timeToPeak: Int,
            val bolusPercEarly: Double,
            val bolusPercDay: Double,
            val bolusPercNight: Double,
            val peakDampingFactor: Double,
            val hypoRiskFactor: Double,
            val outcome: String,
            val peakConfidence: Double, // ★★★ NIEUW
            val mealType: String = "unknown" // ★★★ OPTIONEEL
        )


        private data class SCorrectionPerformance(
            val timestamp: Long,
            val givenDose: Double,
            val startBG: Double,
            val predictedDrop: Double,
            val actualDrop: Double,
            val outcome: String
        )


        override fun saveMealPerformanceResult(result: MealPerformanceResult) {
            val current = loadMealPerformanceResultsSerializable().toMutableList()
            current.add(
                SMealPerformance(
                    timestamp = result.timestamp.millis,
                    detectedCarbs = result.detectedCarbs,
                    givenDose = result.givenDose,
                    startBG = result.startBG,
                    predictedPeak = result.predictedPeak,
                    actualPeak = result.actualPeak,
                    timeToPeak = result.timeToPeak,
                    bolusPercEarly = result.parameters.bolusPercEarly,
                    bolusPercDay = result.parameters.bolusPercDay,
                    bolusPercNight = result.parameters.bolusPercNight,
                    peakDampingFactor = result.parameters.peakDampingFactor,
                    hypoRiskFactor = result.parameters.hypoRiskFactor,
                    outcome = result.outcome,
                    peakConfidence = result.peakConfidence, // ★★★ NIEUW
                    mealType = result.mealType // ★★★ OPTIONEEL
                )
            )
            val limited = current.takeLast(100)
            prefs.edit().putString("meal_performance_data", gson.toJson(limited)).apply()
        }


        private fun loadMealPerformanceResultsSerializable(): List<SMealPerformance> {
            val json = prefs.getString("meal_performance_data", null) ?: return emptyList()
            return try {
                gson.fromJson(json, Array<SMealPerformance>::class.java)?.toList() ?: emptyList()
            } catch (e: Exception) {
                emptyList()
            }
        }

        override fun loadMealPerformanceResults(): List<MealPerformanceResult> {
            val serial = loadMealPerformanceResultsSerializable()
            return serial.map {
                MealPerformanceResult(
                    timestamp = DateTime(it.timestamp),
                    detectedCarbs = it.detectedCarbs,
                    givenDose = it.givenDose,
                    startBG = it.startBG,
                    predictedPeak = it.predictedPeak,
                    actualPeak = it.actualPeak,
                    timeToPeak = it.timeToPeak,
                    parameters = MealParameters(
                        bolusPercEarly = it.bolusPercEarly,
                        bolusPercDay = it.bolusPercDay,
                        bolusPercNight = it.bolusPercNight,
                        peakDampingFactor = it.peakDampingFactor,
                        hypoRiskFactor = it.hypoRiskFactor,
                        timestamp = DateTime(it.timestamp)
                    ),
                    outcome = it.outcome,
                    peakConfidence = it.peakConfidence, // ★★★ NIEUW
                    mealType = it.mealType // ★★★ OPTIONEEL
                )
            }
        }

        override fun saveCorrectionPerformanceResult(result: CorrectionPerformanceResult) {
            val current = loadCorrectionPerformanceResultsSerializable().toMutableList()
            current.add(
                SCorrectionPerformance(
                    timestamp = result.timestamp.millis,
                    givenDose = result.givenDose,
                    startBG = result.startBG,
                    predictedDrop = result.predictedDrop,
                    actualDrop = result.actualDrop,
                    outcome = result.outcome
                )
            )
            val limited = current.takeLast(100)
            prefs.edit().putString("correction_performance_data", gson.toJson(limited)).apply()
        }

        override fun saveCurrentCOB(cob: Double) {
            try {
                prefs.edit().putFloat("current_cob", cob.toFloat()).apply()

            } catch (e: Exception) {

            }
        }

        private fun loadCorrectionPerformanceResultsSerializable(): List<SCorrectionPerformance> {
            val json = prefs.getString("correction_performance_data", null) ?: return emptyList()
            return try {
                gson.fromJson(json, Array<SCorrectionPerformance>::class.java)?.toList() ?: emptyList()
            } catch (e: Exception) {
                emptyList()
            }
        }

        override fun loadCorrectionPerformanceResults(): List<CorrectionPerformanceResult> {
            val serial = loadCorrectionPerformanceResultsSerializable()
            return serial.map {
                CorrectionPerformanceResult(
                    timestamp = DateTime(it.timestamp),
                    givenDose = it.givenDose,
                    startBG = it.startBG,
                    predictedDrop = it.predictedDrop,
                    actualDrop = it.actualDrop,
                    outcome = it.outcome
                )
            }
        }

        override fun saveLearningProfile(profile: FCLLearningProfile) {
            try {
                val s = SProfile(
                    personalCarbRatio = profile.personalCarbRatio,
                    personalISF = profile.personalISF,
                    mealTimingFactors = profile.mealTimingFactors,
                    lastUpdatedMillis = profile.lastUpdated.millis,
                    learningConfidence = profile.learningConfidence,
                    totalLearningSamples = profile.totalLearningSamples
                )
                val json = gson.toJson(s)
                prefs.edit().putString("learning_profile", json).apply()

                // Backup: alleen opslaan als laatste backup ouder is dan interval
                val now = System.currentTimeMillis()
                val lastBackup = if (backupMeta.exists()) backupMeta.readText().toLongOrNull() ?: 0 else 0
                if (now - lastBackup > backupIntervalMs) {
                    backupFile.writeText(json)
                    backupMeta.writeText(now.toString())

                }
            } catch (e: Exception) {

            }
        }

        override fun loadLearningProfile(): FCLLearningProfile? {
            return try {
                val json = prefs.getString("learning_profile", null)
                    ?: if (backupFile.exists()) {

                        backupFile.readText()
                    } else return null

                val s = gson.fromJson(json, SProfile::class.java)
                FCLLearningProfile(
                    personalCarbRatio = s.personalCarbRatio,
                    personalISF = s.personalISF,
                    mealTimingFactors = s.mealTimingFactors,
                    lastUpdated = org.joda.time.DateTime(s.lastUpdatedMillis),
                    learningConfidence = s.learningConfidence,
                    totalLearningSamples = s.totalLearningSamples,
                )
            } catch (e: Exception) {

                null
            }
        }

        // ----- Meal response persistence -----
        private fun loadHistoricalMealDataSerializable(): List<SMealResponse> {
            val json = prefs.getString("meal_response_data", null) ?: return emptyList()
            return try {
                gson.fromJson(json, Array<SMealResponse>::class.java)?.toList() ?: emptyList()
            } catch (e: Exception) {
                emptyList()
            }
        }

        override fun saveMealResponseData(mealData: MealResponseData) {
            val current = loadHistoricalMealDataSerializable().toMutableList()
            current.add(
                SMealResponse(
                    timestamp = mealData.timestamp.millis,
                    carbs = mealData.carbs,
                    insulinGiven = mealData.insulinGiven,
                    predictedPeak = mealData.predictedPeak,
                    actualPeak = mealData.actualPeak,
                    timeToPeak = mealData.timeToPeak,
                    bgStart = mealData.bgStart,
                    bgEnd = mealData.bgEnd
                )
            )
            val limited = current.takeLast(1000)
            prefs.edit().putString("meal_response_data", gson.toJson(limited)).apply()
        }

        override fun loadHistoricalMealData(): List<MealResponseData> {
            val serial = loadHistoricalMealDataSerializable()
            return serial.map {
                MealResponseData(
                    timestamp = DateTime(it.timestamp),
                    carbs = it.carbs,
                    insulinGiven = it.insulinGiven,
                    predictedPeak = it.predictedPeak,
                    actualPeak = it.actualPeak,
                    timeToPeak = it.timeToPeak,
                    bgStart = it.bgStart,
                    bgEnd = it.bgEnd
                )
            }
        }

        // ----- Pending learning updates persistence -----
        private fun loadPendingLearningUpdatesSerializable(): List<SPendingUpdate> {
            val json = prefs.getString("pending_learning_updates", null) ?: return emptyList()
            return try {
                gson.fromJson(json, Array<SPendingUpdate>::class.java)?.toList() ?: emptyList()
            } catch (e: Exception) {
                emptyList()
            }
        }

        override fun savePendingLearningUpdate(update: LearningUpdate) {
            val current = loadPendingLearningUpdatesSerializable().toMutableList()
            current.add(
                SPendingUpdate(
                    timestamp = update.timestamp.millis,
                    detectedCarbs = update.detectedCarbs,
                    givenDose = update.givenDose,
                    startBG = update.startBG,
                    expectedPeak = update.expectedPeak,
                    mealType = update.mealType
                )
            )
            prefs.edit().putString("pending_learning_updates", gson.toJson(current)).apply()
        }

        override fun loadPendingLearningUpdates(): List<LearningUpdate> {
            val serial = loadPendingLearningUpdatesSerializable()
            return serial.map {
                LearningUpdate(
                    timestamp = DateTime(it.timestamp),
                    detectedCarbs = it.detectedCarbs,
                    givenDose = it.givenDose,
                    startBG = it.startBG,
                    expectedPeak = it.expectedPeak,
                    mealType = it.mealType
                )
            }
        }

        override fun clearPendingLearningUpdates() {
            prefs.edit().remove("pending_learning_updates").apply()
        }

        override fun savePendingCorrectionUpdate(update: CorrectionUpdate) {
            val key = "pending_correction_updates"
            val current = loadPendingCorrectionUpdates().toMutableList()
            current.add(update)
            val json = gson.toJson(current)
            prefs.edit().putString(key, json).apply()
        }

        override fun loadPendingCorrectionUpdates(): List<CorrectionUpdate> {
            val key = "pending_correction_updates"
            val json = prefs.getString(key, null) ?: return emptyList()
            return try {
                val type = object : TypeToken<List<CorrectionUpdate>>() {}.type
                gson.fromJson(json, type)
            } catch (e: Exception) {
                emptyList()
            }
        }

        override fun clearPendingCorrectionUpdates() {
            val key = "pending_correction_updates"
            prefs.edit().remove(key).apply()
        }


        // ----- Peak detection persistence -----
        private fun loadPeakDetectionDataSerializable(): List<SPeakData> {
            val json = prefs.getString("peak_detection_data", null) ?: return emptyList()
            return try {
                gson.fromJson(json, Array<SPeakData>::class.java)?.toList() ?: emptyList()
            } catch (e: Exception) {
                emptyList()
            }
        }

        override fun savePeakDetectionData(peakData: PeakDetectionData) {
            val current = loadPeakDetectionDataSerializable().toMutableList()
            current.add(
                SPeakData(
                    timestamp = peakData.timestamp.millis,
                    bg = peakData.bg,
                    trend = peakData.trend,
                    acceleration = peakData.acceleration,
                    isPeak = peakData.isPeak
                )
            )
            val limited = current.takeLast(500)
            prefs.edit().putString("peak_detection_data", gson.toJson(limited)).apply()
        }

        override fun loadPeakDetectionData(): List<PeakDetectionData> {
            val serial = loadPeakDetectionDataSerializable()
            return serial.map {
                PeakDetectionData(
                    timestamp = DateTime(it.timestamp),
                    bg = it.bg,
                    trend = it.trend,
                    acceleration = it.acceleration,
                    isPeak = it.isPeak
                )
            }
        }

        override fun resetAllLearningData() {
            try {
                // Verwijder alle shared preferences keys
                prefs.edit().clear().apply()
                // Verwijder backup files
                if (backupFile.exists()) {backupFile.delete()}
                if (backupMeta.exists()) {backupMeta.delete()}
            } catch (e: Exception) {
            }
        }
    }

    // ★★★ PERSISTENT HIGH BG DATA CLASS ★★★
    data class PersistentResult(
        val extraBolus: Double,
        val log: String,
        val shouldDeliver: Boolean
    )

    data class MealResponseData(
        val timestamp: DateTime,
        val carbs: Double,
        val insulinGiven: Double,
        val predictedPeak: Double,
        val actualPeak: Double,
        val timeToPeak: Int,
        val bgStart: Double,
        val bgEnd: Double
    )

    data class LearningUpdate(
        val timestamp: DateTime,
        val detectedCarbs: Double,
        val givenDose: Double,
        val startBG: Double,
        val expectedPeak: Double,
        val mealType: String
    )

    data class CorrectionUpdate(
        val insulinGiven: Double,
        val predictedDrop: Double,
        val bgStart: Double,
        val timestamp: DateTime
    )

    data class PeakDetectionData(
        val timestamp: DateTime,
        val bg: Double,
        val trend: Double,
        val acceleration: Double,
        val isPeak: Boolean
    )

    // PERFORMANCE TRACKING DATA CLASSES
    data class MealPerformanceResult(
        val timestamp: DateTime,
        val detectedCarbs: Double,
        val givenDose: Double,
        val startBG: Double,
        val predictedPeak: Double,
        val actualPeak: Double,
        val timeToPeak: Int, // minutes
        val parameters: MealParameters,
        val outcome: String,     // "SUCCESS", "TOO_HIGH", "TOO_LOW"
        val peakConfidence: Double, // ★★★ NIEUW TOEGEVOEGD
        val mealType: String = "unknown" // ★★★ OPTIONEEL: ook handig!
    )

    data class CorrectionPerformanceResult(
        val timestamp: DateTime,
        val givenDose: Double,
        val startBG: Double,
        val predictedDrop: Double,
        val actualDrop: Double,
        val outcome: String // "SUCCESS", "TOO_AGGRESSIVE", "TOO_CONSERVATIVE"
    )

    data class MealParameters(
        val bolusPercEarly: Double,
        val bolusPercDay: Double,
        val bolusPercNight: Double,
        val peakDampingFactor: Double,
        val hypoRiskFactor: Double,
        val timestamp: DateTime
    )

    data class FCLLearningProfile(
        val personalCarbRatio: Double = 1.0,
        val personalISF: Double = 1.0,
        val mealTimingFactors: Map<String, Double> = emptyMap(),
        val lastUpdated: DateTime = DateTime.now(),
        val learningConfidence: Double = 0.0,
        val totalLearningSamples: Int = 0
    ) {
        fun getMealTimeFactor(hour: Int): Double {
            val mealType = when (hour) {
                in 6..10 -> "breakfast"
                in 11..14 -> "lunch"
                in 17..21 -> "dinner"
                else -> "other"
            }
            return mealTimingFactors[mealType] ?: 1.0
        }
    }

    data class EnhancedInsulinAdvice(
        val dose: Double,
        val reason: String,
        val confidence: Double,
        val predictedValue: Double? = null,
        val mealDetected: Boolean = false,
        val detectedCarbs: Double = 0.0,
        val shouldDeliverBolus: Boolean = false,
        val phase: String = "auto",
        val learningMetrics: LearningMetrics? = null,
        val reservedDose: Double = 0.0,
        val carbsOnBoard: Double = 0.0,
        val Target_adjust: Double = 0.0,
        val ISF_adjust: Double = 0.0,
        val activityLog: String = "",
        val resistentieLog: String = "",
        val effectiveISF: Double = 0.0,
        val MathBolusAdvice: String = "",
        // ★★★ NIEUWE VELDEN VOOR WISKUNDIGE FASE HERKENNING ★★★
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

    data class LearningMetrics(
        val confidence: Double,
        val samples: Int,
        val carbRatioAdjustment: Double,
        val isfAdjustment: Double,
        val mealTimeFactors: Map<String, Double>,
    )

    data class BGDataPoint(
        val timestamp: DateTime,
        val bg: Double,
        val iob: Double
    )

    data class ActiveCarbs(
        val timestamp: DateTime,      // start van de maaltijd
        var totalCarbs: Double,       // totaal geschatte koolhydraten
        var absorbedCarbs: Double = 0.0, // reeds opgenomen
        val tau: Double = 40.0        // tijdconstante in minuten (≈ hoe snel opname verloopt)
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

    // ★★★ WISKUNDIGE FASE HERKENNING DATA CLASSES ★★★
    data class RobustTrendAnalysis(
        val firstDerivative: Double,  // mmol/L per uur (helling)
        val secondDerivative: Double, // mmol/L per uur² (versnelling)
        val consistency: Double,      // 0-1 betrouwbaarheidsscore
        val phase: String = "uncertain"
    )

    // ★★★ FASE TRANSITIE DATA CLASS ★★★
    data class PhaseTransitionResult(
        val phase: String,
        val transitionFactor: Double, // 0.0-1.0 factor voor bolusaanpassing
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

    // Configuration properties
    private var currentBg: Double = 5.5
    private var currentCR: Double = 7.0
    private var currentISF: Double = 8.0
    private var Target_Bg: Double = 5.2

    private val persistentLogHistory = mutableListOf<String>()
    private val MAX_LOG_HISTORY = 5

    private var lastPersistentBolusTime: DateTime? = null
    private var persistentBolusCount: Int = 0
    private var persistentGrens: Double = 0.6

    private val minIOBForEffect = 0.3
    private val insulinSensitivityFactor = 3.0
    private val dailyReductionFactor = 0.7

    // State tracking
    private var lastMealTime: DateTime? = null
    private var mealInProgress = false
    private var peakDetected = false
    private var mealDetectionState = MealDetectionState.NONE

    // ★★★ DEBUG VARIABELEN VOOR CSV ★★★
    private var lastMealDetectionDebug: String = ""
    private var lastCOBDebug: String = ""
    private var lastReservedBolusDebug: String = ""

     private var recentDataForAnalysis: List<BGDataPoint> = emptyList()

    // ★★★ NIEUW: Reserved bolus tracking ★★★
    private var pendingReservedBolus: Double = 0.0
    private var pendingReservedCarbs: Double = 0.0
    private var pendingReservedTimestamp: DateTime? = null
    private var pendingReservedPhase: String = "stable"

    // Progressieve bolus tracking
    private val activeMeals = mutableListOf<ActiveCarbs>()
    private val pendingLearningUpdates = mutableListOf<LearningUpdate>()
    private val pendingCorrectionUpdates = mutableListOf<CorrectionUpdate>()

    // ★★★ STAPPEN GLOBALE VARIABELEN ★★★

    private var currentStappenPercentage: Double = 100.0
    private var currentStappenTargetAdjust: Double = 0.0
    private var currentStappenLog: String = "--- Steps activity ---\nStep counter switched OFF"

    private var DetermineStap5min: Int =1
    private var DetermineStap30min: Int =1

    // ★★★ CARBS TRACKING VARIABELEN ★★★
    private var lastDetectedCarbs: Double = 0.0
    private var lastCarbsOnBoard: Double = 0.0
    private var lastCOBUpdateTime: DateTime? = null

    // Learning system
    private val storage: FCLStorage = AndroidFCLStorage(context)
    private var learningProfile: FCLLearningProfile = storage.loadLearningProfile() ?: FCLLearningProfile()

    // ★★★ WISKUNDIGE FASE STATE TRACKING ★★★
    private var lastRobustTrends: RobustTrendAnalysis? = null
    private var lastMathBolusAdvice: String = ""
    private var lastMathAnalysisTime: DateTime? = null

    // ★★★ FASE TRANSITIE TRACKING ★★★
    private var lastPhaseTransitionFactor: Double = 1.0

    // ★★★ Tracking van laatste afgegeven bolus ★★★
    private var lastDeliveredBolus: Double = 0.0
    private var lastBolusReason: String = ""
    private var lastBolusTime: DateTime? = null
    private val MEAL_DETECTION_COOLDOWN_MINUTES = 45

    private var lastCalculatedBolus: Double = 0.0
    private var lastShouldDeliver: Boolean = false

    // ★★★ CSV LOGGING VARIABELEN ★★★
    private var lastCleanupCheck: DateTime? = null
    private val CLEANUP_CHECK_INTERVAL = 24 * 60 * 60 * 1000L // 24 uur

    // ★★★ RESISTENTIE HELPER ★★★
    private val resistanceHelper = FCLResistance(preferences, persistenceLayer, context)
    // ★★★ ACTIVITY HELPER ★★★
    private val activityHelper = FCLActivity(preferences, context)
    // ★★★ LOGGING HELPER ★★★
    private val loggingHelper = FCLLogging(context)
    // ★★★ PARAMETERS HELPER ★★★
    private val parametersHelper = FCLParameters(preferences)
    // ★★★ METRICS HELPER ★★★
    private val metricsHelper = FCLMetrics(context)


    init {
         // ★★★  Reset learning data EERST als nodig ★★★
        resetLearningDataIfNeeded()
        // Robuust laden van learning profile
        try {

            val loadedProfile = storage.loadLearningProfile()
            learningProfile = loadedProfile ?: FCLLearningProfile()

            // NIEUW: Controleer op stagnatie bij opstarten
            checkAndResetConfidenceStagnation()
            // NIEUW: Recalculate confidence bij opstarten
            if (learningProfile.totalLearningSamples > 0) {
                val recalculatedConfidence = calculateLearningConfidence()
                learningProfile = learningProfile.copy(learningConfidence = recalculatedConfidence)}

        } catch (e: Exception) {
            learningProfile = FCLLearningProfile()
        }
        // Laad andere data
        try {
            pendingLearningUpdates.clear()
            pendingLearningUpdates.addAll(storage.loadPendingLearningUpdates())

        } catch (ex: Exception) {

        }

        processPendingLearningUpdates()
        processPendingCorrectionUpdates()
        detectPeaksFromHistoricalData()
        processFallbackLearning()
    }


    // ★★★ UNIFORME CARB DETECTIE METHODE - VERVANGT BEIDE OUDE METHODES ★★★
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

        // ★★★ BASIS CARBS BEREKENING - COMBINATIE VAN BEIDE METHODES ★★★
        var detectedCarbs = 0.0
        var detectionReason = "No carb detection"
        var confidence = 0.0

        // 1. Onverklaarde stijging (van oude detectMealFromBG)
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

        // ★★★ BESLISSINGSLOGICA - COMBINEER ALLE SIGNALEN ★★★
        when {
            // Zeer sterke stijging - prioriteit 1
            slope10 > 5.0 -> {
                detectedCarbs = slope10 * 12.0
                detectionReason = "Rapid rise detection: slope=${"%.1f".format(slope10)} mmol/L/h"
                confidence = 0.8
            }

            // Hoge wiskundige carbs met goede consistentie - prioriteit 2
            mathCarbs > 20.0 && robustTrends.consistency > 0.6 -> {
                detectedCarbs = mathCarbs
                detectionReason = "Mathematical detection: ${robustTrends.phase}, slope=${"%.1f".format(robustTrends.firstDerivative)}"
                confidence = robustTrends.consistency
            }
            // Onverklaarde stijging boven drempel - prioriteit 3
            unexplainedDelta > mealDetectionSensitivity * 0.7 -> {
                detectedCarbs = cobAdjustedCarbs
                detectionReason = "Unexplained rise: ${"%.1f".format(unexplainedDelta)} mmol/L"
                confidence = 0.6
            }
            // Matige stijging met consistente trend - prioriteit 4
            slope10 > 1.5 && currentBG > targetBG + 0.3 && robustTrends.consistency > 0.4 -> {
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

    private fun calculateCOBAdjustedCarbs(
        unexplainedDelta: Double,
        effectiveCR: Double,
        mealDetectionSensitivity: Double
    ): Double {
        return unexplainedDelta * effectiveCR * preferences.get(IntKey.carb_percentage).toDouble() / 100.0
    }


    private fun updateResistentieIndienNodig() {
        resistanceHelper.updateResistentieIndienNodig(isNachtTime())
    }

    private fun resetLearningDataIfNeeded() {
        if (preferences.get(BooleanKey.ResetLearning)) {

            try {
                // Reset storage
                storage.resetAllLearningData()

                // Reset in-memory profile
                learningProfile = FCLLearningProfile()

                // Clear pending updates
                pendingLearningUpdates.clear()
                pendingCorrectionUpdates.clear()
                activeMeals.clear()
                // ★★★ RESET WISKUNDIGE STATE ★★★
                lastRobustTrends = null
                lastMathBolusAdvice = ""
                lastMathAnalysisTime = null

            } catch (e: Exception) {

            }
        }
    }

    fun setCurrentCR(value: Double) {currentCR = value}
    fun setCurrentISF(value: Double) {currentISF = value}
    fun setTargetBg(value: Double) {Target_Bg = value}

    fun set5minStap(value: Int) {DetermineStap5min = value}
    fun set30minStap(value: Int) {DetermineStap30min = value}

    private fun getCurrentBolusAggressiveness(): Double {return if (isNachtTime()) preferences.get(IntKey.bolus_perc_night).toDouble() else preferences.get(IntKey.bolus_perc_day).toDouble()}

    private fun gethypoThreshold(): Double {
        return if (isNachtTime()) HYPO_THRESHOLD_NIGHT else HYPO_THRESHOLD_DAY
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
            Pair(6, 0) // fallback
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
            // Over middernacht (bijv. 23:00 tot 05:00)
            huidigeTijdInMinuten >= startInMinuten || huidigeTijdInMinuten < eindInMinuten
        } else {
            // Normaal bereik (bijv. 08:00 tot 17:00)
            huidigeTijdInMinuten in startInMinuten..eindInMinuten
        }
    }

    // ★★★ PERSISTENT HIGH BG DETECTIE ★★★
    private fun checkPersistentHighBG(
        historicalData: List<BGDataPoint>,
        currentIOB: Double,
        MaxIOB: Double
    ): PersistentResult {

        if (!preferences.get(BooleanKey.PersistentAanUit)) {
            return PersistentResult(0.0, "Persistent high BG correction uitgeschakeld", false)
        }

        val now = DateTime.now()

        // Cooldown check
        lastPersistentBolusTime?.let { lastTime ->
            val minutesSinceLast = Minutes.minutesBetween(lastTime, now).minutes
            if (minutesSinceLast < preferences.get(IntKey.persistent_CoolDown)) {
                return PersistentResult(0.0,
                                        "Persistent: Cooldown (${preferences.get(IntKey.persistent_CoolDown) - minutesSinceLast} min)",
                                        false)
            }
        }

        // Check voldoende data
        if (historicalData.size < 7) {
            return PersistentResult(0.0, "Persistent: Onvoldoende data", false)
        }

        val currentBG = historicalData.last().bg
        val delta5 = historicalData.last().bg - historicalData[historicalData.size - 2].bg
        val delta15 = historicalData.last().bg - historicalData[historicalData.size - 4].bg
        val delta30 = historicalData.last().bg - historicalData[historicalData.size - 7].bg

        val isNacht = isNachtTime()
        val persistentDrempel = if (isNacht) preferences.get(DoubleKey.persistent_Nachtdrempel) else preferences.get(DoubleKey.persistent_Dagdrempel)
        val maxBolus = if (isNacht) preferences.get(DoubleKey.persistent_Nacht_MaxBolus) else preferences.get(DoubleKey.persistent_Dag_MaxBolus)
        val dagDeel = if (isNacht) "NightTime" else "DayTime"

        var logEntries = mutableListOf<String>()
        logEntries.add("Persistent BG Analysis - $dagDeel")

        // Stabiliteitscheck
        val isStableHighBG = (abs(delta5) < persistentGrens &&
            abs(delta15) < persistentGrens + 0.5 &&
            abs(delta30) < persistentGrens + 1.0 &&
            currentBG > persistentDrempel)

        if (!isStableHighBG) {
            logEntries.add("No persistent high BG detected")
            logEntries.add("BG: ${"%.1f".format(currentBG)}, threshold: ${"%.1f".format(persistentDrempel)}")
            logEntries.add("Stability: 5min=${"%.1f".format(delta5)}, 15min=${"%.1f".format(delta15)}")
            return PersistentResult(0.0, logEntries.joinToString("\n"), false)
        }

        // ★★★ LINEAIRE PROGRESSIE TUSSEN TARGET EN TARGET+2 ★★★
        val bgAboveThreshold = currentBG - persistentDrempel
        val linearRange = 1.0 // mmol/L range voor lineaire progressie

        // Bereken lineaire factor (0.0 - 1.0) binnen het bereik persistentDrempel tot persistentDrempel+2
        val linearFactor = (bgAboveThreshold / linearRange).coerceIn(0.0, 1.0)

        // Basis bolus = lineaire progressie van 0 tot maxBolus
        val baseBolus = maxBolus * linearFactor

        logEntries.add("Persistent high BG detected!")
        logEntries.add("BG: ${"%.1f".format(currentBG)} > threshold: ${"%.1f".format(persistentDrempel)}")
        logEntries.add("BG above threshold: ${"%.1f".format(bgAboveThreshold)} mmol/L")
        logEntries.add("Linear factor: ${(linearFactor * 100).toInt()}%")
        logEntries.add("Base bolus: ${"%.2f".format(baseBolus)}U")

        // ★★★ IOB CORRECTIE ★★★
        val iobFactor = when {
            currentIOB > MaxIOB * 0.75 -> 0.6
            currentIOB > MaxIOB * 0.5 -> 0.7
            currentIOB > MaxIOB * 0.25 -> 0.8
            currentIOB > MaxIOB * 0.1 -> 0.9
            else -> 1.0
        }

        // ★★★ TOEPASSEN IOB CORRECTIE ★★★
        var calculatedBolus = baseBolus * iobFactor


        // Begrens tot max bolus (veiligheidsnet)
        val finalBolus = min(calculatedBolus, maxBolus)

        if (finalBolus < 0.1) {
            logEntries.add("No extra bolus: Too small after safety checks")
            logEntries.add("Berekend: ${"%.2f".format(calculatedBolus)}U, IOB: ${"%.1f".format(currentIOB)}")
            return PersistentResult(0.0, logEntries.joinToString("\n"), false)
        }

        // Update tracking
        persistentBolusCount++
        lastPersistentBolusTime = now

        logEntries.add("Extra bolus: ${"%.2f".format(finalBolus)}U")
        logEntries.add("Max allowed: ${"%.2f".format(maxBolus)}U")
        logEntries.add("IOB factor: ${(iobFactor * 100).toInt()}%")
        logEntries.add("Linear progress: ${(linearFactor * 100).toInt()}%")
        logEntries.add("Nr boluses: $persistentBolusCount")

        val logEntry = "${DateTime.now().toString("HH:mm")} | BG: ${"%.1f".format(currentBG)} | BOLUS: ${"%.2f".format(finalBolus)}U"
        persistentLogHistory.add(0, logEntry)
        if (persistentLogHistory.size > MAX_LOG_HISTORY) {
            persistentLogHistory.removeAt(persistentLogHistory.lastIndex)
        }

        return PersistentResult(roundDose(finalBolus), logEntries.joinToString("\n"), true)
    }

    // ★★★ STAPPEN BEREKENING - uit FCLActivity file ★★★
    fun berekenStappenAdjustment() {
        val result = activityHelper.berekenStappenAdjustment(DetermineStap5min, DetermineStap30min)
        currentStappenPercentage = result.percentage
        currentStappenTargetAdjust = result.targetAdjust
        currentStappenLog = result.log
    }

    fun getLearningStatus(): String {
        val Day_Night = if (isNachtTime()) "NightTime" else "DayTime"
        val currentHour = DateTime.now().hourOfDay

        val recentMeals = storage.loadMealPerformanceResults().takeLast(5).reversed()
        val mealPerformanceSummary = if (recentMeals.isNotEmpty()) {
            recentMeals.joinToString("\n    ") { meal ->
                "${meal.timestamp.toString("HH:mm")} | ${meal.mealType.padEnd(10)} | " +
                    "${meal.detectedCarbs.toInt()}g | Peak: ${"%.1f".format(meal.actualPeak)} | " +
                    "Conf: ${(meal.peakConfidence * 100).toInt()}% | ${meal.outcome}"
            }
        } else {
            "    No meal data yet"
        }


        // ★★★ VERVANGDE STAPPEN WEERGAVE - GEBRUIK DIRECT DE LOG ★★★
        val stappenStatus = if (preferences.get(BooleanKey.stappenAanUit)) {
            // Toon de complete stappenlog
            currentStappenLog
        } else {
            "Step counter switched OFF"
        }

        var PersiOnOff = ""
        if (preferences.get(BooleanKey.PersistentAanUit)) {
            PersiOnOff = " - Persistent Bg detection switched ON"
        } else {
            PersiOnOff = " - Persistent Bg detection switched OFF"
        }

        // ★★★ METRICS BEREKENEN ★★★
        val metrics24h = metricsHelper.calculateMetrics(24)
        val metrics7d = metricsHelper.calculateMetrics(168) // 7 dagen
        val dataQuality24h = metricsHelper.getDataQualityMetrics(24)

// ★★★ PARAMETER ADVIES ★★★
        val parameters = FCLParameters(preferences)
        val agressivenessAdvice = metricsHelper.calculateAgressivenessAdvice(parameters, metrics24h)


        return """
╔═══════════════════
║  ══ FCL v2.7.2 ══ 
╚═══════════════════

🎯 LAATSTE BOLUS BESLISSING
─────────────────────
• Fase/advies: ${lastMathBolusAdvice?.take(100) ?: "Geen berekend"}${if (lastMathBolusAdvice?.length ?: 0 > 100) "..." else ""}
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


[🍽️  KOOLHYDRATEN DETECTIE]
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
${lastMathBolusAdvice ?: "Geen wiskundig advies berekend"}

🛡️ VEILIGHEIDSSYSTEEM
─────────────────────
• Max bolus: ${round(preferences.get(DoubleKey.max_bolus), 2)}U
• Max basaal: ${round(preferences.get(DoubleKey.ApsMaxBasal), 2)}U/h
• Max IOB: ${round(preferences.get(DoubleKey.ApsSmbMaxIob), 2)}U
• IOB correctie %: ${(preferences.get(IntKey.IOB_corr_perc))}%


🔥 PERSISTENTE HOGE BG 
─────────────────────
${PersiOnOff}
• Dag drempel: ${"%.1f".format(preferences.get(DoubleKey.persistent_Dagdrempel))} mmol/L
• Nacht drempel: ${"%.1f".format(preferences.get(DoubleKey.persistent_Nachtdrempel))} mmol/L
• Max bolus: Dag ${"%.2f".format(preferences.get(DoubleKey.persistent_Dag_MaxBolus))}U, Nacht ${"%.2f".format(preferences.get(DoubleKey.persistent_Nacht_MaxBolus))}U
• Stabiliteitsgrens: ${"%.1f".format(persistentGrens)} mmol/L
• Cooldown: ${preferences.get(IntKey.persistent_CoolDown)} min

[ PERSISTENTE CHECKS (laatste ${MAX_LOG_HISTORY}) ]
${if (persistentLogHistory.isEmpty()) "Geen recente checks" else persistentLogHistory.joinToString("\n  ") { it.trim() }}


⚙️ INSTELLINGEN & CONFIGURATIE
─────────────────────
[ BOLUS INSTELLINGEN ]
• Overall Aggressiveness: ${Day_Night} → ${getCurrentBolusAggressiveness().toInt()}% 
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
${stappenStatus.split("\n").joinToString("\n  ") { it }}

🔄 RESISTENTIE ANALYSE
─────────────────────
${resistanceHelper.getCurrentResistanceLog().split("\n").joinToString("\n  ") { it }}

📈 RECENTE ACTIVITEIT
─────────────────────
[ MAALTIJD PRESTATIES ]
$mealPerformanceSummary

⚙️ PARAMETERS CONFIGURATIE OVERZICHT
─────────────────────
${parametersHelper.getParameterSummary()}

📊 GLUCOSE METRICS & PERFORMANCE
─────────────────────
[ DATA KWALITEIT - 24U ]
• Metingen: ${dataQuality24h.totalReadings}/${dataQuality24h.expectedReadings}
• Completeheid: ${dataQuality24h.dataCompleteness.toInt()}% ${if (!dataQuality24h.hasSufficientData) "⚠️" else "✅"}
• Metingen per uur: ${metrics24h.readingsPerHour.toInt()}/12 ${if (metrics24h.readingsPerHour < 8) "⚠️" else "✅"}

[ LAATSTE 24 UUR ]
• Time in Range: ${metrics24h.timeInRange.toInt()}% (3.9-10.0 mmol/L)
• Time Below Range: ${metrics24h.timeBelowRange.toInt()}% (<3.9 mmol/L) ${if (metrics24h.timeBelowRange > 5) "⚠️" else ""}
• Time Above Range: ${metrics24h.timeAboveRange.toInt()}% (>10.0 mmol/L) ${if (metrics24h.timeAboveRange > 25) "⚠️" else ""}
• Time Below Target: ${metrics24h.timeBelowTarget.toInt()}% (<5.2 mmol/L) ${if (metrics24h.timeBelowTarget > 15) "🚨" else ""}
• Gemiddelde glucose: ${round(metrics24h.averageGlucose, 1)} mmol/L
• GMI: ${round(metrics24h.gmi, 1)}% (geschatte HbA1c)
• Variatie (CV): ${metrics24h.cv.toInt()}% ${if (metrics24h.cv > 36) "⚠️" else ""}
• Hypo events: ${metrics24h.lowEvents} (zeer laag: ${metrics24h.veryLowEvents}) ${if (metrics24h.lowEvents > 3) "🚨" else ""}
• Hyper events: ${metrics24h.highEvents} ${if (metrics24h.highEvents > 3) "⚠️" else ""}
• Agressiviteit score: ${round(metrics24h.agressivenessScore, 1)}/10 ${if (metrics24h.agressivenessScore > 5) "🚨" else ""}
• Meal detection rate: ${metrics24h.mealDetectionRate.toInt()}%
• Bolus delivery rate: ${metrics24h.bolusDeliveryRate.toInt()}%
• Gem. gedetecteerde carbs: ${round(metrics24h.averageDetectedCarbs, 1)}g

[ LAATSTE 7 DAGEN ]
• Time in Range: ${metrics7d.timeInRange.toInt()}%
• Time Below Range: ${metrics7d.timeBelowRange.toInt()}%
• Time Above Range: ${metrics7d.timeAboveRange.toInt()}%
• Time Below Target: ${metrics7d.timeBelowTarget.toInt()}% ${if (metrics7d.timeBelowTarget > 15) "⚠️" else ""}
• Gemiddelde glucose: ${round(metrics7d.averageGlucose, 1)} mmol/L

[ PARAMETER OPTIMALISATIE ADVIES ]
${if (agressivenessAdvice.isNotEmpty()) {
            agressivenessAdvice.joinToString("\n  ") { advice ->
                val arrow = when (advice.changeDirection) {
                    "INCREASE" -> "⬆️"
                    "DECREASE" -> "⬇️"
                    else -> "➡️"
                }
                "$arrow ${advice.parameterName}: ${advice.currentValue.toInt()} → ${advice.recommendedValue.toInt()}"
            }
        } else {
            "  ✅ Geen parameter aanpassingen aanbevolen"
        }}

${if (agressivenessAdvice.isNotEmpty()) {
            "\n[ TOELICHTING ]\n" + agressivenessAdvice.joinToString("\n  ") { advice ->
                "• ${advice.parameterName}: ${advice.reason}"
            }
        } else {
            ""
        }}

""".trimIndent()

    }

    // ★★★ VERVANG DE BESTAANDE PIEKDETECTIE ★★★
    private fun detectPeaksFromHistoricalData() {
        val historicalData = storage.loadPeakDetectionData()
        if (historicalData.isEmpty()) return
        if (pendingLearningUpdates.isEmpty()) return

        // Gebruik de nieuwe geavanceerde piekdetectie
        val peaks = advancedPeakDetection(
            historicalData.map {
                BGDataPoint(timestamp = it.timestamp, bg = it.bg, iob = 0.0)
            }
        )

        if (peaks.isEmpty()) return

        val processed = mutableListOf<LearningUpdate>()

        // Loop over een kopie van pendingLearningUpdates
        for (update in ArrayList(pendingLearningUpdates)) {
            var bestMatch: PeakDetectionData? = null
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

                }
            }
        }

        if (processed.isNotEmpty()) {
            pendingLearningUpdates.removeAll(processed.toSet())
            storage.clearPendingLearningUpdates()
            pendingLearningUpdates.forEach { storage.savePendingLearningUpdate(it) }
        }
    }

    // ★★★ NIEUWE GEAVANCEERDE PIEKDETECTIE ★★★
    private fun advancedPeakDetection(historicalData: List<BGDataPoint>): List<PeakDetectionData> {
        if (historicalData.size < 5) return emptyList()

        val smoothedData = applyExponentialSmoothing(historicalData, alpha = 0.3)
        val peaks = mutableListOf<PeakDetectionData>()

        for (i in 2 until smoothedData.size - 2) {
            val window = smoothedData.subList(i-2, i+3)
            val (isPeak, confidence) = analyzePeakCharacteristics(window)

            if (isPeak && confidence > 0.7) {
                val originalData = historicalData[i]
                peaks.add(PeakDetectionData(
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
        detectedPeaks: List<PeakDetectionData>,
        historicalData: List<BGDataPoint>
    ): List<PeakDetectionData> {
        val filtered = mutableListOf<PeakDetectionData>()
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
        // synchroniseer in-memory met storage
        try {
            pendingLearningUpdates.clear()
            pendingLearningUpdates.addAll(storage.loadPendingLearningUpdates())
        } catch (ex: Exception) {

            return
        }

        val now = DateTime.now()
        val historicalData = storage.loadPeakDetectionData()
        if (pendingLearningUpdates.isEmpty() || historicalData.size < 5) return

        val processed = mutableListOf<LearningUpdate>()

        for (update in ArrayList(pendingLearningUpdates)) {
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
                        peakTimestamp = peakTimestamp,

                    )

                    processed.add(update)
                } catch (ex: Exception) {

                }
            }
        }

        if (processed.isNotEmpty()) {
            pendingLearningUpdates.removeAll(processed.toSet())
            storage.clearPendingLearningUpdates()
            pendingLearningUpdates.forEach { storage.savePendingLearningUpdate(it) }
        }
    }


    // ★★★ Safety check voor meal detectie boven target ★★★
    private fun canDetectMealAboveTarget(
        currentBG: Double,
        targetBG: Double,
        trends: TrendAnalysis,
        currentIOB: Double,
        MaxIOB: Double
    ): Boolean {
        return when {
            currentBG > targetBG + 5.0 -> false  // Verhoog van 3.0 naar 5.0
            trends.recentTrend < -2.0 -> false    // Minder strict voor lichte daling
            currentIOB > MaxIOB * 0.8 -> false   // Verhoog van 0.62 naar 0.8
            else -> true
        }
    }


    fun estimateRiseFromCOB(
        effectiveCR: Double,
        tauAbsorptionMinutes: Int,
        detectionWindowMinutes: Int = 60  // horizon voor meal detection
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


    // Store peak detection data for later analysis
    private fun storePeakDetectionData(currentData: BGDataPoint, trends: TrendAnalysis) {
        val deltaThreshold = 0.5 // mmol verschil om op te slaan
        val minMinutes = 5

        val previous = storage.loadPeakDetectionData().lastOrNull()
        val shouldSave = when {
            previous == null -> true
            abs(currentData.bg - previous.bg) >= deltaThreshold -> true
            Minutes.minutesBetween(previous.timestamp, currentData.timestamp).minutes >= minMinutes -> true
            else -> false
        }

        if (!shouldSave) return

        val peakData = PeakDetectionData(
            timestamp = currentData.timestamp,
            bg = currentData.bg,
            trend = trends.recentTrend,
            acceleration = trends.acceleration,
            isPeak = false
        )
        storage.savePeakDetectionData(peakData)
    }


    // Learning functions
    private fun storeMealForLearning(detectedCarbs: Double, givenDose: Double, startBG: Double, expectedPeak: Double, mealType: String) {
        val learningUpdate = LearningUpdate(
            timestamp = DateTime.now(),
            detectedCarbs = detectedCarbs,
            givenDose = givenDose,
            startBG = startBG,
            expectedPeak = expectedPeak,
            mealType = mealType
        )
        // In-memory queue + persistent opslag
        try {
            pendingLearningUpdates.add(learningUpdate)
            storage.savePendingLearningUpdate(learningUpdate)

        } catch (ex: Exception) {

        }
    }


    private fun processPendingLearningUpdates() {
        // synchroniseer in-memory met storage (bestaande logica)
        try {
            pendingLearningUpdates.clear()
            pendingLearningUpdates.addAll(storage.loadPendingLearningUpdates())
        } catch (ex: Exception) {

            return
        }

        val now = DateTime.now()
        // Verwijder updates ouder dan 6 uur (te oud)
        val expired = pendingLearningUpdates.filter { update ->
            Minutes.minutesBetween(update.timestamp, now).minutes > 360
        }
        if (expired.isNotEmpty()) {
            pendingLearningUpdates.removeAll(expired.toSet())
            // persist remaining
            storage.clearPendingLearningUpdates()
            pendingLearningUpdates.forEach { storage.savePendingLearningUpdate(it) }
        }

        // --- NIEUW: probeer meteen pending updates te koppelen aan al aanwezige piek-data ---
        try {
            detectPeaksFromHistoricalData()
        } catch (ex: Exception) {
        }

        // --- NIEUW: probeer fallback-matching voor oudere updates (bestaande functie hergebruiken) ---
        try {
            processFallbackLearning()
        } catch (ex: Exception) {
        }

        // Herlaad in-memory pending list, omdat detectPeaks/processFallback mogelijk storage heeft aangepast
        try {
            pendingLearningUpdates.clear()
            pendingLearningUpdates.addAll(storage.loadPendingLearningUpdates())
        } catch (ex: Exception) {
        }
    }

    private fun processPendingCorrectionUpdates() {
        try {
            pendingCorrectionUpdates.clear()
            pendingCorrectionUpdates.addAll(storage.loadPendingCorrectionUpdates())
        } catch (ex: Exception) {

            return
        }

        val now = DateTime.now()
        val toRemove = mutableListOf<CorrectionUpdate>()

        for (update in pendingCorrectionUpdates) {
            val elapsed = Minutes.minutesBetween(update.timestamp, now).minutes

            // wacht 2–4 uur voor effectmeting
            if (elapsed in 120..240) {
                val bgNow = currentBg ?: continue
                val actualDrop = update.bgStart - bgNow

                updateISFFromCorrectionResponse(
                    givenCorrectionInsulin = update.insulinGiven,
                    predictedDrop = update.predictedDrop,
                    actualDrop = actualDrop,
                    bgStart = update.bgStart,
                               )

                toRemove.add(update)
            }

            // opruimen als te oud (>6h)
            if (elapsed > 360) {
                toRemove.add(update)
            }
        }

        if (toRemove.isNotEmpty()) {
            pendingCorrectionUpdates.removeAll(toRemove.toSet())
            storage.clearPendingCorrectionUpdates()
            pendingCorrectionUpdates.forEach { storage.savePendingCorrectionUpdate(it) }
        }

    }

    private fun adaptiveUpdate(
        oldValue: Double,
        observedValue: Double,
        confidence: Double,
        baseAlpha: Double,
        minValue: Double? = null,
        maxValue: Double? = null
    ): Double {
        // VERBETERDE OUTLIER DETECTIE
        if (observedValue <= 0 || observedValue > oldValue * 2.5 || observedValue < oldValue / 2.5) {

            return oldValue
        }

        // DYNAMISCHE ALPHA OP BASIS VAN CONFIDENCE
        val dynamicAlpha = baseAlpha * confidence.coerceIn(0.1, 1.0)

        // EXPONENTIËLE GLADING
        var updated = oldValue * (1 - dynamicAlpha) + observedValue * dynamicAlpha

        // BOUNDARIES
        if (minValue != null) updated = maxOf(minValue, updated)
        if (maxValue != null) updated = minOf(maxValue, updated)

        return updated
    }

    private fun updateLearningFromMealResponse(
        detectedCarbs: Double,
        givenDose: Double,
        predictedPeak: Double,
        actualPeak: Double,
        bgStart: Double,
        bgEnd: Double,
        mealType: String,
        startTimestamp: DateTime,
        peakTimestamp: DateTime,
    ) {
        // ★★★ VEREENVOUDIGDE LEARNING ZONDER HOURLY SENSITIVITIES ★★★
        val actualRise = actualPeak - bgStart
        val expectedRiseFromCarbs = if (currentCR > 0) detectedCarbs / currentCR else 0.0
        val observedCarbRatioEffectiveness = if (expectedRiseFromCarbs > 0)
            actualRise / expectedRiseFromCarbs else 1.0

        val minCrISFCf = preferences.get(DoubleKey.CarbISF_min_Factor)
        val maxCrISFCf = preferences.get(DoubleKey.CarbISF_max_Factor)

        // ★★★ ENKEL CARB RATIO LEARNING ★★★
        val newCarbRatio = adaptiveUpdate(
            oldValue = learningProfile.personalCarbRatio,
            observedValue = observedCarbRatioEffectiveness,
            confidence = min(1.0, abs(observedCarbRatioEffectiveness - 1.0)),
            baseAlpha = 0.03, // ★★★ LAGERE LEARNING RATE ★★★
            minValue = minCrISFCf,
            maxValue = maxCrISFCf
        )

        // ★★★ MEAL TIMING FACTORS BEHOUDEN ★★★
        val newMealFactors = learningProfile.mealTimingFactors.toMutableMap()
        val currentFactor = newMealFactors[mealType] ?: 1.0
        newMealFactors[mealType] = (currentFactor + (observedCarbRatioEffectiveness - 1.0) * 0.05)
            .coerceIn(minCrISFCf, maxCrISFCf)

        // ★★★ VEREENVOUDIGDE CONFIDENCE ★★★
        val newConfidence = calculateSimpleConfidence()

        learningProfile = FCLLearningProfile(
            personalCarbRatio = newCarbRatio,
            personalISF = 1.0, // ★★★ ISF LEARNING UITGESCHAKELD ★★★
            mealTimingFactors = newMealFactors,
            lastUpdated = DateTime.now(),
            learningConfidence = newConfidence,
            totalLearningSamples = learningProfile.totalLearningSamples + 1
        )

        storage.saveLearningProfile(learningProfile)

    }

    private fun calculateSimpleConfidence(): Double {
        val mealResults = storage.loadMealPerformanceResults()
        if (mealResults.isEmpty()) return 0.1

        val recentMeals = mealResults.takeLast(10)
        val successRate = recentMeals.count {
            it.outcome == "SUCCESS" ||
                (it.actualPeak in 6.0..10.0 && it.outcome != "TOO_LOW")
        }.toDouble() / recentMeals.size

        return successRate.coerceIn(0.1, 0.9)
    }


    private fun calculateLearningConfidence(): Double {
        val mealResults = storage.loadMealPerformanceResults()
        val correctionResults = storage.loadCorrectionPerformanceResults()

        // Verlengde periode voor recente samples: 21 dagen i.p.v. 14
        val recentMeals = mealResults.filter {
            Days.daysBetween(it.timestamp, DateTime.now()).days <= 21
        }
        val recentCorrections = correctionResults.filter {
            Days.daysBetween(it.timestamp, DateTime.now()).days <= 21
        }

        val totalRecentSamples = recentMeals.size + recentCorrections.size

        // EXPLICIETE SAMPLE LOGICA
        return when {
            learningProfile.totalLearningSamples < 10 -> {
                // Beginnende fase - lineaire groei van 0% naar 30%
                val linearGrowth = learningProfile.totalLearningSamples / 10.0 * 0.3
                min(0.3, linearGrowth).coerceAtLeast(0.05) // Minimaal 5%
            }

            totalRecentSamples < 5 -> {
                // Behoud bestaande confidence bij weinig recente samples, maar met vloer
                val maintainedConfidence = learningProfile.learningConfidence * 0.8
                max(0.3, maintainedConfidence) // Minimaal 30%
            }

            else -> {
                // Gebruik de bestaande gedetailleerde berekening
                calculateDetailedConfidence(recentMeals, recentCorrections, totalRecentSamples)
            }
        }
    }

    private fun calculateDetailedConfidence(
        recentMeals: List<MealPerformanceResult>,
        recentCorrections: List<CorrectionPerformanceResult>,
        totalRecentSamples: Int
    ): Double {
        var totalScore = 0.0
        var totalWeight = 0.0

        // Meal performance scoring (behoud bestaande logica)
        if (recentMeals.isNotEmpty()) {
            val successRate = recentMeals.count { it.outcome == "SUCCESS" }.toDouble() / recentMeals.size
            val avgPeakError = recentMeals.map { abs(it.actualPeak - it.predictedPeak) }.average()
            val peakAccuracy = max(0.0, 1.0 - (avgPeakError / 3.0)) // Max 3.0 mmol/L error

            val mealScore = (successRate * 0.6) + (peakAccuracy * 0.4)
            totalScore += mealScore * recentMeals.size
            totalWeight += recentMeals.size
        }

        // Correction performance scoring (behoud bestaande logica)
        if (recentCorrections.isNotEmpty()) {
            val successRate = recentCorrections.count { it.outcome == "SUCCESS" }.toDouble() / recentCorrections.size
            val dropAccuracy = recentCorrections.map {
                val relativeError = abs(it.actualDrop - it.predictedDrop) / max(1.0, it.predictedDrop)
                max(0.0, 1.0 - relativeError)
            }.average()

            val correctionScore = (successRate * 0.7) + (dropAccuracy * 0.3)
            totalScore += correctionScore * recentCorrections.size
            totalWeight += recentCorrections.size
        }

        val baseConfidence = if (totalWeight > 0) totalScore / totalWeight else 0.0

        // NIEUWE: Sample bonus berekening
        val sampleBonus = calculateSampleBonus(totalRecentSamples, learningProfile.totalLearningSamples)

        // NIEUWE: Time decay compensation - minder agressieve decay
        val timeCompensation = if (learningProfile.totalLearningSamples > 50) 0.1 else 0.0

        val finalConfidence = (baseConfidence + sampleBonus + timeCompensation)
            .coerceIn(0.0, 1.0)


        return finalConfidence
    }

    private fun calculateSampleBonus(recentSamples: Int, totalSamples: Int): Double {
        // Bonus gebaseerd op recente samples
        val recentBonus = when {
            recentSamples >= 20 -> 0.3
            recentSamples >= 15 -> 0.25
            recentSamples >= 10 -> 0.2
            recentSamples >= 5 -> 0.15
            else -> 0.1
        }

        // Bonus gebaseerd op totale samples (minder streng)
        val totalBonus = min(0.2, totalSamples / 200.0) // Bij 200 samples = 20% bonus

        return recentBonus + totalBonus
    }

    // NIEUWE FUNCTIE: Controleer en reset confidence stagnatie
    private fun checkAndResetConfidenceStagnation() {
        val currentConfidence = learningProfile.learningConfidence
        val totalSamples = learningProfile.totalLearningSamples

        // Als we veel samples hebben maar lage confidence, reset de berekening
        if (totalSamples > 30 && currentConfidence < 0.3) {


            // Forceer herberekening met huidige data
            val recalculatedConfidence = calculateLearningConfidence()

            if (recalculatedConfidence > currentConfidence) {
                learningProfile = learningProfile.copy(learningConfidence = recalculatedConfidence)
                storage.saveLearningProfile(learningProfile)

            }
        }
    }

    private fun updateISFFromCorrectionResponse(
        givenCorrectionInsulin: Double,
        predictedDrop: Double,
        actualDrop: Double,
        bgStart: Double,
       ) {
        if (givenCorrectionInsulin <= 0.0) return

        try {
            // ★★★ ALLEEN PERFORMANCE TRACKING BEHOUDEN ★★★
            val outcome = when {
                actualDrop > predictedDrop * 1.5 -> "TOO_AGGRESSIVE"
                actualDrop < predictedDrop * 0.5 -> "TOO_CONSERVATIVE"
                else -> "SUCCESS"
            }

            val correctionResult = CorrectionPerformanceResult(
                timestamp = DateTime.now(),
                givenDose = givenCorrectionInsulin,
                startBG = bgStart,
                predictedDrop = predictedDrop,
                actualDrop = actualDrop,
                outcome = outcome
            )

            storage.saveCorrectionPerformanceResult(correctionResult)

        } catch (e: Exception) {

        }
    }

    // ★★★ NIEUWE HYPO LEARNING FUNCTIES ★★★

    private fun updateLearningFromHypoAfterMeal(
        mealType: String,
        bgEnd: Double
    ) {
        val severity = when {
            bgEnd < 4.0 -> 0.3  // Ernstige hypo
            bgEnd < 4.5 -> 0.2  // Matige hypo
            else -> 0.1          // Milde hypo
        }

        // Pas meal timing factor aan
        val newMealFactors = learningProfile.mealTimingFactors.toMutableMap()
        val currentFactor = newMealFactors[mealType] ?: 1.0
        newMealFactors[mealType] = (currentFactor * (1.0 - severity)).coerceIn(preferences.get(DoubleKey.CarbISF_min_Factor), preferences.get(DoubleKey.CarbISF_max_Factor))

        // Update profile
        learningProfile = learningProfile.copy(
            mealTimingFactors = newMealFactors,
            lastUpdated = DateTime.now()
        )

        storage.saveLearningProfile(learningProfile)

    }

    private fun getTimeBasedRecoveryFactor(mealType: String, hour: Int): Double {
        val lastHypoTime = storage.loadMealPerformanceResults()
            .filter { it.mealType == mealType && it.outcome == "TOO_LOW" }
            .maxByOrNull { it.timestamp }?.timestamp

        if (lastHypoTime == null) return 1.0 // No hypo history

        val daysSinceLastHypo = Days.daysBetween(lastHypoTime, DateTime.now()).days

        // ★★★ GEBRUIK minRecoveryDays en maxRecoveryDays ★★★
        return when {
            daysSinceLastHypo < MIN_RECOVERY_DAYS -> 0.7  // Binnen minimale recovery periode
            daysSinceLastHypo < MIN_RECOVERY_DAYS + 1 -> 0.8
            daysSinceLastHypo < MIN_RECOVERY_DAYS + 2 -> 0.9
            daysSinceLastHypo < MAX_RECOVERY_DAYS -> 0.95
            else -> 1.0 // Na maxRecoveryDays - volledig hersteld
        }
    }

    private fun getPerformanceBasedRecovery(mealType: String): Double {
        val recentMeals = storage.loadMealPerformanceResults()
            .filter {
                it.mealType == mealType &&
                    Days.daysBetween(it.timestamp, DateTime.now()).days <= MAX_RECOVERY_DAYS // ★★★ Gebruik maxRecoveryDays ★★★
            }

        if (recentMeals.isEmpty()) return 1.0

        // Bereken success ratio
        val successCount = recentMeals.count { it.outcome == "SUCCESS" }
        val totalCount = recentMeals.size
        val successRatio = successCount.toDouble() / totalCount

        // ★★★ STRENGERE CRITERIA BIJ KORTE minRecoveryDays ★★★
        val requiredSuccessRatio = when (MIN_RECOVERY_DAYS) {
            1 -> 0.9  // 90% success bij snelle recovery
            2 -> 0.8  // 80% success bij normale recovery
            3 -> 0.7  // 70% success bij langzame recovery
            else -> 0.6 // 60% success bij zeer langzame recovery
        }

        // Bereken gemiddelde piek voor successen
        val successfulPeaks = recentMeals
            .filter { it.outcome == "SUCCESS" }
            .map { it.actualPeak }

        val avgPeak = if (successfulPeaks.isNotEmpty()) successfulPeaks.average() else 8.0

        // ★★★ DYNAMISCH HERSTEL OP BASIS VAN PERFORMANCE EN INSTELLINGEN ★★★
        return when {
            successRatio >= requiredSuccessRatio && avgPeak in 7.0..9.0 -> 1.0    // Perfect
            successRatio >= requiredSuccessRatio * 0.8 && avgPeak in 6.5..10.0 -> 0.95
            successRatio >= requiredSuccessRatio * 0.6 -> 0.9
            else -> 0.8
        }
    }

    private fun getHypoAdjustedMealFactor(mealType: String, hour: Int): Double {
        val baseFactor = learningProfile.getMealTimeFactor(hour)


        // Check recente hypo's voor deze maaltijd
        val recentMeals = storage.loadMealPerformanceResults()
            .filter {
                it.mealType == mealType &&
                    Days.daysBetween(it.timestamp, DateTime.now()).days <= MAX_RECOVERY_DAYS // ★★★ Gebruik maxRecoveryDays ★★★
            }

        val recentHypoCount = recentMeals.count { it.outcome == "TOO_LOW" }
        val recentSuccessCount = recentMeals.count { it.outcome == "SUCCESS" }
        val totalRecentMeals = recentMeals.size

        if (recentHypoCount == 0) {
            // ★★★ GEEN HYPO'S - geleidelijk herstel gebaseerd op minRecoveryDays ★★★
            val recoverySpeed = when (MIN_RECOVERY_DAYS) {
                1 -> 0.95  // Snel herstel bij 1 dag
                2 -> 0.90  // Normaal herstel bij 2 dagen
                3 -> 0.85  // Langzaam herstel bij 3 dagen
                else -> 0.8 // Zeer langzaam bij 4+ dagen
            }

            val baseReduction = when (totalRecentMeals) {
                0 -> 1.0  // Geen data, geen aanpassing
                1 -> recoverySpeed // Eerste succes
                2 -> recoverySpeed * 0.95 // Tweede succes
                3 -> recoverySpeed * 0.90 // Derde succes
                else -> recoverySpeed * 0.85 // Verdere successen
            }

            // Sneller herstel bij consistente successen
            val consecutiveSuccessBonus = if (recentSuccessCount >= MIN_RECOVERY_DAYS) 0.95 else 1.0
            val recoveryFactor = baseReduction * consecutiveSuccessBonus

            val adjustedFactor = baseFactor * recoveryFactor


            return adjustedFactor.coerceIn(0.7, 1.3)
        }

        // ★★★ HYPO GEDETECTEERD - reductie toepassen ★★★
        val reductionFactor = when (recentHypoCount) {
            1 -> 0.9   // 10% reductie
            2 -> 0.8   // 20% reductie
            3 -> 0.7   // 30% reductie
            else -> 0.6 // 40% reductie bij 4+ hypo's
        }

        // Combineer met time-based recovery voor geleidelijk herstel
        val timeRecovery = getTimeBasedRecoveryFactor(mealType, hour)
        val performanceRecovery = getPerformanceBasedRecovery(mealType)
        val aggressivenessRecovery = HYPO_RECOVERY_AGGRESSIVENESS

        // Neem de meest conservatieve (laagste) recovery factor
        val overallRecovery = min(timeRecovery, min(performanceRecovery, aggressivenessRecovery))

        val finalFactor = baseFactor * reductionFactor * overallRecovery


        return finalFactor.coerceIn(0.5, 1.5)
    }

    private fun getEffectiveCarbRatio(): Double {
        val base = currentCR * learningProfile.personalCarbRatio
        return base / resistanceHelper.getCurrentResistanceFactor() // ★★★ DELEN door resistentie factor ★★★
    }

    private fun getEffectiveISF(): Double {
        val baseISF = currentISF * learningProfile.personalISF
        // ★★★ GEBRUIK OPGESLAGEN STAPPENPERCENTAGE - ZONDER OVERBODIGE CHECK ★★★
        return (baseISF / (currentStappenPercentage / 100.0)) / resistanceHelper.getCurrentResistanceFactor()
    }
    private fun getEffectiveTarget(): Double {
        // ★★★ GEBRUIK OPGESLAGEN STAPPENTARGET - ZONDER OVERBODIGE CHECK ★★★
        return Target_Bg + currentStappenTargetAdjust
    }


    private fun getSafeDoseWithLearning(
        calculatedDose: Double,
        learnedDose: Double?,
        confidence: Double,
        currentIOB: Double,
        trends: TrendAnalysis,
        phase: String = "stable", // ← Deze wordt nu altijd de robuuste fase
        MaxIOB: Double
    ): Double {
        val base = when {
            confidence > 0.8 -> learnedDose ?: calculatedDose
            confidence > 0.6 -> (learnedDose ?: calculatedDose) * 0.85
            else -> calculatedDose * 0.7
        }

        // ★★★ ALGEMENE AGGRESSIVITEIT TOEPASSEN ★★★
        val overallAggressiveness = getCurrentBolusAggressiveness() / 100.0
        val phaseFactor = getPhaseSpecificAggressiveness(phase) // ← Gebruik robuuste fase

        val iobFactor = when {
            currentIOB > MaxIOB * 0.5 -> 0.45
            currentIOB > MaxIOB * 0.25 -> 0.7
            else -> 1.0
        }

        val accelPenalty = if (trends.acceleration > 1.0) 1.1 else 1.0
        val trendPenalty = if (trends.recentTrend > 2.5) 0.95 else 1.0

        return (base * iobFactor / accelPenalty * trendPenalty * phaseFactor * overallAggressiveness).coerceAtLeast(0.0)
    }


    fun getCarbsOnBoard(): Double {
        val now = DateTime.now()
        return activeMeals.sumOf { it.getRemainingCarbs(now) }
    }
    private fun cleanUpMeals() {
        val now = DateTime.now()
        activeMeals.removeIf { it.getRemainingCarbs(now) < 0.1 }
    }


    // ★★★ NIEUWE FUNCTIE VOOR COB MANAGEMENT ★★★
    private fun addOrUpdateActiveMeal(detectedCarbs: Double, timestamp: DateTime) {
        val now = DateTime.now()
        cleanUpMeals()

        // ★★★ VERWIJDER DE 30-MINUTEN RESTRICTIE ★★★
        val recentMeal = activeMeals.firstOrNull {
            Minutes.minutesBetween(it.timestamp, now).minutes < preferences.get(IntKey.tau_absorption_minutes)
        }

        if (recentMeal == null) {
            // Nieuwe maaltijd toevoegen
            val newMeal = ActiveCarbs(
                timestamp = timestamp,
                totalCarbs = detectedCarbs,
                tau = preferences.get(IntKey.tau_absorption_minutes).toDouble()
            )
            activeMeals.add(newMeal)
            lastCOBDebug = "NEW_MEAL: ${detectedCarbs}g at ${timestamp.toString("HH:mm")}"

            // ★★★ DIRECT COB OPSLAAN ★★★
            storage.saveCurrentCOB(detectedCarbs)
        } else {
            // Bestaande maaltijd bijwerken
            val oldCarbs = recentMeal.totalCarbs
            recentMeal.totalCarbs = max(oldCarbs, detectedCarbs)
            lastCOBDebug = "UPDATE_MEAL: ${oldCarbs}g -> ${recentMeal.totalCarbs}g"

            // ★★★ DIRECT COB OPSLAAN ★★★
            val currentCOB = getCarbsOnBoard()
            storage.saveCurrentCOB(currentCOB)
        }

        val currentCOB = getCarbsOnBoard()
        lastCOBDebug += " | TOTAL_COB: ${currentCOB}g, ACTIVE_MEALS: ${activeMeals.size}"

        // ★★★ FORCEER COB OPSLAG ★★★
        storage.saveCurrentCOB(currentCOB)
    }


    private fun getMealTypeFromHour(): String {
        val hour = DateTime.now().hourOfDay
        return when (hour) {
            in 6..10 -> "breakfast"
            in 11..14 -> "lunch"
            in 17..21 -> "dinner"
            else -> "other"
        }
    }

    // Trend analysis functions
    private fun analyzeTrends(data: List<BGDataPoint>): TrendAnalysis {
        if (data.isEmpty()) return TrendAnalysis(0.0, 0.0, 0.0)

        // Gebruik smoothing voor trendcalculatie
        val smoothed = smoothBGSeries(data, alpha = 0.35)
        // Bouw een tijdelijk BGDataPoint-list met smoothed values maar behoud timestamps
        val smoothPoints = smoothed.map { (ts, bg) -> BGDataPoint(timestamp = ts, bg = bg, iob = data.find { it.timestamp == ts }?.iob ?: 0.0) }

        val recentTrend = calculateRecentTrend(smoothPoints, 4)  // Langere termijn trend
        val shortTermTrend = calculateShortTermTrend(smoothPoints)  // Nieuwe korte-termijn functie
        val acceleration = calculateAcceleration(smoothPoints, 3)

        // Store peak-detection data only on meaningful events to reduce noise
        val lastPeakSave = storage.loadPeakDetectionData().lastOrNull()
        val shouldSave = lastPeakSave == null || Minutes.minutesBetween(lastPeakSave.timestamp, data.last().timestamp).minutes >= 5 || acceleration < -0.5
        if (shouldSave) {
            storePeakDetectionData(data.last(), TrendAnalysis(recentTrend, shortTermTrend, acceleration))
            // probeer meteen pending learning updates te matchen met nieuw opgeslagen peak data
            try {
                detectPeaksFromHistoricalData()
            } catch (ex: Exception) {

            }
        }


        return TrendAnalysis(recentTrend, shortTermTrend, acceleration)
    }


    private fun smoothBGSeries(data: List<BGDataPoint>, alpha: Double = 0.3): List<Pair<DateTime, Double>> {
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


    // Detecteer sensorfouten inclusief compression lows.
   // Geeft null terug als er geen fout is.
    private fun detectSensorIssue(historicalData: List<BGDataPoint>): SensorIssueType? {
        if (historicalData.size < 3) return null

        // --- Grote sprongen ---
        val recent3 = historicalData.takeLast(3)
        val d1 = recent3[1].bg - recent3[0].bg
        val d2 = recent3[2].bg - recent3[1].bg
        if (abs(d1) > 3.0 || abs(d2) > 3.0) {

            return SensorIssueType.JUMP_TOO_LARGE
        }

        // --- Oscillaties ---
        val oscillation = (
            (recent3[0].bg < recent3[1].bg && recent3[2].bg < recent3[1].bg) || // piek
                (recent3[0].bg > recent3[1].bg && recent3[2].bg > recent3[1].bg)    // dal
            ) && (abs(d1) >= 0.5 && abs(d2) >= 0.5)

        if (oscillation) {

            return SensorIssueType.OSCILLATION
        }

        // --- Compression lows ---
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


    // ★★★ NIEUW: Meal pattern validation ★★★
    private fun validateMealPattern(historicalData: List<BGDataPoint>): MealConfidenceLevel {
        if (historicalData.size < 6) return MealConfidenceLevel.SUSPECTED

        val recent = historicalData.takeLast(6)

        // Check op consistent stijgend patroon (minimaal 4 van 5 metingen)
        val risingCount = recent.zipWithNext { a, b ->
            b.bg > a.bg + 0.1
        }.count { it }

        // Check op geleidelijke stijging (lage variantie in slopes)
        val slopes = recent.zipWithNext { a, b ->
            val minutesDiff = Minutes.minutesBetween(a.timestamp, b.timestamp).minutes.toDouble()
            if (minutesDiff > 0) (b.bg - a.bg) / minutesDiff * 60.0 else 0.0 // mmol/L per uur
        }

        val slopeVariance = if (slopes.size > 1) {
            val average = slopes.average()
            slopes.map { (it - average) * (it - average) }.average()
        } else 0.0

        // Bepaal confidence level
        return when {
            risingCount >= 4 && slopeVariance < 0.1 -> {

                MealConfidenceLevel.HIGH_CONFIDENCE
            }
            risingCount >= 3 -> {

                MealConfidenceLevel.CONFIRMED
            }
            else -> {

                MealConfidenceLevel.SUSPECTED
            }
        }
    }

    // ★★★ NIEUWE FUNCTIES VOOR BETERE MEAL DETECTION ★★★
    private fun hasSustainedRisePattern(historicalData: List<BGDataPoint>): Boolean {
        if (historicalData.size < 6) return false

        val recent = historicalData.takeLast(6)
        val risingCount = recent.zipWithNext { a, b ->
            b.bg > a.bg + 0.1
        }.count { it }

        // Minimaal 4 van 5 metingen stijgend
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
        // Gebruik dezelfde logica als calculatePeakConfidenceForMeal maar voor recente data
        if (historicalData.size < 6) return 0.5

        val recentData = historicalData.takeLast(6)
        val rises = recentData.zipWithNext { a, b -> b.bg - a.bg }
        val risingCount = rises.count { it > 0.1 }
        val riseConsistency = risingCount.toDouble() / rises.size

        val riseVariance = calculateVariance(rises)

        // ZELFDE CONFIDENCE BEREKENING ALS calculatePeakConfidenceForMeal
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
        var confidence = 0.3 // Lagere baseline

        if (historicalData.size < 4) return if (detectedCarbs > 20) 0.4 else 0.2

        val recent = historicalData.takeLast(4)
        val totalRise = recent.last().bg - recent.first().bg

// Strengere voorwaarden voor hoge confidence
        val hasQuickRise = hasRecentRise(historicalData, 2)
        val hasStrongRise = totalRise > 2.0
        val hasConsistentPattern = hasSustainedRisePattern(historicalData)

        if (hasConsistentPattern && hasStrongRise) {
            confidence += 0.4
        } else if (hasQuickRise && totalRise > 1.0) {
            confidence += 0.2
        }

// Hogere carbs vereist voor meer confidence
        if (detectedCarbs > 25) {
            confidence += 0.2
        } else if (detectedCarbs > 15) {
            confidence += 0.1
        }

        return confidence.coerceIn(0.0, 0.8)  // Max 80% confidence
    }


    // ★★★ VERBETERDE SNACK/MEAL DETECTION ★★★
    private fun distinguishMealFromSnack(
        historicalData: List<BGDataPoint>,
        detectedCarbs: Double
    ): Boolean {
        if (historicalData.size < 4) return detectedCarbs > 20 // Bij twijfel, conservatief

        val recent = historicalData.takeLast(4)
        val totalRise = recent.last().bg - recent.first().bg

        // Verbeterde detectie: focus op consistentie i.p.v. absolute grenzen
        val rises = recent.zipWithNext { a, b -> b.bg - a.bg }
        val consistentRise = rises.all { it > 0.1 } // Alle metingen stijgend
        val riseVariance = if (rises.size > 1) calculateVariance(rises) else 1.0

        // Nieuwe, slimmere heuristiek:
        val isLikelyMeal = when {
            // Duidelijke maaltijd: substantiële carbs + consistente stijging
            detectedCarbs > 30 && consistentRise && totalRise > 1.5 -> {
                true
            }
            // Waarschijnlijke maaltijd: matige carbs + lage variantie in stijging
            detectedCarbs > 20 && riseVariance < 0.1 && totalRise > 1.0 -> {
                true
            }
            // Twijfelgeval: gebruik trendanalyse voor betere beslissing
            detectedCarbs > 15 && hasSustainedRisePattern(historicalData) -> {
                true
            }
            // Waarschijnlijk snack: kleine hoeveelheid + onregelmatige stijging
            else -> {
                false
            }
        }
        return isLikelyMeal
    }



    // ★★★ NIEUWE VERBETERDE CONSISTENCY BEREKENING ★★★
    private fun calculateEnhancedConsistency(data: List<BGDataPoint>): Double {
        if (data.size < 4) return 0.0

        val slopes = mutableListOf<Double>()

        // Bereken slopes tussen opeenvolgende punten
        for (i in 1 until data.size) {
            val timeDiff = Minutes.minutesBetween(data[i-1].timestamp, data[i].timestamp).minutes / 60.0
            if (timeDiff > 0) {
                slopes.add((data[i].bg - data[i-1].bg) / timeDiff)
            }
        }

        if (slopes.size < 2) return 0.0

        // Drie componenten voor consistency
        val directionConsistency = calculateDirectionConsistency(slopes)
        val magnitudeConsistency = calculateMagnitudeConsistency(slopes)
        val patternConsistency = calculatePatternConsistency(data)

        // Gewogen gemiddelde
        return (directionConsistency * 0.5 + magnitudeConsistency * 0.3 + patternConsistency * 0.2)
    }

    private fun calculateDirectionConsistency(slopes: List<Double>): Double {
        if (slopes.isEmpty()) return 0.0

        // Tel hoeveel slopes dezelfde richting hebben (boven noise threshold)
        val positiveSlopes = slopes.count { it > 0.05 }  // > 0.05 mmol/L/u om noise te filteren
        val negativeSlopes = slopes.count { it < -0.05 }
        val totalValidSlopes = positiveSlopes + negativeSlopes

        if (totalValidSlopes == 0) return 0.0

        // Consistentie gebaseerd op dominantie van één richting
        val maxDirection = max(positiveSlopes, negativeSlopes)
        return maxDirection.toDouble() / totalValidSlopes
    }

    private fun calculateMagnitudeConsistency(slopes: List<Double>): Double {
        if (slopes.size < 2) return 0.0

        // Filter near-zero slopes voor magnitude analyse
        val significantSlopes = slopes.filter { abs(it) > 0.05 }
        if (significantSlopes.size < 2) return 0.0

        val mean = significantSlopes.average()
        val variance = significantSlopes.map { (it - mean) * (it - mean) }.average()

        // Hoe lager de variantie, hoe hoger de consistentie
        return exp(-variance * 5.0).coerceIn(0.0, 1.0)
    }

    private fun calculatePatternConsistency(data: List<BGDataPoint>): Double {
        if (data.size < 4) return 0.0

        // Check op monotone stijging/daling (sterk consistent patroon)
        val isMonotonicRise = data.zipWithNext().all { (a, b) -> b.bg >= a.bg - 0.1 }
        val isMonotonicFall = data.zipWithNext().all { (a, b) -> b.bg <= a.bg + 0.1 }

        if (isMonotonicRise || isMonotonicFall) return 0.9

        // Check op concave/convexe patronen
        val secondDifferences = mutableListOf<Double>()
        for (i in 1 until data.size - 1) {
            val firstDiff = data[i].bg - data[i-1].bg
            val secondDiff = data[i+1].bg - data[i].bg
            secondDifferences.add(secondDiff - firstDiff)
        }

        // Consistentie van versnelling/vertraging
        val consistentAcceleration = secondDifferences.all { it > -0.1 && it < 0.1 }
        if (consistentAcceleration && secondDifferences.isNotEmpty()) return 0.7

        return 0.3
    }

    private fun calculateMagnitudeConsistencyFromData(data: List<BGDataPoint>): Double {
        val slopes = mutableListOf<Double>()
        for (i in 1 until data.size) {
            val timeDiff = Minutes.minutesBetween(data[i-1].timestamp, data[i].timestamp).minutes / 60.0
            if (timeDiff > 0) slopes.add((data[i].bg - data[i-1].bg) / timeDiff)
        }
        return calculateMagnitudeConsistency(slopes)
    }


    // Hulpfuncties voor debugging
    private fun calculateDirectionConsistencyFromData(data: List<BGDataPoint>): Double {
        val slopes = mutableListOf<Double>()
        for (i in 1 until data.size) {
            val timeDiff = Minutes.minutesBetween(data[i-1].timestamp, data[i].timestamp).minutes / 60.0
            if (timeDiff > 0) slopes.add((data[i].bg - data[i-1].bg) / timeDiff)
        }
        return calculateDirectionConsistency(slopes)
    }


    // ★★★ GEWOGEN EERSTE AFGELEIDE ★★★
    private fun calculateWeightedFirstDerivative(smoothedData: List<Pair<DateTime, Double>>): Double {
        if (smoothedData.size < 3) return calculateSimpleFirstDerivative(smoothedData)

        // Gebruik laatste 3 punten voor betrouwbaarheid
        val slopes = mutableListOf<Double>()
        val weights = mutableListOf<Double>()

        for (i in 1 until smoothedData.size) {
            val timeDiff = Minutes.minutesBetween(smoothedData[i-1].first, smoothedData[i].first).minutes / 60.0
            if (timeDiff > 0) {
                slopes.add((smoothedData[i].second - smoothedData[i-1].second) / timeDiff)
                weights.add(1.0 / (1.0 + (smoothedData.size - 1 - i))) // Meer gewicht voor recentere
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

        // ★★★ FASEN MET DYNAMISCHE DREMPELS ★★★
        val earlyRiseSlope = preferences.get(DoubleKey.phase_early_rise_slope)
        val midRiseSlope = preferences.get(DoubleKey.phase_mid_rise_slope)
        val lateRiseSlope = preferences.get(DoubleKey.phase_late_rise_slope)

        // ★★★ BEPAAL FASE OP BASIS VAN MEERDERE FACTOREN ★★★
        val proposedPhase = when {
            // Dalende trend heeft voorrang
            currentSlope < -1.0 -> "declining"
            currentSlope < -0.3 -> "declining"

            // Vroege detectie: lagere drempel bij consistente stijging
            hasConsistentRise(slopes, 3) && currentSlope > earlyRiseSlope * 0.7 -> "early_rise"
            currentSlope > earlyRiseSlope -> "early_rise"

            // Mid-rise: houd rekening met versnelling
            currentSlope > midRiseSlope && robustTrends.secondDerivative > 0.1 -> "mid_rise"
            currentSlope > midRiseSlope -> "mid_rise"

            // Late-rise: alleen bij duidelijke stijging
            currentSlope > lateRiseSlope -> "late_rise"

            else -> "stable"
        }

        // ★★★ FASEOVERGANG MET HYSTERESE EN GEMIDDELDE ★★★
        val (finalPhase, transitionFactor) = calculatePhaseTransition(
            currentPhase = previousPhase,
            proposedPhase = proposedPhase,
            currentSlope = currentSlope,
            slopes = slopes
        )

        val debugInfo = "Phase: $previousPhase → $proposedPhase → $finalPhase (factor: ${"%.2f".format(transitionFactor)})"

        return PhaseTransitionResult(finalPhase, transitionFactor, debugInfo)
    }

    // ★★★ ROBUUSTE TWEEDE AFGELEIDE ★★★
    private fun calculateRobustSecondDerivative(smoothedData: List<Pair<DateTime, Double>>): Double {
        if (smoothedData.size < 4) return 0.0

        // Eenvoudige maar robuuste tweede afgeleide
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

            // Gemiddelde versnelling
            return ((slope2 - slope1) + (slope3 - slope2)) / 2.0
        }
        return 0.0
    }
    private fun calculateRobustTrends(historicalData: List<BGDataPoint>): RobustTrendAnalysis {
        if (historicalData.size < 5) {
            val result = RobustTrendAnalysis(0.0, 0.0, 0.0, "uncertain")
            lastRobustTrends = result
            return result
        }

        // ★★★ BEWAAR DATA VOOR LATER GEBRUIK ★★★
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

        // ★★★ VERBETERDE AFGELEIDEN ★★★
        val firstDerivative = calculateWeightedFirstDerivative(smoothed)
        val secondDerivative = calculateRobustSecondDerivative(smoothed)

        // ★★★ NIEUWE CONSISTENCY BEREKENING ★★★
        val consistency = calculateEnhancedConsistency(recentDataForAnalysis)

        // ★★★ VERBETERDE FASEDETECTIE ★★★
        val phaseTransition = calculateEnhancedPhaseDetection(
            robustTrends = RobustTrendAnalysis(firstDerivative, secondDerivative, consistency, "stable"),
            historicalData = historicalData,
            currentBG = recentDataForAnalysis.last().bg,
            previousPhase = lastRobustTrends?.phase ?: "stable"
        )

        val phase = phaseTransition.phase

        val result = RobustTrendAnalysis(firstDerivative, secondDerivative, consistency, phase)
        lastRobustTrends = result

        // ★★★ TRANSITIE FACTOR OPSLAAN VOOR BOLUS BEREKENING ★★★
        lastPhaseTransitionFactor = phaseTransition.transitionFactor

        return result
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

        // ★★★ IOB-BASED AGGRESSIVENESS REDUCTION ★★★
        val iobRatio = currentIOB / maxIOB
        val IOB_safety_perc = preferences.get(IntKey.IOB_corr_perc)
        val iobAggressivenessFactor = when {
            iobRatio > 0.8 -> 0.2 * (IOB_safety_perc / 100.0)
            iobRatio > 0.6 -> 0.4 * (IOB_safety_perc / 100.0)
            iobRatio > 0.4 -> 0.6 * (IOB_safety_perc / 100.0)
            iobRatio > 0.2 -> 0.8 * (IOB_safety_perc / 100.0)
            else -> 1.0 * (IOB_safety_perc / 100.0)
        }

        // ★★★ ABSOLUTE IOB BLOKKADE ★★★
        if (shouldBlockMathematicalBolusForHighIOB(currentIOB, maxIOB, robustTrends, detectedCarbs > 0)) {
            return MathematicalBolusAdvice(
                immediatePercentage = 0.0,
                reservedPercentage = 0.0,
                reason = "Math: Blocked due to high IOB (${"%.1f".format(currentIOB)}U)"
            )
        }

        // ★★★ TRANSITIE FACTOR TOEPASSEN ★★★
        val transitionFactor = lastPhaseTransitionFactor

        // ★★★ BASIS PERCENTAGES MET TRANSITIE FACTOR ★★★
        val baseEarlyPerc = (preferences.get(IntKey.bolus_perc_early).toDouble() / 100.0) * transitionFactor * iobAggressivenessFactor
        val baseMidPerc = (preferences.get(IntKey.bolus_perc_mid).toDouble() / 100.0) * transitionFactor * iobAggressivenessFactor
        val baseLatePerc = (preferences.get(IntKey.bolus_perc_late).toDouble() / 100.0) * transitionFactor * iobAggressivenessFactor

        // ★★★ NIEUW: CONSISTENCY-BASED SCALING ★★★
        val consistencyFactor = calculateConsistencyBasedScaling(robustTrends.consistency)

        // ★★★ PAS CONSISTENCY FACTOR TOE ★★★
        val consistentEarlyPerc = baseEarlyPerc * consistencyFactor
        val consistentMidPerc = baseMidPerc * consistencyFactor
        val consistentLatePerc = baseLatePerc * consistencyFactor

        // ★★★ ALGEMENE AGGRESSIVITEIT (DAG/NACHT) ★★★
        val overallAggressiveness = getCurrentBolusAggressiveness() / 100.0

        // ★★★ COMBINATIE: consistent percentages × algemene agressiviteit ★★★
        val combinedEarlyPerc = consistentEarlyPerc * overallAggressiveness
        val combinedMidPerc = consistentMidPerc * overallAggressiveness
        val combinedLatePerc = consistentLatePerc * overallAggressiveness

        // ★★★ DYNAMISCHE CORRECTIES ★★★
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

        // ★★★ TOTALE DYNAMISCHE FACTOR ★★★
        val totalDynamicFactor = trendFactor * safetyFactor * confidenceFactor

        return when (robustTrends.phase) {

            "early_rise" -> {
                // ★★★ BOOST VOOR VROEGE FASE ★★★
                val earlyRiseBoost = 1.3  // 30% boost voor vroege detectie
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

            "peak" -> {
                MathematicalBolusAdvice(
                    immediatePercentage = 0.0,
                    reservedPercentage = 0.0,
                    reason = "Math: Peak"
                )
            }

            "declining" -> {
                MathematicalBolusAdvice(
                    immediatePercentage = 0.0,
                    reservedPercentage = 0.0,
                    reason = "Math: Declining"
                )
            }

            "stable" -> {
                MathematicalBolusAdvice(
                    immediatePercentage = 0.0,
                    reservedPercentage = 0.0,
                    reason = "Math: Stable"
                )
            }

            else -> {
                MathematicalBolusAdvice(
                    immediatePercentage = 0.0,
                    reservedPercentage = 0.0,
                    reason = "Math: ${robustTrends.phase}"
                )
            }
        }
    }

    // ★★★ NIEUWE FUNCTIE: CONSISTENCY-BASED SCALING ★★★
    private fun calculateConsistencyBasedScaling(consistency: Double): Double {
        return when {
            consistency > 0.8 -> 1.0   // Hoge consistentie: 100%
            consistency > 0.6 -> 0.8   // Matige consistentie: 80%
            consistency > 0.4 -> 0.6   // Lage consistentie: 60%
            consistency > 0.2 -> 0.4   // Zeer lage consistentie: 40%
            else -> 0.2                // Minimale consistentie: 20%
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
    ): Triple<Double, Double, String> { // Returns (immediateBolus, reservedBolus, reason)

        // Gebruik de BESTAANDE uitgebreide wiskundige methode
        val mathAdvice = getMathematicalBolusAdvice(
            robustTrends = robustTrends,
            detectedCarbs = detectedCarbs,
            currentBG = currentBG,
            targetBG = targetBG,
            historicalData = historicalData,
            currentIOB = currentIOB,
            maxIOB = maxIOB
        )

        // Bereken totale bolus op basis van carbs
        val totalCarbBolus = detectedCarbs / effectiveCR

        // Gebruik de wiskundige percentages voor splitsing
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

        // Gebruik dezelfde veiligheidslogica als de meal methode
        val mathAdvice = getMathematicalBolusAdvice(
            robustTrends = robustTrends,
            detectedCarbs = 0.0, // Geen carbs voor correctie
            currentBG = currentBG,
            targetBG = targetBG,
            historicalData = listOf(), // Lege historical data voor correctie
            currentIOB = currentIOB,
            maxIOB = maxIOB
        )

        // Bereken correctie dose
        val bgAboveTarget = currentBG - targetBG
        val requiredCorrection = bgAboveTarget / effectiveISF

        // Pas wiskundige percentages toe op correctie
        return requiredCorrection * mathAdvice.immediatePercentage
    }


    private fun shouldBlockMathematicalBolusForHighIOB(
        currentIOB: Double,
        maxIOB: Double,
        robustTrends: RobustTrendAnalysis,
        mealDetected: Boolean = false
    ): Boolean {
        if (mealDetected) {
            return when {
                currentIOB > maxIOB * 0.95 -> true                    // Alleen bij echte max
                currentIOB > maxIOB * 0.85 && robustTrends.firstDerivative < 0.5 -> true    //0.1
                currentIOB > maxIOB * 0.75 && robustTrends.firstDerivative < 0.0 -> true   //-0.5
                else -> false
            }
        } else {
            return when {
                currentIOB > maxIOB * 0.95 -> true                    // Alleen bij echte max
                currentIOB > maxIOB * 0.85 && robustTrends.firstDerivative < 1.0 -> true    //0.5
                currentIOB > maxIOB * 0.75 && robustTrends.firstDerivative < 0.5 -> true    //0.0
                else -> false
            }
        }
    }

    // ★★★ VERBETERDE FASEOVERGANG BEREKENING ★★★
    private fun calculateBackwardTransitionFactor(
        fromPhase: String,
        toPhase: String,
        currentSlope: Double,
        slopeConsistency: Double
    ): Double {
        // Basis percentages uit preferences
        val fromPercentage = getPhasePercentage(fromPhase)
        val toPercentage = getPhasePercentage(toPhase)

        // Gemiddelde van de twee percentages
        val averagePercentage = (fromPercentage + toPercentage) / 2.0

        // Pas aan op basis van slope consistentie
        val consistencyFactor = 0.5 + (slopeConsistency * 0.5) // 0.5-1.0 range

        return averagePercentage * consistencyFactor
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

        // ★★★ GEEN VERANDERING ★★★
        if (currentPhase == proposedPhase) {
            return Pair(currentPhase, 1.0)
        }

        // ★★★ VOORUITGANG IN FASEN ★★★
        if (proposedIndex > currentIndex) {
            return Pair(proposedPhase, 1.0)
        }

        // ★★★ TERUGGANG IN FASEN - GEMIDDELDE PERCENTAGES ★★★
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

    // ★★★ DYNAMISCHE FACTOREN BEREKENING ★★★
    private fun calculateDynamicFactors(
        robustTrends: RobustTrendAnalysis,
        currentBG: Double,
        targetBG: Double,
        historicalData: List<BGDataPoint>,
        currentIOB: Double,  // ★★★ NIEUW PARAMETERS ★★★
        maxIOB: Double
    ): DynamicFactors {

        val trendFactor = calculateTrendFactor(robustTrends.firstDerivative)

        // ★★★ VEILIGHEIDSFACTOR MET IOB ★★★
        val safetyFactor = calculateSafetyFactorWithIOB(
            currentBG, targetBG, historicalData, currentIOB, maxIOB
        )

        val confidenceFactor = calculateConfidenceFactor(robustTrends.consistency, historicalData)

        return DynamicFactors(trendFactor, safetyFactor, confidenceFactor)
    }

    private fun calculateSafetyFactorWithIOB(
        currentBG: Double,
        targetBG: Double,
        historicalData: List<BGDataPoint>,
        currentIOB: Double,
        maxIOB: Double
    ): Double {
        val bgAboveTarget = currentBG - targetBG
        val iobRatio = currentIOB / maxIOB

        val IOB_safety_perc = preferences.get(IntKey.IOB_corr_perc)

        // ★★★ MILDER IOB BELEID ★★★
        val iobPenalty = when {
            iobRatio > 0.8 -> 0.6 * (IOB_safety_perc / 100.0)  // was 0.4
            iobRatio > 0.6 -> 0.75 * (IOB_safety_perc / 100.0) // was 0.5
            iobRatio > 0.4 -> 0.85 * (IOB_safety_perc / 100.0) // was 0.6
            iobRatio > 0.2 -> 0.9 * (IOB_safety_perc / 100.0)  // was 0.7
            else -> 1.0 * (IOB_safety_perc / 100.0)
        }

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

    private fun calculateTrendFactor(slope: Double): Double {
        // ★★★ TREND FACTOR: 0.8 bij lage trend, 1.2 bij hoge trend ★★★
        return when {
            slope > 3.0 -> 1.2  // Zeer sterke stijging
            slope > 2.0 -> 1.1  // Sterke stijging
            slope > 1.0 -> 1.0  // Normale stijging
            slope > 0.5 -> 0.9  // Lichte stijging
            else -> 0.8         // Zeer lichte stijging
        }
    }

    private fun calculateConfidenceFactor(consistency: Double, historicalData: List<BGDataPoint>): Double {
        // ★★★ CONFIDENCE FACTOR: betrouwbaarheid van de faseherkenning ★★★
        val dataPoints = historicalData.takeLast(6).size
        val dataFactor = if (dataPoints >= 4) 1.0 else 0.7

        return (consistency * dataFactor).coerceIn(0.5, 1.0)
    }

    private fun calculateVolatility(data: List<BGDataPoint>): Double {
        if (data.size < 2) return 0.0
        val changes = data.zipWithNext().map { (a, b) -> abs(b.bg - a.bg) }
        return changes.average()
    }


    // ★★★ DATA CLASS VOOR DYNAMISCHE FACTOREN ★★★
    private data class DynamicFactors(
        val trendFactor: Double,
        val safetyFactor: Double,
        val confidenceFactor: Double
    )


    // ★★★ NIEUW: Real-time bijsturing ★★★
    private fun shouldAdjustOrCancelBolus(
        historicalData: List<BGDataPoint>,
        initialDetection: MealDetectionState
    ): Boolean {
        if (historicalData.size < 4) return false

        val recent = historicalData.takeLast(4)

        // ★★★ GEBRUIK NIEUWE FUNCTIE ★★★
        val isStillRising = hasRecentRise(historicalData, 1) // Minimaal 1 stijgende meting

        // Stijging stopt of keert om - alleen aanpassen als NIET meer stijgend
        val plateauOrDecline = recent.zipWithNext { a, b ->
            b.bg <= a.bg + 0.1
        }.count { it } >= 2 && !isStillRising  // ★★★ ONLY adjust if NOT still rising

        // Onverwachte daling na initiële stijging
        val unexpectedDrop = recent.last().bg < recent[recent.size - 2].bg - 0.5
        val shouldAdjust = plateauOrDecline || unexpectedDrop
        if (shouldAdjust) {

        }
        return shouldAdjust
    }


    private fun calculateRecentTrend(data: List<BGDataPoint>, pointsBack: Int): Double {
        if (data.size <= pointsBack) return 0.0

        // Gebruik echte tijdverschillen i.p.v. aannames over interval
        val currentIndex = data.lastIndex
        val pastIndex = max(0, currentIndex - pointsBack)

        val currentPoint = data[currentIndex]
        val pastPoint = data[pastIndex]

        val timeDiffMinutes = Minutes.minutesBetween(pastPoint.timestamp, currentPoint.timestamp).minutes
        val timeDiffHours = timeDiffMinutes / 60.0
        if (timeDiffHours <= 0) return 0.0

        val bgDiff = currentPoint.bg - pastPoint.bg
        return bgDiff / timeDiffHours  // mmol/L per uur
    }

    // Specifieke functie voor korte-termijn trend (15-20 minuten)
    private fun calculateShortTermTrend(data: List<BGDataPoint>): Double {
        if (data.size < 4) return 0.0

        val current = data.last()

        // Zoek het punt ongeveer 15-20 minuten geleden
        val twentyMinAgo = current.timestamp.minusMinutes(20)
        val fifteenMinAgo = current.timestamp.minusMinutes(15)

        // Zoek het dichtstbijzijnde punt in dit tijdvenster
        val pastPoint = data.findLast {
            it.timestamp.isAfter(twentyMinAgo) && it.timestamp.isBefore(fifteenMinAgo)
        } ?: data.findLast {
            it.timestamp.isBefore(current.timestamp.minusMinutes(10))
        } ?: return 0.0

        val timeDiffMinutes = Minutes.minutesBetween(pastPoint.timestamp, current.timestamp).minutes
        val timeDiffHours = timeDiffMinutes / 60.0
        if (timeDiffHours <= 0) return 0.0

        val bgDiff = current.bg - pastPoint.bg
        return bgDiff / timeDiffHours  // mmol/L per uur
    }

    // Check op consistente daling over recente metingen
    private fun checkConsistentDecline(data: List<BGDataPoint>): Boolean {
        if (data.size < 3) return false

        val recentPoints = data.takeLast(3)
        var declineCount = 0

        for (i in 0 until recentPoints.size - 1) {
            if (recentPoints[i + 1].bg < recentPoints[i].bg - 0.1) {
                declineCount++
            }
        }

        return declineCount >= 2  // Minimaal 2 van de laatste 3 metingen dalend
    }

    private fun shouldBlockBolusForShortTermTrend(
        currentData: BGDataPoint,
        historicalData: List<BGDataPoint>,
        trends: TrendAnalysis,
        maxIOB: Double
    ): Boolean {
        if (historicalData.size < 4) return false

        val iobRatio = currentData.iob / maxIOB

        // Korte-termijn trend (laatste 15-20 minuten)
        val shortTermTrend = calculateShortTermTrend(historicalData)

        // Check op consistente daling
        val isConsistentDecline = checkConsistentDecline(historicalData)

        return when {
            // Sterke daling in korte termijn
            shortTermTrend < -3.0 -> true
            // Matige daling + consistente daling over metingen
            shortTermTrend < -2.0 && isConsistentDecline -> true
            // Dalende trend + hoge IOB
            shortTermTrend < -1.0 && iobRatio > 0.5 -> true
            // Zeer consistente daling (3 van 3 metingen dalend)
            isConsistentDecline && shortTermTrend < -0.5 -> true
            else -> false
        }
    }

    // ★★★ HYPO RECOVERY DETECTION ★★★
    private fun isLikelyHypoRecovery(
        currentBG: Double,
        historicalData: List<BGDataPoint>,
        trends: TrendAnalysis
    ): Boolean {
        if (historicalData.size < 4) return false

        // ★★★ STRENGERE HYPO DEFINITIE: Alleen bij echte hypo's (<4.0) ★★★
        val recentHypo = historicalData.any {
            it.bg < gethypoThreshold() &&
                Minutes.minutesBetween(it.timestamp, DateTime.now()).minutes <= HYPO_RECOVERY_MINUTES
        }

        if (!recentHypo) return false

        // ★★★ DYNAMISCHE BEREKENING HERSTEL RANGE ★★★
        val recoveryRangeMin = gethypoThreshold()
        val recoveryRangeMax = gethypoThreshold() + HYPO_RECOVERY_BG_RANGE
        val isInRecoveryPhase = currentBG in recoveryRangeMin..recoveryRangeMax

        val hasRapidRise = hasRapidRisePatternFromLow(historicalData)
        val isStableHighBG = currentBG > recoveryRangeMax && trends.recentTrend < 1.0

        return recentHypo && isInRecoveryPhase && hasRapidRise && !isStableHighBG
    }

    private fun hasRapidRisePatternFromLow(historicalData: List<BGDataPoint>): Boolean {
        if (historicalData.size < 4) return false

        // ★★★ GEBRUIK INSTELBARE TIJD ★★★
        val recoveryWindowAgo = DateTime.now().minusMinutes(HYPO_RECOVERY_MINUTES)
        val recentData = historicalData.filter { it.timestamp.isAfter(recoveryWindowAgo) }

        val minPoint = recentData.minByOrNull { it.bg }
        val current = historicalData.last()

        minPoint?.let { lowPoint ->
            val minutesSinceLow = Minutes.minutesBetween(lowPoint.timestamp, current.timestamp).minutes
            val totalRise = current.bg - lowPoint.bg

            // ★★★ INSTELBARE TIJD EN STIJGING ★★★
            val minRecoveryTime = 15
            val maxRecoveryTime = HYPO_RECOVERY_MINUTES
            val minRiseRequired = 2.0

            val isRapidRecovery = minutesSinceLow in minRecoveryTime..maxRecoveryTime && totalRise > minRiseRequired

            val pointsAfterLow = recentData.filter { it.timestamp.isAfter(lowPoint.timestamp) }
            if (pointsAfterLow.size >= 3) {
                val risingCount = pointsAfterLow.zipWithNext { a, b ->
                    b.bg > a.bg + 0.1
                }.count { it }

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
        // 1. Directe hypo-herstel detectie
        if (isLikelyHypoRecovery(currentBG, historicalData, trends)) {

            return true
        }

        // 2. ★★★ GEBRUIK hypoThreshold VOOR EXTRA CONSERVATIEVE CHECK ★★★
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


    private fun isTrendReversingToDecline(data: List<BGDataPoint>, trends: TrendAnalysis): Boolean {
        if (data.size < 5) return false

        // Check of de versnelling negatief wordt (afremming)
        val isDecelerating = trends.acceleration < -0.3

        // Check of de korte-termijn trend daalt terwijl lange-termijn nog stijgt
        val shortTermTrend = calculateShortTermTrend(data)
        val isDiverging = shortTermTrend < 0 && trends.recentTrend > 1.0

        // Check of de laatste metingen consistent dalen
        val lastThree = data.takeLast(3)
        val decliningCount = lastThree.zipWithNext().count { (first, second) ->
            second.bg < first.bg - 0.1
        }

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
            iobRatio > 0.8 -> true                                    // 0.9 Alleen bij echte max
            iobRatio > 0.7 && trends.recentTrend < -1.0 -> true       // 0.8 en -2.0 Alleen bij sterke daling
            iobRatio > 0.6 && trends.recentTrend < -2.0 -> true       // 0.7 en -3.0 Alleen bij zeer sterke daling
            else -> false
        }
    }

    private fun isAtPeakOrDeclining(historicalData: List<BGDataPoint>, trends: TrendAnalysis): Boolean {
        if (historicalData.size < 6) return false

        val recentPoints = historicalData.takeLast(6)

        // Verbeterde peak detectie met plateau herkenning
        val maxIndex = recentPoints.withIndex().maxByOrNull { it.value.bg }?.index ?: -1
        val isClearPeak = maxIndex in 2..4 &&
            recentPoints.last().bg < recentPoints[maxIndex].bg - 0.5

        // Plateau detectie - kleine variaties rond het maximum
        val plateauPoints = recentPoints.takeLast(4)
        val maxBG = plateauPoints.maxOf { it.bg }
        val minBG = plateauPoints.minOf { it.bg }
        val isPlateau = (maxBG - minBG) < 0.4 && trends.recentTrend < 0.8

        // Versnellings-based peak detectie - gevoeliger gemaakt
        val isDecelerating = trends.acceleration < -0.3 && trends.recentTrend < 1.5

        // Dalende trend over meerdere punten
        val decliningCount = recentPoints.zipWithNext().count { (first, second) ->
            second.bg < first.bg - 0.1
        }
        val isConsistentDecline = decliningCount >= 3

        return isClearPeak || isPlateau || isDecelerating || isConsistentDecline
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

    // Hulpfunctie voor trend tussen twee punten
    private fun calculateTrendBetweenPoints(point1: BGDataPoint, point2: BGDataPoint): Double {
        val timeDiffHours = Minutes.minutesBetween(point1.timestamp, point2.timestamp).minutes / 60.0
        if (timeDiffHours <= 0) return 0.0
        return (point2.bg - point1.bg) / timeDiffHours
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

    private fun predictMealResponse(currentBG: Double, trends: TrendAnalysis, phase: String, minutesAhead: Int): Double {
        val dynamicMaxRise = calculateDynamicMaxRise(currentBG)
        val predictedRise = when (phase) { // ← phase is nu altijd de robuuste fase
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


    private fun isHypoRiskWithin(minutesAhead: Int, currentBG: Double, iob: Double, isf: Double, thresholdMmol: Double = 4.0): Boolean {
        val predicted = predictIOBEffect(currentBG, iob, isf, minutesAhead)
        return predicted < thresholdMmol
    }

    private fun predictBasalResponse(currentBG: Double, trends: TrendAnalysis, minutesAhead: Int): Double {
        return currentBG + (trends.recentTrend * minutesAhead / 60 * 0.3)
    }

    // Hoofdfunctie voor real-time voorspelling
    fun predictRealTime(
        currentData: BGDataPoint,
        historicalData: List<BGDataPoint>,
        ISF: Double,
        minutesAhead: Int = 60,
        carbRatio: Double,
        targetBG: Double,
        currentIOB: Double, // ★★★ TOEVOEGEN
        maxIOB: Double      // ★★★ TOEVOEGEN
    ): PredictionResult {

        val trends = analyzeTrends(historicalData)

       // ★★★ UNIFORME CARB DETECTIE ★★★
        val carbsResult = calculateUnifiedCarbsDetection(
            historicalData = historicalData,
            robustTrends = calculateRobustTrends(historicalData),
            currentBG = currentData.bg,
            targetBG = targetBG,
            currentIOB = currentData.iob,
            maxIOB = maxIOB, // Je moet maxIOB als parameter toevoegen aan predictRealTime
            effectiveCR = carbRatio
        )

        val detectedCarbs = carbsResult.detectedCarbs
        val mealState = if (detectedCarbs > 10.0) MealDetectionState.DETECTED else MealDetectionState.NONE
        val mealDetected = mealState != MealDetectionState.NONE


        // ★★★ GEBRUIK ROBUUSTE FASE ★★★
        val robustPhase = lastRobustTrends?.phase ?: "stable"

        updateMealStatusAutomatically(currentData, historicalData, trends, mealState) // ← phase parameter verwijderd

        val prediction = when {
            mealInProgress -> predictMealResponse(currentData.bg, trends, robustPhase, minutesAhead) // ← Gebruik robustPhase
            currentData.iob > minIOBForEffect -> predictIOBEffect(currentData.bg, currentData.iob, ISF, minutesAhead)
            else -> predictBasalResponse(currentData.bg, trends, minutesAhead)
        }

        return PredictionResult(
            value = prediction.coerceIn(3.5, 20.0),
            trend = trends.recentTrend,
            mealDetected = mealDetected,
            mealInProgress = mealInProgress,
            phase = robustPhase // ← Gebruik robustPhase
        )
    }

    private fun getPhaseSpecificAggressiveness(phase: String): Double {
        // ★★★ COMBINATIE: fase-specifiek × algemene agressiviteit ★★★
        val overallAggressiveness = getCurrentBolusAggressiveness() / 100.0

        return when (phase) {
            "early_rise" -> (preferences.get(IntKey.bolus_perc_early).toDouble() / 100.0) * overallAggressiveness
            "mid_rise" -> (preferences.get(IntKey.bolus_perc_mid).toDouble() / 100.0) * overallAggressiveness
            "late_rise" -> (preferences.get(IntKey.bolus_perc_late).toDouble() / 100.0) * overallAggressiveness
            "peak" -> 0.0
            else -> overallAggressiveness // Voor stable, declining, etc.
        }
    }


    // ★★★ HULPFUNCTIE VOOR RECENTE STIJGING ★★★
    private fun hasRecentRise(historicalData: List<BGDataPoint>, minRisingPoints: Int = 2): Boolean {
        if (historicalData.size < minRisingPoints + 1) return false

        val recent = historicalData.takeLast(minRisingPoints + 1)
        val risingCount = recent.zipWithNext { a, b ->
            b.bg > a.bg + 0.15  // Minimaal 0.15 stijging tussen metingen
        }.count { it }

        return risingCount >= minRisingPoints
    }


    private fun shouldReleaseReservedBolus(
        currentBG: Double,
        targetBG: Double,
        trends: TrendAnalysis,
        historicalData: List<BGDataPoint>,
        currentIOB: Double,
        maxIOB: Double
    ): Boolean {
        if (historicalData.size < 3) return false

        val isRecentlyRising = hasRecentRise(historicalData, 2)
        val shortTermTrend = calculateShortTermTrend(historicalData)

        // ★★★ JOUW SPECIFIEKE IOB-AWARE LOGICA ★★★
        val iobCapacity = maxIOB - currentIOB

        // ★★★ CRITISCHE CONDITIES VOOR RELEASE ★★★
        val isVeryHighBG = currentBG > targetBG + 5.0  // BG > 10.2
        val hasMinIOBCapacity = iobCapacity > 0.3      // Minimaal 0.3U capaciteit nodig
        val isRising = trends.recentTrend > 0.5 || shortTermTrend > 1.0
        val isRapidRise = trends.recentTrend > 2.0 || shortTermTrend > 2.5

        // ★★★ RELEASE LOGICA ★★★
        return when {
            isVeryHighBG && hasMinIOBCapacity && isRising -> true
            currentBG > targetBG + 4.0 && isRising && hasMinIOBCapacity -> true  // was 3.0
            currentBG > targetBG + 2.0 && isRapidRise && hasMinIOBCapacity -> true  // was 1.0
            else -> false
        } && !isAtPeakOrDeclining(historicalData, trends) && currentIOB < maxIOB * 0.7  // EXTRA IOB CHECK
    }

    private fun calculateReservedBolusRelease(
        currentBG: Double,
        targetBG: Double,
    //    trends: TrendAnalysis,
    //    historicalData: List<BGDataPoint>,
        currentIOB: Double,
        maxIOB: Double,
        maxBolus: Double  // ★★★ NIEUWE PARAMETER ★★★
    ): Double {
        if (pendingReservedBolus <= 0.0) return 0.0

        // ★★★ JOUW SPECIFIEKE BEREKENING ★★★
        val bgAboveTarget = currentBG - targetBG
        val iobCapacity = maxIOB - currentIOB

        // Bepaal release percentage op basis van BG en IOB capaciteit
        val releasePercentage = when {
            bgAboveTarget > 5.0 && iobCapacity > 1.0 -> 0.8  // Zeer hoog + ruimte
            bgAboveTarget > 5.0 && iobCapacity > 0.5 -> 0.6  // Zeer hoog + beperkte ruimte
            bgAboveTarget > 3.0 && iobCapacity > 0.8 -> 0.7  // Hoog + ruimte
            bgAboveTarget > 3.0 && iobCapacity > 0.4 -> 0.5  // Hoog + beperkte ruimte
            bgAboveTarget > 2.0 && iobCapacity > 0.6 -> 0.4  // Matig hoog + ruimte
            else -> 0.2  // Conservatieve release
        }

        val releaseAmount = pendingReservedBolus * releasePercentage

        // ★★★ MAX BOLUS BEGRENSING ★★★
        val cappedRelease = minOf(releaseAmount, maxBolus, iobCapacity)

        // Alleen releasen als het significant is
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

            // ✅ NIEUW: Harde timeout na 90 minuten
            if (minutesPassed > 90) {
                pendingReservedBolus = 0.0
                pendingReservedCarbs = 0.0
                pendingReservedTimestamp = null
            } else {
                // Milde afbouw alleen in de eerste 90 minuten
                val hoursPassed = minutesPassed / 60.0
                val decayFactor = exp(-hoursPassed * 0.5) // 50% per uur
                pendingReservedBolus *= decayFactor
                pendingReservedCarbs *= decayFactor

                // Cleanup als bijna verdwenen
                if (pendingReservedBolus < 0.1) {
                    pendingReservedBolus = 0.0
                    pendingReservedCarbs = 0.0
                    pendingReservedTimestamp = null
                }
            }
        }
    }


    // Insulin advice voor closed loop
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
                    phase = realTimePrediction.phase // ← Gebruik robuuste fase van prediction
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

        // ★★★ ALGEMENE AGGRESSIVITEIT TOEPASSEN ★★★
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
        // ★★★ NIEUWE HYPO RECOVERY CHECK ★★★
        if (isLikelyHypoRecovery(currentData.bg, historicalData, trends)) {
            return true
        }

        // ★★★ MINDER RESTRICTIEF VOOR HOGE BG ★★★
        val isHighBG = currentData.bg > targetBG + 3.0

        return when {
            // Absolute hypo-veiligheid - blijft strikt
            currentData.bg < gethypoThreshold() + 1.0 -> {
                true
            }

            // Voor hoge BG, gebruik minder restrictieve IOB limieten
            currentData.iob > maxIOB * (if (isHighBG) 0.7 else 0.45) && currentData.bg < targetBG + (if (isHighBG) 2.0 else 1.0) -> {
                true
            }

            // Sterke daling + dicht bij hypo
            currentData.bg < gethypoThreshold() + 2.0 && trends.recentTrend < -1.0 -> {
                true
            }

            else -> false
        }
    }

    private fun explainWithholdReason(currentData: BGDataPoint, trends: TrendAnalysis, targetBG: Double, maxIOB: Double): String {
        val iobRatio = currentData.iob / maxIOB
        return when {
            currentData.bg < 5.0 ->
                "Withheld: BG ${"%.1f".format(currentData.bg)} < 5.0 mmol/L (hypo risk)"

            currentData.bg < 5.8 && trends.recentTrend < -0.3 ->
                "Withheld: BG ${"%.1f".format(currentData.bg)} and falling fast (${String.format("%.2f", trends.recentTrend)} mmol/L/h)"

            currentData.bg < 6.5 && trends.recentTrend < -0.5 ->
                "Withheld: BG ${"%.1f".format(currentData.bg)} with strong downward trend (${String.format("%.2f", trends.recentTrend)} mmol/L/h)"

            iobRatio > 0.5 && currentData.bg < targetBG + 1.0 ->
                "Withheld: IOB ${"%.2f".format(currentData.iob)} > 1.8U and BG ${"%.1f".format(currentData.bg)} < target+1.0 (${String.format("%.1f", targetBG + 1.0)})"

            iobRatio > 0.25 && trends.recentTrend < -0.3 ->
                "Withheld: IOB ${"%.2f".format(currentData.iob)} and falling trend (${String.format("%.2f", trends.recentTrend)} mmol/L/h)"

            else -> "Withheld: unspecified safety condition"
        }
    }

    private fun checkForCarbCorrection(historicalData: List<BGDataPoint>): Boolean {
        if (historicalData.size < 6) return false
        // ★★★ GEBRUIK hypoThreshold ★★★
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

    private fun updateMealStatusAutomatically(
        currentData: BGDataPoint,
        historicalData: List<BGDataPoint>,
        trends: TrendAnalysis,
        currentMealState: MealDetectionState,
    ) {
        val currentTime = currentData.timestamp

        // ★★★ GEBRUIK ROBUUSTE FASE ★★★
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

        if (mealInProgress && !peakDetected && robustPhase == "peak") { // ← Gebruik robustPhase
            peakDetected = true
        }

        // NIEUW: Meal afronding detectie en performance logging
        if (mealInProgress && shouldEndMealPhase(currentData, historicalData, trends)) {
            val recentUpdate = pendingLearningUpdates.lastOrNull {
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

    private fun logMealPerformanceResult(
        currentData: BGDataPoint,
        historicalData: List<BGDataPoint>,
        peakConfidence: Double = 0.5, // ★★★ NIEUWE PARAMETER
        mealType: String = "unknown"  // ★★★ NIEUWE PARAMETER
    ) {
        try {
            // Zoek de laatste pending learning update voor deze meal
            val recentUpdates = pendingLearningUpdates.sortedByDescending { it.timestamp }
            val latestUpdate = recentUpdates.firstOrNull {
                Minutes.minutesBetween(it.timestamp, DateTime.now()).minutes < 240
            } ?: return

            // Zoek de werkelijke piek BG tijdens deze meal
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

            // Bepaal outcome
            val outcome = when {
                actualPeak > 11.0 -> "TOO_HIGH"
                actualPeak < 6.0 -> "TOO_LOW"
                else -> "SUCCESS"
            }

            // ★★★ BEREKEN PEAK CONFIDENCE ALS NIET MEEGEGEVEN ★★★
            val finalPeakConfidence = if (peakConfidence == 0.5) {
                calculatePeakConfidence(historicalData, latestUpdate.detectedCarbs)
            } else {
                peakConfidence
            }

            // ★★★ BEPAAL MEAL TYPE ALS NIET MEEGEGEVEN ★★★
            val finalMealType = if (mealType == "unknown") {
                latestUpdate.mealType // Gebruik het mealType uit de LearningUpdate
            } else {
                mealType
            }
            // ★★★ NIEUW: Roep hypo learning aan bij TOO_LOW ★★★
            if (outcome == "TOO_LOW") {
                updateLearningFromHypoAfterMeal(
                    mealType = finalMealType,
                    bgEnd = currentData.bg
                )
            }

            // Sla resultaat op
            val performanceResult = MealPerformanceResult(
                timestamp = DateTime.now(),
                detectedCarbs = latestUpdate.detectedCarbs,
                givenDose = latestUpdate.givenDose,
                startBG = latestUpdate.startBG,
                predictedPeak = latestUpdate.expectedPeak,
                actualPeak = actualPeak,
                timeToPeak = timeToPeak,
                parameters = MealParameters(
                    bolusPercEarly = preferences.get(IntKey.bolus_perc_early).toDouble(),
                    bolusPercDay = preferences.get(IntKey.bolus_perc_day).toDouble(),
                    bolusPercNight = preferences.get(IntKey.bolus_perc_day).toDouble(),
                    peakDampingFactor = preferences.get(IntKey.peak_damping_percentage).toDouble()/100.0,
                    hypoRiskFactor = preferences.get(IntKey.hypo_risk_percentage).toDouble()/100.0,
                    timestamp = DateTime.now()
                ),
                outcome = outcome,
                peakConfidence = finalPeakConfidence, // ★★★ NIEUW
                mealType = finalMealType // ★★★ OPTIONEEL

            )

            storage.saveMealPerformanceResult(performanceResult)

        } catch (e: Exception) {

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


    private fun roundDose(dose: Double): Double {
        return round(dose * 20) / 20
    }

    private fun round(value: Double, digits: Int): Double {
        val scale = Math.pow(10.0, digits.toDouble())
        return Math.round(value * scale) / scale
    }


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
                loggingHelper.logToDetailedCSV(
                    fclAdvice = advice,
                    currentData = currentData,
                    currentISF = currentISF,
                    currentIOB = currentIOB
                )
                return advice
            }

            // ★★★ EÉNMALIGE UPDATE ★★★
            updateResistentieIndienNodig()
            berekenStappenAdjustment()

            val effectiveISF = getEffectiveISF()  // bevat nu automatisch stappenadjustment
            val effectiveTarget = getEffectiveTarget()  // bevat nu automatisch stappenadjustment

            // VALIDATIE VAN LEARNING PARAMETERS TOEVOEGEN
            if (learningProfile.personalCarbRatio.isNaN() || learningProfile.personalCarbRatio <= 0) {
                learningProfile = learningProfile.copy(personalCarbRatio = 1.0)
            }
            if (learningProfile.personalISF.isNaN() || learningProfile.personalISF <= 0) {
                learningProfile = learningProfile.copy(personalISF = 1.0)
            }
            // ★★★  Check op reset bij elke advice call ★★★
            resetLearningDataIfNeeded()

            // ★★★ ALTIJD ROBUUSTE FASE GEBRUIKEN ★★★
            val robustTrends = calculateRobustTrends(historicalData)
            val robustPhase = robustTrends.phase

            processPendingLearningUpdates()
            processPendingCorrectionUpdates()

            // housekeeping
            cleanUpMeals()

            // COB
            val cobNow = getCarbsOnBoard()

            val basicAdvice = getInsulinAdvice(
                currentData, historicalData,
                effectiveISF, effectiveTarget, carbRatio, currentIOB, maxIOB
            )

            // Probeer openstaande learning updates af te handelen
            processPendingLearningUpdates()
            processPendingCorrectionUpdates()

            // Sla profiel altijd op na wijzigingen
            storage.saveLearningProfile(learningProfile)

            // placeholders
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

            // ★★★ PERSISTENT HIGH BG CHECK - HOOGSTE PRIORITEIT (EARLY RETURN) ★★★
            val persistentResult = checkPersistentHighBG(historicalData, currentIOB, maxIOB)
            val hasPersistentBolus = persistentResult.shouldDeliver && persistentResult.extraBolus > 0.05
            val persistentBolusAmount = if (hasPersistentBolus) persistentResult.extraBolus else 0.0

            if (hasPersistentBolus) {
                // ★★★ DIRECT RETURN - GEEN verdere verwerking ★★★
                storeMealForLearning(
                    detectedCarbs = 0.0,
                    givenDose = persistentBolusAmount,
                    startBG = currentData.bg,
                    expectedPeak = currentData.bg,
                    mealType = "persistent_correction"
                )

                val persistentAdvice = EnhancedInsulinAdvice(
                    dose = persistentBolusAmount,
                    reason = "persistent High Bg",
                    confidence = finalConfidence,
                    predictedValue = predictedPeak,
                    mealDetected = finalMealDetected,
                    detectedCarbs = finalDetectedCarbs,
                    shouldDeliverBolus = true,
                    phase = finalPhase,
                    learningMetrics = LearningMetrics(
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

            // === Safety check voor niet-persistent scenario's ===
            val safetyBlock = shouldWithholdInsulin(currentData, trends, effectiveTarget, maxIOB, historicalData)
            if (safetyBlock) {
                finalDose = 0.0
                finalReason = "Safety: ${explainWithholdReason(currentData, trends, effectiveTarget, maxIOB)}"
                finalDeliver = false
                finalPhase = "safety"
            }

            // === NIEUW: Korte-termijn trend safety check ===
            if (finalDeliver && finalDose > 0) {
                if (shouldBlockBolusForShortTermTrend(currentData, historicalData, trends, maxIOB)) {
                    val shortTermTrend = calculateShortTermTrend(historicalData)
                    finalDose = 0.0
                    finalDeliver = false
                    finalReason = "Safety: Strong short-term decline (${"%.1f".format(shortTermTrend)} mmol/L/h)"
                    finalPhase = "safety"
                }
            }

            // === NIEUW: Trend reversal detection ===
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

            // Update debug info
            lastMealDetectionDebug = carbsResult.detectionReason

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

            // Alleen overwegen bij voldoende betrouwbaarheid
            if (robustTrends.consistency > preferences.get(DoubleKey.phase_min_consistency) &&
                mathBolusAdvice.immediatePercentage > 0 && detectedCarbs > 0) {

                // ★★★ BEREKEN totalCarbBolus ★★★
                val effectiveCR = getEffectiveCarbRatio()
                val totalCarbBolus = detectedCarbs / effectiveCR

                // ★★★ PAS HYPO-ADJUSTED FACTOR TOE ★★★
                val mealType = getMealTypeFromHour()
                val currentHour = DateTime.now().hourOfDay
                val hypoAdjustedFactor = getHypoAdjustedMealFactor(mealType, currentHour)
                val adjustedTotalCarbBolus = totalCarbBolus * hypoAdjustedFactor

                val mathImmediateBolus = adjustedTotalCarbBolus * mathBolusAdvice.immediatePercentage
                val mathReservedBolus = adjustedTotalCarbBolus * mathBolusAdvice.reservedPercentage

                // Alleen toepassen als we meer vertrouwen hebben dan de standaard methode
                if (robustTrends.consistency > 0.7 || detectedCarbs > 20) {
                    finalDose = mathImmediateBolus
                    finalReservedBolus = mathReservedBolus
                    finalReason = mathBolusAdvice.reason
                    finalPhase = robustTrends.phase

                    // ★★★ RESERVED BOLUS MET CARBS ★★★
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

                    // Store voor learning met wiskundige fase
                    storeMealForLearning(
                        detectedCarbs = detectedCarbs,
                        givenDose = finalDose,
                        startBG = currentData.bg,
                        expectedPeak = predictedPeak,
                        mealType = robustTrends.phase  // Gebruik fase als meal type voor learning
                   )
               }
            }

            // ★★★ NIEUW: Safety checks voor false positives ★★★
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

            // ★★★ NIEUW: Meal detection ook wanneer BG boven target is ★★★
            else if (mealState != MealDetectionState.NONE && !sensorError && finalDeliver) {
                val mealConfidence = calculateMealConfidence(historicalData, detectedCarbs)

                if (mealConfidence > 0.4 && isLikelyMeal && detectedCarbs > 15 &&
                    canDetectMealAboveTarget(currentData.bg, effectiveTarget, trends, currentIOB, maxIOB)) {

                    // ★★★ TOEVOEGING: Voeg gedetecteerde koolhydraten toe aan COB ★★★
                    finalCOB = getCarbsOnBoard() // Update COB voor return waarde

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

                    // Bereken correction component (30% van benodigde correction)
                    val correctionComponent = max(0.0, (currentData.bg - effectiveTarget) / effectiveISF) * 0.3

                    finalDose = immediateBolus + correctionComponent
                    finalReason = "Meal+Correction: ${"%.1f".format(detectedCarbs)}g + BG=${"%.1f".format(currentData.bg)} | $bolusReason"
                    finalMealDetected = true
                    finalDetectedCarbs = detectedCarbs
                    finalPhase = "meal_correction_combination"

                    // Store voor learning
                    storeMealForLearning(
                        detectedCarbs = detectedCarbs,
                        givenDose = finalDose,
                        startBG = currentData.bg,
                        expectedPeak = predictedPeak,
                        mealType = getMealTypeFromHour()
                    )

                    // Reserveer bolus
                    if (reservedBolus > 0.1) {
                        pendingReservedBolus = reservedBolus
                        pendingReservedCarbs = detectedCarbs
                        pendingReservedTimestamp = DateTime.now()
                        pendingReservedPhase = robustPhase
                        finalReason += " | Reserved: ${"%.2f".format(reservedBolus)}U"
                    }

                }
            }

            // ★★★ VERBETERDE SAFETY CHECK - STA EXTREME STIJGINGEN TOE MET IOB BEWUSTZIJN ★★★
            else if (!isLikelyMeal && detectedCarbs < 15) {
                // ★★★ IOB CAPACITEIT CHECK ★★★
                val iobCapacity = maxIOB - currentIOB
                val hasIOBCapacity = iobCapacity > 0.5  // Minimaal 0.5U beschikbaar

                // ★★★ EXTREME STIJGING CONDITIES ★★★
                val isExtremeRise = robustTrends.phase in listOf("early_rise", "mid_rise") &&
                    robustTrends.firstDerivative > 5.0 &&
                    robustTrends.consistency > 0.6

                val isVeryHighBG = currentData.bg > 10.0

                val shouldBlock = when {
                    // BLOKKEREN: Geen IOB capaciteit + geen extreme stijging
                    !hasIOBCapacity && !isExtremeRise && !isVeryHighBG -> true

                    // BLOKKEREN: Dalende/stabiele fase + lage carbs
                    robustTrends.phase in listOf("declining", "peak", "stable") && detectedCarbs < 10 -> true

                    // TOESTAAN: Extreme stijging + IOB capaciteit
                    hasIOBCapacity && isExtremeRise -> false

                    // TOESTAAN: Zeer hoge BG + IOB capaciteit
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
                    // ★★★ TOESTAAN MET IOB BEWUSTZIJN ★★★
                    finalDetectedCarbs = maxOf(detectedCarbs, 25.0)
                    finalMealDetected = true
                    finalReason = "Extreme rise override (slope: ${"%.1f".format(robustTrends.firstDerivative)}, IOB cap: ${"%.1f".format(iobCapacity)}U)"

                }
            }

            // Safety: pas aan bij tegenvallende stijging
            else if (shouldAdjust && mealInProgress) {
                finalMealDetected = true
                finalDetectedCarbs = detectedCarbs * 0.5 // Carb schatting aanpassen
            }

            // ★★★ GEFASEERDE MEAL PROCESSING MET IOB REDUCTIE (voor BG onder target) ★★★
            else if (mealState != MealDetectionState.NONE && !sensorError && !finalMealDetected) {
                val mealConfidence = calculateMealConfidence(historicalData, detectedCarbs)

                if (mealConfidence > 0.4 && detectedCarbs > 10) {
                    // ★★★ TOEVOEGING: Voeg gedetecteerde koolhydraten toe aan COB ★★★
                    addOrUpdateActiveMeal(detectedCarbs, DateTime.now())
                    finalCOB = getCarbsOnBoard() // Update COB voor return waarde

                    // ★★★ ALLEEN WISKUNDIGE METHODE ★★★
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

                    // ★★★ IOB VEILIGHEID ZIT AL IN WISKUNDIGE METHODE ★★★
                    if (immediateBolus <= 0.0) {
                        // Bolus geblokkeerd door wiskundige veiligheidschecks
                        finalDose = 0.0
                        finalReason = "Math-Safety: ${bolusReason}"
                        finalDeliver = false
                        finalPhase = "safety_math_blocked"
                        finalMealDetected = false
                    } else {
                        // ★★★ GEEN IOB REDUCTIE - al verwerkt in wiskundige methode ★★★
                        val finalImmediateBolus = immediateBolus
                        val finalReservedBolus = reservedBolus

                        // Bewaar reserved bolus voor later
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

                        // Store for learning (alleen de initiële bolus)
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
                    // Laag vertrouwen - monitor alleen
                    finalDose = 0.0
                    finalReason = "Monitoring uncertain pattern (confidence: ${"%.0f".format(mealConfidence * 100)}%)"
                    finalDeliver = false
                    finalPhase = "safety_monitoring"
                    finalMealDetected = false
                }
            }

            // ★★★ RESERVED BOLUS RELEASE LOGIC ★★★
            decayReservedBolusOverTime()

            // Check of reserved bolus vrijgegeven moet worden
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

                        // Update learning met de vrijgegeven bolus
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

            // === Correction (alleen als GEEN maaltijd gedetecteerd EN GEEN persistent bolus) ===
            else if (!finalMealDetected && currentData.bg > effectiveTarget + 0.5) {

                // ★★★ WISKUNDIGE CORRECTIE ★★★
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
                        val predictedDrop = finalDose * effectiveISF   // mmol/L daling verwacht
                        val corrUpdate = CorrectionUpdate(
                            insulinGiven = finalDose,
                            predictedDrop = predictedDrop,
                            bgStart = currentData.bg,
                            timestamp = DateTime.now()
                        )
                        storage.savePendingCorrectionUpdate(corrUpdate)

                        // ★★★ TRACK BOLUS AFGIFTE ★★★
                    //    trackBolusDelivery(finalDose, currentIOB, finalReason)
                    }
                } catch (ex: Exception) {
                    // Logging
                }
            }

            // === Geen actie ===
            else if (!finalMealDetected) {
                finalDose = 0.0
                finalReason = "No action: BG=${"%.1f".format(currentData.bg)} ~ target=${"%.1f".format(effectiveTarget)}"
                finalPhase = "stable"
                finalDeliver = false
                finalConfidence = learningProfile.learningConfidence
            }

            // -------------------------------------------------------------------
            // Early Boost logic (dynamisch, meal én non-meal)
            // -------------------------------------------------------------------
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

            // === Voorspelling leegmaken bij dalende trend ===
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
                learningMetrics = LearningMetrics(
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

}

enum class MealDetectionState { NONE, EARLY_RISE, RISING, PEAK, DECLINING, DETECTED }
enum class MealConfidenceLevel {
    SUSPECTED,      // Initiële stijging gedetecteerd
    CONFIRMED,      // Consistente stijging over meerdere metingen
    HIGH_CONFIDENCE // Patroon matcht maaltijdprofiel
}
enum class SensorIssueType {JUMP_TOO_LARGE, OSCILLATION, COMPRESSION_LOW}