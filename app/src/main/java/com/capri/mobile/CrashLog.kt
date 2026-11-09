package com.capri.mobile

import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Starts the crash log before anything else runs. */
class CapriApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
        Lang.init(this)
    }
}

/**
 * What went wrong last time, so people can send it to us: our own crashes (with the stack), plus what Android says
 * about the times it stopped her (killed for memory, a crash inside the brain, a freeze). Phone model, Android
 * version, app version and RAM only: nothing personal.
 */
object CrashLog {
    private const val FILE = "crash.txt"

    fun install(c: Context) {
        val app = c.applicationContext
        val before = Thread.getDefaultUncaughtExceptionHandler()
        if (before is Catcher) return
        Thread.setDefaultUncaughtExceptionHandler(Catcher(app, before))
    }

    private class Catcher(val app: Context, val next: Thread.UncaughtExceptionHandler?) : Thread.UncaughtExceptionHandler {
        override fun uncaughtException(t: Thread, e: Throwable) {
            runCatching { File(app.filesDir, FILE).writeText(header(app) + "Crash in thread ${t.name}\n" + Log.getStackTraceString(e).take(12000)) }
            next?.uncaughtException(t, e)
        }
    }

    fun header(c: Context): String {
        val when_ = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())
        return "Capri ${Updates.version(c)} · ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · " +
            "${ModelStore.ramGb(c)} GB RAM · $when_\n"
    }

    /** The report about the last time she died, or "" when all is well. */
    fun last(c: Context): String {
        val out = StringBuilder()
        val f = File(c.filesDir, FILE)
        if (f.exists()) out.append(runCatching { f.readText() }.getOrDefault(""))
        if (Build.VERSION.SDK_INT >= 30) runCatching {
            val seen = c.getSharedPreferences("crash", Context.MODE_PRIVATE).getLong("seen", 0L)
            val am = c.getSystemService(ActivityManager::class.java)
            val bad = am.getHistoricalProcessExitReasons(c.packageName, 0, 8).firstOrNull { it.timestamp > seen && it.reason in REASONS }
            if (bad != null) {
                if (out.isEmpty()) out.append(header(c))
                out.append("Android stopped her: ").append(REASONS[bad.reason]).append(" at ")
                    .append(SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(bad.timestamp)))
                    .append(" (memory used: ").append(bad.pss / 1024).append(" MB)\n")
                bad.description?.let { out.append(it.take(400)).append('\n') }
                if (bad.reason == ApplicationExitInfo.REASON_ANR || bad.reason == ApplicationExitInfo.REASON_CRASH_NATIVE) {
                    runCatching { bad.traceInputStream?.use { s -> out.append(String(s.readBytes().take(6000).toByteArray())) } }
                }
            }
        }
        return out.toString().trim()
    }

    fun clear(c: Context) {
        File(c.filesDir, FILE).delete()
        c.getSharedPreferences("crash", Context.MODE_PRIVATE).edit().putLong("seen", System.currentTimeMillis()).apply()
    }

    private val REASONS = mapOf(
        ApplicationExitInfo.REASON_CRASH to "a crash",
        ApplicationExitInfo.REASON_CRASH_NATIVE to "a crash inside her brain (native)",
        ApplicationExitInfo.REASON_ANR to "she froze (not responding)",
        ApplicationExitInfo.REASON_LOW_MEMORY to "the phone ran out of memory",
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE to "she used too much battery or CPU",
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE to "she couldn't start",
    )
}
