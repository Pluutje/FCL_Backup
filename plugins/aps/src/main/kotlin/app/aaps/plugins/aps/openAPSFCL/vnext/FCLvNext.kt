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
    val basalRate: Double
)


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


private fun iobEnergyDamping(iobRatio: Double): Double {
    return when {
        iobRatio < 0.3 -> 1.0
        iobRatio < 0.6 -> 1.0 - (iobRatio - 0.3) * 0.7
        iobRatio < 0.9 -> 0.5 - (iobRatio - 0.6) * 1.0
        else -> 0.2
    }.coerceIn(0.1, 1.0)
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
    hybridPercentage: Int
): ExecutionResult {

    if (dose <= 0.0 || hybridPercentage <= 0) {
        return ExecutionResult(
            bolus = dose,
            basalRate = 0.0
        )
    }

    val basalPart = dose * (hybridPercentage / 100.0)
    val bolusPart = dose - basalPart

    // 10 minuten basaal → U/h
    val basalRate = basalPart * 6.0

    return ExecutionResult(
        bolus = bolusPart,
        basalRate = basalRate
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

        // 1️⃣ Config & context
        val config = loadFCLvNextConfig(preferences, input.isNight)
        val ctx = buildContext(input)

        val status = StringBuilder()

        // ─────────────────────────────────────────────
        // Persistent detectie (STAP 1)
        // ─────────────────────────────────────────────

        val persistentActive = isPersistentHighBG(ctx, config)

        status.append(
            "Persistent=${if (persistentActive) "YES" else "NO"}\n"
        )

        // ─────────────────────────────────────────────
        // 2️⃣ Energie (hoofdmodel)
        // ─────────────────────────────────────────────

        val energy = calculateEnergy(ctx, config)
        status.append("Energy=${"%.2f".format(energy)}\n")

        // ─────────────────────────────────────────────
        // 3️⃣ Ruwe dosis uit energie
        // ─────────────────────────────────────────────

        val rawDose = energyToInsulin(
            energy = energy,
            effectiveISF = input.effectiveISF,
            config = config
        )

        status.append("RawDose=${"%.2f".format(rawDose)}U\n")

        // ─────────────────────────────────────────────
        // 4️⃣ Beslissing (allow / dampening)
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
        // 5️⃣ IOB-remming (CRUCIAAL, centraal)
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
// 6️⃣ Persistent HIGH BG (additief)
// ─────────────────────────────────────────────

        if (persistentActive) {
            val persistentDose = calculatePersistentDose(
                ctx = ctx,
                config = config,
                maxSMB = config.maxSMB
            )

            if (persistentDose > 0.0) {
                finalDose += persistentDose
                status.append("PersistentDose=${"%.2f".format(persistentDose)}U\n")
            }
        }

// ─────────────────────────────────────────────
// 7️⃣ MAX SMB CAP (ABSOLUUT)
// ─────────────────────────────────────────────

        if (finalDose > config.maxSMB) {
            status.append(
                "Cap maxSMB ${"%.2f".format(finalDose)} → ${"%.2f".format(config.maxSMB)}U\n"
            )
            finalDose = config.maxSMB
        }

        // ─────────────────────────────────────────────
        // 7️⃣ Execution & hybrid delivery
        // ─────────────────────────────────────────────

        val execution = executeDelivery(
            dose = finalDose,
            hybridPercentage = config.hybridPercentage
        )

        status.append(
            "Execution: bolus=${"%.2f".format(execution.bolus)}U " +
                "basal=${"%.2f".format(execution.basalRate)}U/h\n"
        )

        val shouldDeliver =
            execution.bolus > 0.05 || execution.basalRate > 0.0

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


