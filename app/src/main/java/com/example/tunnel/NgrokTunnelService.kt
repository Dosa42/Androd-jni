package com.example.tunnel

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class NgrokTunnelService : Service() {

  private val tag = "NgrokTunnelService"
  private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
  private var stateCollectorJob: Job? = null
  private var wakeLock: PowerManager.WakeLock? = null

  override fun onCreate() {
    super.onCreate()
    Log.d(tag, "NgrokTunnelService created")
    createNotificationChannel()
    acquireWakeLock()
    observeTunnelState()
  }

  private fun createNotificationChannel() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      val channel = NotificationChannel(
        CHANNEL_ID,
        "Ngrok Native Tunnel Service",
        NotificationManager.IMPORTANCE_LOW
      ).apply {
        description = "Monitors active public Ngrok tunnel ingress"
      }
      val manager = getSystemService(NotificationManager::class.java)
      manager.createNotificationChannel(channel)
    }
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    val action = intent?.action
    Log.d(tag, "onStartCommand action: $action")

    when (action) {
      ACTION_START_TUNNEL -> {
        val token = intent.getStringExtra(EXTRA_AUTH_TOKEN) ?: NgrokConfig.getAuthToken(this)
        val port = intent.getIntExtra(EXTRA_PORT, NgrokConfig.getLocalPort(this))
        startForeground(NOTIFICATION_ID, buildNotification("Initializing Ngrok Native Tunnel..."))
        NgrokTunnelManager.executeStartTunnel(token, port)
      }

      ACTION_STOP_TUNNEL -> {
        NgrokTunnelManager.stopTunnel(this)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
      }
    }

    return START_NOT_STICKY
  }

  private fun observeTunnelState() {
    stateCollectorJob?.cancel()
    stateCollectorJob = serviceScope.launch {
      NgrokTunnelManager.state.collectLatest { state ->
        when (state) {
          is TunnelState.Connected -> {
            updateNotification("Tunnel Active: ${state.publicUrl}", "Port: ${state.localPort} • Requests: ${state.totalRequests}")
          }
          is TunnelState.Connecting -> {
            updateNotification("Connecting Ngrok...", state.stage)
          }
          is TunnelState.Error -> {
            updateNotification("Ngrok Tunnel Error", state.errorMessage)
          }
          is TunnelState.Stopping -> {
            updateNotification("Stopping Tunnel...", "Disconnecting edge sessions")
          }
          is TunnelState.Disconnected -> {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
          }
        }
      }
    }
  }

  private fun acquireWakeLock() {
    try {
      val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
      wakeLock = powerManager.newWakeLock(
        PowerManager.PARTIAL_WAKE_LOCK,
        "NgrokAgent:TunnelWakeLock"
      ).apply {
        acquire(60 * 60 * 1000L)
      }
    } catch (e: Exception) {
      Log.w(tag, "Could not acquire wakelock: ${e.message}")
    }
  }

  private fun releaseWakeLock() {
    try {
      if (wakeLock?.isHeld == true) {
        wakeLock?.release()
      }
      wakeLock = null
    } catch (_: Exception) {}
  }

  private fun buildNotification(title: String, content: String = "Ngrok Native Tunnel Service"): Notification {
    val openAppIntent = Intent(this, MainActivity::class.java).apply {
      flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
    }
    val openPendingIntent = PendingIntent.getActivity(
      this, 0, openAppIntent,
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    val stopIntent = Intent(this, NgrokTunnelService::class.java).apply {
      action = ACTION_STOP_TUNNEL
    }
    val stopPendingIntent = PendingIntent.getService(
      this, 1, stopIntent,
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    return NotificationCompat.Builder(this, CHANNEL_ID)
      .setContentTitle(title)
      .setContentText(content)
      .setSmallIcon(android.R.drawable.stat_notify_sync)
      .setContentIntent(openPendingIntent)
      .setOngoing(true)
      .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop Tunnel", stopPendingIntent)
      .setPriority(NotificationCompat.PRIORITY_LOW)
      .build()
  }

  private fun updateNotification(title: String, content: String) {
    val notification = buildNotification(title, content)
    val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    notificationManager.notify(NOTIFICATION_ID, notification)
  }

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onDestroy() {
    super.onDestroy()
    Log.d(tag, "NgrokTunnelService destroyed")
    NgrokTunnelManager.releaseResources()
    releaseWakeLock()
    serviceScope.cancel()
  }

  companion object {
    const val CHANNEL_ID = "ngrok_native_tunnel_channel"
    const val NOTIFICATION_ID = 2002
    const val ACTION_START_TUNNEL = "com.example.tunnel.action.START"
    const val ACTION_STOP_TUNNEL = "com.example.tunnel.action.STOP"
    const val EXTRA_AUTH_TOKEN = "extra_auth_token"
    const val EXTRA_PORT = "extra_port"
  }
}
