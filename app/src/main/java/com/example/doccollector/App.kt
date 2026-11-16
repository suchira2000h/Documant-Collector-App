package com.example.doccollector

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun formatSize(b: Long) = when {
    b >= 1_073_741_824 -> "%.1f GB".format(b / 1_073_741_824.0)
    b >= 1_048_576 -> "%.1f MB".format(b / 1_048_576.0)
    b >= 1024 -> "${b / 1024} KB"
    else -> "$b B"
}

private fun openFile(ctx: Context, f: File) {
    val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", f)
    val i = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, mimeOf(f.name))
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    runCatching { ctx.startActivity(Intent.createChooser(i, "Open with")) }
        .onFailure { Toast.makeText(ctx, "No app found to open this file", Toast.LENGTH_SHORT).show() }
}

private fun openUrl(ctx: Context, url: String) {
    runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        .onFailure { Toast.makeText(ctx, "Can't open link", Toast.LENGTH_SHORT).show() }
}

private fun loadThumb(f: File, kind: Kind): ImageBitmap? = try {
    when (kind) {
        Kind.IMAGE -> {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.absolutePath, bounds)
            var s = 1
            while (bounds.outWidth / (s * 2) >= 160 && bounds.outHeight / (s * 2) >= 160) s *= 2
            BitmapFactory.decodeFile(
                f.absolutePath, BitmapFactory.Options().apply { inSampleSize = s }
            )?.asImageBitmap()
        }
        Kind.VIDEO -> {
            val r = MediaMetadataRetriever()
            try {
                r.setDataSource(f.absolutePath)
                r.getFrameAtTime(0)?.let { b ->
                    val sc = 160f / maxOf(b.width, b.height)
                    Bitmap.createScaledBitmap(
                        b,
                        (b.width * sc).toInt().coerceAtLeast(1),
                        (b.height * sc).toInt().coerceAtLeast(1),
                        true
                    ).asImageBitmap()
                }
            } finally {
                r.release()
            }
        }
        else -> null
    }
} catch (e: Throwable) {
    null
}

