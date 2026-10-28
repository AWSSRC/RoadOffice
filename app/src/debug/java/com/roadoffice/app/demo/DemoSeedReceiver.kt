package com.roadoffice.app.demo

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.roadoffice.app.data.ClientEntity
import com.roadoffice.app.graph

/**
 * 디버그 빌드 전용 — 시연 영상용 가상 거래처 주입 (src/debug 에만 있어 release APK 에는 포함되지 않음).
 * adb shell am broadcast -a com.roadoffice.app.DEMO_SEED -n com.roadoffice.app/.demo.DemoSeedReceiver
 * 첫 거래처는 현재 위치로 등록해 도착 시 업무로 분류되게 한다. 이름은 모두 가상.
 */
class DemoSeedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val g = context.graph
        val pending = goAsync()
        g.launch {
            try {
                val here = g.signals.location.value
                val now = System.currentTimeMillis()
                if (g.db.clients().all().isEmpty()) {
                    g.db.clients().insert(ClientEntity(name = "한빛병원", lat = here?.lat, lon = here?.lon, createdAt = now))
                    g.db.clients().insert(ClientEntity(name = "새솔내과의원", lat = null, lon = null, createdAt = now))
                    g.db.clients().insert(ClientEntity(name = "미래약국", lat = null, lon = null, createdAt = now))
                }
                g.userName = "김영업"
                g.department = "영업1팀"
                g.signals.log("시연 데이터 준비 완료")
            } finally {
                pending.finish()
            }
        }
    }
}
