package app.aaps.plugins.aps.openAPSFCL

import android.content.Context
import android.os.Environment
import com.google.gson.Gson
import org.joda.time.DateTime
import org.joda.time.format.DateTimeFormat
import org.joda.time.format.DateTimeFormatter
import java.io.File
import kotlin.math.*

class FCLMetrics(private val context: Context) {

    data class GlucoseMetrics(
        val period: String,
        val timeInRange: Double,           // 3.9-10.0 mmol/L
        val timeBelowRange: Double,        // <3.9 mmol/L
        val timeAboveRange: Double,        // >10.0 mmol/L
        val timeBelowTarget: Double,       // <5.2 mmol/L - NIEUW: meting agressiviteit
        val averageGlucose: Double,
        val gmi: Double,                   // Glucose Management Indicator
        val cv: Double,                    // Coefficient of Variation
        val totalReadings: Int,
        val lowEvents: Int,                // Aantal hypo events (<3.9)
        val veryLowEvents: Int,            // Aantal zeer lage events (<3.0)
        val highEvents: Int,               // Aantal hyper events (>13.9)
        val agressivenessScore: Double,    // NIEUW: score voor parameter agressiviteit
        val startDate: DateTime,
        val endDate: DateTime,
        val mealDetectionRate: Double,     // Percentage van tijd dat meal detected is
        val bolusDeliveryRate: Double,     // Percentage van adviezen die worden afgegeven
        val averageDetectedCarbs: Double,  // Gemiddelde gedetecteerde carbs
        val readingsPerHour: Double        // Gemiddeld aantal metingen per uur
    )

    data class ParameterAgressivenessAdvice(
        val parameterName: String,
        val currentValue: Double,
        val recommendedValue: Double,
        val reason: String,
        val confidence: Double,
        val expectedImprovement: String,
        val changeDirection: String // "INCREASE", "DECREASE", "OPTIMAL"
    )

    companion object {
        private const val TARGET_LOW = 3.9
        private const val TARGET_HIGH = 10.0
        private const val TARGET_IDEAL = 5.2  // Streefdoel voor agressiviteit meting
        private const val VERY_LOW_THRESHOLD = 3.0
        private const val VERY_HIGH_THRESHOLD = 13.9

        // Grenswaarden voor agressiviteit beoordeling
        private const val AGGRESSIVE_THRESHOLD = 15.0  // % tijd onder streefdoel
        private const val VERY_AGGRESSIVE_THRESHOLD = 25.0

        // Minimale data kwaliteit
        private const val MIN_READINGS_PER_HOUR = 8.0  // 8 van 12 metingen (5-min interval)
    }

    private val gson = Gson()
    // ★★★ CORRECTIE: Gebruik hetzelfde datumformaat als jouw CSV ★★★
    private val dateFormatter: DateTimeFormatter = DateTimeFormat.forPattern("dd-MM-yyyy HH:mm")




    private fun getCSVFile(): File {
        val externalDir = File(Environment.getExternalStorageDirectory().absolutePath + "/Documents/AAPS/")
        val analyseDir = File(externalDir, "ANALYSE")
        return File(analyseDir, "FCL_Analysis.csv")
    }

