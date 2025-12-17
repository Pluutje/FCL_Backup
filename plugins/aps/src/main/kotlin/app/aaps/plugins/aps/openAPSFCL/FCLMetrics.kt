package app.aaps.plugins.aps.openAPSFCL

import android.content.Context
import android.os.Environment
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.keys.Preferences
import org.joda.time.DateTime
import org.joda.time.Minutes
import java.io.File
import java.util.Locale
import kotlin.math.min
import kotlin.math.sqrt

class FCLMetrics(
    private val context: Context,
    private val preferences: Preferences,
    private val persistenceLayer: PersistenceLayer   // ← NIEUW
) {

    // ─────────────────────────────────────────────
    // PUBLIC API (wat FCL / UI nodig heeft)
    // ─────────────────────────────────────────────

    fun getUserStatsString(): String = optimizationController.getUserStatsString()

    /**
     * Wordt aangeroepen vanuit FCL (mag no-op zijn voor nu).
     * We houden de signature zodat je FCL niet opnieuw hoeft te refactoren.
     */
    fun onFiveMinuteTick(currentBG: Double, currentIOB: Double, context: FCL.FCLContext) {
        // Alleen cache verversen voor UI.
        // Logging gebeurt via logMetricsTick() vanuit FCL.kt.
        try {
            optimizationController.updateUserStatsCache()
        } catch (_: Exception) {
            // nooit laten crashen
        }
    }

    /**
     * Wordt vanuit FCL aangeroepen om te loggen naar metrics CSV.
     * 4 kolommen: timestamp,bg,iob,target
     */
    fun logMetricsTick(
        timestamp: DateTime = DateTime.now(),
        bg: Double,
        iob: Double,
        target: Double
    ) {
        try {
            val file = getMetricsCSVFile()

            // header indien nodig
            if (!file.exists() || file.length() == 0L) {
                file.parentFile?.mkdirs()
                file.writeText("timestamp,bg,iob,target\n")
            }

            // schrijf regel

            val ts = timestamp.toString("dd-MM-yyyy HH:mm")
            val line = "${ts},${roundN(bg, 1)},${roundN(iob, 2)},${roundN(target, 1)}\n"
            file.appendText(line)

            // retentie (max 7 dagen)
            maybeCleanupRetention(file)

        } catch (_: Exception) {
            // metrics logging mag NOOIT FCL blokkeren
        }
    }

    /**
     * FCL riep dit bij jou aan; houden als no-op zodat je geen compile errors krijgt.
     */
    fun updateLastAdvice(advice: FCL.EnhancedInsulinAdvice) {
        // Niet nodig voor metrics-only CSV
    }

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

    // ★★★ UNIFORME PARAMETER ADVIES CLASS ★★★
    data class ParameterAdvice(
        val parameterName: String,
        val currentValue: Double,
        val recommendedValue: Double,
        val reason: String,
        val confidence: Double,
        val direction: String
    )

    // ─────────────────────────────────────────────
    // DATA STRUCTS (alleen wat we nodig hebben)
    // ─────────────────────────────────────────────

    data class DataQualityMetrics(
        val totalReadings: Int,
        val expectedReadings: Int,
        val dataCompleteness: Double,
        val periodHours: Int,
        val hasSufficientData: Boolean
    )

    data class GlucoseStats(
        val timeInRange: Double,
        val timeAboveRange: Double,
        val timeBelowRange: Double,
        val timeBelowTarget: Double,
        val averageGlucose: Double,
        val gmi: Double,
        val cv: Double,
        val readingsPerHour: Double
    )

    data class UserStatsCache(
        val lastUpdated: DateTime,
        val dataQuality24h: DataQualityMetrics,
        val metrics24h: GlucoseStats,
        val metrics7d: GlucoseStats
    )

    private data class MetricsReading(
        val timestamp: DateTime,
        val bg: Double,
        val iob: Double,
        val target: Double
    )

    companion object {

        private const val TARGET_LOW = 3.9
        private const val TARGET_HIGH = 10.0
        private const val VERY_LOW_THRESHOLD = 3.0

        // fallback target (alleen gebruikt als target niet in CSV staat, maar dat staat er wél)
        private var Target_Bg: Double = 5.2

        private const val RETENTION_DAYS = 7
        private const val CLEANUP_CHECK_INTERVAL_MS = 24 * 60 * 60 * 1000L
    }

    fun setTargetBg(value: Double) {
        Target_Bg = value
    }

    // ─────────────────────────────────────────────
    // FILE IO
    // ─────────────────────────────────────────────

    private var lastCleanupCheck: DateTime? = null

    private fun getMetricsCSVFile(): File {
        val basePath = Environment.getExternalStorageDirectory().absolutePath +
            "/Documents/AAPS/ANALYSE"
        val dir = File(basePath)
        if (!dir.exists()) dir.mkdirs()
        return File(dir, "FCL_Metrics.csv")
    }

    private fun maybeCleanupRetention(file: File) {
        val now = DateTime.now()
        val shouldCheck = lastCleanupCheck?.let { now.millis - it.millis > CLEANUP_CHECK_INTERVAL_MS } ?: true
        if (!shouldCheck) return

        lastCleanupCheck = now
        cleanupRetention(file)
    }

    private fun cleanupRetention(file: File) {
        if (!file.exists() || file.length() == 0L) return

        val cutoff = DateTime.now().minusDays(RETENTION_DAYS)

        try {
            val lines = file.readLines()
            if (lines.isEmpty()) return

            val header = lines.first()
            val data = lines.drop(1)

            val kept = data.filter { line ->
                try {
                    val parts = line.split(",")
                    if (parts.size < 4) return@filter false
                    val ts = DateTime.parse(parts[0])
                    ts.isAfter(cutoff)
                } catch (_: Exception) {
                    // bij parse-fout: behoud liever dan per ongeluk te gooien
                    true
                }
            }

            // herschrijf alleen als er iets weg kan
            if (kept.size < data.size) {
                file.writeText(header + "\n")
                if (kept.isNotEmpty()) file.appendText(kept.joinToString("\n") + "\n")
            }
        } catch (_: Exception) {
            // silent fail
        }
    }

    private fun getBgReadingsFromAAPS(
        start: DateTime,
        end: DateTime
    ): List<Pair<DateTime, Double>> {

        val MG_DL_TO_MMOL = 18.0

        val readings = persistenceLayer.getBgReadingsDataFromTimeToTime(
            start.millis,
            end.millis,
            false
        )

        if (readings.isEmpty()) return emptyList()

        return readings.mapNotNull {
            val mmol = it.value / MG_DL_TO_MMOL
            if (mmol > 0) {
                Pair(DateTime(it.timestamp), mmol)
            } else {
                null
            }
        }
    }



    private fun roundN(x: Double, decimals: Int): Double {
        return "%.${decimals}f".format(Locale.US, x).toDouble()
    }

    // ─────────────────────────────────────────────
    // OPTIMIZATION CONTROLLER (UI stats)
    // ─────────────────────────────────────────────

    private val optimizationController = FCLOptimizationController()

    inner class FCLOptimizationController {

        private var userStatsCache: UserStatsCache? = null

        fun updateUserStatsCache() {
            val dq24 = computeDataQuality(24)
            val m24 = computeGlucoseStats(24)
            val m7d = computeGlucoseStats(24 * 7)

            userStatsCache = UserStatsCache(
                lastUpdated = DateTime.now(),
                dataQuality24h = dq24,
                metrics24h = m24,
                metrics7d = m7d
            )
        }

        fun getUserStatsString(): String {
            if (userStatsCache == null) updateUserStatsCache()
            val cache = userStatsCache ?: return "Statistieken worden verzameld…"

            val dq24 = cache.dataQuality24h
            val m24 = cache.metrics24h
            val m7 = cache.metrics7d

            return """
📊 GLUCOSE STATISTIEKEN
─────────────────────
[ TIR (3.9–10.0 mmol/L) ]
   • 24u: ${m24.timeInRange.toInt()}% 
   • 7d : ${m7.timeInRange.toInt()}%

[⏰ UPDATES]
• Laatste update: ${cache.lastUpdated.toString("HH:mm")}

[ DATA KWALITEIT - 24U ]
• Metingen: ${dq24.totalReadings}/${dq24.expectedReadings}
• Completeheid: ${dq24.dataCompleteness.toInt()}% ${if (!dq24.hasSufficientData) "⚠️" else "✅"}
• Metingen per uur: ${m24.readingsPerHour.toInt()}/12 ${if (m24.readingsPerHour < 8) "⚠️" else "✅"}

[ LAATSTE 24 UUR ]
• Time in Range: ${m24.timeInRange.toInt()}% 
• Time Above Range: ${m24.timeAboveRange.toInt()}%
• Time Below Range: ${m24.timeBelowRange.toInt()}%
• Time Below Target: ${m24.timeBelowTarget.toInt()}%
• Gemiddelde glucose: ${roundN(m24.averageGlucose, 1)} mmol/L
• GMI (HbA1c): ${gmiPercentToMmolMol(m24.gmi)} (${roundN(m24.gmi, 1)}%)
• Variatie (CV): ${m24.cv.toInt()}%

[ LAATSTE 7 DAGEN ]
• Time in Range: ${m7.timeInRange.toInt()}%
• Time Above Range: ${m7.timeAboveRange.toInt()}%
• Time Below Range: ${m7.timeBelowRange.toInt()}%
• Time Below Target: ${m7.timeBelowTarget.toInt()}%
• Gemiddelde glucose: ${roundN(m7.averageGlucose, 1)} mmol/L
• GMI (HbA1c): ${gmiPercentToMmolMol(m7.gmi)} (${roundN(m7.gmi, 1)}%)
• Variatie (CV): ${m7.cv.toInt()}%
""".trimIndent()
        }

        private fun computeDataQuality(hours: Int): DataQualityMetrics {
            val end = DateTime.now()
            val start = end.minusHours(hours)

            val readings = getBgReadingsFromAAPS(start, end)
            val total = readings.size

            val expected = hours * 12 // 5-min interval
            val completeness = if (expected > 0) (total.toDouble() / expected.toDouble()) * 100.0 else 0.0

            return DataQualityMetrics(
                totalReadings = total,
                expectedReadings = expected,
                dataCompleteness = completeness,
                periodHours = hours,
                hasSufficientData = completeness >= 70.0
            )
        }

        private fun computeGlucoseStats(hours: Int): GlucoseStats {
            val end = DateTime.now()
            val start = end.minusHours(hours)

            val readings = getBgReadingsFromAAPS(start, end)
            if (readings.size < 2) {
                return GlucoseStats(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
            }

            val sorted = readings.sortedBy { it.first.millis }
            val bgValues = sorted.map { it.second }
            val avg = bgValues.average()

            val target = Target_Bg   // ✅ vaste target voor metrics

            var timeInRangeH = 0.0
            var timeAboveH = 0.0
            var timeBelowH = 0.0
            var timeBelowTargetH = 0.0

            for (i in 0 until sorted.size - 1) {
                val a = sorted[i]
                val b = sorted[i + 1]

                val minutesBetween = Minutes.minutesBetween(a.first, b.first).minutes
                if (minutesBetween <= 0) continue

                val hoursBetween = minutesBetween / 60.0
                val avgBG = (a.second + b.second) / 2.0

                when {
                    avgBG in TARGET_LOW..TARGET_HIGH -> timeInRangeH += hoursBetween
                    avgBG > TARGET_HIGH              -> timeAboveH += hoursBetween
                    avgBG < TARGET_LOW               -> timeBelowH += hoursBetween
                }

                if (avgBG < target) timeBelowTargetH += hoursBetween
            }

            val totalClassifiedHours = timeInRangeH + timeAboveH + timeBelowH
            val tir = percent(timeInRangeH, totalClassifiedHours)
            val tar = percent(timeAboveH, totalClassifiedHours)
            val tbr = percent(timeBelowH, totalClassifiedHours)
            val tbt = percent(timeBelowTargetH, totalClassifiedHours)

            val std = sqrt(bgValues.map { (it - avg) * (it - avg) }.average())
            val cv = if (avg > 0) (std / avg) * 100.0 else 0.0

            val gmi = 3.31 + 0.02392 * (avg * 18.0)

            val readingsPerHour = readings.size.toDouble() / hours.toDouble()

            return GlucoseStats(
                timeInRange = tir,
                timeAboveRange = tar,
                timeBelowRange = tbr,
                timeBelowTarget = tbt,
                averageGlucose = avg,
                gmi = gmi,
                cv = cv,
                readingsPerHour = readingsPerHour
            )
        }

        // ─────────────────────────────────────────────
        // HELPERS
        // ─────────────────────────────────────────────

        private fun computeTotalHours(sorted: List<Pair<DateTime, Double>>): Double {
            if (sorted.size < 2) return 0.0
            val start = sorted.first().first
            val end = sorted.last().first
            val minutes = Minutes.minutesBetween(start, end).minutes
            return minutes / 60.0
        }



        private fun percent(part: Double, total: Double): Double {
            if (total <= 0.0) return 0.0
            return (part / total) * 100.0
        }

        private fun gmiPercentToMmolMol(gmiPercent: Double): Int {
            // IFCC conversie
            return ((gmiPercent - 2.15) * 10.929).toInt()
        }

    }
}