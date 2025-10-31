package app.aaps.plugins.aps.openAPSFCL

import org.joda.time.DateTime

// ★★★ SHARED DATA CLASSES VOOR FCL SYSTEM ★★★

data class BGDataPoint(
    val timestamp: DateTime,
    val bg: Double,
    val iob: Double
)

// Andere data classes die mogelijk gedeeld moeten worden kunnen hier later toegevoegd worden

