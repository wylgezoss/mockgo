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
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat

/**
 * 前台服务：在独立线程里持续向系统上报固定的模拟位置（GPS + 网络两个来源）。
 * GPS 来源按真实 GPS 的形式声明（需要卫星、高精度）并附带卫星数；
 * 另有唤醒锁、被系统杀掉后自动恢复、定期检查来源是否仍然有效。
 */
class MockLocationService : Service() {

    companion object {
        const val ACTION_START = "start"
        const val ACTION_STOP = "stop"
        const val ACTION_STATE = "com.example.mockgo.STATE"
        const val EXTRA_LAT = "lat"
        const val EXTRA_LNG = "lng"
        const val EXTRA_ERROR = "error"
        private const val TICK_MS = 100L
        private const val GPS = LocationManager.GPS_PROVIDER
        private const val NET = LocationManager.NETWORK_PROVIDER

        @Volatile
        var running = false
            private set
    }

    private lateinit var lm: LocationManager
    private val main = Handler(Looper.getMainLooper())
    private var thread: HandlerThread? = null
    private var worker: Handler? = null
    private val lock = Any()

    @Volatile private var lat = 0.0
    @Volatile private var lng = 0.0
    private var tickCount = 0
    private var wakeLock: PowerManager.WakeLock? = null
    private val sp by lazy { getSharedPreferences("svc", Context.MODE_PRIVATE) }

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            if (!push()) return
            tickCount++
            if (tickCount % 90 == 0) reassert() // 约每 9 秒检查一次
            worker?.postDelayed(this, TICK_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        when {
            action == ACTION_STOP -> {
                stopAll()
                return START_NOT_STICKY
            }
            action == ACTION_START -> {
                lat = intent!!.getDoubleExtra(EXTRA_LAT, 0.0)
                lng = intent.getDoubleExtra(EXTRA_LNG, 0.0)
                sp.edit().putBoolean("active", true).putString("pos", "$lat,$lng").apply()
                begin()
            }
            intent == null && sp.getBoolean("active", false) -> {
                // 被系统杀掉后重启：恢复上次的模拟位置
                val p = (sp.getString("pos", "") ?: "").split(",").mapNotNull { it.toDoubleOrNull() }
                if (p.size == 2) {
                    lat = p[0]; lng = p[1]
                    begin()
                } else {
                    stopAll()
                }
            }
            else -> if (!running) stopSelf()
        }
        return if (running) START_STICKY else START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        worker?.removeCallbacksAndMessages(null)
        thread?.quitSafely()
        thread = null
        releaseWakeLock()
        cleanup()
        super.onDestroy()
    }

    private fun begin() {
        try {
            startForegroundNotification()
        } catch (_: Exception) {
            // 后台启动前台服务受限时忽略，服务仍可继续
        }
        if (!running) {
            if (!setup()) return
            running = true
            acquireWakeLock()
            val t = HandlerThread("mock-loc", android.os.Process.THREAD_PRIORITY_FOREGROUND).also { it.start() }
            thread = t
            worker = Handler(t.looper).also { it.post(tick) }
        } else {
            push()
        }
        notifyState(null)
    }

    // ---------------------------------------------------------- 提供者

    /** GPS：需要卫星、高精度；网络：需要网络和基站、粗精度。 */
    private fun addProvider(p: String) {
        synchronized(lock) {
            try { lm.removeTestProvider(p) } catch (_: Exception) {}
            if (p == GPS) {
                lm.addTestProvider(GPS, false, true, false, false, true, true, true,
                    Criteria.POWER_HIGH, Criteria.ACCURACY_FINE)
            } else {
                lm.addTestProvider(NET, true, false, true, true, true, true, true,
                    Criteria.POWER_LOW, Criteria.ACCURACY_COARSE)
            }
            lm.setTestProviderEnabled(p, true)
        }
    }

    private fun setup(): Boolean = try {
        addProvider(GPS)
        addProvider(NET)
        true
    } catch (e: SecurityException) {
        fail("未将本应用设为模拟位置信息应用")
        false
    } catch (e: Exception) {
        fail("无法创建模拟位置：${e.message}")
        false
    }

    /** 定期确认提供者仍可用；被系统移除则重建。 */
    private fun reassert() {
        for (p in arrayOf(GPS, NET)) {
            try {
                synchronized(lock) { lm.setTestProviderEnabled(p, true) }
            } catch (_: Exception) {
                if (running) try { addProvider(p) } catch (_: Exception) {}
            }
        }
    }

    private fun push(): Boolean {
        synchronized(lock) {
            if (!running) return false
            val now = System.currentTimeMillis()
            val elapsed = SystemClock.elapsedRealtimeNanos()

            val g = Location(GPS).apply {
                latitude = lat
                longitude = lng
                altitude = 50.0
                accuracy = 1f
                speed = 0f
                bearing = 0f
                time = now
                elapsedRealtimeNanos = elapsed
                extras = Bundle().apply { putInt("satellites", 7) }
            }
            val n = Location(NET).apply {
                latitude = lat
                longitude = lng
                altitude = 50.0
                accuracy = 2f
                speed = 0f
                bearing = 0f
                time = now
                elapsedRealtimeNanos = elapsed
            }
            for ((p, loc) in listOf(NET to n, GPS to g)) {
                try {
                    lm.setTestProviderLocation(p, loc)
                } catch (e: SecurityException) {
                    main.post { fail("未将本应用设为模拟位置信息应用") }
                    return false
                } catch (e: IllegalArgumentException) {
                    // 提供者被系统移除：重建
                    try { addProvider(p) } catch (_: Exception) {}
                } catch (_: Exception) {}
            }
        }
        return true
    }

    private fun cleanup() {
        synchronized(lock) {
            for (p in arrayOf(GPS, NET)) {
                try {
                    lm.setTestProviderEnabled(p, false)
                    lm.removeTestProvider(p)
                } catch (_: Exception) {}
            }
        }
    }

    // ---------------------------------------------------------- 状态

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "mockgo:location").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseWakeLock() {
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (_: Exception) {}
        wakeLock = null
    }

    private fun fail(msg: String) {
        notifyState(msg)
        stopAll()
    }

    private fun stopAll() {
        sp.edit().putBoolean("active", false).apply()
        running = false
        worker?.removeCallbacksAndMessages(null)
        thread?.quitSafely()
        thread = null
        worker = null
        releaseWakeLock()
        cleanup()
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
