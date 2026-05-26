package com.edgemusic

import android.content.Context
import kotlinx.coroutines.*
import java.io.File
import java.net.URL

/**
 * Manages ACE-Step GGUF model downloads.
 * Models stored in app's external files dir.
 */
object ModelManager {

    private const val HF_BASE = "https://huggingface.co/Serveurperso/ACE-Step-1.5-GGUF/resolve/main"

    val REQUIRED_MODELS = listOf(
        ModelFile("acestep-5Hz-lm-0.6B-BF16.gguf", 1331L),       // LM (1.3GB)
        ModelFile("Qwen3-Embedding-0.6B-Q8_0.gguf", 784L),       // Text encoder
        ModelFile("acestep-v15-turbo-Q4_K_M.gguf", 1450L),       // DiT turbo
        ModelFile("vae-BF16.gguf", 322L),                          // VAE decoder
    )

    data class ModelFile(val name: String, val sizeMB: Long)

    fun getModelsDir(context: Context): File {
        return File(context.getExternalFilesDir(null), "models").also { it.mkdirs() }
    }

    fun getMissingModels(context: Context): List<ModelFile> {
        val dir = getModelsDir(context)
        return REQUIRED_MODELS.filter { !File(dir, it.name).exists() }
    }

    fun allModelsReady(context: Context): Boolean = getMissingModels(context).isEmpty()

    suspend fun downloadModel(
        context: Context,
        model: ModelFile,
        onProgress: (Float) -> Unit = {}
    ): Boolean = withContext(Dispatchers.IO) {
        val outFile = File(getModelsDir(context), model.name)
        val tmpFile = File(outFile.path + ".tmp")
        try {
            val url = URL("$HF_BASE/${model.name}")
            val conn = url.openConnection()
            conn.connectTimeout = 30000
            val totalBytes = conn.contentLengthLong
            var downloaded = 0L
            conn.getInputStream().use { input ->
                tmpFile.outputStream().use { output ->
                    val buf = ByteArray(65536)
                    var n: Int
                    while (input.read(buf).also { n = it } > 0) {
                        output.write(buf, 0, n)
                        downloaded += n
                        if (totalBytes > 0) onProgress(downloaded.toFloat() / totalBytes)
                    }
                }
            }
            tmpFile.renameTo(outFile)
            true
        } catch (e: Exception) {
            tmpFile.delete()
            android.util.Log.e("ModelManager", "Download failed: ${model.name}", e)
            false
        }
    }
}
