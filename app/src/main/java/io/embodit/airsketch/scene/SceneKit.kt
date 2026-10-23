package io.embodit.airsketch.scene

import android.graphics.Bitmap
import androidx.compose.ui.graphics.Color
import com.pico.spatial.core.ecs.BoundingBox
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.ModelEntity
import com.pico.spatial.core.ecs.OpacityControllerComponent
import com.pico.spatial.core.ecs.TransformComponent
import com.pico.spatial.core.ecs.resource.BlendingMode
import com.pico.spatial.core.ecs.resource.Material
import com.pico.spatial.core.ecs.resource.MaterialCullingMode
import com.pico.spatial.core.ecs.resource.MeshModel
import com.pico.spatial.core.ecs.resource.MeshResource
import com.pico.spatial.core.ecs.resource.PhysicallyBasedMaterial
import com.pico.spatial.core.ecs.resource.Resource
import com.pico.spatial.core.ecs.resource.TextureResource
import com.pico.spatial.core.ecs.resource.UnlitMaterial
import com.pico.spatial.core.math.Color4
import com.pico.spatial.core.math.Quat
import com.pico.spatial.core.math.Vector2
import com.pico.spatial.core.math.Vector3
import io.embodit.airsketch.domain.Brush
import io.embodit.airsketch.domain.MeshBuilder
import io.embodit.airsketch.domain.MeshData
import io.embodit.airsketch.domain.Palette
import io.embodit.airsketch.domain.Quat4
import io.embodit.airsketch.domain.Vec3
import io.embodit.airsketch.domain.boundsOf
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

// domain <-> SDK types; the domain layer owns the math so it tests on a plain JVM

internal fun Vec3.sdk(): Vector3 = Vector3(x, y, z)

internal fun Quat4.sdk(): Quat = Quat(x, y, z, w)

internal fun Color.color4(alpha: Float = 1f): Color4 = Color4(red, green, blue, alpha)

internal fun argb4(c: Int, alpha: Float = 1f): Color4 = Color4(((c shr 16) and 0xFF) / 255f, ((c shr 8) and 0xFF) / 255f, (c and 0xFF) / 255f, alpha)

internal val Entity.transformOrNull: TransformComponent?
    get() = components[TransformComponent::class.java]

internal fun Entity.setLocal(position: Vec3? = null, rotation: Quat4? = null, scale: Vec3? = null) {
    val t = transformOrNull ?: return
    if (position != null) t.setPosition(position.sdk())
    if (rotation != null) t.setQuaternion(rotation.sdk())
    if (scale != null) t.setScaleVector(scale.sdk())
}

internal fun Entity.setOpacity(opacity: Float) {
    val existing = components[OpacityControllerComponent::class.java]
    if (existing != null) existing.opacity = opacity.coerceIn(0f, 1f) else components.set(OpacityControllerComponent(opacity.coerceIn(0f, 1f)))
}

internal fun Entity.named(name: String): Entity = also { runCatching { it.setName(name) } }

/** True when [e] is [ancestor] or hangs below it. */
internal fun isUnder(e: Entity?, ancestor: Entity): Boolean {
    var p = e
    while (p != null) {
        if (p == ancestor) return true
        p = p.getParent()
    }
    return false
}

/** Resources shared by entities that come and go: made global, closed when the scene goes away. */
internal class SharedResources {
    private val owned = ArrayList<Resource>()

    fun <T : Resource> keep(resource: T): T {
        resource.toGlobal()
        owned += resource
        return resource
    }

    fun close() {
        owned.forEach { runCatching { it.close() } }
        owned.clear()
    }
}

/** Design brand tokens and the interaction role. */
object SketchColors {
    const val GRAPHITE = 0xFF2E2C33.toInt()
    const val TICK = 0xFF8C8796.toInt()
    const val CORAL = 0xFFFF8A5C.toInt()
}

