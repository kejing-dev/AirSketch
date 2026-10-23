package io.embodit.airsketch.data

import android.content.Context
import io.embodit.airsketch.domain.IndexEntry
import io.embodit.airsketch.domain.Work
import io.embodit.airsketch.platform.SketchLog
import java.io.File
import org.json.JSONArray

/** Result of opening one work: missing, good, or unreadable (design S17). */
sealed interface WorkRead {
    data object Missing : WorkRead

    data class Ok(val work: Work) : WorkRead

    data class Broken(val reason: String) : WorkRead
}

/**
 * App-private files (design dataModel.storage, fully offline): works/index.json + works/<id>.json, and the
 * prefs (language, slowSpin, onboardingDone, lastWorkId) in SharedPreferences. Writes go through a temp file
 * and a rename. A work with no strokes is never written to the index.
 */
class WorkStore(context: Context) {
    private val dir = File(context.filesDir, "works").apply { mkdirs() }
    private val indexFile = File(dir, "index.json")
    private val prefs = context.getSharedPreferences("prefs", Context.MODE_PRIVATE)

    private fun file(id: String) = File(dir, "$id.json")

    private fun writeAtomic(f: File, text: String) {
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(f)) {
            f.delete()
            tmp.renameTo(f)
        }
    }

    fun index(): List<IndexEntry> =
        runCatching {
            if (!indexFile.exists()) return emptyList()
            val a = JSONArray(indexFile.readText())
            List(a.length()) { IndexEntry.fromJson(a.getJSONObject(it)) }.sortedByDescending { it.updatedAt }
        }.getOrElse {
            SketchLog.w("INDEX unreadable: $it")
            emptyList()
        }

    private fun writeIndex(list: List<IndexEntry>) {
        val a = JSONArray()
        list.sortedByDescending { it.updatedAt }.forEach { a.put(it.toJson()) }
        writeAtomic(indexFile, a.toString())
    }

    fun read(id: String): WorkRead {
        val f = file(id)
        if (!f.exists()) return WorkRead.Missing
        return try {
            WorkRead.Ok(Work.parse(f.readText()))
        } catch (t: Throwable) {
            SketchLog.w("SAVE broken id=$id: $t")
            WorkRead.Broken(t.message ?: t.javaClass.simpleName)
        }
    }

    /** Writes [w] and its index row; an empty work is dropped from the index (and its file removed). */
    fun write(w: Work) {
        if (w.sample != null) return
        val rest = index().filter { it.id != w.id }
        if (w.strokes.isEmpty()) {
            file(w.id).delete()
            writeIndex(rest)
            return
        }
        writeAtomic(file(w.id), w.toJson())
        writeIndex(rest + IndexEntry.of(w))
    }

    fun delete(id: String) {
        file(id).delete()
        writeIndex(index().filter { it.id != id })
    }

    /** Debug `corruptSave`: overwrite the work file with garbage (QA S17). */
    fun corrupt(id: String) {
        if (file(id).exists() || index().any { it.id == id }) file(id).writeText("{\"id\": broken")
    }

    fun clearAll() {
        dir.listFiles()?.forEach { it.delete() }
        prefs.edit().clear().apply()
    }

    var language: String
        get() = prefs.getString("language", "follow") ?: "follow"
        set(v) = prefs.edit().putString("language", v).apply()

    var slowSpin: Boolean
        get() = prefs.getBoolean("slowSpin", true)
        set(v) = prefs.edit().putBoolean("slowSpin", v).apply()

    var onboardingDone: Boolean
        get() = prefs.getBoolean("onboardingDone", false)
        set(v) = prefs.edit().putBoolean("onboardingDone", v).apply()

    var lastWorkId: String?
        get() = prefs.getString("lastWorkId", null)
        set(v) = prefs.edit().putString("lastWorkId", v).apply()

    /** Next "Sketch n" number (never reused). */
    fun nextNameIndex(): Int {
        val n = prefs.getInt("nameSeq", 0) + 1
        prefs.edit().putInt("nameSeq", n).apply()
        return n
    }

    /** Tool settings carried from one sketch to the next (design defaults.rule). */
    var lastTools: String
        get() = prefs.getString("lastTools", "0,1,7,0") ?: "0,1,7,0"
        set(v) = prefs.edit().putString("lastTools", v).apply()
}
