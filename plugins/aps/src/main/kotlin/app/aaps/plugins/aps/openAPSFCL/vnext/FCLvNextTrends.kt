package app.aaps.plugins.aps.openAPSFCL.vnext

import org.joda.time.DateTime
import org.joda.time.Minutes
import kotlin.math.abs
import kotlin.math.sign

/**
 * PURE TREND ENGINE
 * Input: BG tijdserie (mmol/L)
 * Output: robuuste trendkenmerken
 */
object FCLvNextTrends {

    // ─────────────────────────────────────────────
    // DATA TYPES
    // ─────────────────────────────────────────────

    data class BGPoint(
        val time: DateTime,
        val bg: Double          // mmol/L
    )

    data class RobustTrendAnalysis(
        val firstDerivative: Double,    // mmol/L per uur
        val secondDerivative: Double,   // mmol/L per uur²
        val consistency: Double,        // 0..1
        val directionConsistency: Double, // 0..1
        val magnitudeConsistency: Double, // 0..1
        val phase: Phase
    )

    enum class Phase {
        RISING,
        FALLING,
        STABLE,
        ACCELERATING_UP,
        ACCELERATING_DOWN,
        UNKNOWN
    }

    // ─────────────────────────────────────────────
    // PUBLIC ENTRY POINT
    // ─────────────────────────────────────────────

    fun calculateTrends(data: List<BGPoint>): RobustTrendAnalysis {
        if (data.size < 5) {
            return RobustTrendAnalysis(
                firstDerivative = 0.0,
                secondDerivative = 0.0,
                consistency = 0.0,
                directionConsistency = 0.0,
                magnitudeConsistency = 0.0,
                phase = Phase.UNKNOWN
            )
        }

        val slopes = calculateSlopes(data)
        val first = slopes.average()
        val second = calculateSecondDerivative(slopes)

        val dirConsistency = calculateDirectionConsistency(slopes)
        val magConsistency = calculateMagnitudeConsistency(slopes)

        val consistency = (dirConsistency * 0.6 + magConsistency * 0.4)
            .coerceIn(0.0, 1.0)

        val phase = determinePhase(first, second, consistency)

        return RobustTrendAnalysis(
            firstDerivative = first,
            secondDerivative = second,
            consistency = consistency,
            directionConsistency = dirConsistency,
            magnitudeConsistency = magConsistency,
            phase = phase
        )
    }

    // ─────────────────────────────────────────────
    // INTERNAL HELPERS
    // ─────────────────────────────────────────────

    private fun calculateSlopes(data: List<BGPoint>): List<Double> {
        val slopes = mutableListOf<Double>()

        for (i in 1 until data.size) {
            val dtMin = Minutes.minutesBetween(data[i - 1].time, data[i].time).minutes
            if (dtMin <= 0) continue

            val dtHr = dtMin / 60.0
            val delta = data[i].bg - data[i - 1].bg
            slopes.add(delta / dtHr)
        }

        return slopes
    }

    private fun calculateSecondDerivative(slopes: List<Double>): Double {
        if (slopes.size < 3) return 0.0

        val accel = mutableListOf<Double>()
        for (i in 1 until slopes.size) {
            accel.add(slopes[i] - slopes[i - 1])
        }
        return accel.average()
    }

    private fun calculateDirectionConsistency(slopes: List<Double>): Double {
        if (slopes.isEmpty()) return 0.0

        val signs = slopes.map { sign(it) }.filter { it != 0.0 }
        if (signs.isEmpty()) return 0.0

        val dominant = signs.groupingBy { it }.eachCount().maxByOrNull { it.value }!!
        return dominant.value.toDouble() / signs.size
    }

    private fun calculateMagnitudeConsistency(slopes: List<Double>): Double {
        if (slopes.size < 2) return 0.0

        val mags = slopes.map { abs(it) }
        val avg = mags.average()
        if (avg == 0.0) return 0.0

        val deviations = mags.map { abs(it - avg) / avg }
        val stability = 1.0 - deviations.average()

        return stability.coerceIn(0.0, 1.0)
    }

    private fun determinePhase(
        first: Double,
        second: Double,
        consistency: Double
    ): Phase {
        if (consistency < 0.3) return Phase.UNKNOWN

        return when {
            first > 0.3 && second > 0.1 -> Phase.ACCELERATING_UP
            first < -0.3 && second < -0.1 -> Phase.ACCELERATING_DOWN
            first > 0.2 -> Phase.RISING
            first < -0.2     -> Phase.FALLING
            abs(first) < 0.2 -> Phase.STABLE
            else             -> Phase.UNKNOWN
        }
    }
}