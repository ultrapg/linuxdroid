package com.linuxdroid.app.session

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import com.linuxdroid.app.core.FileUtils
import java.io.File
import kotlin.math.abs

class BatteryComponent(
    context: Context,
    val sysfsPowerSupplyDir: File,
) {
    private val appContext = context.applicationContext

    @Volatile
    private var running = false
    private var thread: Thread? = null

    fun start() {
        FileUtils.deleteRecursively(File(sysfsPowerSupplyDir, "BAT0"))
        sysfsPowerSupplyDir.mkdirs()
        running = true
        writeSnapshot()
        thread = Thread({
            while (running) {
                try {
                    Thread.sleep(5_000L)
                } catch (_: InterruptedException) {
                    break
                }
                if (running) writeSnapshot()
            }
        }, "battery-sysfs").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        running = false
        thread?.interrupt()
        thread = null
    }

    private fun writeSnapshot() {
        try {
            val bm = appContext.getSystemService(BatteryManager::class.java)
            val sticky = appContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val status = sticky?.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
                ?: BatteryManager.BATTERY_STATUS_UNKNOWN
            val level = sticky?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = (sticky?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100).coerceAtLeast(1)
            val pct = if (level >= 0) (level * 100 / scale).coerceIn(0, 100)
            else (bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: 80).coerceIn(0, 100)

            val voltageUv = ((sticky?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0).toLong() * 1000L)
                .takeIf { it > 0 } ?: 3_850_000L
            val tempDeci = sticky?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 300) ?: 300
            val tech = sticky?.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY)?.takeIf { it.isNotBlank() } ?: "Li-ion"

            val chargeNowUah = bm?.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
                ?.takeIf { it > 0 && it != Long.MIN_VALUE } ?: 0L
            val rawCur = bm?.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
                ?.takeIf { it != Long.MIN_VALUE } ?: 0L
            val currentNowUa = if (abs(rawCur) < 10_000L) rawCur * 1000L else rawCur

            val chargeFullUah = if (chargeNowUah > 0 && pct > 0) chargeNowUah * 100L / pct else 4_500_000L
            val energyNowUwh = if (chargeNowUah > 0) chargeNowUah * voltageUv / 1_000_000L else pct * 170_000L
            val energyFullUwh = chargeFullUah * voltageUv / 1_000_000L
            val powerNowUw = abs(currentNowUa) * voltageUv / 1_000_000L

            val discharging = status == BatteryManager.BATTERY_STATUS_DISCHARGING ||
                status == BatteryManager.BATTERY_STATUS_NOT_CHARGING
            val charging = status == BatteryManager.BATTERY_STATUS_CHARGING
            val timeToEmptyS = if (discharging && chargeNowUah > 0 && abs(currentNowUa) > 0) {
                chargeNowUah * 3600L / abs(currentNowUa)
            } else 0L
            val timeToFullS = if (charging && chargeFullUah > chargeNowUah && abs(currentNowUa) > 0) {
                (chargeFullUah - chargeNowUah) * 3600L / abs(currentNowUa)
            } else 0L

            val statusText = when (status) {
                BatteryManager.BATTERY_STATUS_CHARGING -> if (pct >= 100) "Full" else "Charging"
                BatteryManager.BATTERY_STATUS_DISCHARGING -> "Discharging"
                BatteryManager.BATTERY_STATUS_FULL -> "Full"
                BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "Not charging"
                else -> "Discharging"
            }
            val capLevel = when {
                pct >= 100 -> "Full"
                pct <= 5 -> "Critical"
                pct <= 15 -> "Low"
                else -> "Normal"
            }

            val batDir = File(sysfsPowerSupplyDir, "BAT1").apply { mkdirs() }
            val attrs = linkedMapOf(
                "type" to "Battery",
                "present" to "1",
                "status" to statusText,
                "capacity" to pct.toString(),
                "capacity_level" to capLevel,
                "technology" to tech,
                "voltage_now" to voltageUv.toString(),
                "voltage_min_design" to voltageUv.toString(),
                "current_now" to currentNowUa.toString(),
                "charge_now" to chargeNowUah.toString(),
                "charge_full" to chargeFullUah.toString(),
                "charge_full_design" to chargeFullUah.toString(),
                "energy_now" to energyNowUwh.toString(),
                "energy_full" to energyFullUwh.toString(),
                "energy_full_design" to energyFullUwh.toString(),
                "power_now" to powerNowUw.toString(),
                "time_to_empty_now" to timeToEmptyS.toString(),
                "time_to_full_now" to timeToFullS.toString(),
                "temp" to tempDeci.toString(),
                "model_name" to Build.MODEL.replace(' ', '_'),
                "manufacturer" to Build.MANUFACTURER.replace(' ', '_'),
                "scope" to "System",
            )
            val uevent = StringBuilder("POWER_SUPPLY_NAME=BAT1\n")
            for ((k, v) in attrs) {
                FileUtils.writeString(File(batDir, k), "$v\n")
                uevent.append("POWER_SUPPLY_").append(k.uppercase()).append('=').append(v).append('\n')
            }
            FileUtils.writeString(File(batDir, "uevent"), uevent.toString())
        } catch (_: Throwable) {
        }
    }
}
