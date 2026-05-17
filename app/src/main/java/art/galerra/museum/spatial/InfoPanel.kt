package art.galerra.museum.spatial

import android.graphics.Bitmap
import android.util.Log
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Shared state for the floating info modal. The activity mutates this on hotspot click; the
 * Compose tree observes it and re-renders. Kept as a module-level `mutableStateOf` so the
 * panel registration's `composeViewCreator` lambda can read it without extra plumbing.
 *
 * `imageBitmap` is optional — populated only when the source asset is an image and the bitmap
 * has already been downloaded by [ImmersiveActivity.loadImageTexture]. Video assets render a
 * caption-style modal without preview thumbnail (the video plane itself is moved in front of
 * the player; see [ImmersiveActivity.showModalFor]).
 */
data class InfoPanelState(
    val title: String = "",
    val description: String = "",
    val visible: Boolean = false,
    val imageBitmap: Bitmap? = null,
    val kind: String = "", // "image" | "video" | "model" | "audio" | ""
)

val infoPanelState = mutableStateOf(InfoPanelState())

/** Invoked when the user clicks the close button or the panel background. */
var infoPanelOnDismiss: () -> Unit = {}

private val GoldAccent = Color(0xFFD4AF37)
private val GoldDim = Color(0x66D4AF37)
private val PanelBg = Color(0xEE0E0A06)
private val OnPanel = Color(0xFFF5EFE6)
private val OnPanelDim = Color(0xCCBDB3A6)

/**
 * Plain Material3 implementation — DELIBERATELY avoids uiset SpatialTheme / LocalColorScheme /
 * PrimaryButton because those have crashed at runtime on Quest with our earlier panel registration
 * (LocalShapes.current.large + weight(1f).verticalScroll combo silently produced an empty surface).
 * Hand-painting with Material3 Text + Box + clickable keeps the dependency surface minimal so the
 * panel reliably composes regardless of theme inheritance from the host Activity window.
 */
@Composable
fun InfoPanel() {
    val state = infoPanelState.value
    MaterialTheme {
        // The panel surface is fixed-size and transparent; the dark modal Column below wraps
        // its content height so the box is exactly as tall as what it holds — a short caption
        // gets a small box, not a half-empty oversized one. verticalScroll stays as a safety
        // net for the rare description that would exceed the panel surface.
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(24.dp))
                    .background(PanelBg)
                    .border(2.dp, GoldDim, RoundedCornerShape(24.dp))
                    .padding(horizontal = 32.dp, vertical = 28.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                // Eyebrow label — type-specific so the user gets a hint of what they clicked on.
                Text(
                    text = when (state.kind) {
                        "image" -> "* PAINTING"
                        "video" -> "* VIDEO"
                        "model" -> "* OBJECT"
                        "audio" -> "* AUDIO"
                        else -> "* EXHIBIT"
                    },
                    color = GoldAccent,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    letterSpacing = 4.sp,
                )
                Spacer(modifier = Modifier.size(10.dp))

                Text(
                    text = state.title.ifBlank { "Untitled exhibit" },
                    color = OnPanel,
                    fontFamily = FontFamily.Serif,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 28.sp,
                )
                Spacer(modifier = Modifier.size(12.dp))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp)
                        .background(GoldDim),
                )
                Spacer(modifier = Modifier.size(16.dp))

                // Thumbnail for images AND video posters. Defensive: skip if bitmap is recycled
                // (rare race with the texture loader if the user reopens the modal after a release).
                // Video bitmaps are captured async by spawnVideo via MediaMetadataRetriever, so
                // they may not be present yet on first click — falls back to no-poster.
                val bmp = state.imageBitmap
                if (bmp != null && !bmp.isRecycled && (state.kind == "image" || state.kind == "video")) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(160.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color.Black)
                            .border(1.dp, GoldDim, RoundedCornerShape(12.dp)),
                    ) {
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = state.title,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    Spacer(modifier = Modifier.size(16.dp))
                }

                Text(
                    text = state.description.ifBlank {
                        "No description provided for this exhibit."
                    },
                    color = OnPanelDim,
                    fontSize = 16.sp,
                    lineHeight = 22.sp,
                )

                Spacer(modifier = Modifier.size(24.dp))

                // Close button — hand-painted clickable Box so we don't depend on uiset's
                // PrimaryButton which has crashed in earlier panel registrations.
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.CenterEnd,
                ) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(GoldAccent)
                            .clickable {
                                Log.i("MuseumSpatial", "info modal close clicked")
                                infoPanelOnDismiss()
                            }
                            .padding(horizontal = 28.dp, vertical = 12.dp),
                    ) {
                        Text(
                            text = "Close",
                            color = Color(0xFF1A1208),
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp,
                        )
                    }
                }
            }
        }
    }
}
