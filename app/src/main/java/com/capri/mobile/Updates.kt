package com.capri.mobile

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * New version alert: on Wi-Fi she asks GitHub for the latest release, a few times a day at most. If it's newer than
 * this app you get a notification and a card on Home, and both open the Releases page. Nothing is downloaded here.
 */
object Updates {
    private const val API = "https://api.github.com/repos/Capri-cmd/Capri-Mobile/releases/latest"
    const val PAGE = "$REPO_URL/releases/latest"
    private const val EVERY_MS = 6 * 3600 * 1000L
    private const val CHANNEL = "capri_updates"

    fun version(c: Context): String =
        runCatching { c.packageManager.getPackageInfo(c.packageName, 0).versionName }.getOrNull().orEmpty()

    /** "v0.3.2" against "0.3.1-alpha": only the numbers count. */
    fun newer(tag: String, than: String): Boolean {
        fun parts(s: String) = Regex("\\d+").findAll(s.substringBefore('-')).map { it.value.toIntOrNull() ?: 0 }.toList()
        val a = parts(tag)
        val b = parts(than)
        for (i in 0 until maxOf(a.size, b.size)) {
            val d = a.getOrElse(i) { 0 } - b.getOrElse(i) { 0 }
            if (d != 0) return d > 0
        }
        return false
    }

    /** The version Home should tell you about, or "" when you're up to date or said Later. */
    fun waiting(c: Context, prefs: Prefs): String {
        val tag = prefs.updateTag
        return if (tag.isNotEmpty() && tag != prefs.updateSkipped && newer(tag, version(c))) tag else ""
    }

    private fun onWifi(c: Context): Boolean {
        val cm = c.getSystemService(ConnectivityManager::class.java) ?: return false
        return cm.getNetworkCapabilities(cm.activeNetwork)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
    }

    /** Asks GitHub unless it was asked lately. [found] runs, off the main thread, the first time a version shows up. */
    fun check(c: Context, found: (String) -> Unit = {}) {
        val app = c.applicationContext
        val prefs = Prefs(app)
        val now = System.currentTimeMillis()
        if (now - prefs.updateChecked < EVERY_MS || !onWifi(app)) return
        prefs.updateChecked = now
        thread(name = "capri-update") {
            val tag = runCatching { latest() }.getOrElse { prefs.updateChecked = 0L; "" }  // no connection: ask again soon
            if (tag.isEmpty()) return@thread
            prefs.updateTag = tag
            if (newer(tag, version(app)) && tag != prefs.updateTold) {
                prefs.updateTold = tag
                notify(app, tag)
                found(tag)
            }
        }
    }

    private fun latest(): String {
        val h = URL(API).openConnection() as HttpURLConnection
        h.connectTimeout = 8000
        h.readTimeout = 8000
        h.setRequestProperty("Accept", "application/vnd.github+json")
        h.setRequestProperty("User-Agent", "Capri-Mobile")
        try {
            if (h.responseCode != 200) return ""
            return JSONObject(h.inputStream.bufferedReader().use { it.readText() }).optString("tag_name")
        } finally {
            h.disconnect()
        }
    }

    private fun notify(c: Context, tag: String) {
        val nm = c.getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel(CHANNEL, "New versions", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(c, 10, Intent(Intent.ACTION_VIEW, Uri.parse(PAGE)),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        runCatching {
            nm.notify(8, NotificationCompat.Builder(c, CHANNEL)
                .setSmallIcon(R.drawable.ic_capri_stat)
                .setContentTitle("New Capri update")
                .setContentText("Version ${tag.removePrefix("v")} is out. Tap to get it.")
                .setContentIntent(open)
                .setAutoCancel(true)
                .build())
        }
    }
}
