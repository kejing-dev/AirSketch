package io.embodit.airsketch.platform

import android.util.Log
import io.embodit.airsketch.domain.Vec3
import java.util.Locale

/**
 * One line per event under the tag `AirSketch`, with stable prefixes the QA scripts grep for:
 * `STATE`, `STROKE`, `NIB`, `TAP`, `DRAG`, `TURN`, `STICK`, `SAVE`, `LOAD`, `CMD`, `DUMP`, `BOUNDS`, `MESH`.
 */
object SketchLog {
    const val TAG = "AirSketch"

    fun i(line: String) {
        Log.i(TAG, line)
    }

    fun w(line: String, error: Throwable? = null) {
        Log.w(TAG, line, error)
    }

    fun e(line: String, error: Throwable? = null) {
        Log.e(TAG, line, error)
    }

    fun pos(v: Vec3?): String = if (v == null) "null" else String.format(Locale.US, "(%.3f, %.3f, %.3f)", v.x, v.y, v.z)
}
