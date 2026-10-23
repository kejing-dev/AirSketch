package io.embodit.airsketch.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.Button
import com.pico.spatial.ui.design.ButtonDefaults
import com.pico.spatial.ui.design.Icon
import com.pico.spatial.ui.design.IconButton
import com.pico.spatial.ui.design.IconButtonDefaults
import com.pico.spatial.ui.design.LinearProgressIndicator
import com.pico.spatial.ui.design.ListItem
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.SegmentControl
import com.pico.spatial.ui.design.SegmentControlDefaults
import com.pico.spatial.ui.design.SegmentItem
import com.pico.spatial.ui.design.Switch
import com.pico.spatial.ui.design.Text
import com.pico.spatial.ui.foundation.material.backgroundMaterial
import com.pico.spatial.ui.platform.Material
import io.embodit.airsketch.BuildConfig
import io.embodit.airsketch.R
import io.embodit.airsketch.domain.Palette
import io.embodit.airsketch.domain.Sizes
import io.embodit.airsketch.ui.theme.SketchUi
import kotlinx.coroutines.delay

private val GlassShape = RoundedCornerShape(24.dp)

/**
 * Glass panel root: fixed dp size (an attachment root must not wrap its content), Material.Regular,
 * redrawn every 0.5 s so a black backdrop frame on the emulator does not stick (ported from Voxel Jigsaw).
 */
@Composable
private fun Glass(w: Float, h: Float, padding: Int = 12, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val tick = remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(500)
            tick.intValue++
        }
    }
    Column(
        modifier = Modifier.size(w.dp, h.dp)
            .clip(GlassShape)
            .backgroundMaterial(enable = true, style = Material.Regular)
            .drawBehind { tick.intValue }
            .then(modifier)
            .padding(padding.dp),
        content = content,
    )
}

/** An empty fixed-size root for a panel that is not in use (its holder is shrunk away by the scene). */
@Composable
private fun Empty(w: Float, h: Float) = Box(Modifier.size(w.dp, h.dp))

@Composable
private fun ink() = PicoTheme.colorScheme.labelPrimary

@Composable
private fun sub() = PicoTheme.colorScheme.labelSecondary

private fun argb(c: Int) = Color(c)

@Composable
private fun Primary(label: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Button(onClick = onClick, modifier = modifier, enabled = enabled, size = ButtonDefaults.Small, colors = ButtonDefaults.buttonColors(containerColor = SketchUi.Coral, contentColor = SketchUi.OnCoral)) {
        // design-style: inherited-content-color Button
        Text(label, maxLines = 1)
    }
}

@Composable
private fun Secondary(label: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Button(onClick = onClick, modifier = modifier, enabled = enabled, size = ButtonDefaults.Small, colors = ButtonDefaults.buttonColors(containerColor = PicoTheme.colorScheme.fillSecondary, contentColor = PicoTheme.colorScheme.labelPrimary)) {
        // design-style: inherited-content-color Button
        Text(label, maxLines = 1)
    }
}

@Composable
private fun RoundIcon(icon: Int, label: String, size: Int = 44, active: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    val c = PicoTheme.colorScheme
    val tint = if (active) SketchUi.OnCoral else c.labelPrimary
    IconButton(
        onClick = onClick,
        size = IconButtonDefaults.iconButtonSize(size.dp),
        shape = CircleShape,
        enabled = enabled,
        colors = IconButtonDefaults.iconButtonColors(containerColor = if (active) SketchUi.Coral else c.fillSecondary, contentColor = tint),
    ) {
        Icon(painterResource(icon), contentDescription = label, modifier = Modifier.size((size * 0.5f).dp).alpha(if (enabled) 1f else 0.35f), tint = tint)
    }
}

@Composable
private fun segColors() =
    SegmentControlDefaults.colors(
        itemBackgroundColor = Color.Transparent,
        itemContentColor = PicoTheme.colorScheme.labelPrimary,
        selectedItemBackgroundColor = SketchUi.Coral,
        selectedItemContentColor = SketchUi.OnCoral,
    )

