package com.roadoffice.app.vehicle

import ai.pleos.playground.location.fused.FusedLocation
import ai.pleos.playground.location.fused.listener.FusedLocationListener
import ai.pleos.playground.location.fused.model.LocationData
import ai.pleos.playground.navi.constants.RouteOption
import ai.pleos.playground.navi.data.DestinationArrivedInfo
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.data.RouteStartInfo
import ai.pleos.playground.navi.data.TripInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import ai.pleos.playground.vehicle.listener.DistanceDrivenListener
import ai.pleos.playground.vehicle.listener.EvBatteryStateOfChargeListener
import ai.pleos.playground.vehicle.listener.FuelPercentListener
import ai.pleos.playground.vehicle.listener.GearSelectionListener
import ai.pleos.playground.vehicle.listener.SeatOccupancyListener
import android.car.Car
import android.car.VehiclePropertyIds
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.roadoffice.app.BuildConfig
import com.roadoffice.app.cabin.Cabin
import com.roadoffice.app.cabin.Seat
import com.roadoffice.app.cabin.SeatCalibration
import com.roadoffice.app.workmile.Gear
import com.roadoffice.app.workmile.LatLng
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/** 내비에서 오는 이벤트 — 운행 기록 보강용. */
sealed interface NaviEvent {
    data class RouteStarted(val name: String?, val distanceM: Int, val tollFare: Int) : NaviEvent
    data class Arrived(val name: String?, val address: String?) : NaviEvent
    data class Trip(val remainingM: Int, val eta: Int) : NaviEvent
}

/**
 * 차량 신호 모음. Pleos SDK(Vehicle·NaviHelper·FusedLocation) 와 AAOS 표준(속도)을 한곳에서 Flow 로 노출한다.
 * Vehicle: 주행거리·기어(워크마일), 좌석 점유(동승자 보호·착석 회고), 배터리 SOC·연료(주유·충전 포착).
 * 화면·기록기는 이 Flow 만 보면 되고, SDK 세부는 여기 갇힌다.
 */
class VehicleSignals(private val context: Context) {
    private val _gear = MutableStateFlow(Gear.UNKNOWN)
    private val _odometerKm = MutableStateFlow<Double?>(null)
    private val _speedKmh = MutableStateFlow<Float?>(null)
    private val _location = MutableStateFlow<LatLng?>(null)
    private val _navi = MutableSharedFlow<NaviEvent>(extraBufferCapacity = 16)
    private val _naviConnected = MutableStateFlow(false)
    private val _log = MutableSharedFlow<String>(replay = 50, extraBufferCapacity = 50)
    private val _cabin = MutableStateFlow(Cabin())
    private val rawSeats = java.util.concurrent.ConcurrentHashMap<Seat, Boolean>()
    private val prefs = context.getSharedPreferences("roadoffice_signals", Context.MODE_PRIVATE)
    private val seatCal = SeatCalibration(if (prefs.contains("seat_inverted")) prefs.getBoolean("seat_inverted", false) else null)
    private val _socPct = MutableStateFlow<Int?>(null)
    private val _fuelPct = MutableStateFlow<Int?>(null)

    val gear: StateFlow<Gear> = _gear
    val odometerKm: StateFlow<Double?> = _odometerKm
    val speedKmh: StateFlow<Float?> = _speedKmh
    val location: StateFlow<LatLng?> = _location
    val navi: SharedFlow<NaviEvent> = _navi
    val naviConnected: StateFlow<Boolean> = _naviConnected
    val log: SharedFlow<String> = _log
    val cabin: StateFlow<Cabin> = _cabin
    /** EV 배터리 잔량(%) — 전기차가 아니면 null */
    val socPct: StateFlow<Int?> = _socPct
    /** 연료 잔량(%) — 내연기관·하이브리드, 전기차면 null */
    val fuelPct: StateFlow<Int?> = _fuelPct

    private var vehicle: Vehicle? = null
    private var naviHelper: NaviHelper? = null
    private var fused: FusedLocation? = null
    private var car: Car? = null

