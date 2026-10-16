package com.example.mockgo

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.os.CancellationSignal
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.util.GeoPoint
import org.osmdroid.util.MapTileIndex
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 点地图选位置 → 开始模拟；地图上画出模拟位置与真实位置的连线；可一键回到真实位置。 */
class MainActivity : AppCompatActivity() {

    private lateinit var map: MapView
    private lateinit var tvCoord: TextView
    private lateinit var btnGo: Button
    private lateinit var etSearch: EditText
    private lateinit var lvResults: ListView
    private var selName = ""
    private var searchSeq = 0
    private var suppressWatcher = false
    private val searchRunnable = Runnable { doSearch(false) }
    private var marker: Marker? = null
    private var lat = Double.NaN
    private var lng = Double.NaN

    // 开始模拟时记录的“真实位置”和“模拟位置”（均为地图坐标 GCJ-02；真实位置另存一份 WGS-84 用于算距离）
    private var realGcj: GeoPoint? = null
    private var realWgs: DoubleArray? = null
    private var mockGcj: GeoPoint? = null
    private var line: Polyline? = null
    private var realMarker: Marker? = null
    private lateinit var tvDiag: TextView
    private var lastPushCount = 0L
    private var lastDiagTime = 0L
    private val diagTick = object : Runnable {
        override fun run() {
            updateDiag()
            map.postDelayed(this, 1000)
        }
    }

    private val sp by lazy { getSharedPreferences("state", MODE_PRIVATE) }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            refresh()
            i.getStringExtra(MockLocationService.EXTRA_ERROR)?.let { showError(it) }
            if (!MockLocationService.running) {
                realGcj = null
                realWgs = null
                mockGcj = null
            }
            updateLine()
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

