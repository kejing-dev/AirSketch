package io.embodit.airsketch.scene

import android.os.SystemClock
import com.pico.spatial.core.container.SpatialViewContent
import com.pico.spatial.core.ecs.CollisionComponent
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.HoverEffectComponent
import com.pico.spatial.core.ecs.InteractableComponent
import com.pico.spatial.core.ecs.ModelComponent
import com.pico.spatial.core.ecs.ModelEntity
import com.pico.spatial.core.ecs.ViewCoordinateSpace
import com.pico.spatial.core.ecs.resource.MeshResource
import com.pico.spatial.core.ecs.resource.PhysicsMaterialResource
import com.pico.spatial.core.ecs.resource.ShapeResource
import com.pico.spatial.core.math.Vector3
import com.pico.spatial.ui.foundation.content.SpatialViewAttachments
import io.embodit.airsketch.domain.Brush
import io.embodit.airsketch.domain.MeshBuilder
import io.embodit.airsketch.domain.Quat4
import io.embodit.airsketch.domain.Samples
import io.embodit.airsketch.domain.Sizes
import io.embodit.airsketch.domain.StrokeBuilder
import io.embodit.airsketch.domain.StrokeData
import io.embodit.airsketch.domain.StrokeMesher
import io.embodit.airsketch.domain.Vec3
import io.embodit.airsketch.domain.Zone
import io.embodit.airsketch.platform.ControllerStick
import io.embodit.airsketch.platform.SketchLog
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlin.math.sin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** What the scene tells the controller (main thread). */
interface SceneListener {
    fun onStrokeStarted()

    /** The pen was lifted: [stroke] is null when it was too short to keep. [full]: it stopped at the limit. */
    fun onStrokeEnded(stroke: StrokeData?, full: Boolean)

    /** The nib was moved by a tap on the dots or a drag of its shadow. */
    fun onNibMoved()

    fun onTurning(turning: Boolean)

    /** The base came to rest at a new angle (save it). */
    fun onTurned(yawDeg: Float)

    /** A drag on the nib / shadow / base began (palette collapses, design S06 exits). */
    fun onAnyDrag()
}

/** What the controller allows right now (design states[].scene, per-state 3D gesture rules). */
data class SceneMode(
    val nib: Boolean = false,
    val plane: Boolean = false,
    val planeTappable: Boolean = false,
    val turn: Boolean = false,
    val mirrorPlane: Boolean = false,
    val display: Boolean = false,
    val spin: Boolean = false,
    val fastBreath: Boolean = false,
    /** Onboarding step 2: the dots stay lit while drawing. */
    val planeWhileDrawing: Boolean = false,
)

/**
 * The volume's 3D content (design entities3d), owned on the ECS side. A main-thread frame loop (the reference
 * repo's clock) runs the nib tween, the base turn, the stick, the live stroke tail, glow and breathing; Compose
 * only sends discrete gesture events here.
 *
 * Frames: "volume" = design volume coordinates (origin on the volume floor centre, +Y up, +Z towards the user);
 * "art" = the base's frame (the volume frame turned by [yaw] about the base axis). Strokes live in art.
 */
class SketchScene(private val listener: SceneListener, private val stick: ControllerStick) {
    companion object {
        /** SpatialView origin is the volume centre; the design origin is the floor centre (volume 0.56 × 0.62 × 0.56). */
        val ROOT_OFFSET = Vec3(0f, -0.31f, 0f)
        val VOL_MIN = Vec3(-0.28f, 0f, -0.28f)
        val VOL_MAX = Vec3(0.28f, 0.62f, 0.28f)
        const val BASE_THICK = 0.025f
        const val PLANE_R = 0.16f
        const val SHADOW_D = 0.04f
        const val TURN_PER_M = (PI / 0.30).toFloat() // 0.30 m of sideways drag = 180°
        const val TAP_AFTER_DRAG_MS = 250L
        const val LIVE_BODY_EVERY = 64
        private const val STICK_DEAD = 0.15f
        private const val STICK_RATE = (PI / 2).toFloat()
        private const val SPIN_RATE = (2 * PI / 40).toFloat()
        private val UP = Vec3(0f, 1f, 0f)
    }

    private enum class Drag { NONE, DRAW, SHADOW, TURN }

    private val shared = SharedResources()
    private lateinit var mats: Materials
    private lateinit var physics: PhysicsMaterialResource
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var loop: Job? = null
    private var content: SpatialViewContent? = null
    private val panels = HashMap<String, Entity>()

