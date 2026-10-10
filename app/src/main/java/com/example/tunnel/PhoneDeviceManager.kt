package com.example.tunnel

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.net.NetworkInterface
import java.util.Collections

data class PhoneTelemetry(
    val deviceModel: String,
    val androidVersion: String,
    val sdkInt: Int,
    val batteryPercent: Int,
    val isCharging: Boolean,
    val chargingType: String,
    val networkType: String,
    val localIpAddresses: List<String>,
    val storageFreeGb: Double,
    val storageTotalGb: Double,
    val ramFreeMb: Long,
    val ramTotalMb: Long,
    val uptimeFormatted: String
)

object PhoneDeviceManager {

    fun getTelemetry(context: Context): PhoneTelemetry {
        val batteryStatus: Intent? = IntentFilter(Intent.ACTION_BATTERY_CHANGED).let { filter ->
            context.registerReceiver(null, filter)
        }

        val level: Int = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale: Int = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val batteryPct: Int = if (level >= 0 && scale > 0) ((level / scale.toFloat()) * 100).toInt() else 100

        val chargePlug = batteryStatus?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) ?: -1
        val isCharging = chargePlug == BatteryManager.BATTERY_PLUGGED_USB ||
                chargePlug == BatteryManager.BATTERY_PLUGGED_AC ||
                chargePlug == BatteryManager.BATTERY_PLUGGED_WIRELESS
        val chargingType = when (chargePlug) {
            BatteryManager.BATTERY_PLUGGED_USB -> "USB"
            BatteryManager.BATTERY_PLUGGED_AC -> "AC Power"
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> "Wireless"
            else -> if (isCharging) "Charging" else "Battery"
        }

        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val activeNetwork = cm?.activeNetwork
        val caps = cm?.getNetworkCapabilities(activeNetwork)
        val networkType = when {
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> "Wi-Fi"
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> "Cellular 4G/5G"
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true -> "Ethernet"
            else -> "Active Link"
        }

        val ips = mutableListOf<String>()
        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (intf in interfaces) {
                val addrs = Collections.list(intf.inetAddresses)
                for (addr in addrs) {
                    if (!addr.isLoopbackAddress && addr.hostAddress?.contains(":") == false) {
                        addr.hostAddress?.let { ips.add(it) }
                    }
                }
            }
        } catch (_: Exception) {}
        if (ips.isEmpty()) ips.add("127.0.0.1")

        val stat = StatFs(Environment.getDataDirectory().path)
        val freeBytes = stat.availableBlocksLong * stat.blockSizeLong
        val totalBytes = stat.blockCountLong * stat.blockSizeLong
        val freeGb = (freeBytes / (1024.0 * 1024.0 * 1024.0))
        val totalGb = (totalBytes / (1024.0 * 1024.0 * 1024.0))

        val runtime = java.lang.Runtime.getRuntime()
        val ramFreeMb = runtime.freeMemory() / (1024 * 1024)
        val ramTotalMb = runtime.totalMemory() / (1024 * 1024)

        val uptimeMs = SystemClock.elapsedRealtime()
        val hours = uptimeMs / (1000 * 60 * 60)
        val minutes = (uptimeMs / (1000 * 60)) % 60
        val uptimeStr = "${hours}h ${minutes}m"

        return PhoneTelemetry(
            deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}",
            androidVersion = Build.VERSION.RELEASE,
            sdkInt = Build.VERSION.SDK_INT,
            batteryPercent = batteryPct,
            isCharging = isCharging,
            chargingType = chargingType,
            networkType = networkType,
            localIpAddresses = ips,
            storageFreeGb = String.format("%.1f", freeGb).toDoubleOrNull() ?: freeGb,
            storageTotalGb = String.format("%.1f", totalGb).toDoubleOrNull() ?: totalGb,
            ramFreeMb = ramFreeMb,
            ramTotalMb = ramTotalMb,
            uptimeFormatted = uptimeStr
        )
    }

    fun getTelemetryJson(context: Context): JSONObject {
        val t = getTelemetry(context)
        return JSONObject().apply {
            put("device_model", t.deviceModel)
            put("android_version", t.androidVersion)
            put("sdk_int", t.sdkInt)
            put("battery_percent", t.batteryPercent)
            put("is_charging", t.isCharging)
            put("charging_type", t.chargingType)
            put("network_type", t.networkType)
            put("ip_addresses", JSONArray(t.localIpAddresses))
            put("storage_free_gb", t.storageFreeGb)
            put("storage_total_gb", t.storageTotalGb)
            put("ram_free_mb", t.ramFreeMb)
            put("ram_total_mb", t.ramTotalMb)
            put("uptime", t.uptimeFormatted)
        }
    }
}
