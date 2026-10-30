package com.roadoffice.app

import android.car.Car
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import com.roadoffice.app.ui.RoadOfficeScreen

/** 진입점: 권한 요청 → 신호·기록 시작 → 화면. 기록은 [AppGraph] 가 맡아 화면을 닫아도 이어진다. */
class MainActivity : ComponentActivity() {

    private val permissions = arrayOf(
        "pleos.car.permission.CAR_DRIVE",
        "pleos.car.permission.CAR_ENERGY",
        "pleos.car.permission.CAR_SEATS",
        "pleos.car.permission.NAVI_ROUTE",
        "pleos.car.permission.NAVI_ROUTE_SEARCH",
        "pleos.car.permission.FUSED_LOCATION",
        "pleos.car.permission.STT_SERVICE",
        "pleos.car.permission.TTS_SERVICE",
        "pleos.car.permission.LLM_SERVICE",
        android.Manifest.permission.RECORD_AUDIO,
        Car.PERMISSION_SPEED,
    )

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            result.filterValues { !it }.keys.forEach { graph.signals.log("권한 거부: ${it.substringAfterLast('.')}") }
            graph.start()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { RoadOfficeScreen(graph) }
        permissionLauncher.launch(permissions)
    }
}