    val root = Entity().named("sketch:root")
    private val art = Entity().named("art")
    private val baseHolder = Entity().named("base")
    private lateinit var ticks: ModelEntity
    private lateinit var ticksHot: ModelEntity
    private val nibRoot = Entity().named("nib")
    private val nibSpheres = HashMap<Int, ModelEntity>()
    private lateinit var nibRing: ModelEntity
    private lateinit var sphereMesh: MeshResource
    private var plane: ModelEntity? = null
    private var planeMesh: MeshResource? = null
    private val planeCollider = Entity().named("plane:collider")
    private lateinit var shadow: ModelEntity
    private var dash: ModelEntity? = null
    private var dashMesh: MeshResource? = null
    private val mirrorRoot = Entity().named("mirror")
    private lateinit var glow: ModelEntity

    // state
    var yaw = 0f
        private set
    private var yawFrom = 0f
    private var yawTo: Float? = null
    private var yawT = 0f
    private var spinPausedUntil = 0L
    var nib = Zone.NIB_HOME
        private set
    private var nibFrom = nib
    private var nibTo: Vec3? = null
    private var nibT = 0f
    private var nibDirty = true
    var brush = Brush.INK
    var size = 1
    var color = 7
    var mirror = false
    var mode = SceneMode()
        private set
    private var drag = Drag.NONE
    private var lastDragEnd = 0L
    private var glowLeft = 0f
    private var turning = false
    var lastTap: String = "none"
        private set

    // live stroke
    private var live: StrokeBuilder? = null
    private var liveBodyEnd = 0
    private var liveDrawn = 0
    private val liveEntities = ArrayList<ModelEntity>()
    private val liveMeshes = arrayOfNulls<MeshResource>(4)

    // debug injection (stroke <path>): moves the nib along a path through the same drag pipeline
    private var inject: List<Vec3>? = null
    private var injectAt = 0
    private var injectHoldAt = -1

    // batches: per brush, chunks of ≤ 60k vertices
    private inner class Chunk(val brush: Brush) {
        val strokes = ArrayList<StrokeData>()
        var verts = 0
        var entity: ModelEntity? = null
        var mesh: MeshResource? = null
    }

    private val chunks = HashMap<Brush, ArrayList<Chunk>>()
    private val maxChunkVerts = 60_000

    private var lastNs = 0L
    var frames = 0L
        private set

    // ------------------------------------------------------------------------------------------
    // setup

