package com.example.ui.screens.ccx

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ccx.AiProtocol
import com.example.ui.theme.*

@Composable
fun AddChannelDialog(
    onDismiss: () -> Unit,
    onConfirm: (String, AiProtocol, String, String, String, Int) -> Unit
) {
    var name by remember { mutableStateOf("Custom Upstream") }
    var selectedProtocol by remember { mutableStateOf(AiProtocol.OPENAI_COMPATIBLE) }
    var targetUrl by remember { mutableStateOf("http://127.0.0.1:11434/v1/chat/completions") }
    var apiKey by remember { mutableStateOf("") }
    var defaultModel by remember { mutableStateOf("gpt-4o") }
    var priorityText by remember { mutableStateOf("1") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = DarkSurfaceElevated,
        title = {
            Text("Add Upstream AI Server", color = TextPrimary, fontWeight = FontWeight.Bold)
        },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Channel Name") },
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFF6366F1),
                        unfocusedBorderColor = DarkCardBorder,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary
                    )
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text("Protocol Specification:", color = TextSecondary, fontSize = 11.sp)
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    AiProtocol.entries.forEach { proto ->
                        FilterChip(
                            selected = selectedProtocol == proto,
                            onClick = {
                                selectedProtocol = proto
                                targetUrl = when (proto) {
                                    AiProtocol.OPENAI_COMPATIBLE -> "https://api.openai.com/v1/chat/completions"
                                    AiProtocol.CLAUDE_MESSAGES -> "https://api.anthropic.com/v1/messages"
                                    AiProtocol.CUSTOM_REST -> "http://127.0.0.1:8080/generate"
                                }
                                defaultModel = when (proto) {
                                    AiProtocol.OPENAI_COMPATIBLE -> "gpt-4o"
                                    AiProtocol.CLAUDE_MESSAGES -> "claude-3-5-sonnet"
                                    AiProtocol.CUSTOM_REST -> "custom-model"
                                }
                            },
                            label = { Text(proto.displayName.take(12), fontSize = 10.sp) }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = targetUrl,
                    onValueChange = { targetUrl = it },
                    label = { Text("Target Upstream URL") },
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = CyberCyan,
                        unfocusedBorderColor = DarkCardBorder,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary
                    )
                )

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text("API Key / Bearer Token (Optional)") },
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = CyberCyan,
                        unfocusedBorderColor = DarkCardBorder,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary
                    )
                )

                Spacer(modifier = Modifier.height(8.dp))

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = defaultModel,
                        onValueChange = { defaultModel = it },
                        label = { Text("Default Model") },
                        modifier = Modifier.weight(1f),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = CyberCyan,
                            unfocusedBorderColor = DarkCardBorder,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary
                        )
                    )

                    OutlinedTextField(
                        value = priorityText,
                        onValueChange = { priorityText = it },
                        label = { Text("Priority") },
                        modifier = Modifier.weight(0.6f),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = CyberCyan,
                            unfocusedBorderColor = DarkCardBorder,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary
                        )
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val p = priorityText.toIntOrNull() ?: 1
                    onConfirm(name, selectedProtocol, targetUrl, apiKey, defaultModel, p)
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6366F1))
            ) {
                Text("Add Upstream Channel", fontWeight = FontWeight.Bold, color = Color.White)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = TextMuted)
            }
        }
    )
}