@Composable
private fun Segments(labels: List<String>, selected: Int, itemWidth: Int, onPick: (Int) -> Unit) {
    SegmentControl(modifier = Modifier.width((itemWidth * labels.size + 8).dp).height(40.dp), backgroundColor = PicoTheme.colorScheme.fillTertiary) {
        labels.forEachIndexed { i, l ->
            SegmentItem(
                selected = i == selected,
                onClick = { onPick(i) },
                modifier = Modifier.width(itemWidth.dp).fillMaxHeight(),
                textStyle = PicoTheme.typography.labelMedium,
                colors = segColors(),
                title = { Text(text = l, maxLines = 1, color = if (i == selected) SketchUi.OnCoral else ink()) },
            )
        }
    }
}

/** A round colour chip; [ring] draws the interaction-colour outline of the current colour. */
@Composable
private fun Chip(color: Int, diameter: Int, ring: Boolean, modifier: Modifier = Modifier) {
    Box(
        modifier.size((diameter + 8).dp).drawBehind {
            val r = diameter.dp.toPx() / 2
            drawCircle(argb(Palette.ARGB[color]), radius = r)
            if (ring) drawCircle(SketchUi.Coral, radius = r + 3.dp.toPx(), style = Stroke(width = 2.5.dp.toPx()))
        }
    )
}

// ---------------------------------------------------------------------------------------------
// the toolbar panel: tools row, palette, menu, confirm strips, gallery, settings, notices, loading

@Composable
fun ToolbarPanel(s: UiState, g: SketchController) {
    if (s.screen == Screen.DISPLAY) return Empty(PanelSizes.BAR_W, PanelSizes.BAR_H)
    val h = PanelSizes.height(s)
    when {
        s.screen == Screen.LOADING -> Glass(PanelSizes.BAR_W, h) { LoadingBody(s) }
        s.screen == Screen.ERROR -> Glass(PanelSizes.BAR_W, h) { ErrorBody(s, g) }
        s.bar == Bar.MENU -> Glass(PanelSizes.BAR_W, h) { MenuBody(s, g) }
        s.bar == Bar.CONFIRM_CLEAR -> Glass(PanelSizes.BAR_W, h) { ConfirmBody(s.strings.confirmClear(s.strokeCount), s.strings.cancel, s.strings.clear, { g.closeBar() }, { g.confirmClear() }) }
        s.bar == Bar.CONFIRM_DELETE -> Glass(PanelSizes.BAR_W, h) { ConfirmBody(s.strings.confirmDel(s.workName), s.strings.cancel, s.strings.del, { g.cancelToGallery() }, { g.confirmDelete() }) }
        s.bar == Bar.GALLERY -> Glass(PanelSizes.BAR_W, h, padding = 10) { GalleryBody(s, g) }
        s.bar == Bar.SETTINGS -> Glass(PanelSizes.BAR_W, h) { SettingsBody(s, g) }
        s.full -> Glass(PanelSizes.BAR_W, h) { FullBody(s, g) }
        else -> Glass(PanelSizes.BAR_W, h) {
            if (s.bar == Bar.PALETTE) PaletteBody(s, g)
            Box {
                Column(Modifier.alpha(if (s.drawing) 0.4f else 1f)) { ToolsBody(s, g) }
                // while drawing the bar is dimmed and takes no clicks (design S04)
                if (s.drawing) Box(Modifier.matchParentSize().pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent().changes.forEach { it.consume() } } })
            }
        }
    }
}

