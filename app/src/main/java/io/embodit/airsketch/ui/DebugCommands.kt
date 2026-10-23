package io.embodit.airsketch.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import io.embodit.airsketch.domain.Vec3
import io.embodit.airsketch.domain.Zone
import io.embodit.airsketch.platform.SketchLog
import java.util.Locale

/**
 * QA hooks (design verification.debugHooks), registered in debug builds only:
 *
 *   adb shell am broadcast -a io.embodit.airsketch.DEBUG -p io.embodit.airsketch --es cmd <name> [--es s '<args>']
 *
 * seed <spring|tree|orbit> · stroke <helix|zigzag|circle|line> [brush] [s|m|l] [color] [half] · release ·
 * tapPlane <x> <y> · dragShadow <dx> <dz> · dragBase <dx> · holdBase <dx> · yaw <deg> · turn90 · mirror <on|off> · undo · redo ·
 * fill · state <S01..S17> · locale <zh|en|follow> · corruptSave · boundsCheck · nibInfo · resetOnboarding ·
 * tools <brush> <size> <color> · palette · menu · gallery · card <index> · settings · display · keep · new ·
 * clear · stick <x>,<ms> · dump · resetAll. Every command logs `CMD <name> ...` and its result.
 */
class DebugCommands(private val g: SketchController) : BroadcastReceiver() {
    private val main = Handler(Looper.getMainLooper())

    override fun onReceive(context: Context, intent: Intent) {
        val cmd = intent.getStringExtra("cmd") ?: return
        val arg = intent.getStringExtra("s") ?: ""
        main.post { runCatching { run(cmd, arg.trim()) }.onFailure { SketchLog.e("CMD $cmd failed: $it", it) } }
    }

    private val scene get() = g.scene

    private fun later(ms: Long, block: () -> Unit) = main.postDelayed({ runCatching(block).onFailure { SketchLog.e("CMD later failed: $it", it) } }, ms)

    fun run(cmd: String, a: String) {
        SketchLog.i("CMD $cmd $a")
        val p = a.split(Regex("[ ,]+")).filter { it.isNotEmpty() }
        when (cmd) {
            "seed" -> g.debugSeed(p.getOrElse(0) { "spring" })
            "stroke" -> {
                val path = p.getOrElse(0) { "helix" }
                val rest = p.drop(1).filter { it != "half" }
                g.debugTools(rest.getOrNull(0), rest.getOrNull(1), rest.getOrNull(2))
                scene.debugStroke(path, half = "half" in p)
            }
            "release" -> scene.debugRelease()
            "tapPlane" -> tapPlane(p.getOrElse(0) { "0" }.toFloat(), p.getOrElse(1) { "0.34" }.toFloat())
            "dragShadow" -> scene.debugDragShadow(p.getOrElse(0) { "0" }.toFloat(), p.getOrElse(1) { "0" }.toFloat())
            "dragBase" -> scene.debugDragBase(p.getOrElse(0) { "0.15" }.toFloat())
            "holdBase" -> scene.debugHoldBase(p.getOrElse(0) { "0.075" }.toFloat())
            "yaw" -> {
                scene.setYawDeg(p.getOrElse(0) { "0" }.toFloat())
                g.onTurned(scene.yawDeg())
            }
            "turn90" -> g.turn90()
            "mirror" -> g.setMirror(p.getOrElse(0) { "on" } == "on")
            "undo" -> g.undo()
            "redo" -> g.redo()
            "fill" -> g.debugFill()
            "locale" -> g.debugLocale(p.getOrElse(0) { "follow" })
            "corruptSave" -> g.debugCorruptSave()
            "boundsCheck" -> scene.boundsCheck(g.cur.strokes).let { SketchLog.i("BOUNDS outside=${it.size} ${it.take(8).joinToString(" ")}") }
            "nibInfo" -> SketchLog.i("NIBINFO nib=${SketchLog.pos(scene.nib)} yaw=${scene.yawDeg().toInt()} lastTap=${scene.lastTap}")
            "resetOnboarding" -> g.debugResetOnboarding()
            "tools" -> g.debugTools(p.getOrNull(0), p.getOrNull(1), p.getOrNull(2))
            "palette" -> g.togglePalette()
            "menu" -> g.openMenu()
            "gallery" -> g.openGallery()
            "card" -> g.s.cards.getOrNull(p.getOrElse(0) { "0" }.toInt())?.let { g.pickCard(it) }
            "settings" -> g.openSettings()
            "display" -> g.openDisplay()
            "keep" -> g.keepDrawing()
            "new" -> g.newWork()
            "clear" -> g.askClear()
            "confirm" -> if (g.s.bar == Bar.CONFIRM_DELETE) g.confirmDelete() else g.confirmClear()
            "delete" -> g.askDelete()
            "stick" -> g.stick.debugHold(p.getOrElse(0) { "0.8" }.toFloat(), p.getOrElse(1) { "1500" }.toLong())
            "dump" -> SketchLog.i("DUMP ${g.dump()}")
            "resetAll" -> {
                g.store.clearAll()
                g.boot()
            }
            "state" -> state(p.getOrElse(0) { "S05" }.uppercase(Locale.US))
            else -> SketchLog.w("CMD unknown $cmd")
        }
    }

