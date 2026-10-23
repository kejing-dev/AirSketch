package io.embodit.airsketch.domain

import kotlin.math.roundToInt
import org.json.JSONArray
import org.json.JSONObject

/** The four brushes of the design (brushes[]); [id] is the saved name. */
enum class Brush(val id: String) {
    INK("ink"),
    NEON("neon"),
    RIBBON("ribbon"),
    TAPER("taper");

    companion object {
        fun of(i: Int): Brush = entries.getOrElse(i) { INK }

        fun byId(id: String): Brush? = entries.firstOrNull { it.id == id }
    }
}

/** Twelve fixed stroke colours (design colors[]), index = the palette texture column. */
object Palette {
    val IDS = listOf("ink", "snow", "vermilion", "orange", "lemon", "leaf", "teal", "sky", "ultramarine", "violet", "pink", "walnut")
    val ARGB =
        intArrayOf(
            0xFF1D1D24.toInt(), 0xFFF4F1EA.toInt(), 0xFFE5483B.toInt(), 0xFFF28C28.toInt(),
            0xFFF5D33F.toInt(), 0xFF8BD346.toInt(), 0xFF2EC4A6.toInt(), 0xFF3FA7F5.toInt(),
            0xFF3E5BDB.toInt(), 0xFF8E5BE8.toInt(), 0xFFF06BAA.toInt(), 0xFF9A6B4A.toInt(),
        )
    const val SIZE = 12
    const val SKY = 7

    /** Column centre of colour [i] in the 12 × 1 palette texture. */
    fun u(i: Int): Float = (i.coerceIn(0, SIZE - 1) + 0.5f) / SIZE

    /** Neon reads 15 % lighter (design brushes[neon].material). */
    fun brighten(argb: Int, k: Float = 0.15f): Int {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        fun up(c: Int) = (c + (255 - c) * k).roundToInt().coerceIn(0, 255)
        return (0xFF shl 24) or (up(r) shl 16) or (up(g) shl 8) or up(b)
    }
}

/** Stroke diameters 3 / 6 / 12 mm (design sizes.rule). */
object Sizes {
    val DIAMETER_M = floatArrayOf(0.003f, 0.006f, 0.012f)
    val NIB_M = floatArrayOf(0.018f, 0.024f, 0.030f)

    fun radius(i: Int): Float = DIAMETER_M[i.coerceIn(0, 2)] / 2f
}

/**
 * One stroke in base-local metres (the "art" frame: the volume frame turned with the base about its vertical
 * axis). [pts] holds x, y, z, radius per point. [mirrored] strokes also draw the copy with x negated; the pair is
 * one stroke for undo and counts twice towards the point limit (design strokeRules.mirror / limits).
 */
class StrokeData(
    val brush: Brush,
    val size: Int,
    val color: Int,
    val mirrored: Boolean,
    val faceYawDeg: Float,
    val pts: FloatArray,
) {
    val count: Int get() = pts.size / 4

    /** Points counted against the 16 000 limit. */
    val cost: Int get() = if (mirrored) count * 2 else count

    fun point(i: Int) = Vec3(pts[i * 4], pts[i * 4 + 1], pts[i * 4 + 2])

    fun radius(i: Int) = pts[i * 4 + 3]

    fun last(): Vec3 = point(count - 1)

    /** Saved as int16 millimetres + radius in 0.1 mm (design dataModel.stroke). */
    fun toJson(): JSONObject {
        val a = JSONArray()
        for (i in 0 until count) {
            a.put((pts[i * 4] * 1000f).roundToInt())
            a.put((pts[i * 4 + 1] * 1000f).roundToInt())
            a.put((pts[i * 4 + 2] * 1000f).roundToInt())
            a.put((pts[i * 4 + 3] * 10000f).roundToInt())
        }
        return JSONObject()
            .put("brush", brush.id)
            .put("size", size)
            .put("color", color)
            .put("mirrored", mirrored)
            .put("faceYawDeg", faceYawDeg.toDouble())
            .put("pts", a)
    }

    companion object {
        fun fromJson(o: JSONObject): StrokeData {
            val a = o.getJSONArray("pts")
            require(a.length() % 4 == 0 && a.length() >= 8) { "bad pts ${a.length()}" }
            val p = FloatArray(a.length())
            for (i in 0 until a.length() step 4) {
                p[i] = a.getInt(i) / 1000f
                p[i + 1] = a.getInt(i + 1) / 1000f
                p[i + 2] = a.getInt(i + 2) / 1000f
                p[i + 3] = a.getInt(i + 3) / 10000f
            }
            return StrokeData(
                Brush.byId(o.getString("brush")) ?: error("brush ${o.getString("brush")}"),
                o.optInt("size", 1),
                o.getInt("color").coerceIn(0, Palette.SIZE - 1),
                o.optBoolean("mirrored", false),
                o.optDouble("faceYawDeg", 0.0).toFloat(),
                p,
            )
        }
    }
}

