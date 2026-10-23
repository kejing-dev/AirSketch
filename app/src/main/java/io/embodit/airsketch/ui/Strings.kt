package io.embodit.airsketch.ui

import java.util.Calendar

enum class Lang { ZH, EN }

/** All UI copy in both languages (design i18n; wording from the sample page). */
class Strings(val lang: Lang) {
    private val zh = lang == Lang.ZH

    val appName = if (zh) "空中画笔" else "Air Sketch"
    val loading = if (zh) "正在铺纸…" else "Laying out paper…"
    val hints =
        if (zh) listOf("捏住光点，拖一下", "点亮着的点，或拖底座上的影子，换个地方下笔", "拖底座转过来，接着画", "拖窗口下沿，可以把它挪到桌边")
        else listOf("Pinch the light, drag", "Tap the dots, or drag the shadow, to move it", "Turn the base, keep going", "Drag the window edge to your desk")
    val brushes = if (zh) listOf("墨线", "霓虹", "丝带", "速写") else listOf("Ink", "Neon", "Ribbon", "Sketch")
    val sizes = if (zh) listOf("细", "中", "粗") else listOf("S", "M", "L")
    val sizeLabel = if (zh) "粗细" else "Size"
    val colorLabel = if (zh) "颜色" else "Color"
    val menuNew = if (zh) "新画一幅" else "New"
    val menuGallery = if (zh) "作品架" else "Gallery"
    val menuDisplay = if (zh) "摆着看" else "Display"
    val menuClear = if (zh) "清空" else "Clear"
    val menuSettings = if (zh) "设置" else "Settings"
    val cancel = if (zh) "取消" else "Cancel"
    val clear = if (zh) "清空" else "Clear"
    val gallery = if (zh) "作品架" else "Gallery"
    val done = if (zh) "完成" else "Done"
    val delThis = if (zh) "删除这幅" else "Delete this"
    val del = if (zh) "删除" else "Delete"
    val newWork = if (zh) "新画一幅" else "New sketch"
    val emptyGallery = if (zh) "还没有作品\n新画一幅吧" else "No sketches yet\nStart a new one"
    val sample = if (zh) "示例" else "Sample"
    val pill = if (zh) "接着画" else "Keep drawing"
    val settings = if (zh) "设置" else "Settings"
    val language = if (zh) "语言" else "Language"
    val follow = if (zh) "跟随系统" else "System"
    val spin = if (zh) "摆着看时慢慢转" else "Slowly turn on display"
    val about = if (zh) "关于" else "About"
    val full = if (zh) "这幅画满了，新开一幅接着画吧" else "This sketch is full. Start a new one?"
    val display = if (zh) "摆着看" else "Display"
    val err = if (zh) "这幅画没打开" else "Couldn't open this sketch"
    val errSub = if (zh) "文件读不出来，原文件留着没动" else "The file couldn't be read. It was left untouched."
    val errMissing = if (zh) "作品文件不见了" else "The sketch file is missing."
    val retry = if (zh) "重试" else "Retry"
    val stickTip = if (zh) "摇杆左右转动底座" else "Stick left / right turns the base"
    val mirror = if (zh) "对称" else "Mirror"
    val turn90 = if (zh) "转 90°" else "Turn 90°"
    val undo = if (zh) "撤销" else "Undo"
    val redo = if (zh) "重做" else "Redo"
    val more = if (zh) "更多" else "More"
    val back = if (zh) "返回" else "Back"
    val skip = if (zh) "跳过引导" else "Skip"
    val newSample = if (zh) "示例：画下一笔就存成你的作品" else "Sample: your first stroke makes it yours"

    fun confirmClear(n: Int) = if (zh) "清空这幅？画过的 $n 笔都会擦掉" else "Clear this sketch? All $n strokes go away"

    fun confirmDel(name: String) = if (zh) "删掉「$name」？删了找不回来" else "Delete “$name”? It can't be undone"

    fun strokes(n: Int) = if (zh) "$n 笔" else if (n == 1) "1 stroke" else "$n strokes"

    fun about(version: String) =
        if (zh) "空中画笔 v$version · 参照 Open Brush（Apache-2.0）、A-Painter（MIT）· 全离线"
        else "Air Sketch v$version · after Open Brush (Apache-2.0) and A-Painter (MIT) · fully offline"

    fun sampleName(id: String) =
        when (id) {
            "tree" -> if (zh) "小树" else "Little tree"
            "orbit" -> if (zh) "星环" else "Orbit"
            else -> if (zh) "弹簧" else "Spring"
        }

    /** "画 3 · 10月21日" / "Sketch 3 · Oct 21" (design i18n.note). */
    fun workName(n: Int, at: Long): String {
        val c = Calendar.getInstance().apply { timeInMillis = if (at > 0) at else System.currentTimeMillis() }
        val m = c.get(Calendar.MONTH)
        val d = c.get(Calendar.DAY_OF_MONTH)
        return if (zh) "画 $n · ${m + 1}月${d}日" else "Sketch $n · ${MONTHS[m]} $d"
    }

    private companion object {
        val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
    }
}
