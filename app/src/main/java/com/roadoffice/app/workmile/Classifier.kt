package com.roadoffice.app.workmile

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

enum class Purpose { BUSINESS, PERSONAL, UNKNOWN }

data class ClientPlace(val id: Long, val name: String, val location: LatLng?, val radiusM: Double = 300.0)

data class Classification(val purpose: Purpose, val clientId: Long?)

/** 두 지점 사이 거리(m) — 하버사인 공식. */
fun distanceMeters(a: LatLng, b: LatLng): Double {
    val r = 6_371_000.0
    val dLat = Math.toRadians(b.lat - a.lat)
    val dLon = Math.toRadians(b.lon - a.lon)
    val h = sin(dLat / 2).pow(2) + cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLon / 2).pow(2)
    return 2 * r * asin(sqrt(h))
}

/**
 * 업무/개인 자동 분류 (규칙 기반, 차 안에서만 처리).
 * 1) 도착 지점이 등록 거래처 반경 안 → 업무
 * 2) 내비 목적지 이름이 거래처 이름을 포함 → 업무
 * 3) 그 외 → 미분류 (정차 후 사용자에게 한 번만 묻는다)
 */
object Classifier {
    fun classify(trip: TripDraft, clients: List<ClientPlace>): Classification {
        val end = trip.endLocation
        if (end != null) {
            clients
                .filter { it.location != null }
                .minByOrNull { distanceMeters(end, it.location!!) }
                ?.takeIf { distanceMeters(end, it.location!!) <= it.radiusM }
                ?.let { return Classification(Purpose.BUSINESS, it.id) }
        }
        val dest = trip.destinationName?.replace(" ", "")
        if (!dest.isNullOrEmpty()) {
            clients.firstOrNull { dest.contains(it.name.replace(" ", ""), ignoreCase = true) }
                ?.let { return Classification(Purpose.BUSINESS, it.id) }
        }
        return Classification(Purpose.UNKNOWN, null)
    }
}
