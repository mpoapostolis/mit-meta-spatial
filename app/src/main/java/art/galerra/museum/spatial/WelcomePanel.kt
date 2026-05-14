package art.galerra.museum.spatial

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
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

private val PanelBackground = Color(0xCC0E0A06) // dark translucent — matches InfoPanel
private val GoldAccent = Color(0xFFD4AF37)
private val GoldDim = Color(0x55D4AF37)
private val BodyText = Color(0xFFEDE7DA)
private val SubtleText = Color(0xCCEDE7DA)
private val ButtonBg = Color(0xFF1A130A)

@Composable
fun WelcomePanel() {
    val state = welcomeState.value

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color.Transparent,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp)
                .clip(RoundedCornerShape(28.dp))
                .background(PanelBackground),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 40.dp, vertical = 32.dp),
            ) {
                Text(
                    text = "Καλώς ήρθες — Εικονικό Μουσείο",
                    color = GoldAccent,
                    fontFamily = FontFamily.Serif,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 38.sp,
                    lineHeight = 46.sp,
                )
                Spacer(modifier = Modifier.size(10.dp))
                Text(
                    text = "Διάλεξε έκθεση",
                    color = SubtleText,
                    fontFamily = FontFamily.Default,
                    fontSize = 20.sp,
                )
                Spacer(modifier = Modifier.size(14.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp)
                        .background(GoldDim),
                )
                Spacer(modifier = Modifier.size(18.dp))

                when {
                    state.loading -> {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator(
                                color = GoldAccent,
                                strokeWidth = 4.dp,
                            )
                        }
                    }
                    state.scenes.isEmpty() -> {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = "Δεν βρέθηκαν εκθέσεις",
                                color = SubtleText,
                                fontSize = 18.sp,
                            )
                        }
                    }
                    else -> {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            contentPadding = PaddingValues(vertical = 4.dp),
                        ) {
                            items(state.scenes, key = { it.id }) { scene ->
                                SceneButton(scene)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SceneButton(scene: SceneRecord) {
    Button(
        onClick = { welcomeOnPick(scene.id) },
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, GoldDim, RoundedCornerShape(14.dp)),
        colors = ButtonDefaults.buttonColors(
            containerColor = ButtonBg,
            contentColor = BodyText,
        ),
        shape = RoundedCornerShape(14.dp),
        contentPadding = PaddingValues(horizontal = 22.dp, vertical = 16.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = scene.name.ifBlank { "Untitled scene" },
                color = GoldAccent,
                fontFamily = FontFamily.Serif,
                fontWeight = FontWeight.SemiBold,
                fontSize = 22.sp,
                lineHeight = 28.sp,
            )
            if (scene.description.isNotBlank()) {
                Spacer(modifier = Modifier.size(4.dp))
                Text(
                    text = scene.description,
                    color = SubtleText,
                    fontFamily = FontFamily.Default,
                    fontSize = 15.sp,
                    lineHeight = 20.sp,
                    maxLines = 2,
                )
            }
        }
    }
}
