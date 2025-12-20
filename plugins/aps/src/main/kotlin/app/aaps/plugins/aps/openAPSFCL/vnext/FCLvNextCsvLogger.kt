package app.aaps.plugins.aps.openAPSFCL.vnext

import android.os.Environment
import org.joda.time.DateTime
import java.io.File
import java.util.Locale

object FCLvNextCsvLogger {

    private const val FILE_NAME = "FCLvNext_Log.csv"
    private const val MAX_DAYS = 5
    private const val MAX_LINES = MAX_DAYS * 288  // 5 min ticks

    private val header = listOf(
        "ts",
        "isNight",
        "bg_mmol",
        "target_mmol",
        "delta_target",
        "slope",
        "accel",
        "consistency",
        "iob",
        "iob_ratio",
        "effective_isf",
        "gain",
        "energy",
        "raw_dose",
        "iob_factor",
        "persistent_active",
        "persistent_dose",
        "final_dose",
        "delivered_total",
        "bolus",
        "basal_u_h",
        "should_deliver",
        "decision_reason"
    ).joinToString(",")

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
        slope: Double,
        accel: Double,
        consistency: Double,
        iob: Double,
        iobRatio: Double,
        effectiveISF: Double,
        gain: Double,
        energy: Double,
        rawDose: Double,
        iobFactor: Double,
        persistentActive: Boolean,
        persistentDose: Double,
        finalDose: Double,
        deliveredTotal: Double,
        bolus: Double,
        basalRate: Double,
        shouldDeliver: Boolean,
        decisionReason: String
    ) {
        try {
            val file = getFile()

            val line = listOf(
                ts.toString("yyyy-MM-dd HH:mm:ss"),
                isNight,
                f(bg),
                f(target),
                f(bg - target),
                f(slope),
                f(accel),
                f(consistency),
                f(iob),
                f(iobRatio),
                f(effectiveISF),
                f(gain),
                f(energy),
                f(rawDose),
                f(iobFactor),
                persistentActive,
                f(persistentDose),
                f(finalDose),
                f(deliveredTotal),
                f(bolus),
                f(basalRate),
                shouldDeliver,
                decisionReason.replace(",", ";")
            ).joinToString(",")

            if (!file.exists() || file.length() == 0L) {
                file.writeText(header + "\n" + line + "\n")
                return
            }

            val lines = file.readLines().toMutableList()

            // behoud header
            val body = lines.drop(1).toMutableList()
            body.add(0, line)

            val trimmed =
                if (body.size > MAX_LINES)
                    body.take(MAX_LINES)
                else body

            file.writeText(header + "\n")
            file.appendText(trimmed.joinToString("\n") + "\n")

        } catch (_: Exception) {
            // logging mag NOOIT FCL blokkeren
        }
    }

    private fun f(x: Double): String =
        String.format(Locale.US, "%.4f", x)
}
