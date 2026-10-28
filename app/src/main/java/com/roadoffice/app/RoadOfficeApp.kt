package com.roadoffice.app

import android.app.Application
import android.content.Context
import com.roadoffice.app.data.AppDatabase
import com.roadoffice.app.data.ClientEntity
import com.roadoffice.app.data.MeetingNoteEntity
import com.roadoffice.app.data.TripEntity
import com.roadoffice.app.meeting.Briefing
import com.roadoffice.app.meeting.NoteAssistant
import com.roadoffice.app.meeting.LlmNoteAssistant
import com.roadoffice.app.meeting.Voice
import com.roadoffice.app.vehicle.NaviEvent
import com.roadoffice.app.vehicle.VehicleSignals
import com.roadoffice.app.workmile.ActiveTrip
import com.roadoffice.app.workmile.Classifier
import com.roadoffice.app.workmile.ClientPlace
import com.roadoffice.app.workmile.ExportProfile
import com.roadoffice.app.workmile.ExportTrip
import com.roadoffice.app.workmile.Gear
import com.roadoffice.app.workmile.LatLng
import com.roadoffice.app.workmile.Purpose
import com.roadoffice.app.workmile.ReportWriter
import com.roadoffice.app.workmile.TripDraft
import com.roadoffice.app.workmile.TripRecorder
import com.roadoffice.app.workmile.distanceMeters
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

/** 회고 대기 상태 — 거래처에서 출발하면 "미팅 어땠어요?" 를 묻는다. */
data class PendingDebrief(val tripId: Long, val clientId: Long?, val clientName: String)

/**
 * 앱 전역 객체 — 신호·기록기·DB·음성을 연결한다. 앱 프로세스가 살아 있는 동안 백그라운드에서도 동작.
 *
 * 영업사원의 하루 흐름:
 *  출발 → (워크마일) 운행 기록 → 거래처 1km 앞 (브리핑) 지난 상담·할 일 음성 → 도착·주차 → 운행 저장·업무 분류
 *  → 미팅 → 다시 출발 (회고) "○○ 미팅 어땠어요?" → 말한 내용을 상담 일지·할 일로 정리
 */
class AppGraph(private val context: Context) {
    val db: AppDatabase by lazy { AppDatabase.open(context) }
    val signals = VehicleSignals(context)
    val voice by lazy { Voice(context) { signals.log(it) } }
    val settings = context.getSharedPreferences("roadoffice_settings", Context.MODE_PRIVATE)
    private val reports = ReportWriter(context)
    /** 상담 일지 정리기: Pleos 기기 내장 LLM 우선, 안 되면 규칙 기반 (서버 Bedrock 은 승인 후 추가) */
    val assistant: NoteAssistant by lazy { LlmNoteAssistant(context, log = { signals.log(it) }) }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val recorder = TripRecorder()
    private val lock = Mutex()
    private val _activeTrip = MutableStateFlow<ActiveTrip?>(null)
    val activeTrip: StateFlow<ActiveTrip?> = _activeTrip
    private val _pendingDebrief = MutableStateFlow<PendingDebrief?>(null)
    val pendingDebrief: StateFlow<PendingDebrief?> = _pendingDebrief
    private val _listening = MutableStateFlow(false)
    val listening: StateFlow<Boolean> = _listening
    private val briefedThisTrip = mutableSetOf<Long>()

    var perKmRate: Int
        get() = settings.getInt("per_km_rate", 300)
        set(v) = settings.edit().putInt("per_km_rate", v).apply()
    var userName: String
        get() = settings.getString("user_name", "") ?: ""
        set(v) = settings.edit().putString("user_name", v).apply()
    var department: String
        get() = settings.getString("department", "") ?: ""
        set(v) = settings.edit().putString("department", v).apply()

    private var started = false

