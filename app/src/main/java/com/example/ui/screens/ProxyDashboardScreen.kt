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
import com.example.proxy.RoutingMode
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

            // 2. Ngrok Native Tunnel Ingress Telemetry Card
            item {
                NgrokTunnelCard()
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
            onConfirm = { protocol, path, routingMode, upstreamUrl ->
                viewModel.openNewStream(protocol, path, routingMode, upstreamUrl)
                showNewStreamDialog = false
            }
        )
    }
}

@Composable
private fun NewStreamDialog(
    onDismiss: () -> Unit,
    onConfirm: (ProtocolType, String, RoutingMode, String?) -> Unit
) {
    var selectedProtocol by remember { mutableStateOf(ProtocolType.WEBSOCKET) }
    var routingMode by remember { mutableStateOf(RoutingMode.UPSTREAM_REMOTE) }
    var endpointPath by remember { mutableStateOf("/upstream/echo") }
    var upstreamUrl by remember { mutableStateOf("wss://echo.websocket.events") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = DarkSurfaceElevated,
        title = {
            Text("Open Channel", color = TextPrimary, fontWeight = FontWeight.Bold)
        },
        text = {
            Column {
                Text("Routing Destination:", color = TextSecondary, fontSize = 12.sp)
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    FilterChip(
                        selected = routingMode == RoutingMode.UPSTREAM_REMOTE,
                        onClick = {
                            routingMode = RoutingMode.UPSTREAM_REMOTE
                            upstreamUrl = when (selectedProtocol) {
                                ProtocolType.WEBSOCKET -> "wss://echo.websocket.events"
                                ProtocolType.HTTP_SSE -> "https://sse.dev/test"
                                ProtocolType.SOCKET_IO -> "wss://echo.websocket.events"
                            }
                        },
                        label = { Text("Remote Upstream", fontSize = 10.sp, color = CyberRose) }
                    )
                    FilterChip(
                        selected = routingMode == RoutingMode.IN_PROCESS_LOCAL,
                        onClick = { routingMode = RoutingMode.IN_PROCESS_LOCAL },
                        label = { Text("In-Process FFI", fontSize = 10.sp, color = CyberCyan) }
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                Text("Select Protocol:", color = TextSecondary, fontSize = 12.sp)
                Spacer(modifier = Modifier.height(6.dp))
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
                                    ProtocolType.HTTP_SSE -> if (routingMode == RoutingMode.UPSTREAM_REMOTE) "/upstream/sse" else "/api/stream/telemetry"
                                    ProtocolType.WEBSOCKET -> if (routingMode == RoutingMode.UPSTREAM_REMOTE) "/upstream/echo" else "/ws/echo"
                                    ProtocolType.SOCKET_IO -> if (routingMode == RoutingMode.UPSTREAM_REMOTE) "/upstream/socket.io" else "/socket.io/?EIO=4"
                                }
                                if (routingMode == RoutingMode.UPSTREAM_REMOTE) {
                                    upstreamUrl = when (proto) {
                                        ProtocolType.WEBSOCKET -> "wss://echo.websocket.events"
                                        ProtocolType.HTTP_SSE -> "https://sse.dev/test"
                                        ProtocolType.SOCKET_IO -> "wss://echo.websocket.events"
                                    }
                                }
                            },
                            label = { Text(proto.name, fontSize = 10.sp) }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                if (routingMode == RoutingMode.UPSTREAM_REMOTE) {
                    OutlinedTextField(
                        value = upstreamUrl,
                        onValueChange = { upstreamUrl = it },
                        label = { Text("External Remote Server URL") },
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = CyberRose,
                            unfocusedBorderColor = DarkCardBorder,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary
                        )
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }

                OutlinedTextField(
                    value = endpointPath,
                    onValueChange = { endpointPath = it },
                    label = { Text("Internal Proxy Path / Identifier") },
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
                onClick = {
                    onConfirm(
                        selectedProtocol,
                        endpointPath,
                        routingMode,
                        if (routingMode == RoutingMode.UPSTREAM_REMOTE) upstreamUrl else null
                    )
                },
                colors = ButtonDefaults.buttonColors(containerColor = if (routingMode == RoutingMode.UPSTREAM_REMOTE) CyberRose else CyberCyan)
            ) {
                Text(
                    text = if (routingMode == RoutingMode.UPSTREAM_REMOTE) "Forward to Upstream" else "Open In-Process",
                    color = DarkBackground,
                    fontWeight = FontWeight.Bold
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = TextMuted)
            }
        }
    )
}
