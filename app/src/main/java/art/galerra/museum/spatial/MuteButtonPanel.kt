package art.galerra.museum.spatial

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Shared state for the floating mute-toggle button. The activity flips [muteButtonState] when
 * audio is muted/unmuted; the Compose tree observes it and re-renders the speaker glyph.
 * Click bubbles up through [muteButtonOnClick] which the activity wires to `toggleAudio()`.
 */
val muteButtonState = mutableStateOf(false)

/** Invoked when the user clicks the mute button. Activity wires this to `toggleAudio()`. */
var muteButtonOnClick: () -> Unit = {}

// Translucent dark surface keeps the button visually consistent with the other museum panels.
private val ButtonBackgroundActive = Color(0xCC0E0A06)
private val ButtonBackgroundMuted = Color(0xCC2A0A0A) // subtle red-tinted dark when muted
private val GoldAccent = Color(0xFFD4AF37)
private val GoldDim = Color(0x66D4AF37)
private val MutedAccent = Color(0xFFD47A37) // warm rust for muted state

/**
 * Plain Material3 — same simplification as InfoPanel.kt. The uiset Icon/VolumeOn glyph has
 * been replaced with a literal "ON" / "MUTED" text label since icon rendering inside a tiny
 * (0.18 m) Compose panel often fails to find the vector asset at runtime on Quest.
 */
@Composable
fun MuteButtonPanel() {
    val muted = muteButtonState.value
    val ring = if (muted) MutedAccent else GoldAccent
    val ringSoft = if (muted) Color(0x66D47A37) else GoldDim
    val surface = if (muted) ButtonBackgroundMuted else ButtonBackgroundActive

    MaterialTheme {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(6.dp),
            contentAlignment = Alignment.Center,
        ) {
            // Outer faint glow ring.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(CircleShape)
                    .border(2.dp, ringSoft, CircleShape)
                    .padding(4.dp),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(CircleShape)
                        .background(surface)
                        .border(2.dp, ring, CircleShape)
                        .clickable { muteButtonOnClick() },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = if (muted) "MUTED" else "ON",
                        color = ring,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                    )
                }
            }
        }
    }
}