    fun attach(content: SpatialViewContent, attachments: SpatialViewAttachments) {
        this.content = content
        mats = Materials(shared)
        physics = PhysicsMaterialResource(0.8f, 0.65f, 0f)
        root.setLocal(position = ROOT_OFFSET)
        content.addEntity(root)
        root.addChild(art)
        // base (turns with the art frame)
        art.addChild(baseHolder)
        val cyl = shared.keep(Meshes.cylinder("base"))
        model(cyl, mats.graphite, "base:disc", baseHolder).setLocal(position = Vec3(0f, Zone.BASE_TOP - BASE_THICK / 2, 0f), scale = Vec3(Zone.BASE_R * 2, BASE_THICK, Zone.BASE_R * 2))
        val tickMesh = shared.keep(Meshes.fromData(tickData(), "base:ticks"))
        ticks = model(tickMesh, mats.tick, "base:ticks", baseHolder)
        ticksHot = model(tickMesh, mats.tickHot, "base:ticksHot", baseHolder).also { it.enabled = false }
        val baseShape = runCatching { ShapeResource.createConvexMesh(MeshResource.createCylinder(BASE_THICK, Zone.BASE_R)) }.getOrElse { ShapeResource.createBox(Vector3(Zone.BASE_R * 2, BASE_THICK, Zone.BASE_R * 2)) }
        val baseCollider = Entity().named("base:collider")
        baseHolder.addChild(baseCollider)
        baseCollider.setLocal(position = Vec3(0f, Zone.BASE_TOP - BASE_THICK / 2, 0f))
        baseCollider.components.set(CollisionComponent(listOf(baseShape), physics))
        baseCollider.components.set(InteractableComponent())
        baseCollider.components.set(HoverEffectComponent())
        // mirror plane (art frame, x = 0)
        art.addChild(mirrorRoot)
        val cube = shared.keep(MeshResource.createBox(Vector3(1f, 1f, 1f), 0f))
        val zh = Zone.Y1 - Zone.Y0
        model(cube, mats.mirrorFace, "mirror:face", mirrorRoot).setLocal(position = Vec3(0f, Zone.Y0 + zh / 2, 0f), scale = Vec3(0.0005f, zh, Zone.R * 2))
        for (z in floatArrayOf(-Zone.R, Zone.R)) model(cube, mats.mirrorEdge, "mirror:edge", mirrorRoot).setLocal(position = Vec3(0f, Zone.Y0 + zh / 2, z), scale = Vec3(0.0015f, zh, 0.0015f))
        mirrorRoot.enabled = false
        // nib
        root.addChild(nibRoot)
        sphereMesh = shared.keep(MeshResource.createSphere(0.5f))
        val ringMesh = shared.keep(Meshes.ring("nib:ring", 0.40f, facingZ = true))
        nibRing = model(ringMesh, mats.white, "nib:ring", nibRoot)
        nibRoot.components.set(CollisionComponent(listOf(ShapeResource.createSphere(0.03f)), physics))
        nibRoot.components.set(InteractableComponent())
        nibRoot.components.set(HoverEffectComponent())
        // touch plane: dots + a collider the size of the lit patch
        root.addChild(planeCollider)
        planeCollider.components.set(InteractableComponent())
        // shadow on the base top + dashed line up to the nib
        val disc = shared.keep(Meshes.disc("shadow"))
        shadow = model(disc, mats.shadow, "nib:shadow", root)
        shadow.setLocal(scale = Vec3(SHADOW_D, 1f, SHADOW_D))
        shadow.components.set(CollisionComponent(listOf(ShapeResource.createBox(Vector3(0.05f / SHADOW_D, 0.012f, 0.05f / SHADOW_D))), physics))
        shadow.components.set(InteractableComponent())
        shadow.components.set(HoverEffectComponent())
        // boundary glow ring
        val glowMesh = shared.keep(Meshes.ring("glow", 0.47f))
        glow = model(glowMesh, mats.glow, "zone:glow", root).also { it.enabled = false }
        glow.setLocal(scale = Vec3(Zone.R * 2 + 0.006f, 1f, Zone.R * 2 + 0.006f))
        for (id in PanelIds.ALL) {
            attachments.entity(id)?.let {
                content.addEntity(it)
                panels[id] = it
                hidePanel(id)
            }
        }
        applyNib()
        applyMode()
        lastNs = SystemClock.elapsedRealtimeNanos()
        loop = scope.launch {
            while (isActive) {
                delay(16)
                val now = SystemClock.elapsedRealtimeNanos()
                val dt = ((now - lastNs) / 1e9f).coerceIn(0.001f, 0.1f)
                lastNs = now
                runCatching { frame(dt) }.onFailure { SketchLog.e("FRAME failed: $it", it) }
            }
        }
        SketchLog.i("SCENE attached panels=${panels.keys}")
    }

    private fun tickData() = MeshBuilder(24 * 24).also { b ->
        for (k in 0 until 24) {
            val a = k / 24f * 2 * PI.toFloat()
            val len = if (k % 6 == 0) 0.024f else 0.014f
            val r = Zone.BASE_R - 0.012f
            // a thin bar pointing outwards; boxes are axis-aligned, so build it in a rotated frame by hand
            val dir = Vec3(sin(a), 0f, kotlin.math.cos(a))
            val side = Vec3(dir.z, 0f, -dir.x)
            val c = Vec3(dir.x * r, Zone.BASE_TOP + 0.001f, dir.z * r)
            val hw = 0.002f
            val hl = len / 2
            val p = listOf(c + side * hw + dir * hl, c - side * hw + dir * hl, c - side * hw - dir * hl, c + side * hw - dir * hl)
            val i0 = b.vertex(p[0], UP, 0.5f, 0.5f); val i1 = b.vertex(p[1], UP, 0.5f, 0.5f); val i2 = b.vertex(p[2], UP, 0.5f, 0.5f); val i3 = b.vertex(p[3], UP, 0.5f, 0.5f)
            b.tri(i0, i1, i2); b.tri(i0, i2, i3)
        }
    }.build()

    // ------------------------------------------------------------------------------------------
    // what is shown / allowed

    fun setMode(m: SceneMode) {
        if (m == mode) return
        mode = m
        if (!m.nib && drag == Drag.DRAW) endStroke()
        if (!m.display) spinPausedUntil = 0L
        applyMode()
    }

    private fun applyMode() {
        if (!::shadow.isInitialized) return
        val drawing = drag == Drag.DRAW
        nibRoot.enabled = mode.nib
        shadow.enabled = mode.nib
        dash?.enabled = mode.nib
        val planeOn = mode.nib && mode.plane && (!drawing || mode.planeWhileDrawing)
        plane?.enabled = planeOn
        planeCollider.enabled = planeOn && mode.planeTappable && !drawing
        mirrorRoot.enabled = mirror && mode.mirrorPlane
        nibDirty = true
    }

