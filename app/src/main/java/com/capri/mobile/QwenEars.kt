package com.capri.mobile

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors

/**
 * Her own ears, the same as the desktop's: Qwen3-ASR running on the phone through llama.cpp. It gets names and slang
 * right, knows 52 languages, and nothing leaves the phone. Records only while you hold her; the audio is never saved.
 * [heard] gets what you said, [failed] "nothing" when it was silence; both on the main thread.
 */
class QwenEars(private val context: Context, private val heard: (String) -> Unit, private val failed: (String) -> Unit) {
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "capri-ears") }
    private val main = Handler(Looper.getMainLooper())
    private var handle = 0L
    @Volatile private var recording = false
    private var mic: Thread? = null
    private var pcm = ShortArray(RATE * 30)
    private var n = 0

    private val rest = Runnable { free() }

    /** Frees her ears (about 1 GB). They load again the next time you hold her to talk. */
    fun free() = worker.execute {
        if (handle != 0L) { Llama.free(handle); handle = 0L }
    }

    /** Starts loading her ears before you've finished talking the first time. */
    fun warm() = worker.execute { load() }

    private fun load() {
        if (handle != 0L) return
        val threads = ModelStore.threads()
        val h = runCatching { Llama.load(ModelStore.file(context, ModelStore.ASR).path, 1024, threads) }.getOrDefault(0L)
        if (h != 0L && runCatching { Llama.loadEars(h, ModelStore.eyes(context, ModelStore.ASR).path, threads) }.getOrDefault(false)) handle = h
        else if (h != 0L) Llama.free(h)
    }

    @SuppressLint("MissingPermission")  // only called once the mic is allowed
    fun start() {
        if (recording) return
        warm()
        n = 0
        recording = true
        mic = Thread({
            val size = maxOf(AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT), RATE / 2)
            val rec = runCatching {
                AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, size)
            }.getOrNull()
            if (rec == null || rec.state != AudioRecord.STATE_INITIALIZED) {
                rec?.release()
                recording = false
                main.post { failed("mic") }
                return@Thread
            }
            rec.startRecording()
            while (recording && n < pcm.size) {
                val got = rec.read(pcm, n, minOf(RATE / 10, pcm.size - n))
                if (got <= 0) break
                n += got
            }
            rec.stop()
            rec.release()
        }, "capri-mic").apply { start() }
    }

    /** You let go: what you said goes through her ears. */
    fun stop() {
        if (!recording) return
        recording = false
        mic?.join(500)
        val samples = FloatArray(n) { pcm[it] / 32768f }
        worker.execute {
            val text = transcribe(samples)
            main.post { if (text.isEmpty()) failed("nothing") else heard(text) }
            main.removeCallbacks(rest)
            main.postDelayed(rest, 120_000)  // not talking for a while: give the memory back
        }
    }

    /** What's in [samples] (16 kHz mono), or "" for silence. Blocks. */
    fun transcribe(samples: FloatArray): String {
        if (samples.size < RATE / 3) return ""
        load()
        if (handle == 0L) return ""
        val seconds = samples.size / RATE
        val out = runCatching { Llama.hear(handle, samples, CONTEXT, 48 + 12 * seconds) }.getOrDefault("")
        return parse(out)
    }

    fun release() {
        recording = false
        worker.execute {
            if (handle != 0L) Llama.free(handle)
            handle = 0L
        }
        worker.shutdown()
    }

    companion object {
        const val RATE = 16000
        // told her name and the usual slang, so it hears them right (same list as the desktop)
        private const val CONTEXT = "Capri, choom, gonk, preem, Night City, Edgerunners, Arasaka, Discord, Spotify, YouTube, Steam"
        private val LINE = Regex("\\s*language\\s+(\\w+)\\s*<asr_text>(.*)", RegexOption.DOT_MATCHES_ALL)

        /** "language English<asr_text>Hey Capri." -> "Hey Capri."; "language None" is silence. */
        fun parse(out: String): String {
            val m = LINE.matchAt(out, 0) ?: return out.replace("<asr_text>", " ").trim().replace(Regex("\\s+"), " ")
            if (m.groupValues[1].equals("none", true)) return ""
            return m.groupValues[2].trim().replace(Regex("\\s+"), " ")
        }
    }
}