@Composable
private fun Thumb(f: File, kind: Kind) {
    val bmp by produceState<ImageBitmap?>(null, f.absolutePath, f.lastModified()) {
        value = withContext(Dispatchers.IO) { loadThumb(f, kind) }
    }
    Box(
        Modifier.size(56.dp).clip(RoundedCornerShape(10.dp)).background(Color(kind.bg)),
        contentAlignment = Alignment.Center
    ) {
        val b = bmp
        if (b != null) {
            Image(b, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            Text(kind.badge, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Color(kind.fg))
        }
    }
}

@Composable
private fun HtmlViewer(f: File, onClose: () -> Unit, onOpenWith: () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column {
                Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Close") }
                    Text(
                        f.name, Modifier.weight(1f), fontWeight = FontWeight.SemiBold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                    TextButton(onClick = onOpenWith) { Text("Open with…") }
                }
                AndroidView(
                    factory = { c ->
                        WebView(c).apply {
                            settings.javaScriptEnabled = true
                            settings.allowFileAccess = true
                            settings.allowContentAccess = false
                            webViewClient = WebViewClient()
                            loadUrl(Uri.fromFile(f).toString())
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App(incoming: Incoming?, onIncomingHandled: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val snack = remember { SnackbarHostState() }
    var docs by remember { mutableStateOf(Store.list(ctx)) }
    var filter by remember { mutableStateOf<Kind?>(null) }
    var busy by remember { mutableStateOf(false) }
    var toDelete by remember { mutableStateOf<File?>(null) }
    var viewing by remember { mutableStateOf<File?>(null) }
    var pendingLink by remember { mutableStateOf<Pair<String, String?>?>(null) }
    val dateFmt = remember { SimpleDateFormat("d MMM yyyy", Locale.getDefault()) }

    fun handle(inc: Incoming) {
        scope.launch {
            val html = inc.html
            val text = inc.text
            // A lone link: ask whether to keep the link or download the page/file
            if (inc.uris.isEmpty() && html.isNullOrBlank() && text != null) {
                val url = Store.asUrl(text)
                if (url != null) {
                    pendingLink = url to inc.subject
                    return@launch
                }
            }
            busy = true
            var ok = 0
            var fail = 0
            withContext(Dispatchers.IO) {
                if (inc.uris.isNotEmpty()) {
                    for (u in inc.uris) {
                        if (runCatching { Store.addUri(ctx, u) }.isSuccess) ok++ else fail++
                    }
                } else if (!html.isNullOrBlank()) {
                    if (runCatching { Store.addHtml(ctx, html, inc.subject) }.isSuccess) ok++ else fail++
                } else if (!text.isNullOrBlank()) {
                    if (runCatching { Store.addText(ctx, text, inc.subject) }.isSuccess) ok++ else fail++
                }
            }
            docs = Store.list(ctx)
            busy = false
            snack.showSnackbar(
                when {
                    ok == 0 && fail == 0 -> "Nothing to add"
                    fail == 0 && ok == 1 -> "Added to collection"
                    fail == 0 -> "Added $ok items"
                    ok == 0 -> "Could not add"
                    else -> "Added $ok, $fail failed"
                }
            )
        }
    }

    // Auto-save whatever was shared, no extra taps
    LaunchedEffect(incoming) {
        val inc = incoming ?: return@LaunchedEffect
        handle(inc)
        onIncomingHandled()
    }

    val activeFilter = filter?.takeIf { k -> docs.any { kindOf(it.name) == k } }
    val shown = if (activeFilter == null) docs else docs.filter { kindOf(it.name) == activeFilter }

    Scaffold(
        topBar = {
            TopAppBar(title = {
                Column {
                    Text("My Collection", fontWeight = FontWeight.Bold)
                    Text("${docs.size} items • ${formatSize(docs.sumOf { it.length() })}", fontSize = 12.sp)
                }
            })
        },
        snackbarHost = { SnackbarHost(snack) }
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())

            if (docs.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(32.dp), Alignment.Center) {
                    Text(
                        "Nothing collected yet.\nOpen any photo, video, web page, PDF, file or link, tap Share and choose Doc Collector.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = activeFilter == null,
                        onClick = { filter = null },
                        label = { Text("All (${docs.size})") }
                    )
                    Kind.values().filter { k -> docs.any { kindOf(it.name) == k } }.forEach { k ->
                        FilterChip(
                            selected = activeFilter == k,
                            onClick = { filter = k },
                            label = { Text("${k.label} (${docs.count { kindOf(it.name) == k }})") }
                        )
                    }
                }
                LazyColumn(
                    Modifier.fillMaxWidth().weight(1f),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(shown, key = { it.absolutePath }) { f ->
                        val kind = kindOf(f.name)
                        Card(
                            Modifier.fillMaxWidth().clickable {
                                when (kind) {
                                    Kind.HTML -> viewing = f
                                    Kind.LINK -> Store.linkOf(f)?.let { openUrl(ctx, it) }
                                    else -> openFile(ctx, f)
                                }
                            },
                            shape = RoundedCornerShape(16.dp),
                            elevation = CardDefaults.cardElevation(2.dp)
                        ) {
                            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Thumb(f, kind)
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        f.name, fontWeight = FontWeight.SemiBold,
                                        maxLines = 2, overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        "${kind.badge} • ${formatSize(f.length())} • ${dateFmt.format(Date(f.lastModified()))}",
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                IconButton(onClick = { toDelete = f }) {
                                    Icon(Icons.Default.Delete, "Delete", tint = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Shared link: keep the link, or download the page / file behind it
    pendingLink?.let { (url, subject) ->
        AlertDialog(
            onDismissRequest = { pendingLink = null },
            title = { Text("Link shared") },
            text = { Text("$url\n\nDownload the page or file, or just keep the link?") },
            confirmButton = {
                TextButton(onClick = {
                    pendingLink = null
                    scope.launch {
                        busy = true
                        val r = withContext(Dispatchers.IO) { runCatching { Store.download(ctx, url) } }
                        val msg = if (r.isSuccess) {
                            "Downloaded ${r.getOrNull()?.name}"
                        } else {
                            withContext(Dispatchers.IO) { runCatching { Store.addLink(ctx, url, subject) } }
                            "Download failed, saved the link instead"
                        }
                        docs = Store.list(ctx)
                        busy = false
                        snack.showSnackbar(msg)
                    }
                }) { Text("Download") }
            },
            dismissButton = {
                TextButton(onClick = {
                    pendingLink = null
                    scope.launch {
                        withContext(Dispatchers.IO) { runCatching { Store.addLink(ctx, url, subject) } }
                        docs = Store.list(ctx)
                        snack.showSnackbar("Link saved")
                    }
                }) { Text("Save link") }
            }
        )
    }

    // In-app HTML viewer
    viewing?.let { f ->
        HtmlViewer(f, onClose = { viewing = null }, onOpenWith = { openFile(ctx, f) })
    }

    // Delete confirmation
    toDelete?.let { f ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("Delete item?") },
            text = { Text("Are you sure you want to delete \"${f.name}\"? This can't be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    f.delete()
                    docs = Store.list(ctx)
                    toDelete = null
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Cancel") } }
        )
    }
}
