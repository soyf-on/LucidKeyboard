package dev.lucid.keyboard.core.lm

import java.io.InputStream

/** Loads the static per-language models shipped with the app. [open] resolves an asset name. */
object ModelBundle {
    const val EMOJI = "emoji.tsv"
    val LANGUAGES = listOf("en", "nl")
    val NAMES = mapOf("en" to "English", "nl" to "Nederlands")

    fun lexiconFile(lang: String) = "${lang}_lexicon.bin"
    fun ngramFile(lang: String) = "${lang}_charlm.bin"
    fun replacementsFile(lang: String) = "${lang}_replacements.tsv"
    fun bigramsFile(lang: String) = "${lang}_bigrams.bin"

    fun loadPack(lang: String, open: (String) -> InputStream): LanguagePack {
        val lex = open(lexiconFile(lang)).use { Lexicon.read(it) }
        val ng = open(ngramFile(lang)).use { CharNgram.read(it) }
        val reps = runCatching {
            open(replacementsFile(lang)).bufferedReader().useLines { lines ->
                lines.mapNotNull { l -> l.split('\t').takeIf { it.size == 3 }?.let { it[0] to (it[1] to it[2]) } }.toMap()
            }
        }.getOrDefault(emptyMap())
        val bg = runCatching { open(bigramsFile(lang)).use { BigramModel.read(it) } }.getOrNull()
        return LanguagePack(lang, lex, ng, reps, bg)
    }
}
