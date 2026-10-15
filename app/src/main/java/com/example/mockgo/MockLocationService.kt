package com.example.mockgo

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Criteria
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat

/** 前台服务：每秒向系统上报一次固定的模拟位置。 */
class MockLocationService : Service() {

    companion object {
        const val ACTION_START = "start"
        const val ACTION_STOP = "stop"
        const val ACTION_STATE = "com.example.mockgo.STATE"
        const val EXTRA_LAT = "lat"
        const val EXTRA_LNG = "lng"
        const val EXTRA_ERROR = "error"
        private val PROVIDERS = arrayOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)

        @Volatile
        var running = false
            private set
    }

    private lateinit var lm: LocationManager
    private val handler = Handler(Looper.getMainLooper())
    private var lat = 0.0
    private var lng = 0.0

    private val tick = object : Runnable {
        override fun run() {
            if (!push()) return
            handler.postDelayed(this, 1000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val req = intent
        if (req?.action == ACTION_START) {
            lat = req.getDoubleExtra(EXTRA_LAT, 0.0)
            lng = req.getDoubleExtra(EXTRA_LNG, 0.0)
            startForegroundNotification()
            if (!running) {
                if (!setup()) return START_NOT_STICKY
                running = true
                handler.removeCallbacks(tick)
                handler.post(tick)
            } else {
                push()
            }
            notifyState(null)
        } else {
            stopAll()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        cleanup()
        running = false
        super.onDestroy()
    }

    private fun setup(): Boolean = try {
        for (p in PROVIDERS) {
            try { lm.removeTestProvider(p) } catch (_: Exception) {}
            lm.addTestProvider(p, false, false, false, false, true, true, true,
                Criteria.POWER_LOW, Criteria.ACCURACY_FINE)
            lm.setTestProviderEnabled(p, true)
        }
        true
    } catch (e: SecurityException) {
        fail("未将本应用设为模拟位置信息应用")
        false
    } catch (e: Exception) {
        fail("无法创建模拟位置：${e.message}")
        false
    }

    private fun push(): Boolean {
        for (p in PROVIDERS) {
            try {
                val loc = Location(p).apply {
                    latitude = lat
                    longitude = lng
                    altitude = 50.0
                    accuracy = 5f
                    time = System.currentTimeMillis()
                    elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
                }
                lm.setTestProviderLocation(p, loc)
            } catch (e: SecurityException) {
                fail("未将本应用设为模拟位置信息应用")
                return false
            } catch (_: Exception) {}
        }
        return true
    }

    private fun cleanup() {
        for (p in PROVIDERS) {
            try {
                lm.setTestProviderEnabled(p, false)
                lm.removeTestProvider(p)
            } catch (_: Exception) {}
        }
    }

    private fun fail(msg: String) {
        notifyState(msg)
        stopAll()
    }

    private fun stopAll() {
        handler.removeCallbacks(tick)
        cleanup()
        running = false
        notifyState(null)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun notifyState(error: String?) {
        sendBroadcast(Intent(ACTION_STATE).setPackage(packageName).putExtra(EXTRA_ERROR, error))
    }

    private fun startForegroundNotification() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(NotificationChannel("mock", "模拟定位", NotificationManager.IMPORTANCE_LOW))
        }
        val fl = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val stop = PendingIntent.getService(this, 1,
            Intent(this, MockLocationService::class.java).setAction(ACTION_STOP), fl)
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), fl)
        val n = NotificationCompat.Builder(this, "mock")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("正在模拟位置")
            .setContentText(String.format("%.6f, %.6f", lat, lng))
            .setContentIntent(open)
            .addAction(0, "停止", stop)
            .setOngoing(true)
            .build()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
        ServiceCompat.startForeground(this, 1, n, type)
    }
}
