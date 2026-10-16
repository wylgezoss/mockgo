package com.example.mockgo

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** 历史位置（坐标为地图坐标 GCJ-02），保存在本机。 */
object History {
    data class Item(val name: String, val lat: Double, val lng: Double, val time: Long)

    private const val KEY = "list"
    private const val MAX = 100

    private fun sp(ctx: Context) = ctx.getSharedPreferences("hist", Context.MODE_PRIVATE)

    fun load(ctx: Context): MutableList<Item> {
        val out = mutableListOf<Item>()
        try {
            val arr = JSONArray(sp(ctx).getString(KEY, "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(Item(o.optString("name"), o.getDouble("lat"), o.getDouble("lng"), o.getLong("time")))
            }
        } catch (_: Exception) {
        }
        return out
    }

    private fun save(ctx: Context, list: List<Item>) {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("name", it.name).put("lat", it.lat).put("lng", it.lng).put("time", it.time)) }
        sp(ctx).edit().putString(KEY, arr.toString()).apply()
    }

    /** 相同位置只保留最新一条，最新的排在最前。 */
    fun add(ctx: Context, name: String, lat: Double, lng: Double) {
        val list = load(ctx)
        list.removeAll { Math.abs(it.lat - lat) < 1e-5 && Math.abs(it.lng - lng) < 1e-5 }
        list.add(0, Item(name, lat, lng, System.currentTimeMillis()))
        save(ctx, list.take(MAX))
    }

    fun remove(ctx: Context, item: Item) {
        val list = load(ctx)
        list.removeAll { it.time == item.time && it.lat == item.lat && it.lng == item.lng }
        save(ctx, list)
    }

    fun clear(ctx: Context) = save(ctx, emptyList())
}
