package io.embodit.airsketch.ui

import android.content.Context
import io.embodit.airsketch.data.WorkRead
import io.embodit.airsketch.data.WorkStore
import io.embodit.airsketch.domain.Brush
import io.embodit.airsketch.domain.Limits
import io.embodit.airsketch.domain.Palette
import io.embodit.airsketch.domain.Quat4
import io.embodit.airsketch.domain.Samples
import io.embodit.airsketch.domain.StrokeBuilder
import io.embodit.airsketch.domain.StrokeData
import io.embodit.airsketch.domain.Vec3
import io.embodit.airsketch.domain.Work
import io.embodit.airsketch.domain.Zone
import io.embodit.airsketch.domain.resample
import io.embodit.airsketch.platform.ControllerStick
import io.embodit.airsketch.platform.SketchLog
import io.embodit.airsketch.scene.PanelIds
import io.embodit.airsketch.scene.SceneListener
import io.embodit.airsketch.scene.SceneMode
import io.embodit.airsketch.scene.SketchScene
import java.util.Locale
import java.util.UUID
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The app: state machine (design states S01–S17), the current work with its undo / redo, saves (1 s debounce,
 * immediately on focus loss), the gallery, onboarding, and where each attachment panel sits. The 3D side is
 * [SketchScene]; it reports gestures through [SceneListener].
 */
class SketchController(context: Context) : SceneListener {
    private val app = context.applicationContext
    val store = WorkStore(app)
    val stick = ControllerStick()
    val scene = SketchScene(this, stick)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()
    val s: UiState get() = _state.value

    var cur: Work = blankWork()
        private set
    private val redoStack = ArrayList<StrokeData>()
    private var saveJob: Job? = null
    private var bootJob: Job? = null
    private var onboardingJob: Job? = null
    private var attached = false
    private var settingsReturn = Bar.MENU

    init {
        _state.value = s.copy(langSetting = store.language, lang = resolveLang(store.language), slowSpin = store.slowSpin)
        scene.budgetProvider = { if (cur.strokes.size >= Limits.MAX_STROKES) 0 else Limits.MAX_POINTS - cur.points }
        scene.onNibPlaced = { if (s.onboarding != null) layoutPanels(s) }
    }

    private fun resolveLang(setting: String): Lang =
        when (setting) {
            "zh" -> Lang.ZH
            "en" -> Lang.EN
            else -> if (Locale.getDefault().language.startsWith("zh")) Lang.ZH else Lang.EN
        }

    private fun set(block: (UiState) -> UiState) {
        val before = _state.value
        val next = block(before)
        val after = next.copy(designState = designStateOf(next))
        _state.value = after
        if (before.designState != after.designState || before.bar != after.bar || before.screen != after.screen) {
            SketchLog.i("STATE ${after.designState} screen=${after.screen} bar=${after.bar} strokes=${after.strokeCount} full=${after.full} onboarding=${after.onboarding} lang=${after.lang} sample=${after.sample}")
        }
        scene.setMode(modeOf(after))
        layoutPanels(after)
    }

    private fun designStateOf(s: UiState): String {
        val base = when (s.screen) {
            Screen.LOADING -> "S01"
            Screen.ERROR -> "S17"
            Screen.DISPLAY -> "S14"
            Screen.DRAW -> when {
                s.bar == Bar.SETTINGS -> "S15"
                s.bar == Bar.CONFIRM_DELETE -> "S13"
                s.bar == Bar.GALLERY -> "S12"
                s.bar == Bar.CONFIRM_CLEAR -> "S11"
                s.bar == Bar.MENU -> "S10"
                s.full -> "S16"
                s.drawing -> "S04"
                s.turning -> "S08"
                s.bar == Bar.PALETTE -> "S06"
                s.onboarding != null -> "S02"
                s.strokeCount == 0 -> "S03"
                else -> "S05"
            }
        }
        val marks = buildString {
            if (s.screen == Screen.DRAW && s.mirror && base in setOf("S03", "S05", "S04", "S06", "S08", "S02")) append("+S07")
            if (s.screen == Screen.DRAW && s.canRedo && base in setOf("S03", "S05", "S16", "S08")) append("+S09")
        }
        return base + marks
    }

