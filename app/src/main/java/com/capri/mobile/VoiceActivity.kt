package com.capri.mobile

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent

/**
 * The fallback ears: the phone's own voice-typing popup (Google's, Samsung's, Xiaomi's...). Used when the speech
 * recognizer can't be driven from her overlay on this phone. Opening it brings the app to the front for a moment,
 * which is also what lets the mic work on phones that block it for apps in the background. No window of its own:
 * the popup shows, the words go to her, it closes.
 */
class VoiceActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return  // rotated mid-popup: the popup's result still comes back to us
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PROMPT, "Talk to Capri")
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(i, 1)
        } catch (e: ActivityNotFoundException) {
            CapriService.instance?.voiceResult(null, "This phone has no voice typing at all. Type to me instead.")
            finish()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        val text = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
            ?.takeIf { resultCode == RESULT_OK && it.isNotBlank() }
        val her = CapriService.instance
        if (her != null) {
            her.voiceResult(text, if (resultCode == RESULT_CANCELED) "" else null)
        } else if (text != null) {  // she isn't on screen (the app's mic button): summon her with what you said
            val i = Intent(this, CapriService::class.java).setAction(CapriService.ACTION_ASK).putExtra("text", text)
            if (android.os.Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
        }
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }
}
