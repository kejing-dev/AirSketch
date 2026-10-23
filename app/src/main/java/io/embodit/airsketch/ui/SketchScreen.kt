package io.embodit.airsketch.ui

import android.content.Context
import android.content.IntentFilter
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import com.pico.spatial.core.math.Vector3
import com.pico.spatial.ui.foundation.content.SpatialView
import com.pico.spatial.ui.foundation.gesture.TargetEntity
import com.pico.spatial.ui.foundation.gesture.detectSpatialDragGesture
import com.pico.spatial.ui.foundation.gesture.detectSpatialTapGesture
import com.pico.spatial.ui.platform.LengthUnit
import com.pico.spatial.ui.platform.LocalPhysicalLengthConverter
import io.embodit.airsketch.BuildConfig
import io.embodit.airsketch.domain.Vec3
import io.embodit.airsketch.platform.SketchLog
import io.embodit.airsketch.scene.PanelIds
import java.lang.ref.WeakReference

/** The one live controller, for the debug broadcast (debug builds). */
object Active {
    var controller: WeakReference<SketchController>? = null
}

/**
 * The volume: one full-size SpatialView with the 3D scene and the attachment panels (design windowModel
 * single_panel_in_volume). Gestures: a spatial tap (the dots) and a spatial drag (nib / shadow / base), each in
 * its own pointerInput (SDK doc: separate pointerInputs so one gesture does not eat the other).
 */
@Composable
fun SketchScreen() {
    val context = LocalContext.current
    val g = remember { SketchController(context).also { Active.controller = WeakReference(it) } }
    val s by g.state.collectAsState()
    val density = LocalDensity.current
    val converter = LocalPhysicalLengthConverter.current

    val focused = LocalWindowInfo.current.isWindowFocused
    LaunchedEffect(focused) { g.onFocus(focused) }

    DisposableEffect(g) {
        g.stick.start()
        val receiver = if (BuildConfig.DEBUG) DebugCommands(g).also { context.registerReceiver(it, IntentFilter("${context.packageName}.DEBUG"), Context.RECEIVER_EXPORTED) } else null
        onDispose {
            receiver?.let { runCatching { context.unregisterReceiver(it) } }
            g.close()
            Active.controller = null
        }
    }

    // dragAmount is a per-event increment in px (design strokeRules.dragMapping): px → dp → metres
    fun meter(px: Float) = with(density) { converter.dpToLength(px.toDp(), LengthUnit.Meters) }

    Box(
        Modifier.fillMaxSize().pointerInput(Unit) {
            // thumbstick fallback: horizontal scroll on the volume (see ControllerStick)
            awaitPointerEventScope {
                while (true) {
                    val e = awaitPointerEvent()
                    if (e.type == PointerEventType.Scroll) e.changes.firstOrNull()?.let { g.stick.feedScroll(it.scrollDelta.x) }
                }
            }
        }
    ) {
        SpatialView(
            modifier = Modifier.fillMaxSize()
                .pointerInput(Unit) {
                    detectSpatialTapGesture(context, TargetEntity.any()) { value ->
                        g.scene.tap(value.targetEntity, Vector3(value.position.x, value.position.y, value.position.z))
                    }
                }
                .pointerInput(Unit) {
                    detectSpatialDragGesture(
                        context,
                        targetedToEntity = TargetEntity.any(),
                        onDragStart = { _, value -> g.scene.dragStart(value.targetEntity) },
                        onDragEnd = { g.scene.dragEnd() },
                        onDragCancel = { g.scene.dragEnd() },
                    ) { value ->
                        val d = value.dragAmount
                        g.scene.dragMove(Vec3(meter(d.x), -meter(d.y), meter(d.z)))
                    }
                },
            initial = { c, attachments ->
                g.scene.attach(c, attachments)
                g.onAttached()
                SketchLog.i("VIEW attached")
            },
            attachments = {
                AttachmentPanel(id = PanelIds.TOOLBAR) { ToolbarPanel(s, g) }
                AttachmentPanel(id = PanelIds.HINT) { HintPanel(s, g) }
                AttachmentPanel(id = PanelIds.PILL) { PillPanel(s, g) }
            },
        )
    }
}
