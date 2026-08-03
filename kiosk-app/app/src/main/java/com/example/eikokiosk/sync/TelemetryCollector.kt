package com.example.eikokiosk.sync

import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.RemoteException
import android.util.Log
import java.util.*

object TelemetryCollector {
    private const val TAG = "TelemetryCollector"

    data class TelemetryData(
        val wifiBytes: Long,
        val mobileBytes: Long,
        val appUsage: Map<String, Long> // PackageName -> Foreground time in ms
    )

    fun collectDailyStats(context: Context): TelemetryData {
        val calendar = Calendar.getInstance()
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        val startTime = calendar.timeInMillis
        val endTime = System.currentTimeMillis()

        val wifiBytes = getNetworkUsage(context, NetworkCapabilities.TRANSPORT_WIFI, startTime, endTime)
        val mobileBytes = getNetworkUsage(context, NetworkCapabilities.TRANSPORT_CELLULAR, startTime, endTime)
        val appUsage = getAppUsage(context, startTime, endTime)

        return TelemetryData(wifiBytes, mobileBytes, appUsage)
    }

    private fun getNetworkUsage(context: Context, networkType: Int, startTime: Long, endTime: Long): Long {
        val networkStatsManager = context.getSystemService(Context.NETWORK_STATS_SERVICE) as NetworkStatsManager
        var totalBytes = 0L
        try {
            val bucket = networkStatsManager.querySummaryForDevice(
                if (networkType == NetworkCapabilities.TRANSPORT_WIFI) ConnectivityManager.TYPE_WIFI else ConnectivityManager.TYPE_MOBILE,
                null,
                startTime,
                endTime
            )
            totalBytes = bucket.rxBytes + bucket.txBytes
        } catch (e: RemoteException) {
            Log.e(TAG, "RemoteException querying network stats", e)
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException querying network stats (Permission denied?)", e)
        }
        return totalBytes
    }

    private fun getAppUsage(context: Context, startTime: Long, endTime: Long): Map<String, Long> {
        val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val usageMap = mutableMapOf<String, Long>()
        try {
            val stats = usageStatsManager.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, startTime, endTime)
            for (stat in stats) {
                if (stat.totalTimeInForeground > 0) {
                    usageMap[stat.packageName] = stat.totalTimeInForeground
                }
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException querying usage stats (Permission denied?)", e)
        }
        return usageMap
    }
}