    private val naviListener = object : NaviHelperEventListener {
        override fun onRouteStarted(info: RouteStartInfo) {
            _navi.tryEmit(NaviEvent.RouteStarted(info.name, info.distance, info.tollFare))
            log("경로 시작: ${info.name} · ${info.distance}m · 통행료 ${info.tollFare}원")
        }

        override fun onDestinationArrived(info: DestinationArrivedInfo) {
            _navi.tryEmit(NaviEvent.Arrived(info.name, info.address))
            log("도착: ${info.name}")
        }

        override fun onTripInfo(info: TripInfo) {
            _navi.tryEmit(NaviEvent.Trip(info.distance, info.eta))
        }
    }

    private val locationListener = object : FusedLocationListener {
        override fun onChanged(location: LocationData?) {
            location?.let { _location.value = LatLng(it.latitude, it.longitude) }
        }

        override fun onError(exception: Exception) = log("위치 오류: ${exception.message}")
    }

    fun start() {
        if (vehicle != null) return
        val v = Vehicle(context).also { it.initialize() }
        vehicle = v

        v.getOdometer().getCurrentDistanceDriven({ _odometerKm.value = it?.toDouble() }, { log("주행거리 조회 실패: ${it.message}") })
        v.getOdometer().registerDistanceDriven(object : DistanceDrivenListener {
            override fun onDistanceDrivenUpdated(value: Float?) {
                _odometerKm.value = value?.toDouble()
            }

            override fun onFailed(exception: Exception) = log("주행거리 구독 실패: ${exception.message}")
        })
        v.getGear().getCurrentGearSelectionState({ _gear.value = it.toGear() }, { log("기어 조회 실패: ${it.message}") })
        v.getGear().registerGearSelection(object : GearSelectionListener {
            override fun onSelected(gear: VehicleGear?) {
                _gear.value = gear.toGear()
            }

            override fun onFailed(exception: Exception) = log("기어 구독 실패: ${exception.message}")
        })

        subscribeSeats(v)
        subscribeEnergy(v)

        NaviHelper(context).also { n ->
            naviHelper = n
            n.initialize(
                onConnected = {
                    _naviConnected.value = true
                    n.addListener(naviListener)
                },
                onFailed = { log("내비 연결 실패: ${it.message}") },
            )
        }

        runCatching {
            FusedLocation(context).also { f ->
                fused = f
                f.initialize()
                // initialize() 는 연결 완료 콜백이 없어서, 서비스 연결 뒤 구독되도록 지연 후 걸고 위치가 안 오면 다시 건다
                subscribeLocation(f, attempt = 1)
            }
        }.onFailure { log("위치 초기화 실패: ${it.message}") }

        runCatching {
            val c = Car.createCar(context).also { car = it }
            val pm = c.getCarManager(Car.PROPERTY_SERVICE) as CarPropertyManager
            pm.registerCallback(object : CarPropertyManager.CarPropertyEventCallback {
                override fun onChangeEvent(value: CarPropertyValue<*>) {
                    (value.value as? Float)?.let { _speedKmh.value = it * 3.6f; calibrateSeats(it * 3.6f) }
                }

                override fun onErrorEvent(propId: Int, zone: Int) = log("속도 오류: $propId")
            }, VehiclePropertyIds.PERF_VEHICLE_SPEED, CarPropertyManager.SENSOR_RATE_NORMAL)
        }.onFailure { log("속도 구독 실패: ${it.message}") }
    }

    private val main = Handler(Looper.getMainLooper())

    /** 좌석별 점유. 한 자리라도 값이 오면 이 차는 좌석 점유를 지원하는 것([Cabin.known]). */
    private fun subscribeSeats(v: Vehicle) {
        val seats = mapOf(
            Seat.DRIVER to SeatValue.ROW_1_LEFT, Seat.PASSENGER to SeatValue.ROW_1_RIGHT,
            Seat.REAR_LEFT to SeatValue.ROW_2_LEFT, Seat.REAR_RIGHT to SeatValue.ROW_2_RIGHT,
        )
        fun update(seat: Seat, raw: Boolean?) {
            if (raw == null) return
            rawSeats[seat] = raw
            publishCabin()
        }
        seats.forEach { (seat, value) ->
            val area = SeatArea(value)
            runCatching {
                v.getSeat().getCurrentSeatOccupancy(area, { update(seat, it) }, { log("좌석(${seat.name}) 조회 실패: ${it.message}") })
                v.getSeat().registerSeatOccupancy(area, object : SeatOccupancyListener {
                    override fun onSeatOccupancyUpdated(value: Boolean?) = update(seat, value)
                    override fun onFailed(exception: Exception) = log("좌석(${seat.name}) 구독 실패: ${exception.message}")
                })
            }.onFailure { log("좌석 점유 미지원: ${it.message}") }
        }
    }

