package com.capri.mobile

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import java.io.File

/**
 * Brains she can run on the phone (llama.cpp GGUFs): the desktop app's three, plus any .gguf
 * you add yourself. A brain dropped from this list in an update stays usable: its file shows up as one of your own.
 * Brains live in the app's own storage (models/<file>) until you switch on "Keep brains safe": then they live in
 * Capri/models on the phone's shared storage, which survives deleting the app, and a new install finds them by file
 * name as soon as it's allowed to read that folder. A download only becomes <file> once it's complete, so any <file>
 * is a finished brain, even if a newer catalog lists another size.
 */
data class Model(val id: String, val label: String, val note: String, val url: String, val file: String, val bytes: Long,
                 val minRamGb: Int, val capri: Boolean = false,
                 val eyesUrl: String = "", val eyesFile: String = "", val eyesBytes: Long = 0) {
    /** Her eyes for this brain (its vision projector, an "mmproj" GGUF): optional, downloaded separately. */
    val hasEyes get() = eyesUrl.isNotEmpty()
}

object ModelStore {
    private const val HF = "https://huggingface.co/"
    val catalog = listOf(  // the same three brains as the desktop app
        Model("nemotron", "Nemotron Heretic E4B", "the best one: smart, real answers, and it can see your screen with its eyes",
            HF + "Ghasty3/CapriAI2/resolve/main/nemotron-3.5-bolt-e4b-heretic/Nemotron-3.5-Bolt-E4B-Heretic.Q4_K_M.gguf",
            "Nemotron-3.5-Bolt-E4B-Heretic.Q4_K_M.gguf", 5335293696, 12, capri = true,
            eyesUrl = HF + "Ghasty3/CapriAI2/resolve/main/nemotron-3.5-bolt-e4b-heretic/Nemotron-3.5-Bolt-E4B-Heretic.BF16-mmproj.gguf",
            eyesFile = "Nemotron-3.5-Bolt-E4B-Heretic.BF16-mmproj.gguf", eyesBytes = 991552032),
        Model("conv7b", "Conversational 7B", "fast and chatty, but gets facts wrong sometimes",
            HF + "xiaoheiqaq/conversational-7b-v0.4-Q4_K_M-GGUF/resolve/main/conversational-7b-v0.4-q4_k_m.gguf",
            "conversational-7b-v0.4-q4_k_m.gguf", 4372815648, 8),
        Model("qwen4b", "CapriAI2 (Qwen3.5 4B)", "trained to be Capri; small and new",
            HF + "Ghasty3/CapriAI2/resolve/main/qwen3.5-4b/Qwen3.5-4B.Q4_K_M.gguf",
            "CapriAI2-Qwen3.5-4B.Q4_K_M.gguf", 2783446624, 6, capri = true,
            eyesUrl = HF + "Ghasty3/CapriAI2/resolve/main/qwen3.5-4b/Qwen3.5-4B.BF16-mmproj.gguf",
            eyesFile = "CapriAI2-Qwen3.5-4B.BF16-mmproj.gguf", eyesBytes = 675568864),
    )

    /** Her ears, same as the desktop: the "eyes" fields hold its audio projector (both files are needed). */
    val ASR = Model("qwenasr", "Qwen3-ASR", "the sharpest ears: names, slang and accents, 52 languages",
        HF + "ggml-org/Qwen3-ASR-0.6B-GGUF/resolve/main/Qwen3-ASR-0.6B-Q8_0.gguf", "Qwen3-ASR-0.6B-Q8_0.gguf", 804749248, 4,
        eyesUrl = HF + "ggml-org/Qwen3-ASR-0.6B-GGUF/resolve/main/mmproj-Qwen3-ASR-0.6B-Q8_0.gguf",
        eyesFile = "mmproj-Qwen3-ASR-0.6B-Q8_0.gguf", eyesBytes = 214392480)

    fun earsReady(c: Context) = installed(c, ASR) && eyesInstalled(c, ASR)

