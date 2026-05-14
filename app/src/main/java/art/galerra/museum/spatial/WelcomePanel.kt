package art.galerra.museum.spatial

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.mutableStateOf
import com.meta.spatial.uiset.theme.LocalColorScheme
import com.meta.spatial.uiset.theme.LocalShapes
import com.meta.spatial.uiset.theme.SpatialTheme
import com.meta.spatial.uiset.theme.darkSpatialColorScheme
import com.meta.spatial.uiset.theme.icons.SpatialIcons
import com.meta.spatial.uiset.theme.icons.regular.ArrowRight
import com.meta.spatial.uiset.theme.icons.regular.CategoryAll

/**
 * Shared state for the welcome / scene-picker panel. The activity populates [welcomeState]
 * after [PocketBaseClient.loadScenes] returns; the Compose tree re-renders to show the list
 * of buttons. Click bubbles up through [welcomeOnPick] which the activity wires to set
 * `exhibitionId` + trigger `loadExhibition()` and hide this panel.
 */
data class WelcomeState(
    val scenes: List<SceneRecord> = emptyList(),
    val loading: Boolean = true,
    val visible: Boolean = true,
)

val welcomeState = mutableStateOf(WelcomeState())

/** Invoked when the user picks a scene from the list. Activity wires this to a loader. */
var welcomeOnPick: (String) -> Unit = {}

// Gold accent reused across all three panels — the only non-token colour we keep, to give
// the museum its house style flourish over Meta's neutral SpatialTheme palette.
private val GoldAccent = Color(0xFFD4AF37)
private val GoldDim = Color(0x66D4AF37)
private val GoldFaint = Color(0x33D4AF37)

@Composable
fun WelcomePanel() {
    SpatialTheme(colorScheme = darkSpatialColorScheme()) {
        val state = welcomeState.value

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
                // Title + subtitle block.
                Text(
                    text = "Καλώς ήρθες στο Εικονικό Μουσείο",
                    style = SpatialTheme.typography.headline1Strong.copy(
                        color = GoldAccent,
                        fontFamily = FontFamily.Serif,
                        fontWeight = FontWeight.SemiBold,
                    ),
                )
                Spacer(modifier = Modifier.size(12.dp))
                Text(
                    text = "Διάλεξε έκθεση για να μπεις",
                    style = SpatialTheme.typography.body1.copy(
                        color = SpatialTheme.colorScheme.primaryAlphaBackground,
                    ),
                )
                Spacer(modifier = Modifier.size(20.dp))

                // Gold divider rule.
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp)
                        .background(GoldDim),
                )
                Spacer(modifier = Modifier.size(28.dp))

                // Content swaps between loading / empty / list states.
                Box(modifier = Modifier.fillMaxSize()) {
                    when {
                        state.loading -> LoadingState()
                        state.scenes.isEmpty() -> EmptyState()
                        else -> SceneList(state.scenes)
                    }
                }
            }
        }
    }
}

@Composable
private fun LoadingState() {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator(
            color = GoldAccent,
            strokeWidth = 4.dp,
            modifier = Modifier.size(48.dp),
        )
        Spacer(modifier = Modifier.size(24.dp))
        Text(
            text = "Φόρτωση εκθέσεων...",
            style = SpatialTheme.typography.body1.copy(
                color = SpatialTheme.colorScheme.primaryAlphaBackground,
            ),
        )
    }
}

@Composable
private fun EmptyState() {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = SpatialIcons.Regular.CategoryAll,
            contentDescription = null,
            tint = GoldDim,
            modifier = Modifier.size(48.dp),
        )
        Spacer(modifier = Modifier.size(16.dp))
        Text(
            text = "Δεν βρέθηκαν εκθέσεις",
            style = SpatialTheme.typography.body1.copy(
                color = SpatialTheme.colorScheme.primaryAlphaBackground,
            ),
        )
    }
}

@Composable
private fun SceneList(scenes: List<SceneRecord>) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = PaddingValues(vertical = 4.dp),
    ) {
        items(scenes, key = { it.id }) { scene ->
            SceneCard(scene)
        }
    }
}

/**
 * One row in the scene picker — a two-line card with a trailing gold arrow. uiset
 * [SecondaryButton] only supports a single label/leading/trailing layout, so we hand-paint
 * the card using SpatialTheme tokens for the surface + typography + shape, and a gold
 * border ring for the museum accent.
 */
@Composable
private fun SceneCard(scene: SceneRecord) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(SpatialTheme.shapes.medium)
            .background(SpatialTheme.colorScheme.secondaryButton)
            .border(1.dp, GoldFaint, SpatialTheme.shapes.medium)
            .clickable { welcomeOnPick(scene.id) }
            .padding(horizontal = 24.dp, vertical = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = scene.name.ifBlank { "Untitled scene" },
                style = SpatialTheme.typography.headline3Strong.copy(
                    color = GoldAccent,
                    fontFamily = FontFamily.Serif,
                    fontWeight = FontWeight.SemiBold,
                ),
            )
            if (scene.description.isNotBlank()) {
                Spacer(modifier = Modifier.size(6.dp))
                Text(
                    text = scene.description,
                    style = SpatialTheme.typography.body2.copy(
                        color = SpatialTheme.colorScheme.primaryAlphaBackground,
                    ),
                    maxLines = 2,
                )
            }
        }
        Spacer(modifier = Modifier.width(20.dp))
        Icon(
            imageVector = SpatialIcons.Regular.ArrowRight,
            contentDescription = null,
            tint = GoldAccent,
            modifier = Modifier.size(28.dp),
        )
    }
}
