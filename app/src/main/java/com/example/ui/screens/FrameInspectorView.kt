package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.proxy.Direction
import com.example.proxy.FrameLog
import com.example.proxy.ProtocolType
import com.example.ui.theme.*
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun FrameInspectorView(
    logs: List<FrameLog>,
    selectedFilter: ProtocolType?,
    onSelectFilter: (ProtocolType?) -> Unit,
    onClearLogs: () -> Unit,
    modifier: Modifier = Modifier
) {
    val filteredLogs = if (selectedFilter == null) {
        logs
    } else {
        logs.filter { it.protocol == selectedFilter }
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, DarkCardBorder, RoundedCornerShape(16.dp))
            .testTag("frame_inspector_card"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = DarkSurfaceElevated)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            // Header Row: Title & Clear Button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Real-Time JNI Frame Inspector",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    Text(
                        text = "${filteredLogs.size} frames captured in-process",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextMuted
                    )
                }

                IconButton(
                    onClick = onClearLogs,
                    modifier = Modifier.testTag("clear_logs_button")
                ) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "Clear Logs",
                        tint = TextMuted,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Protocol Filter Chips
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                FilterChip(
                    selected = selectedFilter == null,
                    onClick = { onSelectFilter(null) },
                    label = { Text("All", fontSize = 11.sp) }
                )
                FilterChip(
                    selected = selectedFilter == ProtocolType.SOCKET_IO,
                    onClick = { onSelectFilter(ProtocolType.SOCKET_IO) },
                    label = { Text("Socket.IO", fontSize = 11.sp, color = CyberAmber) }
                )
                FilterChip(
                    selected = selectedFilter == ProtocolType.WEBSOCKET,
                    onClick = { onSelectFilter(ProtocolType.WEBSOCKET) },
                    label = { Text("WebSocket", fontSize = 11.sp, color = CyberCyan) }
                )
                FilterChip(
                    selected = selectedFilter == ProtocolType.HTTP_SSE,
                    onClick = { onSelectFilter(ProtocolType.HTTP_SSE) },
                    label = { Text("SSE", fontSize = 11.sp, color = CyberEmerald) }
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Log Items List
            if (filteredLogs.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp)
                        .background(TerminalBackground, RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No frames logged yet. Send or trigger events above.",
                        color = TextMuted,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(280.dp)
                        .background(TerminalBackground, RoundedCornerShape(8.dp))
                        .padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(filteredLogs.reversed(), key = { it.id }) { log ->
                        FrameLogRow(log)
                    }
                }
            }
        }
    }
}

@Composable
private fun FrameLogRow(log: FrameLog) {
    val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
    val formattedTime = timeFormat.format(Date(log.timestamp))

    val dirColor = if (log.direction == Direction.INCOMING_JNI_CALLBACK) CyberEmerald else CyberCyan
    val dirIcon = if (log.direction == Direction.INCOMING_JNI_CALLBACK) Icons.Default.ArrowDownward else Icons.Default.ArrowUpward

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(DarkSurface, RoundedCornerShape(6.dp))
            .border(0.5.dp, DarkCardBorder, RoundedCornerShape(6.dp))
            .padding(8.dp)
    ) {
        // Line 1: Timestamp | Direction Icon | Opcode | Length
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = dirIcon,
                    contentDescription = null,
                    tint = dirColor,
                    modifier = Modifier.size(12.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = log.opcodeName,
                    color = dirColor,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
                if (log.isUpstream) {
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "[UPSTREAM]",
                        color = CyberRose,
                        fontSize = 8.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                }
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "${log.rawLength}B",
                    color = TextMuted,
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace
                )
            }

            Text(
                text = formattedTime,
                color = TextMuted,
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace
            )
        }

        Spacer(modifier = Modifier.height(4.dp))

        // Line 2: Payload Preview
        Text(
            text = log.payloadPreview,
            color = TextPrimary,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace
        )

        // Line 3: Hex Dump
        if (log.hexDump.isNotEmpty()) {
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "HEX: ${log.hexDump}",
                color = CodeHighlight,
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}
