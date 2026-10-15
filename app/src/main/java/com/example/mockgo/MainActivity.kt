package com.example.mockgo

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.util.MapTileIndex
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import java.io.File

/** 点地图选位置（或输入坐标），然后开始 / 停止模拟。 */
class MainActivity : AppCompatActivity() {

    private lateinit var map: MapView
    private lateinit var tvCoord: TextView
    private lateinit var btnGo: Button
    private var marker: Marker? = null
    private var lat = Double.NaN
    private var lng = Double.NaN

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            refresh()
            i.getStringExtra(MockLocationService.EXTRA_ERROR)?.let { showError(it) }
        }
    }

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            if (hasLocationPermission()) launch() else toast("需要定位权限")
        }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().apply {
            userAgentValue = packageName
            osmdroidBasePath = File(cacheDir, "osmdroid")
            osmdroidTileCache = File(cacheDir, "osmdroid/tiles")
        }

        val root = FrameLayout(this)
        map = MapView(this).apply {
            setTileSource(AMAP)
            setMultiTouchControls(true)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.SHOW_AND_FADEOUT)
            controller.setZoom(5.0)
            controller.setCenter(GeoPoint(35.0, 105.0))
            overlays.add(MapEventsOverlay(object : MapEventsReceiver {
                override fun singleTapConfirmedHelper(p: GeoPoint): Boolean { select(p.latitude, p.longitude); return true }
                override fun longPressHelper(p: GeoPoint): Boolean = false
            }))
        }
        root.addView(map, FrameLayout.LayoutParams(-1, -1))

        tvCoord = TextView(this).apply { text = "点击地图选择位置"; setTextColor(0xFF000000.toInt()) }
        val btnInput = Button(this).apply { text = "输入坐标"; setOnClickListener { inputDialog() } }
        btnGo = Button(this).apply { setOnClickListener { onGo() } }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0xEEFFFFFF.toInt())
            setPadding(dp(14), dp(8), dp(10), dp(8))
            addView(tvCoord, LinearLayout.LayoutParams(0, -2, 1f))
            addView(btnInput)
            addView(btnGo)
        }
        root.addView(bar, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        setContentView(root)
        refresh()
    }

    override fun onStart() {
        super.onStart()
        ContextCompat.registerReceiver(this, receiver, IntentFilter(MockLocationService.ACTION_STATE),
            ContextCompat.RECEIVER_NOT_EXPORTED)
        refresh()
    }

    override fun onStop() { unregisterReceiver(receiver); super.onStop() }
    override fun onResume() { super.onResume(); map.onResume() }
    override fun onPause() { map.onPause(); super.onPause() }

    private fun select(la: Double, ln: Double) {
        lat = la; lng = ln
        val m = marker ?: Marker(map).also { it.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM); map.overlays.add(it); marker = it }
        m.position = GeoPoint(la, ln)
        map.invalidate()
        tvCoord.text = String.format("%.6f, %.6f", la, ln)
    }

    private fun refresh() { btnGo.text = if (MockLocationService.running) "停止" else "开始" }

    private fun onGo() {
        if (MockLocationService.running) {
            startService(Intent(this, MockLocationService::class.java).setAction(MockLocationService.ACTION_STOP))
            return
        }
        if (lat.isNaN()) { toast("请先选择位置"); return }
        if (!hasLocationPermission()) {
            val need = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
            if (Build.VERSION.SDK_INT >= 33) need.add(Manifest.permission.POST_NOTIFICATIONS)
            permLauncher.launch(need.toTypedArray())
            return
        }
        launch()
    }

    private fun launch() {
        if (lat.isNaN()) return
        val wgs = Gcj.toWgs84(lat, lng) // 地图上点的是国内坐标，转成 GPS 坐标再模拟
        ContextCompat.startForegroundService(this, Intent(this, MockLocationService::class.java)
            .setAction(MockLocationService.ACTION_START)
            .putExtra(MockLocationService.EXTRA_LAT, wgs.first)
            .putExtra(MockLocationService.EXTRA_LNG, wgs.second))
        refresh()
    }

    private fun hasLocationPermission() = ContextCompat.checkSelfPermission(
        this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun inputDialog() {
        val et = EditText(this).apply { hint = "纬度,经度  如 39.9042,116.4074"; setPadding(dp(24), dp(16), dp(24), dp(8)) }
        AlertDialog.Builder(this).setTitle("输入坐标（高德/国内地图坐标）").setView(et)
            .setPositiveButton("确定") { _, _ ->
                val p = et.text.toString().replace("，", ",").split(",").map { it.trim() }
                val la = p.getOrNull(0)?.toDoubleOrNull()
                val ln = p.getOrNull(1)?.toDoubleOrNull()
                if (p.size != 2 || la == null || ln == null || la !in -90.0..90.0 || ln !in -180.0..180.0) {
                    toast("输入有误")
                } else {
                    select(la, ln)
                    map.controller.setZoom(16.0)
                    map.controller.animateTo(GeoPoint(la, ln))
                }
            }.setNegativeButton("取消", null).show()
    }

    private fun showError(msg: String) {
        AlertDialog.Builder(this).setTitle("无法模拟位置")
            .setMessage("$msg。\n\n请在“开发者选项 → 选择模拟位置信息应用”中选择本应用。")
            .setPositiveButton("打开开发者选项") { _, _ ->
                try { startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) }
                catch (_: Exception) { toast("请先开启开发者模式") }
            }.setNegativeButton("取消", null).show()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    companion object {
        /** 高德地图瓦片（GCJ-02 坐标系）。 */
        private val AMAP = object : OnlineTileSourceBase(
            "AMap", 3, 18, 256, ".png",
            arrayOf(
                "https://webst01.is.autonavi.com/appmaptile?lang=zh_cn&size=1&scale=1&style=7",
                "https://webst02.is.autonavi.com/appmaptile?lang=zh_cn&size=1&scale=1&style=7",
                "https://webst03.is.autonavi.com/appmaptile?lang=zh_cn&size=1&scale=1&style=7",
                "https://webst04.is.autonavi.com/appmaptile?lang=zh_cn&size=1&scale=1&style=7"
            )
        ) {
            override fun getTileURLString(pMapTileIndex: Long): String =
                baseUrl + "&x=" + MapTileIndex.getX(pMapTileIndex) +
                        "&y=" + MapTileIndex.getY(pMapTileIndex) +
                        "&z=" + MapTileIndex.getZoom(pMapTileIndex)
        }
    }
}