    private fun publishCabin() {
        val before = _cabin.value
        val now = seatCal.cabin(rawSeats)
        if (now == before) return
        if (now.known) Seat.entries.filter { (it in before.occupied) != (it in now.occupied) }
            .forEach { log("좌석 ${it.name}: ${if (it in now.occupied) "착석" else "빈자리"}") }
        _cabin.value = now
    }

    /** 주행 중(10km/h 이상)이면 운전석에 사람이 있다 → 좌석 값의 뜻을 맞춘다 ([SeatCalibration]). */
    private fun calibrateSeats(speedKmh: Float) {
        if (speedKmh < 10f || _gear.value != Gear.DRIVE) return
        val raw = rawSeats[Seat.DRIVER] ?: return
        if (seatCal.onDriving(raw)) {
            prefs.edit().putBoolean("seat_inverted", seatCal.inverted == true).apply()
            log("좌석 센서 보정: ${if (seatCal.inverted == true) "값 반전(SDK 2.3.2)" else "표준"}")
            publishCabin()
        }
    }

    /** 배터리 SOC(전기차)·연료(%) — 차종에 따라 한쪽만 온다. */
    private fun subscribeEnergy(v: Vehicle) {
        runCatching {
            v.getEvBattery().getEvBatteryStateOfCharge({ _socPct.value = it }, { log("배터리 조회 실패: ${it.message}") })
            v.getEvBattery().registerEvBatteryStateOfCharge(object : EvBatteryStateOfChargeListener {
                override fun onEvBatteryStateOfChargeUpdated(value: Int?) { _socPct.value = value }
                override fun onFailed(exception: Exception) = log("배터리 구독 실패: ${exception.message}")
            })
        }.onFailure { log("배터리 미지원: ${it.message}") }
        runCatching {
            v.getFuel().getFuelPercent({ _fuelPct.value = it }, { log("연료 조회 실패: ${it.message}") })
            v.getFuel().registerFuelPercent(object : FuelPercentListener {
                override fun onFuelPercentUpdated(value: Int?) { _fuelPct.value = value }
                override fun onFailed(exception: Exception) = log("연료 구독 실패: ${exception.message}")
            })
        }.onFailure { log("연료 미지원: ${it.message}") }
    }

    private fun subscribeLocation(f: FusedLocation, attempt: Int) {
        main.postDelayed({
            if (_location.value != null) return@postDelayed
            runCatching {
                f.unregisterFusedLocationCallback(locationListener)
                f.getLastKnownLocation(locationListener)
                f.registerFusedLocationCallback(locationListener)
            }.onFailure { log("위치 구독 실패($attempt): ${it.message}") }
            if (attempt < 5) subscribeLocation(f, attempt + 1)
        }, 2_000L * attempt)
    }

    /** 시험용 경로 안내 요청 (에뮬레이터 내비 서버 접속이 되면 경로 시작·도착 이벤트 확인). */
    fun requestRoute(name: String, address: String, at: LatLng) {
        runCatching {
            naviHelper?.requestRoute(RouteInfo(at.lat, at.lon, null, null, name, "", address, "", RouteOption.RECOMMENDED, "", false))
            log("경로 요청: $name")
        }.onFailure { log("경로 요청 실패: ${it.message}") }
    }

    /**
     * 이벤트 기록. 화면(신호 탭)용 목록은 메모리에만 두고, logcat 은 디버그 빌드에서만 남긴다
     * (보안 가이드: 출시 빌드 로그에 거래처명·상담 내용 등 민감 정보 금지).
     */
    fun log(msg: String) {
        if (BuildConfig.DEBUG) Log.i("RoadOffice", msg)
        _log.tryEmit(msg)
    }

    private fun VehicleGear?.toGear() = when (this) {
        VehicleGear.GEAR_PARK -> Gear.PARK
        VehicleGear.GEAR_REVERSE -> Gear.REVERSE
        VehicleGear.GEAR_NEUTRAL -> Gear.NEUTRAL
        null, VehicleGear.GEAR_UNKNOWN -> Gear.UNKNOWN
        else -> Gear.DRIVE
    }
}
