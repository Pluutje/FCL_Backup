package app.aaps.plugins.aps.openAPSFCL

import android.content.Context
import android.content.SharedPreferences
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.Preferences
import org.joda.time.DateTime
import kotlin.math.*

class FCLResistance(
    private val preferences: Preferences,
    private val persistenceLayer: PersistenceLayer,
    private val context: Context  // ← NIEUW: context toegevoegd
) {

    data class ResistentieResult(val resistentie: Double, val log: String)



    // ★★★ RESISTENTIE STATE TRACKING ★★★
    private var currentResistentieFactor: Double = 1.0
    private var currentResistentieLog: String = ""
    private var lastResistentieCalculation: DateTime? = null
    private val RESISTENTIE_CALCULATION_INTERVAL = 15 * 60 * 1000L // 15 minuten

    // ★★★ RESISTENTIE OPSLAG ★★★
    private val prefs: SharedPreferences = context.getSharedPreferences("FCL_Learning_Data", Context.MODE_PRIVATE)

    // ★★★ INIT: LAAD OPGESLAGEN RESISTENTIE BIJ OPSTARTEN ★★★
    init {
        currentResistentieFactor = loadCurrentResistance()
    }


    // ★★★ PUBLIC FUNCTIES VOOR STATE ACCESS ★★★
    fun getCurrentResistanceFactor(): Double = currentResistentieFactor
    fun getCurrentResistanceLog(): String = currentResistentieLog

    fun updateResistentieIndienNodig(isNachtTime: Boolean) {
        val now = DateTime.now()
        val shouldUpdate = lastResistentieCalculation?.let {
            now.millis - it.millis > RESISTENTIE_CALCULATION_INTERVAL
        } ?: true

        if (shouldUpdate && preferences.get(BooleanKey.Resistentie)) {
            val result = calculateResistentie(isNachtTime)
            currentResistentieFactor = result.resistentie
            currentResistentieLog = result.log
            lastResistentieCalculation = now

            // Opslaan voor externe toegang
            saveCurrentResistance(currentResistentieFactor)
        }
    }

    // ★★★ RESISTENTIE OPSLAG FUNCTIES ★★★
    fun saveCurrentResistance(resistance: Double) {
        try {
            prefs.edit().putFloat("current_resistance", resistance.toFloat()).apply()
        } catch (e: Exception) {
            // Silent fail
        }
    }

    fun loadCurrentResistance(): Double {
        return try {
            prefs.getFloat("current_resistance", 1.0f).toDouble()
        } catch (e: Exception) {
            1.0
        }
    }

    // ... (hier komen de bestaande calculateResistentie, calculateCorrectionFactor, getBgHistoryWithStdDev functies) ...
    // ★★★ BESTAANDE FUNCTIES BLIJVEN STAAN ★★★
    fun calculateResistentie(isNachtTime: Boolean): ResistentieResult {
        var log_resistentie = ""

        if (!preferences.get(BooleanKey.Resistentie)) {
            log_resistentie = " → correctie: ❌ O̶F̶F̶ " + "\n"
            return ResistentieResult(1.0, log_resistentie)
        }

        log_resistentie = " → correctie: ✅ ON " + "\n"

        val MinresistentiePerc = preferences.get(IntKey.Min_resistentiePerc)
        val MaxresistentiePerc = preferences.get(IntKey.Max_resistentiePerc)
        val DagresistentiePerc = preferences.get(IntKey.Dag_resistentiePerc)
        val NachtresistentiePerc = preferences.get(IntKey.Nacht_resistentiePerc)
        val Dagenresistentie = preferences.get(IntKey.Dagen_resistentie)
        val Urenresistentie = preferences.get(DoubleKey.Uren_resistentie)
        val Dagresistentie_target = preferences.get(DoubleKey.Dag_resistentie_target)
        val Nachtresistentie_target = preferences.get(DoubleKey.Nacht_resistentie_target)
        val MinutenDelayresistentie = preferences.get(IntKey.MinDelay_resistentie)

        var resistentie_percentage = if (isNachtTime) NachtresistentiePerc else DagresistentiePerc
        val target = if (isNachtTime) Nachtresistentie_target else Dagresistentie_target

        val now = DateTime.now()
        val uurVanDag = now.hourOfDay
        val minuten = now.minuteOfHour
        val minuutTxt = String.format("%02d", minuten)

        log_resistentie += " ● Tijd: $uurVanDag:$minuutTxt → ${if (isNachtTime) "s'Nachts" else "Overdag"}\n"
        log_resistentie += "      → perc.: $resistentie_percentage%\n"
        log_resistentie += "      → Target: ${round(target, 1)} mmol/l\n"

        // Periode berekening
        val totaleMinutenNu = uurVanDag * 60 + minuten
        val totaleMinutenStart = totaleMinutenNu + MinutenDelayresistentie
        val urenTotUur = (totaleMinutenStart / 60) % 24
        val urenTotMinuut = totaleMinutenStart % 60
        val totaleMinutenEind = totaleMinutenStart + (Urenresistentie * 60).toInt()
        val urenEindUur = (totaleMinutenEind / 60) % 24
        val urenEindMinuut = totaleMinutenEind % 60

        log_resistentie += " ● Referentie periode:\n"
        log_resistentie += "      → afgelopen $Dagenresistentie dagen\n"
        log_resistentie += "      → van ${String.format("%02d", urenTotUur)}:${String.format("%02d", urenTotMinuut)} tot ${String.format("%02d", urenEindUur)}:${String.format("%02d", urenEindMinuut)}\n"

        val macht = Math.pow(resistentie_percentage.toDouble(), 1.4) / 2800
        val intervals = mutableListOf<Pair<Double, Double>>()

        for (i in 1..Dagenresistentie) {
            val base = (24.0 * i) - 1
            intervals.add(Pair(base, base - Urenresistentie))
        }

        val correctionFactors = mutableListOf<Double>()
        val formatter = org.joda.time.format.DateTimeFormat.forPattern("dd-MM")
        val today = DateTime.now()

        for ((index, interval) in intervals.withIndex()) {
            val startTime = interval.first.toLong()
            val endTime = interval.second.toLong()

            val (bgGem, bgStdDev) = getBgHistoryWithStdDev(startTime, endTime, Urenresistentie.toLong())

            if (bgGem > 0) {
                val rel_std = (bgStdDev / bgGem * 100).toInt()
                val cf = calculateCorrectionFactor(bgGem, target, macht, rel_std)
                val dateString = today.minusDays(index + 1).toString(formatter)

                log_resistentie += " → $dateString : correctie percentage = ${(cf * 100).toInt()}%\n"
                log_resistentie += "   ϟ Bg gem: ${round(bgGem, 1)}     ϟ Rel StdDev: $rel_std %.\n"
                correctionFactors.add(cf)
            }
        }

        var ResistentieCfEff = 1.0
        if (correctionFactors.isNotEmpty()) {
            var tot_gew_gem = 0
            val weights = listOf(70, 25, 5, 3, 2)

            for (i in correctionFactors.indices) {
                val weight = if (i < weights.size) weights[i] else 1
                ResistentieCfEff += correctionFactors[i] * weight
                tot_gew_gem += weight
            }
            ResistentieCfEff /= tot_gew_gem.toDouble()
        }

        val minRes = MinresistentiePerc.toDouble() / 100
        val maxRes = MaxresistentiePerc.toDouble() / 100
        ResistentieCfEff = ResistentieCfEff.coerceIn(minRes, maxRes)

        val status = if (ResistentieCfEff > minRes && ResistentieCfEff < maxRes) "Cf_eff" else "Cf_eff (begrensd)"
        log_resistentie += "\n »» $status = ${(ResistentieCfEff * 100).toInt()}%\n"

        return ResistentieResult(ResistentieCfEff, log_resistentie)
    }

    private fun calculateCorrectionFactor(bgGem: Double, targetProfiel: Double, macht: Double, rel_std: Int): Double {
        var rel_std_cf = 1.0
        if (bgGem > targetProfiel && rel_std > 0) {
            rel_std_cf = 1.0 / rel_std + 1.0
        }
        var cf = Math.pow(bgGem / targetProfiel, macht) * rel_std_cf
        if (cf < 0.1) cf = 1.0
        return cf
    }

    private fun getBgHistoryWithStdDev(startHour: Long, endHour: Long, uren: Long): Pair<Double, Double> {
        val MIN_READINGS_PER_HOUR = 8
        val MG_DL_TO_MMOL_L_CONVERSION = 18.0

        val now = System.currentTimeMillis()

        // ★★★ CORRECTIE: directe millis berekening ★★★
        val startTime = now - (startHour * 60 * 60 * 1000)
        val endTime = now - (endHour * 60 * 60 * 1000)

        val bgReadings = persistenceLayer.getBgReadingsDataFromTimeToTime(startTime, endTime, false)

        if (bgReadings.size < MIN_READINGS_PER_HOUR * uren) {
            return Pair(0.0, 0.0)
        }

        val totalBgValue = bgReadings.sumOf { it.value }
        val bgAverage = (totalBgValue / bgReadings.size) / MG_DL_TO_MMOL_L_CONVERSION

        val variance = bgReadings.sumOf {
            val bgInMmol = it.value / MG_DL_TO_MMOL_L_CONVERSION
            (bgInMmol - bgAverage) * (bgInMmol - bgAverage)
        } / bgReadings.size

        val stdDev = Math.sqrt(variance)
        return Pair(bgAverage, stdDev)
    }

    private fun round(value: Double, digits: Int): Double {
        val scale = Math.pow(10.0, digits.toDouble())
        return Math.round(value * scale) / scale
    }
}