    fun setTools(brush: Brush, size: Int, color: Int, mirror: Boolean) {
        this.brush = brush
        this.size = size
        this.color = color
        this.mirror = mirror
        applyNib()
        applyMode()
    }

    private fun applyNib() {
        if (!::sphereMesh.isInitialized) return
        nibSpheres.values.forEach { it.enabled = false }
        val s = nibSpheres.getOrPut(color) { model(sphereMesh, mats.nib(color), "nib:ball$color", nibRoot) }
        s.enabled = true
        val d = Sizes.NIB_M[size.coerceIn(0, 2)]
        s.setLocal(scale = Vec3(d, d, d))
        nibRing.setLocal(scale = Vec3(0.04f, 0.04f, 1f))
    }

    // ------------------------------------------------------------------------------------------
    // strokes on the base (batched per brush)

    fun setStrokes(list: List<StrokeData>, yawDeg: Float) {
        chunks.values.flatten().forEach { c -> runCatching { c.entity?.destroy() } }
        chunks.clear()
        for (s in list) place(s)
        chunks.values.flatten().forEach { rebuild(it) }
        yaw = yawDeg * PI.toFloat() / 180f
        yawTo = null
        applyYaw()
        SketchLog.i("MESH set strokes=${list.size} chunks=${chunks.values.sumOf { it.size }} verts=${chunks.values.flatten().sumOf { it.verts }}")
    }

    private fun place(s: StrokeData): Chunk {
        val list = chunks.getOrPut(s.brush) { ArrayList() }
        val cost = StrokeMesher.vertexCost(s)
        val c = list.lastOrNull()?.takeIf { it.verts + cost <= maxChunkVerts } ?: Chunk(s.brush).also { list += it }
        c.strokes += s
        c.verts += cost
        return c
    }

    fun addStroke(s: StrokeData) {
        rebuild(place(s))
    }

    fun removeStroke(s: StrokeData) {
        val list = chunks[s.brush] ?: return
        val c = list.firstOrNull { ch -> ch.strokes.any { it === s } } ?: return
        c.strokes.removeAll { it === s }
        c.verts -= StrokeMesher.vertexCost(s)
        if (c.strokes.isEmpty()) {
            runCatching { c.entity?.destroy() }
            list.remove(c)
        } else rebuild(c)
    }

    private fun rebuild(c: Chunk) {
        val t0 = SystemClock.elapsedRealtime()
        val b = MeshBuilder(c.verts + 16)
        c.strokes.forEach { StrokeMesher.append(b, it) }
        val data = b.build()
        if (data.vertexCount == 0) return
        val t1 = SystemClock.elapsedRealtime()
        val (model, bounds) = Meshes.model(data)
        val t2 = SystemClock.elapsedRealtime()
        val mesh = c.mesh
        if (mesh == null || c.entity == null) {
            val m = MeshResource.createWithMeshModel(model = model, bounds = bounds, name = "batch:${c.brush.id}")
            c.mesh = m
            c.entity = model(m, mats.brush(c.brush), "batch:${c.brush.id}", art)
        } else {
            mesh.replaceWithMeshModel(model, bounds)
            c.entity?.components?.get(ModelComponent::class.java)?.mesh = mesh
        }
        SketchLog.i("MESH batch ${c.brush.id} verts=${data.vertexCount} build=${t1 - t0}ms model=${t2 - t1}ms upload=${SystemClock.elapsedRealtime() - t2}ms")
    }

    fun batchCount(): Int = chunks.values.sumOf { it.size }

    // ------------------------------------------------------------------------------------------
    // input (detectSpatialDragGesture / detectSpatialTapGesture)

    fun dragStart(target: Entity?) {
        drag = Drag.NONE
        if (mode.nib && isUnder(target, nibRoot)) {
            beginStroke()
        } else if (mode.nib && isUnder(target, shadow)) {
            drag = Drag.SHADOW
            nibTo = null
            listener.onAnyDrag()
        } else if (mode.turn && isUnder(target, baseHolder)) {
            drag = Drag.TURN
            yawTo = null
            setTurning(true)
            listener.onAnyDrag()
            if (mode.display) spinPausedUntil = SystemClock.elapsedRealtime() + 10_000
        }
        SketchLog.i("DRAG start kind=$drag target=${runCatching { target?.getName() }.getOrNull()}")
    }

