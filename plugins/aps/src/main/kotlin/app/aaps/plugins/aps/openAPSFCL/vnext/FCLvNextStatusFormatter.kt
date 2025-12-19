package app.aaps.plugins.aps.openAPSFCL.vnext

import org.joda.time.DateTime

class FCLvNextStatusFormatter {

    fun buildStatus(
        isNight: Boolean,
        advice: FCLvNextAdvice?,
        bolusAmount: Double,
        basalRate: Double,
        shouldDeliver: Boolean,
        activityLog: String?,
        resistanceLog: String?,
        metricsText: String?
    ): String {

        val coreStatus = """
STATUS: (${if (isNight) "'S NACHTS" else "OVERDAG"})
─────────────────────
• Laatste update: ${DateTime.now().toString("HH:mm:ss")}
• Advies actief: ${if (shouldDeliver) "JA" else "NEE"}
• Bolus: ${"%.2f".format(bolusAmount)} U
• Basaal: ${"%.2f".format(basalRate)} U/h
""".trimIndent()

        val activityStatus = """
🏃 ACTIVITEIT
─────────────────────
${activityLog ?: "Geen activiteitdata"}
""".trimIndent()

        val resistanceStatus = """
🧬 INSULINERESISTENTIE
─────────────────────
${resistanceLog ?: "Geen resistentie-log"}
""".trimIndent()

        val fclCore = """
🧠 FCL vNext
─────────────────────
${advice?.statusText ?: "Geen FCL advies"}
""".trimIndent()

        val metricsStatus = metricsText ?: """
📊 GLUCOSE STATISTIEKEN
─────────────────────
Nog geen data
""".trimIndent()

        return """
════════════════════════
🧠 FCL vNext v15.5.1
════════════════════════

$coreStatus

$fclCore

$activityStatus

$resistanceStatus

$metricsStatus
""".trimIndent()
    }
}


