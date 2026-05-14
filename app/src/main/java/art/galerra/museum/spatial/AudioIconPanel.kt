package art.galerra.museum.spatial

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Per-audio-source mute icon — small disc pinned in 3D to the audio emitter's pose so the user
 * can SEE where each sound is coming from and silence individual sources without nuking the
 * global bus. Plain Material3 — same simplification rationale as InfoPanel.kt.
 */

/** Per-source mute state. true = muted. Keyed by the dynamic audio key generated in spawnAudio. */
val audioIconMuteStates: MutableMap<String, MutableState<Boolean>> = mutableMapOf()

/** Per-source click handlers — invoked when the user taps a specific in-scene audio icon. */
val audioIconOnClick: MutableMap<String, () -> Unit> = mutableMapOf()

private val IconBackgroundActive = Color(0xCC0E0A06)
private val IconBackgroundMuted = Color(0xCC2A0A0A)
private val GoldAccent = Color(0xFFD4AF37)
private val GoldDim = Color(0x66D4AF37)
private val MutedAccent = Color(0xFFD47A37)

@Composable
fun AudioIconPanel(audioKey: String) {
    // Bind to (or lazily create) this instance's observable state. The map is populated up-front
    // in spawnAudio, but defensively create-on-miss so a recomposition before the activity
    // finishes wiring doesn't crash.
    val muteState = audioIconMuteStates.getOrPut(audioKey) { mutableStateOf(false) }
    val muted = muteState.value
    val ring = if (muted) MutedAccent else GoldAccent
    val ringSoft = if (muted) Color(0x66D47A37) else GoldDim
    val surface = if (muted) IconBackgroundMuted else IconBackgroundActive

    // Subtle pulse while playing — heartbeat at ~0.9 Hz. Always running (can't conditionally
    // compose infinite transitions without flicker); when muted we ignore its output.
    val transition = rememberInfiniteTransition(label = "audio_pulse")
    val pulseScale by transition.animateFloat(
        initialValue = 0.92f,
        targetValue = 1.06f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1100),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "audio_pulse_scale",
    )
    val pulse = if (muted) 1f else pulseScale

    MaterialTheme {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(4.dp),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .scale(pulse)
                    .clip(CircleShape)
                    .border(2.dp, ringSoft, CircleShape)
                    .padding(3.dp),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(CircleShape)
                        .background(surface)
                        .border(2.dp, ring, CircleShape)
                        .clickable { audioIconOnClick[audioKey]?.invoke() },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = if (muted) "x" else "*",
                        color = ring,
                        fontWeight = FontWeight.Bold,
                        fontSize = 28.sp,
                    )
                }
            }
        }
    }
}
