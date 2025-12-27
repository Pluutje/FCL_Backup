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
    // delivery timing & caps
    val deliveryCycleMinutes: Int,     // bv 5
    val maxTempBasalRate: Double,      // bv 25.0 (later via prefs)

    // SMB safety
    val maxSMB: Double,

    // meal detect (bestaand)
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
    val commitIobPower: Double,

    // ── NEW: micro-correction hold (wachten bij dalen/vlak) ──
    val correctionHoldSlopeMax: Double,     // bv -0.20 mmol/L/h
    val correctionHoldAccelMax: Double,     // bv +0.05 mmol/L/h²
    val correctionHoldDeltaMax: Double,     // bv 1.5 mmol/L boven target

    val smallCorrectionMaxU: Double,        // bv 0.15
    val smallCorrectionCooldownMinutes: Int, // bv 20

    // ── NEW: peak/absorption suppression ──
    // Binnen deze window na een commit mag het algoritme "stilvallen" rond/na de piek.
    val absorptionWindowMinutes: Int,

    // Als slope onder deze waarde komt, benaderen we piek/plateau
    val peakSlopeThreshold: Double,       // mmol/L/h

    // Als accel onder deze waarde komt, zit je in afremmen (pre-peak/peak)
    val peakAccelThreshold: Double,       // mmol/L/h²

    // Hoe hard onderdrukken we dosing in absorption (0.0 = volledig stop)
    val absorptionDoseFactor: Double,     // 0..1

    // ── NEW: pre-peak bundling control ──
    val prePeakBundleFactor: Double,   // 0.0..1.0 (relatief tov maxSMB)

    // ── NEW: re-entry ──
    // Minimale tijd sinds laatste commit voordat we een nieuwe meal-commit toestaan
    val reentryMinMinutesSinceCommit: Int,

    // Extra cooldown speciaal voor re-entry commits (los van commitCooldownMinutes)
    val reentryCooldownMinutes: Int,

    // Re-entry vereist sterkere signalen (tweede gang / dessert)
    val reentrySlopeMin: Double,          // mmol/L/h
    val reentryAccelMin: Double,          // mmol/L/h²
    val reentryDeltaMin: Double,           // mmol/L

    // ── NEW: stagnation / nasleep na maaltijd ──
    val stagnationDeltaMin: Double,        // mmol/L boven target
    val stagnationSlopeMaxNeg: Double,     // mmol/L/h (lichte daling toegestaan)
    val stagnationSlopeMaxPos: Double,     // mmol/L/h (lichte stijging toegestaan)
    val stagnationAccelMaxAbs: Double,   //
    val stagnationEnergyBoost: Double, // energy per mmol boven target

    // ── Peak prediction ──
    val peakPredictionThreshold: Double,    // mmol/L (bv 12.0)
    val peakConfirmCycles: Int,             // aantal 5m-cycli
    val peakMinConsistency: Double,
    val peakMinSlope: Double,               // mmol/L/h
    val peakMinAccel: Double,               // mmol/L/h²
    val peakPredictionHorizonH: Double,      // uren
    val peakExitSlope: Double,    // bv 0.45
    val peakExitAccel: Double,    // bv -0.08

    val peakIobBoostWatching: Double,   // bv 1.15
    val peakIobBoostConfirmed: Double,  // bv 1.40

    val peakMomentumHalfLifeMin: Double,   // bv 25.0
    val peakMinMomentum: Double,           // bv 0.35 (mmol)
    val peakMomentumGain: Double,          // bv 2.8
    val peakRiseGain: Double,              // bv 0.65
    val peakUseMaxSlopeFrac: Double,       // bv 0.6
    val peakUseMaxAccelFrac: Double,       // bv 0.5
    val peakPredictionMaxMmol: Double       // bv 25.0
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
        deliveryCycleMinutes = 5,
        maxTempBasalRate = 15.0,

        maxSMB = maxSMB,

        // meal (jouw huidige defaults)
        mealSlopeMin = 0.8,
        mealSlopeSpan = 0.8,

        mealAccelMin = 0.15,
        mealAccelSpan = 0.6,

        mealDeltaMin = 0.8,
        mealDeltaSpan = 1.0,

        mealUncertainConfidence = 0.45,
        mealConfirmConfidence = 0.7,

        commitCooldownMinutes = 15,

        uncertainMinFraction = 0.45,
        uncertainMaxFraction = 0.70,
        confirmMinFraction = 0.70,
        confirmMaxFraction = 1.00,

        minCommitDose = 0.3,
        commitIobPower = prefs.get(DoubleKey.fcl_vnext_commit_iob_power),

        correctionHoldSlopeMax = -0.20,
        correctionHoldAccelMax = 0.05,
        correctionHoldDeltaMax = 1.5,

        smallCorrectionMaxU = 0.15,
        smallCorrectionCooldownMinutes = 15,

        // ── NEW: absorption/peak suppression ──
        absorptionWindowMinutes = 60,     // 60 min na commit: piek + begin afbouw
        peakSlopeThreshold = 0.3,         // onder 0.3 mmol/L/h = plateau/piek regio
        peakAccelThreshold = -0.05,       // accel < -0.05 = afremmen (pre-peak/peak)
        absorptionDoseFactor = 0.0,       // 0 = stop (drastisch). Later kun je 0.1 doen.

        prePeakBundleFactor = 0.55,    //  noch implementeren prefs.get(DoubleKey.fcl_vnext_prepeak_maxsmb_factor),

        // ── NEW: re-entry ──
        reentryMinMinutesSinceCommit = 25,  // tweede gang vaak pas later
        reentryCooldownMinutes = 20,        // niet stapelen met elke 5m
        reentrySlopeMin = 1.0,              // moet echt weer stijgen
        reentryAccelMin = 0.10,             // liefst weer versnellen
        reentryDeltaMin = 1.0,    // boven target

// ── NEW: stagnation / nasleep ──
        stagnationDeltaMin = prefs.get(DoubleKey.fcl_vnext_stagnation_delta_min),
        stagnationSlopeMaxNeg =  prefs.get(DoubleKey.fcl_vnext_stagnation_slope_max_neg),
        stagnationSlopeMaxPos = prefs.get(DoubleKey.fcl_vnext_stagnation_slope_max_pos),
        stagnationAccelMaxAbs = prefs.get(DoubleKey.fcl_vnext_stagnation_accel_max_abs),
        stagnationEnergyBoost = prefs.get(DoubleKey.fcl_vnext_stagnation_energy_boost),

        peakPredictionThreshold = 12.5,
        peakConfirmCycles = 2,
        peakMinConsistency = 0.55,
        peakMinSlope = 0.5,
        peakMinAccel = -0.1,
        peakPredictionHorizonH = 1.2,
        peakExitSlope = 0.45,
        peakExitAccel = -0.08,
        peakIobBoostWatching = 1.15,
        peakIobBoostConfirmed =1.40,

        peakMomentumHalfLifeMin = 25.0,
        peakMinMomentum = 0.35,
        peakMomentumGain = 2.8,
        peakRiseGain = 0.65,
        peakUseMaxSlopeFrac = 0.6,
        peakUseMaxAccelFrac = 0.5,
        peakPredictionMaxMmol = 25.0,
    )

}