    /** The tap pipeline from a known point (design acceptance: tap error ≤ 1 cm; logs the target and the result). */
    private fun tapPlane(x: Float, y: Float) {
        if (!scene.mode.planeTappable) {
            SketchLog.i("TAP refused: dots not tappable in ${g.s.designState}")
            return
        }
        val want = Zone.clamp(Vec3(x, y, scene.nib.z)).first
        scene.moveNibTo(want)
        g.onNibMoved()
        later(400) {
            val err = scene.nib.distanceTo(want)
            SketchLog.i("NIB tapPlane want=${SketchLog.pos(want)} got=${SketchLog.pos(scene.nib)} err=${"%.4f".format(Locale.US, err)}m")
        }
    }

    /** Puts the app into a named design state for a screenshot (design states[].qaCapture). */
    private fun state(id: String) {
        // leave whatever the previous capture left open
        scene.debugRelease()
        if (id != "S02" && g.s.onboarding != null) g.skipOnboarding()
        if (g.s.screen == Screen.DISPLAY && id != "S14") g.keepDrawing()
        if (g.s.bar != Bar.TOOLS && id !in setOf("S12", "S13", "S15")) g.closeBar()
        when (id) {
            "S01" -> g.boot(holdMs = 6000)
            "S02" -> {
                g.newWork()
                g.setMirror(false)
                g.debugResetOnboarding()
            }
            "S03" -> {
                g.newWork()
                g.setMirror(false)
            }
            "S04" -> {
                g.newWork()
                g.setMirror(false)
                later(300) { scene.debugStroke("helix", half = true) }
            }
            "S05" -> g.debugSeed("spring")
            "S06" -> {
                g.debugSeed("spring")
                later(200) { g.togglePalette() }
            }
            "S07" -> {
                // base turned 30° so the mirror plane and the mirrored copy read as two lines from the front
                g.newWork()
                g.setMirror(true)
                scene.setYawDeg(30f)
                later(300) { scene.debugStroke("zigzag", half = false) }
            }
            "S08" -> {
                // a drag on the base held open: the work turns 45° and the tick ring stays lit until `release`
                g.debugSeed("orbit")
                later(200) { scene.debugHoldBase(0.075f) }
            }
            "S09" -> {
                g.debugSeed("spring")
                later(300) { scene.debugStroke("line", half = false) }
                later(3500) { g.undo() }
            }
            "S10" -> {
                g.debugSeed("spring")
                later(200) { g.openMenu() }
            }
            "S11" -> {
                g.debugSeed("tree")
                later(200) {
                    g.debugFillOne()
                    g.openMenu()
                    g.askClear()
                }
            }
            "S12" -> later(10) { g.openGallery() }
            "S13" -> {
                g.debugSeed("orbit")
                later(200) {
                    g.debugFillOne()
                    g.openGallery()
                    g.askDelete()
                }
            }
            "S14" -> {
                g.debugSeed("tree")
                later(200) {
                    g.debugFillOne()
                    g.openDisplay()
                }
            }
            "S15" -> {
                g.openMenu()
                g.openSettings()
            }
            "S16" -> g.debugFill()
            "S17" -> {
                g.debugCorruptSave()
                later(300) { g.boot() }
            }
            else -> SketchLog.w("CMD state unknown $id")
        }
    }
}
