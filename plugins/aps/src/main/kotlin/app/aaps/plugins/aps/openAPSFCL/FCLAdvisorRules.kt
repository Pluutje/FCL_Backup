package app.aaps.plugins.aps.openAPSFCL

import org.joda.time.DateTime

/**
 * Alle beslisregels komen HIER
 * In A3.1 doet dit bewust nog niets
 */
class FCLAdvisorRules {

    fun evaluate(
        state: FCLAdvisorState,
        now: DateTime
    ): List<FCLAdvisorSuggestion> {

        // A3.1: GEEN logica
        return emptyList()
    }
}


