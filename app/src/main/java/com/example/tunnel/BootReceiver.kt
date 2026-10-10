package com.example.tunnel

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class BootReceiver : BroadcastReceiver() {

  override fun onReceive(context: Context, intent: Intent?) {
    val action = intent?.action
    Log.d("BootReceiver", "Received action: $action")

    if (action == Intent.ACTION_BOOT_COMPLETED ||
        action == "android.intent.action.QUICKBOOT_POWERON" ||
        action == "com.htc.intent.action.QUICKBOOT_POWERON") {

      val autoStart = NgrokConfig.isAutoStartOnBoot(context)
      val token = NgrokConfig.getAuthToken(context)

      if (autoStart && token.isNotBlank()) {
        Log.i("BootReceiver", "Auto-starting Ngrok Tunnel Service after boot...")
        NgrokTunnelManager.startTunnel(context)
      }
    }
  }
}
