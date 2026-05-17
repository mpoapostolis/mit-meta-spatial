package art.galerra.museum.spatial

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Tiny circular floating "Back to scene picker" button. Sized + styled to match
 * [MuteButtonPanel] so the two read as a paired toolbar. The activity assigns
 * [backButtonOnClick] in [ImmersiveActivity.spawnBackButtonEntity] to wipe the
 * current exhibition objects and re-show the welcome panel.
 *
 * Plain Material3 (no uiset SpatialTheme) for the same reasons documented in InfoPanel.kt —
 * uiset theming has crashed at runtime on the device.
 */
var backButtonOnClick: () -> Unit = {}

private val BackGold = Color(0xFFD4AF37)
private val BackBg = Color(0xEE1A1208)

@Composable
fun BackButtonPanel() {
    MaterialTheme {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(CircleShape)
                .background(BackBg)
                .border(2.dp, BackGold, CircleShape)
                .clickable { backButtonOnClick() }
                .padding(8.dp),
            contentAlignment = Alignment.Center,
        ) {
            // Left-arrow glyph — chosen so the meaning reads regardless of locale (some users
            // of this app are Greek-speaking, "Back" might not register at a glance).
            Text(
                text = "<",
                color = BackGold,
                fontFamily = FontFamily.Serif,
                fontWeight = FontWeight.Bold,
                fontSize = 28.sp,
            )
        }
    }
}
