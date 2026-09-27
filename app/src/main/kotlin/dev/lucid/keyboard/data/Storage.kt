package dev.lucid.keyboard.data

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.AtomicFile
import dev.lucid.keyboard.core.lm.UserVocabulary
import dev.lucid.keyboard.core.lm.UserVocabularyData
import dev.lucid.keyboard.core.touch.SpatialModel
import dev.lucid.keyboard.core.touch.SpatialModelData
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.Executors

/**
 * On-device persistence for personal data. Files live in the app's private, non-backed-up
 * storage (allowBackup=false). Writes are debounced and done off the main thread from
 * immutable snapshots taken on the main thread, via AtomicFile so a crash can't corrupt them.
 */
class Storage(context: Context) {
    private val dir = File(context.noBackupFilesDir, "personal").apply { mkdirs() }
    private val io = Executors.newSingleThreadExecutor { Thread(it, "lucid-io").apply { priority = Thread.MIN_PRIORITY } }
    private val main = Handler(Looper.getMainLooper())
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val vocabFile = AtomicFile(File(dir, "vocabulary.json"))
    private fun spatialFile(landscape: Boolean) = AtomicFile(File(dir, if (landscape) "touch_landscape.json" else "touch_portrait.json"))

    fun loadVocabulary(): UserVocabulary = runCatching {
        UserVocabulary(UserVocabulary.parse(String(vocabFile.readFully())))
    }.getOrElse { UserVocabulary() }

    fun loadSpatial(landscape: Boolean): SpatialModel = runCatching {
        SpatialModel(json.decodeFromString(SpatialModelData.serializer(), String(spatialFile(landscape).readFully())))
    }.getOrElse { SpatialModel() }

    private var pendingVocab: Runnable? = null
    private val pendingSpatial = arrayOfNulls<Runnable>(2)

    fun saveVocabularySoon(v: UserVocabulary, delayMs: Long = 1500) {
        pendingVocab?.let { main.removeCallbacks(it) }
        val r = Runnable {
            val snapshot: UserVocabularyData = v.snapshot()
            io.execute { write(vocabFile, UserVocabulary.JSON.encodeToString(UserVocabularyData.serializer(), snapshot)) }
        }
        pendingVocab = r
        main.postDelayed(r, delayMs)
    }

    fun saveSpatialSoon(m: SpatialModel, landscape: Boolean, delayMs: Long = 3000) {
        val i = if (landscape) 1 else 0
        pendingSpatial[i]?.let { main.removeCallbacks(it) }
        val r = Runnable {
            val data = m.data
            io.execute { write(spatialFile(landscape), json.encodeToString(SpatialModelData.serializer(), data)) }
        }
        pendingSpatial[i] = r
        main.postDelayed(r, delayMs)
    }

    /** Flush pending writes now (e.g. when the keyboard hides). */
    fun flush() {
        pendingVocab?.let { main.removeCallbacks(it); it.run() }; pendingVocab = null
        for (i in 0..1) { pendingSpatial[i]?.let { main.removeCallbacks(it); it.run() }; pendingSpatial[i] = null }
    }

    fun deleteSpatial() {
        for (l in listOf(false, true)) spatialFile(l).delete()
    }

    private fun write(f: AtomicFile, text: String) {
        val out = f.startWrite()
        try { out.write(text.toByteArray()); f.finishWrite(out) } catch (e: Exception) { f.failWrite(out) }
    }
}