/** Materials of the scene. Strokes use one material per brush with a 12 × 1 palette texture (design strokeRules.bake). */
internal class Materials(private val shared: SharedResources) {
    val graphite: Material = lit(SketchColors.GRAPHITE, 0.8f, 0.10f)
    val tick: Material = lit(SketchColors.TICK, 0.6f, 0.25f)
    val tickHot: UnlitMaterial = solid(SketchColors.CORAL)
    val white: UnlitMaterial = solid(0xFFFFFFFF.toInt(), depthTest = false)
    val shadow: UnlitMaterial = sheer(0xFFFFFFFF.toInt(), 0.35f)
    val dash: UnlitMaterial = sheer(0xFFFFFFFF.toInt(), 0.30f)
    val mirrorFace: UnlitMaterial = sheer(0xFFFFFFFF.toInt(), 0.05f)
    val mirrorEdge: UnlitMaterial = sheer(0xFFFFFFFF.toInt(), 0.45f)
    val glow: UnlitMaterial = sheer(SketchColors.CORAL, 0.9f)
    private val nibs = HashMap<Int, UnlitMaterial>()
    private val brushes = HashMap<Brush, Material>()

    fun nib(color: Int): UnlitMaterial = nibs.getOrPut(color) { solid(Palette.ARGB[color]) }

    fun lit(c: Int, roughness: Float, glow: Float): Material =
        shared.keep(
            PhysicallyBasedMaterial.create(BlendingMode.OPAQUE).apply {
                setBaseColor(argb4(c))
                setMetallic(0f)
                setRoughness(roughness)
                setEmissiveColor(Color4(((c shr 16) and 0xFF) / 255f * glow, ((c shr 8) and 0xFF) / 255f * glow, (c and 0xFF) / 255f * glow, 1f))
                setCullingMode(MaterialCullingMode.NONE)
            }
        )

    fun solid(c: Int, depthTest: Boolean = true): UnlitMaterial =
        shared.keep(
            UnlitMaterial.create(BlendingMode.OPAQUE).apply {
                setBaseColor(argb4(c))
                runCatching { setApplyToneMapping(false) }
                if (!depthTest) runCatching { setDepthTest(false) }
                setCullingMode(MaterialCullingMode.NONE)
            }
        )

    fun sheer(c: Int, opacity: Float): UnlitMaterial =
        shared.keep(
            UnlitMaterial.create(BlendingMode.TRANSPARENT).apply {
                setBaseColor(argb4(c))
                setOpacity(opacity)
                runCatching { setApplyToneMapping(false) }
                setCullingMode(MaterialCullingMode.NONE)
                setDepthWrite(false)
            }
        )

    /** The dot patch of the touch plane: a texture with alpha, see-through. */
    fun dots(): UnlitMaterial {
        val tex = shared.keep(TextureResource.create(dotBitmap(128)))
        return shared.keep(
            UnlitMaterial.create(BlendingMode.TRANSPARENT).apply {
                setBaseColor(Color4(1f, 1f, 1f, 1f))
                setBaseColorTexture(tex)
                setOpacity(1f)
                runCatching { setApplyToneMapping(false) }
                setCullingMode(MaterialCullingMode.NONE)
                setDepthWrite(false)
            }
        )
    }

    /** Stroke material of [b]: base colour (and a share of emission) read from the palette texture through uv0. */
    fun brush(b: Brush): Material =
        brushes.getOrPut(b) {
            val colors = if (b == Brush.NEON) IntArray(Palette.SIZE) { Palette.brighten(Palette.ARGB[it]) } else Palette.ARGB
            val tex = shared.keep(TextureResource.create(paletteBitmap(colors)))
            when (b) {
                Brush.NEON ->
                    shared.keep(
                        UnlitMaterial.create(BlendingMode.OPAQUE).apply {
                            setBaseColor(Color4(1f, 1f, 1f, 1f))
                            setBaseColorTexture(tex)
                            runCatching { setApplyToneMapping(false) }
                            setCullingMode(MaterialCullingMode.NONE)
                        }
                    )
                else ->
                    shared.keep(
                        PhysicallyBasedMaterial.create(BlendingMode.OPAQUE).apply {
                            setBaseColor(Color4(1f, 1f, 1f, 1f))
                            setBaseColorTexture(tex)
                            setMetallic(0f)
                            setRoughness(if (b == Brush.RIBBON) 0.4f else 0.7f)
                            setEmissiveTexture(tex)
                            setEmissiveColor(Color4(0.30f, 0.30f, 0.30f, 1f))
                            setCullingMode(MaterialCullingMode.NONE)
                        }
                    )
            }
        }

