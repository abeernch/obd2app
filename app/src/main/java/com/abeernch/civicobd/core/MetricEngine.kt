package com.abeernch.civicobd.core

/**
 * Values computed from the raw PIDs. Spec section 5.
 * This ECU reports MAP but not MAF or fuel trims, so fuel flow uses the speed-density method.
 */
data class Derived(
    val throttleRel: Double? = null,  // 0 % at closed throttle
    val fuelLph: Double? = null,      // litres per hour
    val instantKmL: Double? = null,   // null when stopped or in fuel cut
    val avg10KmL: Double? = null,     // last 10 seconds
    val boostKpa: Double? = null,     // MAP minus outside pressure, negative is vacuum
    val accelG: Double? = null,
    val powerHp: Double? = null,      // estimate from fuel flow
    val gear: Int = 0,                // 0 means neutral or clutch pressed
    val fuelCut: Boolean = false,
    val dtS: Double = 0.0,            // seconds since the previous step
    val shiftAtRpm: Double? = null,   // upshift point for the current throttle, null when not relevant
    val shiftUp: Boolean = false,     // engine speed has reached the upshift point
    val lugging: Boolean = false,     // too few rpm for the load: shift down
)

object FuelModel {
    const val DISPLACEMENT_L = 1.5
    // Volumetric efficiency. 0.845 matches Car Scanner's fuel rate on this car
    // (checked against its 23 Oct log). Fill-ups refine it through the fuel factor.
    const val VE = 0.845
    const val AFR = 14.7              // stoichiometric air to fuel ratio for petrol
    const val PETROL_G_PER_L = 740.0

    /** Air mass flow in g/s from MAP (kPa), RPM and intake temperature (°C). */
    fun airGps(mapKpa: Double, rpm: Double, iatC: Double): Double =
        mapKpa * (rpm / 120.0) * VE * DISPLACEMENT_L * 28.97 / (8.314 * (iatC + 273.15))

    /** Fuel flow in L/h. */
    fun fuelLph(mapKpa: Double, rpm: Double, iatC: Double, factor: Double): Double =
        airGps(mapKpa, rpm, iatC) / AFR / PETROL_G_PER_L * 3600.0 * factor
}

class MetricEngine(learned: List<Double>? = null) {

    // Speed in km/h per 1000 RPM for gears 1 to 5. Third gear was measured on 23 Oct
    // (about 20.5). The others start from typical 7th-gen Civic ratios, then each one
    // is refined from your own steady driving (see learn()).
    private val kmhPer1000 = (learned?.takeIf { it.size == 5 } ?: DEFAULT_RATIOS).toMutableList()

    /** Current gear ratios, for saving between drives. */
    fun ratios(): List<Double> = kmhPer1000.toList()

    companion object {
        val DEFAULT_RATIOS = listOf(8.1, 13.6, 20.5, 26.7, 34.8)
    }

    private var closedThrottle = 100.0
    private var baroKpa = 95.0          // replaced by MAP whenever the engine is off
    private var lastMs = 0L
    private var lastSpeed: Double? = null
    private var accel = 0.0
    private val window = ArrayDeque<Triple<Long, Double, Double>>() // time, km, litres
    private var gearCandidate = 0
    private var gearCount = 0
    private var gear = 0

