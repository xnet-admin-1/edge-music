package com.edgemusic

import android.content.Context
import java.io.File

/**
 * Manages ACE-Step GGUF model files.
 * Models are loaded via file picker — no auto-download.
 */
object ModelManager {

    enum class ModelSlot(val label: String) {
        LM("Language Model"),
        TEXT_ENC("Text Encoder"),
        DIT("DiT (Diffusion)"),
        VAE("VAE Decoder")
    }

    private val loaded = mutableMapOf<ModelSlot, File>()

    fun getModelsDir(context: Context): File {
        return File(context.getExternalFilesDir(null), "models").also { it.mkdirs() }
    }

    fun setModel(slot: ModelSlot, file: File) {
        loaded[slot] = file
    }

    fun getModel(slot: ModelSlot): File? = loaded[slot]

    fun allModelsReady(): Boolean = ModelSlot.entries.all { loaded[it]?.exists() == true }

    fun getStatus(): Map<ModelSlot, String> {
        return ModelSlot.entries.associateWith { slot ->
            loaded[slot]?.name ?: "Not loaded"
        }
    }

    /** Copy a picked URI file into models dir and register it */
    fun importModel(context: Context, slot: ModelSlot, uri: android.net.Uri): File? {
        val dir = getModelsDir(context)
        val fileName = slot.name.lowercase() + ".gguf"
        val dest = File(dir, fileName)
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            }
            loaded[slot] = dest
            return dest
        } catch (e: Exception) {
            return null
        }
    }
}
