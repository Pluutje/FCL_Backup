package app.aaps.plugins.aps.openAPSFCL.vnext

import org.joda.time.DateTime
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.Preferences
import app.aaps.core.keys.StringKey
import kotlin.collections.get

data class FCLvNextInput(
    val bgNow: Double,                          // mmol/L
    val bgHistory: List<Pair<DateTime, Double>>, // mmol/L
    val currentIOB: Double,
    val maxIOB: Double,
    val effectiveISF: Double,                   // mmol/L per U
    val targetBG: Double,                       // mmol/L
    val isNight: Boolean
)

data class FCLvNextContext(
    val input: FCLvNextInput,

    // trends
    val slope: Double,          // mmol/L per uur
    val acceleration: Double,   // mmol/L per uur²
    val consistency: Double,    // 0..1

    // relatieve veiligheid
    val iobRatio: Double,       // currentIOB / maxIOB

    // afstand tot target
    val deltaToTarget: Double   // bgNow - targetBG
)

data class FCLvNextAdvice(
    val bolusAmount: Double,
    val basalRate: Double,
    val shouldDeliver: Boolean,

    // feedback naar determineBasal
    val effectiveISF: Double,
    val targetAdjustment: Double,

    // debug / UI
    val statusText: String
)

private data class DecisionResult(
    val allowed: Boolean,
    val force: Boolean,
    val dampening: Double,
    val reason: String
)

private data class ExecutionResult(
    val bolus: Double,
    val basalRate: Double,      // U/h (temp basal 30 min)
    val deliveredTotal: Double  // bolus + (basalRate * 0.5h)
)

private enum class MealState { NONE, UNCERTAIN, CONFIRMED }

private data class MealSignal(
    val state: MealState,
    val confidence: Double,     // 0..1
    val reason: String
)

private var lastCommitAt: DateTime? = null
private var lastCommitDose: Double = 0.0
private var lastCommitReason: String = ""


private fun calculateEnergy(
    ctx: FCLvNextContext,
    config: FCLvNextConfig
): Double {

    val positional = ctx.deltaToTarget * config.kDelta
    val kinetic = ctx.slope * config.kSlope
    val accelerationBoost = ctx.acceleration * config.kAccel

    var energy = positional + kinetic + accelerationBoost

    // betrouwbaarheid (exponentieel)
    val consistency = ctx.consistency
        .coerceAtLeast(config.minConsistency)
        .let { Math.pow(it, config.consistencyExp) }

    energy *= consistency

    return energy
}


private fun energyToInsulin(
    energy: Double,
    effectiveISF: Double,
    config: FCLvNextConfig
): Double {
    if (energy <= 0.0) return 0.0
    return (energy / effectiveISF) * config.gain
}



private fun decide(ctx: FCLvNextContext): DecisionResult {

    // === LAYER A — HARD STOPS ===
    if (ctx.consistency < 0.2) {
        return DecisionResult(
            allowed = false,
            force = false,
            dampening = 0.0,
            reason = "Hard stop: unreliable data"
        )
    }

    if (ctx.iobRatio > 1.1) {
        return DecisionResult(
            allowed = false,
            force = false,
            dampening = 0.0,
            reason = "Hard stop: IOB saturated"
        )
    }

    // === LAYER C — FORCE ALLOW ===
    if (ctx.slope > 2.0 && ctx.acceleration > 0.5 && ctx.consistency > 0.6) {
        return DecisionResult(
            allowed = true,
            force = true,
            dampening = 1.0,
            reason = "Force: strong rising trend"
        )
    }

    // === LAYER B — SOFT ALLOW ===
    val nightFactor = if (ctx.input.isNight) 0.7 else 1.0
    val consistencyFactor = ctx.consistency.coerceIn(0.3, 1.0)

    return DecisionResult(
        allowed = true,
        force = false,
        dampening = nightFactor * consistencyFactor,
        reason = "Soft allow"
    )
}