    fun dragMove(delta: Vec3) {
        when (drag) {
            Drag.DRAW -> {
                val (p, hit) = Zone.clamp(nib + delta)
                nib = p
                if (hit) glowAt(p.y)
                live?.let { b ->
                    b.feed(Zone.toArt(p, yaw), SystemClock.elapsedRealtime(), hit)
                    if (b.full) endStroke()
                }
                nibDirty = true
            }
            Drag.SHADOW -> {
                val (p, hit) = Zone.clamp(nib + Vec3(delta.x, 0f, delta.z))
                if (hit) glowAt(p.y)
                nib = p
                nibDirty = true
            }
            Drag.TURN -> {
                yaw += delta.x * TURN_PER_M
                applyYaw()
                if (mode.display) spinPausedUntil = SystemClock.elapsedRealtime() + 10_000
            }
            Drag.NONE -> Unit
        }
    }

    fun dragEnd() {
        when (drag) {
            Drag.DRAW -> endStroke()
            Drag.SHADOW -> {
                SketchLog.i("NIB shadow -> ${SketchLog.pos(nib)}")
                listener.onNibMoved()
            }
            Drag.TURN -> {
                setTurning(false)
                listener.onTurned(yawDeg())
            }
            Drag.NONE -> Unit
        }
        drag = Drag.NONE
        lastDragEnd = SystemClock.elapsedRealtime()
        applyMode()
    }

    /** Window lost focus: a stroke in progress ends as if released (design lifecycle). */
    fun dropDrag() {
        if (drag != Drag.NONE) dragEnd()
        inject = null
    }

    fun tap(target: Entity?, px: Vector3) {
        if (!isUnder(target, planeCollider) || !planeCollider.enabled) return
        if (SystemClock.elapsedRealtime() - lastDragEnd < TAP_AFTER_DRAG_MS) {
            SketchLog.i("TAP ignored (just after a drag)")
            return
        }
        val c = content ?: return
        val hit = runCatching { c.convertPosition(px, ViewCoordinateSpace.Local, c.localSpatialCoordinateSpace) }.getOrNull()
        if (hit == null) {
            SketchLog.w("TAP conversion failed px=(${px.x}, ${px.y}, ${px.z})")
            return
        }
        val v = Vec3(hit.x, hit.y, hit.z) - ROOT_OFFSET
        lastTap = "px=(${"%.1f".format(px.x)}, ${"%.1f".format(px.y)}, ${"%.1f".format(px.z)}) hit=${SketchLog.pos(v)} nibZ=${"%.3f".format(nib.z)}"
        SketchLog.i("TAP plane $lastTap")
        moveNibTo(Vec3(v.x, v.y, nib.z))
        listener.onNibMoved()
    }

    /** Glides the nib to [p] (clamped into the zone) in 0.15 s (design touchPlane). */
    fun moveNibTo(p: Vec3, animate: Boolean = true) {
        val (q, hit) = Zone.clamp(p)
        if (hit) glowAt(q.y)
        if (!animate) {
            nib = q
            nibTo = null
            nibDirty = true
            return
        }
        nibFrom = nib
        nibTo = q
        nibT = 0f
    }

    fun nibToArtPoint(a: Vec3?, animate: Boolean = false) {
        moveNibTo(if (a == null) Zone.NIB_HOME else Zone.toVolume(a, yaw), animate)
    }

    private fun beginStroke() {
        live = null
        drag = Drag.DRAW
        nibTo = null
        listener.onAnyDrag()
        listener.onStrokeStarted()
        val budget = budgetProvider()
        val b = StrokeBuilder(brush, size, color, mirror, yawDeg(), budget)
        b.start(Zone.toArt(nib, yaw), SystemClock.elapsedRealtime())
        live = b
        liveBodyEnd = 0
        liveDrawn = 0
        applyMode()
        SketchLog.i("STROKE start brush=${brush.id} size=$size color=$color mirror=$mirror at=${SketchLog.pos(nib)} yaw=${yawDeg().roundToInt()} budget=$budget")
    }

    /** Points the next stroke may use (set by the controller from the sketch's limits). */
    var budgetProvider: () -> Int = { Int.MAX_VALUE }

    private fun endStroke() {
        val b = live ?: return
        live = null
        drag = Drag.NONE
        val s = b.finish()
        SketchLog.i("STROKE end raw=${b.count} kept=${s?.count ?: 0} full=${b.full}")
        if (s != null) listener.onStrokeEnded(s, b.full) else listener.onStrokeEnded(null, b.full)
        clearLive()
        lastDragEnd = SystemClock.elapsedRealtime()
        applyMode()
    }

    private fun clearLive() {
        liveEntities.forEach { runCatching { it.destroy() } }
        liveEntities.clear()
        for (i in liveMeshes.indices) liveMeshes[i] = null
        liveBodyEnd = 0
        liveDrawn = 0
    }

