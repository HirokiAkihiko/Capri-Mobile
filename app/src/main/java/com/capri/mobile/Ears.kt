package com.capri.mobile

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * Her ears: the phone's own speech recognizer. Only on while you hold her; nothing is recorded or kept.
 *
 * Phones differ a lot here, so it tries its way through: offline first (private, fast), then online when the phone
 * has no offline language pack, a fresh recognizer when the old one got stuck, and the phone's on-device engine
 * (Android 12+) when the normal one won't start. If all of that fails, [failed] gets "popup": the service then opens
 * the phone's own voice-typing popup instead (VoiceActivity), which works on nearly every phone.
 */
class Ears(private val context: Context, private val heard: (String) -> Unit, private val level: (Float) -> Unit,
           private val failed: (String) -> Unit) {
    private var sr: SpeechRecognizer? = null
    private val main = Handler(Looper.getMainLooper())
    var listening = false
        private set
    private var offline = true  // ask for on-device recognition first
    private var onDevice = false  // Android 12+: the phone's on-device engine instead of the default one
    private var tries = 0
    private var wanted = false  // you're still holding her: only then does a hiccup get retried
    var lastError = ""  // e.g. "LANGUAGE_UNAVAILABLE (13)", for whoever's debugging (logcat tag CapriEars)
        private set

    val available get() = SpeechRecognizer.isRecognitionAvailable(context) ||
        (Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(context))

    fun start() {
        if (listening) return
        tries = 0
        wanted = true
        begin()
    }

    private fun begin() {
        val r = sr ?: create().also { sr = it }
        if (r == null) {
            fail("NO_RECOGNIZER", -1)
            return
        }
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = level(((rmsdB + 2f) / 12f).coerceIn(0f, 1f))
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onPartialResults(partialResults: Bundle?) = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
            override fun onError(error: Int) {
                listening = false
                if (!wanted && error != SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                    failed("nothing")  // you let go before it got going: no retries, no popup
                    return
                }
                when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> failed("nothing")
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> failed("mic")
                    // no offline language pack on this phone: the same thing online
                    12, 13 -> if (offline) { offline = false; retry() } else fail(name(error), error)
                    // stuck, busy, or its service went away: a fresh recognizer, then the on-device engine
                    SpeechRecognizer.ERROR_CLIENT, SpeechRecognizer.ERROR_RECOGNIZER_BUSY, SpeechRecognizer.ERROR_SERVER,
                    SpeechRecognizer.ERROR_AUDIO, 11 -> when {
                        tries < 1 -> { fresh(); retry() }
                        !onDevice && Build.VERSION.SDK_INT >= 31 &&
                            SpeechRecognizer.isOnDeviceRecognitionAvailable(context) -> { onDevice = true; fresh(); retry() }
                        else -> fail(name(error), error)
                    }
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
                        if (!offline) { offline = true; retry() } else fail(name(error), error)
                    else -> fail(name(error), error)
                }
            }
            override fun onResults(results: Bundle?) {
                listening = false
                wanted = false
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                if (text.isBlank()) failed("nothing") else heard(text)
            }
        })
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        if (offline) i.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        listening = true
        try {
            r.startListening(i)
        } catch (e: Exception) {
            listening = false
            fail("START_FAILED ${e.javaClass.simpleName}", -2)
        }
    }

    private fun create(): SpeechRecognizer? = try {
        if (onDevice && Build.VERSION.SDK_INT >= 31) SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        else SpeechRecognizer.createSpeechRecognizer(context)
    } catch (e: Exception) {
        null
    }

    private fun retry() {
        tries++
        if (!wanted) {  // you let go meanwhile
            listening = false
            failed("nothing")
            return
        }
        if (tries > 4) {
            fail("GAVE_UP", -3)
            return
        }
        listening = true
        main.postDelayed({ begin() }, 250)  // the old session needs a moment to let go of the mic
    }

    private fun fresh() {
        try {
            sr?.destroy()
        } catch (_: Exception) {
        }
        sr = null
    }

    private fun fail(what: String, code: Int) {
        lastError = if (code >= 0) "$what ($code)" else what
        listening = false
        android.util.Log.w("CapriEars", "speech recognizer failed: $lastError (offline=$offline onDevice=$onDevice)")
        failed("popup")
    }

    /** Let go of her: stop listening and use what was said. */
    fun stop() {
        if (!listening) return
        wanted = false  // a hiccup from here on isn't retried (that started her listening again after you let go)
        sr?.stopListening()  // what was said so far still comes back in onResults
    }

    fun release() {
        fresh()
        listening = false
        wanted = false
    }

    companion object {
        fun name(code: Int) = when (code) {
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "NETWORK_TIMEOUT"
            SpeechRecognizer.ERROR_NETWORK -> "NETWORK"
            SpeechRecognizer.ERROR_AUDIO -> "AUDIO"
            SpeechRecognizer.ERROR_SERVER -> "SERVER"
            SpeechRecognizer.ERROR_CLIENT -> "CLIENT"
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "SPEECH_TIMEOUT"
            SpeechRecognizer.ERROR_NO_MATCH -> "NO_MATCH"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "RECOGNIZER_BUSY"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "INSUFFICIENT_PERMISSIONS"
            10 -> "TOO_MANY_REQUESTS"
            11 -> "SERVER_DISCONNECTED"
            12 -> "LANGUAGE_NOT_SUPPORTED"
            13 -> "LANGUAGE_UNAVAILABLE"
            14 -> "CANNOT_CHECK_SUPPORT"
            15 -> "CANNOT_LISTEN_TO_DOWNLOAD_EVENTS"
            else -> "ERROR"
        }
    }
}
