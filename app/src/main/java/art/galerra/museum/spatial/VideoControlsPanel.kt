package art.galerra.museum.spatial

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
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
 * Shared state for the in-scene video controls bar that shows when the user clicks a video
 * plane. The activity owns the active ExoPlayer reference and updates this state at ~30 Hz
 * via a Handler poll loop (see [ImmersiveActivity.pollVideoStateRunnable]).
 *
 * Mirrors the PremiumMediaSample's `MediaState`. `progressSec` and `durationSec` are kept here
 * for the time-label display; we no longer expose a scrub slider (Material3 Slider was
 * unreliable inside the 3D-panel render path — user reported "modal video kanto kalutero"
 * twice → simplified UX to three big tap buttons + a time readout).
 */
data class VideoControlsState(
    val visible: Boolean = false,
    val isPlaying: Boolean = false,
    val isMuted: Boolean = false,
    val progressSec: Float = 0f,
    val durationSec: Float = 0f,
    val title: String = "",
)

val videoControlsState = mutableStateOf(VideoControlsState())

/** Toggle play/pause; receives the *intended* new state (true = play, false = pause). */
var videoControlsOnPlayPause: (Boolean) -> Unit = {}
/** Seek relative — positive forward, negative backward. Used by +10s / -10s nav buttons. */
var videoControlsOnSeekRelative: (Float) -> Unit = {}
/** Toggle mute on the *active* video player only (not the global mute bus). */
var videoControlsOnMute: (Boolean) -> Unit = {}
/** Close the controls + restore the video plane to its original pose/scale. */
var videoControlsOnClose: () -> Unit = {}

private val GoldAccent = Color(0xFFD4AF37)
private val GoldDim = Color(0x66D4AF37)
private val PanelBg = Color(0xEE0E0A06)
private val OnPanel = Color(0xFFF5EFE6)
private val OnPanelDim = Color(0xCCBDB3A6)

/**
 * Simplified in-scene controls — five chunky pill buttons in one row + a time/title strip.
 * Sized for VR-finger / controller-ray accuracy on Quest 2 (~24 dp font, 56 dp pill height).
 *
 * Layout:
 *   ┌────────────────────────────────────────────────────────────────┐
 *   │  TITLE                                  00:42 / 03:15           │
 *   │  ┌──────┐ ┌──────┐ ┌────────┐ ┌──────┐ ┌──────┐                │
 *   │  │ −10s │ │ MUTE │ │ PLAY ► │ │ +10s │ │ CLOSE│                │
 *   │  └──────┘ └──────┘ └────────┘ └──────┘ └──────┘                │
 *   └────────────────────────────────────────────────────────────────┘
 *
 * No Material3 Slider — replaced with -10s / +10s pills which call
 * [videoControlsOnSeekRelative]. Simpler interaction, no jitter, accurate on controller rays.
 */
@Composable
fun VideoControlsPanel() {
    val state = videoControlsState.value
    MaterialTheme {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(20.dp))
                .background(PanelBg)
                .border(2.dp, GoldDim, RoundedCornerShape(20.dp)),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 24.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // Title row + time readout (left = current, right = total).
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = state.title.ifBlank { "Now playing" },
                        color = OnPanel,
                        fontFamily = FontFamily.Serif,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 18.sp,
                        maxLines = 1,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = "${formatTime(state.progressSec)} / ${formatTime(state.durationSec)}",
                        color = OnPanelDim,
                        fontSize = 14.sp,
                    )
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp)
                        .background(GoldDim),
                )

                // Pill button row — three controls: mute, play/pause, close. The -10s / +10s
                // seek buttons were removed per user feedback ("vgale ta -10 +10 buttons apo to
                // mpourdelo to video modal") — they were unnecessary clutter for the museum's
                // mostly-short-clip videos. Now the row has more breathing room and each pill
                // is easier to hit with the controller ray.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PillButton(
                        label = if (state.isMuted) "Unmute" else "Mute",
                        onClick = { videoControlsOnMute(!state.isMuted) },
                    )
                    // Play/pause — primary (gold-filled).
                    PillButton(
                        label = if (state.isPlaying) "Pause" else "Play ▸",
                        emphasized = true,
                        onClick = { videoControlsOnPlayPause(!state.isPlaying) },
                    )
                    PillButton(label = "✕ Close", onClick = { videoControlsOnClose() })
                }
                Spacer(modifier = Modifier.size(4.dp))
            }
        }
    }
}

/** Pill-shaped button mirroring InfoPanel.kt's close-button styling. */
@Composable
private fun PillButton(label: String, emphasized: Boolean = false, onClick: () -> Unit) {
    val bg = if (emphasized) GoldAccent else Color(0x331A1208)
    val fg = if (emphasized) Color(0xFF1A1208) else GoldAccent
    val borderColor = if (emphasized) GoldAccent else GoldDim
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(bg)
            .border(1.dp, borderColor, RoundedCornerShape(12.dp))
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(
            text = label,
            color = fg,
            fontWeight = FontWeight.SemiBold,
            fontSize = 16.sp,
        )
    }
}

/** Format a duration in seconds as `mm:ss` (or `h:mm:ss` past one hour). */
private fun formatTime(secs: Float): String {
    if (secs.isNaN() || secs < 0f) return "0:00"
    val total = secs.toInt()
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