private fun executeDelivery(
    dose: Double,
    hybridPercentage: Int,
    bolusStep: Double = 0.05,      // SMB/bolus stap
    basalRateStep: Double = 0.05,  // U/h stap
    minSmb: Double = 0.05,
    smallDoseThreshold: Double = 0.20
): ExecutionResult {

    // 0) onder SMB minimum: niets
    if (dose < minSmb) {
        return ExecutionResult(
            bolus = 0.0,
            basalRate = 0.0,
            deliveredTotal = 0.0
        )
    }

    // 1) kleine doses: alleen SMB (voorkomt dubbel afronden)
    if (dose < smallDoseThreshold || hybridPercentage <= 0) {
        val bolusRounded = roundToStep(dose, bolusStep).coerceAtLeast(minSmb)
        return ExecutionResult(
            bolus = bolusRounded,
            basalRate = 0.0,
            deliveredTotal = bolusRounded
        )
    }

    // 2) hybride split in units (30 min basal = rate * 0.5)
    val idealBasalUnits = dose * (hybridPercentage / 100.0)
    val idealBolusUnits = dose - idealBasalUnits

    // 3) als bolus deel te klein wordt -> SMB-only
    if (idealBolusUnits < minSmb) {
        val bolusRounded = roundToStep(dose, bolusStep).coerceAtLeast(minSmb)
        return ExecutionResult(
            bolus = bolusRounded,
            basalRate = 0.0,
            deliveredTotal = bolusRounded
        )
    }

    // 4) rond bolus af
    val bolusRounded = roundToStep(idealBolusUnits, bolusStep)
        .coerceAtLeast(minSmb)
        .coerceAtMost(dose)

    // 5) rest naar basal (units over 30 min)
    val basalUnitsWanted = (dose - bolusRounded).coerceAtLeast(0.0)

    // 6) basalUnits -> rate voor 30 min: rate = units / 0.5 = units * 2
    val basalRateWanted = basalUnitsWanted * 2.0

    // 7) ROUND (jouw voorkeur) op rate step
    val basalRateRounded = roundToStep(basalRateWanted, basalRateStep).coerceAtLeast(0.0)

    // 8) werkelijk basal units voor 30 min
    val basalUnitsDelivered = basalRateRounded * 0.5

    val delivered = bolusRounded + basalUnitsDelivered

    return ExecutionResult(
        bolus = bolusRounded,
        basalRate = basalRateRounded,
        deliveredTotal = delivered
    )
}


private fun iobDampingFactor(
    iobRatio: Double,
    config: FCLvNextConfig,
    power: Double = 2.0
): Double {

    val r = iobRatio.coerceIn(0.0, 2.0)

    if (r <= config.iobStart) return 1.0
    if (r >= config.iobMax) return config.iobMinFactor

    val x = ((r - config.iobStart) /
        (config.iobMax - config.iobStart))
        .coerceIn(0.0, 1.0)

    val shaped = 1.0 - Math.pow(x, power)

    return (config.iobMinFactor +
        (1.0 - config.iobMinFactor) * shaped)
        .coerceIn(config.iobMinFactor, 1.0)
}

private fun roundToStep(value: Double, step: Double): Double {
    if (step <= 0.0) return value
    return (kotlin.math.round(value / step) * step)
}

private fun clamp(value: Double, min: Double, max: Double): Double {
    return value.coerceIn(min, max)
}


private fun isPersistentHighBG(
    ctx: FCLvNextContext,
    config: FCLvNextConfig
): Boolean {

    val aboveTarget =
        ctx.deltaToTarget >= config.persistentDeltaTarget

    val stableBG =
        kotlin.math.abs(ctx.slope) <= config.persistentMaxSlope

    val iobOk =
        ctx.iobRatio < config.persistentIobLimit

    val reliable =
        ctx.consistency >= config.minConsistency

    return aboveTarget && stableBG && iobOk && reliable
}
private fun calculatePersistentDose(
    ctx: FCLvNextContext,
    config: FCLvNextConfig,
    maxSMB: Double
): Double {

    if (maxSMB <= 0.0) return 0.0

    // basis shot
    val baseDose = maxSMB * config.persistentFraction

    // hergebruik EXACT dezelfde IOB-remming
     val iobFactor = iobDampingFactor(
        iobRatio = ctx.iobRatio,
        config = config
    )

    return (baseDose * iobFactor).coerceAtLeast(0.0)
}

private fun detectMealSignal(ctx: FCLvNextContext, config: FCLvNextConfig): MealSignal {

    // basisvoorwaarden: voldoende data
    if (ctx.consistency < config.minConsistency) {
        return MealSignal(MealState.NONE, 0.0, "Low consistency")
    }

    val rising = ctx.slope > config.mealSlopeMin
    val accelerating = ctx.acceleration > config.mealAccelMin
    val aboveTarget = ctx.deltaToTarget > config.mealDeltaMin

    // confidence: combineer factoren (simpel, maar werkt)
    val slopeScore = ((ctx.slope - config.mealSlopeMin) / config.mealSlopeSpan).coerceIn(0.0, 1.0)
    val accelScore = ((ctx.acceleration - config.mealAccelMin) / config.mealAccelSpan).coerceIn(0.0, 1.0)
    val deltaScore = ((ctx.deltaToTarget - config.mealDeltaMin) / config.mealDeltaSpan).coerceIn(0.0, 1.0)

    val confidence =
        (0.45 * slopeScore + 0.35 * accelScore + 0.20 * deltaScore)
            .coerceIn(0.0, 1.0)

    // state
    val state = when {
        rising && accelerating && aboveTarget && confidence >= config.mealConfirmConfidence ->
            MealState.CONFIRMED

        (rising || accelerating) && aboveTarget && confidence >= config.mealUncertainConfidence ->
            MealState.UNCERTAIN

        else -> MealState.NONE
    }

    val reason = "MealSignal=$state conf=${"%.2f".format(confidence)}"
    return MealSignal(state, confidence, reason)
}

