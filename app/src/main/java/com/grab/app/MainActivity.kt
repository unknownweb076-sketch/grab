package com.grab.app

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.MimeTypeMap
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class MainActivity : Activity() {

    private lateinit var web: WebView
    private val pool = Executors.newFixedThreadPool(2)
    private val nextId = AtomicInteger(0)
    private var pageLoaded = false
    private var pendingText: String? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this).apply {
            setBackgroundColor(0xFF0B0D14.toInt())
            overScrollMode = View.OVER_SCROLL_NEVER
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            addJavascriptInterface(Bridge(), "Android")
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    return request?.url?.scheme != "file"
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    pageLoaded = true
                    deliverPending()
                }
            }
            loadUrl("file:///android_asset/index.html")
        }
        setContentView(web)
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(i: Intent?) {
        if (i?.action == Intent.ACTION_SEND) {
            val t = i.getStringExtra(Intent.EXTRA_TEXT)
            if (!t.isNullOrBlank()) {
                pendingText = t
                deliverPending()
            }
        }
    }

    private fun deliverPending() {
        val t = pendingText ?: return
        if (!pageLoaded) return
        pendingText = null
        web.evaluateJavascript("incoming(" + JSONObject.quote(t) + ")", null)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        moveTaskToBack(true)
    }

    inner class Bridge {
        @JavascriptInterface
        fun download(url: String, mode: String): Int {
            val id = nextId.getAndIncrement()
            pool.execute { runJob(id, url, mode) }
            return id
        }

        @JavascriptInterface
        fun paste(): String {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = cm.primaryClip ?: return ""
            if (clip.itemCount == 0) return ""
            return clip.getItemAt(0).coerceToText(this@MainActivity)?.toString() ?: ""
        }

        @JavascriptInterface
        fun isReady(): Boolean = GrabApp.ready.count == 0L
    }

    private fun push(id: Int, state: String, pct: Float, msg: String, title: String = "") {
        val o = JSONObject()
        o.put("id", id)
        o.put("state", state)
        o.put("pct", pct.toDouble())
        o.put("msg", msg)
        if (title.isNotEmpty()) o.put("title", title)
        runOnUiThread { web.evaluateJavascript("onJob($o)", null) }
    }

    private fun runJob(id: Int, url: String, mode: String) {
        var title = ""
        val dir = File(getExternalFilesDir(null), "jobs/$id")
        try {
            push(id, "running", 0f, "Getting ready...")
            GrabApp.ready.await()
            GrabApp.initError?.let { throw IllegalStateException(it) }

            dir.deleteRecursively()
            dir.mkdirs()

            val req = YoutubeDLRequest(url)
            req.addOption("--no-playlist")
            req.addOption("--no-mtime")
            req.addOption("-o", dir.absolutePath + "/%(title).150B.%(ext)s")
            if (mode == "audio") {
                req.addOption("-x")
                req.addOption("--audio-format", "mp3")
                req.addOption("--audio-quality", "0")
            } else {
                req.addOption("-f", "bv*[ext=mp4]+ba[ext=m4a]/b[ext=mp4]/bv*+ba/b")
                req.addOption("--merge-output-format", "mp4")
            }

            var lastPct = -10f
            var lastMsg = ""
            val strip = Regex("""\.f\d+(?=\.\w+$)""")

            YoutubeDL.getInstance().execute(req, "grab-$id") { progress, eta, line ->
                if (line.contains("Destination:")) {
                    title = File(line.substringAfter("Destination:").trim()).name.replace(strip, "")
                }
                val pct = if (progress >= 0f) progress else 0f
                val msg = when {
                    line.contains("[Merger]") -> "Merging video + audio..."
                    line.contains("[ExtractAudio]") -> "Converting to mp3..."
                    eta > 0 -> "Downloading · ${eta / 60}m ${eta % 60}s left"
                    else -> "Downloading..."
                }
                if (msg != lastMsg || kotlin.math.abs(pct - lastPct) >= 1f) {
                    lastMsg = msg
                    lastPct = pct
                    push(id, "running", pct, msg, title)
                }
            }

            push(id, "running", 100f, "Saving to Downloads...", title)
            val files = dir.listFiles()
                ?.filter { it.isFile && !it.name.endsWith(".part") && !it.name.endsWith(".ytdl") }
                ?: emptyList()
            if (files.isEmpty()) throw IllegalStateException("No file was produced")
            files.forEach { saveToDownloads(it) }
            if (title.isEmpty()) title = files.first().name
            push(id, "done", 100f, "Saved to Downloads/Grab", title)
        } catch (t: Throwable) {
            val m = t.message ?: "Failed"
            val err = m.lines().firstOrNull { it.startsWith("ERROR") } ?: m.lines().first()
            push(id, "error", 0f, err.take(140))
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun saveToDownloads(f: File) {
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(f.extension.lowercase())
            ?: "application/octet-stream"
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, f.name)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Grab")
        }
        val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("Could not create file in Downloads")
        contentResolver.openOutputStream(uri)!!.use { out ->
            f.inputStream().use { it.copyTo(out) }
        }
    }
}
