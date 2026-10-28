package com.capri.mobile

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * Her ears: the phone's own speech recognizer (on-device where the phone has it). Only on while you hold her;
 * nothing is recorded or kept.
 */
class Ears(private val context: Context, private val heard: (String) -> Unit, private val level: (Float) -> Unit,
           private val failed: (String) -> Unit) {
    private var sr: SpeechRecognizer? = null
    var listening = false
        private set

    val available get() = SpeechRecognizer.isRecognitionAvailable(context)

    fun start() {
        if (listening) return
        val r = sr ?: SpeechRecognizer.createSpeechRecognizer(context).also { sr = it }
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
                failed(when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "nothing"
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "mic"
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "net"
                    else -> "error $error"
                })
            }
            override fun onResults(results: Bundle?) {
                listening = false
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                if (text.isBlank()) failed("nothing") else heard(text)
            }
        })
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        listening = true
        r.startListening(i)
    }

    /** Let go of her: stop listening and use what was said. */
    fun stop() {
        if (listening) sr?.stopListening()
    }

    fun release() {
        sr?.destroy()
        sr = null
        listening = false
    }
}
