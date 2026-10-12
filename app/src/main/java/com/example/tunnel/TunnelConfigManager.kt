package com.example.tunnel

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

data class TunnelHostProfile(
    val id: String = System.currentTimeMillis().toString(),
    val name: String,
    val targetUrl: String,
    val customHeadersJson: String = "{\"X-Forwarded-Source\": \"Ngrok-Android\", \"Content-Type\": \"application/json\"}",
    val testPayload: String = "{\"action\": \"test_ping\"}",
    val isDefault: Boolean = false
)

object TunnelConfigManager {
    private const val PREFS_NAME = "tunnel_host_config_prefs"
    private const val KEY_PROFILES = "key_tunnel_profiles"
    private const val KEY_ACTIVE_ID = "key_active_profile_id"

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
                list.add(
                    TunnelHostProfile(
                        id = obj.optString("id"),
                        name = obj.optString("name"),
                        targetUrl = obj.optString("targetUrl"),
                        customHeadersJson = obj.optString("customHeadersJson", "{}"),
                        testPayload = obj.optString("testPayload", ""),
                        isDefault = obj.optBoolean("isDefault", false)
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
                put("targetUrl", p.targetUrl)
                put("customHeadersJson", p.customHeadersJson)
                put("testPayload", p.testPayload)
                put("isDefault", p.isDefault)
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

    private fun defaultProfiles(): List<TunnelHostProfile> {
        return listOf(
            TunnelHostProfile(
                id = "default-local",
                name = "Local Companion Server (Port 8085)",
                targetUrl = "http://127.0.0.1:8085",
                customHeadersJson = "{\"X-Forwarded-Source\": \"Ngrok-Android\", \"Content-Type\": \"application/json\"}",
                testPayload = "{\"action\": \"test_ping\"}",
                isDefault = true
            )
        )
    }
}
