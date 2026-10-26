package org.vaanisetu.app.ui.components

import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.vaanisetu.app.data.model.TransceiverState
import org.vaanisetu.app.ui.theme.*

@Composable
fun PttButton(
    state: TransceiverState,
    rmsLevel: Float,
    onPress: () -> Unit,
    onRelease: () -> Unit,
    modifier: Modifier = Modifier
) {
    val view = LocalView.current
    val isListening = state == TransceiverState.LISTENING

    // Pulsing animation when listening
    val infiniteTransition = rememberInfiniteTransition(label = "ptt_pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_scale"
    )

    val currentScale = if (isListening) pulseScale + (rmsLevel * 0.2f) else 1.0f

    val buttonColor = when (state) {
        TransceiverState.LISTENING -> CrimsonEmergency
        TransceiverState.PROCESSING, TransceiverState.SENDING -> AmberWarning
        TransceiverState.RECEIVING, TransceiverState.SYNTHESIZING, TransceiverState.PLAYING -> EmeraldGreen
        else -> CyanPrimary
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(200.dp)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onPress = {
                            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                            onPress()
                            tryAwaitRelease()
                            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                            onRelease()
                        }
                    )
                }
        ) {
            // Pulsing Halo
            if (isListening) {
                Box(
                    modifier = Modifier
                        .size(190.dp)
                        .scale(currentScale)
                        .clip(CircleShape)
                        .background(buttonColor.copy(alpha = 0.25f))
                )
            }

            // Main PTT Circle
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(150.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.radialGradient(
                            colors = listOf(buttonColor, buttonColor.copy(alpha = 0.8f))
                        )
                    )
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = if (isListening) Icons.Default.Mic else Icons.Default.MicOff,
                        contentDescription = "PTT Button",
                        tint = Slate900,
                        modifier = Modifier.size(54.dp)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = if (isListening) "TRANSMITTING" else "HOLD TO TALK",
                        color = Slate900,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Black
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "State: ${state.name}",
            color = Slate400,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium
        )
    }
}
