package com.example.mockgo

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** 国内地图（高德，GCJ-02）坐标 与 系统 GPS（WGS-84）坐标 互转。 */
object Gcj {
    private const val A = 6378245.0
    private const val EE = 0.00669342162296594323

    private fun outOfChina(lat: Double, lng: Double) =
        lng < 72.004 || lng > 137.8347 || lat < 0.8293 || lat > 55.8271

    private fun tLat(x: Double, y: Double): Double {
        var r = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * sqrt(abs(x))
        r += (20.0 * sin(6.0 * x * PI) + 20.0 * sin(2.0 * x * PI)) * 2.0 / 3.0
        r += (20.0 * sin(y * PI) + 40.0 * sin(y / 3.0 * PI)) * 2.0 / 3.0
        r += (160.0 * sin(y / 12.0 * PI) + 320.0 * sin(y * PI / 30.0)) * 2.0 / 3.0
        return r
    }

    private fun tLng(x: Double, y: Double): Double {
        var r = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * sqrt(abs(x))
        r += (20.0 * sin(6.0 * x * PI) + 20.0 * sin(2.0 * x * PI)) * 2.0 / 3.0
        r += (20.0 * sin(x * PI) + 40.0 * sin(x / 3.0 * PI)) * 2.0 / 3.0
        r += (150.0 * sin(x / 12.0 * PI) + 300.0 * sin(x / 30.0 * PI)) * 2.0 / 3.0
        return r
    }

    /** 该点处 GCJ-02 相对 WGS-84 的偏移（纬度, 经度）。 */
    private fun delta(lat: Double, lng: Double): Pair<Double, Double> {
        var dLat = tLat(lng - 105.0, lat - 35.0)
        var dLng = tLng(lng - 105.0, lat - 35.0)
        val radLat = lat / 180.0 * PI
        var magic = sin(radLat)
        magic = 1 - EE * magic * magic
        val sq = sqrt(magic)
        dLat = dLat * 180.0 / (A * (1 - EE) / (magic * sq) * PI)
        dLng = dLng * 180.0 / (A / sq * cos(radLat) * PI)
        return Pair(dLat, dLng)
    }

    /** 地图坐标(GCJ-02) → 系统 GPS 坐标(WGS-84)，返回 (纬度, 经度)。 */
    fun toWgs84(lat: Double, lng: Double): Pair<Double, Double> {
        if (outOfChina(lat, lng)) return Pair(lat, lng)
        val d = delta(lat, lng)
        return Pair(lat - d.first, lng - d.second)
    }

    /** 系统 GPS 坐标(WGS-84) → 地图坐标(GCJ-02)，返回 (纬度, 经度)。 */
    fun toGcj02(lat: Double, lng: Double): Pair<Double, Double> {
        if (outOfChina(lat, lng)) return Pair(lat, lng)
        val d = delta(lat, lng)
        return Pair(lat + d.first, lng + d.second)
    }
}
