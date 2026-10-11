package com.example.ui.screens.ccx

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Transform
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
import com.example.ccx.AiProtocol
import com.example.ccx.CcxGatewayManager
import com.example.ccx.TranslationResult
import com.example.ui.theme.*

@Composable
fun CcxTranslatorBench(
    modifier: Modifier = Modifier
) {
    var sourceProtocol by remember { mutableStateOf(AiProtocol.CLAUDE_MESSAGES) }
    var targetProtocol by remember { mutableStateOf(AiProtocol.OPENAI_COMPATIBLE) }

    var inputPayload by remember {
        mutableStateOf(
            """{
  "model": "claude-3-5-sonnet",
  "system": "You are a specialized network debugging assistant.",
  "messages": [
    {
      "role": "user",
      "content": "Diagnose latency spike on upstream cluster."
    }
  ],
  "max_tokens": 1024,
  "stream": true
}"""
        )
    }

    var lastResult by remember { mutableStateOf<TranslationResult?>(null) }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag("ccx_translator_bench"),
        colors = CardDefaults.cardColors(containerColor = DarkSurfaceElevated),
        border = androidx.compose.foundation.BorderStroke(1.dp, DarkCardBorder),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Transform,
                        contentDescription = null,
                        tint = Color(0xFF6366F1),
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "NATIVE PROTOCOL TRANSLATOR",
                        color = TextPrimary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                }

                IconButton(
                    onClick = {
                        val tmp = sourceProtocol
                        sourceProtocol = targetProtocol
                        targetProtocol = tmp
                        inputPayload = if (sourceProtocol == AiProtocol.OPENAI_COMPATIBLE) {
                            """{
  "model": "gpt-4o",
  "messages": [
    { "role": "system", "content": "You are an automated proxy agent." },
    { "role": "user", "content": "Fetch active upstream health." }
  ],
  "max_tokens": 512,
  "stream": true
}"""
                        } else {
                            """{
  "model": "claude-3-5-sonnet",
  "system": "You are a specialized network debugging assistant.",
  "messages": [
    { "role": "user", "content": "Diagnose latency spike on upstream cluster." }
  ],
  "max_tokens": 1024
}"""
                        }
                    },
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.SwapHoriz,
                        contentDescription = "Swap Protocols",
                        tint = CyberCyan,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Direction Indicator
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    color = Color(0xFFD97706).copy(alpha = 0.15f),
                    shape = RoundedCornerShape(6.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFD97706).copy(alpha = 0.4f))
                ) {
                    Text(
                        text = "FROM: ${sourceProtocol.displayName}",
                        color = Color(0xFFD97706),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }

                Text("➔", color = TextMuted, fontSize = 14.sp)

                Surface(
                    color = CyberEmerald.copy(alpha = 0.15f),
                    shape = RoundedCornerShape(6.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, CyberEmerald.copy(alpha = 0.4f))
                ) {
                    Text(
                        text = "TO: ${targetProtocol.displayName}",
                        color = CyberEmerald,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Input Payload Editor
            OutlinedTextField(
                value = inputPayload,
                onValueChange = { inputPayload = it },
                label = { Text("Input Payload (${sourceProtocol.name})") },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 110.dp, max = 150.dp),
                textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color(0xFF6366F1),
                    unfocusedBorderColor = DarkCardBorder,
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary
                )
            )

            Spacer(modifier = Modifier.height(10.dp))

            // Translate Trigger Button
            Button(
                onClick = {
                    lastResult = CcxGatewayManager.executeTranslationAndForward(
                        inputPayload = inputPayload,
                        sourceProtocol = sourceProtocol,
                        targetProtocol = targetProtocol
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("translate_button"),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6366F1))
            ) {
                Icon(Icons.Default.Transform, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Translate In-Process via CCX Engine", fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }

            // Translated Output Preview
            if (lastResult != null) {
                Spacer(modifier = Modifier.height(12.dp))
                val res = lastResult!!
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(TerminalBackground, RoundedCornerShape(8.dp))
                        .padding(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = if (res.isSuccess) "TRANSLATION SUCCESS" else "FAILED",
                            color = if (res.isSuccess) CyberEmerald else CyberRose,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            text = "${res.executionTimeMs} ms in-process",
                            color = CyberCyan,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    Text(
                        text = if (res.isSuccess) res.translatedPayload else (res.errorMessage ?: "Unknown error"),
                        color = TextPrimary,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}
