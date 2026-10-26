package org.vaanisetu.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.vaanisetu.app.data.model.LanguageRegistry
import org.vaanisetu.app.data.model.ModelStatus
import org.vaanisetu.app.data.model.SupportedLanguage
import org.vaanisetu.app.state.TransceiverCoordinator
import org.vaanisetu.app.ui.theme.*

@Composable
fun LanguageScreen(
    coordinator: TransceiverCoordinator,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    val selectedLanguage by coordinator.modelManager.selectedLanguage.collectAsState()
    val languages = LanguageRegistry.languages

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Slate900)
            .padding(16.dp)
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
            Column {
                Text(
                    text = "10-Language Neural Hub",
                    color = Slate200,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Single-active-model memory management",
                    color = Slate400,
                    fontSize = 12.sp
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            items(languages) { lang ->
                val isSelected = lang.code.equals(selectedLanguage.code, ignoreCase = true)

                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = if (isSelected) Slate800 else Slate800.copy(alpha = 0.6f)
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(
                            if (isSelected) Modifier.border(2.dp, CyanPrimary, RoundedCornerShape(12.dp))
                            else Modifier
                        )
                        .clickable {
                            scope.launch {
                                coordinator.modelManager.selectLanguage(lang.code)
                            }
                        }
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = lang.displayName,
                                    color = if (isSelected) CyanAccent else Slate200,
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "(${lang.nativeName})",
                                    color = Slate400,
                                    fontSize = 13.sp
                                )
                            }
                            Spacer(modifier = Modifier.height(6.dp))

                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                // STT Status Badge
                                StatusBadge(label = "STT", status = lang.sttStatus)
                                // TTS Status Badge
                                StatusBadge(label = "TTS", status = lang.ttsStatus)
                            }
                        }

                        if (isSelected) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = "Selected",
                                tint = CyanAccent,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun StatusBadge(label: String, status: ModelStatus) {
    val (badgeBg, badgeText) = when (status) {
        ModelStatus.VERIFIED -> EmeraldGreen.copy(alpha = 0.2f) to EmeraldGreen
        ModelStatus.NEEDS_VALIDATION -> AmberWarning.copy(alpha = 0.2f) to AmberWarning
        ModelStatus.LOADING -> CyanPrimary.copy(alpha = 0.2f) to CyanPrimary
        else -> Slate600.copy(alpha = 0.2f) to Slate400
    }

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(badgeBg)
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(
            text = "$label: ${status.name}",
            color = badgeText,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold
        )
    }
}
