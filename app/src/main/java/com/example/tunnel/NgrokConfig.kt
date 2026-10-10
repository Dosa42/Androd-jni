package com.example.tunnel

import android.content.Context
import android.content.SharedPreferences

object NgrokConfig {
  const val DEFAULT_LOCAL_PORT: Int = 8085
  private const val PREFS_NAME = "ngrok_prefs"
  private const val KEY_AUTH_TOKEN = "key_auth_token"
  private const val KEY_PORT = "key_local_port"
  private const val KEY_AUTO_START_ON_BOOT = "key_auto_start_on_boot"

  private fun getPrefs(context: Context): SharedPreferences {
    return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
  }

  fun getAuthToken(context: Context): String {
    return getPrefs(context).getString(KEY_AUTH_TOKEN, "") ?: ""
  }

  fun setAuthToken(context: Context, token: String) {
    getPrefs(context).edit().putString(KEY_AUTH_TOKEN, token.trim()).apply()
  }

  fun getLocalPort(context: Context): Int {
    return getPrefs(context).getInt(KEY_PORT, DEFAULT_LOCAL_PORT)
  }

  fun setLocalPort(context: Context, port: Int) {
    getPrefs(context).edit().putInt(KEY_PORT, port).apply()
  }

  fun isAutoStartOnBoot(context: Context): Boolean {
    return getPrefs(context).getBoolean(KEY_AUTO_START_ON_BOOT, false)
  }

  fun setAutoStartOnBoot(context: Context, autoStart: Boolean) {
    getPrefs(context).edit().putBoolean(KEY_AUTO_START_ON_BOOT, autoStart).apply()
  }

  fun resetToDefaults(context: Context) {
    getPrefs(context).edit().clear().apply()
  }
}
