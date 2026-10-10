package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.proxy.ConnectionCardState
import com.example.proxy.ProtocolType
import com.example.ui.theme.*

@Composable
fun ProtocolTestBench(
    connectionCards: Map<Long, ConnectionCardState>,
    onSendWsText: (Long, String) -> Unit,
    onSendWsBinary: (Long, String) -> Unit,
    onEmitSocketIo: (Long, String, String) -> Unit,
    onOpenNewStream: (ProtocolType, String) -> Unit,
    modifier: Modifier = Modifier
) {
    var selectedTab by remember { mutableIntStateOf(0) }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, DarkCardBorder, RoundedCornerShape(16.dp))
            .testTag("protocol_test_bench"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = DarkSurfaceElevated)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Text(
                text = "Interactive JNI Protocol Workbench",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )
            Text(
                text = "Dispatches direct in-process proxy_request(...) calls with raw byte buffers",
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            // Tabs
            TabRow(
                selectedTabIndex = selectedTab,
                containerColor = DarkBackground,
                contentColor = CyberCyan,
                modifier = Modifier.clip(RoundedCornerShape(8.dp))
            ) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text("Socket.IO v4", fontSize = 12.sp, fontWeight = FontWeight.Bold) }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text("WebSocket Duplex", fontSize = 12.sp, fontWeight = FontWeight.Bold) }
                )
                Tab(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    text = { Text("HTTP SSE Stream", fontSize = 12.sp, fontWeight = FontWeight.Bold) }
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            when (selectedTab) {
                0 -> SocketIoTestTab(
                    connectionCards = connectionCards.filter { it.value.protocol == ProtocolType.SOCKET_IO },
                    onEmit = onEmitSocketIo,
                    onOpenNew = { onOpenNewStream(ProtocolType.SOCKET_IO, "/socket.io/?EIO=4") }
                )
                1 -> WebSocketTestTab(
                    connectionCards = connectionCards.filter { it.value.protocol == ProtocolType.WEBSOCKET },
                    onSendText = onSendWsText,
                    onSendBinary = onSendWsBinary,
                    onOpenNew = { onOpenNewStream(ProtocolType.WEBSOCKET, "/ws/echo") }
                )
                2 -> SseTestTab(
                    connectionCards = connectionCards.filter { it.value.protocol == ProtocolType.HTTP_SSE },
                    onOpenNew = { onOpenNewStream(ProtocolType.HTTP_SSE, "/api/stream/telemetry") }
                )
            }
        }
    }
}

@Composable
private fun SocketIoTestTab(
    connectionCards: Map<Long, ConnectionCardState>,
    onEmit: (Long, String, String) -> Unit,
    onOpenNew: () -> Unit
) {
    var eventName by remember { mutableStateOf("client_event") }
    var payloadJson by remember { mutableStateOf("{\"msg\":\"Hello JNI FFI\",\"speed\":99}") }

    val activeSession = connectionCards.values.firstOrNull()

    Column {
        if (activeSession == null) {
            Text("No active Socket.IO connection.", color = TextMuted, fontSize = 12.sp)
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = onOpenNew,
                colors = ButtonDefaults.buttonColors(containerColor = CyberAmber),
                modifier = Modifier.testTag("open_socketio_stream_btn")
            ) {
                Text("Open In-Process Socket.IO Channel", color = DarkBackground, fontWeight = FontWeight.Bold)
            }
        } else {
            OutlinedTextField(
                value = eventName,
                onValueChange = { eventName = it },
                label = { Text("Event Name (e.g., chat_message)") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("socketio_event_input"),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = CyberAmber,
                    unfocusedBorderColor = DarkCardBorder,
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary
                )
            )

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = payloadJson,
                onValueChange = { payloadJson = it },
                label = { Text("JSON Payload") },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("socketio_payload_input"),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = CyberAmber,
                    unfocusedBorderColor = DarkCardBorder,
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary
                )
            )

            Spacer(modifier = Modifier.height(10.dp))

            Button(
                onClick = { onEmit(activeSession.streamId, eventName, payloadJson) },
                colors = ButtonDefaults.buttonColors(containerColor = CyberAmber),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("emit_socketio_btn")
            ) {
                Icon(Icons.Default.Send, contentDescription = null, tint = DarkBackground, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Emit 42[\"$eventName\", ...] via JNI", color = DarkBackground, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun WebSocketTestTab(
    connectionCards: Map<Long, ConnectionCardState>,
    onSendText: (Long, String) -> Unit,
    onSendBinary: (Long, String) -> Unit,
    onOpenNew: () -> Unit
) {
    var wsText by remember { mutableStateOf("ping_inproc_pipe") }
    var isBinaryMode by remember { mutableStateOf(false) }

    val activeSession = connectionCards.values.firstOrNull()

    Column {
        if (activeSession == null) {
            Text("No active WebSocket connection.", color = TextMuted, fontSize = 12.sp)
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = onOpenNew,
                colors = ButtonDefaults.buttonColors(containerColor = CyberCyan),
                modifier = Modifier.testTag("open_ws_stream_btn")
            ) {
                Text("Open In-Process WebSocket Channel", color = DarkBackground, fontWeight = FontWeight.Bold)
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (isBinaryMode) "Binary Frame Mode (Hex)" else "Text Frame Mode (UTF-8)",
                    fontSize = 12.sp,
                    color = CyberCyan,
                    fontWeight = FontWeight.Bold
                )
                FilterChip(
                    selected = isBinaryMode,
                    onClick = {
                        isBinaryMode = !isBinaryMode
                        wsText = if (isBinaryMode) "DEADBEEF0102" else "ping_inproc_pipe"
                    },
                    label = { Text(if (isBinaryMode) "Switch to TEXT" else "Switch to BINARY", fontSize = 11.sp) }
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = wsText,
                onValueChange = { wsText = it },
                label = { Text(if (isBinaryMode) "Hex Bytes (e.g. DEADBEEF)" else "Text Frame Payload") },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("ws_payload_input"),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = CyberCyan,
                    unfocusedBorderColor = DarkCardBorder,
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary
                )
            )

            Spacer(modifier = Modifier.height(10.dp))

            Button(
                onClick = {
                    if (isBinaryMode) {
                        onSendBinary(activeSession.streamId, wsText)
                    } else {
                        onSendText(activeSession.streamId, wsText)
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = CyberCyan),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("send_ws_frame_btn")
            ) {
                Icon(Icons.Default.Send, contentDescription = null, tint = DarkBackground, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = if (isBinaryMode) "Send Binary Opcode 0x2 via JNI" else "Send Text Opcode 0x1 via JNI",
                    color = DarkBackground,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun SseTestTab(
    connectionCards: Map<Long, ConnectionCardState>,
    onOpenNew: () -> Unit
) {
    val activeCount = connectionCards.size

    Column {
        Text(
            text = "Server-Sent Events (SSE) pushes chunked telemetry directly over the raw JNI callback onSseChunk.",
            color = TextSecondary,
            fontSize = 12.sp
        )
        Spacer(modifier = Modifier.height(10.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(TerminalBackground, RoundedCornerShape(8.dp))
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("Active SSE Streams", color = TextMuted, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                Text("$activeCount active", color = CyberEmerald, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            }

            Button(
                onClick = onOpenNew,
                colors = ButtonDefaults.buttonColors(containerColor = CyberEmerald),
                modifier = Modifier.testTag("spawn_sse_stream_btn")
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = null, tint = DarkBackground, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Spawn SSE Stream", color = DarkBackground, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }
        }
    }
}
