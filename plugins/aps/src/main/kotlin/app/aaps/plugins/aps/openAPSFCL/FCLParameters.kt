package app.aaps.plugins.aps.openAPSFCL

import app.aaps.core.keys.*
import app.aaps.core.keys.Preferences
import kotlin.math.round

class FCLParameters(private val preferences: Preferences) {

    data class ParameterDefinition(
        val key: Any,  // IntKey, DoubleKey, StringKey, etc.
        val name: String,
        val category: String,
        val minValue: Double,
        val maxValue: Double,
        val defaultValue: Double,
        val description: String,
        val impactLevel: String // "HIGH", "MEDIUM", "LOW"
    )

    data class ParameterValue(
        val current: Double,
        val definition: ParameterDefinition
    )

    data class ParameterAdvice(
        val parameter: ParameterDefinition,
        val currentValue: Double,
        val recommendedValue: Double,
        val reason: String,
        val confidence: Double,
        val expectedImpact: String,
        val changeDirection: String // "INCREASE", "DECREASE", "OPTIMAL"
    )

    // ★★★ PARAMETER DEFINITIES - NIEUWE 2-FASE STRUCTUUR ★★★
    private val parameterDefinitions = listOf(
        // ★★★ NIEUWE 2-FASE BOLUS PARAMETERS ★★★
        ParameterDefinition(
            key = IntKey.bolus_perc_rising,
            name = "Rising Phase Bolus %",
            category = "BOLUS",
            minValue = 10.0,
            maxValue = 200.0,
            defaultValue = 100.0,
            description = "Percentage van volledige bolus bij stijgende fase",
            impactLevel = "HIGH"
        ),
        ParameterDefinition(
            key = IntKey.bolus_perc_plateau,
            name = "Plateau Phase Bolus %",
            category = "BOLUS",
            minValue = 10.0,
            maxValue = 200.0,
            defaultValue = 60.0,
            description = "Percentage van volledige bolus bij plateau fase",
            impactLevel = "HIGH"
        ),

        // ★★★ HYBRIDE BASAAL PARAMETER ★★★
        ParameterDefinition(
            key = IntKey.hybrid_basal_perc,
            name = "Hybrid Basal Percentage",
            category = "BOLUS",
            minValue = 0.0,
            maxValue = 100.0,
            defaultValue = 0.0,
            description = "Percentage van bolus dat als tijdelijke basaal wordt afgegeven",
            impactLevel = "MEDIUM"
        ),

        // ★★★ NIEUWE 2-FASE DETECTIE PARAMETERS ★★★
        ParameterDefinition(
            key = DoubleKey.phase_rising_slope,
            name = "Rising Phase Slope",
            category = "PHASE_DETECTION",
            minValue = 0.3,
            maxValue = 2.5,
            defaultValue = 1.0,
            description = "Minimale stijging (mmol/L/uur) voor stijgende fase detectie",
            impactLevel = "HIGH"
        ),
        ParameterDefinition(
            key = DoubleKey.phase_plateau_slope,
            name = "Plateau Phase Slope",
            category = "PHASE_DETECTION",
            minValue = 0.1,
            maxValue = 1.0,
            defaultValue = 0.4,
            description = "Minimale stijging (mmol/L/uur) voor plateau fase detectie",
            impactLevel = "HIGH"
        ),

        // ★★★ DAG/NACHT BOLUS PARAMETERS ★★★
        ParameterDefinition(
            key = IntKey.bolus_perc_day,
            name = "Daytime Bolus %",
            category = "BOLUS",
            minValue = 10.0,
            maxValue = 200.0,
            defaultValue = 100.0,
            description = "Algemene bolus agressiviteit overdag",
            impactLevel = "HIGH"
        ),
        ParameterDefinition(
            key = IntKey.bolus_perc_night,
            name = "Nighttime Bolus %",
            category = "BOLUS",
            minValue = 5.0,
            maxValue = 100.0,
            defaultValue = 20.0,
            description = "Algemene bolus agressiviteit 's nachts",
            impactLevel = "HIGH"
        ),

        // ★★★ DYNAMISCHE AGRESSIVITEIT PARAMETERS ★★★
        ParameterDefinition(
            key = DoubleKey.dynamic_night_aggressiveness_threshold,
            name = "Nacht agressiviteit drempel",
            category = "BOLUS",
            minValue = 0.5,
            maxValue = 5.0,
            defaultValue = 2.0,
            description = "Minimale stijging (mmol/L/uur) om nachtagressiviteit te verhogen bij maaltijden",
            impactLevel = "MEDIUM"
        ),
        ParameterDefinition(
            key = IntKey.enhanced_early_boost_perc,
            name = "Vroege Bolus Boost %",
            category = "BOLUS",
            minValue = 10.0,
            maxValue = 100.0,
            defaultValue = 40.0,
            description = "Maximale extra boost bij sterke stijgingen (10% - 100%)",
            impactLevel = "MEDIUM"
        ),
        ParameterDefinition(
            key = IntKey.min_minutes_between_bolus,
            name = "Minimale tijd tussen bolussen",
            category = "BOLUS",
            minValue = 5.0,
            maxValue = 15.0,
            defaultValue = 8.0,
            description = "Minimale minuten tussen bolussen bij sterke stijgingen",
            impactLevel = "MEDIUM"
        ),

        // ★★★ FASE DETECTIE VERFIJNING PARAMETERS ★★★
        ParameterDefinition(
            key = DoubleKey.phase_peak_slope,
            name = "Peak Slope",
            category = "PHASE_DETECTION",
            minValue = -0.5,
            maxValue = 0.8,
            defaultValue = 0.1,
            description = "Stijging (mmol/L/uur) voor piekdetectie",
            impactLevel = "MEDIUM"
        ),
        ParameterDefinition(
            key = DoubleKey.phase_early_rise_accel,
            name = "Rising Phase Acceleration",
            category = "PHASE_DETECTION",
            minValue = 0.05,
            maxValue = 0.5,
            defaultValue = 0.2,
            description = "Versnelling voor stijgende fase detectie",
            impactLevel = "LOW"
        ),
        ParameterDefinition(
            key = DoubleKey.phase_min_consistency,
            name = "Minimum Consistency",
            category = "PHASE_DETECTION",
            minValue = 0.3,
            maxValue = 0.9,
            defaultValue = 0.6,
            description = "Minimale consistentie voor fase herkenning",
            impactLevel = "MEDIUM"
        ),

        // ★★★ NIEUWE GEAvANCEERDE DETECTIE PARAMETERS ★★★
        ParameterDefinition(
            key = DoubleKey.data_smoothing_alpha,
            name = "Data Smoothing Factor",
            category = "PHASE_DETECTION",
            minValue = 0.1,
            maxValue = 0.8,
            defaultValue = 0.4,
            description = "Mate van gladmaken van ruis in BG data (0.1=gevoelig, 0.8=stabiel)",
            impactLevel = "MEDIUM"
        ),
        ParameterDefinition(
            key = DoubleKey.direction_consistency_threshold,
            name = "Direction Consistency Threshold",
            category = "PHASE_DETECTION",
            minValue = 0.3,
            maxValue = 0.9,
            defaultValue = 0.7,
            description = "Benodigde consistentie in trendrichting voor betrouwbare detectie",
            impactLevel = "MEDIUM"
        ),

        // ★★★ MAALTIJD PARAMETERS ★★★
        ParameterDefinition(
            key = IntKey.carb_percentage,
            name = "Carb Detection %",
            category = "MEAL",
            minValue = 10.0,
            maxValue = 200.0,
            defaultValue = 100.0,
            description = "Percentage van gedetecteerde koolhydraten dat wordt gebruikt",
            impactLevel = "HIGH"
        ),
        ParameterDefinition(
            key = IntKey.tau_absorption_minutes,
            name = "Carb Absorption Time",
            category = "MEAL",
            minValue = 20.0,
            maxValue = 60.0,
            defaultValue = 40.0,
            description = "Absorptietijd koolhydraten in minuten",
            impactLevel = "MEDIUM"
        ),
        ParameterDefinition(
            key = DoubleKey.meal_detection_sensitivity,
            name = "Meal Detection Sensitivity",
            category = "MEAL",
            minValue = 0.1,
            maxValue = 0.5,
            defaultValue = 0.35,
            description = "Gevoeligheid voor maaltijd detectie (mmol/L/5min)",
            impactLevel = "HIGH"
        ),

        // ★★★ VEILIGHEID PARAMETERS ★★★
        ParameterDefinition(
            key = IntKey.peak_damping_percentage,
            name = "Peak Damping %",
            category = "SAFETY",
            minValue = 10.0,
            maxValue = 100.0,
            defaultValue = 50.0,
            description = "Percentage bolus reductie bij piekdetectie",
            impactLevel = "MEDIUM"
        ),
        ParameterDefinition(
            key = IntKey.hypo_risk_percentage,
            name = "Hypo Risk Bolus %",
            category = "SAFETY",
            minValue = 10.0,
            maxValue = 50.0,
            defaultValue = 25.0,
            description = "Bolus Percentage bij hypo risico",
            impactLevel = "HIGH"
        ),
        ParameterDefinition(
            key = IntKey.IOB_corr_perc,
            name = "IOB Safety %",
            category = "SAFETY",
            minValue = 50.0,
            maxValue = 150.0,
            defaultValue = 100.0,
            description = "Percentage bolus reductie bij hoge IOB",
            impactLevel = "HIGH"
        )
    )