    private fun paletteBitmap(colors: IntArray): Bitmap {
        val bmp = Bitmap.createBitmap(colors.size, 1, Bitmap.Config.ARGB_8888)
        colors.forEachIndexed { i, c -> bmp.setPixel(i, 0, c) }
        return bmp
    }

    /** Dots every 1/16 of the patch, fading towards the rim (design touchPlane: a radially fading dot grid). */
    private fun dotBitmap(n: Int): Bitmap {
        val bmp = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888)
        val step = n / 16f
        val c = n / 2f
        for (y in 0 until n) for (x in 0 until n) {
            val gx = ((x + 0.5f) / step) % 1f - 0.5f
            val gy = ((y + 0.5f) / step) % 1f - 0.5f
            val d = sqrt(gx * gx + gy * gy) * step
            val r = sqrt((x - c) * (x - c) + (y - c) * (y - c)) / c
            val inDot = (1.6f - d).coerceIn(0f, 1f)
            val fade = (1f - r * r).coerceIn(0f, 1f)
            val rim = if (r in 0.97f..1.0f) 0.25f else 0f
            val a = (0.85f * inDot * fade + rim).coerceIn(0f, 1f)
            bmp.setPixel(x, y, ((a * 255).toInt() shl 24) or 0xFFFFFF)
        }
        return bmp
    }
}

/** Builds SDK meshes. */
internal object Meshes {
    fun model(data: MeshData): Pair<MeshModel, BoundingBox> {
        val n = data.vertexCount
        val positions = ArrayList<Vector3>(n)
        val normals = ArrayList<Vector3>(n)
        val uvs = ArrayList<Vector2>(n)
        for (i in 0 until n) {
            positions += Vector3(data.positions[i * 3], data.positions[i * 3 + 1], data.positions[i * 3 + 2])
            normals += Vector3(data.normals[i * 3], data.normals[i * 3 + 1], data.normals[i * 3 + 2])
            uvs += Vector2(data.uvs[i * 2], data.uvs[i * 2 + 1])
        }
        val (c, h) = boundsOf(data)
        return MeshModel(positions = positions, triangleIndices = data.indices.toList(), normals = normals, uv0 = uvs) to BoundingBox(center = c.sdk(), halfExtent = h.sdk())
    }

    fun fromData(data: MeshData, name: String): MeshResource {
        val (m, b) = model(data)
        return MeshResource.createWithMeshModel(model = m, bounds = b, name = name)
    }

    /** A cylinder of diameter 1 and height 1 about local Y. */
    fun cylinder(name: String, seg: Int = 72): MeshResource {
        val b = MeshBuilder(seg * 8)
        val top = b.vertex(Vec3(0f, 0.5f, 0f), Vec3.UP, 0.5f, 0.5f)
        val bottom = b.vertex(Vec3(0f, -0.5f, 0f), Vec3(0f, -1f, 0f), 0.5f, 0.5f)
        for (i in 0 until seg) {
            val a0 = 2 * PI.toFloat() * i / seg
            val a1 = 2 * PI.toFloat() * (i + 1) / seg
            val x0 = 0.5f * cos(a0); val z0 = 0.5f * sin(a0); val x1 = 0.5f * cos(a1); val z1 = 0.5f * sin(a1)
            b.tri(top, b.vertex(Vec3(x1, 0.5f, z1), Vec3.UP, 0.5f, 0.5f), b.vertex(Vec3(x0, 0.5f, z0), Vec3.UP, 0.5f, 0.5f))
            b.tri(bottom, b.vertex(Vec3(x0, -0.5f, z0), Vec3(0f, -1f, 0f), 0.5f, 0.5f), b.vertex(Vec3(x1, -0.5f, z1), Vec3(0f, -1f, 0f), 0.5f, 0.5f))
            val p = b.vertex(Vec3(x0, -0.5f, z0), Vec3(x0 * 2, 0f, z0 * 2), 0.5f, 0.5f)
            val q = b.vertex(Vec3(x1, -0.5f, z1), Vec3(x1 * 2, 0f, z1 * 2), 0.5f, 0.5f)
            val r = b.vertex(Vec3(x1, 0.5f, z1), Vec3(x1 * 2, 0f, z1 * 2), 0.5f, 0.5f)
            val s = b.vertex(Vec3(x0, 0.5f, z0), Vec3(x0 * 2, 0f, z0 * 2), 0.5f, 0.5f)
            b.tri(p, r, q); b.tri(p, s, r)
        }
        return fromData(b.build(), name)
    }

