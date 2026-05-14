package art.galerra.museum.spatial

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Shared state for the floating mute-toggle button. The activity flips [muteButtonState] when
 * audio is muted/unmuted; the Compose tree below observes it and re-renders the speaker glyph.
 * Click bubbles up through [muteButtonOnClick] which the activity wires to `toggleAudio()`.
 */
val muteButtonState = mutableStateOf(false)

/** Invoked when the user clicks the mute button. Activity wires this to `toggleAudio()`. */
var muteButtonOnClick: () -> Unit = {}

private val ButtonBackground = Color(0xCC0E0A06) // dark translucent (matches InfoPanel)
private val GoldAccent = Color(0xFFD4AF37)

@Composable
fun MuteButtonPanel() {
    val muted = muteButtonState.value

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color.Transparent,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(8.dp),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(CircleShape)
                    .background(ButtonBackground)
                    .clickable { muteButtonOnClick() },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (muted) "🔇" else "🔊", // 🔇 / 🔊
                    color = GoldAccent,
                    fontFamily = FontFamily.Default,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 64.sp,
                )
            }
        }
    }
}
