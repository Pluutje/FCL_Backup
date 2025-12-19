package app.aaps.plugins.aps.openAPSFCL.vnext

import org.joda.time.DateTime
import app.aaps.core.keys.Preferences
import app.aaps.core.keys.StringKey

class FCLvNextDayNightHelper(
    private val preferences: Preferences
) {

    fun isNightNow(): Boolean {
        val now = DateTime.now()
        val currentHour = now.hourOfDay
        val currentMinute = now.minuteOfHour
        val currentDayOfWeek = now.dayOfWeek

        val isWeekend = isWeekendDay(currentDayOfWeek)

        val ochtendStart = if (isWeekend) {
            preferences.get(StringKey.OchtendStartWeekend)
        } else {
            preferences.get(StringKey.OchtendStart)
        }

        val nachtStart = preferences.get(StringKey.NachtStart)

        val (ochtendUur, ochtendMin) = parseTime(ochtendStart)
        val (nachtUur, nachtMin) = parseTime(nachtStart)

        return isInTijdBereik(
            currentHour, currentMinute,
            nachtUur, nachtMin,
            ochtendUur, ochtendMin
        )
    }

    private fun isWeekendDay(dayOfWeek: Int): Boolean {
        val dayMapping = mapOf(
            1 to "ma", 2 to "di", 3 to "wo", 4 to "do",
            5 to "vr", 6 to "za", 7 to "zo"
        )
        val currentDayAbbr = dayMapping[dayOfWeek] ?: return false
        val weekendDagen = preferences.get(StringKey.WeekendDagen)

        return weekendDagen.split(",").any {
            it.trim().equals(currentDayAbbr, ignoreCase = true)
        }
    }

    private fun parseTime(timeStr: String): Pair<Int, Int> {
        return try {
            val parts = timeStr.split(":")
            val uur = parts[0].toInt()
            val minuut = if (parts.size > 1) parts[1].toInt() else 0
            Pair(uur, minuut)
        } catch (_: Exception) {
            Pair(6, 0)
        }
    }

    private fun isInTijdBereik(
        hh: Int, mm: Int,
        startUur: Int, startMinuut: Int,
        eindUur: Int, eindMinuut: Int
    ): Boolean {
        val start = startUur * 60 + startMinuut
        val end = eindUur * 60 + eindMinuut
        val now = hh * 60 + mm

        return if (end < start) {
            now >= start || now < end
        } else {
            now in start..end
        }
    }
}
