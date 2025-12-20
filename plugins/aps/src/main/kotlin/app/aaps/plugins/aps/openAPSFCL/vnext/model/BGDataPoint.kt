package app.aaps.plugins.aps.openAPSFCL.vnext.model

import org.joda.time.DateTime

data class BGDataPoint(
    val timestamp: DateTime,
    val bg: Double,     // mmol/L
    val iob: Double     // U
)
