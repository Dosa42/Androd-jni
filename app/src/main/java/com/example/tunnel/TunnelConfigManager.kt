package com.example.tunnel

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

data class HeaderEntry(
    val id: String = System.currentTimeMillis().toString() + (0..99).random(),
    val key: String,
    val value: String
)

data class ClientBackendConfig(
    val isEnabled: Boolean = false,
    val restEndpoint: String = "",
    val apiKey: String = "",
    val cloudHeaderKey: String = "X-Cloud-Provider",
    val cloudHeaderValue: String = "Custom-REST"
)

data class SystemParameters(
    val connectTimeoutSeconds: Int = 15,
    val readTimeoutSeconds: Int = 30,
    val wakelockDurationMinutes: Int = 60,
    val pingIntervalSeconds: Int = 20,
    val trafficLogMaxEntries: Int = 100
)

data class TunnelHostProfile(
    val id: String = System.currentTimeMillis().toString(),
    val name: String,
    val targetUrls: List<String>,
    val pathRewritePrefix: String = "",
    val customHeaders: List<HeaderEntry> = listOf(
        HeaderEntry(key = "X-Forwarded-Source", value = "Ngrok-Android"),
        HeaderEntry(key = "Content-Type", value = "application/json")
    ),
    val testPayload: String = "{\"action\": \"ping\"}",
    val clientBackend: ClientBackendConfig = ClientBackendConfig(),
    val isDefault: Boolean = false,
    val totalRequestsHandled: Long = 0L,
    val averageLatencyMs: Long = 0L
)

