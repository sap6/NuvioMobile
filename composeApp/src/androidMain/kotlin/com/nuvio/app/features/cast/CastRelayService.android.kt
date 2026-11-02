package com.nuvio.app.features.cast

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.nuvio.app.core.build.AppFeaturePolicy

/**
 * Keeps Nuvio running while the TV plays from the phone's relay: with the screen off Android would
 * pause the app and let the processor and Wi-Fi sleep, and the TV would stall. Shows a notification
 * with a Stop button.
 */
class CastRelayService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            UnifiedCastRepository.stop()
            stopSelf()
            return START_NOT_STICKY
        }
        val tv = intent?.getStringExtra(EXTRA_TV) ?: "TV"
        val started = runCatching {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification(tv),
                if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0,
            )
        }.onFailure { Log.w(TAG, "Couldn't keep casting running in the background", it) }.isSuccess
        if (!started) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (wakeLock == null) {
            wakeLock = getSystemService(PowerManager::class.java)
                ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "nuvio:cast-relay")?.apply { acquire() }
            @Suppress("DEPRECATION") // The replacement mode needs API 29; this one still works everywhere.
            wifiLock = (applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager)
                ?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "nuvio:cast-relay")?.apply { acquire() }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wifiLock?.takeIf { it.isHeld }?.release()
        super.onDestroy()
    }

    private fun notification(tv: String): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            manager?.createNotificationChannel(NotificationChannel(CHANNEL, "Casting", NotificationManager.IMPORTANCE_LOW))
        }
        val open = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE)
        }
        val stop = PendingIntent.getService(
            this, 1, Intent(this, CastRelayService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(com.nuvio.app.R.drawable.ic_notification_small)
            .setContentTitle("Casting to $tv")
            .setContentText("Nuvio is sending the video to your TV from this phone.")
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "CastRelayService"
        private const val CHANNEL = "nuvio_cast_relay"
        private const val NOTIFICATION_ID = 0x4E56
        private const val ACTION_STOP = "com.nuvio.app.cast.STOP_RELAY"
        private const val EXTRA_TV = "tv"

        fun start(context: Context, tv: String) {
            if (!AppFeaturePolicy.mediaPlaybackForegroundServiceEnabled) return
            runCatching {
                ContextCompat.startForegroundService(context, Intent(context, CastRelayService::class.java).putExtra(EXTRA_TV, tv))
            }.onFailure { Log.w(TAG, "Couldn't start the casting service", it) }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, CastRelayService::class.java)) }
        }
    }
}