    fun step(v: Map<Int, Double>, now: Long, factor: Double): Derived {
        val dtS = if (lastMs == 0L) 0.0 else ((now - lastMs) / 1000.0).coerceAtMost(3.0)
        lastMs = now

        val rpm = v[0x0C]
        val speed = v[0x0D]
        val map = v[0x0B]
        val iat = v[0x0F] ?: 30.0
        val throttle = v[0x11]

        // Closed throttle on this car reads about 9.8 %. Learn the exact value as the lowest seen.
        if (throttle != null && throttle < closedThrottle) closedThrottle = throttle
        val throttleRel = throttle?.let {
            ((it - closedThrottle) / (100.0 - closedThrottle) * 100.0).coerceIn(0.0, 100.0)
        }

        if (rpm != null && rpm < 1.0 && map != null) baroKpa = map

        // Closed throttle, engine above idle, car rolling: the ECU cuts fuel completely.
        val fuelCut = rpm != null && speed != null && throttleRel != null &&
            throttleRel < 1.0 && rpm > 1300 && speed > 5

        val fuelLph = when {
            rpm == null || map == null -> null
            rpm < 1.0 || fuelCut -> 0.0
            else -> FuelModel.fuelLph(map, rpm, iat, factor)
        }

        val instant = if (speed != null && speed >= 3 && fuelLph != null && fuelLph > 0.05) {
            (speed / fuelLph).coerceAtMost(99.9)
        } else {
            null
        }

        // Rolling 10 second average
        if (dtS > 0 && speed != null && fuelLph != null) {
            window.addLast(Triple(now, speed * dtS / 3600.0, fuelLph * dtS / 3600.0))
        }
        while (window.isNotEmpty() && now - window.first().first > 10_000) window.removeFirst()
        val windowKm = window.sumOf { it.second }
        val windowL = window.sumOf { it.third }
        val avg10 = if (windowL > 0.0005 && windowKm > 0.005) (windowKm / windowL).coerceAtMost(99.9) else null

        // Acceleration in g, smoothed
        if (speed != null && dtS > 0) {
            val prev = lastSpeed
            if (prev != null) {
                val a = (speed - prev) / 3.6 / dtS / 9.81
                accel = 0.7 * accel + 0.3 * a
            }
            lastSpeed = speed
        }

        // Power estimate: fuel energy 34.2 MJ/L, about 27 % reaches the crankshaft.
        val powerHp = fuelLph?.let { it * 34.2 * 1000.0 / 3600.0 * 0.27 * 1.341 }

        // Gear must hold for two readings before the display changes
        val g = gearFor(rpm, speed)
        if (g == gearCandidate) gearCount++ else {
            gearCandidate = g
            gearCount = 1
        }
        if (gearCount >= 2) gear = gearCandidate

        // Gear learning: in steady driving the speed-to-rpm ratio is exact for the gear in use,
        // so nudge that gear's stored ratio towards it. Slow, so one odd sample changes little.
        if (gear > 0 && rpm != null && speed != null && speed > 20 && kotlin.math.abs(accel) < 0.03 &&
            throttleRel != null && throttleRel in 3.0..60.0
        ) {
            val ratio = speed / (rpm / 1000.0)
            val i = gear - 1
            if (kotlin.math.abs(ratio - kmhPer1000[i]) / kmhPer1000[i] < 0.08) {
                kmhPer1000[i] = kmhPer1000[i] * 0.98 + ratio * 0.02
            }
        }

        // Shift guidance. The upshift point rises with throttle: about 2,500 rpm when cruising
        // gently, up to 5,500 rpm at full throttle. While the engine is cold it stays low.
        val coolant = v[0x05]
        var shiftAt: Double? = null
        var shiftUp = false
        var lugging = false
        if (gear in 1..4 && rpm != null && speed != null && speed > 5 && throttleRel != null && !fuelCut) {
            shiftAt = 2500.0 + (throttleRel / 100.0).coerceIn(0.0, 1.0) * 3000.0
            if (coolant != null && coolant < 70) shiftAt = minOf(shiftAt, 3000.0)
            shiftUp = rpm >= shiftAt
        }
        if (gear >= 2 && rpm != null && rpm < 1300 && throttleRel != null && throttleRel > 40) lugging = true

        return Derived(
            throttleRel = throttleRel,
            fuelLph = fuelLph,
            instantKmL = instant,
            avg10KmL = avg10,
            boostKpa = map?.let { it - baroKpa },
            accelG = accel,
            powerHp = powerHp,
            gear = gear,
            fuelCut = fuelCut,
            dtS = dtS,
            shiftAtRpm = shiftAt,
            shiftUp = shiftUp,
            lugging = lugging,
        )
    }

    private fun gearFor(rpm: Double?, speed: Double?): Int {
        if (rpm == null || speed == null || rpm < 700 || speed < 3) return 0
        val ratio = speed / (rpm / 1000.0)
        var best = 0
        var bestError = 1.0
        kmhPer1000.forEachIndexed { i, r ->
            val error = kotlin.math.abs(ratio - r) / r
            if (error < bestError) {
                bestError = error
                best = i + 1
            }
        }
        return if (bestError < 0.12) best else 0
    }
}
