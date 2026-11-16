package com.example.doccollector

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.IntentCompat

/** Everything that arrived through the Share sheet. */
data class Incoming(
    val uris: List<Uri>,
    val text: String?,
    val html: String?,
    val subject: String?
)

class MainActivity : ComponentActivity() {
    private var incoming by mutableStateOf<Incoming?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Only read the intent on a fresh start so a restart never re-imports the same share
        if (savedInstanceState == null) incoming = extract(intent)
        setContent {
            MaterialTheme {
                App(incoming = incoming, onIncomingHandled = { incoming = null })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        incoming = extract(intent)
    }

    private fun extract(i: Intent?): Incoming? {
        if (i == null) return null
        // Re-opening the app from the recents list re-delivers the old share intent; ignore it
        if (i.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return null

        val uris = mutableListOf<Uri>()
        when (i.action) {
            Intent.ACTION_SEND ->
                IntentCompat.getParcelableExtra(i, Intent.EXTRA_STREAM, Uri::class.java)
                    ?.let { uris.add(it) }
            Intent.ACTION_SEND_MULTIPLE ->
                IntentCompat.getParcelableArrayListExtra(i, Intent.EXTRA_STREAM, Uri::class.java)
                    ?.let { uris.addAll(it) }
            else -> return null
        }
        // Some apps only put the files in ClipData
        if (uris.isEmpty()) {
            val cd = i.clipData
            if (cd != null) {
                for (n in 0 until cd.itemCount) cd.getItemAt(n).uri?.let { uris.add(it) }
            }
        }

        val text = i.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
        val html = i.getStringExtra(Intent.EXTRA_HTML_TEXT)
        val subject = i.getStringExtra(Intent.EXTRA_SUBJECT)

        if (uris.isEmpty() && text.isNullOrBlank() && html.isNullOrBlank()) return null
        return Incoming(uris, text, html, subject)
    }
}
