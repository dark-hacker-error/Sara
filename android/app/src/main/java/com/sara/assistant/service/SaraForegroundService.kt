package com.sara.assistant.service

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
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.sara.assistant.MainActivity
import com.sara.assistant.R
import com.sara.assistant.SaraApp
import com.sara.assistant.core.SaraLog
import com.sara.assistant.live.LiveState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Keeps the process alive (and the mic usable) while Sara has an open voice session.
 *
 * foregroundServiceType="microphone" is declared in the manifest; on API 34+ that type also
 * requires the FOREGROUND_SERVICE_MICROPHONE permission, which is also declared.
 */
class SaraForegroundService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observeJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_SESSION -> {
                container().live.stop()
                stopSelf()
                return START_NOT_STICKY
            }

            else -> {
                startForegroundCompat(buildNotification("Sara is listening", "Tap to open Sara"))
                observeState()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        observeJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    private fun observeState() {
        if (observeJob?.isActive == true) return
        observeJob = scope.launch {
            container().live.state.collectLatest { state ->
                val text = when (state.liveState) {
                    LiveState.CONNECTING -> "Connecting…"
                    LiveState.LISTENING -> "Listening"
                    LiveState.THINKING -> "Thinking"
                    LiveState.SPEAKING -> "Speaking"
                    LiveState.ACTING -> state.activeTaskGoal?.let { "Working: $it" } ?: "Working on your phone"
                    LiveState.ERROR -> state.lastError ?: "Something went wrong"
                    LiveState.IDLE -> "Idle"
                }
                notify(buildNotification("Sara", text))
            }
        }
    }

    private fun startForegroundCompat(notification: Notification) {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        } else {
            0
        }
        runCatching {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
        }.onFailure { SaraLog.e("startForeground failed", it) }
    }

    private fun notify(notification: Notification) {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        runCatching { manager.notify(NOTIFICATION_ID, notification) }
    }

    private fun buildNotification(title: String, text: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, SaraForegroundService::class.java).setAction(ACTION_STOP_SESSION),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(openIntent)
            .addAction(0, getString(R.string.action_stop), stopIntent)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notif_channel_voice),
            NotificationManager.IMPORTANCE_LOW,
        ).apply { setShowBadge(false) }
        manager.createNotificationChannel(channel)
    }

    private fun container() = (application as SaraApp).container

    companion object {
        private const val CHANNEL_ID = "sara_voice_session"
        private const val NOTIFICATION_ID = 4201
        const val ACTION_STOP_SESSION = "com.sara.assistant.action.STOP_SESSION"

        fun start(context: Context) {
            val intent = Intent(context, SaraForegroundService::class.java)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }.onFailure { SaraLog.e("Could not start foreground service", it) }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, SaraForegroundService::class.java)) }
        }
    }
}
