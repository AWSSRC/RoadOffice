package com.roadoffice.app

import android.app.Application
import android.content.Context
import com.roadoffice.app.data.AppDatabase
import com.roadoffice.app.data.ClientEntity
import com.roadoffice.app.data.TripEntity
import com.roadoffice.app.vehicle.NaviEvent
import com.roadoffice.app.vehicle.VehicleSignals
import com.roadoffice.app.workmile.ActiveTrip
import com.roadoffice.app.workmile.Classifier
import com.roadoffice.app.workmile.ClientPlace
import com.roadoffice.app.workmile.LatLng
import com.roadoffice.app.workmile.Purpose
import com.roadoffice.app.workmile.TripDraft
import com.roadoffice.app.workmile.TripRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class RoadOfficeApp : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
    }
}

val Context.graph: AppGraph get() = (applicationContext as RoadOfficeApp).graph

/** 앱 전역 객체 — 신호·기록기·DB 를 연결한다. 앱 프로세스가 살아 있는 동안 백그라운드에서도 기록이 이어진다. */
class AppGraph(context: Context) {
    val db: AppDatabase by lazy { AppDatabase.open(context) }
    val signals = VehicleSignals(context)
    val settings = context.getSharedPreferences("roadoffice_settings", Context.MODE_PRIVATE)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val recorder = TripRecorder()
    private val lock = Mutex()
    private val _activeTrip = MutableStateFlow<ActiveTrip?>(null)
    val activeTrip: StateFlow<ActiveTrip?> = _activeTrip

    var perKmRate: Int
        get() = settings.getInt("per_km_rate", 300)
        set(v) = settings.edit().putInt("per_km_rate", v).apply()

    private var started = false

    fun start() {
        if (started) return
        started = true
        signals.start()

        scope.launch {
            signals.gear.collect { g ->
                lock.withLock {
                    recorder.onGear(g, System.currentTimeMillis(), signals.odometerKm.value, signals.location.value)
                    _activeTrip.value = recorder.active
                }
                if (g.name != "UNKNOWN") signals.log("기어: ${g.name}")
            }
        }
        scope.launch {
            signals.odometerKm.collect { km -> if (km != null) lock.withLock { recorder.onOdometer(km) } }
        }
        scope.launch {
            signals.navi.collect { e ->
                lock.withLock {
                    when (e) {
                        is NaviEvent.RouteStarted -> recorder.onRouteStarted(e.name, e.tollFare)
                        is NaviEvent.Arrived -> recorder.onArrived(e.name, e.address)
                        is NaviEvent.Trip -> Unit
                    }
                    _activeTrip.value = recorder.active
                }
            }
        }
        // 주차 확정 감시 (1초 간격)
        scope.launch {
            while (true) {
                val done = lock.withLock {
                    recorder.onTick(System.currentTimeMillis()).also { _activeTrip.value = recorder.active }
                }
                if (done != null) save(done)
                delay(1_000)
            }
        }
    }

    private suspend fun save(trip: TripDraft) {
        val clients = db.clients().all().map { ClientPlace(it.id, it.name, it.location(), it.radiusM) }
        val c = Classifier.classify(trip, clients)
        val clientName = clients.firstOrNull { it.id == c.clientId }?.name
        db.trips().insert(
            TripEntity(
                startAt = trip.startAt,
                endAt = trip.endAt,
                startOdometerKm = trip.startOdometerKm,
                endOdometerKm = trip.endOdometerKm,
                distanceKm = trip.distanceKm,
                startLat = trip.startLocation?.lat,
                startLon = trip.startLocation?.lon,
                endLat = trip.endLocation?.lat,
                endLon = trip.endLocation?.lon,
                destinationName = trip.destinationName ?: clientName,
                destinationAddress = trip.destinationAddress,
                tollFareEstimate = trip.tollFareEstimate,
                purpose = c.purpose.name,
                clientId = c.clientId,
            ),
        )
        signals.log("운행 저장: %.1fkm · %s".format(trip.distanceKm, if (c.purpose == Purpose.BUSINESS) "업무($clientName)" else "미분류"))
    }

    fun setPurpose(tripId: Long, purpose: Purpose) = scope.launch { db.trips().setPurpose(tripId, purpose.name) }

    fun addClientHere(name: String) = scope.launch {
        val here = signals.location.value
        db.clients().insert(ClientEntity(name = name, lat = here?.lat, lon = here?.lon, createdAt = System.currentTimeMillis()))
        signals.log("거래처 등록: $name ${if (here == null) "(위치 없음 — 이름으로만 인식)" else ""}")
    }

    fun deleteClient(id: Long) = scope.launch { db.clients().delete(id) }
}

fun ClientEntity.location(): LatLng? = if (lat != null && lon != null) LatLng(lat, lon) else null