    /** Not a file on the phone: NVIDIA's cloud, with your own free key. */
    val NV = Model("nvidia", "Nemotron Ultra 550B", "NVIDIA's cloud, with your own free key", "", "", 0, 0)
    const val NV_URL = "https://integrate.api.nvidia.com"
    const val NV_MODEL = "nvidia/nemotron-3-ultra-550b-a55b"
    // Nemotron Ultra reads text only, so she looks through NVIDIA's vision models, on the same key (the first that answers)
    val NV_EYES = listOf("nvidia/nemotron-3.5-super-vl-120b-a12b", "nvidia/nemotron-3-nano-omni-30b-a3b-reasoning")
    const val NV_KEY_PAGE = "https://build.nvidia.com/nvidia/nemotron-3-ultra-550b-a55b"

    fun remote(m: Model) = m.id == LM.id || m.id == NV.id

    /** Not a file on the phone: the model loaded in LM Studio on your PC (see [LmStudio]). */
    val LM = Model("lmstudio", "LM Studio", "on your PC, over Wi-Fi", "", "", 0, 0)

    private fun private(c: Context) = File(c.filesDir, "models").apply { mkdirs() }
    private fun shared() = File(Environment.getExternalStorageDirectory(), "Capri/models")

    /** May she use the shared folder? (All files access on Android 11+, plain storage before that.) */
    fun canShare(c: Context) = if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
    else c.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    /** Where new brains go. */
    fun dir(c: Context): File = if (canShare(c)) shared().apply { mkdirs() } else private(c)

    private fun dirs(c: Context) = if (canShare(c)) listOf(dir(c), private(c)) else listOf(private(c))

    /** Where [name] is (or its unfinished download), else where it would go. */
    private fun find(c: Context, name: String): File {
        val at = dirs(c).firstOrNull { File(it, name).exists() || File(it, "$name.part").exists() } ?: dir(c)
        return File(at, name)
    }

    val sharedPath get() = "Capri/models"

    fun file(c: Context, m: Model) = find(c, m.file)
    fun part(c: Context, m: Model) = File(find(c, m.file).path + ".part")
    fun installed(c: Context, m: Model) = file(c, m).let { it.exists() && it.length() > 0 }

    fun eyes(c: Context, m: Model) = find(c, m.eyesFile)
    fun eyesInstalled(c: Context, m: Model) = m.hasEyes && eyes(c, m).let { it.exists() && it.length() > 0 }

    /** Your own GGUFs (added from the app), as models without a download link. */
    fun own(c: Context): List<Model> = dirs(c).flatMap { d ->
        d.listFiles { f -> f.name.endsWith(".gguf") && "mmproj" !in f.name.lowercase() && f.name != ASR.file && catalog.none { it.file == f.name } }
            ?.toList().orEmpty()
    }.distinctBy { it.name }.sortedBy { it.name }
        .map { Model("own:" + it.name, it.nameWithoutExtension, "your own model", "", it.name, it.length(), 0) }

    /** Moves brains out of the app's private storage into the shared folder, so they outlive the app. */
    fun moveToShared(c: Context, progress: (String) -> Unit) {
        val to = shared().apply { mkdirs() }
        val files = private(c).listFiles { f -> f.isFile && f.name.endsWith(".gguf") }.orEmpty()
        for (f in files) {
            val out = File(to, f.name)
            if (out.exists()) { f.delete(); continue }
            if (to.usableSpace < f.length() + 200_000_000L) { progress("Not enough space to move ${f.name}. It stays in the app."); continue }
            progress("Moving ${f.name}...")
            val tmp = File(to, f.name + ".moving")
            try {
                f.copyTo(tmp, overwrite = true)
                tmp.renameTo(out)
                f.delete()
            } catch (e: Exception) {
                tmp.delete()
                progress("Couldn't move ${f.name}: ${e.message}")
                return
            }
        }
        progress("")
    }

    fun all(c: Context) = catalog + own(c)

    fun byId(c: Context, id: String) = (all(c) + ASR).firstOrNull { it.id == id }

    /**
     * The brain in use, if it's on the phone. If the one you picked is gone (renamed in an update, say), she takes the
     * biggest brain that is on the phone instead, so an update never leaves her without one.
     */
    fun active(c: Context): Model? {
        val prefs = Prefs(c)
        if (prefs.brain == LM.id) return LM
        if (prefs.brain == NV.id) return NV
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
