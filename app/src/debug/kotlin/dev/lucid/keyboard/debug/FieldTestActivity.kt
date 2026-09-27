package dev.lucid.keyboard.debug

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Debug-only screen with one field per input type, used for manual and adb-driven tests. */
class FieldTestActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 140, 40, 40); setBackgroundColor(Color.rgb(236, 240, 247)) }
        fun field(tag: String, type: Int, options: Int = EditorInfo.IME_ACTION_DONE) {
            col.addView(TextView(this).apply { text = tag; setTextColor(Color.DKGRAY) })
            col.addView(EditText(this).apply { this.tag = tag; inputType = type; imeOptions = options; hint = tag; setTextColor(Color.BLACK); setSingleLine((type and InputType.TYPE_TEXT_FLAG_MULTI_LINE) == 0) })
        }
        val text = InputType.TYPE_CLASS_TEXT
        field("message", text or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_AUTO_CORRECT, EditorInfo.IME_ACTION_SEND)
        field("password", text or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        field("url", text or InputType.TYPE_TEXT_VARIATION_URI, EditorInfo.IME_ACTION_GO)
        field("email", text or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)
        field("number", InputType.TYPE_CLASS_NUMBER)
        field("phone", InputType.TYPE_CLASS_PHONE)
        field("multiline", text or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES)
        field("nosuggest", text or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS)
        field("incognito", text or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES, EditorInfo.IME_ACTION_SEARCH or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING)
        field("search", text, EditorInfo.IME_ACTION_SEARCH)
        col.addView(android.widget.Button(this).apply {
            setText("Copy sample text"); tag = "copy"
            setOnClickListener {
                getSystemService(android.content.ClipboardManager::class.java)
                    .setPrimaryClip(android.content.ClipData.newPlainText("sample", "See you at the station at 7"))
            }
        })
        setContentView(ScrollView(this).apply { addView(col) })
    }
}
