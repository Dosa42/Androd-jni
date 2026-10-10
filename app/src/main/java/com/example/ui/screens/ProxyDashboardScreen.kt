package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Security
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
import com.example.proxy.ProtocolType
import com.example.proxy.ProxyViewModel
import com.example.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProxyDashboardScreen(
    viewModel: ProxyViewModel,
    modifier: Modifier = Modifier
) {
    val stats by viewModel.stats.collectAsStateWithLifecycle()
    val connectionCards by viewModel.connectionCards.collectAsStateWithLifecycle()
    val logs by viewModel.logs.collectAsStateWithLifecycle()
    val selectedFilter by viewModel.selectedProtocolFilter.collectAsStateWithLifecycle()

    var showNewStreamDialog by remember { mutableStateOf(false) }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .testTag("proxy_dashboard_screen"),
        containerColor = DarkBackground,
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Hub,
                            contentDescription = null,
                            tint = CyberCyan,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "InProcess Proxy",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary
                            )
                            Text(
                                text = "Direct In-Process JNI & FFI Engine",
                                style = MaterialTheme.typography.labelSmall,
                                color = CyberCyan,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                },
                actions = {
                    IconButton(
                        onClick = { showNewStreamDialog = true },
                        modifier = Modifier.testTag("add_stream_appbar_button")
                    ) {
                        Icon(
                            Icons.Default.Add,
                            contentDescription = "Add New Channel",
                            tint = CyberCyan
                        )
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
            // 1. Service Manager Card
            item {
                ServiceManagerCard(
                    stats = stats,
                    onToggleService = { enable ->
                        if (enable) viewModel.startAndBindService() else viewModel.stopService()
                    },
                    onRestartStreams = { viewModel.restartDefaultStreams() }
                )
            }

            // 2. Section Header: Active Connection Status Cards
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Active Connection Channels (${connectionCards.size})",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )

                    TextButton(
                        onClick = { showNewStreamDialog = true },
                        modifier = Modifier.testTag("add_stream_text_button")
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, tint = CyberCyan, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Add Stream", fontSize = 12.sp, color = CyberCyan)
                    }
                }
            }

            // List of Connection Cards
            if (connectionCards.isEmpty()) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = DarkSurfaceElevated)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(24.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "No active streams. Tap 'Re-Init Default Streams' or '+' to start.",
                                color = TextMuted,
                                fontSize = 13.sp
                            )
                        }
                    }
                }
            } else {
                items(connectionCards.values.toList(), key = { it.streamId }) { cardState ->
                    ConnectionStatusCard(
                        cardState = cardState,
                        onCloseStream = { viewModel.closeStream(it) },
                        onSendQuickPing = {
                            if (cardState.protocol == ProtocolType.SOCKET_IO) {
                                viewModel.emitSocketIoEvent(it, "ping", "{\"t\":${System.currentTimeMillis()}}")
                            } else {
                                viewModel.sendWebSocketText(it, "ping_manual")
                            }
                        }
                    )
                }
            }

            // 3. Interactive Protocol Workbench
            item {
                ProtocolTestBench(
                    connectionCards = connectionCards,
                    onSendWsText = { streamId, text -> viewModel.sendWebSocketText(streamId, text) },
                    onSendWsBinary = { streamId, hex -> viewModel.sendWebSocketBinary(streamId, hex) },
                    onEmitSocketIo = { streamId, ev, payload -> viewModel.emitSocketIoEvent(streamId, ev, payload) },
                    onOpenNewStream = { protocol, path -> viewModel.openNewStream(protocol, path) }
                )
            }

            // 4. Live JNI Frame Inspector
            item {
                FrameInspectorView(
                    logs = logs,
                    selectedFilter = selectedFilter,
                    onSelectFilter = { viewModel.setProtocolFilter(it) },
                    onClearLogs = { viewModel.clearLogs() }
                )
            }
        }
    }

    if (showNewStreamDialog) {
        NewStreamDialog(
            onDismiss = { showNewStreamDialog = false },
            onConfirm = { protocol, path ->
                viewModel.openNewStream(protocol, path)
                showNewStreamDialog = false
            }
        )
    }
}

@Composable
private fun NewStreamDialog(
    onDismiss: () -> Unit,
    onConfirm: (ProtocolType, String) -> Unit
) {
    var selectedProtocol by remember { mutableStateOf(ProtocolType.WEBSOCKET) }
    var endpointPath by remember { mutableStateOf("/ws/echo") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = DarkSurfaceElevated,
        title = {
            Text("Open In-Process Stream", color = TextPrimary, fontWeight = FontWeight.Bold)
        },
        text = {
            Column {
                Text("Select Protocol:", color = TextSecondary, fontSize = 12.sp)
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    ProtocolType.entries.forEach { proto ->
                        FilterChip(
                            selected = selectedProtocol == proto,
                            onClick = {
                                selectedProtocol = proto
                                endpointPath = when (proto) {
                                    ProtocolType.HTTP_SSE -> "/api/stream/telemetry"
                                    ProtocolType.WEBSOCKET -> "/ws/echo"
                                    ProtocolType.SOCKET_IO -> "/socket.io/?EIO=4"
                                }
                            },
                            label = { Text(proto.name, fontSize = 10.sp) }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                OutlinedTextField(
                    value = endpointPath,
                    onValueChange = { endpointPath = it },
                    label = { Text("Endpoint Path") },
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = CyberCyan,
                        unfocusedBorderColor = DarkCardBorder,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary
                    )
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(selectedProtocol, endpointPath) },
                colors = ButtonDefaults.buttonColors(containerColor = CyberCyan)
            ) {
                Text("Open Stream", color = DarkBackground, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = TextMuted)
            }
        }
    )
}
