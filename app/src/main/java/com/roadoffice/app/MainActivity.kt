package com.roadoffice.app

import android.car.Car
import android.car.VehiclePropertyIds
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.pleos.playground.navi.data.DestinationArrivedInfo
import ai.pleos.playground.navi.data.RouteStartInfo
import ai.pleos.playground.navi.data.TripInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import ai.pleos.playground.vehicle.listener.DistanceDrivenListener
import ai.pleos.playground.vehicle.listener.GearSelectionListener
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 1단계(B-01·B-02): 차량 신호 점검 화면.
 * 워크마일에 필요한 신호(주행거리·기어·속도·내비 경로/도착/트립)가 실제로 들어오는지 확인한다.
 */
class MainActivity : ComponentActivity() {

    private val odometerKm = mutableStateOf("—")
    private val gear = mutableStateOf("—")
    private val speedKmh = mutableStateOf("—")
    private val naviState = mutableStateOf("연결 대기")
    private val events = mutableStateListOf<String>()

    private lateinit var vehicle: Vehicle
    private lateinit var naviHelper: NaviHelper
    private var car: Car? = null

    private val permissions = arrayOf(
        "pleos.car.permission.CAR_DRIVE",
        "pleos.car.permission.CAR_ENERGY",
        "pleos.car.permission.NAVI_ROUTE",
        Car.PERMISSION_SPEED,
    )

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            result.forEach { (p, granted) -> log("권한 ${p.substringAfterLast('.')}: ${if (granted) "허용" else "거부"}") }
            startSignals()
        }

    private val naviListener = object : NaviHelperEventListener {
        override fun onRouteStarted(info: RouteStartInfo) {
            log("경로 시작: ${info.name} · ${info.distance}m · ${info.duration}s · 통행료 ${info.tollFare}원")
        }

        override fun onDestinationArrived(info: DestinationArrivedInfo) {
            log("도착: ${info.name} (${info.address}) · ${info.distance}m")
        }

        override fun onTripInfo(info: TripInfo) {
            log("트립: 남은 ${info.distance}m · ETA ${info.eta}")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { SignalScreen() }
        permissionLauncher.launch(permissions)
    }

    private fun startSignals() {
        vehicle = Vehicle(applicationContext).also { it.initialize() }

        vehicle.getOdometer().getCurrentDistanceDriven(
            { km -> odometerKm.value = "%.1f km".format(km ?: 0f) },
            { e -> log("주행거리 조회 실패: ${e.message}") },
        )
        vehicle.getOdometer().registerDistanceDriven(object : DistanceDrivenListener {
            override fun onDistanceDrivenUpdated(value: Float?) {
                odometerKm.value = "%.1f km".format(value ?: 0f)
            }

            override fun onFailed(exception: Exception) = log("주행거리 구독 실패: ${exception.message}")
        })

        vehicle.getGear().getCurrentGearSelectionState(
            { g -> gear.value = g?.label() ?: "—" },
            { e -> log("기어 조회 실패: ${e.message}") },
        )
        vehicle.getGear().registerGearSelection(object : GearSelectionListener {
            override fun onSelected(gear: VehicleGear?) {
                val label = gear?.label() ?: "—"
                this@MainActivity.gear.value = label
                log("기어 변경: $label")
            }

            override fun onFailed(exception: Exception) = log("기어 구독 실패: ${exception.message}")
        })

        naviHelper = NaviHelper(applicationContext)
        naviHelper.initialize(
            onConnected = {
                naviState.value = "연결됨"
                naviHelper.addListener(naviListener)
            },
            onFailed = { t -> naviState.value = "연결 실패: ${t.message}" },
        )

        startSpeed()
    }

    private fun startSpeed() {
        runCatching {
            val c = Car.createCar(this)
            car = c
            val pm = c.getCarManager(Car.PROPERTY_SERVICE) as CarPropertyManager
            pm.registerCallback(object : CarPropertyManager.CarPropertyEventCallback {
                override fun onChangeEvent(value: CarPropertyValue<*>) {
                    val mps = value.value as? Float ?: return
                    speedKmh.value = "%.0f km/h".format(mps * 3.6f)
                }

                override fun onErrorEvent(propId: Int, zone: Int) = log("속도 오류: $propId")
            }, VehiclePropertyIds.PERF_VEHICLE_SPEED, CarPropertyManager.SENSOR_RATE_NORMAL)
        }.onFailure { log("속도 구독 실패: ${it.message}") }
    }

    private fun VehicleGear.label() = when (this) {
        VehicleGear.GEAR_PARK -> "P"
        VehicleGear.GEAR_REVERSE -> "R"
        VehicleGear.GEAR_NEUTRAL -> "N"
        VehicleGear.GEAR_DRIVE -> "D"
        else -> toString()
    }

    private fun log(msg: String) {
        Log.i(TAG, msg)
        val t = SimpleDateFormat("HH:mm:ss", Locale.KOREA).format(Date())
        runOnUiThread { events.add(0, "$t  $msg") }
    }

    override fun onDestroy() {
        if (::naviHelper.isInitialized) {
            naviHelper.removeListener(naviListener)
            naviHelper.release()
        }
        if (::vehicle.isInitialized) vehicle.release()
        car?.disconnect()
        super.onDestroy()
    }

    @androidx.compose.runtime.Composable
    private fun SignalScreen() {
        MaterialTheme {
            Column(
                Modifier
                    .fillMaxSize()
                    .background(Color(0xFF002C5F))
                    .padding(32.dp),
            ) {
                Text("로드오피스 · 신호 점검", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(24.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Tile("주행거리", odometerKm.value)
                    Tile("기어", gear.value)
                    Tile("속도", speedKmh.value)
                    Tile("내비", naviState.value)
                }
                Spacer(Modifier.height(24.dp))
                Text("이벤트", color = Color(0xFF00AAD2), fontSize = 20.sp, fontWeight = FontWeight.Bold)
                LazyColumn(Modifier.fillMaxWidth()) {
                    items(events) { Text(it, color = Color.White, fontSize = 18.sp, modifier = Modifier.padding(vertical = 4.dp)) }
                }
            }
        }
    }

    @androidx.compose.runtime.Composable
    private fun Tile(label: String, value: String) {
        Column(
            Modifier
                .width(220.dp)
                .background(Color(0xFF0A3D7A))
                .padding(20.dp),
        ) {
            Text(label, color = Color(0xFFA9BCD6), fontSize = 16.sp)
            Text(value, color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        }
    }

    companion object {
        private const val TAG = "RoadOffice"
    }
}