@Composable
private fun ColumnScope.ToolsBody(s: UiState, g: SketchController) {
    val t = s.strings
    Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Segments(t.brushes, s.brush, 70) { g.setBrush(it) }
        Spacer(Modifier.weight(1f))
        // the colour dot: its size follows the stroke size; it opens "Colour & size"
        val open = s.bar == Bar.PALETTE
        IconButton(
            onClick = { g.togglePalette() },
            size = IconButtonDefaults.iconButtonSize(44.dp),
            shape = CircleShape,
            colors = IconButtonDefaults.iconButtonColors(containerColor = if (open) SketchUi.Coral else PicoTheme.colorScheme.fillSecondary, contentColor = ink()),
        ) {
            val d = (Sizes.NIB_M[s.size] * 1000f).toInt() // 18 / 24 / 30 dp
            Box(Modifier.size(d.dp).clip(CircleShape).drawBehind { drawCircle(argb(Palette.ARGB[s.color])) })
        }
        RoundIcon(R.drawable.ic_mirror, t.mirror, active = s.mirror) { g.setMirror(!s.mirror) }
        RoundIcon(R.drawable.ic_turn90, t.turn90) { g.turn90() }
        RoundIcon(R.drawable.ic_undo, t.undo, enabled = s.strokeCount > 0 && s.sample == null) { g.undo() }
        RoundIcon(R.drawable.ic_redo, t.redo, enabled = s.canRedo) { g.redo() }
        RoundIcon(R.drawable.ic_more, t.more) { g.openMenu() }
    }
    Spacer(Modifier.height(4.dp))
    Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        val left = s.message ?: when {
            s.sample != null -> "${s.workName} · ${t.newSample}"
            s.workName.isNotEmpty() -> "${s.workName} · ${t.strokes(s.strokeCount)}"
            else -> ""
        }
        Text(left, style = PicoTheme.typography.labelMedium, color = if (s.message != null) SketchUi.Coral else sub(), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Icon(painterResource(R.drawable.ic_stick), contentDescription = null, modifier = Modifier.size(14.dp), tint = PicoTheme.colorScheme.labelTertiary)
        Spacer(Modifier.width(4.dp))
        Text(t.stickTip, style = PicoTheme.typography.labelSmall, color = PicoTheme.colorScheme.labelTertiary, maxLines = 1)
    }
}

@Composable
private fun ColumnScope.PaletteBody(s: UiState, g: SketchController) {
    val t = s.strings
    Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(t.sizeLabel, style = PicoTheme.typography.bodyMedium, color = ink())
        Spacer(Modifier.width(12.dp))
        Segments(t.sizes, s.size, 64) { g.setSize(it) }
        Spacer(Modifier.weight(1f))
        Text(t.colorLabel, style = PicoTheme.typography.labelMedium, color = sub())
    }
    Spacer(Modifier.height(6.dp))
    for (row in 0 until 2) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            for (k in 0 until 6) {
                val i = row * 6 + k
                IconButton(
                    onClick = { g.pickColor(i) },
                    size = IconButtonDefaults.iconButtonSize(48.dp),
                    shape = CircleShape,
                    colors = IconButtonDefaults.iconButtonColors(containerColor = Color.Transparent, contentColor = ink()),
                ) {
                    Chip(i, 30, ring = i == s.color)
                }
            }
        }
        Spacer(Modifier.height(4.dp))
    }
    Spacer(Modifier.height(6.dp))
}

@Composable
private fun ColumnScope.MenuBody(s: UiState, g: SketchController) {
    val t = s.strings
    Spacer(Modifier.weight(1f))
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        RoundIcon(R.drawable.ic_back, t.back) { g.closeBar() }
        Secondary(t.menuNew, Modifier.weight(1f)) { g.newWork() }
        Secondary(t.menuGallery, Modifier.weight(1f)) { g.openGallery() }
        Secondary(t.menuDisplay, Modifier.weight(1f)) { g.openDisplay() }
        Secondary(t.menuClear, Modifier.weight(1f), enabled = s.strokeCount > 0) { g.askClear() }
        Secondary(t.menuSettings, Modifier.weight(1f)) { g.openSettings() }
    }
    Spacer(Modifier.weight(1f))
}

@Composable
private fun ColumnScope.ConfirmBody(text: String, cancel: String, ok: String, onCancel: () -> Unit, onOk: () -> Unit) {
    Spacer(Modifier.weight(1f))
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(text, style = PicoTheme.typography.titleMedium, color = ink(), maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Secondary(cancel, Modifier.width(96.dp)) { onCancel() }
        Primary(ok, Modifier.width(96.dp)) { onOk() }
    }
    Spacer(Modifier.weight(1f))
}

