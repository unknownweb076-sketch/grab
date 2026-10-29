package com.grab.app

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Toast
import java.io.File

class CookieActivity : Activity() {

    private var site = "youtube"

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        site = intent.getStringExtra("site") ?: "youtube"
        val start = if (site == "instagram") {
            "https://www.instagram.com/accounts/login/"
        } else {
            "https://accounts.google.com/ServiceLogin?service=youtube&continue=https%3A%2F%2Fwww.youtube.com%2F"
        }

        val web = WebView(this)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        // look like normal Chrome, not an embedded WebView, so Google allows sign-in
        web.settings.userAgentString = web.settings.userAgentString
            .replace("; wv", "")
            .replace(Regex("""Version/\d+\.\d+ """), "")
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
        web.webViewClient = WebViewClient()
        web.loadUrl(start)

        val done = Button(this).apply {
            text = "Done, I'm signed in"
            setOnClickListener {
                save()
                finish()
            }
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF0B0D14.toInt())
            addView(web, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(
                done,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }
        setContentView(root)
    }

    private fun save() {
        val cm = CookieManager.getInstance()
        cm.flush()
        val targets: List<Pair<String, String>> = if (site == "instagram") {
            listOf("https://www.instagram.com" to ".instagram.com")
        } else {
            listOf(
                "https://www.youtube.com" to ".youtube.com",
                "https://accounts.google.com" to ".google.com",
                "https://www.google.com" to ".google.com"
            )
        }
        val exp = System.currentTimeMillis() / 1000 + 31536000
        val sb = StringBuilder()
        for ((url, domain) in targets) {
            val raw = cm.getCookie(url) ?: continue
            for (part in raw.split(";")) {
                val p = part.trim()
                val i = p.indexOf('=')
                if (i <= 0) continue
                sb.append(domain).append("\tTRUE\t/\tTRUE\t").append(exp).append('\t')
                    .append(p.substring(0, i)).append('\t').append(p.substring(i + 1)).append('\n')
            }
        }
        val text = sb.toString()
        val signedIn = if (site == "instagram") {
            text.contains("\tsessionid\t")
        } else {
            text.contains("\tSAPISID\t") || text.contains("\t__Secure-3PAPISID\t")
        }
        val f = File(filesDir, "cookies_$site.txt")
        if (signedIn) {
            f.writeText(text)
            Toast.makeText(this, "Signed in ✓", Toast.LENGTH_SHORT).show()
        } else {
            f.delete()
            Toast.makeText(this, "Not signed in yet. Try again.", Toast.LENGTH_LONG).show()
        }
    }
}
