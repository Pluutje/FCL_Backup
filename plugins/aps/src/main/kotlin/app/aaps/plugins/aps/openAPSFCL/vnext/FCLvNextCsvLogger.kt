package app.aaps.plugins.aps.openAPSFCL.vnext

import android.os.Environment
import org.joda.time.DateTime
import java.io.File
import java.util.Locale

object FCLvNextCsvLogger {

    private const val FILE_NAME = "FCLvNext_Log.csv"
    private const val MAX_DAYS = 5
    private const val MAX_LINES = MAX_DAYS * 288  // 5 min ticks

    private const val SEP = ";"

    private val header = listOf(
        // ── Context ──
        "ts",
        "isNight",
        "bg_mmol",
        "target_mmol",
        "delta_target",
        "iob",
        "iob_ratio",

        // ── Trends ──
        "slope",
        "accel",
        "consistency",

        // ── Model ──
        "effective_isf",
        "gain",
        "energy_base",
        "energy_total",
        "stagnation_active",
        "stagnation_boost",
        "stagnation_accel",
        "stagnation_accel_limit",
        "raw_dose",
        "iob_factor",
        "normal_dose",

        // ── Early ──
        "early_stage",
        "early_confidence",
        "early_target_u",

        // ── Decision / phase ──
        "meal_state",
        "commit_fraction",
        "minutes_since_commit",

        // ── Peak prediction ──
        "peak_state",
        "predicted_peak",
        "peak_iob_boost",
        "effective_iob_ratio",
        "peak_band",
        "peak_max_slope",
        "peak_momentum",
        "peak_rise_since_start",
        "peak_episode_active",
        "suppress_for_peak",
        "absorption_active",
        "reentry_signal",
        "decision_reason",

        // ── Execution ──
        "final_dose",
        "commanded_dose",
        "delivered_total",
        "bolus",
        "basal_u_h",
        "should_deliver"
    ).joinToString(SEP)

    private fun getFile(): File {
        val dir = File(
            Environment.getExternalStorageDirectory(),
            "Documents/AAPS/ANALYSE"
        )
        if (!dir.exists()) dir.mkdirs()
        return File(dir, FILE_NAME)
    }

    fun log(
        ts: DateTime = DateTime.now(),
        isNight: Boolean,
        bg: Double,
        target: Double,

        // trends
        slope: Double,
        accel: Double,
        consistency: Double,

        // IOB
        iob: Double,
        iobRatio: Double,

        // model
        effectiveISF: Double,
        gain: Double,
        energyBase: Double,
        energyTotal: Double,
        stagnationActive: Boolean,
        stagnationBoost: Double,
        stagnationAccel: Double,
        stagnationAccelLimit: Double,

        rawDose: Double,
        iobFactor: Double,

        // dosing
        finalDose: Double,
        deliveredTotal: Double,
        bolus: Double,
        basalRate: Double,
        shouldDeliver: Boolean,

        // peak
        peakState: String,
        predictedPeak: Double,
        peakIobBoost: Double,
        effectiveIobRatio: Double,
        peakBand: Int,
        peakMaxSlope: Double,
        peakMomentum: Double,
        peakRiseSinceStart: Double,
        peakEpisodeActive: Boolean,

        // decision
        decisionReason: String,

        // advisor / phase
        minutesSinceCommit: Int,
        suppressForPeak: Boolean,
        absorptionActive: Boolean,
        reentrySignal: Boolean,
        mealState: String,
        commitFraction: Double,

        normalDose: Double,
        commandedDose: Double,

        // early
        earlyStage: Int,
        earlyConfidence: Double,
        earlyTargetU: Double
    ) {
        try {
            val file = getFile()

            val line = listOf(
                ts.toString("yyyy-MM-dd HH:mm:ss"),
                isNight,

                // BG (1 dec)
                bg1(bg),
                bg1(target),

                // delta (2 dec)
                d2(bg - target),

                // IOB/insulin (2 dec)
                u2(iob),
                u2(iobRatio),

                // trends
                t2(slope),
                a2(accel),
                t2(consistency),

                // model
                bg2(effectiveISF),
                u2(gain),
                e2(energyBase),
                e2(energyTotal),
                stagnationActive,
                e2(stagnationBoost),
                a2(stagnationAccel),
                a2(stagnationAccelLimit),
                u2(rawDose),
                u2(iobFactor),
                u2(normalDose),

                // early
                earlyStage,
                t2(earlyConfidence),
                u2(earlyTargetU),

                // phase
                mealState,
                t2(commitFraction),
                minutesSinceCommit,

                // peak
                peakState,
                bg1(predictedPeak),
                u2(peakIobBoost),
                u2(effectiveIobRatio),
                peakBand,
                t2(peakMaxSlope),
                t2(peakMomentum),
                bg1(peakRiseSinceStart),
                peakEpisodeActive,
                suppressForPeak,
                absorptionActive,
                reentrySignal,
                decisionReason.replace(SEP, ","),

                // execution
                u2(finalDose),
                u2(commandedDose),
                u2(deliveredTotal),
                u2(bolus),
                u2(basalRate),
                shouldDeliver
            ).joinToString(SEP)

            if (!file.exists() || file.length() == 0L) {
                file.writeText(header + "\n" + line + "\n")
                return
            }

            val lines = file.readLines().toMutableList()

            val body = lines.drop(1).toMutableList()
            body.add(0, line)

            val trimmed =
                if (body.size > MAX_LINES) body.take(MAX_LINES) else body

            file.writeText(header + "\n")
            file.appendText(trimmed.joinToString("\n") + "\n")

        } catch (_: Exception) {
            // logging mag NOOIT FCL blokkeren
        }
    }

    // formatting helpers
    private fun bg1(x: Double) = String.format(Locale.US, "%.1f", x) // BG-ish
    private fun bg2(x: Double) = String.format(Locale.US, "%.2f", x) // BG-ish but 2 (ISF)
    private fun d2(x: Double)  = String.format(Locale.US, "%.2f", x) // delta
    private fun u2(x: Double)  = String.format(Locale.US, "%.2f", x) // insulin / IOB
    private fun a2(x: Double)  = String.format(Locale.US, "%.2f", x) // accel
    private fun e2(x: Double)  = String.format(Locale.US, "%.2f", x) // energy
    private fun t2(x: Double)  = String.format(Locale.US, "%.2f", x) // trends/misc
}
