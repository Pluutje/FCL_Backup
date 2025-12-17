package app.aaps.plugins.aps.openAPSFCL

import org.joda.time.DateTime

/**
 * Interne state van Advisor
 * Bevat GEEN business logic
 */
class FCLAdvisorState {

    var lastMeal: FCLMetrics.MealPerformanceMetrics? = null
        private set

    var lastUpdate: DateTime? = null
        private set

    fun updateFromMeal(meal: FCLMetrics.MealPerformanceMetrics) {
        lastMeal = meal
        lastUpdate = DateTime.now()
    }
}

/**
 * Wat Advisor uiteindelijk teruggeeft
 * (nog los van Metrics)
 */
data class FCLAdvisorSuggestion(
    val parameterName: String,
    val currentValue: Double,
    val suggestedValue: Double,
    val confidence: Double,
    val reason: String
)