    private fun modeOf(s: UiState): SceneMode {
        val draw = s.screen == Screen.DRAW
        val tools = s.bar == Bar.TOOLS || s.bar == Bar.PALETTE
        val nib = draw && tools && !s.full
        return SceneMode(
            nib = nib,
            plane = nib,
            planeTappable = nib && s.bar == Bar.TOOLS,
            turn = (draw && s.bar in setOf(Bar.TOOLS, Bar.PALETTE, Bar.MENU, Bar.GALLERY)) || s.screen == Screen.DISPLAY,
            mirrorPlane = draw && s.mirror,
            display = s.screen == Screen.DISPLAY,
            spin = s.slowSpin,
            fastBreath = s.onboarding == 0,
            planeWhileDrawing = s.onboarding == 1,
        )
    }

    // ------------------------------------------------------------------------------------------
    // panels (design spatialLayout; volume metres)

    private val leanBack15 = Quat4.axisAngle(Vec3(1f, 0f, 0f), Math.toRadians(-15.0).toFloat())
    private val panelUp = leanBack15.rotate(Vec3(0f, 1f, 0f))
    private val toolbarCentre = Vec3(0f, 0.05f, 0.25f)
    private val toolbarBottom = toolbarCentre - panelUp * (PanelSizes.BAR_H * PanelSizes.M_PER_DP / 2f)

    private fun layoutPanels(s: UiState) {
        if (!attached) return
        val sc = scene
        if (s.screen == Screen.DISPLAY) {
            sc.hidePanel(PanelIds.TOOLBAR)
            sc.showPanel(PanelIds.PILL, toolbarCentre, leanBack15)
        } else {
            sc.hidePanel(PanelIds.PILL)
            val h = PanelSizes.height(s) * PanelSizes.M_PER_DP
            sc.showPanel(PanelIds.TOOLBAR, toolbarBottom + panelUp * (h / 2f), leanBack15)
        }
        if (s.screen == Screen.DRAW && s.onboarding != null && s.bar == Bar.TOOLS && !s.full) sc.showPanel(PanelIds.HINT, sc.hintAnchor()) else sc.hidePanel(PanelIds.HINT)
    }

    // ------------------------------------------------------------------------------------------
    // lifecycle

    fun onAttached() {
        attached = true
        boot()
    }

    /** S01: load the index and the last work (S02 first time / S03 empty / S05 / S14 / S17). */
    fun boot(holdMs: Long = 0L) {
        bootJob?.cancel()
        scene.dropDrag()
        // S01: the base alone while the work loads
        scene.setStrokes(emptyList(), 0f)
        set { it.copy(screen = Screen.LOADING, loading = 0f, bar = Bar.TOOLS, errorReason = null) }
        bootJob = scope.launch {
            for (i in 1..8) {
                delay(90)
                set { it.copy(loading = i / 8f) }
            }
            // debug `state S01` keeps the loading strip up long enough for a capture
            if (holdMs > 0) delay(holdMs)
            open()
        }
    }

    private fun open() {
        val id = store.lastWorkId
        if (id == null) {
            show(blankWork())
            return
        }
        when (val r = store.read(id)) {
            is WorkRead.Ok -> show(r.work)
            WorkRead.Missing -> fail(s.strings.errMissing)
            is WorkRead.Broken -> fail("${s.strings.errSub} (${r.reason.take(60)})")
        }
    }

    private fun fail(reason: String) {
        // the unreadable file stays as it is (design S17): nothing in memory may be saved over it
        saveJob?.cancel()
        cur = blankWork().apply { sample = "error" }
        redoStack.clear()
        scene.setStrokes(emptyList(), 0f)
        SketchLog.i("LOAD failed $reason")
        set { it.copy(screen = Screen.ERROR, errorReason = reason, bar = Bar.TOOLS) }
    }

    private fun blankWork(): Work {
        val t = runCatching { store.lastTools.split(",").map { it.trim().toInt() } }.getOrNull()?.takeIf { it.size == 4 } ?: listOf(0, 1, Palette.SKY, 0)
        val now = System.currentTimeMillis()
        return Work(id = UUID.randomUUID().toString().take(8), nameIndex = 0, createdAt = now, updatedAt = now, brush = t[0].coerceIn(0, 3), size = t[1].coerceIn(0, 2), color = t[2].coerceIn(0, Palette.SIZE - 1), mirror = t[3] == 1)
    }