    // ★★★ PUBLIC FUNCTIES ★★★

    fun getAllParameters(): Map<String, ParameterValue> {
        return parameterDefinitions.associate { definition ->
            definition.name to ParameterValue(
                current = getParameterValue(definition),
                definition = definition
            )
        }
    }

    fun getParametersByCategory(category: String): Map<String, ParameterValue> {
        return getAllParameters().filter { it.value.definition.category == category }
    }

    fun getHighImpactParameters(): Map<String, ParameterValue> {
        return getAllParameters().filter { it.value.definition.impactLevel == "HIGH" }
    }

    fun getParameterValue(parameterName: String): Double? {
        return getAllParameters()[parameterName]?.current
    }

    fun getParameterDefinition(parameterName: String): ParameterDefinition? {
        return parameterDefinitions.find { it.name == parameterName }
    }

    // ★★★ NIEUWE FUNCTIE: Get parameter by technical name ★★★
    fun getParameterValueByTechnicalName(technicalName: String): Double? {
        return when (technicalName) {
            "bolus_perc_rising" -> getParameterValue("Rising Phase Bolus %")
            "bolus_perc_plateau" -> getParameterValue("Plateau Phase Bolus %")
            "hybrid_basal_perc" -> getParameterValue("Hybrid Basal Percentage")
            "phase_rising_slope" -> getParameterValue("Rising Phase Slope")
            "phase_plateau_slope" -> getParameterValue("Plateau Phase Slope")
            "bolus_perc_day" -> getParameterValue("Daytime Bolus %")
            "bolus_perc_night" -> getParameterValue("Nighttime Bolus %")
            "dynamic_night_aggressiveness_threshold" -> getParameterValue("Nacht agressiviteit drempel")
            "meal_detection_sensitivity" -> getParameterValue("Meal Detection Sensitivity")
            "carb_percentage" -> getParameterValue("Carb Detection %")
            "peak_damping_percentage" -> getParameterValue("Peak Damping %")
            "hypo_risk_percentage" -> getParameterValue("Hypo Risk Bolus %")
            "IOB_corr_perc" -> getParameterValue("IOB Safety %")
            "phase_peak_slope" -> getParameterValue("Peak Slope")
            "phase_early_rise_accel" -> getParameterValue("Rising Phase Acceleration")
            "phase_min_consistency" -> getParameterValue("Minimum Consistency")
            "data_smoothing_alpha" -> getParameterValue("Data Smoothing Factor")
            "direction_consistency_threshold" -> getParameterValue("Direction Consistency Threshold")
            else -> null
        }
    }

