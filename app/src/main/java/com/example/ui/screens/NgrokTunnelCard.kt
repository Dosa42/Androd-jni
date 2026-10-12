package com.example.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.tunnel.*
import com.example.ui.theme.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun NgrokTunnelCard(
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val tunnelState by NgrokTunnelManager.state.collectAsStateWithLifecycle()
    val trafficLogs by NgrokTunnelManager.trafficLogs.collectAsStateWithLifecycle()
    val totalRequests by NgrokTunnelManager.totalRequests.collectAsStateWithLifecycle()

    var showConfigDialog by remember { mutableStateOf(false) }
    var showLogsExpanded by remember { mutableStateOf(false) }
    var pingResult by remember { mutableStateOf<String?>(null) }
    var isPinging by remember { mutableStateOf(false) }

    val activeProfile = TunnelConfigManager.getActiveProfile(context)
    val sysParams = TunnelConfigManager.getSystemParameters(context)

    val statusColor = when (tunnelState) {
        is TunnelState.Connected -> CyberEmerald
        is TunnelState.Connecting -> CyberAmber
        is TunnelState.Stopping -> CyberRose
        is TunnelState.Error -> CyberRose
        is TunnelState.Disconnected -> TextMuted
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag("ngrok_tunnel_card"),
        colors = CardDefaults.cardColors(containerColor = DarkSurfaceElevated),
        border = androidx.compose.foundation.BorderStroke(1.dp, DarkCardBorder),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Header Row: Ngrok Badge, Status, Settings
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        color = Color(0xFF0066F5).copy(alpha = 0.15f),
                        shape = RoundedCornerShape(8.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF0066F5).copy(alpha = 0.5f))
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Public,
                                contentDescription = null,
                                tint = Color(0xFF38BDF8),
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "NGROK DYNAMIC CLOUD",
                                color = Color(0xFF38BDF8),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    // Status Pill
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .background(DarkBackground, RoundedCornerShape(12.dp))
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(statusColor)
                        )
                        Spacer(modifier = Modifier.width(5.dp))
                        Text(
                            text = when (tunnelState) {
                                is TunnelState.Connected -> "ACTIVE"
                                is TunnelState.Connecting -> "CONNECTING"
                                is TunnelState.Stopping -> "STOPPING"
                                is TunnelState.Error -> "FAULT"
                                is TunnelState.Disconnected -> "STANDBY"
                            },
                            color = statusColor,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }

                IconButton(
                    onClick = { showConfigDialog = true },
                    modifier = Modifier
                        .size(32.dp)
                        .testTag("ngrok_config_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = "Configure Tunnel Host & System",
                        tint = TextSecondary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Body depending on state
            when (val state = tunnelState) {
                is TunnelState.Connected -> {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = DarkBackground,
                        shape = RoundedCornerShape(12.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, CyberEmerald.copy(alpha = 0.4f))
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                text = "PUBLIC INGRESS URL",
                                color = TextMuted,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = state.publicUrl,
                                    color = CyberEmerald,
                                    fontSize = 13.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.weight(1f)
                                )

                                IconButton(
                                    onClick = {
                                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                        clipboard.setPrimaryClip(ClipData.newPlainText("Ngrok URL", state.publicUrl))
                                        Toast.makeText(context, "Copied public URL to clipboard!", Toast.LENGTH_SHORT).show()
                                    },
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.ContentCopy,
                                        contentDescription = "Copy URL",
                                        tint = CyberEmerald,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(8.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "Host: ${activeProfile.name}" + if (activeProfile.clientBackend.isEnabled) " [Custom REST Cloud]" else "",
                                    color = TextSecondary,
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                                Text(
                                    text = "Requests: $totalRequests",
                                    color = CyberCyan,
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                coroutineScope.launch {
                                    isPinging = true
                                    pingResult = NgrokTunnelManager.testLocalPing(state.localPort)
                                    isPinging = false
                                }
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = CyberCyan)
                        ) {
                            Icon(Icons.Default.NetworkCheck, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(if (isPinging) "Testing..." else "Test Local Ping", fontSize = 11.sp)
                        }

                        Button(
                            onClick = { NgrokTunnelManager.stopTunnel(context) },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = CyberRose)
                        ) {
                            Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Stop Tunnel", fontSize = 11.sp, color = DarkBackground, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                is TunnelState.Connecting -> {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(DarkBackground, RoundedCornerShape(12.dp))
                            .padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        CircularProgressIndicator(
                            color = CyberAmber,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = state.stage,
                            color = CyberAmber,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }

                is TunnelState.Error -> {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(CyberRose.copy(alpha = 0.1f), RoundedCornerShape(12.dp))
                            .border(1.dp, CyberRose.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                            .padding(12.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Error, contentDescription = null, tint = CyberRose, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Tunnel Connection Fault", color = CyberRose, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = state.errorMessage,
                            color = TextPrimary,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = { showConfigDialog = true },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = CyberCyan)
                            ) {
                                Text("Edit Config", fontSize = 11.sp)
                            }
                            Button(
                                onClick = { NgrokTunnelManager.startTunnel(context) },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(containerColor = CyberRose)
                            ) {
                                Text("Retry Tunnel", fontSize = 11.sp, color = DarkBackground, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }

                is TunnelState.Disconnected, is TunnelState.Stopping -> {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(DarkBackground, RoundedCornerShape(12.dp))
                            .padding(12.dp)
                    ) {
                        Text(
                            text = "Active Host Profile: ${activeProfile.name}",
                            color = CyberCyan,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                        if (activeProfile.clientBackend.isEnabled) {
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "Cloud Client Backend: ${activeProfile.clientBackend.restEndpoint}",
                                color = Color(0xFF818CF8),
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                        Spacer(modifier = Modifier.height(10.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TextButton(
                                onClick = { showConfigDialog = true },
                                colors = ButtonDefaults.textButtonColors(contentColor = TextSecondary)
                            ) {
                                Icon(Icons.Default.Tune, contentDescription = null, modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Manage Hosts & System", fontSize = 11.sp)
                            }

                            Button(
                                onClick = {
                                    val token = NgrokConfig.getAuthToken(context)
                                    if (token.isBlank()) {
                                        showConfigDialog = true
                                    } else {
                                        NgrokTunnelManager.startTunnel(context)
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0066F5)),
                                modifier = Modifier.testTag("start_ngrok_button")
                            ) {
                                Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Start Tunnel", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }

            if (pingResult != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = pingResult ?: "",
                    color = CyberCyan,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(TerminalBackground, RoundedCornerShape(6.dp))
                        .padding(8.dp)
                )
            }

            // Collapsible Traffic Logs Header
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showLogsExpanded = !showLogsExpanded }
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Live Ingress Traffic Logs (${trafficLogs.size}) [Buffer Max: ${sysParams.trafficLogMaxEntries}]",
                    color = TextSecondary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Icon(
                    imageVector = if (showLogsExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    tint = TextMuted,
                    modifier = Modifier.size(16.dp)
                )
            }

            AnimatedVisibility(visible = showLogsExpanded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 200.dp)
                        .background(TerminalBackground, RoundedCornerShape(8.dp))
                        .padding(8.dp)
                ) {
                    if (trafficLogs.isEmpty()) {
                        Text(
                            text = "No traffic yet. Send requests to the public URL to inspect live HTTP traffic.",
                            color = TextMuted,
                            fontSize = 11.sp
                        )
                    } else {
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            items(trafficLogs.take(sysParams.trafficLogMaxEntries)) { entry ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = entry.method,
                                            color = when (entry.method) {
                                                "GET" -> CyberCyan
                                                "POST" -> CyberEmerald
                                                else -> CyberAmber
                                            },
                                            fontSize = 10.sp,
                                            fontFamily = FontFamily.Monospace,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = entry.path.take(28),
                                            color = TextPrimary,
                                            fontSize = 10.sp,
                                            fontFamily = FontFamily.Monospace
                                        )
                                    }
                                    Text(
                                        text = "${entry.statusCode} (${entry.responseDurationMs}ms)",
                                        color = if (entry.statusCode == 200) CyberEmerald else CyberAmber,
                                        fontSize = 10.sp,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showConfigDialog) {
        TunnelHostConfigDialog(
            onDismiss = { showConfigDialog = false }
        )
    }
}

@Composable
fun TunnelHostConfigDialog(
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var profiles by remember { mutableStateOf(TunnelConfigManager.getProfiles(context)) }
    var activeProfile by remember { mutableStateOf(TunnelConfigManager.getActiveProfile(context)) }
    var token by remember { mutableStateOf(NgrokConfig.getAuthToken(context)) }
    var sysParams by remember { mutableStateOf(TunnelConfigManager.getSystemParameters(context)) }

    var editingProfile by remember { mutableStateOf<TunnelHostProfile?>(null) }
    var showAddEditSheet by remember { mutableStateOf(false) }
    var showSystemParamsTab by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = DarkSurfaceElevated,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Dynamic Host & System Setup", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { showSystemParamsTab = false }) {
                        Text("Hosts", color = if (!showSystemParamsTab) CyberCyan else TextMuted, fontSize = 11.sp)
                    }
                    TextButton(onClick = { showSystemParamsTab = true }) {
                        Text("System Config", color = if (showSystemParamsTab) CyberCyan else TextMuted, fontSize = 11.sp)
                    }
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 440.dp)
            ) {
                if (!showSystemParamsTab) {
                    OutlinedTextField(
                        value = token,
                        onValueChange = {
                            token = it
                            NgrokConfig.setAuthToken(context, it)
                        },
                        label = { Text("Ngrok Authtoken") },
                        placeholder = { Text("2abc...xyz") },
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFF0066F5),
                            unfocusedBorderColor = DarkCardBorder,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary
                        )
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Configured Host Profiles", color = CyberCyan, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        TextButton(onClick = {
                            editingProfile = TunnelHostProfile(
                                name = "New Cloud Host",
                                targetUrls = listOf("http://127.0.0.1:8085")
                            )
                            showAddEditSheet = true
                        }) {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Add Host", fontSize = 11.sp)
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        items(profiles) { profile ->
                            val isActive = profile.id == activeProfile.id
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        TunnelConfigManager.setActiveProfileId(context, profile.id)
                                        activeProfile = profile
                                    },
                                color = if (isActive) Color(0xFF0066F5).copy(alpha = 0.2f) else DarkBackground,
                                shape = RoundedCornerShape(8.dp),
                                border = androidx.compose.foundation.BorderStroke(1.dp, if (isActive) Color(0xFF0066F5) else DarkCardBorder)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(10.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = profile.name + if (isActive) " (ACTIVE)" else "",
                                            color = if (isActive) CyberEmerald else TextPrimary,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 12.sp
                                        )
                                        Text(
                                            text = "Targets: ${profile.targetUrls.size}" + if (profile.clientBackend.isEnabled) " [Custom REST Cloud]" else "",
                                            color = TextSecondary,
                                            fontSize = 10.sp,
                                            fontFamily = FontFamily.Monospace
                                        )
                                    }

                                    Row {
                                        IconButton(
                                            onClick = {
                                                editingProfile = profile
                                                showAddEditSheet = true
                                            },
                                            modifier = Modifier.size(28.dp)
                                        ) {
                                            Icon(Icons.Default.Edit, contentDescription = "Edit Host", tint = CyberCyan, modifier = Modifier.size(14.dp))
                                        }
                                        if (profiles.size > 1) {
                                            IconButton(
                                                onClick = {
                                                    TunnelConfigManager.deleteProfile(context, profile.id)
                                                    profiles = TunnelConfigManager.getProfiles(context)
                                                    activeProfile = TunnelConfigManager.getActiveProfile(context)
                                                },
                                                modifier = Modifier.size(28.dp)
                                            ) {
                                                Icon(Icons.Default.Delete, contentDescription = "Delete Host", tint = TextMuted, modifier = Modifier.size(14.dp))
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                } else {
                    // System Parameters Config Tab
                    var connTimeout by remember { mutableStateOf(sysParams.connectTimeoutSeconds.toString()) }
                    var readTimeout by remember { mutableStateOf(sysParams.readTimeoutSeconds.toString()) }
                    var wakelockMins by remember { mutableStateOf(sysParams.wakelockDurationMinutes.toString()) }
                    var pingSecs by remember { mutableStateOf(sysParams.pingIntervalSeconds.toString()) }
                    var logMax by remember { mutableStateOf(sysParams.trafficLogMaxEntries.toString()) }

                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        item {
                            Text("Global Network & Daemon Parameters", color = CyberCyan, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            Spacer(modifier = Modifier.height(4.dp))
                        }
                        item {
                            OutlinedTextField(
                                value = connTimeout,
                                onValueChange = { connTimeout = it },
                                label = { Text("OkHttp Connect Timeout (Seconds)") },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        item {
                            OutlinedTextField(
                                value = readTimeout,
                                onValueChange = { readTimeout = it },
                                label = { Text("OkHttp Read Timeout (Seconds)") },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        item {
                            OutlinedTextField(
                                value = wakelockMins,
                                onValueChange = { wakelockMins = it },
                                label = { Text("Wakelock Duration (Minutes)") },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        item {
                            OutlinedTextField(
                                value = pingSecs,
                                onValueChange = { pingSecs = it },
                                label = { Text("Ping Interval (Seconds)") },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        item {
                            OutlinedTextField(
                                value = logMax,
                                onValueChange = { logMax = it },
                                label = { Text("Traffic Log Max Buffer Entries") },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        item {
                            Button(
                                onClick = {
                                    val newParams = SystemParameters(
                                        connectTimeoutSeconds = connTimeout.toIntOrNull() ?: 15,
                                        readTimeoutSeconds = readTimeout.toIntOrNull() ?: 30,
                                        wakelockDurationMinutes = wakelockMins.toIntOrNull() ?: 60,
                                        pingIntervalSeconds = pingSecs.toIntOrNull() ?: 20,
                                        trafficLogMaxEntries = logMax.toIntOrNull() ?: 100
                                    )
                                    TunnelConfigManager.saveSystemParameters(context, newParams)
                                    sysParams = newParams
                                    Toast.makeText(context, "System parameters saved!", Toast.LENGTH_SHORT).show()
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = CyberCyan),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Save System Parameters", color = DarkBackground, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0066F5))
            ) {
                Text("Close", color = Color.White, fontWeight = FontWeight.Bold)
            }
        }
    )

    if (showAddEditSheet && editingProfile != null) {
        HostProfileEditDialog(
            profile = editingProfile!!,
            onDismiss = { showAddEditSheet = false },
            onSave = { saved ->
                TunnelConfigManager.addOrUpdateProfile(context, saved)
                TunnelConfigManager.setActiveProfileId(context, saved.id)
                profiles = TunnelConfigManager.getProfiles(context)
                activeProfile = TunnelConfigManager.getActiveProfile(context)
                showAddEditSheet = false
            }
        )
    }
}

@Composable
fun HostProfileEditDialog(
    profile: TunnelHostProfile,
    onDismiss: () -> Unit,
    onSave: (TunnelHostProfile) -> Unit
) {
    var name by remember { mutableStateOf(profile.name) }
    var targetsText by remember { mutableStateOf(profile.targetUrls.joinToString("\n")) }
    var pathRewritePrefix by remember { mutableStateOf(profile.pathRewritePrefix) }
    var headers by remember { mutableStateOf(profile.customHeaders.toMutableStateList()) }
    var testPayload by remember { mutableStateOf(profile.testPayload) }

    // Client Backend (Custom REST) state
    var cbEnabled by remember { mutableStateOf(profile.clientBackend.isEnabled) }
    var cbEndpoint by remember { mutableStateOf(profile.clientBackend.restEndpoint) }
    var cbApiKey by remember { mutableStateOf(profile.clientBackend.apiKey) }
    var cbHeaderKey by remember { mutableStateOf(profile.clientBackend.cloudHeaderKey) }
    var cbHeaderVal by remember { mutableStateOf(profile.clientBackend.cloudHeaderValue) }

    var newHeaderKey by remember { mutableStateOf("") }
    var newHeaderVal by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = DarkSurfaceElevated,
        title = {
            Text("Edit Host Profile & Client Backend", color = TextPrimary, fontWeight = FontWeight.Bold)
        },
        text = {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Profile Name") },
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = CyberCyan,
                            unfocusedBorderColor = DarkCardBorder,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary
                        )
                    )
                }

                item {
                    OutlinedTextField(
                        value = targetsText,
                        onValueChange = { targetsText = it },
                        label = { Text("Target URLs (One per line for Load Balancing)") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 70.dp),
                        textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = CyberCyan,
                            unfocusedBorderColor = DarkCardBorder,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary
                        )
                    )
                }

                // Client Backend (Custom REST) Section
                item {
                    Spacer(modifier = Modifier.height(4.dp))
                    Surface(
                        color = DarkBackground,
                        shape = RoundedCornerShape(10.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF6366F1).copy(alpha = 0.5f))
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Client Backend (Custom REST)", color = Color(0xFF818CF8), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                Switch(
                                    checked = cbEnabled,
                                    onCheckedChange = { cbEnabled = it },
                                    colors = SwitchDefaults.colors(checkedThumbColor = Color(0xFF6366F1))
                                )
                            }

                            if (cbEnabled) {
                                Spacer(modifier = Modifier.height(8.dp))
                                OutlinedTextField(
                                    value = cbEndpoint,
                                    onValueChange = { cbEndpoint = it },
                                    label = { Text("Custom REST Endpoint URL") },
                                    modifier = Modifier.fillMaxWidth(),
                                    textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                OutlinedTextField(
                                    value = cbApiKey,
                                    onValueChange = { cbApiKey = it },
                                    label = { Text("API Key / Token") },
                                    modifier = Modifier.fillMaxWidth(),
                                    textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    OutlinedTextField(
                                        value = cbHeaderKey,
                                        onValueChange = { cbHeaderKey = it },
                                        label = { Text("Header Key") },
                                        modifier = Modifier.weight(1f)
                                    )
                                    OutlinedTextField(
                                        value = cbHeaderVal,
                                        onValueChange = { cbHeaderVal = it },
                                        label = { Text("Header Value") },
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }
                        }
                    }
                }

                item {
                    Text("Custom Request Headers (Key-Value Builder)", color = CyberCyan, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }

                items(headers, key = { it.id }) { entry ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = entry.key,
                            onValueChange = { k ->
                                val idx = headers.indexOf(entry)
                                if (idx >= 0) headers[idx] = entry.copy(key = k)
                            },
                            placeholder = { Text("Header Key") },
                            modifier = Modifier.weight(1f),
                            textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                        )
                        OutlinedTextField(
                            value = entry.value,
                            onValueChange = { v ->
                                val idx = headers.indexOf(entry)
                                if (idx >= 0) headers[idx] = entry.copy(value = v)
                            },
                            placeholder = { Text("Value") },
                            modifier = Modifier.weight(1f),
                            textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                        )
                        IconButton(
                            onClick = { headers.remove(entry) },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(Icons.Default.RemoveCircleOutline, contentDescription = "Remove Header", tint = CyberRose)
                        }
                    }
                }

                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = newHeaderKey,
                            onValueChange = { newHeaderKey = it },
                            placeholder = { Text("New Header Key") },
                            modifier = Modifier.weight(1f),
                            textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                        )
                        OutlinedTextField(
                            value = newHeaderVal,
                            onValueChange = { newHeaderVal = it },
                            placeholder = { Text("Value") },
                            modifier = Modifier.weight(1f),
                            textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                        )
                        Button(
                            onClick = {
                                if (newHeaderKey.isNotBlank()) {
                                    headers.add(HeaderEntry(key = newHeaderKey.trim(), value = newHeaderVal.trim()))
                                    newHeaderKey = ""
                                    newHeaderVal = ""
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = CyberCyan)
                        ) {
                            Text("Add", fontSize = 11.sp, color = DarkBackground, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val urls = targetsText.split("\n").map { it.trim() }.filter { it.isNotBlank() }
                    onSave(
                        profile.copy(
                            name = name.trim(),
                            targetUrls = if (urls.isNotEmpty()) urls else listOf("http://127.0.0.1:8085"),
                            customHeaders = headers.toList(),
                            clientBackend = ClientBackendConfig(
                                isEnabled = cbEnabled,
                                restEndpoint = cbEndpoint.trim(),
                                apiKey = cbApiKey.trim(),
                                cloudHeaderKey = cbHeaderKey.trim(),
                                cloudHeaderValue = cbHeaderVal.trim()
                            )
                        )
                    )
                },
                colors = ButtonDefaults.buttonColors(containerColor = CyberCyan)
            ) {
                Text("Save Host Profile", color = DarkBackground, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = TextMuted)
            }
        }
    )
}
