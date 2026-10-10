package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.proxy.ProxyServiceStats
import com.example.ui.theme.*

@Composable
fun ServiceManagerCard(
    stats: ProxyServiceStats,
    onToggleService: (Boolean) -> Unit,
    onRestartStreams: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, DarkCardBorder, RoundedCornerShape(16.dp))
            .testTag("service_manager_card"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = DarkSurfaceElevated)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            // Header: Title & Master Switch
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Pulsating status dot
                    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
                    val alpha by infiniteTransition.animateFloat(
                        initialValue = 0.3f,
                        targetValue = 1.0f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(800, easing = LinearEasing),
                            repeatMode = RepeatMode.Reverse
                        ),
                        label = "pulseAlpha"
                    )

                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .clip(CircleShape)
                            .background(
                                if (stats.isServiceRunning) CyberEmerald.copy(alpha = alpha) else CyberRose
                            )
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = "In-Process FFI Proxy Engine",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                        Text(
                            text = if (stats.isServiceRunning) "DAEMON RUNNING (IN-APP)" else "STANDBY",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (stats.isServiceRunning) CyberEmerald else TextMuted,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                Switch(
                    checked = stats.isServiceRunning,
                    onCheckedChange = onToggleService,
                    modifier = Modifier.testTag("service_toggle_switch"),
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = CyberEmerald,
                        uncheckedThumbColor = TextMuted,
                        uncheckedTrackColor = DarkBackground
                    )
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Tech Badges Row (PID, Mode)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AssistChip(
                    onClick = {},
                    label = { Text("PID: ${stats.pid}", fontFamily = FontFamily.Monospace, fontSize = 11.sp) },
                    leadingIcon = {
                        Icon(Icons.Default.Memory, contentDescription = null, modifier = Modifier.size(14.dp), tint = CyberCyan)
                    },
                    colors = AssistChipDefaults.assistChipColors(
                        containerColor = DarkBackground,
                        labelColor = TextPrimary
                    ),
                    border = AssistChipDefaults.assistChipBorder(borderColor = DarkCardBorder, enabled = true)
                )

                AssistChip(
                    onClick = {},
                    label = { Text(stats.nativeLibraryMode, fontFamily = FontFamily.Monospace, fontSize = 11.sp) },
                    colors = AssistChipDefaults.assistChipColors(
                        containerColor = DarkBackground,
                        labelColor = CyberCyan
                    ),
                    border = AssistChipDefaults.assistChipBorder(borderColor = DarkCardBorder, enabled = true)
                )

                if (stats.upstreamStreamsCount > 0) {
                    AssistChip(
                        onClick = {},
                        label = { Text("UPSTREAM: ${stats.upstreamStreamsCount}", fontFamily = FontFamily.Monospace, fontSize = 11.sp) },
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = DarkBackground,
                            labelColor = CyberRose
                        ),
                        border = AssistChipDefaults.assistChipBorder(borderColor = DarkCardBorder, enabled = true)
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Live Telemetry Grid (Uptime, Total Frames, Total Bytes, Throughput)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(TerminalBackground, RoundedCornerShape(12.dp))
                    .padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                MetricColumn(
                    label = "UPTIME",
                    value = formatUptime(stats.uptimeSeconds),
                    color = TextPrimary
                )
                MetricColumn(
                    label = "FRAMES",
                    value = "${stats.totalFrames}",
                    color = CyberCyan
                )
                MetricColumn(
                    label = "BYTES",
                    value = formatBytes(stats.totalBytes),
                    color = CyberEmerald
                )
                MetricColumn(
                    label = "THROUGHPUT",
                    value = "${String.format("%.1f", stats.currentThroughputKbps)} kb/s",
                    color = CyberAmber
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Control Actions
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                OutlinedButton(
                    onClick = onRestartStreams,
                    modifier = Modifier.testTag("restart_streams_button"),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    shape = RoundedCornerShape(8.dp),
                    border = ButtonDefaults.outlinedButtonBorder.copy(brush = androidx.compose.ui.graphics.SolidColor(DarkCardBorder))
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp), tint = CyberCyan)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Re-Init Default Streams", fontSize = 12.sp, color = TextPrimary)
                }
            }
        }
    }
}

@Composable
private fun MetricColumn(
    label: String,
    value: String,
    color: Color
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = label,
            fontSize = 9.sp,
            color = TextMuted,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = value,
            fontSize = 13.sp,
            color = color,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace
        )
    }
}

private fun formatUptime(seconds: Long): String {
    val mins = seconds / 60
    val secs = seconds % 60
    return String.format("%02d:%02d", mins, secs)
}

private fun formatBytes(bytes: Long): String {
    return when {
        bytes < 1024 -> "${bytes} B"
        bytes < 1024 * 1024 -> String.format("%.1f KB", bytes / 1024.0)
        else -> String.format("%.2f MB", bytes / (1024.0 * 1024.0))
    }
}