    /** Puts [w] on the base (design lifecycle: open the last work; samples open read-only). */
    private fun show(w: Work, keepBar: Boolean = false) {
        cur = w
        redoStack.clear()
        scene.dropDrag()
        scene.setStrokes(w.strokes, w.yawDeg)
        scene.setTools(Brush.of(w.brush), w.size, w.color, w.mirror)
        scene.nibToArtPoint(w.strokes.lastOrNull()?.last())
        val firstRun = !store.onboardingDone
        SketchLog.i("LOAD work=${w.id} sample=${w.sample} strokes=${w.strokes.size} points=${w.points} yaw=${w.yawDeg} display=${w.display}")
        set {
            it.copy(
                screen = if (w.display && !keepBar) Screen.DISPLAY else Screen.DRAW,
                bar = if (keepBar) it.bar else Bar.TOOLS,
                brush = w.brush, size = w.size, color = w.color, mirror = w.mirror,
                strokeCount = w.strokes.size, canRedo = false, full = Limits.full(w), drawing = false, turning = false,
                onboarding = if (firstRun && !keepBar) (it.onboarding ?: 0) else null,
                workName = nameOf(w, it.strings), workId = w.id, sample = w.sample, errorReason = null,
            )
        }
    }

    private fun nameOf(w: Work, t: Strings): String = when {
        w.sample != null -> t.sampleName(w.sample!!)
        w.nameIndex > 0 -> t.workName(w.nameIndex, w.createdAt)
        else -> ""
    }

    fun onFocus(focused: Boolean) {
        if (!focused) {
            scene.dropDrag()
            saveNow()
        }
    }

    fun close() {
        saveNow()
        scope.cancel()
        stick.stop()
        scene.close()
    }

