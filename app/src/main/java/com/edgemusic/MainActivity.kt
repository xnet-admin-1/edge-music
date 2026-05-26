package com.edgemusic

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var startBtn: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.status_text)
        progressBar = findViewById(R.id.progress_bar)
        startBtn = findViewById(R.id.start_btn)

        startBtn.setOnClickListener {
            if (ModelManager.allModelsReady(this)) {
                startService(Intent(this, MusicService::class.java).apply { action = MusicService.ACTION_START })
                statusText.text = "Server running on port ${MusicService.DEFAULT_PORT}"
            } else {
                downloadModels()
            }
        }

        updateStatus()
    }

    private fun updateStatus() {
        val missing = ModelManager.getMissingModels(this)
        if (missing.isEmpty()) {
            statusText.text = "Models ready. Tap Start."
            startBtn.text = "Start Server"
        } else {
            val totalMB = missing.sumOf { it.sizeMB }
            statusText.text = "${missing.size} models needed (~${totalMB}MB)"
            startBtn.text = "Download Models"
        }
    }

    private fun downloadModels() {
        val missing = ModelManager.getMissingModels(this)
        startBtn.isEnabled = false
        progressBar.max = missing.size * 100

        lifecycleScope.launch {
            var completed = 0
            for (model in missing) {
                statusText.text = "Downloading ${model.name}..."
                ModelManager.downloadModel(this@MainActivity, model) { progress ->
                    progressBar.progress = (completed * 100 + (progress * 100).toInt())
                }
                completed++
            }
            progressBar.progress = progressBar.max
            statusText.text = "All models ready!"
            startBtn.isEnabled = true
            updateStatus()
        }
    }
}
