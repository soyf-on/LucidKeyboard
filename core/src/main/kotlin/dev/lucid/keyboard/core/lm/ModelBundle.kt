package dev.lucid.keyboard.core.lm

import java.io.InputStream

/** The static models shipped with the app. [open] resolves an asset name to a stream. */
class ModelBundle(
    val lexicon: Lexicon,
    val ngram: CharNgram,
    /** from -> (to, weakest mode name) */
    val replacements: Map<String, Pair<String, String>>,
) {
    companion object {
        const val LEXICON = "en_lexicon.bin"
        const val NGRAM = "en_charlm.bin"
        const val REPLACEMENTS = "en_replacements.tsv"
        const val EMOJI = "emoji.tsv"

        fun load(open: (String) -> InputStream): ModelBundle {
            val lex = open(LEXICON).use { Lexicon.read(it) }
            val ng = open(NGRAM).use { CharNgram.read(it) }
            val reps = open(REPLACEMENTS).bufferedReader().useLines { lines ->
                lines.mapNotNull { l -> l.split('\t').takeIf { it.size == 3 }?.let { it[0] to (it[1] to it[2]) } }.toMap()
            }
            return ModelBundle(lex, ng, reps)
        }
    }
}
