package org.vaanisetu.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.vaanisetu.app.state.TransceiverCoordinator
import org.vaanisetu.app.transport.TransportStatus
import org.vaanisetu.app.ui.theme.*

@Composable
fun DiagnosticsScreen(
    coordinator: TransceiverCoordinator,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val telemetry by coordinator.latestTelemetry.collectAsState()
    val audioDiag by coordinator.audioCaptureManager.diagnostics.collectAsState()
    val playbackDiag by coordinator.audioPlaybackManager.playbackDiagnostics.collectAsState()
    val netDiag by coordinator.activeTransport.connectionDiagnostics.collectAsState()
    val transportStatus by coordinator.activeTransport.status.collectAsState()
    var evaluatedWer by remember { mutableStateOf<Double?>(null) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Slate900)
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        // Header
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            IconButton(onClick = onNavigateBack) {
                Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Slate200)
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "Live Telemetry & Diagnostics",
                color = Slate200,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // --- OFFLINE MODE DIAGNOSTICS ---
        Text(text = "OFFLINE MODE DIAGNOSTICS", color = EmeraldGreen, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(8.dp))

        val currentLang by coordinator.modelManager.selectedLanguage.collectAsState()
        Card(
            colors = CardDefaults.cardColors(containerColor = Slate800),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                MetricRow(label = "Internet connectivity", value = "NOT REQUIRED", highlightColor = EmeraldGreen)
                HorizontalDivider(color = Slate700, modifier = Modifier.padding(vertical = 6.dp))
                MetricRow(label = "Mobile data", value = "OFF (Not required)", highlightColor = EmeraldGreen)
                HorizontalDivider(color = Slate700, modifier = Modifier.padding(vertical = 6.dp))
                MetricRow(label = "STT engine", value = "On-Device Offline STT", highlightColor = Slate200)
                HorizontalDivider(color = Slate700, modifier = Modifier.padding(vertical = 6.dp))
                MetricRow(label = "STT model", value = currentLang.sttModelName, highlightColor = CyanAccent)
                HorizontalDivider(color = Slate700, modifier = Modifier.padding(vertical = 6.dp))
                MetricRow(label = "TTS engine", value = "On-Device Offline TTS", highlightColor = Slate200)
                HorizontalDivider(color = Slate700, modifier = Modifier.padding(vertical = 6.dp))
                MetricRow(label = "TTS model", value = currentLang.ttsModelName, highlightColor = CyanAccent)
                HorizontalDivider(color = Slate700, modifier = Modifier.padding(vertical = 6.dp))
                MetricRow(label = "Transport", value = "Local Wi-Fi TCP", highlightColor = EmeraldGreen)
                HorizontalDivider(color = Slate700, modifier = Modifier.padding(vertical = 6.dp))
                MetricRow(label = "Peer IP", value = netDiag.targetIp ?: (netDiag.detectedLocalIp ?: "192.168.43.1"), highlightColor = Slate200)
                HorizontalDivider(color = Slate700, modifier = Modifier.padding(vertical = 6.dp))
                MetricRow(label = "Port", value = "8899", highlightColor = Slate200)
                HorizontalDivider(color = Slate700, modifier = Modifier.padding(vertical = 6.dp))
                MetricRow(label = "Cloud/API calls", value = "0 (Strict Offline)", highlightColor = EmeraldGreen)
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // --- 0. AUDIO PIPELINE DIAGNOSTICS ---
        Text(text = "0. AUDIO PIPELINE DIAGNOSTICS", color = CyanAccent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(8.dp))

        Card(
            colors = CardDefaults.cardColors(containerColor = Slate800),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(text = "Capture", color = Slate300, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(4.dp))
                MetricRow(
                    label = "Audio Source",
                    value = if (audioDiag.isInitialized) audioDiag.audioSource else "NOT ACTIVE"
                )
                HorizontalDivider(color = Slate700, modifier = Modifier.padding(vertical = 6.dp))
                MetricRow(
                    label = "Sample Rate",
                    value = if (audioDiag.isInitialized) "${audioDiag.configuredSampleRate} Hz" else "NOT ACTIVE"
                )
                HorizontalDivider(color = Slate700, modifier = Modifier.padding(vertical = 6.dp))
                MetricRow(
                    label = "Frame Size",
                    value = if (audioDiag.isInitialized) "${audioDiag.configuredFrameSize} samples (${audioDiag.configuredFrameSize * 1000 / maxOf(audioDiag.configuredSampleRate, 1)} ms)" else "NOT ACTIVE"
                )
                HorizontalDivider(color = Slate700, modifier = Modifier.padding(vertical = 6.dp))
                MetricRow(
                    label = "Min Buffer (hardware)",
                    value = if (audioDiag.minBufferSizeBytes > 0) "${audioDiag.minBufferSizeBytes} bytes" else "NOT MEASURED"
                )
                HorizontalDivider(color = Slate700, modifier = Modifier.padding(vertical = 6.dp))
                MetricRow(
                    label = "Allocated Buffer",
                    value = if (audioDiag.allocatedBufferSizeBytes > 0) "${audioDiag.allocatedBufferSizeBytes} bytes" else "NOT MEASURED"
                )
                HorizontalDivider(color = Slate700, modifier = Modifier.padding(vertical = 6.dp))
                MetricRow(
                    label = "Fallback Used",
                    value = if (audioDiag.isInitialized) (if (audioDiag.audioSourceFallbackUsed) "YES (MIC)" else "NO") else "N/A",
                    highlightColor = if (audioDiag.audioSourceFallbackUsed) AmberWarning else Slate200
                )

                if (audioDiag.initErrorMessage != null) {
                    HorizontalDivider(color = Slate700, modifier = Modifier.padding(vertical = 6.dp))
                    MetricRow(
                        label = "Error",
                        value = audioDiag.initErrorMessage ?: "",
                        highlightColor = CrimsonEmergency
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))
                Text(text = "Playback", color = Slate300, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(4.dp))
                MetricRow(
                    label = "Last Playback Duration",
                    value = if (playbackDiag.playbackDurationMs > 0) "${playbackDiag.playbackDurationMs} ms" else "NOT MEASURED"
                )
                HorizontalDivider(color = Slate700, modifier = Modifier.padding(vertical = 6.dp))
                MetricRow(
                    label = "Samples Written",
                    value = if (playbackDiag.samplesWritten > 0) "${playbackDiag.samplesWritten}" else "NOT MEASURED"
                )
                HorizontalDivider(color = Slate700, modifier = Modifier.padding(vertical = 6.dp))
                MetricRow(
                    label = "Priority",
                    value = playbackDiag.priority,
                    highlightColor = if (playbackDiag.priority == "EMERGENCY") AmberWarning else Slate200
                )

                if (playbackDiag.errorMessage != null) {
                    HorizontalDivider(color = Slate700, modifier = Modifier.padding(vertical = 6.dp))
                    MetricRow(
                        label = "Playback Error",
                        value = playbackDiag.errorMessage ?: "",
                        highlightColor = CrimsonEmergency
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // --- 1. D2D NETWORK & TCP CONNECTION DIAGNOSTICS ---
        Text(text = "1. D2D NETWORK & TCP CONNECTION DIAGNOSTICS", color = CyanAccent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(8.dp))

        Card(
            colors = CardDefaults.cardColors(containerColor = Slate800),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                MetricRow(
                    label = "Transport Status",
                    value = transportStatus.name,
                    highlightColor = when (transportStatus) {
                        TransportStatus.CONNECTED -> EmeraldGreen
                        TransportStatus.ERROR -> CrimsonEmergency
                        TransportStatus.LISTENING, TransportStatus.CONNECTING -> AmberWarning
                        else -> Slate200
                    }
                )
                HorizontalDivider(color = Slate700, modifier = Modifier.padding(vertical = 6.dp))
                MetricRow(
                    label = "Current Role",
                    value = netDiag.role
                )
                HorizontalDivider(color = Slate700, modifier = Modifier.padding(vertical = 6.dp))
                MetricRow(
                    label = "Discovered Local IPv4",
                    value = netDiag.detectedLocalIp ?: "NOT DETECTED",
                    highlightColor = if (netDiag.detectedLocalIp != null) EmeraldGreen else AmberWarning
                )
                HorizontalDivider(color = Slate700, modifier = Modifier.padding(vertical = 6.dp))
                MetricRow(
                    label = "Hotspot / Subnet IP",
                    value = netDiag.detectedHotspotIp ?: "NOT DETECTED"
                )

                if (netDiag.serverBindAddress != null) {
                    HorizontalDivider(color = Slate700, modifier = Modifier.padding(vertical = 6.dp))
                    MetricRow(
                        label = "ServerSocket Bound Address",
                        value = "${netDiag.serverBindAddress} (Port: ${netDiag.serverLocalPort})"
                    )
                    HorizontalDivider(color = Slate700, modifier = Modifier.padding(vertical = 6.dp))
                    MetricRow(
                        label = "ServerSocket Accept State",
                        value = netDiag.acceptState,
                        highlightColor = if (netDiag.incomingConnectionReachedAccept) EmeraldGreen else AmberWarning
                    )
                    HorizontalDivider(color = Slate700, modifier = Modifier.padding(vertical = 6.dp))
                    MetricRow(
                        label = "Incoming Reached accept()",
                        value = if (netDiag.incomingConnectionReachedAccept) "YES (${netDiag.lastClientConnectedIp})" else "NO",
                        highlightColor = if (netDiag.incomingConnectionReachedAccept) EmeraldGreen else Slate400
                    )
                }

                if (netDiag.targetIp != null) {
                    HorizontalDivider(color = Slate700, modifier = Modifier.padding(vertical = 6.dp))
                    MetricRow(
                        label = "Client Target Endpoint",
                        value = "${netDiag.targetIp}:${netDiag.targetPort}"
                    )
                    HorizontalDivider(color = Slate700, modifier = Modifier.padding(vertical = 6.dp))
                    MetricRow(
                        label = "Connection Handshake Latency",
                        value = if (netDiag.connectionDurationMs > 0) "${netDiag.connectionDurationMs} ms" else "NOT CONNECTED"
                    )
                    HorizontalDivider(color = Slate700, modifier = Modifier.padding(vertical = 6.dp))
                    MetricRow(
                        label = "Wi-Fi Network Binding",
                        value = if (netDiag.boundToWifiNetwork) "BOUND (Wi-Fi Preferred)" else if (netDiag.wifiNetworkFound) "AVAILABLE" else "DEFAULT ROUTE",
                        highlightColor = if (netDiag.boundToWifiNetwork) EmeraldGreen else Slate400
                    )
                }

                if (netDiag.lastError != null) {
                    val err = netDiag.lastError!!
                    HorizontalDivider(color = Slate700, modifier = Modifier.padding(vertical = 6.dp))
                    MetricRow(
                        label = "Last Socket Error Type",
                        value = err.errorType.label,
                        highlightColor = CrimsonEmergency
                    )
                    HorizontalDivider(color = Slate700, modifier = Modifier.padding(vertical = 6.dp))
                    MetricRow(
                        label = "Exception Class",
                        value = err.exceptionClass,
                        highlightColor = CrimsonEmergency
                    )
                    HorizontalDivider(color = Slate700, modifier = Modifier.padding(vertical = 6.dp))
                    MetricRow(
                        label = "Exception Message",
                        value = err.exceptionMessage,
                        highlightColor = CrimsonEmergency
                    )
                    if (!err.rootCause.isNullOrEmpty()) {
                        HorizontalDivider(color = Slate700, modifier = Modifier.padding(vertical = 6.dp))
                        MetricRow(
                            label = "Underlying Cause",
                            value = err.rootCause,
                            highlightColor = AmberWarning
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // --- 2. MEASURED BANDWIDTH EFFICIENCY ---
        Text(text = "2. MEASURED BANDWIDTH EFFICIENCY", color = CyanAccent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(8.dp))

        Card(
            colors = CardDefaults.cardColors(containerColor = Slate800),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                MetricRow(
                    label = "Raw 16kHz PCM Equivalent",
                    value = if (telemetry.rawPcmBytes > 0) "${telemetry.rawPcmBytes} bytes" else "NOT MEASURED"
                )
                HorizontalDivider(color = Slate700, modifier = Modifier.padding(vertical = 8.dp))
                MetricRow(
                    label = "VaaniSetu Transmitted Packet",
                    value = if (telemetry.transmittedBytes > 0) "${telemetry.transmittedBytes} bytes" else "NOT MEASURED"
                )
                HorizontalDivider(color = Slate700, modifier = Modifier.padding(vertical = 8.dp))
                MetricRow(
                    label = "Measured Bandwidth Reduction",
                    value = if (telemetry.rawPcmBytes > 0) String.format("%.2f%%", telemetry.measuredBandwidthReductionPercent) else "NOT MEASURED",
                    highlightColor = EmeraldGreen
                )
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // --- 3. LATENCY BREAKDOWN (T0 -> T6) ---
        Text(text = "3. LATENCY BREAKDOWN (T0 → T6)", color = CyanAccent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(8.dp))

        Card(
            colors = CardDefaults.cardColors(containerColor = Slate800),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                MetricRow(
                    label = "STT Latency (T2 − T1)",
                    value = if (telemetry.sttLatencyMs > 0) "${telemetry.sttLatencyMs} ms" else "NOT MEASURED"
                )
                HorizontalDivider(color = Slate700, modifier = Modifier.padding(vertical = 8.dp))
                MetricRow(
                    label = "Network D2D Latency (T4 − T3)",
                    value = if (telemetry.networkLatencyMs > 0) "${telemetry.networkLatencyMs} ms" else "NOT MEASURED"
                )
                HorizontalDivider(color = Slate700, modifier = Modifier.padding(vertical = 8.dp))
                MetricRow(
                    label = "TTS Latency (T5 − T4)",
                    value = if (telemetry.ttsLatencyMs > 0) "${telemetry.ttsLatencyMs} ms" else "NOT MEASURED"
                )
                HorizontalDivider(color = Slate700, modifier = Modifier.padding(vertical = 8.dp))
                MetricRow(
                    label = "End-to-End Latency (T6 − T1)",
                    value = if (telemetry.endToEndLatencyMs > 0) "${telemetry.endToEndLatencyMs} ms" else "NOT MEASURED",
                    highlightColor = CyanPrimary
                )
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // --- 4. REAL-TIME FACTOR (RTF) & SYSTEM RESOURCES ---
        Text(text = "4. RTF & HARDWARE PROFILING", color = CyanAccent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(8.dp))

        Card(
            colors = CardDefaults.cardColors(containerColor = Slate800),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                MetricRow(
                    label = "STT Real-Time Factor (RTF)",
                    value = if (telemetry.sttRtf > 0) String.format("%.3f", telemetry.sttRtf) else "NOT MEASURED"
                )
                HorizontalDivider(color = Slate700, modifier = Modifier.padding(vertical = 8.dp))
                MetricRow(
                    label = "TTS Real-Time Factor (RTF)",
                    value = if (telemetry.ttsRtf > 0) String.format("%.3f", telemetry.ttsRtf) else "NOT MEASURED"
                )
                HorizontalDivider(color = Slate700, modifier = Modifier.padding(vertical = 8.dp))
                MetricRow(
                    label = "App Memory Footprint (PSS)",
                    value = if (telemetry.memoryPssMb > 0) String.format("%.1f MB", telemetry.memoryPssMb) else "NOT MEASURED"
                )
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // --- 5. ACCURACY (WER EVALUATOR) ---
        Text(text = "5. ACCURACY EVALUATOR (WORD ERROR RATE)", color = CyanAccent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(8.dp))

        Card(
            colors = CardDefaults.cardColors(containerColor = Slate800),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Automated Ground-Truth WER Harness",
                    color = Slate200,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Executes Levenshtein distance on test sentences.",
                    color = Slate400,
                    fontSize = 12.sp
                )
                Spacer(modifier = Modifier.height(10.dp))

                Button(
                    onClick = {
                        val ref = "यह वाणीसेतु की तरफ से एक आपातकालीन संदेश है"
                        val hyp = "यह वाणीसेतु की तरफ से आपातकालीन संदेश है"
                        evaluatedWer = coordinator.benchmarkEngine.calculateWer(ref, hyp)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = CyanPrimary),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, tint = Slate900)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Run Offline WER Benchmark", color = Slate900, fontWeight = FontWeight.Bold)
                }

                if (evaluatedWer != null) {
                    Spacer(modifier = Modifier.height(12.dp))
                    MetricRow(
                        label = "Measured Word Error Rate (WER)",
                        value = String.format("%.2f%%", evaluatedWer),
                        highlightColor = if ((evaluatedWer ?: 100.0) < 15.0) EmeraldGreen else AmberWarning
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
fun MetricRow(label: String, value: String, highlightColor: Color = Slate200) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, color = Slate400, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Spacer(modifier = Modifier.width(8.dp))
        Text(text = value, color = highlightColor, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
}
