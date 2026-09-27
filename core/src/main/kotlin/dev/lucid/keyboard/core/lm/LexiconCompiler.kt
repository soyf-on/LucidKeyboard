package dev.lucid.keyboard.core.lm

import java.io.File

/** Compiles the lexicon TSV files into the binary assets loaded by the keyboard. Run: ./gradlew :core:generateLexicon */
fun main(args: Array<String>) {
    val src = File(args[0]); val out = File(args[1])
    out.mkdirs()
    val t0 = System.nanoTime()
    val lex = Lexicon.readTsv(File(src, "en_words.tsv"))
    File(out, ModelBundle.LEXICON).outputStream().use { lex.write(it) }
    val words = File(src, "en_words.tsv").readLines().map { it.substringBefore('\t') }
    val ngram = CharNgram.train(words.asSequence())
    File(out, ModelBundle.NGRAM).outputStream().use { ngram.write(it) }
    File(src, "en_replacements.tsv").copyTo(File(out, ModelBundle.REPLACEMENTS), overwrite = true)
    File(src, "emoji.tsv").copyTo(File(out, ModelBundle.EMOJI), overwrite = true)
    println("lexicon: ${lex.wordCount} words, ${lex.nodeCount} nodes in ${(System.nanoTime() - t0) / 1_000_000} ms")
}
