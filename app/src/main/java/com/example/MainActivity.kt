package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.proxy.ProxyViewModel
import com.example.ui.screens.ProxyDashboardScreen
import com.example.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        try {
            com.ngrok.Runtime.load()
            val port = com.example.tunnel.NgrokConfig.getLocalPort(this)
            com.example.tunnel.NgrokTunnelManager.ensureLocalServerRunning(port, this)
        } catch (t: Throwable) {
            android.util.Log.w("MainActivity", "Ngrok native init note: ${t.message}")
        }

        setContent {
            MyApplicationTheme {
                val viewModel: ProxyViewModel = viewModel()
                ProxyDashboardScreen(viewModel = viewModel)
            }
        }
    }
}