/** A sketch (design dataModel.work). A [sample] work is shown read-only until its first new stroke. */
class Work(
    var id: String,
    var nameIndex: Int,
    var createdAt: Long,
    var updatedAt: Long,
    var yawDeg: Float = 0f,
    var mirror: Boolean = false,
    var brush: Int = 0,
    var size: Int = 1,
    var color: Int = Palette.SKY,
    var display: Boolean = false,
    val strokes: MutableList<StrokeData> = ArrayList(),
    var sample: String? = null,
) {
    val points: Int get() = strokes.sumOf { it.cost }

    val colorsUsed: List<Int> get() = strokes.map { it.color }.distinct()

    fun toJson(): String {
        val a = JSONArray()
        strokes.forEach { a.put(it.toJson()) }
        return JSONObject()
            .put("id", id)
            .put("nameIndex", nameIndex)
            .put("createdAt", createdAt)
            .put("updatedAt", updatedAt)
            .put("yawDeg", yawDeg.toDouble())
            .put("mirror", mirror)
            .put("brush", Brush.of(brush).id)
            .put("size", size)
            .put("color", color)
            .put("display", display)
            .put("strokes", a)
            .toString()
    }

    companion object {
        fun parse(text: String): Work {
            val o = JSONObject(text)
            val a = o.getJSONArray("strokes")
            val list = ArrayList<StrokeData>(a.length())
            for (i in 0 until a.length()) list += StrokeData.fromJson(a.getJSONObject(i))
            return Work(
                id = o.getString("id"),
                nameIndex = o.getInt("nameIndex"),
                createdAt = o.getLong("createdAt"),
                updatedAt = o.getLong("updatedAt"),
                yawDeg = o.optDouble("yawDeg", 0.0).toFloat(),
                mirror = o.optBoolean("mirror", false),
                brush = (Brush.byId(o.optString("brush", "ink")) ?: Brush.INK).ordinal,
                size = o.optInt("size", 1).coerceIn(0, 2),
                color = o.optInt("color", Palette.SKY).coerceIn(0, Palette.SIZE - 1),
                display = o.optBoolean("display", false),
                strokes = list,
            )
        }
    }
}

/** One row of works/index.json: the gallery reads only this (design dataModel.index). */
data class IndexEntry(val id: String, val nameIndex: Int, val updatedAt: Long, val strokeCount: Int, val colorsUsed: List<Int>) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("nameIndex", nameIndex).put("updatedAt", updatedAt).put("strokeCount", strokeCount).put("colorsUsed", JSONArray(colorsUsed))

    companion object {
        fun of(w: Work) = IndexEntry(w.id, w.nameIndex, w.updatedAt, w.strokes.size, w.colorsUsed)

        fun fromJson(o: JSONObject): IndexEntry {
            val c = o.optJSONArray("colorsUsed") ?: JSONArray()
            return IndexEntry(o.getString("id"), o.getInt("nameIndex"), o.getLong("updatedAt"), o.getInt("strokeCount"), List(c.length()) { c.getInt(it) })
        }
    }
}

/** Per-sketch limits (design strokeRules.limits). */
object Limits {
    const val MAX_STROKES = 300
    const val MAX_POINTS = 16_000

    fun full(w: Work): Boolean = w.strokes.size >= MAX_STROKES || w.points >= MAX_POINTS
}
