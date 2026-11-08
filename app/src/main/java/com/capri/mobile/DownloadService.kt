package com.capri.mobile

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/** One brain download at a time, in the background with a progress notification. Drops resume where they stopped. */
class DownloadService : Service() {
    /** id is the model's id, or "<id>:eyes" for its vision add-on. */
    data class State(val id: String = "", val done: Long = 0, val total: Long = 0, val speed: Long = 0, val error: String = "")

    /** One file to fetch: a brain, or a brain's eyes. */
    private data class Job(val id: String, val label: String, val url: String, val file: java.io.File, val bytes: Long,
                           val model: Model, val eyes: Boolean)

    companion object {
        val state = MutableStateFlow(State())
        private const val CHANNEL = "capri_dl"

        fun start(c: Context, m: Model, eyes: Boolean = false) {
            val i = Intent(c, DownloadService::class.java).putExtra("id", m.id).putExtra("eyes", eyes)
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i) else c.startService(i)
        }

        fun cancel(c: Context) = c.startService(Intent(c, DownloadService::class.java).setAction("cancel"))
    }

    @Volatile private var cancel = false
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var worker: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "cancel") {
            cancel = true
            return START_NOT_STICKY
        }
        val m = ModelStore.byId(this, intent?.getStringExtra("id") ?: "") ?: run { stopSelf(); return START_NOT_STICKY }
        val job = if (intent?.getBooleanExtra("eyes", false) == true && m.hasEyes) {
            Job(m.id + ":eyes", m.label + " eyes", m.eyesUrl, ModelStore.eyes(this, m), m.eyesBytes, m, true)
        } else {
            Job(m.id, m.label, m.url, ModelStore.file(this, m), m.bytes, m, false)
        }
        notify(job.label, 0, job.bytes, 0, first = true)
        if (worker?.isAlive == true) return START_NOT_STICKY  // one at a time
        cancel = false
        worker = thread(name = "capri-download") { run(job) }
        return START_NOT_STICKY
    }

    private fun run(m: Job) {
        val part = java.io.File(m.file.path + ".part")
        var err = ""
        var tries = 0
        state.value = State(m.id, part.length(), m.bytes)
        runCatching { part.parentFile?.mkdirs() }
        val room = runCatching { (part.parentFile ?: m.file.parentFile)!!.usableSpace }.getOrDefault(Long.MAX_VALUE)
        if (room < m.bytes - part.length() + 300_000_000L) {  // say so now instead of failing at 90%
            err = "not enough space: free up about %.1f GB on your phone".format((m.bytes - part.length() + 300_000_000L - room) / 1e9)
            tries = 99
        }
        while (!cancel && part.length() < m.bytes && tries < 40) {
            tries++
            try {
                val have = part.length()
                val c = URL(m.url).openConnection() as HttpURLConnection
                c.instanceFollowRedirects = true
                c.connectTimeout = 15000
                c.readTimeout = 30000
                c.setRequestProperty("User-Agent", "CapriMobile/0.3")
                if (have > 0) c.setRequestProperty("Range", "bytes=$have-")
                val code = c.responseCode
                if (code == 404 || code == 403 || code == 401) { err = "download link is dead ($code)"; break }
                val append = have > 0 && code == 206
                FileOutputStream(part, append).use { out ->
                    var done = if (append) have else 0L
                    val buf = ByteArray(1 shl 16)
                    val t0 = System.nanoTime()
                    val start = done
                    var last = 0L
                    c.inputStream.use { inp ->
                        while (!cancel) {
                            val n = inp.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            val now = System.nanoTime()
                            if (now - last > 400_000_000L) {
                                last = now
                                val speed = ((done - start) * 1_000_000_000L / maxOf(1L, now - t0))
                                state.value = State(m.id, done, m.bytes, speed)
                                notify(m.label, done, m.bytes, speed)
                            }
                        }
                    }
                }
                tries = 0
            } catch (e: Exception) {
                err = when (e) {
                    is java.net.UnknownHostException -> "no internet connection"
                    is java.io.IOException -> if ("ENOSPC" in e.message.orEmpty()) "your phone is out of space" else "connection dropped, retrying"
                    else -> e.message ?: e.javaClass.simpleName
                }
                if ("out of space" in err) break
                Thread.sleep(minOf(30_000L, 2000L * tries))
            }
        }
        val ok = part.length() == m.bytes
        if (ok) {
            part.renameTo(m.file)
            val ears = m.model.id == ModelStore.ASR.id
            if (!ears && !m.eyes && ModelStore.active(this) == null) Prefs(this).brain = m.model.id  // the first brain becomes hers
            if (ears && !m.eyes) handler.post { start(this, m.model, eyes = true) }  // her ears are two files
        }
        state.value = State(if (ok) "" else m.id, part.length(), m.bytes, 0,
            if (ok) "" else if (cancel) "paused" else err.ifEmpty { "stopped" })
        if (ok) CapriService.brainChanged(this)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun notify(label: String, done: Long, total: Long, speed: Long, first: Boolean = false) {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Brain downloads", NotificationManager.IMPORTANCE_LOW))
        }
        val pct = if (total > 0) (done * 100 / total).toInt() else 0
        val n = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_capri_stat)
            .setContentTitle("Downloading $label")
            .setContentText("%.2f of %.2f GB  ·  %.1f MB/s".format(done / 1e9, total / 1e9, speed / 1e6))
            .setProgress(100, pct, false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
        if (first) {
            ServiceCompat.startForeground(this, 8, n,
                if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0)
        } else {
            nm.notify(8, n)
        }
    }
}
