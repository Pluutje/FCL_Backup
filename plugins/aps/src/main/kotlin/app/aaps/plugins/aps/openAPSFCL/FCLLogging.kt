package app.aaps.plugins.aps.openAPSFCL

import android.content.Context
import org.joda.time.DateTime
import java.io.File
import android.os.Environment
import kotlin.math.round

// ★★★ IMPORT DE NODIGE DATA CLASSES ★★★
import app.aaps.plugins.aps.openAPSFCL.FCL.EnhancedInsulinAdvice
import app.aaps.plugins.aps.openAPSFCL.FCL.BGDataPoint

class FCLLogging(private val context: Context) {

    private var lastCleanupCheck: DateTime? = null
    private val CLEANUP_CHECK_INTERVAL = 24 * 60 * 60 * 1000L // 24 uur

    fun logToDetailedCSV(
        fclAdvice: EnhancedInsulinAdvice,
        currentData: BGDataPoint,
        currentISF: Double,
        currentIOB: Double
    ) {
        try {
            val dateStr = DateTime.now().toString("dd-MM-yyyy HH:mm")

            val csvLine = buildString {
                // Basis data
                append("$dateStr,")                      // datum
                append("${round(currentData.bg, 1)},")
                append("${round(currentIOB, 3)},")
                append("${round(currentISF, 1)},")
                append("${round(fclAdvice.dose, 2)},")
                append("${round(fclAdvice.dose, 2)},")  // lastCalculatedBolus is niet meer beschikbaar, gebruik gewoon dose
                append("${fclAdvice.shouldDeliverBolus},")
                append("${round(fclAdvice.reservedDose, 2)},")

                // Reden (escape quotes)
                append("\"${fclAdvice.reason.replace("\"", "'")}\",")

                // Voorspelling en confidence
                append("${fclAdvice.predictedValue?.let { round(it, 1) } ?: "null"},")
                append("${round(fclAdvice.confidence, 2)},")
                append("${fclAdvice.mealDetected},")
                append("${fclAdvice.phase},")
                append("${round(fclAdvice.detectedCarbs, 1)},")
                append("${round(fclAdvice.carbsOnBoard, 1)},")

                // Blocked reason - alleen vullen als shouldDeliverBolus false is
                append("\"${if (!fclAdvice.shouldDeliverBolus) getBlockedReason(fclAdvice) else ""}\"")
            }

            // Write to CSV file with retention management
            writeToCSVFileWithRetention(csvLine)

        } catch (e: Exception) {
            // Silent fail - don't disrupt normal operation
        }
    }

    private fun getBlockedReason(fclAdvice: EnhancedInsulinAdvice): String {
        return when {
            fclAdvice.reason.contains("IOB", ignoreCase = true) && fclAdvice.mathSlope > 2.0 ->
                "OVERRIDE: Rising despite IOB (slope: ${"%.1f".format(fclAdvice.mathSlope)})"
            fclAdvice.reason.contains("stable", ignoreCase = true) && fclAdvice.mathSlope > 1.0 ->
                "OVERRIDE: Actually rising (slope: ${"%.1f".format(fclAdvice.mathSlope)})"
            fclAdvice.reason.contains("IOB", ignoreCase = true) -> "High IOB blocking delivery"
            fclAdvice.reason.contains("stable", ignoreCase = true) -> "Stable/declining trend"
            fclAdvice.reason.contains("target", ignoreCase = true) -> "Near target range"
            fclAdvice.reason.contains("activity", ignoreCase = true) -> "Activity mode active"
            fclAdvice.reason.contains("safety", ignoreCase = true) -> "Safety protection active"
            fclAdvice.reason.contains("hypo", ignoreCase = true) -> "Hypo protection"
            else -> "Algorithm: ${fclAdvice.reason.take(50)}"  // Toon echte reden
        }
    }

    private fun writeToCSVFileWithRetention(csvLine: String) {
        try {
            val csvFile = getOrCreateCSVFile()
            val now = DateTime.now()

            // ★★★ EENMALIG PER DAG RETENTIE CONTROLEREN ★★★
            val shouldCheckCleanup = lastCleanupCheck?.let {
                now.millis - it.millis > CLEANUP_CHECK_INTERVAL
            } ?: true

            if (shouldCheckCleanup) {
                cleanupOldCSVFiles()
                lastCleanupCheck = now
            }

            // Schrijf header als bestand nieuw is
            writeCSVHeader(csvFile)

            // Schrijf naar file
            csvFile.appendText("$csvLine\n")

        } catch (e: Exception) {
            // Silent fail
        }
    }

    private fun getOrCreateCSVFile(): File {
        val externalDir = File(Environment.getExternalStorageDirectory().absolutePath + "/Documents/AAPS/")
        val analyseDir = File(externalDir, "ANALYSE")
        if (!analyseDir.exists()) {
            analyseDir.mkdirs()
        }
        return File(analyseDir, "FCL_Analysis.csv")
    }

    private fun cleanupOldCSVFiles() {
        val csvFile = getOrCreateCSVFile()
        performRetentionCleanup(csvFile)
    }
    private fun performRetentionCleanup(csvFile: File) {
        if (!csvFile.exists() || csvFile.length() == 0L) return

        try {
            val lines = csvFile.readLines()
            if (lines.size <= 1) return // Alleen header, niets te doen

            val header = lines[0]
            val dataLines = lines.subList(1, lines.size)

            val cutoffDate = DateTime.now().minusDays(7)

            val filteredLines = dataLines.filter { line ->
                try {
                    // Extract date from first column (format: "dd-MM-yyyy HH:mm")
                    val datePart = line.substringBefore(',')
                    val lineDate = DateTime.parse(datePart,
                                                  org.joda.time.format.DateTimeFormat.forPattern("dd-MM-yyyy HH:mm"))
                    lineDate.isAfter(cutoffDate)
                } catch (e: Exception) {
                    // Bij parse fout, behoud de regel voor veiligheid
                    true
                }
            }

            // ★★★ ALLEEN HERSCRIJVEN ALS ER REGELS ZIJN VERWIJDERD ★★★
            if (filteredLines.size < dataLines.size) {
                csvFile.writeText(header + "\n")
                if (filteredLines.isNotEmpty()) {
                    csvFile.appendText(filteredLines.joinToString("\n") + "\n")
                }
            }

        } catch (e: Exception) {
            // Silent fail - behoud bestaand bestand bij errors
        }
    }

    private fun writeCSVHeader(csvFile: File) {
        try {
            if (!csvFile.exists() || csvFile.length() == 0L) {
                val header = "Timestamp,CurrentBG,CurrentIOB,CurrentISF,Dose,LastCalculatedBolus,ShouldDeliver,ReservedDose,Reason,PredictedValue,Confidence,MealDetected,Phase,DetectedCarbs,CarbsOnBoard,BlockedReason\n"
                csvFile.writeText(header)
            }
        } catch (e: Exception) {
            // Silent fail
        }
    }

    private fun round(value: Double, digits: Int): Double {
        val scale = Math.pow(10.0, digits.toDouble())
        return Math.round(value * scale) / scale
    }


}

