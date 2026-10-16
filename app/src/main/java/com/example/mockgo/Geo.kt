package com.example.mockgo

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * 地名搜索。返回的坐标统一为“地图坐标（GCJ-02）”，可直接在高德底图上定位。
 * 顺序：高德（需自己填 Key，最准）→ OpenStreetMap Nominatim → Photon。
 */
object Geo {
    /** 高德 Web 服务 Key（用于地名搜索和反查中文地址）。 */
    const val WEB_KEY = "d76ad72d46c19d95d72d6e5497161961"

    data class Place(val name: String, val addr: String, val lat: Double, val lng: Double)
    data class Result(val places: List<Place>, val error: String?)

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 5000
        c.readTimeout = 6000
        c.setRequestProperty("User-Agent", "MockGoLite/1.0 (Android)")
        c.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9")
        try {
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    fun search(q: String, amapKey: String): Result {
        var err: String? = null
        if (amapKey.isNotEmpty()) {
            try {
                val r = amap(q, amapKey)
                if (r.isNotEmpty()) return Result(r, null)
            } catch (e: Exception) {
                err = "高德搜索失败：${e.message}"
            }
        }
        try {
            val r = nominatim(q)
            if (r.isNotEmpty()) return Result(r, null)
        } catch (e: Exception) {
            if (err == null) err = "网络搜索失败，请检查网络"
        }
        try {
            val r = photon(q)
            if (r.isNotEmpty()) return Result(r, null)
        } catch (e: Exception) {
            if (err == null) err = "网络搜索失败，请检查网络"
        }
        return Result(emptyList(), err)
    }

    /** 高德 Web 服务 · 逆地理编码：坐标（GCJ-02）→ 中文地址。失败返回 null。 */
    fun reverse(lat: Double, lng: Double, key: String): String? {
        return try {
            val j = JSONObject(get("https://restapi.amap.com/v3/geocode/regeo?key=${enc(key)}&location=$lng,$lat&extensions=base"))
            if (j.optString("status") != "1") return null
            val a = j.optJSONObject("regeocode")?.opt("formatted_address") as? String
            a?.takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
    }

    /** 高德 Web 服务 · 关键字搜索（返回 GCJ-02）。 */
    private fun amap(q: String, key: String): List<Place> {
        val j = JSONObject(get("https://restapi.amap.com/v3/place/text?key=${enc(key)}&keywords=${enc(q)}&offset=10&page=1&extensions=base"))
        if (j.optString("status") != "1") throw Exception(j.optString("info", "未知错误"))
        val arr = j.optJSONArray("pois") ?: return emptyList()
        val out = ArrayList<Place>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val loc = o.optString("location").split(",")
            if (loc.size != 2) continue
            val lng = loc[0].toDoubleOrNull() ?: continue
            val lat = loc[1].toDoubleOrNull() ?: continue
            val address = o.opt("address") as? String ?: ""
            val area = listOf(o.optString("pname"), o.optString("cityname"), o.optString("adname"))
                .filter { it.isNotEmpty() && it != "[]" }
                .distinct()
                .joinToString("")
            out.add(Place(o.optString("name"), area + address, lat, lng))
        }
        return out
    }

    /** OpenStreetMap Nominatim（WGS-84，转成 GCJ-02）。 */
    private fun nominatim(q: String): List<Place> {
        val arr = JSONArray(get("https://nominatim.openstreetmap.org/search?format=jsonv2&limit=8&accept-language=zh-CN&q=${enc(q)}"))
        val out = ArrayList<Place>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val lat = o.getString("lat").toDouble()
            val lon = o.getString("lon").toDouble()
            val disp = o.optString("display_name")
            val name = o.optString("name").ifEmpty { disp.split(",").first().trim() }
            val g = Gcj.toGcj02(lat, lon)
            out.add(Place(name, disp, g.first, g.second))
        }
        return out
    }

    /** Photon（基于 OSM，WGS-84，转成 GCJ-02）。 */
    private fun photon(q: String): List<Place> {
        val j = JSONObject(get("https://photon.komoot.io/api/?limit=8&q=${enc(q)}"))
        val arr = j.optJSONArray("features") ?: return emptyList()
        val out = ArrayList<Place>()
        for (i in 0 until arr.length()) {
            val f = arr.getJSONObject(i)
            val c = f.getJSONObject("geometry").getJSONArray("coordinates")
            val lon = c.getDouble(0)
            val lat = c.getDouble(1)
            val p = f.optJSONObject("properties") ?: JSONObject()
            val name = p.optString("name").ifEmpty { p.optString("street") }
            val addr = listOf("state", "city", "district", "street", "housenumber")
                .map { p.optString(it) }
                .filter { it.isNotEmpty() }
                .joinToString(" ")
            val g = Gcj.toGcj02(lat, lon)
            out.add(Place(name, addr, g.first, g.second))
        }
        return out
    }
}
