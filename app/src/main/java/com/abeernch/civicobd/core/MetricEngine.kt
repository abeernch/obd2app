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

class MetricEngine {

    // Speed in km/h per 1000 RPM for gears 1 to 5. Third gear was measured on 23 Oct
    // (about 20.5). The others are scaled from typical 7th-gen Civic ratios.
    private val kmhPer1000 = listOf(8.1, 13.6, 20.5, 26.7, 34.8)

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
