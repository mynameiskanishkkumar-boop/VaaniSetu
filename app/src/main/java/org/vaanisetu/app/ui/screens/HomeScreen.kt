package org.vaanisetu.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch
import org.vaanisetu.app.data.model.ConversationMessage
import org.vaanisetu.app.data.model.LocationFreshness
import org.vaanisetu.app.data.model.MessageDirection
import org.vaanisetu.app.data.model.MessagePriority
import org.vaanisetu.app.data.model.SourceLocationInfo
import org.vaanisetu.app.data.model.TransceiverMode
import org.vaanisetu.app.data.model.TransceiverState
import org.vaanisetu.app.state.TransceiverCoordinator
import org.vaanisetu.app.transport.TransportStatus
import org.vaanisetu.app.ui.components.PttButton
import org.vaanisetu.app.ui.theme.*

@Composable
fun HomeScreen(
    coordinator: TransceiverCoordinator,
    onNavigateToConnection: () -> Unit,
    onNavigateToLanguage: () -> Unit,
    onNavigateToDiagnostics: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    val clipboardManager = LocalClipboardManager.current

    val state by coordinator.transceiverState.collectAsState()
    val mode by coordinator.transceiverMode.collectAsState()
    val rmsLevel by coordinator.audioCaptureManager.currentRmsLevel.collectAsState()
    val selectedLanguage by coordinator.modelManager.selectedLanguage.collectAsState()
    val transportStatus by coordinator.activeTransport.status.collectAsState()
    val peerName by coordinator.activeTransport.connectedPeerName.collectAsState()
    val conversationHistory by coordinator.conversationHistory.collectAsState()
    val voiceStatusMessage by coordinator.voiceStatusMessage.collectAsState()
    val isSourceLocationEnabled by coordinator.isSourceLocationEnabled.collectAsState()

    var isSttTestRunning by remember { mutableStateOf(false) }
    var isTtsTestRunning by remember { mutableStateOf(false) }
    var selectedLocationDetail by remember { mutableStateOf<Pair<String, SourceLocationInfo>?>(null) }
    var copyNotice by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Slate900)
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // --- TOP HEADER BAR ---
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "VAANISETU",
                    color = CyanAccent,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 2.sp
                )
                Text(
                    text = "Offline Multilingual Voice Transceiver",
                    color = Slate400,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium
                )
            }

            IconButton(onClick = onNavigateToDiagnostics) {
                Icon(
                    imageVector = Icons.Default.BarChart,
                    contentDescription = "Diagnostics",
                    tint = CyanPrimary
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // --- STATUS & QUICK CONTROLS CHIPS ---
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Connection Status Chip
            AssistChip(
                onClick = onNavigateToConnection,
                label = {
                    Text(
                        text = if (transportStatus == TransportStatus.CONNECTED) "Connected: ${peerName ?: "Peer"}" else transportStatus.name,
                        fontSize = 11.sp
                    )
                },
                leadingIcon = {
                    Icon(
                        imageVector = if (transportStatus == TransportStatus.CONNECTED) Icons.Default.Wifi else Icons.Default.WifiOff,
                        contentDescription = null,
                        tint = if (transportStatus == TransportStatus.CONNECTED) EmeraldGreen else CrimsonEmergency,
                        modifier = Modifier.size(16.dp)
                    )
                },
                colors = AssistChipDefaults.assistChipColors(
                    containerColor = Slate800,
                    labelColor = Slate200
                ),
                modifier = Modifier.weight(1f)
            )

            // Language Selector Chip
            AssistChip(
                onClick = onNavigateToLanguage,
                label = {
                    Text(
                        text = "${selectedLanguage.displayName} (${selectedLanguage.code.uppercase()})",
                        fontSize = 11.sp
                    )
                },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Translate,
                        contentDescription = null,
                        tint = CyanPrimary,
                        modifier = Modifier.size(16.dp)
                    )
                },
                colors = AssistChipDefaults.assistChipColors(
                    containerColor = Slate800,
                    labelColor = Slate200
                ),
                modifier = Modifier.weight(1f)
            )
        }

        // --- VOICE STATUS / ERROR BANNER ---
        if (!voiceStatusMessage.isNullOrEmpty()) {
            Spacer(modifier = Modifier.height(10.dp))
            Card(
                colors = CardDefaults.cardColors(containerColor = AmberWarning.copy(alpha = 0.15f)),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = null,
                        tint = AmberWarning,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = voiceStatusMessage ?: "",
                        color = AmberWarning,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }

        if (copyNotice != null) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = copyNotice ?: "",
                color = EmeraldGreen,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        // --- MODE SWITCHER (PTT vs Hands-Free Continuous) ---
        Card(
            colors = CardDefaults.cardColors(containerColor = Slate800),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = if (mode == TransceiverMode.PUSH_TO_TALK) Icons.Default.TouchApp else Icons.Default.Hearing,
                        contentDescription = null,
                        tint = CyanAccent,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (mode == TransceiverMode.PUSH_TO_TALK) "Push-To-Talk (Walkie-Talkie)" else "Continuous (Hands-Free VAD)",
                        color = Slate200,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Switch(
                    checked = mode == TransceiverMode.CONTINUOUS,
                    onCheckedChange = { isContinuous ->
                        coordinator.setTransceiverMode(if (isContinuous) TransceiverMode.CONTINUOUS else TransceiverMode.PUSH_TO_TALK)
                    }
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // --- SOURCE LOCATION TRANSMISSION TOGGLE ---
        Card(
            colors = CardDefaults.cardColors(containerColor = Slate800),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Default.LocationOn,
                        contentDescription = null,
                        tint = if (isSourceLocationEnabled) CyanAccent else Slate500,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = "Transmit Source Location (GPS)",
                            color = Slate200,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = if (isSourceLocationEnabled) "Attaching sender coordinates (100% offline)" else "Coordinates disabled (saves battery)",
                            color = if (isSourceLocationEnabled) CyanPrimary else Slate500,
                            fontSize = 10.sp
                        )
                    }
                }
                Switch(
                    checked = isSourceLocationEnabled,
                    onCheckedChange = { enabled ->
                        coordinator.setSourceLocationEnabled(enabled)
                    }
                )
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // --- CENTRAL PTT BUTTON / VISUALIZER ---
        if (mode == TransceiverMode.PUSH_TO_TALK) {
            PttButton(
                state = state,
                rmsLevel = rmsLevel,
                onPress = { coordinator.onPttPressed() },
                onRelease = { coordinator.onPttReleased() }
            )
        } else {
            // Continuous Mode Status Indicator
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(160.dp)
                    .clip(RoundedCornerShape(80.dp))
                    .background(Slate800)
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.Hearing,
                        contentDescription = "VAD Listening",
                        tint = if (state == TransceiverState.LISTENING) EmeraldGreen else AmberWarning,
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "VAD ACTIVE",
                        color = Slate200,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Speak naturally",
                        color = Slate400,
                        fontSize = 10.sp
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // --- EMERGENCY SOS BUTTON ---
        Button(
            onClick = { coordinator.triggerEmergencySos() },
            colors = ButtonDefaults.buttonColors(containerColor = CrimsonEmergency),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
        ) {
            Icon(imageVector = Icons.Default.Warning, contentDescription = "SOS", tint = PureWhite)
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "BROADCAST EMERGENCY SOS",
                color = PureWhite,
                fontWeight = FontWeight.Black,
                letterSpacing = 1.sp
            )
        }

        Spacer(modifier = Modifier.height(20.dp))

        // --- INTERNAL DIAGNOSTIC TESTS ---
        Card(
            colors = CardDefaults.cardColors(containerColor = Slate800),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text(
                    text = "OFFLINE VOICE PIPELINE DIAGNOSTICS",
                    color = CyanAccent,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Button(
                        onClick = {
                            scope.launch {
                                isSttTestRunning = true
                                coordinator.runLocalSttTest()
                                isSttTestRunning = false
                            }
                        },
                        enabled = !isSttTestRunning && !isTtsTestRunning,
                        colors = ButtonDefaults.buttonColors(containerColor = Slate700),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            text = if (isSttTestRunning) "Listening..." else "Test STT",
                            fontSize = 11.sp,
                            color = Slate100,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Button(
                        onClick = {
                            scope.launch {
                                isTtsTestRunning = true
                                coordinator.runLocalTtsTest()
                                isTtsTestRunning = false
                            }
                        },
                        enabled = !isSttTestRunning && !isTtsTestRunning,
                        colors = ButtonDefaults.buttonColors(containerColor = Slate700),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            text = if (isTtsTestRunning) "Speaking..." else "Test TTS",
                            fontSize = 11.sp,
                            color = Slate100,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Button(
                        onClick = {
                            scope.launch {
                                isSttTestRunning = true
                                coordinator.runLocalVoiceLoopTest()
                                isSttTestRunning = false
                            }
                        },
                        enabled = !isSttTestRunning && !isTtsTestRunning,
                        colors = ButtonDefaults.buttonColors(containerColor = CyanPrimary.copy(alpha = 0.25f)),
                        modifier = Modifier.weight(1.2f)
                    ) {
                        Text(
                            text = if (isSttTestRunning) "Looping..." else "Voice Loop",
                            fontSize = 11.sp,
                            color = CyanAccent,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // --- CONVERSATION STREAM HEADER ---
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "CONVERSATION STREAM (${conversationHistory.size})",
                color = Slate400,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
            )
            if (conversationHistory.isNotEmpty()) {
                TextButton(
                    onClick = { coordinator.clearConversationHistory() },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.DeleteSweep,
                        contentDescription = "Clear Conversation",
                        tint = Slate400,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "Clear",
                        color = Slate400,
                        fontSize = 11.sp
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        if (conversationHistory.isEmpty()) {
            Card(
                colors = CardDefaults.cardColors(containerColor = Slate800.copy(alpha = 0.6f)),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = Icons.Default.Forum,
                        contentDescription = null,
                        tint = Slate600,
                        modifier = Modifier.size(32.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "No conversation messages yet.",
                        color = Slate400,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = "Hold PTT to talk or wait for peer transmissions.",
                        color = Slate600,
                        fontSize = 11.sp
                    )
                }
            }
        } else {
            val timeFormatter = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                conversationHistory.forEach { msg ->
                    val isTx = msg.direction == MessageDirection.TX
                    val isEmergency = msg.priority == MessagePriority.EMERGENCY

                    val cardBackground = when {
                        isEmergency -> CrimsonEmergency.copy(alpha = 0.2f)
                        isTx -> CyanPrimary.copy(alpha = 0.12f)
                        else -> EmeraldGreen.copy(alpha = 0.12f)
                    }

                    val headerColor = when {
                        isEmergency -> CrimsonEmergency
                        isTx -> CyanAccent
                        else -> EmeraldGreen
                    }

                    val headerLabel = when {
                        isEmergency -> if (isTx) "Emergency (You / TX)" else "Emergency (Other phone / RX)"
                        isTx -> "User (You / TX):"
                        else -> "Other phone (Peer / RX):"
                    }

                    Card(
                        colors = CardDefaults.cardColors(containerColor = cardBackground),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            // Header Row
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = if (isTx) Icons.Default.ArrowUpward else Icons.Default.ArrowDownward,
                                        contentDescription = null,
                                        tint = headerColor,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = headerLabel,
                                        color = headerColor,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = msg.languageCode.uppercase(),
                                        color = Slate400,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier
                                            .background(Slate800, RoundedCornerShape(4.dp))
                                            .padding(horizontal = 5.dp, vertical = 2.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = timeFormatter.format(Date(msg.timestamp)),
                                        color = Slate500,
                                        fontSize = 10.sp
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(6.dp))

                            // Spoken Text Content
                            Text(
                                text = msg.text,
                                color = Slate100,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold
                            )

                            // --- SOURCE LOCATION CARD ---
                            if (msg.location != null && msg.location.hasLocation) {
                                val loc = msg.location
                                Spacer(modifier = Modifier.height(8.dp))

                                val locBorderColor = when (loc.locationFreshness) {
                                    LocationFreshness.FRESH -> EmeraldGreen.copy(alpha = 0.5f)
                                    LocationFreshness.LAST_KNOWN -> AmberWarning.copy(alpha = 0.5f)
                                    LocationFreshness.UNAVAILABLE -> CrimsonEmergency.copy(alpha = 0.5f)
                                }

                                val freshnessBadgeColor = when (loc.locationFreshness) {
                                    LocationFreshness.FRESH -> EmeraldGreen
                                    LocationFreshness.LAST_KNOWN -> AmberWarning
                                    LocationFreshness.UNAVAILABLE -> CrimsonEmergency
                                }

                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(Slate900.copy(alpha = 0.7f))
                                        .border(1.dp, locBorderColor, RoundedCornerShape(8.dp))
                                        .padding(10.dp)
                                ) {
                                    Column {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Icon(
                                                    imageVector = Icons.Default.LocationOn,
                                                    contentDescription = "Source Location",
                                                    tint = CyanAccent,
                                                    modifier = Modifier.size(14.dp)
                                                )
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Text(
                                                    text = if (isTx) "YOUR SOURCE LOCATION" else "SENDER GPS LOCATION",
                                                    color = CyanAccent,
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    letterSpacing = 0.5.sp
                                                )
                                            }

                                            // Freshness Badge
                                            Text(
                                                text = loc.locationFreshness.name,
                                                color = freshnessBadgeColor,
                                                fontSize = 9.sp,
                                                fontWeight = FontWeight.Black,
                                                modifier = Modifier
                                                    .background(freshnessBadgeColor.copy(alpha = 0.15f), RoundedCornerShape(4.dp))
                                                    .padding(horizontal = 5.dp, vertical = 2.dp)
                                            )
                                        }

                                        Spacer(modifier = Modifier.height(4.dp))

                                        // Coordinates Display
                                        Text(
                                            text = loc.formatCoordinates(),
                                            color = Slate100,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Bold,
                                            fontFamily = FontFamily.Monospace
                                        )

                                        Spacer(modifier = Modifier.height(4.dp))

                                        // Metadata Badges (Accuracy, Age, Source)
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                                Text(
                                                    text = "Acc: ${loc.formatAccuracy()}",
                                                    color = Slate400,
                                                    fontSize = 10.sp
                                                )
                                                Text(
                                                    text = "•",
                                                    color = Slate600,
                                                    fontSize = 10.sp
                                                )
                                                val ageSec = loc.getAgeSeconds(msg.timestamp)
                                                Text(
                                                    text = if (ageSec >= 0) "${ageSec}s old" else "Live",
                                                    color = Slate400,
                                                    fontSize = 10.sp
                                                )
                                                Text(
                                                    text = "•",
                                                    color = Slate600,
                                                    fontSize = 10.sp
                                                )
                                                Text(
                                                    text = loc.locationSource,
                                                    color = Slate400,
                                                    fontSize = 10.sp
                                                )
                                            }

                                            // Action Buttons
                                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                                // Copy Coordinates Button
                                                IconButton(
                                                    onClick = {
                                                        val rawCoords = loc.formatRawCoordinates()
                                                        clipboardManager.setText(AnnotatedString(rawCoords))
                                                        copyNotice = "Copied to clipboard: $rawCoords"
                                                    },
                                                    modifier = Modifier.size(24.dp)
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Default.ContentCopy,
                                                        contentDescription = "Copy Coordinates",
                                                        tint = CyanPrimary,
                                                        modifier = Modifier.size(14.dp)
                                                    )
                                                }

                                                // View Details Button
                                                IconButton(
                                                    onClick = {
                                                        selectedLocationDetail = Pair(
                                                            if (isTx) "Your Device Location" else "Sender Device Location",
                                                            loc
                                                        )
                                                    },
                                                    modifier = Modifier.size(24.dp)
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Default.OpenInNew,
                                                        contentDescription = "View Details",
                                                        tint = Slate300,
                                                        modifier = Modifier.size(14.dp)
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // --- LOCATION DETAIL MODAL DIALOG ---
    if (selectedLocationDetail != null) {
        val (title, loc) = selectedLocationDetail!!
        AlertDialog(
            onDismissRequest = { selectedLocationDetail = null },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.LocationOn,
                        contentDescription = null,
                        tint = CyanAccent,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = title, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Slate100)
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "GENUINE GPS/GNSS FIX (100% OFFLINE)",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Black,
                        color = CyanPrimary
                    )

                    Divider(color = Slate700)

                    LocationDetailRow("Formatted Coords", loc.formatCoordinates())
                    LocationDetailRow("Raw Decimal", loc.formatRawCoordinates(), isMono = true)
                    LocationDetailRow("Latitude", String.format(Locale.US, "%.7f", loc.latitude), isMono = true)
                    LocationDetailRow("Longitude", String.format(Locale.US, "%.7f", loc.longitude), isMono = true)
                    LocationDetailRow("Accuracy", loc.formatAccuracy())
                    LocationDetailRow("Altitude", if (loc.altitudeMeters != 0.0) String.format(Locale.US, "%.1fm", loc.altitudeMeters) else "N/A")
                    LocationDetailRow("Fix Freshness", loc.locationFreshness.name)
                    LocationDetailRow("Provider / Source", loc.locationSource)
                    LocationDetailRow("Acquisition Time", loc.formatTimestamp())
                    if (!loc.address.isNullOrBlank()) {
                        LocationDetailRow("Offline Address", loc.address)
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val raw = loc.formatRawCoordinates()
                        clipboardManager.setText(AnnotatedString(raw))
                        copyNotice = "Copied: $raw"
                        selectedLocationDetail = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = CyanPrimary)
                ) {
                    Icon(imageVector = Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Copy Coordinates", color = Slate900, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { selectedLocationDetail = null }) {
                    Text("Close", color = Slate400)
                }
            },
            containerColor = Slate800,
            shape = RoundedCornerShape(16.dp)
        )
    }
}

@Composable
private fun LocationDetailRow(label: String, value: String, isMono: Boolean = false) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, color = Slate400, fontSize = 12.sp)
        Text(
            text = value,
            color = Slate200,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = if (isMono) FontFamily.Monospace else FontFamily.Default
        )
    }
}
