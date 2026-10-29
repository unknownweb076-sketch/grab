package com.grab.app

import android.app.Application
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread

class GrabApp : Application() {

    companion object {
        val ready = CountDownLatch(1)

        @Volatile
        var initError: String? = null
    }

    override fun onCreate() {
        super.onCreate()
        thread {
            try {
                YoutubeDL.getInstance().init(this)
                FFmpeg.getInstance().init(this)
            } catch (t: Throwable) {
                initError = t.message ?: "Init failed"
            }
            ready.countDown()
            // keep yt-dlp fresh so sites don't break
            try {
                YoutubeDL.getInstance().updateYoutubeDL(this)
            } catch (_: Throwable) {
            }
        }
    }
}