@Composable
private fun ColumnScope.GalleryBody(s: UiState, g: SketchController) {
    val t = s.strings
    Row(Modifier.fillMaxWidth().height(32.dp).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(t.gallery, style = PicoTheme.typography.titleMedium, color = ink())
        Spacer(Modifier.weight(1f))
        Primary(t.done, Modifier.width(88.dp)) { g.closeBar() }
    }
    Spacer(Modifier.height(6.dp))
    Row(Modifier.fillMaxWidth().height(96.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (s.cards.none { it.sample == null }) EmptyCard(t) { g.newWork() }
        for (c in s.cards) WorkCard(s, c) { g.pickCard(c) }
    }
    Spacer(Modifier.height(6.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        Secondary(t.delThis, Modifier.width(132.dp), enabled = g.canDelete) { g.askDelete() }
        Spacer(Modifier.weight(1f))
        Primary(t.newWork, Modifier.width(132.dp)) { g.newWork() }
    }
}

@Composable
private fun WorkCard(s: UiState, c: Card, onClick: () -> Unit) {
    val t = s.strings
    val selected = c.id == s.workId
    val shape = RoundedCornerShape(14.dp)
    Column(
        Modifier.width(150.dp).fillMaxHeight().clip(shape)
            .border(if (selected) 2.dp else 0.dp, if (selected) PicoTheme.colorScheme.interaction else Color.Transparent, shape)
            .drawBehind { drawRect(Color(0x22FFFFFF)) }
            .clickable(onClick = onClick)
            .padding(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val name = if (c.sample != null) t.sampleName(c.sample) else t.workName(c.nameIndex, c.updatedAt)
            Text(name, style = PicoTheme.typography.titleSmall, color = ink(), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (c.sample != null) {
                Text(t.sample, style = PicoTheme.typography.labelSmall, color = SketchUi.OnCoral, modifier = Modifier.clip(RoundedCornerShape(6.dp)).drawBehind { drawRect(SketchUi.Coral) }.padding(horizontal = 5.dp))
            } else {
                Text("✓", style = PicoTheme.typography.labelSmall, color = PicoTheme.colorScheme.passable)
            }
        }
        Text(t.strokes(c.strokeCount), style = PicoTheme.typography.labelMedium, color = sub())
        Spacer(Modifier.weight(1f))
        // the colours used, as a strip
        Row(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp))) {
            val colors = c.colors.ifEmpty { listOf(Palette.SKY) }
            for (k in colors) Box(Modifier.weight(1f).fillMaxHeight().drawBehind { drawRect(argb(Palette.ARGB[k])) })
        }
    }
}

@Composable
private fun EmptyCard(t: Strings, onClick: () -> Unit) {
    val line = PicoTheme.colorScheme.labelTertiary
    Box(
        Modifier.width(150.dp).fillMaxHeight().clip(RoundedCornerShape(14.dp))
            .drawBehind {
                drawRoundRect(line, style = Stroke(width = 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f))), cornerRadius = CornerRadius(14.dp.toPx()))
            }
            .clickable(onClick = onClick)
            .padding(10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(t.emptyGallery, style = PicoTheme.typography.labelMedium, color = sub(), textAlign = TextAlign.Center, maxLines = 2)
    }
}

@Composable
private fun ColumnScope.SettingsBody(s: UiState, g: SketchController) {
    val t = s.strings
    Row(verticalAlignment = Alignment.CenterVertically) {
        RoundIcon(R.drawable.ic_back, t.back, 36) { g.settingsBack() }
        Spacer(Modifier.width(10.dp))
        Text(t.settings, style = PicoTheme.typography.titleMedium, color = ink())
    }
    Spacer(Modifier.height(8.dp))
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(t.language, style = PicoTheme.typography.bodyMedium, color = ink())
        Spacer(Modifier.weight(1f))
        val opts = listOf("follow", "zh", "en")
        Segments(listOf(t.follow, "中文", "English"), opts.indexOf(s.langSetting).coerceAtLeast(0), 96) { g.setLanguage(opts[it]) }
    }
    ListItem(headlineContent = { Text(t.spin, maxLines = 1, color = ink()) }, trailingContent = { Switch(checked = s.slowSpin, onCheckedChange = { g.setSlowSpin(it) }) })
    Spacer(Modifier.weight(1f))
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        // the app glyph on its own colour (design appIcon: the About page uses the same glyph)
        Box(Modifier.size(36.dp).clip(RoundedCornerShape(9.dp)).drawBehind { drawRect(Color(0xFF5F30A6)) }) {
            Image(painterResource(R.drawable.ic_launcher_foreground), contentDescription = null, modifier = Modifier.fillMaxSize())
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(t.about, style = PicoTheme.typography.labelMedium, color = ink())
            Text(t.about(BuildConfig.VERSION_NAME), style = PicoTheme.typography.labelSmall, color = sub(), maxLines = 2)
        }
    }
}

