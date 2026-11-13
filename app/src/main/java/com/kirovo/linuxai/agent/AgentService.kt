package com.kirovo.linuxai.agent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.kirovo.linuxai.MainActivity
import com.kirovo.linuxai.data.TaskStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class AgentService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var taskJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private lateinit var engine: AgentEngine

    override fun onCreate() {
        super.onCreate()
        TaskStore.init(applicationContext)
        engine = AgentEngine(applicationContext) { status ->
            TaskStore.setStatus(status)
            updateNotification(status)
        }
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopEverything()
            return START_NOT_STICKY
        }
        promoteToForeground("Sandbox active · waiting for task")
        val task = intent?.getStringExtra(EXTRA_TASK)?.trim().orEmpty()
        if (task.isNotBlank()) {
            taskJob?.cancel()
            acquireWakeLock()
            TaskStore.setWorking(true, "Starting Linux sandbox")
            taskJob = scope.launch {
                try {
                    engine.runTask(task)
                } catch (_: CancellationException) {
                } finally {
                    TaskStore.setWorking(false, "Sandbox ready · background service active")
                    releaseWakeLock()
                    updateNotification("Sandbox ready · waiting for task")
                }
            }
        }
        return START_STICKY
    }

    private fun promoteToForeground(text: String) {
        val notification = makeNotification(text)
        if (Build.VERSION.SDK_INT >= 34) {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else startForeground(NOTIFICATION_ID, notification)
    }

    private fun createChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Linux AI background", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Keeps Linux AI tasks running while the chat is closed"
            setShowBadge(false)
        })
    }

    private fun makeNotification(text: String): Notification {
        val open = PendingIntent.getActivity(this, 10, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 11, Intent(this, AgentService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Linux AI · Sandbox")
            .setContentText(text.take(110))
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stop)
            .build()
    }

    private fun updateNotification(text: String) {
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIFICATION_ID, makeNotification(text))
    }

    private fun acquireWakeLock() {
        try {
            val manager = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (wakeLock == null) wakeLock = manager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LinuxAI:AgentTask")
            if (wakeLock?.isHeld != true) wakeLock?.acquire(12L * 60L * 60L * 1000L)
        } catch (_: Exception) { }
    }

    private fun releaseWakeLock() {
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (_: Exception) { }
    }

    private fun stopEverything() {
        taskJob?.cancel()
        engine.cancel()
        releaseWakeLock()
        TaskStore.setWorking(false, "Stopped")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        taskJob?.cancel()
        engine.cancel()
        releaseWakeLock()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_RUN = "com.kirovo.linuxai.RUN_TASK"
        const val ACTION_STOP = "com.kirovo.linuxai.STOP_SANDBOX"
        const val EXTRA_TASK = "task"
        private const val CHANNEL_ID = "linux_ai_background"
        private const val NOTIFICATION_ID = 8137

        fun start(context: Context, task: String? = null) {
            val intent = Intent(context, AgentService::class.java).setAction(ACTION_RUN)
            if (!task.isNullOrBlank()) intent.putExtra(EXTRA_TASK, task)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, AgentService::class.java).setAction(ACTION_STOP))
        }
    }
}