    /** A flat ring between radii [inner] and 0.5 in the plane facing [normal] (+Y or +Z). */
    fun ring(name: String, inner: Float, facingZ: Boolean = false, seg: Int = 72): MeshResource {
        val b = MeshBuilder(seg * 2 + 2)
        val n = if (facingZ) Vec3(0f, 0f, 1f) else Vec3.UP
        for (i in 0..seg) {
            val a = 2 * PI.toFloat() * i / seg
            for (r in floatArrayOf(inner, 0.5f)) {
                val p = if (facingZ) Vec3(r * cos(a), r * sin(a), 0f) else Vec3(r * cos(a), 0f, r * sin(a))
                b.vertex(p, n, 0.5f, 0.5f)
            }
        }
        for (i in 0 until seg) {
            val a = i * 2; val bb = i * 2 + 1; val c = i * 2 + 3; val d = i * 2 + 2
            b.tri(a, c, bb); b.tri(a, d, c)
        }
        return fromData(b.build(), name)
    }

    /** A flat disc of diameter 1 in XZ facing +Y. */
    fun disc(name: String, seg: Int = 40): MeshResource {
        val b = MeshBuilder(seg + 2)
        val c = b.vertex(Vec3.ZERO, Vec3.UP, 0.5f, 0.5f)
        for (i in 0..seg) {
            val a = 2 * PI.toFloat() * i / seg
            b.vertex(Vec3(0.5f * cos(a), 0f, 0.5f * sin(a)), Vec3.UP, 0.5f, 0.5f)
        }
        for (i in 1..seg) b.tri(c, i + 1, i)
        return fromData(b.build(), name)
    }

    /** Axis-aligned box [half] around [c] appended to [b]. */
    fun box(b: MeshBuilder, c: Vec3, half: Vec3) {
        val faces = listOf(
            Vec3(1f, 0f, 0f) to (Vec3(0f, 1f, 0f) to Vec3(0f, 0f, 1f)),
            Vec3(-1f, 0f, 0f) to (Vec3(0f, 0f, 1f) to Vec3(0f, 1f, 0f)),
            Vec3(0f, 1f, 0f) to (Vec3(0f, 0f, 1f) to Vec3(1f, 0f, 0f)),
            Vec3(0f, -1f, 0f) to (Vec3(1f, 0f, 0f) to Vec3(0f, 0f, 1f)),
            Vec3(0f, 0f, 1f) to (Vec3(1f, 0f, 0f) to Vec3(0f, 1f, 0f)),
            Vec3(0f, 0f, -1f) to (Vec3(0f, 1f, 0f) to Vec3(1f, 0f, 0f)),
        )
        for ((n, uv) in faces) {
            val (u, v) = uv
            fun corner(su: Float, sv: Float): Vec3 {
                val d = n + u * su + v * sv
                return c + Vec3(d.x * half.x, d.y * half.y, d.z * half.z)
            }
            val a = b.vertex(corner(-1f, -1f), n, 0.5f, 0.5f)
            val bb = b.vertex(corner(1f, -1f), n, 0.5f, 0.5f)
            val cc = b.vertex(corner(1f, 1f), n, 0.5f, 0.5f)
            val d = b.vertex(corner(-1f, 1f), n, 0.5f, 0.5f)
            b.tri(a, bb, cc); b.tri(a, cc, d)
        }
    }
}

internal fun model(mesh: MeshResource, material: Material, name: String, parent: Entity): ModelEntity =
    ModelEntity(mesh, material).also {
        runCatching { it.setName(name) }
        parent.addChild(it)
    }