    /** Body (rebuilt every 64 points) + tail (rebuilt each frame), ×2 when mirrored (design strokeRules.live). */
    private fun updateLive() {
        val b = live ?: return
        val n = b.count
        if (n < 2 || n == liveDrawn) return
        val pts = b.raw()
        if (n - liveBodyEnd > LIVE_BODY_EVERY || liveBodyEnd == 0 && n > LIVE_BODY_EVERY) {
            liveBodyEnd = n - 1
            for (m in 0 until if (b.mirrored) 2 else 1) setLive(m * 2, pts, 0, liveBodyEnd + 1, b, m == 1)
        }
        val from = max(0, liveBodyEnd)
        for (m in 0 until if (b.mirrored) 2 else 1) setLive(m * 2 + 1, pts, from, n, b, m == 1)
        liveDrawn = n
    }

    private fun setLive(slot: Int, pts: FloatArray, from: Int, to: Int, b: StrokeBuilder, mirrored: Boolean) {
        if (to - from < 2) return
        val mb = MeshBuilder(StrokeMesher.vertexCost(b.brush, to - from) + 8)
        StrokeMesher.append(mb, pts, from, to, b.brush, b.color, b.faceYawDeg, mirrored, true)
        val data = mb.build()
        val mesh = liveMeshes[slot]
        if (mesh == null) {
            val m = Meshes.fromData(data, "live$slot")
            liveMeshes[slot] = m
            liveEntities += model(m, mats.brush(b.brush), "live$slot", art)
        } else {
            val (model, bounds) = Meshes.model(data)
            mesh.replaceWithMeshModel(model, bounds)
        }
    }

    val drawing: Boolean get() = drag == Drag.DRAW

    // ------------------------------------------------------------------------------------------
    // base turning

    fun turn90() {
        if (!mode.turn) return
        val q = PI.toFloat() / 2
        val from = yawTo ?: yaw
        yawFrom = yaw
        yawTo = (floor(from / q + 1e-3f) + 1) * q
        yawT = 0f
        setTurning(true)
    }

    fun setYawDeg(deg: Float) {
        yaw = deg * PI.toFloat() / 180f
        yawTo = null
        applyYaw()
    }

    fun yawDeg(): Float {
        val d = (yaw * 180f / PI.toFloat()) % 360f
        return if (d < 0) d + 360f else d
    }

    private fun setTurning(on: Boolean) {
        if (turning == on) return
        turning = on
        ticks.enabled = !on
        ticksHot.enabled = on
        listener.onTurning(on)
    }

    private fun applyYaw() {
        art.setLocal(rotation = Quat4.axisAngle(UP, yaw))
    }

    // ------------------------------------------------------------------------------------------
    // debug seams

    /** `stroke <path>`: nib jumps to the path start, then is dragged along it frame by frame (T1 pipeline). */
    fun debugStroke(path: String, half: Boolean) {
        val pts = Samples.path(path)
        moveNibTo(pts.first(), animate = false)
        inject = pts
        injectAt = 0
        injectHoldAt = if (half) pts.size / 2 else -1
        dragStart(nibRoot)
    }

    fun debugRelease() {
        inject = null
        if (drag != Drag.NONE) dragEnd()
    }

    fun debugDragShadow(dx: Float, dz: Float) {
        if (!mode.nib) return
        drag = Drag.SHADOW
        dragMove(Vec3(dx, 0f, dz))
        dragEnd()
    }

    /** `turn` via a drag on the base of [dx] metres (the gesture path, for S08). */
    fun debugDragBase(dx: Float) {
        dragStart(baseHolder)
        dragMove(Vec3(dx, 0f, 0f))
        dragEnd()
    }

    /** A drag on the base of [dx] metres that stays open (S08 capture); `release` ends it. */
    fun debugHoldBase(dx: Float) {
        dragStart(baseHolder)
        dragMove(Vec3(dx, 0f, 0f))
    }

    /** Stroke points or scene parts outside the zone / volume (design acceptance: 0 at any time). */
    fun boundsCheck(strokes: List<StrokeData>): List<String> {
        val out = ArrayList<String>()
        strokes.forEachIndexed { si, s ->
            for (m in 0 until if (s.mirrored) 2 else 1) for (i in 0 until s.count) {
                val a = s.point(i).let { if (m == 1) Vec3(-it.x, it.y, it.z) else it }
                val v = Zone.toVolume(a, yaw)
                val r = s.radius(i) * if (s.brush == Brush.RIBBON) 2.5f else 1f
                if (!insideVolume(v, r)) {
                    out += "stroke$si:$i@${SketchLog.pos(v)}"
                    break
                }
            }
        }
        if (mode.nib && !insideVolume(nib, 0.03f)) out += "nib@${SketchLog.pos(nib)}"
        if (!Zone.inside(nib)) out += "nibZone@${SketchLog.pos(nib)}"
        return out
    }