    // ★★★ INTERNE HULPFUNCTIES ★★★

    private fun getParameterValue(definition: ParameterDefinition): Double {
        return when (definition.key) {
            is IntKey -> preferences.get(definition.key as IntKey).toDouble()
            is DoubleKey -> preferences.get(definition.key as DoubleKey)
            is BooleanKey -> if (preferences.get(definition.key as BooleanKey)) 1.0 else 0.0
            else -> definition.defaultValue
        }
    }

    fun getParametersForAnalysis(): Map<String, Double> {
        return getAllParameters().mapValues { it.value.current }
    }

    fun getParameterSummary(): String {
        val highImpact = getHighImpactParameters()

        return buildString {
            append("🎯 FCL PARAMETER OVERZICHT\n")
            append("════════════════════════════\n")
            append("• Totaal parameters: ${getAllParameters().size}\n")
            append("• Hoog impact parameters: ${highImpact.size}\n\n")

            append("🚀 2-FASE BOLUS PARAMETERS:\n")
            getParametersByCategory("BOLUS").filter {
                it.key.contains("Rising") || it.key.contains("Plateau") || it.key.contains("Hybrid")
            }.forEach { (name, value) ->
                val formattedValue = formatParameterValue(value.current, value.definition)
                append("  📈 $name: $formattedValue\n")
                append("     ${value.definition.description}\n\n")
            }

            append("🌙 DAG/NACHT PARAMETERS:\n")
            getParametersByCategory("BOLUS").filter {
                it.key.contains("Day") || it.key.contains("Night")
            }.forEach { (name, value) ->
                val formattedValue = formatParameterValue(value.current, value.definition)
                append("  ⏰ $name: $formattedValue\n")
                append("     ${value.definition.description}\n\n")
            }

            append("🎯 FASE DETECTIE PARAMETERS:\n")
            getParametersByCategory("PHASE_DETECTION").forEach { (name, value) ->
                val formattedValue = formatParameterValue(value.current, value.definition)
                append("  🔍 $name: $formattedValue\n")
                append("     ${value.definition.description}\n\n")
            }

            append("🍽️ MAALTIJD PARAMETERS:\n")
            getParametersByCategory("MEAL").forEach { (name, value) ->
                val formattedValue = formatParameterValue(value.current, value.definition)
                append("  🍴 $name: $formattedValue\n")
                append("     ${value.definition.description}\n\n")
            }

            append("🛡️ VEILIGHEID PARAMETERS:\n")
            getParametersByCategory("SAFETY").forEach { (name, value) ->
                val formattedValue = formatParameterValue(value.current, value.definition)
                append("  ⚠️ $name: $formattedValue\n")
                append("     ${value.definition.description}\n\n")
            }

            append("💡 TIPS:\n")
            append("• Begin met standaard waarden voor 2-fasen systeem\n")
            append("• Pas eerst basis agressiviteit aan (Dag/Nacht %)\n")
            append("• Gebruik hybride basaal voor stabielere BG na maaltijden\n")
            append("• Fase detectie parameters staan meestal goed afgesteld\n")
        }
    }

    private fun formatParameterValue(value: Double, definition: ParameterDefinition): String {
        return when (definition.key) {
            is IntKey -> {
                when {
                    definition.name.contains("%") -> "${value.toInt()}%"
                    definition.name.contains("Time") -> "${value.toInt()} min"
                    else -> "${value.toInt()}"
                }
            }
            is DoubleKey -> {
                when {
                    definition.name.contains("Slope", ignoreCase = true) -> "%.1f mmol/L/uur".format(value)
                    definition.name.contains("Sensitivity", ignoreCase = true) -> "%.2f mmol/L/5min".format(value)
                    definition.name.contains("Acceleration", ignoreCase = true) -> "%.2f".format(value)
                    definition.name.contains("Consistency", ignoreCase = true) -> "%.1f".format(value)
                    definition.name.contains("Smoothing", ignoreCase = true) -> "%.1f".format(value)
                    else -> "%.2f".format(value)
                }
            }
            else -> "%.2f".format(value)
        }
    }

    private fun round(value: Double, digits: Int): Double {
        val scale = Math.pow(10.0, digits.toDouble())
        return Math.round(value * scale) / scale
    }
}