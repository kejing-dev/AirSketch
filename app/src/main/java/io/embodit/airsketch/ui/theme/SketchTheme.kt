package io.embodit.airsketch.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.systemColorScheme

/** Design palette (palette.colorScheme / brandTokens). Stroke colours are domain content (Palette), not theme. */
object SketchUi {
    val Coral = Color(0xFFFF8A5C) // interaction: selected brush / mirror / open palette, boundary glow
    val Jade = Color(0xFF5FD3A4) // passable: saved badge
    val Alert = Color(0xFFFFB02E) // full
    val Error = Color(0xFFFF6B5E) // can't open
    val OnCoral = Color(0xFF2A1A12)
    val InkViolet = Color(0xFF3D1F6B)
}

/**
 * SpatialUI Vibrant with the four semantic roles of the design; the twelve adaptive grey roles are kept from
 * the system scheme (palette.colorScheme: inherit). Type is PicoTheme's own roles with the system font.
 */
@Composable
fun SketchTheme(content: @Composable () -> Unit) {
    val system = systemColorScheme(LocalContext.current)
    val colors =
        remember(system) {
            system.copy(
                interaction = SketchUi.Coral,
                passable = SketchUi.Jade,
                alert = SketchUi.Alert,
                error = SketchUi.Error,
            )
        }
    PicoTheme(colorScheme = colors, content = content)
}
