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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
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

private val GoldAccent = Color(0xFFD4AF37)
private val GoldDim = Color(0x66D4AF37)
private val GoldFaint = Color(0x33D4AF37)
private val PanelBg = Color(0xEE0E0A06)
private val CardBg = Color(0xCC1A130A)
private val OnPanel = Color(0xFFF5EFE6)
private val OnPanelDim = Color(0xCCBDB3A6)

@Composable
fun WelcomePanel() {
    val state = welcomeState.value
    MaterialTheme {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(24.dp))
                .background(PanelBg)
                .border(2.dp, GoldDim, RoundedCornerShape(24.dp)),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 32.dp, vertical = 28.dp),
            ) {
                Text(
                    text = "Welcome to the Virtual Museum",
                    color = GoldAccent,
                    fontFamily = FontFamily.Serif,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 26.sp,
                )
                Spacer(modifier = Modifier.size(10.dp))
                Text(
                    text = "Pick an exhibition to enter",
                    color = OnPanelDim,
                    fontSize = 15.sp,
                )
                Spacer(modifier = Modifier.size(14.dp))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp)
                        .background(GoldDim),
                )
                Spacer(modifier = Modifier.size(18.dp))

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
        Spacer(modifier = Modifier.size(16.dp))
        Text(
            text = "Loading exhibitions...",
            color = OnPanelDim,
            fontSize = 15.sp,
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
        Text(
            text = "No exhibitions found",
            color = OnPanelDim,
            fontSize = 16.sp,
        )
    }
}

@Composable
private fun SceneList(scenes: List<SceneRecord>) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(scenes, key = { it.id }) { scene ->
            SceneCard(scene)
        }
    }
}

@Composable
private fun SceneCard(scene: SceneRecord) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(CardBg)
            .border(1.dp, GoldFaint, RoundedCornerShape(12.dp))
            .clickable { welcomeOnPick(scene.id) }
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = scene.name.ifBlank { "Untitled scene" },
                color = GoldAccent,
                fontFamily = FontFamily.Serif,
                fontWeight = FontWeight.SemiBold,
                fontSize = 18.sp,
            )
            if (scene.description.isNotBlank()) {
                Spacer(modifier = Modifier.size(4.dp))
                Text(
                    text = scene.description,
                    color = OnPanelDim,
                    fontSize = 13.sp,
                    maxLines = 2,
                )
            }
        }
        Spacer(modifier = Modifier.width(16.dp))
        Text(
            text = ">",
            color = GoldAccent,
            fontWeight = FontWeight.Bold,
            fontSize = 22.sp,
        )
    }
}
