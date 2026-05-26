package com.edgemusic

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var slotsLayout: LinearLayout
    private lateinit var startBtn: Button
    private var pendingSlot: ModelManager.ModelSlot? = null
    private var serverRunning = false

    private val filePicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        val slot = pendingSlot ?: return@registerForActivityResult
        uri ?: return@registerForActivityResult
        val file = ModelManager.importModel(this, slot, uri)
        if (file != null) {
            updateUI()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.status_text)
        slotsLayout = findViewById(R.id.slots_layout)
        startBtn = findViewById(R.id.start_btn)

        startBtn.setOnClickListener {
            if (serverRunning) {
                stopService(Intent(this, MusicService::class.java))
                serverRunning = false
                startBtn.text = "Start Server"
                statusText.text = "Server stopped"
            } else if (ModelManager.allModelsReady()) {
                startService(Intent(this, MusicService::class.java).apply { action = MusicService.ACTION_START })
                serverRunning = true
                startBtn.text = "Stop Server"
                statusText.text = "Server running on port ${MusicService.DEFAULT_PORT}"
                // Open WebView
                startActivity(Intent(this, WebViewActivity::class.java))
            }
        }

        // Auto-detect models already in dir
        val dir = ModelManager.getModelsDir(this)
        dir.listFiles()?.forEach { file ->
            val name = file.name.lowercase()
            val slot = when {
                name.contains("lm") -> ModelManager.ModelSlot.LM
                name.contains("qwen") || name.contains("embed") -> ModelManager.ModelSlot.TEXT_ENC
                name.contains("turbo") || name.contains("dit") || name.contains("sft") || name.contains("base") -> ModelManager.ModelSlot.DIT
                name.contains("vae") -> ModelManager.ModelSlot.VAE
                else -> null
            }
            if (slot != null) ModelManager.setModel(slot, file)
        }

        updateUI()
    }

    private fun updateUI() {
        slotsLayout.removeAllViews()
        for (slot in ModelManager.ModelSlot.entries) {
            val btn = Button(this).apply {
                val model = ModelManager.getModel(slot)
                text = if (model != null) "✓ ${slot.label}: ${model.name}" else "⬜ ${slot.label}: tap to load"
                isAllCaps = false
                setOnClickListener {
                    pendingSlot = slot
                    filePicker.launch("*/*")
                }
            }
            slotsLayout.addView(btn)
        }

        val ready = ModelManager.allModelsReady()
        startBtn.isEnabled = ready
        if (!serverRunning) {
            startBtn.text = if (ready) "Start Server" else "Start Server"
            statusText.text = if (ready) "All models loaded. Ready." else "Load GGUF files for each slot"
        }
    }
}
