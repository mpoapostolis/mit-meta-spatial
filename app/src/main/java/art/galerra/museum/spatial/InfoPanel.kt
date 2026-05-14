package art.galerra.museum.spatial

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
 * Shared state for the floating info panel. The activity mutates this on hotspot click; the
 * Compose tree below observes it and re-renders. Kept as a module-level `mutableStateOf` so the
 * panel registration's `composeViewCreator` lambda can read it without extra plumbing.
 */
data class InfoPanelState(
    val title: String = "",
    val description: String = "",
    val visible: Boolean = false,
)

val infoPanelState = mutableStateOf(InfoPanelState())

/** Invoked when the user clicks the close button or the panel background. */
var infoPanelOnDismiss: () -> Unit = {}

private val PanelBackground = Color(0xCC0E0A06) // dark translucent
private val GoldAccent = Color(0xFFD4AF37)
private val BodyText = Color(0xFFEDE7DA)
private val Divider = Color(0x55D4AF37)

@Composable
fun InfoPanel() {
    val state = infoPanelState.value

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color.Transparent,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp)
                .clip(RoundedCornerShape(28.dp))
                .background(PanelBackground),
        ) {
            // Gold corner accent strip down the left edge.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(start = 0.dp),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 48.dp, vertical = 40.dp),
                ) {
                    Text(
                        text = state.title.ifBlank { "Untitled exhibit" },
                        color = BodyText,
                        fontFamily = FontFamily.Serif,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 44.sp,
                        lineHeight = 52.sp,
                    )
                    Spacer(modifier = Modifier.size(12.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(2.dp)
                            .background(Divider),
                    )
                    Spacer(modifier = Modifier.size(20.dp))
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        Text(
                            text = state.description.ifBlank { "No description provided for this exhibit." },
                            color = BodyText,
                            fontFamily = FontFamily.Default,
                            fontSize = 22.sp,
                            lineHeight = 32.sp,
                        )
                    }
                    Spacer(modifier = Modifier.size(20.dp))
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.CenterEnd,
                    ) {
                        Button(
                            onClick = { infoPanelOnDismiss() },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = GoldAccent,
                                contentColor = Color(0xFF1A130A),
                            ),
                            shape = RoundedCornerShape(14.dp),
                            contentPadding = PaddingValues(horizontal = 28.dp, vertical = 14.dp),
                        ) {
                            Text(
                                text = "Close",
                                fontSize = 20.sp,
                                fontWeight = FontWeight.SemiBold,
                                fontFamily = FontFamily.SansSerif,
                            )
                        }
                    }
                }
            }
        }
    }
}
