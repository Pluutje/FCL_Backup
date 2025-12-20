package app.aaps.plugins.aps.openAPSFCL.vnext

import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.Preferences
import kotlin.Double

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
    val maxSMB: Double,

    // meal
    val mealSlopeMin: Double,
    val mealSlopeSpan: Double,

    val mealAccelMin: Double,
    val mealAccelSpan: Double,

    val mealDeltaMin: Double,
    val mealDeltaSpan: Double,

    val mealUncertainConfidence: Double,
    val mealConfirmConfidence: Double,

    val commitCooldownMinutes: Int,

    val uncertainMinFraction: Double,
    val uncertainMaxFraction: Double,
    val confirmMinFraction: Double,
    val confirmMaxFraction: Double,

    val minCommitDose: Double,
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

        maxSMB = maxSMB,

        // meal
        mealSlopeMin = 0.8, // mmol/L/h
        mealSlopeSpan = 0.8, //

        mealAccelMin = 0.15, // mmol/L/h²
        mealAccelSpan = 0.6,

        mealDeltaMin = 0.8, // mmol/L
        mealDeltaSpan = 1.0,

        mealUncertainConfidence = 0.45,
        mealConfirmConfidence = 0.7,

        commitCooldownMinutes = 15,

        uncertainMinFraction = 0.45,
        uncertainMaxFraction = 0.70,
        confirmMinFraction = 0.70,
        confirmMaxFraction = 1.00,
        minCommitDose = 0.3,
    )
}
