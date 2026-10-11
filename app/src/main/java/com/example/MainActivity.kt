package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.proxy.ProxyViewModel
import com.example.ui.screens.ProxyDashboardScreen
import com.example.ui.screens.ccx.CcxAiGatewayScreen
import com.example.ui.theme.DarkSurface
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
                var selectedTab by remember { mutableIntStateOf(0) }

                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    bottomBar = {
                        NavigationBar(
                            containerColor = DarkSurface
                        ) {
                            NavigationBarItem(
                                selected = selectedTab == 0,
                                onClick = { selectedTab = 0 },
                                icon = { Icon(Icons.Default.Dns, contentDescription = "Network Proxy") },
                                label = { Text("Network Proxy") },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = Color(0xFF00F0FF),
                                    selectedTextColor = Color(0xFF00F0FF),
                                    indicatorColor = Color(0xFF00F0FF).copy(alpha = 0.15f)
                                )
                            )
                            NavigationBarItem(
                                selected = selectedTab == 1,
                                onClick = { selectedTab = 1 },
                                icon = { Icon(Icons.Default.Hub, contentDescription = "CCX AI Gateway") },
                                label = { Text("CCX AI Gateway") },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = Color(0xFF818CF8),
                                    selectedTextColor = Color(0xFF818CF8),
                                    indicatorColor = Color(0xFF6366F1).copy(alpha = 0.2f)
                                )
                            )
                        }
                    }
                ) { innerPadding ->
                    when (selectedTab) {
                        0 -> ProxyDashboardScreen(viewModel = viewModel, modifier = Modifier.padding(innerPadding))
                        1 -> CcxAiGatewayScreen(modifier = Modifier.padding(innerPadding))
                    }
                }
            }
        }
    }
}