    private fun loadCSVData(hours: Int): List<CSVReading> {
        val csvFile = getCSVFile()
        if (!csvFile.exists()) {
            return emptyList()
        }

        val readings = mutableListOf<CSVReading>()
        val cutoffTime = DateTime.now().minusHours(hours)

        try {
            var isFirstLine = true
            var lineCount = 0
            var parsedCount = 0

            csvFile.forEachLine { line ->
                lineCount++
                if (isFirstLine) {
                    isFirstLine = false
                    return@forEachLine // Skip header
                }

                val parts = line.split(",")
                if (parts.size >= 15) { // Minimaal 15 kolommen nodig
                    try {
                        // ★★★ CORRECTIE: Gebruik het juiste datumformaat ★★★
                        val timestamp = dateFormatter.parseDateTime(parts[0])
                        if (timestamp.isAfter(cutoffTime)) {
                            val currentBG = parts[1].toDoubleOrNull() ?: return@forEachLine
                            val currentIOB = parts[2].toDoubleOrNull() ?: 0.0
                            val dose = parts[4].toDoubleOrNull() ?: 0.0

                            // ★★★ CORRECTIE: Verbeterde boolean parsing ★★★
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
                            parsedCount++
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

    fun calculateMetrics(hours: Int = 24): GlucoseMetrics {
        return try {
            val data = loadCSVData(hours)
            if (data.isEmpty()) {
                return createEmptyMetrics(hours)
            }

            calculateGlucoseMetrics(data, hours)
        } catch (e: Exception) {
            createEmptyMetrics(hours)
        }
    }

    fun getDataQualityMetrics(hours: Int = 24): DataQualityMetrics {
        return try {
            val data = loadCSVData(hours)
            if (data.isEmpty()) {
                return DataQualityMetrics(
                    totalReadings = 0,
                    expectedReadings = hours * 12, // 12 metingen per uur (5-min interval)
                    dataCompleteness = 0.0,
                    periodHours = hours,
                    hasSufficientData = false
                )
            }

            val expectedReadings = hours * 12
            val completeness = (data.size.toDouble() / expectedReadings) * 100.0

            DataQualityMetrics(
                totalReadings = data.size,
                expectedReadings = expectedReadings,
                dataCompleteness = completeness,
                periodHours = hours,
                hasSufficientData = completeness > 60.0 // Minimaal 60% complete data
            )
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

    fun calculateAgressivenessAdvice(
        parameters: FCLParameters,
        metrics: GlucoseMetrics
    ): List<ParameterAgressivenessAdvice> {
        val adviceList = mutableListOf<ParameterAgressivenessAdvice>()

        // Analyseer alleen als we voldoende data hebben
        if (metrics.totalReadings < 50 || metrics.readingsPerHour < MIN_READINGS_PER_HOUR) {
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

        // ★★★ AGRESSIVITEIT ANALYSE OP BASIS VAN TIJD ONDER STREEFDOEL ★★★
        when {
            metrics.timeBelowTarget > VERY_AGGRESSIVE_THRESHOLD -> {
                // Zeer agressieve instellingen - significante reductie aanbevolen
                adviceList.addAll(analyzeVeryAggressiveParameters(parameters, metrics))
            }
            metrics.timeBelowTarget > AGGRESSIVE_THRESHOLD -> {
                // Agressieve instellingen - matige reductie aanbevolen
                adviceList.addAll(analyzeAggressiveParameters(parameters, metrics))
            }
            metrics.timeBelowTarget < 5.0 && metrics.timeAboveRange > 20.0 -> {
                // Te conservatief - mogelijk ruimte voor verhoging
                adviceList.addAll(analyzeConservativeParameters(parameters, metrics))
            }
        }

        // Voeg algemeen advies toe gebaseerd op TIR en hypo events
        adviceList.addAll(analyzeSafetyParameters(parameters, metrics))

        // Voeg meal detection advies toe
        adviceList.addAll(analyzeMealDetectionParameters(parameters, metrics))

        return adviceList.take(5) // Beperk tot 5 belangrijkste adviezen
    }

    private fun analyzeVeryAggressiveParameters(
        parameters: FCLParameters,
        metrics: GlucoseMetrics
    ): List<ParameterAgressivenessAdvice> {
        val advice = mutableListOf<ParameterAgressivenessAdvice>()
        val allParams = parameters.getAllParameters()

        // Focus op hoog-impact bolus parameters
        val highImpactBolusParams = allParams.values.filter {
            it.definition.category == "BOLUS" && it.definition.impactLevel == "HIGH"
        }

        highImpactBolusParams.forEach { param ->
            val reduction = calculateSafeReduction(param.current, param.definition.minValue)
            advice.add(
                ParameterAgressivenessAdvice(
                    parameterName = param.definition.name,
                    currentValue = param.current,
                    recommendedValue = reduction,
                    reason = "Zeer agressieve instelling (${metrics.timeBelowTarget.toInt()}% tijd onder streefdoel)",
                    confidence = 0.9,
                    expectedImprovement = "Verminder hypo's met ~${(metrics.timeBelowTarget * 0.6).toInt()}%",
                    changeDirection = "DECREASE"
                )
            )
        }

        return advice
    }

    private fun analyzeAggressiveParameters(
        parameters: FCLParameters,
        metrics: GlucoseMetrics
    ): List<ParameterAgressivenessAdvice> {
        val advice = mutableListOf<ParameterAgressivenessAdvice>()
        val allParams = parameters.getAllParameters()

        // Matige reductie voor bolus parameters
        val bolusParams = allParams.values.filter { it.definition.category == "BOLUS" }

        bolusParams.take(3).forEach { param ->  // Top 3 meest agressieve
            val reduction = calculateModerateReduction(param.current, param.definition.minValue)
            advice.add(
                ParameterAgressivenessAdvice(
                    parameterName = param.definition.name,
                    currentValue = param.current,
                    recommendedValue = reduction,
                    reason = "Agressieve instelling (${metrics.timeBelowTarget.toInt()}% tijd onder streefdoel)",
                    confidence = 0.7,
                    expectedImprovement = "Verhoog TIR met ~${(100 - metrics.timeInRange).toInt()}%",
                    changeDirection = "DECREASE"
                )
            )
        }

        return advice
    }

    private fun analyzeConservativeParameters(
        parameters: FCLParameters,
        metrics: GlucoseMetrics
    ): List<ParameterAgressivenessAdvice> {
        val advice = mutableListOf<ParameterAgressivenessAdvice>()

        val allParams = parameters.getAllParameters()
        val bolusParams = allParams.values.filter {
            it.definition.category == "BOLUS" && it.definition.impactLevel == "HIGH"
        }

        bolusParams.take(2).forEach { param ->
            val increase = calculateSafeIncrease(param.current, param.definition.maxValue)
            advice.add(
                ParameterAgressivenessAdvice(
                    parameterName = param.definition.name,
                    currentValue = param.current,
                    recommendedValue = increase,
                    reason = "Conservatieve instelling (${metrics.timeBelowTarget.toInt()}% onder streefdoel, ${metrics.timeAboveRange.toInt()}% boven range)",
                    confidence = 0.6,
                    expectedImprovement = "Verlaag hyper's met ~${(metrics.timeAboveRange * 0.4).toInt()}%",
                    changeDirection = "INCREASE"
                )
            )
        }

        return advice
    }

    private fun analyzeSafetyParameters(
        parameters: FCLParameters,
        metrics: GlucoseMetrics
    ): List<ParameterAgressivenessAdvice> {
        val advice = mutableListOf<ParameterAgressivenessAdvice>()

        // Analyseer veiligheid op basis van hypo events
        if (metrics.lowEvents > 3 || metrics.veryLowEvents > 1) {
            val allParams = parameters.getAllParameters()
            val safetyParams = allParams.values.filter {
                it.definition.category == "SAFETY"
            }

            safetyParams.forEach { param ->
                val increase = calculateSafeIncrease(param.current, param.definition.maxValue)
                advice.add(
                    ParameterAgressivenessAdvice(
                        parameterName = param.definition.name,
                        currentValue = param.current,
                        recommendedValue = increase,
                        reason = "Veiligheidsaanpassing wegens ${metrics.lowEvents} hypo events",
                        confidence = 0.8,
                        expectedImprovement = "Verminder hypo events met ~${(metrics.lowEvents * 0.5).toInt()}",
                        changeDirection = "INCREASE"
                    )
                )
            }
        }

        return advice
    }

    private fun analyzeMealDetectionParameters(
        parameters: FCLParameters,
        metrics: GlucoseMetrics
    ): List<ParameterAgressivenessAdvice> {
        val advice = mutableListOf<ParameterAgressivenessAdvice>()

        // Analyseer meal detection effectiviteit
        if (metrics.mealDetectionRate < 10.0 && metrics.timeAboveRange > 25.0) {
            val allParams = parameters.getAllParameters()
            val mealParams = allParams.values.filter {
                it.definition.category == "MEAL"
            }

            mealParams.forEach { param ->
                val increase = calculateSafeIncrease(param.current, param.definition.maxValue)
                advice.add(
                    ParameterAgressivenessAdvice(
                        parameterName = param.definition.name,
                        currentValue = param.current,
                        recommendedValue = increase,
                        reason = "Lage meal detection rate (${metrics.mealDetectionRate.toInt()}%) met hoge hyper's",
                        confidence = 0.6,
                        expectedImprovement = "Verhoog meal detectie en verlaag hyper's",
                        changeDirection = "INCREASE"
                    )
                )
            }
        }

        return advice
    }

    private fun calculateSafeReduction(current: Double, minValue: Double): Double {
        val reduction = current * 0.8  // 20% reductie
        return max(reduction, minValue)
    }

    private fun calculateModerateReduction(current: Double, minValue: Double): Double {
        val reduction = current * 0.9  // 10% reductie
        return max(reduction, minValue)
    }

    private fun calculateSafeIncrease(current: Double, maxValue: Double): Double {
        val increase = current * 1.1  // 10% verhoging
        return min(increase, maxValue)
    }



    private fun calculateGlucoseMetrics(data: List<CSVReading>, hours: Int): GlucoseMetrics {
        val bgValues = data.map { it.currentBG }

        // Basis metrics
        val timeInRange = calculateTimeInRange(data)
        val timeBelowRange = calculateTimeBelowRange(data)
        val timeAboveRange = calculateTimeAboveRange(data)
        val timeBelowTarget = calculateTimeBelowTarget(data)
        val averageGlucose = if (bgValues.isNotEmpty()) bgValues.average() else 0.0
        val gmi = calculateGMI(averageGlucose)
        val cv = calculateCV(bgValues)

        // Events tellen
        val (lowEvents, veryLowEvents) = countLowEvents(data)
        val highEvents = countHighEvents(data)

        // Agressiviteit score berekenen
        val agressivenessScore = calculateAgressivenessScore(timeBelowTarget, lowEvents, veryLowEvents)

        // Meal detection en bolus metrics
        val mealDetectionRate = calculateMealDetectionRate(data)
        val bolusDeliveryRate = calculateBolusDeliveryRate(data)
        val averageDetectedCarbs = calculateAverageDetectedCarbs(data)

        // Data kwaliteit
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
        val belowTarget = data.count { it.currentBG < TARGET_IDEAL }
        return (belowTarget.toDouble() / data.size) * 100.0
    }

    private fun calculateGMI(averageGlucose: Double): Double {
        if (averageGlucose <= 0.0) return 0.0
        // Glucose Management Indicator (geschatte HbA1c)
        return 3.31 + (0.02392 * averageGlucose * 18) / 1.0  // Conversie naar mg/dL voor formule
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

        // Basis score op tijd onder streefdoel
        score += min(timeBelowTarget / 10.0, 5.0)  // Max 5 punten

        // Straffen voor hypo events
        score += lowEvents * 0.5
        score += veryLowEvents * 2.0

        return min(score, 10.0)  // Schaal van 0-10
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
}