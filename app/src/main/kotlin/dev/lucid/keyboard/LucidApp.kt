package dev.lucid.keyboard

import android.app.Application
import android.os.SystemClock
import android.util.Log
import dev.lucid.keyboard.core.lm.LanguageModel
import dev.lucid.keyboard.core.lm.ModelBundle
import dev.lucid.keyboard.core.lm.UserVocabulary
import dev.lucid.keyboard.core.touch.SpatialModel
import dev.lucid.keyboard.data.Prefs
import dev.lucid.keyboard.data.Storage
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Process-wide state shared by the keyboard service and the settings screens. Both run
 * in this one process on the main thread, so personal data has a single owner.
 */
class LucidApp : Application() {
    lateinit var prefs: Prefs; private set
    lateinit var storage: Storage; private set

    @Volatile private var loaded: Loaded? = null
    private val ready = CountDownLatch(1)
    /** Milliseconds the model load took (measured, shown in Settings > About). */
    @Volatile var loadMillis = 0L; private set

    class Loaded(val bundle: ModelBundle, val vocabulary: UserVocabulary, val portrait: SpatialModel, val landscape: SpatialModel) {
        val lm = LanguageModel(bundle.lexicon, bundle.ngram, vocabulary, bundle.replacements)
        fun spatial(landscape: Boolean) = if (landscape) this.landscape else portrait
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        prefs = Prefs(this)
        storage = Storage(this)
        Thread({
            val t0 = SystemClock.elapsedRealtime()
            try {
                val bundle = ModelBundle.load { assets.open(it) }
                loaded = Loaded(bundle, storage.loadVocabulary(), storage.loadSpatial(false), storage.loadSpatial(true))
            } catch (e: Exception) {
                Log.e(TAG, "model load failed", e)
            } finally {
                loadMillis = SystemClock.elapsedRealtime() - t0
                ready.countDown()
            }
        }, "lucid-load").start()
    }

    /** Models, waiting (bounded) for the background load on a cold start. */
    fun engine(timeoutMs: Long = 3000): Loaded? {
        loaded?.let { return it }
        ready.await(timeoutMs, TimeUnit.MILLISECONDS)
        return loaded
    }

    fun persistVocabulary() { loaded?.let { storage.saveVocabularySoon(it.vocabulary) } }

    fun resetTouchModels() {
        loaded?.let { it.portrait.reset(); it.landscape.reset(); storage.saveSpatialSoon(it.portrait, false, 0); storage.saveSpatialSoon(it.landscape, true, 0) }
    }

    companion object {
        const val TAG = "Lucid"
        lateinit var instance: LucidApp; private set
    }
}
