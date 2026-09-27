package dev.lucid.keyboard.core.lm

import java.io.File

/** Compiles the lexicon TSV files into the binary assets loaded by the keyboard. Run: ./gradlew :core:generateLexicon */
fun main(args: Array<String>) {
    val src = File(args[0]); val out = File(args[1])
    out.mkdirs()
    for (lang in ModelBundle.LANGUAGES) {
        val t0 = System.nanoTime()
        val tsv = File(src, "${lang}_words.tsv")
        val lex = Lexicon.readTsv(tsv)
        File(out, ModelBundle.lexiconFile(lang)).outputStream().use { lex.write(it) }
        val ngram = CharNgram.train(tsv.readLines().asSequence().map { it.substringBefore('\t') })
        File(out, ModelBundle.ngramFile(lang)).outputStream().use { ngram.write(it) }
        File(src, "${lang}_replacements.tsv").copyTo(File(out, ModelBundle.replacementsFile(lang)), overwrite = true)
        val bgTsv = File(src, "${lang}_bigrams.tsv")
        if (bgTsv.exists()) {
            val bg = BigramModel.fromTsv(bgTsv, lex)
            File(out, ModelBundle.bigramsFile(lang)).outputStream().use { bg.write(it) }
            println("$lang: ${bg.size} word pairs")
        }
        println("$lang: ${lex.wordCount} words, ${lex.nodeCount} nodes in ${(System.nanoTime() - t0) / 1_000_000} ms")
    }
    File(src, "emoji.tsv").copyTo(File(out, ModelBundle.EMOJI), overwrite = true)
}