        // 顶部：搜索框 + 搜索 + 历史
        etSearch = EditText(this).apply {
            hint = "搜索地名 / 地址"
            setSingleLine()
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setTextColor(Color.BLACK)
            setHintTextColor(Color.GRAY)
            setOnEditorActionListener { _, id, _ ->
                if (id == EditorInfo.IME_ACTION_SEARCH) { doSearch(true); true } else false
            }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
                override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    if (suppressWatcher) return
                    map.removeCallbacks(searchRunnable)
                    if ((s?.length ?: 0) < 2) { hideResults(); return }
                    map.postDelayed(searchRunnable, 700) // 输入停顿 0.7 秒后自动搜索
                }
            })
        }
        val btnSearch = Button(this).apply { text = "搜索"; setOnClickListener { doSearch(true) } }
        val btnHist = Button(this).apply { text = "历史"; setOnClickListener { showHistory() } }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(0xF2FFFFFF.toInt()) }
            setPadding(dp(14), dp(2), dp(6), dp(2))
            addView(etSearch, LinearLayout.LayoutParams(0, -2, 1f))
            addView(btnSearch)
            addView(btnHist)
        }
        root.addView(top, FrameLayout.LayoutParams(-1, -2, Gravity.TOP).apply {
            setMargins(dp(8), dp(36), dp(8), 0)
        })

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

        val btnBack = Button(this).apply {
            text = "回到定位"
            setOnClickListener { backToReal() }
        }
        val btnStable = Button(this).apply {
            text = "稳定设置"
            setOnClickListener { showStability() }
        }
        val side = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(btnBack)
            addView(btnStable)
            addView(Button(this@MainActivity).apply { text = "搜索设置"; setOnClickListener { showSearchCfg() } })
        }
        root.addView(side, FrameLayout.LayoutParams(-2, -2, Gravity.END or Gravity.CENTER_VERTICAL).apply {
            rightMargin = dp(8)
        })
        tvDiag = TextView(this).apply {
            textSize = 11f
            setTextColor(Color.WHITE)
            setBackgroundColor(0xAA000000.toInt())
            setPadding(dp(8), dp(4), dp(8), dp(4))
            visibility = View.GONE
        }
        root.addView(tvDiag, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START).apply {
            setMargins(dp(8), 0, 0, dp(100))
        })

        // 搜索结果下拉列表（盖在最上层）
        lvResults = ListView(this).apply {
            setBackgroundColor(Color.WHITE)
            visibility = View.GONE
        }
        root.addView(lvResults, FrameLayout.LayoutParams(-1, dp(300), Gravity.TOP).apply {
            setMargins(dp(8), dp(92), dp(8), 0)
        })
        setContentView(root)
        refresh()
    }

    override fun onStart() {
        super.onStart()
        ContextCompat.registerReceiver(this, receiver, IntentFilter(MockLocationService.ACTION_STATE),
            ContextCompat.RECEIVER_NOT_EXPORTED)
        refresh()
        if (MockLocationService.running) loadSaved()
        updateLine()
        map.post(diagTick)
    }

    override fun onStop() {
        map.removeCallbacks(diagTick)
        unregisterReceiver(receiver)
        super.onStop()
    }
    override fun onResume() { super.onResume(); map.onResume() }
    override fun onPause() { map.onPause(); super.onPause() }

    // ------------------------------------------------------------ 选点

    private fun select(la: Double, ln: Double, name: String = "") {
        lat = la; lng = ln
        selName = name
        val m = marker ?: Marker(map).also { it.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM); map.overlays.add(it); marker = it }
        m.position = GeoPoint(la, ln)
        map.invalidate()
        tvCoord.text = if (name.isEmpty()) String.format("%.6f, %.6f", la, ln)
        else String.format("%s\n%.6f, %.6f", name, la, ln)
    }

    private fun refresh() { btnGo.text = if (MockLocationService.running) "停止" else "开始" }

    private fun onGo() {
        if (MockLocationService.running) {
            stopService()
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

    private fun stopService() {
        startService(Intent(this, MockLocationService::class.java).setAction(MockLocationService.ACTION_STOP))
    }

    /** 先取一次真实位置（此时还没开始模拟），再启动模拟服务。 */
    private fun launch() {
        if (lat.isNaN()) return
        val la = lat
        val ln = lng
        val nm = selName.ifEmpty { String.format("%.5f, %.5f", la, ln) }
        btnGo.isEnabled = false
        btnGo.text = "定位中…"
        fetchReal { real ->
            btnGo.isEnabled = true
            if (real != null) {
                val g = Gcj.toGcj02(real.latitude, real.longitude)
                realGcj = GeoPoint(g.first, g.second)
                realWgs = doubleArrayOf(real.latitude, real.longitude)
            } else {
                realGcj = null
                realWgs = null
                toast("未获取到真实位置，将不显示连线")
            }
            mockGcj = GeoPoint(la, ln)
            saveState()
            History.add(this, nm, la, ln)

            val wgs = Gcj.toWgs84(la, ln) // 地图上点的是国内坐标，转成 GPS 坐标再模拟
            ContextCompat.startForegroundService(this, Intent(this, MockLocationService::class.java)
                .setAction(MockLocationService.ACTION_START)
                .putExtra(MockLocationService.EXTRA_LAT, wgs.first)
                .putExtra(MockLocationService.EXTRA_LNG, wgs.second))
            refresh()
            if (!sp.getBoolean("stability_shown", false)) {
                sp.edit().putBoolean("stability_shown", true).apply()
                showStability()
            }
        }
    }

    // ------------------------------------------------------------ 回到真实位置

    /** 只移动视角到真实位置，不影响模拟。 */
    private fun backToReal() {
        if (MockLocationService.running) {
            // 模拟期间系统定位已被替换，只能使用“开始模拟前”记录的真实位置
            loadSaved()
            val r = realGcj
            if (r == null) {
                toast("开始模拟时没有获取到真实位置，请先停止，打开系统定位后再开始")
                return
            }
            updateLine()
            focusOn(r)
            toast("这是开始模拟时记录的真实位置")
        } else {
            if (!hasLocationPermission()) { toast("需要定位权限"); return }
            toast("正在获取真实位置…")
            fetchReal { real ->
                if (real == null) {
                    toast("未获取到真实位置，请确认系统定位已开启")
                } else {
                    val g = Gcj.toGcj02(real.latitude, real.longitude)
                    realGcj = GeoPoint(g.first, g.second)
                    realWgs = doubleArrayOf(real.latitude, real.longitude)
                    updateLine()
                    focusOn(realGcj!!)
                }
            }
        }
    }

    private fun focusOn(p: GeoPoint) {
        map.controller.setZoom(17.0)
        map.controller.animateTo(p)
    }

    @SuppressLint("MissingPermission")
    private fun lastKnown(lm: LocationManager): Location? =
        listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER, LocationManager.PASSIVE_PROVIDER)
            .mapNotNull { try { lm.getLastKnownLocation(it) } catch (_: Exception) { null } }
            .filter { !it.isFromMockProvider }
            .maxByOrNull { it.time }

    /** 获取真实位置（最多等 4 秒，失败则用最近一次已知位置）。必须在模拟未运行时调用。 */
    @SuppressLint("MissingPermission")
    private fun fetchReal(cb: (Location?) -> Unit) {
        if (!hasLocationPermission()) { cb(null); return }
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        var done = false
        val signals = ArrayList<CancellationSignal>()

        fun finish(l: Location?) {
            if (done) return
            done = true
            signals.forEach { try { it.cancel() } catch (_: Exception) {} }
            val real = if (l != null && !l.isFromMockProvider) l else lastKnown(lm)
            cb(real)
        }

        val providers = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
            .filter { try { lm.isProviderEnabled(it) } catch (_: Exception) { false } }
        if (providers.isEmpty()) { finish(null); return }

        var pending = providers.size
        for (p in providers) {
            val cs = CancellationSignal()
            signals.add(cs)
            try {
                LocationManagerCompat.getCurrentLocation(lm, p, cs, ContextCompat.getMainExecutor(this)) { loc ->
                    if (loc != null) {
                        finish(loc)
                    } else {
                        pending--
                        if (pending == 0) finish(null)
                    }
                }
            } catch (_: Exception) {
                pending--
                if (pending == 0) finish(null)
            }
        }
        map.postDelayed({ finish(null) }, 4000)
    }

    // ------------------------------------------------------------ 连线

    private fun saveState() {
        val r = realGcj
        val w = realWgs
        val m = mockGcj
        sp.edit()
            .putString("real", if (r != null && w != null) "${r.latitude},${r.longitude},${w[0]},${w[1]}" else "")
            .putString("mock", if (m != null) "${m.latitude},${m.longitude}" else "")
            .apply()
    }

    private fun loadSaved() {
        if (realGcj == null) {
            val p = (sp.getString("real", "") ?: "").split(",").mapNotNull { it.toDoubleOrNull() }
            if (p.size == 4) { realGcj = GeoPoint(p[0], p[1]); realWgs = doubleArrayOf(p[2], p[3]) }
        }
        if (mockGcj == null) {
            val p = (sp.getString("mock", "") ?: "").split(",").mapNotNull { it.toDoubleOrNull() }
            if (p.size == 2) mockGcj = GeoPoint(p[0], p[1])
        }
    }

    /** 真实位置：始终显示蓝色图标；模拟运行中再画与模拟位置的虚线并显示距离。 */
    private fun updateLine() {
        line?.let { map.overlays.remove(it) }
        realMarker?.let { map.overlays.remove(it) }
        line = null
        realMarker = null

        val r = realGcj
        val m = mockGcj
        if (r == null) {
            map.invalidate()
            return
        }

        if (MockLocationService.running && m != null) {
            line = Polyline(map).apply {
                setPoints(listOf(r, m))
                outlinePaint.color = 0xFFE53935.toInt()
                outlinePaint.strokeWidth = 6f
                outlinePaint.pathEffect = DashPathEffect(floatArrayOf(24f, 14f), 0f)
            }
            map.overlays.add(line)
        }
        realMarker = Marker(map).apply {
            position = r
            icon = realIcon()
            setAnchor(0.5f, 0.3f)
        }
        map.overlays.add(realMarker)
        map.invalidate()

        val w = realWgs
        if (MockLocationService.running && m != null && w != null) {
            val mw = Gcj.toWgs84(m.latitude, m.longitude)
            val res = FloatArray(1)
            Location.distanceBetween(w[0], w[1], mw.first, mw.second, res)
            val d = res[0]
            val dist = if (d < 1000) String.format("%.0f 米", d) else String.format("%.2f 公里", d / 1000)
            tvCoord.text = String.format("模拟：%.6f, %.6f\n与真实位置相距 %s", m.latitude, m.longitude, dist)
        }
    }

    /** 蓝色圆点 + “真实位置”文字标签。锚点在圆点中心（y=0.3）。 */
    private fun realIcon(): Drawable {
        val w = dp(84)
        val h = dp(60)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val cx = w / 2f
        val cy = dp(18).toFloat()
        p.color = 0x552196F3
        c.drawCircle(cx, cy, dp(17).toFloat(), p)
        p.color = Color.WHITE
        c.drawCircle(cx, cy, dp(10).toFloat(), p)
        p.color = 0xFF1E88E5.toInt()
        c.drawCircle(cx, cy, dp(7).toFloat(), p)
        val rect = RectF(dp(6).toFloat(), dp(38).toFloat(), (w - dp(6)).toFloat(), (h - dp(4)).toFloat())
        c.drawRoundRect(rect, dp(10).toFloat(), dp(10).toFloat(), p)
        p.color = Color.WHITE
        p.textSize = dp(13).toFloat()
        p.textAlign = Paint.Align.CENTER
        p.isFakeBoldText = true
        val fm = p.fontMetrics
        c.drawText("真实位置", cx, rect.centerY() - (fm.ascent + fm.descent) / 2, p)
        return BitmapDrawable(resources, bmp)
    }

    // ------------------------------------------------------------ 诊断

    /** 模拟运行时，在左上角显示：推送速率、重建/拉回次数，以及系统此刻认为各来源的位置是模拟还是真实。 */
    @SuppressLint("MissingPermission")
    private fun updateDiag() {
        if (!MockLocationService.running || !hasLocationPermission()) {
            tvDiag.visibility = View.GONE
            lastDiagTime = 0L
            return
        }
        tvDiag.visibility = View.VISIBLE
        val now = SystemClock.elapsedRealtime()
        val pc = MockLocationService.pushCount
        val rate = if (lastDiagTime > 0 && now > lastDiagTime) (pc - lastPushCount) * 1000 / (now - lastDiagTime) else 0
        lastPushCount = pc
        lastDiagTime = now

        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        fun tag(name: String, provider: String): String {
            val l = try { lm.getLastKnownLocation(provider) } catch (_: Exception) { null } ?: return "$name:无"
            @Suppress("DEPRECATION")
            val mock = if (Build.VERSION.SDK_INT >= 31) l.isMock else l.isFromMockProvider
            val res = FloatArray(1)
            Location.distanceBetween(l.latitude, l.longitude, MockLocationService.curLat, MockLocationService.curLng, res)
            val age = (SystemClock.elapsedRealtimeNanos() - l.elapsedRealtimeNanos) / 1_000_000_000L
            return "$name:${if (mock) "模拟" else "真实"} ${res[0].toInt()}m ${age}s"
        }
        val sb = StringBuilder()
        sb.append("推送 ${rate}/秒  重建 ${MockLocationService.rebuildCount}  拉回 ${MockLocationService.leakCount}")
        if (MockLocationService.lastLeak.isNotEmpty()) sb.append("（${MockLocationService.lastLeak}）")
        sb.append("\n").append(tag("GPS", LocationManager.GPS_PROVIDER))
        sb.append("  ").append(tag("网络", LocationManager.NETWORK_PROVIDER))
        if (Build.VERSION.SDK_INT >= 31) sb.append("  ").append(tag("融合", "fused"))
        tvDiag.text = sb.toString()
    }

    // ------------------------------------------------------------ 搜索 / 历史

    private fun hideKeyboard() {
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(etSearch.windowToken, 0)
    }

    private fun hideResults() { lvResults.visibility = View.GONE }

    /** explicit=true：点了“搜索”或键盘搜索键；false：输入停顿后的自动搜索。 */
    private fun doSearch(explicit: Boolean) {
        val q = etSearch.text.toString().trim()
        if (q.isEmpty()) { hideResults(); return }
        if (!explicit && q.length < 2) return
        val seq = ++searchSeq
        val key = (sp.getString("amap_key", "") ?: "").trim()
        if (explicit) { hideKeyboard(); toast("搜索中…") }
        Thread {
            val r = Geo.search(q, key)
            runOnUiThread {
                if (seq != searchSeq) return@runOnUiThread // 已有更新的搜索，丢弃旧结果
                if (r.places.isEmpty()) {
                    hideResults()
                    if (explicit) toast(r.error ?: "没有找到相关地点")
                } else {
                    showResults(r.places)
                }
            }
        }.start()
    }

    private fun showResults(list: List<Geo.Place>) {
        val adapter = object : ArrayAdapter<Geo.Place>(this, android.R.layout.simple_list_item_2, android.R.id.text1, list) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = super.getView(position, convertView, parent)
                val p = getItem(position)!!
                v.findViewById<TextView>(android.R.id.text1).apply { text = p.name; setTextColor(Color.BLACK) }
                v.findViewById<TextView>(android.R.id.text2).apply { text = p.addr; setTextColor(Color.DKGRAY); maxLines = 2 }
                return v
            }
        }
        lvResults.adapter = adapter
        val lp = lvResults.layoutParams
        lp.height = minOf(list.size * dp(64), dp(320))
        lvResults.layoutParams = lp
        lvResults.visibility = View.VISIBLE
        lvResults.setOnItemClickListener { _, _, pos, _ ->
            val p = adapter.getItem(pos) ?: return@setOnItemClickListener
            hideResults()
            hideKeyboard()
            suppressWatcher = true
            etSearch.setText(p.name)
            suppressWatcher = false
            select(p.lat, p.lng, p.name)
            map.controller.setZoom(16.0)
            map.controller.animateTo(GeoPoint(p.lat, p.lng))
        }
    }

    private fun showSearchCfg() {
        val et = EditText(this).apply {
            hint = "高德 Web 服务 Key（可留空）"
            setSingleLine()
            setText(sp.getString("amap_key", "") ?: "")
            setPadding(dp(24), dp(16), dp(24), dp(8))
        }
        AlertDialog.Builder(this).setTitle("搜索设置")
            .setMessage(
                "不填也能搜索（使用 OpenStreetMap，国内的小地名可能找不全，速度也偏慢）。\n\n" +
                "想要又快又准：到 lbs.amap.com 注册并创建应用，添加“Web 服务”类型的 Key，把 Key 粘贴到下面。"
            )
            .setView(et)
            .setPositiveButton("保存") { _, _ ->
                sp.edit().putString("amap_key", et.text.toString().trim()).apply()
                toast("已保存")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showHistory() {
        val items = History.load(this)
        if (items.isEmpty()) { toast("暂无历史记录"); return }
        val fmt = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
        val lv = ListView(this)
        val adapter = object : ArrayAdapter<History.Item>(this, android.R.layout.simple_list_item_2, android.R.id.text1, items) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = super.getView(position, convertView, parent)
                val h = getItem(position)!!
                v.findViewById<TextView>(android.R.id.text1).text = h.name
                v.findViewById<TextView>(android.R.id.text2).text =
                    String.format("%.5f, %.5f  ·  %s", h.lat, h.lng, fmt.format(Date(h.time)))
                return v
            }
        }
        lv.adapter = adapter
        val dlg = AlertDialog.Builder(this).setTitle("历史位置（点选使用，长按删除）")
            .setView(lv)
            .setNeutralButton("清空") { _, _ -> History.clear(this); toast("已清空") }
            .setNegativeButton("关闭", null)
            .create()
        lv.setOnItemClickListener { _, _, pos, _ ->
            val h = adapter.getItem(pos) ?: return@setOnItemClickListener
            dlg.dismiss()
            suppressWatcher = true
            etSearch.setText("")
            suppressWatcher = false
            hideResults()
            select(h.lat, h.lng, h.name)
            map.controller.setZoom(16.0)
            map.controller.animateTo(GeoPoint(h.lat, h.lng))
        }
        lv.setOnItemLongClickListener { _, _, pos, _ ->
            val h = adapter.getItem(pos)
            if (h != null) {
                History.remove(this, h)
                adapter.remove(h)
                if (adapter.isEmpty) dlg.dismiss()
            }
            true
        }
        dlg.show()
    }

    // ------------------------------------------------------------ 其他

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

    private fun showStability() {
        AlertDialog.Builder(this).setTitle("提高稳定性")
            .setMessage(
                "模拟位置失效或被拉回真实位置，常见原因：\n\n" +
                "1. 手机把本应用在后台杀掉 → 点“电池设置”，选“不限制 / 允许后台运行”，并在多任务界面给本应用加锁。\n\n" +
                "2. 其他软件用 WLAN / 基站辅助定位 → 点“位置设置”，关闭“WLAN 扫描”“蓝牙扫描”，定位模式选“仅 GPS / 设备”。\n\n" +
                "3. 其他软件缓存了旧位置 → 在那个软件里“强行停止”后重新打开。\n\n" +
                "说明：自带定位 SDK、或会检测模拟位置的软件，仍可能显示真实位置，这是本应用无法解决的。"
            )
            .setPositiveButton("电池设置") { _, _ ->
                try {
                    startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
                } catch (_: Exception) {
                    try { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
                    catch (_: Exception) { toast("请在系统设置里手动设置") }
                }
            }
            .setNeutralButton("位置设置") { _, _ ->
                try { startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }
                catch (_: Exception) { toast("请在系统设置里手动设置") }
            }
            .setNegativeButton("关闭", null)
            .show()
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