private fun canCommitNow(now: DateTime, config: FCLvNextConfig): Boolean {
    val last = lastCommitAt ?: return true
    val minutes = org.joda.time.Minutes.minutesBetween(last, now).minutes
    return minutes >= config.commitCooldownMinutes
}

private fun computeCommitFraction(signal: MealSignal, config: FCLvNextConfig): Double {
    return when (signal.state) {
        MealState.NONE -> 0.0

        MealState.UNCERTAIN -> {
            // 0.45..0.70 afhankelijk van confidence
            val t = ((signal.confidence - config.mealUncertainConfidence) /
                (config.mealConfirmConfidence - config.mealUncertainConfidence))
                .coerceIn(0.0, 1.0)
            (config.uncertainMinFraction + t * (config.uncertainMaxFraction - config.uncertainMinFraction))
        }

        MealState.CONFIRMED -> {
            // 0.70..1.00 afhankelijk van confidence
            val t = ((signal.confidence - config.mealConfirmConfidence) /
                (1.0 - config.mealConfirmConfidence))
                .coerceIn(0.0, 1.0)
            (config.confirmMinFraction + t * (config.confirmMaxFraction - config.confirmMinFraction))
        }
    }.coerceIn(0.0, 1.0)
}




class FCLvNext(
    private val preferences: Preferences
) {
    private fun buildContext(input: FCLvNextInput): FCLvNextContext {
        val filteredHistory = FCLvNextBgFilter.ewma(
            data = input.bgHistory,
            alpha = 0.4   // later ook pref-gestuurd mogelijk
        )

        val points = filteredHistory.map { (t, bg) ->
            FCLvNextTrends.BGPoint(t, bg)
        }

        val trends = FCLvNextTrends.calculateTrends(points)

        val iobRatio = if (input.maxIOB > 0.0) {
            (input.currentIOB / input.maxIOB).coerceIn(0.0, 1.5)
        } else 0.0

        return FCLvNextContext(
            input = input,
            slope = trends.firstDerivative,
            acceleration = trends.secondDerivative,
            consistency = trends.consistency,
            iobRatio = iobRatio,
            deltaToTarget = input.bgNow - input.targetBG
        )
    }

    fun getAdvice(input: FCLvNextInput): FCLvNextAdvice {

        // ─────────────────────────────────────────────
        // 1️⃣ Config & context (trends, IOB, delta)
        // ─────────────────────────────────────────────
        val config = loadFCLvNextConfig(preferences, input.isNight)
        val ctx = buildContext(input)

        val status = StringBuilder()

        // ─────────────────────────────────────────────
        // 2️⃣ Persistent HIGH BG detectie
        //     (los van maaltijdlogica)
        // ─────────────────────────────────────────────
        val persistentActive = isPersistentHighBG(ctx, config)
        status.append("Persistent=${if (persistentActive) "YES" else "NO"}\n")

        // ─────────────────────────────────────────────
        // 3️⃣ Energie-model (positie + snelheid + versnelling)
        // ─────────────────────────────────────────────
        val energy = calculateEnergy(ctx, config)
        status.append("Energy=${"%.2f".format(energy)}\n")

        // ─────────────────────────────────────────────
        // 4️⃣ Ruwe dosis uit energie
        // ─────────────────────────────────────────────
        val rawDose = energyToInsulin(
            energy = energy,
            effectiveISF = input.effectiveISF,
            config = config
        )
        status.append("RawDose=${"%.2f".format(rawDose)}U\n")

        // ─────────────────────────────────────────────
        // 5️⃣ Beslissingslaag (hard stop / force / soft allow)
        // ─────────────────────────────────────────────
        val decision = decide(ctx)

        val decidedDose = when {
            !decision.allowed -> 0.0
            decision.force -> rawDose
            else -> rawDose * decision.dampening
        }

        status.append(
            "Decision=${decision.reason} → ${"%.2f".format(decidedDose)}U\n"
        )

        // ─────────────────────────────────────────────
        // 6️⃣ IOB-remming (centraal, altijd toepassen)
        // ─────────────────────────────────────────────
        val iobFactor = iobDampingFactor(
            iobRatio = ctx.iobRatio,
            config = config
        )

        var finalDose = (decidedDose * iobFactor).coerceAtLeast(0.0)

        status.append(
            "IOB=${"%.2f".format(ctx.iobRatio)} " +
                "factor=${"%.2f".format(iobFactor)} " +
                "→ ${"%.2f".format(finalDose)}U\n"
        )

        // ─────────────────────────────────────────────
        // 7️⃣ Persistent HIGH BG (additief)
        //     → kleine extra correcties als BG lang hoog blijft
        // ─────────────────────────────────────────────
        var persistentDose = 0.0

        if (persistentActive) {
            persistentDose = calculatePersistentDose(
                ctx = ctx,
                config = config,
                maxSMB = config.maxSMB
            )

            if (persistentDose > 0.0) {
                finalDose += persistentDose
                status.append(
                    "PersistentDose=${"%.2f".format(persistentDose)}U\n"
                )
            }
        }

        // ─────────────────────────────────────────────
        // 8️⃣ Absolute max SMB cap
        // ─────────────────────────────────────────────
        if (finalDose > config.maxSMB) {
            status.append(
                "Cap maxSMB ${"%.2f".format(finalDose)} → ${"%.2f".format(config.maxSMB)}U\n"
            )
            finalDose = config.maxSMB
        }

        // ─────────────────────────────────────────────
        // 9️⃣ Meal detectie & commit/observe-laag
        //     (hier zit het oude FCL-gedrag)
        // ─────────────────────────────────────────────
        val now = DateTime.now()
        val mealSignal = detectMealSignal(ctx, config)
        val commitAllowed = canCommitNow(now, config)

        status.append(mealSignal.reason + "\n")
        status.append("CommitAllowed=${if (commitAllowed) "YES" else "NO"}\n")

        /*
         Default gedrag:
         - finalDose = “normale” berekende correctie
         - meal-commit kan dit OPSCHALEN
         - cooldown blokkeert alleen commit, niet correcties
         */
        var commandedDose = finalDose

        if (mealSignal.state != MealState.NONE) {

            if (commitAllowed) {
                // ── STAP 1: grote eerste stap bij vertrouwen ──
                val fraction = computeCommitFraction(mealSignal, config)

                // Commit is een snelle, stevige stap (oude FCL-achtig)
                val commitDose =
                    (config.maxSMB * fraction)
                        .coerceAtMost(config.maxSMB)

                // Gebruik altijd de hoogste van:
                // - normale berekening
                // - meal commit
                val committedDose = maxOf(finalDose, commitDose)

                if (committedDose >= config.minCommitDose) {
                    commandedDose = committedDose

                    lastCommitAt = now
                    lastCommitDose = committedDose
                    lastCommitReason =
                        "${mealSignal.state} frac=${"%.2f".format(fraction)}"

                    status.append(
                        "COMMIT ${"%.2f".format(committedDose)}U " +
                            "(${mealSignal.state}, conf=${"%.2f".format(mealSignal.confidence)})\n"
                    )
                } else {
                    status.append("COMMIT skipped (below minCommitDose)\n")
                }

            } else {
                // ── STAP 2: observeerfase ──
                // Geen nieuwe grote stap, maar normale correcties blijven toegestaan
                commandedDose = finalDose
                status.append("OBSERVE (commit cooldown) → normal correction\n")
            }
        }

        // ─────────────────────────────────────────────
        // 🔟 Execution: SMB / hybride bolus + basaal
        // ─────────────────────────────────────────────
        val execution = executeDelivery(
            dose = commandedDose,
            hybridPercentage = config.hybridPercentage
        )

        status.append(
            "Execution: bolus=${"%.2f".format(execution.bolus)}U " +
                "basal=${"%.2f".format(execution.basalRate)}U/h (30m) " +
                "delivered=${"%.2f".format(execution.deliveredTotal)}U\n"
        )

        val shouldDeliver =
            execution.bolus >= 0.05 || execution.basalRate > 0.0

        // ─────────────────────────────────────────────
        // CSV logging (analyse / tuning)
        // ─────────────────────────────────────────────
        FCLvNextCsvLogger.log(
            isNight = input.isNight,
            bg = input.bgNow,
            target = input.targetBG,
            slope = ctx.slope,
            accel = ctx.acceleration,
            consistency = ctx.consistency,
            iob = input.currentIOB,
            iobRatio = ctx.iobRatio,
            effectiveISF = input.effectiveISF,
            gain = config.gain,
            energy = energy,
            rawDose = rawDose,
            iobFactor = iobFactor,
            persistentActive = persistentActive,
            persistentDose = persistentDose,
            finalDose = commandedDose,
            deliveredTotal = execution.deliveredTotal,
            bolus = execution.bolus,
            basalRate = execution.basalRate,
            shouldDeliver = shouldDeliver,
            decisionReason = decision.reason
        )

        // ─────────────────────────────────────────────
        // RETURN
        // ─────────────────────────────────────────────
        return FCLvNextAdvice(
            bolusAmount = execution.bolus,
            basalRate = execution.basalRate,
            shouldDeliver = shouldDeliver,
            effectiveISF = input.effectiveISF,
            targetAdjustment = 0.0,
            statusText = status.toString()
        )
    }




}


