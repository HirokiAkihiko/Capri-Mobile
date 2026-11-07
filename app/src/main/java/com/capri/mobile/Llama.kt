package com.capri.mobile

/** llama.cpp (b11932, the same build as the desktop) compiled into the app: native code in src/main/cpp. */
object Llama {
    init {
        System.loadLibrary("capri_llama")
    }

    fun interface Piece {
        /** A bit of the reply as it's written. Return false to stop. */
        fun onPiece(text: String): Boolean
    }

    /** Loads a GGUF; returns a handle, or 0 (see [error]). */
    external fun load(path: String, nCtx: Int, nThreads: Int): Long

    external fun error(): String

    /** Chat with the model's own template (thinking switched off); returns the whole reply. */
    external fun generate(handle: Long, roles: Array<String>, contents: Array<String>, maxTokens: Int, temperature: Float,
                          piece: Piece): String

    /** Loads her eyes (the brain's vision projector, an mmproj GGUF). */
    external fun loadEyes(handle: Long, path: String, nThreads: Int): Boolean

    /** Like [generate], with a picture (RGB bytes, w x h) where "{IMAGE}" sits in the last message. */
    external fun look(handle: Long, rgb: ByteArray, w: Int, h: Int, roles: Array<String>, contents: Array<String>,
                      maxTokens: Int, temperature: Float, piece: Piece): String

    /** Loads her ears (Qwen3-ASR's audio projector) onto a [load]ed Qwen3-ASR model. */
    external fun loadEars(handle: Long, path: String, nThreads: Int): Boolean

    /** 16 kHz mono samples in, "language English<asr_text>..." out. [context]: words it should expect. */
    external fun hear(handle: Long, pcm: FloatArray, context: String, maxTokens: Int): String

    external fun free(handle: Long)
}