    private fun insideVolume(v: Vec3, r: Float) =
        v.x - r >= VOL_MIN.x && v.x + r <= VOL_MAX.x && v.y - r >= VOL_MIN.y && v.y + r <= VOL_MAX.y && v.z - r >= VOL_MIN.z && v.z + r <= VOL_MAX.z

    // ------------------------------------------------------------------------------------------
    // attachment panels: shown in place or shrunk to nothing (kept enabled)

    fun showPanel(id: String, at: Vec3, rotation: Quat4 = Quat4.IDENTITY) {
        panels[id]?.setLocal(position = at + ROOT_OFFSET, rotation = rotation, scale = Vec3(1f, 1f, 1f))
    }

    fun hidePanel(id: String) {
        panels[id]?.setLocal(position = Vec3(0f, 0.05f, -0.2f) + ROOT_OFFSET, scale = Vec3(0.001f, 0.001f, 0.001f))
    }

    /** Where the hint bubble floats (design spatialLayout.hintBubble). */
    fun hintAnchor(): Vec3 {
        val below = nib.y > 0.50f
        return Vec3(nib.x.coerceIn(-0.19f, 0.19f), nib.y + if (below) -0.06f else 0.06f, (nib.z + 0.03f).coerceAtMost(0.25f))
    }

    var onNibPlaced: (() -> Unit)? = null

    // ------------------------------------------------------------------------------------------
    // the frame

    private fun frame(dt: Float) {
        frames++
        val now = SystemClock.elapsedRealtime()
        // debug injection: a few path points per frame (~0.4 m/s)
        inject?.let { path ->
            if (drag != Drag.DRAW) {
                inject = null
                return@let
            }
            if (injectHoldAt >= 0 && injectAt >= injectHoldAt) return@let
            val to = min(path.size - 1, injectAt + 3)
            for (i in injectAt + 1..to) dragMove(path[i] - nib)
            injectAt = to
            if (injectAt >= path.size - 1) {
                inject = null
                dragEnd()
            }
        }
        // nib glide
        nibTo?.let { t ->
            nibT = min(1f, nibT + dt / 0.15f)
            val e = 1f - (1f - nibT) * (1f - nibT)
            nib = nibFrom.lerp(t, e)
            nibDirty = true
            if (nibT >= 1f) {
                nibTo = null
                SketchLog.i("NIB at ${SketchLog.pos(nib)}")
            }
        }
        // stick turns the base (factory default D1)
        val x = stick.x(now)
        val push = if (abs(x) < STICK_DEAD) 0f else sign(x) * ((abs(x) - STICK_DEAD) / (1 - STICK_DEAD)).let { it * it }
        if (mode.turn && push != 0f && drag != Drag.TURN) {
            yaw += push * STICK_RATE * dt
            yawTo = null
            setTurning(true)
            stickTurning = true
            applyYaw()
            if (mode.display) spinPausedUntil = now + 10_000
        } else if (stickTurning) {
            stickTurning = false
            setTurning(false)
            listener.onTurned(yawDeg())
        }
        // turn 90° tween
        yawTo?.let { t ->
            yawT = min(1f, yawT + dt / 0.3f)
            val e = 1f - (1f - yawT) * (1f - yawT) * (1f - yawT)
            yaw = yawFrom + (t - yawFrom) * e
            applyYaw()
            if (yawT >= 1f) {
                yaw = t
                yawTo = null
                applyYaw()
                setTurning(false)
                listener.onTurned(yawDeg())
            }
        }
        // display: one turn in 40 s
        if (mode.display && mode.spin && drag != Drag.TURN && yawTo == null && now >= spinPausedUntil) {
            yaw += SPIN_RATE * dt
            applyYaw()
        }
        // live stroke
        updateLive()
        // breathing nib
        if (mode.nib && drag != Drag.DRAW) {
            val period = if (mode.fastBreath) 1f else 2f
            val k = 1f + 0.08f * sin(now / 1000f * 2 * PI.toFloat() / period)
            nibRing.setLocal(scale = Vec3(0.04f * k, 0.04f * k, 1f))
        }
        // boundary glow
        if (glowLeft > 0f) {
            glowLeft -= dt
            glow.setOpacity((glowLeft / 0.3f).coerceIn(0f, 1f))
            if (glowLeft <= 0f) glow.enabled = false
        }
        if (nibDirty) {
            nibDirty = false
            placeNibParts()
        }
    }

