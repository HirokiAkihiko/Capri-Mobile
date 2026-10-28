package com.capri.mobile

import android.content.Context
import java.io.File

/**
 * Brains she can run on the phone (llama.cpp GGUFs): only the ones trained to be Capri, smallest first, plus any .gguf
 * you add yourself. A brain dropped from this list in an update stays usable: its file shows up as one of your own.
 * Everything lives in the app's own storage: models/<file>. App updates keep them (and a download only becomes
 * <file> once it's complete, so any <file> there is a finished brain, even if a newer catalog lists another size).
 * Uninstalling asks whether to keep the app's data (hasFragileUserData).
 */
data class Model(val id: String, val label: String, val note: String, val url: String, val file: String, val bytes: Long,
                 val minRamGb: Int, val capri: Boolean = false,
                 val eyesUrl: String = "", val eyesFile: String = "", val eyesBytes: Long = 0) {
    /** Her eyes for this brain (its vision projector, an "mmproj" GGUF): optional, downloaded separately. */
    val hasEyes get() = eyesUrl.isNotEmpty()
}

object ModelStore {
    private const val HF = "https://huggingface.co/"
    val catalog = listOf(
        Model("minicpm", "CapriAI2 MiniCPM5 2B", "trained to be Capri, light",
            HF + "Ghasty3/CapriAI2/resolve/main/minicpm5-2b/CapriAI2-MiniCPM5-2B.Q4_K_M.gguf",
            "CapriAI2-MiniCPM5-2B.Q4_K_M.gguf", 1561314688, 4, capri = true),
        Model("qwen4b", "CapriAI2 Qwen3.5 4B", "trained to be Capri",
            HF + "Ghasty3/CapriAI2/resolve/main/qwen3.5-4b/Qwen3.5-4B.Q6_K.gguf",
            "CapriAI2-Qwen3.5-4B.Q6_K.gguf", 3563028064, 8, capri = true,
            eyesUrl = HF + "Ghasty3/CapriAI2/resolve/main/qwen3.5-4b/Qwen3.5-4B.BF16-mmproj.gguf",
            eyesFile = "CapriAI2-Qwen3.5-4B.BF16-mmproj.gguf", eyesBytes = 675568864),
        Model("gemma4", "CapriAI2 Gemma 4 E4B", "trained to be Capri, flagship phones",
            HF + "Ghasty3/CapriAI2/resolve/main/gemma-4-e4b-it/CapriAI2-Gemma-4-E4B-it.Q4_K_M.gguf",
            "CapriAI2-Gemma-4-E4B-it.Q4_K_M.gguf", 5335292352, 12, capri = true,
            eyesUrl = HF + "Ghasty3/CapriAI2/resolve/main/gemma-4-e4b-it/CapriAI2-Gemma-4-E4B-it.BF16-mmproj.gguf",
            eyesFile = "CapriAI2-Gemma-4-E4B-it.BF16-mmproj.gguf", eyesBytes = 991552032),
        Model("nemotron", "Nemotron Heretic E4B", "the closest to Capri, flagship phones",
            HF + "Ghasty3/CapriAI2/resolve/main/nemotron-3.5-bolt-e4b-heretic/Nemotron-3.5-Bolt-E4B-Heretic.Q4_K_M.gguf",
            "Nemotron-3.5-Bolt-E4B-Heretic.Q4_K_M.gguf", 5335293696, 12, capri = true,
            eyesUrl = HF + "Ghasty3/CapriAI2/resolve/main/nemotron-3.5-bolt-e4b-heretic/Nemotron-3.5-Bolt-E4B-Heretic.BF16-mmproj.gguf",
            eyesFile = "Nemotron-3.5-Bolt-E4B-Heretic.BF16-mmproj.gguf", eyesBytes = 991552032),
    )

    fun dir(c: Context) = File(c.filesDir, "models").apply { mkdirs() }
    fun file(c: Context, m: Model) = File(dir(c), m.file)
    fun part(c: Context, m: Model) = File(dir(c), m.file + ".part")
    fun installed(c: Context, m: Model) = file(c, m).let { it.exists() && it.length() > 0 }

    fun eyes(c: Context, m: Model) = File(dir(c), m.eyesFile)
    fun eyesInstalled(c: Context, m: Model) = m.hasEyes && eyes(c, m).let { it.exists() && it.length() > 0 }

    /** Your own GGUFs (added from the app), as models without a download link. */
    fun own(c: Context): List<Model> = dir(c).listFiles { f ->
        f.name.endsWith(".gguf") && "mmproj" !in f.name.lowercase() && catalog.none { it.file == f.name }
    }
        ?.sortedBy { it.name }?.map { Model("own:" + it.name, it.nameWithoutExtension, "your own model", "", it.name, it.length(), 0) }
        ?: emptyList()

    fun all(c: Context) = catalog + own(c)

    fun byId(c: Context, id: String) = all(c).firstOrNull { it.id == id }

    /**
     * The brain in use, if it's on the phone. If the one you picked is gone (renamed in an update, say), she takes the
     * biggest brain that is on the phone instead, so an update never leaves her without one.
     */
    fun active(c: Context): Model? {
        val prefs = Prefs(c)
        byId(c, prefs.brain)?.let { if (it.url.isEmpty() || installed(c, it)) return it }
        val m = all(c).filter { it.url.isEmpty() || installed(c, it) }.maxByOrNull { file(c, it).length() } ?: return null
        prefs.brain = m.id
        return m
    }

    fun ramGb(c: Context): Int {
        val am = c.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val mi = android.app.ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        return ((mi.totalMem + (1L shl 29)) shr 30).toInt()
    }
}
