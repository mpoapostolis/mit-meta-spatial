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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.meta.spatial.uiset.theme.SpatialTheme
import com.meta.spatial.uiset.theme.darkSpatialColorScheme
import com.meta.spatial.uiset.theme.icons.SpatialIcons
import com.meta.spatial.uiset.theme.icons.regular.VolumeOff
import com.meta.spatial.uiset.theme.icons.regular.VolumeOn

/**
 * Per-audio-source mute icon. Pinned in 3D to the audio emitter's world pose so the user can
 * see WHERE each sound is coming from and silence individual sources without nuking the global
 * audio bus (that's what [MuteButtonPanel] is for).
 *
 * Unlike the floating [MuteButtonPanel] which uses a single global state, each audio source
 * needs its own observable state + click handler. We index those by `audioKey` (the per-instance
 * id we generate in [ImmersiveActivity.spawnAudio]), and the Compose function reads from the
 * lookup map on every recomposition.
 *
 * The disc gently pulses while audio plays (visual cue that something is making noise even when
 * spatialized far away) and freezes when muted.
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
    SpatialTheme(colorScheme = darkSpatialColorScheme()) {
        // Bind to (or lazily create) this instance's observable state. The map is populated
        // up-front in spawnAudio, but defensively create-on-miss so a recomposition before the
        // activity finishes wiring doesn't crash.
        val muteState = audioIconMuteStates.getOrPut(audioKey) { mutableStateOf(false) }
        val muted = muteState.value
        val ring = if (muted) MutedAccent else GoldAccent
        val ringSoft = if (muted) Color(0x66D47A37) else GoldDim
        val surface = if (muted) IconBackgroundMuted else IconBackgroundActive

        // Subtle pulse while playing — a heartbeat at ~0.9 Hz signals "audio active". The
        // pulse animation is always running (infinite transitions can't be conditionally
        // composed without flickering), but when muted we ignore its output and lock the
        // scale at 1f so a muted disc reads as visually static.
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
                    Icon(
                        imageVector = if (muted) {
                            SpatialIcons.Regular.VolumeOff
                        } else {
                            SpatialIcons.Regular.VolumeOn
                        },
                        contentDescription = if (muted) {
                            "Unmute this audio source"
                        } else {
                            "Mute this audio source"
                        },
                        tint = ring,
                        modifier = Modifier.size(42.dp),
                    )
                }
            }
        }
    }
}
