package com.example.ui.screens.ccx

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ccx.CcxGatewayManager
import com.example.ccx.CcxLocalDaemon
import com.example.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CcxAiGatewayScreen(
    modifier: Modifier = Modifier
) {
    val channels by CcxGatewayManager.channels.collectAsStateWithLifecycle()
    val stats by CcxGatewayManager.stats.collectAsStateWithLifecycle()

    var showAddChannelDialog by remember { mutableStateOf(false) }
    var localDaemon by remember { mutableStateOf<CcxLocalDaemon?>(null) }
    var isDaemonActive by remember { mutableStateOf(false) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = DarkBackground,
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            color = Color(0xFF6366F1).copy(alpha = 0.15f),
                            shape = RoundedCornerShape(8.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF6366F1).copy(alpha = 0.5f))
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Hub,
                                    contentDescription = null,
                                    tint = Color(0xFF818CF8),
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "CCX AI GATEWAY",
                                    color = Color(0xFF818CF8),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        Text(
                            text = "Native Engine",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { CcxGatewayManager.probeAllChannels() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Probe Upstreams", tint = CyberCyan)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = DarkSurface,
                    titleContentColor = TextPrimary
                )
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(top = 16.dp, bottom = 32.dp)
        ) {
            // 1. Dual Mode Engine Status Card
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = DarkSurfaceElevated),
                    border = androidx.compose.foundation.BorderStroke(1.dp, DarkCardBorder),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "CORE CCX ENGINE (libccx.so)",
                                color = TextMuted,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                            Surface(
                                color = if (stats.isNativeJniLoaded) CyberEmerald.copy(alpha = 0.15f) else CyberAmber.copy(alpha = 0.15f),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text(
                                    text = if (stats.isNativeJniLoaded) "JNI LOADED" else "STANDBY FFI",
                                    color = if (stats.isNativeJniLoaded) CyberEmerald else CyberAmber,
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            text = "Autonomous AI proxy gateway running in dual mode: zero-latency in-process JNI memory translation & loopback HTTP daemon.",
                            color = TextSecondary,
                            fontSize = 12.sp
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        // Loopback Daemon Row
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(DarkBackground, RoundedCornerShape(10.dp))
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = "Local AI Daemon (Port 8095)",
                                    color = TextPrimary,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = if (isDaemonActive) "Listening: http://127.0.0.1:8095" else "Loopback daemon paused",
                                    color = if (isDaemonActive) CyberEmerald else TextMuted,
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                            }

                            Switch(
                                checked = isDaemonActive,
                                onCheckedChange = { active ->
                                    isDaemonActive = active
                                    if (active) {
                                        localDaemon = CcxLocalDaemon(8095).apply { start() }
                                    } else {
                                        localDaemon?.stop()
                                        localDaemon = null
                                    }
                                },
                                colors = SwitchDefaults.colors(checkedThumbColor = Color(0xFF6366F1))
                            )
                        }
                    }
                }
            }

            // 2. Interactive Protocol Translator Workbench
            item {
                CcxTranslatorBench()
            }

            // 3. Channels Section Header
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Configured Client Upstreams (${channels.size})",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )

                    Button(
                        onClick = { showAddChannelDialog = true },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6366F1)),
                        modifier = Modifier.testTag("add_channel_button")
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Add Server", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            // Channels List
            items(channels, key = { it.id }) { channel ->
                CcxChannelCard(
                    channel = channel,
                    onToggle = { CcxGatewayManager.toggleChannel(it) },
                    onProbe = { CcxGatewayManager.probeChannel(it) },
                    onDelete = { CcxGatewayManager.removeChannel(it) }
                )
            }
        }
    }

    if (showAddChannelDialog) {
        AddChannelDialog(
            onDismiss = { showAddChannelDialog = false },
            onConfirm = { name, proto, url, key, model, prio ->
                CcxGatewayManager.addChannel(name, proto, url, key, model, prio)
                showAddChannelDialog = false
            }
        )
    }
}
