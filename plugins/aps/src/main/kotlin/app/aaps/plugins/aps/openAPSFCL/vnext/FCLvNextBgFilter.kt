package app.aaps.plugins.aps.openAPSFCL.vnext

import org.joda.time.DateTime

object FCLvNextBgFilter {

    /**
     * Exponentially weighted moving average
     * alpha ∈ (0..1), hoger = minder smoothing
     */
    fun ewma(
        data: List<Pair<DateTime, Double>>,
        alpha: Double = 0.4
    ): List<Pair<DateTime, Double>> {

        if (data.isEmpty()) return data

        val result = mutableListOf<Pair<DateTime, Double>>()
        var last = data.first().second

        for ((t, bg) in data) {
            last = alpha * bg + (1 - alpha) * last
            result.add(t to last)
        }

        return result
    }
}
