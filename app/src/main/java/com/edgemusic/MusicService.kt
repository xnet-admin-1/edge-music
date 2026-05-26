package com.edgemusic

import android.app.*
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat

/**
 * Foreground service that runs ace-server binary on localhost.
 * Other apps connect via HTTP to generate music.
 */
class MusicService : Service() {

    companion object {
        const val CHANNEL_ID = "edge_music_channel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_START = "com.edgemusic.START"
        const val ACTION_STOP = "com.edgemusic.STOP"
        const val DEFAULT_PORT = 8085
    }

    private var serverProcess: Process? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        acquireWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopServer(); stopSelf() }
            else -> { startForeground(NOTIFICATION_ID, buildNotification("Starting...")); startServer() }
        }
        return START_STICKY
    }

    private fun startServer() {
        val modelsDir = ModelManager.getModelsDir(this)
        if (!ModelManager.allModelsReady()) {
            updateNotification("Models not loaded")
            return
        }

        val binary = applicationInfo.nativeLibraryDir + "/libace-server.so"
        val pb = ProcessBuilder(
            binary,
            "--models", modelsDir.absolutePath,
            "--host", "127.0.0.1",
            "--port", DEFAULT_PORT.toString(),
            "--no-fa"
        )
        pb.redirectErrorStream(true)
        pb.environment()["LD_LIBRARY_PATH"] = applicationInfo.nativeLibraryDir

        try {
            serverProcess = pb.start()
            updateNotification("Running on port $DEFAULT_PORT")
            // Log output in background
            Thread {
                serverProcess?.inputStream?.bufferedReader()?.forEachLine {
                    android.util.Log.i("AceServer", it)
                }
            }.start()
        } catch (e: Exception) {
            updateNotification("Failed: ${e.message}")
        }
    }

    private fun stopServer() {
        serverProcess?.destroy()
        serverProcess = null
    }

    override fun onDestroy() {
        stopServer()
        wakeLock?.release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun acquireWakeLock() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "edgemusic::server")
        wakeLock?.acquire(24 * 60 * 60 * 1000L)
    }

    private fun buildNotification(text: String): Notification {
        val stopIntent = PendingIntent.getService(
            this, 0, Intent(this, MusicService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Edge Music")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .addAction(android.R.drawable.ic_media_pause, "Stop", stopIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "Music Engine", NotificationManager.IMPORTANCE_LOW)
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
    }
}