    private var stickTurning = false

    private fun glowAt(y: Float) {
        glow.setLocal(position = Vec3(0f, y, 0f))
        glow.enabled = true
        glow.setOpacity(1f)
        glowLeft = 0.3f
    }

    private fun placeNibParts() {
        nibRoot.setLocal(position = nib)
        shadow.setLocal(position = Vec3(nib.x, Zone.BASE_TOP + 0.002f, nib.z))
        // dashed line from the shadow up to the nib
        val b = MeshBuilder(256)
        var y = Zone.BASE_TOP + 0.008f
        while (y + 0.006f < nib.y - 0.016f) {
            Meshes.box(b, Vec3(nib.x, y + 0.003f, nib.z), Vec3(0.0008f, 0.003f, 0.0008f))
            y += 0.012f
        }
        val data = b.build()
        if (data.vertexCount > 0) {
            val m = dashMesh
            if (m == null || dash == null) {
                dashMesh = Meshes.fromData(data, "dash")
                dash = model(dashMesh!!, mats.dash, "nib:dash", root)
            } else {
                val (model, bounds) = Meshes.model(data)
                m.replaceWithMeshModel(model, bounds)
            }
            dash?.enabled = mode.nib
        } else dash?.enabled = false
        if (mode.nib && mode.plane) placePlane()
        onNibPlaced?.invoke()
    }

    /** The dot patch: Ø 0.32 m about the nib at its depth, its lower edge ≥ nib y − 0.10, cut to the zone heights and the volume. */
    private fun placePlane() {
        val x0 = max(nib.x - PLANE_R, VOL_MIN.x + 0.01f)
        val x1 = min(nib.x + PLANE_R, VOL_MAX.x - 0.01f)
        val y0 = max(max(nib.y - 0.10f, Zone.Y0), nib.y - PLANE_R)
        val y1 = min(nib.y + PLANE_R, Zone.Y1)
        if (x1 - x0 < 0.01f || y1 - y0 < 0.01f) return
        val z = nib.z - 0.001f
        val b = MeshBuilder(8)
        fun uv(x: Float, y: Float) = Pair((x - (nib.x - PLANE_R)) / (2 * PLANE_R), 1f - (y - (nib.y - PLANE_R)) / (2 * PLANE_R))
        val n = Vec3(0f, 0f, 1f)
        val corners = listOf(Vec3(x0, y0, z), Vec3(x1, y0, z), Vec3(x1, y1, z), Vec3(x0, y1, z))
        val ids = corners.map { val (u, v) = uv(it.x, it.y); b.vertex(it, n, u, v) }
        b.tri(ids[0], ids[1], ids[2]); b.tri(ids[0], ids[2], ids[3])
        val data = b.build()
        val m = planeMesh
        if (m == null || plane == null) {
            planeMesh = Meshes.fromData(data, "plane")
            plane = model(planeMesh!!, mats.dots(), "plane:dots", root)
        } else {
            val (model, bounds) = Meshes.model(data)
            m.replaceWithMeshModel(model, bounds)
        }
        planeCollider.setLocal(position = Vec3((x0 + x1) / 2, (y0 + y1) / 2, z))
        planeCollider.components.set(CollisionComponent(listOf(ShapeResource.createBox(Vector3(x1 - x0, y1 - y0, 0.004f))), physics))
        applyModeVisibilityOnly()
    }

    private fun applyModeVisibilityOnly() {
        val drawing = drag == Drag.DRAW
        val planeOn = mode.nib && mode.plane && (!drawing || mode.planeWhileDrawing)
        plane?.enabled = planeOn
        planeCollider.enabled = planeOn && mode.planeTappable && !drawing
    }

    fun close() {
        scope.cancel()
        clearLive()
        runCatching { root.destroy() }
        chunks.clear()
        panels.clear()
        shared.close()
        content = null
        SketchLog.i("SCENE closed")
    }

    fun dump(): String =
        "yaw=${yawDeg().roundToInt()} nib=${SketchLog.pos(nib)} drag=$drag live=${live?.count} batches=${batchCount()} " +
            "verts=${chunks.values.flatten().sumOf { it.verts }} mode=$mode frames=$frames stick=${stick.status()} lastTap=$lastTap"
}

object PanelIds {
    const val TOOLBAR = "toolbar"
    const val HINT = "hint"
    const val PILL = "pill"
    val ALL = listOf(TOOLBAR, HINT, PILL)
}
