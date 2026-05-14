package art.galerra.museum.spatial

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import com.meta.spatial.uiset.button.PrimaryButton
import com.meta.spatial.uiset.theme.LocalColorScheme
import com.meta.spatial.uiset.theme.LocalShapes
import com.meta.spatial.uiset.theme.SpatialTheme
import com.meta.spatial.uiset.theme.darkSpatialColorScheme

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

private val GoldAccent = Color(0xFFD4AF37)
private val GoldDim = Color(0x66D4AF37)

@Composable
fun InfoPanel() {
    SpatialTheme(colorScheme = darkSpatialColorScheme()) {
        val state = infoPanelState.value

        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(LocalShapes.current.large)
                .background(brush = LocalColorScheme.current.panel),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 48.dp, vertical = 40.dp),
            ) {
                // Uppercase eyebrow with star ornament.
                Text(
                    text = "✦  ΕΚΘΕΜΑ",
                    style = SpatialTheme.typography.body2Strong.copy(
                        color = GoldAccent,
                        letterSpacing = 4.sp,
                        fontWeight = FontWeight.Bold,
                    ),
                )
                Spacer(modifier = Modifier.size(10.dp))

                // Headline.
                Text(
                    text = state.title.ifBlank { "Untitled exhibit" },
                    style = SpatialTheme.typography.headline1Strong.copy(
                        color = SpatialTheme.colorScheme.primaryAlphaBackground,
                        fontFamily = FontFamily.Serif,
                        fontWeight = FontWeight.SemiBold,
                    ),
                )
                Spacer(modifier = Modifier.size(14.dp))

                // Gold divider rule.
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp)
                        .background(GoldDim),
                )
                Spacer(modifier = Modifier.size(22.dp))

                // Scrollable description body.
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                ) {
                    Text(
                        text = state.description.ifBlank {
                            "No description provided for this exhibit."
                        },
                        style = SpatialTheme.typography.body1.copy(
                            color = SpatialTheme.colorScheme.primaryAlphaBackground,
                        ),
                    )
                }

                Spacer(modifier = Modifier.size(24.dp))

                // Primary close action, right-aligned.
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.CenterEnd,
                ) {
                    PrimaryButton(
                        label = "Κλείσιμο",
                        onClick = { infoPanelOnDismiss() },
                    )
                }
            }
        }
    }
}
