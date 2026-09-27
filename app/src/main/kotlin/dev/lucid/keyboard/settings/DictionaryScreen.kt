package dev.lucid.keyboard.settings

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.lucid.keyboard.LucidApp
import dev.lucid.keyboard.core.lm.UserVocabulary
import dev.lucid.keyboard.core.lm.UserWord
import dev.lucid.keyboard.core.lm.WordSource

@Composable
fun DictionaryScreen() {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as LucidApp
    val vocab = app.engine()?.vocabulary
    if (vocab == null) { Body("The language model could not be loaded."); return }
    // The vocabulary is a plain object shared with the keyboard; bump this to recompose after edits.
    var rev by remember { mutableIntStateOf(0) }
    fun changed() { rev++; app.persistVocabulary() }
    var tab by remember { mutableIntStateOf(0) }
    var confirm by remember { mutableStateOf<String?>(null) }

    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) runCatching { ctx.contentResolver.openOutputStream(uri)!!.use { it.write(vocab.toJson().toByteArray()) } }
            .onSuccess { Toast.makeText(ctx, "Exported", Toast.LENGTH_SHORT).show() }
            .onFailure { Toast.makeText(ctx, "Export failed: ${it.message}", Toast.LENGTH_LONG).show() }
    }
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) runCatching {
            val text = ctx.contentResolver.openInputStream(uri)!!.use { String(it.readBytes()) }
            val incoming = UserVocabulary.parse(text)
            // Merge: incoming entries win for the same word; nothing existing is dropped.
            val cur = vocab.snapshot()
            val words = (cur.words.associateBy { it.word.lowercase() } + incoming.words.associateBy { it.word.lowercase() }).values.toList()
            vocab.load(cur.copy(
                words = words, blocked = (cur.blocked + incoming.blocked).distinct(),
                replacements = cur.replacements + incoming.replacements,
                rejected = (cur.rejected.keys + incoming.rejected.keys).associateWith { (cur.rejected[it].orEmpty() + incoming.rejected[it].orEmpty()).distinct() },
                bigrams = cur.bigrams + incoming.bigrams,
            ))
            changed()
        }.onSuccess { Toast.makeText(ctx, "Imported", Toast.LENGTH_SHORT).show() }
            .onFailure { Toast.makeText(ctx, "Not a Lucid dictionary file", Toast.LENGTH_LONG).show() }
    }

    Choice(listOf("Words", "Replacements", "Blocked"), tab) { tab = it }
    Spacer(Modifier.height(10.dp))
    rev.let { } // read to subscribe
    when (tab) {
        0 -> WordsTab(vocab, rev) { changed() }
        1 -> ReplacementsTab(vocab, rev) { changed() }
        else -> BlockedTab(vocab, rev) { changed() }
    }
    SectionTitle("BACKUP")
    GlassCard {
        Body("Export or import your personal dictionary as a JSON file you control.")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { export.launch("lucid-dictionary.json") }) { Text("Export") }
            OutlinedButton(onClick = { import.launch(arrayOf("application/json", "text/plain", "*/*")) }) { Text("Import") }
        }
    }
    SectionTitle("RESET")
    GlassCard {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { confirm = "inferred" }) { Text("Forget learned words") }
            OutlinedButton(onClick = { confirm = "all" }) { Text("Erase everything") }
        }
        Body("“Forget learned words” keeps words you added yourself, never-correct words, replacements and blocks.")
    }
    confirm?.let { what ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            confirmButton = { TextButton(onClick = { if (what == "all") vocab.resetAll() else vocab.resetInferred(); changed(); confirm = null }) { Text("Erase") } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } },
            title = { Text(if (what == "all") "Erase entire dictionary?" else "Forget learned words?") },
            text = { Text("This cannot be undone unless you exported a backup.") },
        )
    }
}

@Composable
private fun WordsTab(vocab: UserVocabulary, rev: Int, changed: () -> Unit) {
    var newWord by remember { mutableStateOf("") }
    GlassCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(newWord, { newWord = it.trim() }, Modifier.weight(1f), singleLine = true, placeholder = { Text("Add a word") })
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = { if (newWord.isNotEmpty()) { vocab.learnExplicit(newWord); newWord = ""; changed() } }) { Text("Add") }
        }
        Body("Added words work immediately and are never auto-corrected.")
    }
    Spacer(Modifier.height(10.dp))
    val words = remember(rev) { vocab.allWords().sortedWith(compareBy<UserWord> { it.source }.thenBy { it.word.lowercase() }) }
    GlassCard {
        if (words.isEmpty()) Body("No personal words yet.")
        for (w in words) WordRow(vocab, w, changed)
    }
}

@Composable
private fun WordRow(vocab: UserVocabulary, w: UserWord, changed: () -> Unit) {
    val active = vocab.isActive(w.word)
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(w.word, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            val kind = when {
                w.source == WordSource.EXPLICIT -> "added by you"
                active -> "learned from use (${w.count}×)"
                else -> "seen ${w.count}× — not active yet"
            }
            Text(kind + if (w.neverCorrect) " · never corrected" else "", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
        }
        TextButton(onClick = { vocab.setNeverCorrect(w.word, !w.neverCorrect); changed() }) { Text(if (w.neverCorrect) "Allow fix" else "Protect") }
        TextButton(onClick = { vocab.remove(w.word); changed() }) { Text("Delete") }
    }
}

@Composable
private fun ReplacementsTab(vocab: UserVocabulary, rev: Int, changed: () -> Unit) {
    var from by remember { mutableStateOf("") }
    var to by remember { mutableStateOf("") }
    GlassCard {
        OutlinedTextField(from, { from = it.trim() }, Modifier.fillMaxWidth(), singleLine = true, placeholder = { Text("When I type… (e.g. omw)") })
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(to, { to = it }, Modifier.fillMaxWidth(), singleLine = true, placeholder = { Text("Replace with… (e.g. On my way!)") })
        OutlinedButton(onClick = { if (from.isNotEmpty() && to.isNotEmpty()) { vocab.setReplacement(from, to); from = ""; to = ""; changed() } }, Modifier.padding(top = 6.dp)) { Text("Add replacement") }
        Body("Replacements apply when autocorrect is on (any mode) and show as a suggestion when it is off.")
    }
    Spacer(Modifier.height(10.dp))
    val reps = remember(rev) { vocab.replacements().toList() }
    GlassCard {
        if (reps.isEmpty()) Body("No replacements.")
        for ((f, t) in reps) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("$f  →  $t", Modifier.weight(1f), fontSize = 15.sp)
            TextButton(onClick = { vocab.removeReplacement(f); changed() }) { Text("Delete") }
        }
    }
}

@Composable
private fun BlockedTab(vocab: UserVocabulary, rev: Int, changed: () -> Unit) {
    val blocked = remember(rev) { vocab.blockedWords().sorted() }
    GlassCard {
        Body("Words you asked never to be suggested or auto-corrected to. Add one by long-pressing a suggestion on the keyboard.")
        if (blocked.isEmpty()) Body("Nothing blocked.")
        for (w in blocked) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(w, Modifier.weight(1f), fontSize = 15.sp)
            TextButton(onClick = { vocab.unblock(w); changed() }) { Text("Unblock") }
        }
    }
}
