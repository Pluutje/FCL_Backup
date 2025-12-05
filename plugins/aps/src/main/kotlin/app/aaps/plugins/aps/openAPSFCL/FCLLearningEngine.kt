package app.aaps.plugins.aps.openAPSFCL

import android.content.Context
import android.os.Environment
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.Preferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.joda.time.DateTime
import org.joda.time.Days
import org.joda.time.Minutes
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class FCLLearningEngine(
    private val preferences: Preferences,
    private val context: Context
) {

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

    // ★★★ SERIALIZABLE DATA CLASSES VOOR STORAGE ★★★
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

    private data class SMealPerformance(
        val timestamp: Long,
        val detectedCarbs: Double,
        val givenDose: Double,
        val startBG: Double,
        val predictedPeak: Double,
        val actualPeak: Double,
        val timeToPeak: Int,
        // ★★★ NIEUWE 2-FASEN PARAMETERS ★★★
        val bolusPercRising: Double,
        val bolusPercPlateau: Double,
        val bolusPercDay: Double,
        val bolusPercNight: Double,
        val phaseRisingSlope: Double,
        val phasePlateauSlope: Double,
        val outcome: String,
        val peakConfidence: Double,
        val mealType: String = "unknown"
    )

    private data class SCorrectionPerformance(
        val timestamp: Long,
        val givenDose: Double,
        val startBG: Double,
        val predictedDrop: Double,
        val actualDrop: Double,
        val outcome: String
    )

    // Android implementatie met geïsoleerde storage
    class AndroidFCLStorage(private val context: Context) : FCLStorage {
        private val prefs = context.getSharedPreferences("FCL_Learning_Data", Context.MODE_PRIVATE)
        private val gson = Gson()
        private val externalDir = File(Environment.getExternalStorageDirectory().absolutePath + "/Documents/AAPS/")
        private val backupFile = File(externalDir, "ANALYSE/fcl_learning_backup.json")
        private val backupMeta = File(externalDir, "ANALYSE/fcl_learning_backup.meta")
        private val backupIntervalMs = 4 * 60 * 60 * 1000L

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
                // Logging
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
                    lastUpdated = DateTime(s.lastUpdatedMillis),
                    learningConfidence = s.learningConfidence,
                    totalLearningSamples = s.totalLearningSamples,
                )
            } catch (e: Exception) {
                null
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

        // ----- Meal response persistence -----
        private fun loadHistoricalMealDataSerializable(): List<SMealResponse> {
            val json = prefs.getString("meal_response_data", null) ?: return emptyList()
            return try {
                gson.fromJson(json, Array<SMealResponse>::class.java)?.toList() ?: emptyList()
            } catch (e: Exception) {
                emptyList()
            }
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

        // ----- Pending learning updates persistence -----
        private fun loadPendingLearningUpdatesSerializable(): List<SPendingUpdate> {
            val json = prefs.getString("pending_learning_updates", null) ?: return emptyList()
            return try {
                gson.fromJson(json, Array<SPendingUpdate>::class.java)?.toList() ?: emptyList()
            } catch (e: Exception) {
                emptyList()
            }
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

        // ----- Peak detection persistence -----
        private fun loadPeakDetectionDataSerializable(): List<SPeakData> {
            val json = prefs.getString("peak_detection_data", null) ?: return emptyList()
            return try {
                gson.fromJson(json, Array<SPeakData>::class.java)?.toList() ?: emptyList()
            } catch (e: Exception) {
                emptyList()
            }
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
                    // ★★★ NIEUWE 2-FASEN PARAMETERS ★★★
                    bolusPercRising = result.parameters.bolusPercRising,
                    bolusPercPlateau = result.parameters.bolusPercPlateau,
                    bolusPercDay = result.parameters.bolusPercDay,
                    bolusPercNight = result.parameters.bolusPercNight,
                    phaseRisingSlope = result.parameters.phaseRisingSlope,
                    phasePlateauSlope = result.parameters.phasePlateauSlope,
                    outcome = result.outcome,
                    peakConfidence = result.peakConfidence,
                    mealType = result.mealType
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
                        // ★★★ NIEUWE 2-FASEN PARAMETERS ★★★
                        bolusPercRising = it.bolusPercRising,
                        bolusPercPlateau = it.bolusPercPlateau,
                        bolusPercDay = it.bolusPercDay,
                        bolusPercNight = it.bolusPercNight,
                        phaseRisingSlope = it.phaseRisingSlope,
                        phasePlateauSlope = it.phasePlateauSlope,
                        timestamp = DateTime(it.timestamp)
                    ),
                    outcome = it.outcome,
                    peakConfidence = it.peakConfidence,
                    mealType = it.mealType
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

        override fun resetAllLearningData() {
            try {
                // Verwijder alle shared preferences keys
                prefs.edit().clear().apply()
                // Verwijder backup files
                if (backupFile.exists()) backupFile.delete()
                if (backupMeta.exists()) backupMeta.delete()
            } catch (e: Exception) {
                // Logging
            }
        }

        override fun saveCurrentCOB(cob: Double) {
            try {
                prefs.edit().putFloat("current_cob", cob.toFloat()).apply()
            } catch (e: Exception) {
                // Logging
            }
        }
    }

    // ★★★ LEARNING DATA CLASSES ★★★
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
                in 6..10 -> "ontbijt"
                in 11..14 -> "lunch"
                in 17..21 -> "dinner"
                else -> "snack"
            }
            return mealTimingFactors[mealType] ?: 1.0
        }
    }

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

    data class MealPerformanceResult(
        val timestamp: DateTime,
        val detectedCarbs: Double,
        val givenDose: Double,
        val startBG: Double,
        val predictedPeak: Double,
        val actualPeak: Double,
        val timeToPeak: Int,
        val parameters: MealParameters,
        val outcome: String,
        val peakConfidence: Double,
        val mealType: String = "unknown"
    )

    data class CorrectionPerformanceResult(
        val timestamp: DateTime,
        val givenDose: Double,
        val startBG: Double,
        val predictedDrop: Double,
        val actualDrop: Double,
        val outcome: String
    )

    data class MealParameters(
        // ★★★ NIEUWE 2-FASEN PARAMETERS ★★★
        val bolusPercRising: Double,      // Vervangt bolusPercEarly, bolusPercMid, bolusPercLate
        val bolusPercPlateau: Double,     // Nieuwe plateau fase parameter
        val bolusPercDay: Double,
        val bolusPercNight: Double,
        val phaseRisingSlope: Double,     // Vervangt phase_early_rise_slope, phase_mid_rise_slope, phase_late_rise_slope
        val phasePlateauSlope: Double,    // Nieuwe plateau drempel
        val timestamp: DateTime
    )

    data class LearningMetrics(
        val confidence: Double,
        val samples: Int,
        val carbRatioAdjustment: Double,
        val isfAdjustment: Double,
        val mealTimeFactors: Map<String, Double>
    )

    // ★★★ LEARNING STATE ★★★
    private val storage: FCLStorage = AndroidFCLStorage(context)
    private var learningProfile: FCLLearningProfile = storage.loadLearningProfile() ?: FCLLearningProfile()
    private val pendingLearningUpdates = mutableListOf<LearningUpdate>()
    private val pendingCorrectionUpdates = mutableListOf<CorrectionUpdate>()

    // ★★★ PUBLIC INTERFACE ★★★
    fun getLearningProfile(): FCLLearningProfile = learningProfile
    fun getStorage(): FCLStorage = storage

    // ★★★ LEARNING FUNCTIES ★★★
    fun updateLearningFromMealResponse(
        detectedCarbs: Double,
        givenDose: Double,
        predictedPeak: Double,
        actualPeak: Double,
        bgStart: Double,
        bgEnd: Double,
        mealType: String,
        startTimestamp: DateTime,
        peakTimestamp: DateTime,
        currentCR: Double
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

        // ★★★ OPSLAAN MEAL RESPONSE DATA ★★★
        val mealResponseData = MealResponseData(
            timestamp = startTimestamp,
            carbs = detectedCarbs,
            insulinGiven = givenDose,
            predictedPeak = predictedPeak,
            actualPeak = actualPeak,
            timeToPeak = Minutes.minutesBetween(startTimestamp, peakTimestamp).minutes,
            bgStart = bgStart,
            bgEnd = bgEnd
        )
        storage.saveMealResponseData(mealResponseData)
    }

    fun storeMealForLearning(
        detectedCarbs: Double,
        givenDose: Double,
        startBG: Double,
        expectedPeak: Double,
        mealType: String
    ) {
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
            // Logging
        }
    }

    fun getHypoAdjustedMealFactor(mealType: String, hour: Int): Double {
        val baseFactor = learningProfile.getMealTimeFactor(hour)

        // Check recente hypo's voor deze maaltijd
        val recentMeals = storage.loadMealPerformanceResults()
            .filter {
                it.mealType == mealType &&
                    Days.daysBetween(it.timestamp, DateTime.now()).days <= FCL.MAX_RECOVERY_DAYS
            }

        val recentHypoCount = recentMeals.count { it.outcome == "TOO_LOW" }
        val recentSuccessCount = recentMeals.count { it.outcome == "SUCCESS" }
        val totalRecentMeals = recentMeals.size

        if (recentHypoCount == 0) {
            // ★★★ GEEN HYPO'S - geleidelijk herstel ★★★
            val recoverySpeed = when (FCL.MIN_RECOVERY_DAYS) {
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
            val consecutiveSuccessBonus = if (recentSuccessCount >= FCL.MIN_RECOVERY_DAYS) 0.95 else 1.0
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
        val aggressivenessRecovery = FCL.HYPO_RECOVERY_AGGRESSIVENESS

        // Neem de meest conservatieve (laagste) recovery factor
        val overallRecovery = min(timeRecovery, min(performanceRecovery, aggressivenessRecovery))

        val finalFactor = baseFactor * reductionFactor * overallRecovery
        return finalFactor.coerceIn(0.5, 1.5)
    }

    fun resetLearningDataIfNeeded() {
        if (preferences.get(BooleanKey.ResetLearning)) {
            try {
                storage.resetAllLearningData()
                learningProfile = FCLLearningProfile()
                pendingLearningUpdates.clear()
                pendingCorrectionUpdates.clear()
            } catch (e: Exception) {
                // Logging
            }
        }
    }

    fun processPendingLearningUpdates() {
        // synchroniseer in-memory met storage
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

        // Probeer meteen pending updates te koppelen aan al aanwezige piek-data
        try {
            // Deze functie wordt aangeroepen vanuit FCL hoofdklasse
        } catch (ex: Exception) {
            // Logging
        }

        // Probeer fallback-matching voor oudere updates
        try {
            // Deze functie wordt aangeroepen vanuit FCL hoofdklasse
        } catch (ex: Exception) {
            // Logging
        }

        // Herlaad in-memory pending list
        try {
            pendingLearningUpdates.clear()
            pendingLearningUpdates.addAll(storage.loadPendingLearningUpdates())
        } catch (ex: Exception) {
            // Logging
        }
    }

    fun processPendingCorrectionUpdates() {
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
                // Huidige BG moet worden doorgegeven vanuit FCL, hier gebruiken we een placeholder
                val bgNow = 0.0 // Placeholder - zou moeten worden doorgegeven
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

    fun updateLearningFromHypoAfterMeal(
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
        newMealFactors[mealType] = (currentFactor * (1.0 - severity)).coerceIn(
            preferences.get(DoubleKey.CarbISF_min_Factor),
            preferences.get(DoubleKey.CarbISF_max_Factor)
        )

        // Update profile
        learningProfile = learningProfile.copy(
            mealTimingFactors = newMealFactors,
            lastUpdated = DateTime.now()
        )

        storage.saveLearningProfile(learningProfile)
    }

    // ★★★ INTERNE LEARNING FUNCTIES ★★★
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
            // Logging
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

        // Meal performance scoring
        if (recentMeals.isNotEmpty()) {
            val successRate = recentMeals.count { it.outcome == "SUCCESS" }.toDouble() / recentMeals.size
            val avgPeakError = recentMeals.map { abs(it.actualPeak - it.predictedPeak) }.average()
            val peakAccuracy = max(0.0, 1.0 - (avgPeakError / 3.0)) // Max 3.0 mmol/L error

            val mealScore = (successRate * 0.6) + (peakAccuracy * 0.4)
            totalScore += mealScore * recentMeals.size
            totalWeight += recentMeals.size
        }

        // Correction performance scoring
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

        // Sample bonus berekening
        val sampleBonus = calculateSampleBonus(totalRecentSamples, learningProfile.totalLearningSamples)

        // Time decay compensation - minder agressieve decay
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

    private fun getTimeBasedRecoveryFactor(mealType: String, hour: Int): Double {
        val lastHypoTime = storage.loadMealPerformanceResults()
            .filter { it.mealType == mealType && it.outcome == "TOO_LOW" }
            .maxByOrNull { it.timestamp }?.timestamp

        if (lastHypoTime == null) return 1.0 // No hypo history

        val daysSinceLastHypo = Days.daysBetween(lastHypoTime, DateTime.now()).days

        // ★★★ GEBRUIK minRecoveryDays en maxRecoveryDays ★★★
        return when {
            daysSinceLastHypo < FCL.MIN_RECOVERY_DAYS -> 0.7  // Binnen minimale recovery periode
            daysSinceLastHypo < FCL.MIN_RECOVERY_DAYS + 1 -> 0.8
            daysSinceLastHypo < FCL.MIN_RECOVERY_DAYS + 2 -> 0.9
            daysSinceLastHypo < FCL.MAX_RECOVERY_DAYS -> 0.95
            else -> 1.0 // Na maxRecoveryDays - volledig hersteld
        }
    }

    private fun getPerformanceBasedRecovery(mealType: String): Double {
        val recentMeals = storage.loadMealPerformanceResults()
            .filter {
                it.mealType == mealType &&
                    Days.daysBetween(it.timestamp, DateTime.now()).days <= FCL.MAX_RECOVERY_DAYS
            }

        if (recentMeals.isEmpty()) return 1.0

        // Bereken success ratio
        val successCount = recentMeals.count { it.outcome == "SUCCESS" }
        val totalCount = recentMeals.size
        val successRatio = successCount.toDouble() / totalCount

        // ★★★ STRENGERE CRITERIA BIJ KORTE minRecoveryDays ★★★
        val requiredSuccessRatio = when (FCL.MIN_RECOVERY_DAYS) {
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

    // ★★★ HELPER FUNCTIES ★★★
    private fun round(value: Double, digits: Int): Double {
        val scale = Math.pow(10.0, digits.toDouble())
        return Math.round(value * scale) / scale
    }

    // ★★★ STATUS FUNCTIES ★★★
    fun getLearningStatus(): String {
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

        return """
╔═══════════════════
║  ══ FCL Learning Engine ══ 
╚═══════════════════

📊 LEARNING SYSTEEM
─────────────────────
• Laatste update: ${learningProfile.lastUpdated.toString("dd-MM-yyyy HH:mm")}
• Betrouwbaarheid: ${(learningProfile.learningConfidence * 100).toInt()}%
• Leersamples: ${learningProfile.totalLearningSamples}
• Carb ratio aanpassing: ${round(learningProfile.personalCarbRatio, 2)}
• ISF aanpassing: ${round(learningProfile.personalISF, 2)}

[ MAALTIJD FACTOREN ]
${learningProfile.mealTimingFactors.entries.joinToString("\n  ") { "${it.key.padEnd(10)}: ${round(it.value, 2)}" }}

[ RECENTE MAALTIJD PRESTATIES ]
$mealPerformanceSummary

[ PENDING UPDATES ]
• Learning updates: ${pendingLearningUpdates.size}
• Correction updates: ${pendingCorrectionUpdates.size}

[ DATA KWALITEIT ]
• Meal responses: ${storage.loadHistoricalMealData().size}
• Performance results: ${storage.loadMealPerformanceResults().size}
• Correction results: ${storage.loadCorrectionPerformanceResults().size}

""".trimIndent()
    }

    // ★★★ DATA EXPORT FUNCTIES ★★★
    fun exportLearningData(): String {
        val exportData = mapOf(
            "learningProfile" to learningProfile,
            "mealPerformanceResults" to storage.loadMealPerformanceResults().takeLast(50),
            "correctionPerformanceResults" to storage.loadCorrectionPerformanceResults().takeLast(50),
            "historicalMealData" to storage.loadHistoricalMealData().takeLast(100),
            "pendingLearningUpdates" to pendingLearningUpdates,
            "pendingCorrectionUpdates" to pendingCorrectionUpdates
        )

        return try {
            gson.toJson(exportData)
        } catch (e: Exception) {
            "Error exporting data: ${e.message}"
        }
    }

    // ★★★ CLEANUP FUNCTIES ★★★
    fun cleanupOldData() {
        try {
            // Behoud alleen recente data voor performance
            val recentMeals = storage.loadMealPerformanceResults().takeLast(100)
            val recentCorrections = storage.loadCorrectionPerformanceResults().takeLast(100)
            val recentHistorical = storage.loadHistoricalMealData().takeLast(500)

            // Clear and re-save only recent data
            storage.resetAllLearningData()

            recentMeals.forEach { storage.saveMealPerformanceResult(it) }
            recentCorrections.forEach { storage.saveCorrectionPerformanceResult(it) }
            recentHistorical.forEach { storage.saveMealResponseData(it) }

            // Save current profile
            storage.saveLearningProfile(learningProfile)

        } catch (e: Exception) {
            // Logging
        }
    }

    companion object {
        private val gson = Gson()
    }
}