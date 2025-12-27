package app.aaps.plugins.aps.openAPSFCL.vnext

import org.joda.time.DateTime

class FCLvNextStatusFormatter {

    private fun formatDeliveryHistory(
        history: List<Pair<DateTime, Double>>?
    ): String {
        if (history.isNullOrEmpty()) return "Geen recente afleveringen"

        return history.joinToString("\n") { (ts, dose) ->
            "${ts.toString("HH:mm")} ${"%.2f".format(dose)}U"
        }
    }



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

🧪 LAATSTE DOSISSEN
─────────────────────
${formatDeliveryHistory(advice?.let { deliveryHistory.toList() })}
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
 🧠 FCL vNext v17.4.0
════════════════════════

$coreStatus

$fclCore

$activityStatus

$resistanceStatus

$metricsStatus
""".trimIndent()
    }
}