    fun start() {
        if (started) return
        started = true
        signals.start()
        voice.start()
        assistant // LLM 서비스는 연결에 시간이 걸려 미리 붙여 둔다

        scope.launch {
            var prev = Gear.UNKNOWN
            signals.gear.collect { g ->
                lock.withLock {
                    recorder.onGear(g, System.currentTimeMillis(), signals.odometerKm.value, signals.location.value)
                    _activeTrip.value = recorder.active
                }
                if (g != Gear.UNKNOWN) signals.log("기어: ${g.name}")
                // 주차 → 출발 전환: 직전 운행이 거래처 방문이면 회고를 묻는다
                if (prev == Gear.PARK && g != Gear.PARK && g != Gear.UNKNOWN) onDeparture()
                if (g == Gear.PARK) briefedThisTrip.clear()
                prev = g
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
                if (e is NaviEvent.RouteStarted) e.name?.let { briefIfClientNamed(it) }
            }
        }
        // 거래처 1km 안으로 들어오면 브리핑 (주행 중, 운행당 거래처별 1회)
        scope.launch {
            signals.location.collect { here ->
                if (here == null || !recorder.isDriving) return@collect
                val start = recorder.active?.startLocation
                val near = db.clients().all().filter { it.location() != null }
                    // 방금 떠나온 거래처(출발 지점 1km 안)는 브리핑하지 않는다
                    .filter { start == null || distanceMeters(start, it.location()!!) > BRIEF_RADIUS_M }
                    .map { it to distanceMeters(here, it.location()!!) }
                    .filter { it.second <= BRIEF_RADIUS_M }
                    .minByOrNull { it.second }?.first ?: return@collect
                brief(near)
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

    // ── 회고 (미팅 후) ─────────────────────────────────────────────

    /** 거래처에 주차했다가 다시 출발 → 직전 운행이 업무 방문이고 아직 메모가 없으면 회고 요청. */
    private fun onDeparture() = scope.launch {
        val last = db.trips().latest() ?: return@launch
        val name = last.destinationName ?: return@launch
        if (last.purpose != Purpose.BUSINESS.name) return@launch
        if (System.currentTimeMillis() - last.endAt > DEBRIEF_WINDOW_MS) return@launch
        if (db.notes().countForTrip(last.id) > 0) return@launch
        _pendingDebrief.value = PendingDebrief(last.id, last.clientId, name)
        delay(4_000) // 출발 직후 안전 확보 후 짧게
        voice.speak("$name 미팅 어땠어요? 말씀하시면 상담 일지로 정리할게요.") {
            _listening.value = true
            voice.listen(
                onResult = { text -> _listening.value = false; addNote(text) },
                onError = { e -> _listening.value = false; signals.log("회고 음성 입력 실패($e) — 정차 후 화면에서 입력할 수 있어요") },
            )
        }
    }

    /** 회고 텍스트 저장 (음성·화면 입력·Gleo 공통). 대기 중인 회고가 없으면 마지막 업무 운행에 붙인다. */
    fun addNote(text: String, clientNameHint: String? = null) = scope.launch {
        if (text.isBlank()) return@launch
        val pending = _pendingDebrief.value
        val client = pending?.clientId?.let { db.clients().byId(it) }
            ?: clientNameHint?.let { hint -> db.clients().all().firstOrNull { it.name.contains(hint, true) || hint.contains(it.name, true) } }
        val clientName = client?.name ?: pending?.clientName ?: clientNameHint
        val s = assistant.structure(text, clientName)
        db.notes().insert(
            MeetingNoteEntity(
                clientId = client?.id ?: pending?.clientId,
                clientName = clientName,
                tripId = pending?.tripId,
                createdAt = System.currentTimeMillis(),
                rawText = text,
                summary = s.summary,
                tasks = s.tasks.joinToString("\n"),
                followUpAt = s.followUpAt,
                source = s.source,
            ),
        )
        _pendingDebrief.value = null
        signals.log("상담 일지 저장: ${clientName ?: "거래처 미지정"} · 할 일 ${s.tasks.size}개")
        voice.speak(if (s.tasks.isEmpty()) "상담 일지에 저장했어요." else "저장했어요. 할 일 ${s.tasks.size}개, ${s.tasks.first()}.")
    }

    fun dismissDebrief() { _pendingDebrief.value = null }

    // ── 브리핑 (미팅 전) ───────────────────────────────────────────

    private suspend fun briefIfClientNamed(destName: String) {
        val c = db.clients().all().firstOrNull { destName.replace(" ", "").contains(it.name.replace(" ", ""), true) } ?: return
        brief(c)
    }

    /** 거래처 브리핑 문장을 만들어 읽는다. 같은 운행에서 같은 거래처는 1번만. */
    suspend fun brief(c: ClientEntity, force: Boolean = false): String {
        if (!force && !briefedThisTrip.add(c.id)) return ""
        val notes = db.notes().forClient(c.id)
        val open = notes.filter { !it.tasksDone }.flatMap { it.tasks.lines() }.filter { it.isNotBlank() }
        val text = Briefing.text(c.name, notes.firstOrNull()?.summary, open, db.trips().visits(c.id), arriving = !force)
        voice.speak(text)
        return text
    }

    suspend fun briefByName(name: String): String? {
        val c = db.clients().all().firstOrNull { it.name.contains(name, true) || name.contains(it.name, true) } ?: return null
        return brief(c, force = true)
    }

    // ── 워크마일 정산 ─────────────────────────────────────────────

    suspend fun exportMonth(from: Long, to: Long): ReportWriter.Result {
        val trips = db.trips().between(from, to).map {
            ExportTrip(it.startAt, it.endAt, it.startOdometerKm, it.endOdometerKm, it.distanceKm, Purpose.valueOf(it.purpose), it.destinationName, it.tollFareEstimate)
        }
        val r = reports.write(trips, ExportProfile(userName.ifBlank { "-" }, department.ifBlank { "-" }, perKmRate), from)
        signals.log("정산 파일 ${r.files.size}개 생성: ${r.files.joinToString { it.name }}")
        return r
    }

    fun setPurpose(tripId: Long, purpose: Purpose) = scope.launch { db.trips().setPurpose(tripId, purpose.name) }

    /** 가장 최근 운행의 목적 지정 (Gleo "이번 운행 업무로 해 줘"). */
    suspend fun setLatestPurpose(purpose: Purpose): TripEntity? {
        val t = db.trips().latest() ?: return null
        db.trips().setPurpose(t.id, purpose.name)
        return t
    }

    fun setTasksDone(noteId: Long, done: Boolean) = scope.launch { db.notes().setDone(noteId, done) }

    fun addClientHere(name: String) = scope.launch {
        val here = signals.location.value
        db.clients().insert(ClientEntity(name = name, lat = here?.lat, lon = here?.lon, createdAt = System.currentTimeMillis()))
        signals.log("거래처 등록: $name ${if (here == null) "(위치 없음 — 이름으로만 인식)" else ""}")
    }

    fun deleteClient(id: Long) = scope.launch { db.clients().delete(id) }

    fun launch(block: suspend CoroutineScope.() -> Unit) = scope.launch(block = block)

    private companion object {
        const val BRIEF_RADIUS_M = 1_000.0
        const val DEBRIEF_WINDOW_MS = 3 * 60 * 60 * 1000L
    }
}

fun ClientEntity.location(): LatLng? = if (lat != null && lon != null) LatLng(lat, lon) else null
