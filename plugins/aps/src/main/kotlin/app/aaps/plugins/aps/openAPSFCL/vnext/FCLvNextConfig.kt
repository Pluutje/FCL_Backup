package app.aaps.plugins.aps.openAPSFCL.vnext

import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.Preferences

data class FCLvNextConfig(
    // dynamiek
    val kDelta: Double,
    val kSlope: Double,
    val kAccel: Double,

    // betrouwbaarheid
    val minConsistency: Double,
    val consistencyExp: Double,

    // IOB veiligheid
    val iobStart: Double,
    val iobMax: Double,
    val iobMinFactor: Double,

    // gain
    val gain: Double,

    // execution
    val hybridPercentage: Int,

    // ── Persistent High BG ──
    val persistentDeltaTarget: Double,
    val persistentMaxSlope: Double,
    val persistentIobLimit: Double,
    val persistentFraction: Double,

    // SMB safety
    val maxSMB: Double
)

fun loadFCLvNextConfig(
    prefs: Preferences,
    isNight: Boolean
): FCLvNextConfig {

    val gain : Double
    val maxSMB : Double

    if (isNight) {
        gain = prefs.get(DoubleKey.fcl_vnext_gain_night)
        maxSMB = prefs.get(DoubleKey.max_bolus_night)
    } else {
        gain = prefs.get(DoubleKey.fcl_vnext_gain_day)
        maxSMB = prefs.get(DoubleKey.max_bolus_day)
    }

    return FCLvNextConfig(
        // dynamiek
        kDelta = prefs.get(DoubleKey.fcl_vnext_k_delta),
        kSlope = prefs.get(DoubleKey.fcl_vnext_k_slope),
        kAccel = prefs.get(DoubleKey.fcl_vnext_k_accel),

        // betrouwbaarheid
        minConsistency = prefs.get(DoubleKey.fcl_vnext_min_consistency),
        consistencyExp = prefs.get(DoubleKey.fcl_vnext_consistency_exp),

        // IOB veiligheid
        iobStart = prefs.get(DoubleKey.fcl_vnext_iob_start),
        iobMax = prefs.get(DoubleKey.fcl_vnext_iob_max),
        iobMinFactor = prefs.get(DoubleKey.fcl_vnext_iob_min_factor),

        // gain
        gain = gain,

        // execution
        hybridPercentage = prefs.get(IntKey.hybrid_basal_perc),

        // persistent
        persistentDeltaTarget = prefs.get(DoubleKey.fcl_vnext_persistent_delta_target),
        persistentMaxSlope = prefs.get(DoubleKey.fcl_vnext_persistent_max_slope),
        persistentIobLimit = prefs.get(DoubleKey.fcl_vnext_persistent_iob_limit),
        persistentFraction = prefs.get(DoubleKey.fcl_vnext_persistent_fraction),

        maxSMB = maxSMB
    )

}