@Composable
private fun ColumnScope.LoadingBody(s: UiState) {
    Spacer(Modifier.weight(1f))
    Text(s.strings.loading, style = PicoTheme.typography.titleMedium, color = ink(), modifier = Modifier.align(Alignment.CenterHorizontally))
    Spacer(Modifier.height(10.dp))
    LinearProgressIndicator(progress = { s.loading }, modifier = Modifier.fillMaxWidth().padding(horizontal = 120.dp))
    Spacer(Modifier.weight(1f))
}

@Composable
private fun ColumnScope.ErrorBody(s: UiState, g: SketchController) {
    val t = s.strings
    Spacer(Modifier.weight(1f))
    Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(painterResource(R.drawable.ic_error), contentDescription = null, modifier = Modifier.size(28.dp), tint = PicoTheme.colorScheme.error)
        Column(Modifier.weight(1f)) {
            Text(t.err, style = PicoTheme.typography.titleMedium, color = ink(), maxLines = 1)
            Text(s.errorReason ?: t.errSub, style = PicoTheme.typography.labelSmall, color = sub(), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Secondary(t.retry, Modifier.width(96.dp)) { g.retry() }
        Primary(t.newWork, Modifier.width(120.dp)) { g.errorNew() }
    }
    Spacer(Modifier.weight(1f))
}

@Composable
private fun ColumnScope.FullBody(s: UiState, g: SketchController) {
    val t = s.strings
    Spacer(Modifier.weight(1f))
    Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(painterResource(R.drawable.ic_warn), contentDescription = null, modifier = Modifier.size(24.dp), tint = PicoTheme.colorScheme.alert)
        Text(t.full, style = PicoTheme.typography.bodyMedium, color = ink(), maxLines = 2, modifier = Modifier.weight(1f))
        RoundIcon(R.drawable.ic_undo, t.undo, 40) { g.undo() }
        Secondary(t.display, Modifier.width(96.dp)) { g.openDisplay() }
        Primary(t.newWork, Modifier.width(120.dp)) { g.newWork() }
    }
    Spacer(Modifier.weight(1f))
}

// ---------------------------------------------------------------------------------------------
// display pill and the onboarding bubble

@Composable
fun PillPanel(s: UiState, g: SketchController) {
    if (s.screen != Screen.DISPLAY) return Empty(PanelSizes.PILL_W, PanelSizes.PILL_H)
    Box(Modifier.size(PanelSizes.PILL_W.dp, PanelSizes.PILL_H.dp).clip(RoundedCornerShape(22.dp)).backgroundMaterial(enable = true, style = Material.Regular), contentAlignment = Alignment.Center) {
        Button(onClick = { g.keepDrawing() }, size = ButtonDefaults.Small, colors = ButtonDefaults.buttonColors(containerColor = PicoTheme.colorScheme.fillSecondary, contentColor = ink())) {
            // design-style: inherited-content-color Button
            Icon(painterResource(R.drawable.ic_pen), contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(s.strings.pill, maxLines = 1)
        }
    }
}

@Composable
fun HintPanel(s: UiState, g: SketchController) {
    val step = s.onboarding
    if (step == null || s.screen != Screen.DRAW) return Empty(PanelSizes.HINT_W, PanelSizes.HINT_H)
    Row(
        Modifier.size(PanelSizes.HINT_W.dp, PanelSizes.HINT_H.dp).clip(RoundedCornerShape(16.dp)).backgroundMaterial(enable = true, style = Material.Regular).padding(start = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(s.strings.hints[step.coerceIn(0, 3)], style = PicoTheme.typography.labelMedium, color = ink(), maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        IconButton(
            onClick = { g.skipOnboarding() },
            size = IconButtonDefaults.iconButtonSize(30.dp),
            shape = CircleShape,
            colors = IconButtonDefaults.iconButtonColors(containerColor = Color.Transparent, contentColor = sub()),
        ) {
            Icon(painterResource(R.drawable.ic_close), contentDescription = s.strings.skip, modifier = Modifier.size(14.dp), tint = sub())
        }
    }
}
