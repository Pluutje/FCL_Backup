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

    // ★★★ PARAMETER DEFINITIES - VOLLEDIGE LIJST ★★★
    private val parameterDefinitions = listOf(
        // ★★★ BOLUS PERCENTAGES ★★★
        ParameterDefinition(
            key = IntKey.bolus_perc_early,
            name = "Early Rise Bolus %",
            category = "BOLUS",
            minValue = 10.0,
            maxValue = 200.0,
            defaultValue = 100.0,
            description = "Percentage van volledige bolus bij vroege stijging",
            impactLevel = "HIGH"
        ),
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
        ParameterDefinition(
            key = IntKey.bolus_perc_mid,
            name = "Mid Rise Bolus %",
            category = "BOLUS",
            minValue = 10.0,
            maxValue = 200.0,
            defaultValue = 60.0,
            description = "Percentage van volledige bolus bij middelmatige stijging",
            impactLevel = "MEDIUM"
        ),
        ParameterDefinition(
            key = IntKey.bolus_perc_late,
            name = "Late Rise Bolus %",
            category = "BOLUS",
            minValue = 10.0,
            maxValue = 200.0,
            defaultValue = 30.0,
            description = "Percentage van volledige bolus bij late stijging",
            impactLevel = "MEDIUM"
        ),

        // ★★★ FASE DETECTIE PARAMETERS ★★★
        ParameterDefinition(
            key = DoubleKey.phase_early_rise_slope,
            name = "Early Rise Slope",
            category = "PHASE_DETECTION",
            minValue = 0.3,
            maxValue = 2.5,
            defaultValue = 1.0,
            description = "Minimale stijging (mmol/L/uur) voor vroege fase detectie",
            impactLevel = "HIGH"
        ),
        ParameterDefinition(
            key = DoubleKey.phase_mid_rise_slope,
            name = "Mid Rise Slope",
            category = "PHASE_DETECTION",
            minValue = 0.3,
            maxValue = 2.5,
            defaultValue = 1.0,
            description = "Minimale stijging (mmol/L/uur) voor mid fase detectie",
            impactLevel = "MEDIUM"
        ),
        ParameterDefinition(
            key = DoubleKey.phase_late_rise_slope,
            name = "Late Rise Slope",
            category = "PHASE_DETECTION",
            minValue = 0.1,
            maxValue = 1.0,
            defaultValue = 0.4,
            description = "Minimale stijging (mmol/L/uur) voor late fase detectie",
            impactLevel = "MEDIUM"
        ),
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
            name = "Early Rise Acceleration",
            category = "PHASE_DETECTION",
            minValue = 0.05,
            maxValue = 0.5,
            defaultValue = 0.2,
            description = "Versnelling voor vroege fase detectie",
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
            name = "Hypo Risk Reduction %",
            category = "SAFETY",
            minValue = 20.0,
            maxValue = 50.0,
            defaultValue = 35.0,
            description = "Percentage bolus reductie bij hypo risico",
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
            append("=== FCL PARAMETER OVERZICHT ===\n")
            append("Totaal parameters: ${getAllParameters().size}\n")
            append("Hoog impact parameters: ${highImpact.size}\n\n")

            highImpact.forEach { (name, value) ->
                append("🔴 $name: ${round(value.current, 1)} (${value.definition.description})\n")
            }
        }
    }

    private fun round(value: Double, digits: Int): Double {
        val scale = Math.pow(10.0, digits.toDouble())
        return Math.round(value * scale) / scale
    }
}