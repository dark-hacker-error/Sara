package com.sara.assistant.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.sara.assistant.MainActivity
import com.sara.assistant.R
import com.sara.assistant.core.SaraLog
import kotlin.math.abs

/**
 * The floating bubble that lets Sara be summoned from on top of any other app.
 *
 * Requirements: SYSTEM_ALERT_WINDOW (user-granted), and the specialUse foreground service
 * type declared in the manifest. Drag to move, tap to open Sara's voice UI.
 */
class OverlayBubbleService : Service() {

    private var windowManager: WindowManager? = null
    private var bubble: View? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForegroundCompat(buildNotification())
        if (!showBubble()) {
            SaraLog.w("Overlay permission missing; stopping bubble service")
            stopSelf()
        }
    }

    override fun onDestroy() {
        bubble?.let { view -> runCatching { windowManager?.removeView(view) } }
        bubble = null
        super.onDestroy()
    }

    // ---------------------------------------------------------------- bubble

    private fun showBubble(): Boolean {
        if (!android.provider.Settings.canDrawOverlays(this)) return false

        val manager = getSystemService(WindowManager::class.java) ?: return false
        windowManager = manager

        val sizePx = (BUBBLE_SIZE_DP * resources.displayMetrics.density).toInt()

        val container = FrameLayout(this).apply {
            val icon = ImageView(this@OverlayBubbleService).apply {
                setImageResource(R.drawable.ic_launcher_foreground)
                layoutParams = FrameLayout.LayoutParams(sizePx, sizePx, Gravity.CENTER)
            }
            addView(icon)
            setBackgroundResource(R.drawable.bg_bubble)
            alpha = 0.92f
        }

        val params = WindowManager.LayoutParams(
            sizePx,
            sizePx,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 24
            y = 320
        }

        attachDragHandler(container, params)

        return runCatching {
            manager.addView(container, params)
            bubble = container
            layoutParams = params
            true
        }.getOrElse {
            SaraLog.e("Failed to add overlay bubble", it)
            false
        }
    }

    private fun attachDragHandler(view: View, params: WindowManager.LayoutParams) {
        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f
        var dragged = false

        view.setOnTouchListener { target, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x
                    startY = params.y
                    touchX = event.rawX
                    touchY = event.rawY
                    dragged = false
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val deltaX = (event.rawX - touchX).toInt()
                    val deltaY = (event.rawY - touchY).toInt()
                    if (abs(deltaX) > TOUCH_SLOP_PX || abs(deltaY) > TOUCH_SLOP_PX) dragged = true
                    params.x = startX + deltaX
                    params.y = startY + deltaY
                    runCatching { windowManager?.updateViewLayout(target, params) }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    // A tap (not a drag) opens the assistant.
                    if (!dragged) openSara(voice = true)
                    true
                }

                else -> false
            }
        }
    }

    private fun openSara(voice: Boolean) {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(MainActivity.EXTRA_START_VOICE, voice)
        runCatching { startActivity(intent) }
            .onFailure { SaraLog.e("Could not open Sara from bubble", it) }
    }

    // ---------------------------------------------------------------- notification

    private fun startForegroundCompat(notification: Notification) {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        runCatching {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
        }.onFailure { SaraLog.e("bubble startForeground failed", it) }
    }

    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notif_bubble_title))
            .setContentText(getString(R.string.notif_bubble_text))
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(openIntent)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notif_channel_bubble),
                NotificationManager.IMPORTANCE_MIN,
            ),
        )
    }

    companion object {
        private const val CHANNEL_ID = "sara_overlay_bubble"
        private const val NOTIFICATION_ID = 4202
        private const val BUBBLE_SIZE_DP = 56
        private const val TOUCH_SLOP_PX = 12

        fun start(context: Context) {
            val intent = Intent(context, OverlayBubbleService::class.java)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }.onFailure { SaraLog.e("Could not start bubble service", it) }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, OverlayBubbleService::class.java)) }
        }
    }
}
