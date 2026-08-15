package com.cfks.supersys.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import com.cfks.supersys.R
import com.cfks.supersys.model.LogEntry
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.Collections

class LogcatService : Service() {

    companion object {
        private const val TAG = "LogcatService"
        private const val CHANNEL_ID = "supersys_logcat"
        private const val NOTIFICATION_ID = 1001
        private const val MAX_BUFFER = 2000
    }

    interface LogListener {
        fun onLog(entry: LogEntry)
        fun onError(message: String)
    }

    inner class LocalBinder : Binder() {
        val service: LogcatService get() = this@LogcatService
    }

    private val binder = LocalBinder()
    private var logcatThread: Thread? = null
    private var process: Process? = null
    @Volatile private var isRunning = false
    private val listeners = mutableListOf<LogListener>()
    private val logBuffer: MutableList<LogEntry> = Collections.synchronizedList(mutableListOf())
    private val mainHandler = Handler(Looper.getMainLooper())

    fun addListener(listener: LogListener) {
        synchronized(listeners) {
            listeners.add(listener)
        }
        // Replay buffered logs to the new listener on the main thread
        val snapshot: List<LogEntry>
        synchronized(logBuffer) {
            snapshot = logBuffer.toList()
        }
        mainHandler.post {
            for (entry in snapshot) {
                listener.onLog(entry)
            }
        }
    }

    fun removeListener(listener: LogListener) {
        synchronized(listeners) { listeners.remove(listener) }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, createNotification())
        if (!isRunning) {
            startLogcat()
        }
        return START_STICKY
    }

    private fun startLogcat() {
        isRunning = true
        logcatThread = Thread {
            try {
                process = ProcessBuilder("logcat", "-v", "threadtime")
                    .redirectErrorStream(true)
                    .start()

                val reader = BufferedReader(
                    InputStreamReader(process!!.inputStream)
                )

                var line: String? = reader.readLine()
                while (isRunning && line != null) {
                    val entry = LogEntry.parse(line)

                    // Store in buffer
                    synchronized(logBuffer) {
                        logBuffer.add(entry)
                        if (logBuffer.size > MAX_BUFFER) {
                            logBuffer.removeAt(0)
                        }
                    }

                    // Notify listeners
                    synchronized(listeners) {
                        for (l in listeners) {
                            l.onLog(entry)
                        }
                    }
                    line = reader.readLine()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Logcat read error", e)
                synchronized(listeners) {
                    for (l in listeners) {
                        l.onError(getString(R.string.log_error))
                    }
                }
            } finally {
                isRunning = false
            }
        }.apply {
            name = "LogcatReader"
            isDaemon = true
            start()
        }
    }

    private fun stopLogcat() {
        isRunning = false
        try {
            process?.destroy()
            process = null
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping logcat", e)
        }
        logcatThread?.interrupt()
        logcatThread = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.log_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.log_channel_desc)
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.log_notification_title))
                .setContentText(getString(R.string.log_notification_text))
                .setSmallIcon(R.mipmap.ic_launcher)
                .setOngoing(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle(getString(R.string.log_notification_title))
                .setContentText(getString(R.string.log_notification_text))
                .setSmallIcon(R.mipmap.ic_launcher)
                .setOngoing(true)
                .build()
        }
    }

    override fun onDestroy() {
        stopLogcat()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder = binder
}
