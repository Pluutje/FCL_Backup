package app.aaps.plugins.aps.openAPSFCL

class FCLStatusFormatter {

    fun buildFCLStatus(fcl: FCL): String {

        val prefs = fcl.uiPreferences()
        val isNight = fcl.uiIsNight()
        val lastBolusLines = fcl.uiLastBoluses()
            .take(3)
            .joinToString("\n") { (time, dose) ->
                "${time.toString("HH:mm")} -> ${"%.2f".format(dose)}u"
            }

        // ─────────────────────────────────────────────
        // HYBRIDE BASAAL STATUS (KORT)
        // ─────────────────────────────────────────────
        val basalActiveLine = if (fcl.uiIsBasalActive()) {
            val mins = fcl.uiBasalRemainingMinutes()
            val rate = fcl.uiCurrentBasalRate()
            "• Basal actief: JA (${mins} min, ${"%.2f".format(rate)} U/h)"
        } else {
            "• Basal actief: NEE"
        }

        // ─────────────────────────────────────────────
        // KERNSTATUS
        // ─────────────────────────────────────────────
        val coreStatus = """
STATUS: (${if (isNight) "'S NACHTS" else "OVERDAG"})
─────────────────────
• Laatste update: ${fcl.uiLastAnalysisTime()?.toString("HH:mm:ss") ?: "—"}
• Advies: ${fcl.uiLastBolusAdvice().take(120)}
• Bolus berekend: ${"%.2f".format(fcl.uiLastCalculatedBolus())} U
• Afgegeven: ${if (fcl.uiLastShouldDeliver()) "JA" else "NEE"}
${basalActiveLine}

${if (lastBolusLines.isNotBlank()) {
    "Laatste bolussen:\n$lastBolusLines"
} else {
    "Laatste bolussen:\n—"
}}
""".trimIndent()

        // ─────────────────────────────────────────────
        // HYBRIDE BASAAL
        // ─────────────────────────────────────────────
        val hybridStatus = fcl.getHybridStatusString()

        // ─────────────────────────────────────────────
        // ACTIVITEIT
        // ─────────────────────────────────────────────
        val activity = fcl.uiActivity()
        val activityStatus = """
🏃 ACTIVITEIT
─────────────────────
• Actief: ${if (activity?.getCurrentActivityStatus()?.contains("Retentie: 0") == false) "JA" else "NEE"}
• ${activity?.getCurrentActivityStatus() ?: "Geen data"}
""".trimIndent()

        // ─────────────────────────────────────────────
        // PERSISTENTE HOGE BG
        // ─────────────────────────────────────────────
        val persistentStatus =
            fcl.uiPersistent()?.getPersistentStatus()
                ?: """
🔥 PERSISTENTE HOGE BG
─────────────────────
Niet beschikbaar
""".trimIndent()

        // ─────────────────────────────────────────────
        // RESISTENTIE
        // ─────────────────────────────────────────────
        val resistance = fcl.uiResistance()
        val resistanceStatus = """
🧬 INSULINERESISTENTIE
─────────────────────
• Percentage: ${((resistance?.getCurrentResistanceFactor() ?: 1.0) * 100).toInt()}%
• Status:
${resistance?.getCurrentResistanceLog()?.take(600) ?: "Geen log"}
""".trimIndent()

        // ─────────────────────────────────────────────
        // METRICS
        // ─────────────────────────────────────────────
        val metricsStatus =
            fcl.uiMetrics()?.getUserStatsString()
                ?: """
📊 GLUCOSE STATISTIEKEN
─────────────────────
Nog geen data
""".trimIndent()

        // ─────────────────────────────────────────────
        // SAMENVOEGEN
        // ─────────────────────────────────────────────
        return """
════════════════════════
🧠 FCL 15.1.0
════════════════════════

$coreStatus

$hybridStatus

$activityStatus

$persistentStatus

$resistanceStatus

$metricsStatus
""".trimIndent()
    }
}