object TunnelConfigManager {
    private const val PREFS_NAME = "tunnel_host_config_prefs_v3"
    private const val KEY_PROFILES = "key_tunnel_profiles_v3"
    private const val KEY_ACTIVE_ID = "key_active_profile_id_v3"
    private const val KEY_SYS_PARAMS = "key_system_parameters"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun getProfiles(context: Context): List<TunnelHostProfile> {
        val jsonStr = getPrefs(context).getString(KEY_PROFILES, null) ?: return defaultProfiles()
        return try {
            val arr = JSONArray(jsonStr)
            val list = mutableListOf<TunnelHostProfile>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val urlsArr = obj.optJSONArray("targetUrls") ?: JSONArray().apply { put(obj.optString("targetUrl", "http://127.0.0.1:8085")) }
                val urls = mutableListOf<String>()
                for (j in 0 until urlsArr.length()) {
                    urls.add(urlsArr.getString(j))
                }

                val headersArr = obj.optJSONArray("customHeaders") ?: JSONArray()
                val headers = mutableListOf<HeaderEntry>()
                for (j in 0 until headersArr.length()) {
                    val hObj = headersArr.getJSONObject(j)
                    headers.add(
                        HeaderEntry(
                            id = hObj.optString("id", j.toString()),
                            key = hObj.optString("key", ""),
                            value = hObj.optString("value", "")
                        )
                    )
                }

                val cbObj = obj.optJSONObject("clientBackend")
                val clientBackend = if (cbObj != null) {
                    ClientBackendConfig(
                        isEnabled = cbObj.optBoolean("isEnabled", false),
                        restEndpoint = cbObj.optString("restEndpoint", ""),
                        apiKey = cbObj.optString("apiKey", ""),
                        cloudHeaderKey = cbObj.optString("cloudHeaderKey", "X-Cloud-Provider"),
                        cloudHeaderValue = cbObj.optString("cloudHeaderValue", "Custom-REST")
                    )
                } else {
                    ClientBackendConfig()
                }

                list.add(
                    TunnelHostProfile(
                        id = obj.optString("id"),
                        name = obj.optString("name"),
                        targetUrls = if (urls.isEmpty()) listOf("http://127.0.0.1:8085") else urls,
                        pathRewritePrefix = obj.optString("pathRewritePrefix", ""),
                        customHeaders = headers,
                        testPayload = obj.optString("testPayload", ""),
                        clientBackend = clientBackend,
                        isDefault = obj.optBoolean("isDefault", false),
                        totalRequestsHandled = obj.optLong("totalRequestsHandled", 0L),
                        averageLatencyMs = obj.optLong("averageLatencyMs", 0L)
                    )
                )
            }
            if (list.isEmpty()) defaultProfiles() else list
        } catch (e: Exception) {
            defaultProfiles()
        }
    }

    fun saveProfiles(context: Context, profiles: List<TunnelHostProfile>) {
        val arr = JSONArray()
        profiles.forEach { p ->
            val obj = JSONObject().apply {
                put("id", p.id)
                put("name", p.name)
                val urlsArr = JSONArray()
                p.targetUrls.forEach { urlsArr.put(it) }
                put("targetUrls", urlsArr)
                put("pathRewritePrefix", p.pathRewritePrefix)
                val headersArr = JSONArray()
                p.customHeaders.forEach { h ->
                    headersArr.put(
                        JSONObject().apply {
                            put("id", h.id)
                            put("key", h.key)
                            put("value", h.value)
                        }
                    )
                }
                put("customHeaders", headersArr)
                put("testPayload", p.testPayload)
                put(
                    "clientBackend",
                    JSONObject().apply {
                        put("isEnabled", p.clientBackend.isEnabled)
                        put("restEndpoint", p.clientBackend.restEndpoint)
                        put("apiKey", p.clientBackend.apiKey)
                        put("cloudHeaderKey", p.clientBackend.cloudHeaderKey)
                        put("cloudHeaderValue", p.clientBackend.cloudHeaderValue)
                    }
                )
                put("isDefault", p.isDefault)
                put("totalRequestsHandled", p.totalRequestsHandled)
                put("averageLatencyMs", p.averageLatencyMs)
            }
            arr.put(obj)
        }
        getPrefs(context).edit().putString(KEY_PROFILES, arr.toString()).apply()
    }

    fun getActiveProfile(context: Context): TunnelHostProfile {
        val profiles = getProfiles(context)
        val activeId = getPrefs(context).getString(KEY_ACTIVE_ID, null)
        return profiles.find { it.id == activeId } ?: profiles.find { it.isDefault } ?: profiles.first()
    }

    fun setActiveProfileId(context: Context, id: String) {
        getPrefs(context).edit().putString(KEY_ACTIVE_ID, id).apply()
    }

    fun addOrUpdateProfile(context: Context, profile: TunnelHostProfile) {
        val profiles = getProfiles(context).toMutableList()
        val index = profiles.indexOfFirst { it.id == profile.id }
        if (index >= 0) {
            profiles[index] = profile
        } else {
            profiles.add(profile)
        }
        if (profile.isDefault) {
            for (i in profiles.indices) {
                if (profiles[i].id != profile.id) {
                    profiles[i] = profiles[i].copy(isDefault = false)
                }
            }
        }
        saveProfiles(context, profiles)
    }

    fun deleteProfile(context: Context, id: String) {
        val profiles = getProfiles(context).filter { it.id != id }
        saveProfiles(context, profiles.ifEmpty { defaultProfiles() })
    }

    fun recordMetrics(context: Context, profileId: String, latencyMs: Long) {
        val profiles = getProfiles(context).toMutableList()
        val index = profiles.indexOfFirst { it.id == profileId }
        if (index >= 0) {
            val p = profiles[index]
            val newTotal = p.totalRequestsHandled + 1
            val newAvg = if (p.totalRequestsHandled == 0L) latencyMs else ((p.averageLatencyMs * p.totalRequestsHandled) + latencyMs) / newTotal
            profiles[index] = p.copy(totalRequestsHandled = newTotal, averageLatencyMs = newAvg)
            saveProfiles(context, profiles)
        }
    }

    fun getSystemParameters(context: Context): SystemParameters {
        val jsonStr = getPrefs(context).getString(KEY_SYS_PARAMS, null) ?: return SystemParameters()
        return try {
            val obj = JSONObject(jsonStr)
            SystemParameters(
                connectTimeoutSeconds = obj.optInt("connectTimeoutSeconds", 15),
                readTimeoutSeconds = obj.optInt("readTimeoutSeconds", 30),
                wakelockDurationMinutes = obj.optInt("wakelockDurationMinutes", 60),
                pingIntervalSeconds = obj.optInt("pingIntervalSeconds", 20),
                trafficLogMaxEntries = obj.optInt("trafficLogMaxEntries", 100)
            )
        } catch (e: Exception) {
            SystemParameters()
        }
    }

    fun saveSystemParameters(context: Context, params: SystemParameters) {
        val obj = JSONObject().apply {
            put("connectTimeoutSeconds", params.connectTimeoutSeconds)
            put("readTimeoutSeconds", params.readTimeoutSeconds)
            put("wakelockDurationMinutes", params.wakelockDurationMinutes)
            put("pingIntervalSeconds", params.pingIntervalSeconds)
            put("trafficLogMaxEntries", params.trafficLogMaxEntries)
        }
        getPrefs(context).edit().putString(KEY_SYS_PARAMS, obj.toString()).apply()
    }

    private fun defaultProfiles(): List<TunnelHostProfile> {
        return listOf(
            TunnelHostProfile(
                id = "default-local",
                name = "Local Cluster with Custom REST Client Backend",
                targetUrls = listOf("http://127.0.0.1:8085"),
                pathRewritePrefix = "",
                customHeaders = listOf(
                    HeaderEntry(key = "X-Forwarded-Source", value = "Ngrok-Android")
                ),
                testPayload = "{\"action\": \"ping\"}",
                clientBackend = ClientBackendConfig(
                    isEnabled = true,
                    restEndpoint = "https://api.customclientcloud.io/v1/webhook",
                    apiKey = "sec_live_998877665544",
                    cloudHeaderKey = "X-Cloud-Provider",
                    cloudHeaderValue = "Custom-REST"
                ),
                isDefault = true
            )
        )
    }
}
