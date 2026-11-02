package dev.horizon.mobile

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager

/**
 * Protects the phone: decides before launch whether the device can run the game at all, and while
 * it runs whether the game should be stopped before the device overheats or runs out of memory.
 * Stopping goes through the activity's normal close, so the game saves the world first.
 */
object DeviceGuard {
    private const val MIB = 1024L * 1024L
    private const val MIN_TOTAL_RAM = 2560L * MIB
    private const val BLOCK_FREE_RAM = 800L * MIB
    private const val WARN_FREE_RAM = 1500L * MIB
    private const val STOP_FREE_RAM = 200L * MIB

    private const val THERMAL_MODERATE = 2
    private const val THERMAL_SEVERE = 3

    private const val BLOCK_BATTERY_TEMP = 43f
    private const val WARN_BATTERY_TEMP = 40f
    private const val STOP_BATTERY_TEMP = 45f

    /** [fatal]: the game cannot run on this device at all, whatever the person chooses. */
    class Verdict(
        val blockers: List<String>,
        val warnings: List<String>,
        val memoryLine: String,
        val fatal: Boolean,
    ) {
        val ok: Boolean get() = blockers.isEmpty()
    }

    /** Why a running game is stopped; [message] is a string resource. */
    enum class StopReason(val message: Int) {
        THERMAL(R.string.stop_thermal),
        BATTERY_TEMP(R.string.stop_battery_temp),
        MEMORY(R.string.stop_memory),
        BATTERY_LEVEL(R.string.stop_battery_level),
    }

    private class Battery(val percent: Int, val charging: Boolean, val tempC: Float)

    private fun battery(ctx: Context): Battery? {
        // A sticky broadcast: registering with no receiver reads the last value.
        val i = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) return null
        return Battery(
            percent = level * 100 / scale,
            charging = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0,
            tempC = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10f,
        )
    }

    private fun thermalStatus(ctx: Context): Int {
        if (Build.VERSION.SDK_INT < 29) return 0
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.currentThermalStatus
    }

    private fun memory(ctx: Context): ActivityManager.MemoryInfo {
        val info = ActivityManager.MemoryInfo()
        (ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(info)
        return info
    }

    /** The check before launch. */
    fun check(ctx: Context): Verdict {
        val blockers = ArrayList<String>()
        val warnings = ArrayList<String>()
        val mem = memory(ctx)

        var fatal = false
        if (!Build.SUPPORTED_64_BIT_ABIS.contains("arm64-v8a")) {
            blockers += ctx.getString(R.string.block_abi)
            fatal = true
        }
        if (!ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_VERSION, 0x401000)) {
            blockers += ctx.getString(R.string.block_vulkan)
            fatal = true
        }
        if (mem.totalMem < MIN_TOTAL_RAM) blockers += ctx.getString(R.string.block_ram_total)
        if (mem.lowMemory) {
            blockers += ctx.getString(R.string.block_low_memory)
        } else if (mem.availMem < BLOCK_FREE_RAM) {
            blockers += ctx.getString(R.string.block_ram_free)
        } else if (mem.availMem < WARN_FREE_RAM) {
            warnings += ctx.getString(R.string.warn_ram_free)
        }

        val thermal = thermalStatus(ctx)
        if (thermal >= THERMAL_SEVERE) {
            blockers += ctx.getString(R.string.block_thermal)
        } else if (thermal >= THERMAL_MODERATE) {
            warnings += ctx.getString(R.string.warn_thermal)
        }

        battery(ctx)?.let { b ->
            if (b.tempC >= BLOCK_BATTERY_TEMP) {
                blockers += ctx.getString(R.string.block_battery_temp)
            } else if (b.tempC >= WARN_BATTERY_TEMP) {
                warnings += ctx.getString(R.string.warn_battery_temp)
            }
            if (!b.charging && b.percent < 10) {
                blockers += ctx.getString(R.string.block_battery_level)
            } else if (!b.charging && b.percent < 20) {
                warnings += ctx.getString(R.string.warn_battery_level)
            }
        }
        if ((ctx.getSystemService(Context.POWER_SERVICE) as PowerManager).isPowerSaveMode) {
            warnings += ctx.getString(R.string.warn_power_save)
        }

        val memoryLine = ctx.getString(
            R.string.mem_line,
            android.text.format.Formatter.formatShortFileSize(ctx, mem.availMem),
            android.text.format.Formatter.formatShortFileSize(ctx, mem.totalMem),
        )
        return Verdict(blockers, warnings, memoryLine, fatal)
    }

    /** The check while the game runs: a reason to stop it now, or null. */
    fun runtimeStop(ctx: Context): StopReason? {
        if (thermalStatus(ctx) >= THERMAL_SEVERE) return StopReason.THERMAL
        val b = battery(ctx)
        if (b != null) {
            if (b.tempC >= STOP_BATTERY_TEMP) return StopReason.BATTERY_TEMP
            if (!b.charging && b.percent < 4) return StopReason.BATTERY_LEVEL
        }
        if (memory(ctx).availMem < STOP_FREE_RAM) return StopReason.MEMORY
        return null
    }
}