    // ------------------------------------------------------------------------------------------
    // saving

    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(1000)
            saveNow()
        }
    }

    fun saveNow() {
        saveJob?.cancel()
        saveJob = null
        if (cur.sample != null) return
        cur.updatedAt = System.currentTimeMillis()
        runCatching {
            store.write(cur)
            store.lastWorkId = if (cur.strokes.isNotEmpty()) cur.id else store.lastWorkId.takeIf { it != cur.id }
            store.lastTools = "${cur.brush},${cur.size},${cur.color},${if (cur.mirror) 1 else 0}"
        }.onFailure { SketchLog.e("SAVE failed: $it", it) }
        SketchLog.i("SAVE work=${cur.id} strokes=${cur.strokes.size} points=${cur.points} yaw=${cur.yawDeg.toInt()} display=${cur.display}")
    }

    // ------------------------------------------------------------------------------------------
    // scene events

    override fun onStrokeStarted() {
        set { it.copy(drawing = true, bar = if (it.bar == Bar.PALETTE) Bar.TOOLS else it.bar) }
    }

    override fun onStrokeEnded(stroke: StrokeData?, full: Boolean) {
        if (stroke == null) {
            set { it.copy(drawing = false, full = it.full || full) }
            return
        }
        if (cur.sample != null) {
            // a sample becomes the user's own work at its first new stroke (design T9)
            val copy = blankWork()
            copy.strokes.addAll(cur.strokes)
            copy.yawDeg = scene.yawDeg()
            copy.brush = cur.brush; copy.size = cur.size; copy.color = cur.color; copy.mirror = cur.mirror
            cur = copy
            SketchLog.i("SAMPLE copied to work=${cur.id}")
        }
        if (cur.nameIndex == 0) {
            cur.nameIndex = store.nextNameIndex()
            cur.createdAt = System.currentTimeMillis()
        }
        cur.strokes += stroke
        redoStack.clear()
        scene.addStroke(stroke)
        SketchLog.i("STROKE kept n=${stroke.count} brush=${stroke.brush.id} mirrored=${stroke.mirrored} total=${cur.strokes.size} points=${cur.points}")
        scheduleSave()
        val step = s.onboarding
        set {
            it.copy(
                drawing = false, strokeCount = cur.strokes.size, canRedo = false, full = Limits.full(cur) || full,
                workId = cur.id, sample = null, workName = nameOf(cur, it.strings),
                onboarding = if (step == 0) 1 else step,
            )
        }
    }

    override fun onNibMoved() {
        if (s.onboarding == 1) set { it.copy(onboarding = 2) }
        layoutPanels(s)
    }

    override fun onTurning(turning: Boolean) {
        set { it.copy(turning = turning) }
    }

    override fun onTurned(yawDeg: Float) {
        cur.yawDeg = yawDeg
        SketchLog.i("TURN rest yaw=${yawDeg.toInt()}")
        if (cur.strokes.isNotEmpty()) scheduleSave()
        if (s.onboarding == 2) {
            set { it.copy(onboarding = 3, message = it.strings.hints[3]) }
            onboardingJob?.cancel()
            onboardingJob = scope.launch {
                delay(4000)
                finishOnboarding()
                delay(4000)
                set { it.copy(message = null) }
            }
        }
    }

    override fun onAnyDrag() {
        if (s.bar == Bar.PALETTE) set { it.copy(bar = Bar.TOOLS) }
    }

    private fun finishOnboarding() {
        store.onboardingDone = true
        if (s.onboarding != null) set { it.copy(onboarding = null) }
    }

    fun skipOnboarding() {
        onboardingJob?.cancel()
        finishOnboarding()
    }

    // ------------------------------------------------------------------------------------------
    // tools

    fun setBrush(i: Int) {
        cur.brush = i.coerceIn(0, 3)
        applyTools()
    }

    fun setSize(i: Int) {
        cur.size = i.coerceIn(0, 2)
        applyTools()
    }

    /** A swatch: change colour and fold the palette (design S06). */
    fun pickColor(i: Int) {
        cur.color = i.coerceIn(0, Palette.SIZE - 1)
        applyTools()
        if (s.bar == Bar.PALETTE) set { it.copy(bar = Bar.TOOLS) }
    }

    fun setMirror(on: Boolean) {
        cur.mirror = on
        applyTools()
    }

    private fun applyTools() {
        scene.setTools(Brush.of(cur.brush), cur.size, cur.color, cur.mirror)
        set { it.copy(brush = cur.brush, size = cur.size, color = cur.color, mirror = cur.mirror) }
        if (cur.strokes.isNotEmpty()) scheduleSave() else store.lastTools = "${cur.brush},${cur.size},${cur.color},${if (cur.mirror) 1 else 0}"
    }

    fun togglePalette() {
        if (s.drawing) return
        set { it.copy(bar = if (it.bar == Bar.PALETTE) Bar.TOOLS else Bar.PALETTE) }
    }

    fun turn90() {
        scene.turn90()
    }

    fun undo() {
        if (cur.sample != null) return
        val st = cur.strokes.removeLastOrNull() ?: return
        redoStack += st
        scene.removeStroke(st)
        scene.nibToArtPoint(cur.strokes.lastOrNull()?.last(), animate = true)
        SketchLog.i("UNDO total=${cur.strokes.size} redo=${redoStack.size}")
        scheduleSave()
        set { it.copy(strokeCount = cur.strokes.size, canRedo = true, full = Limits.full(cur)) }
    }

    fun redo() {
        val st = redoStack.removeLastOrNull() ?: return
        cur.strokes += st
        scene.addStroke(st)
        scene.nibToArtPoint(st.last(), animate = true)
        SketchLog.i("REDO total=${cur.strokes.size} redo=${redoStack.size}")
        scheduleSave()
        set { it.copy(strokeCount = cur.strokes.size, canRedo = redoStack.isNotEmpty(), full = Limits.full(cur)) }
    }

    // ------------------------------------------------------------------------------------------
    // menu, gallery, display, settings

    fun openMenu() {
        if (s.onboarding != null) skipOnboarding()
        set { it.copy(bar = Bar.MENU) }
    }

    fun closeBar() {
        set { it.copy(bar = Bar.TOOLS) }
    }

    fun newWork() {
        saveNow()
        val w = blankWork()
        store.lastWorkId = null
        show(w.apply { yawDeg = 0f })
    }

    fun askClear() {
        if (cur.strokes.isEmpty()) return
        set { it.copy(bar = Bar.CONFIRM_CLEAR) }
    }

    fun confirmClear() {
        cur.strokes.clear()
        redoStack.clear()
        scene.setStrokes(emptyList(), scene.yawDeg())
        scene.nibToArtPoint(null)
        if (cur.sample == null) saveNow()
        set { it.copy(bar = Bar.TOOLS, strokeCount = 0, canRedo = false, full = false) }
    }

    fun openGallery() {
        if (s.onboarding != null) skipOnboarding()
        saveNow()
        set { it.copy(bar = Bar.GALLERY, cards = cards()) }
    }

    private fun cards(): List<Card> = store.index().take(6).map { Card.of(it) } + Samples.IDS.map { id -> Samples.work(id).let { w -> Card("sample:$id", id, 0, 0L, w.strokes.size, w.colorsUsed) } }

    /** A card: the base shows that work at once; the gallery stays open (design S12). */
    fun pickCard(c: Card) {
        if (c.id == cur.id) return
        saveNow()
        if (c.sample != null) {
            show(Samples.work(c.sample), keepBar = true)
            return
        }
        when (val r = store.read(c.id)) {
            is WorkRead.Ok -> show(r.work.apply { display = false }, keepBar = true)
            WorkRead.Missing -> fail(s.strings.errMissing)
            is WorkRead.Broken -> fail("${s.strings.errSub} (${r.reason.take(60)})")
        }
    }

    val canDelete: Boolean get() = cur.sample == null && store.index().any { it.id == cur.id }

    fun askDelete() {
        if (!canDelete) return
        set { it.copy(bar = Bar.CONFIRM_DELETE) }
    }

    fun confirmDelete() {
        val gone = cur.id
        store.delete(gone)
        if (store.lastWorkId == gone) store.lastWorkId = null
        SketchLog.i("DELETE work=$gone")
        val next = store.index().firstOrNull()
        val w = next?.let { (store.read(it.id) as? WorkRead.Ok)?.work } ?: blankWork()
        show(w.apply { display = false }, keepBar = true)
        set { it.copy(bar = Bar.GALLERY, cards = cards()) }
    }

    fun cancelToGallery() {
        set { it.copy(bar = Bar.GALLERY) }
    }

    fun openDisplay() {
        cur.display = true
        if (cur.strokes.isNotEmpty()) saveNow()
        set { it.copy(screen = Screen.DISPLAY, bar = Bar.TOOLS) }
    }

    fun keepDrawing() {
        cur.display = false
        if (cur.strokes.isNotEmpty()) saveNow()
        set { it.copy(screen = Screen.DRAW, bar = Bar.TOOLS) }
    }

    fun openSettings() {
        settingsReturn = if (s.bar == Bar.SETTINGS) settingsReturn else s.bar
        set { it.copy(bar = Bar.SETTINGS) }
    }

    fun settingsBack() {
        set { it.copy(bar = settingsReturn) }
    }

    fun setLanguage(v: String) {
        store.language = v
        set { it.copy(langSetting = v, lang = resolveLang(v)) }
        set { it.copy(workName = nameOf(cur, it.strings), message = it.message?.let { _ -> it.strings.hints[3] }) }
    }

    fun setSlowSpin(on: Boolean) {
        store.slowSpin = on
        set { it.copy(slowSpin = on) }
    }

    fun retry() = boot()

    fun errorNew() {
        store.lastWorkId = null
        show(blankWork())
    }

    // ------------------------------------------------------------------------------------------
    // debug seams (DebugCommands)

    fun debugSeed(sample: String) {
        if (s.onboarding != null) finishOnboarding()
        saveNow()
        show(Samples.work(sample))
    }

    /** Fills the sketch to its limit with machine strokes (QA S16). */
    fun debugFill() {
        if (cur.sample != null) {
            val copy = blankWork()
            copy.strokes.addAll(cur.strokes)
            cur = copy
        }
        if (cur.nameIndex == 0) cur.nameIndex = store.nextNameIndex()
        var k = cur.strokes.size
        while (!Limits.full(cur)) {
            val budget = Limits.MAX_POINTS - cur.points
            val pts = fillStroke(k)
            val n = minOf(pts.size / 4, budget)
            if (n < 3) break
            cur.strokes += StrokeData(Brush.of(k % 4), 0, k % Palette.SIZE, false, 0f, pts.copyOf(n * 4))
            k++
        }
        redoStack.clear()
        scene.setStrokes(cur.strokes, scene.yawDeg())
        scene.nibToArtPoint(cur.strokes.last().last())
        saveNow()
        cur.display = false
        set { it.copy(screen = Screen.DRAW, strokeCount = cur.strokes.size, canRedo = false, full = Limits.full(cur), workId = cur.id, sample = null, workName = nameOf(cur, it.strings), bar = Bar.TOOLS, onboarding = null) }
    }

    private fun fillStroke(k: Int): FloatArray {
        val raw = ArrayList<Float>()
        val y0 = Zone.Y0 + 0.01f + (k % 23) * 0.019f
        val r = 0.03f + (k % 7) * 0.02f
        val a0 = k * 0.7f
        for (i in 0..12) {
            val a = a0 + i * 0.35f
            raw += cos(a) * r; raw += y0 + i * 0.001f; raw += sin(a) * r; raw += 0.0015f
        }
        return resample(raw.toFloatArray(), StrokeBuilder.RESAMPLE)
    }

    fun debugLocale(v: String) = setLanguage(if (v == "zh" || v == "en") v else "follow")

    fun debugCorruptSave() {
        if (cur.strokes.isEmpty() || cur.sample != null) {
            debugSeed("spring")
            debugFillOne()
        }
        saveNow()
        store.corrupt(cur.id)
        store.lastWorkId = cur.id
        SketchLog.i("CMD corruptSave work=${cur.id}")
    }

    /** One machine stroke straight into the work (no drag), for seams that need an own work. */
    fun debugFillOne() {
        val pts = fillStroke(cur.strokes.size)
        onStrokeEnded(StrokeData(Brush.INK, 1, cur.color, false, 0f, pts), false)
        saveNow()
    }

    fun debugResetOnboarding() {
        store.onboardingDone = false
        onboardingJob?.cancel()
        set { it.copy(onboarding = 0, message = null) }
    }

    fun debugTools(brush: String?, size: String?, color: String?) {
        brush?.let { b -> (Brush.byId(b)?.ordinal ?: b.toIntOrNull())?.let { setBrush(it) } }
        size?.let { z -> (listOf("s", "m", "l").indexOf(z.lowercase()).takeIf { it >= 0 } ?: z.toIntOrNull())?.let { setSize(it) } }
        color?.let { c -> (Palette.IDS.indexOf(c).takeIf { it >= 0 } ?: c.toIntOrNull())?.let { pickColor(it) } }
    }

    fun dump(): String =
        "state=${s.designState} screen=${s.screen} bar=${s.bar} work=${cur.id} sample=${cur.sample} name=${s.workName} strokes=${cur.strokes.size} points=${cur.points} " +
            "redo=${redoStack.size} full=${s.full} onboarding=${s.onboarding} tools=${Brush.of(cur.brush).id}/${cur.size}/${Palette.IDS[cur.color]}/mirror=${cur.mirror} lang=${s.lang} | ${scene.dump()}"
}

object PanelSizes {
    /** 640 dp = 0.52 m (design panels[toolbar]). */
    const val M_PER_DP = 0.52f / 640f
    const val BAR_W = 640f
    const val BAR_H = 104f
    const val PALETTE_H = 272f
    const val GALLERY_H = 208f
    const val SETTINGS_H = 300f
    const val PILL_W = 184f
    const val PILL_H = 44f
    const val HINT_W = 210f
    const val HINT_H = 50f

    fun height(s: UiState): Float =
        when {
            s.screen != Screen.DRAW -> BAR_H
            s.bar == Bar.PALETTE -> PALETTE_H
            s.bar == Bar.GALLERY -> GALLERY_H
            s.bar == Bar.SETTINGS -> SETTINGS_H
            else -> BAR_H
        }
}
