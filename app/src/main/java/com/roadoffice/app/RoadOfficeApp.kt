package com.roadoffice.app

import android.app.Application
import android.content.Context
import com.roadoffice.app.data.AppDatabase
import com.roadoffice.app.data.ClientEntity
import com.roadoffice.app.data.MeetingNoteEntity
import com.roadoffice.app.data.RefillEntity
import com.roadoffice.app.data.SecurePrefs
import com.roadoffice.app.data.TripEntity
import com.roadoffice.app.meeting.Briefing
import com.roadoffice.app.meeting.DebriefTrigger
import com.roadoffice.app.meeting.NoteAssistant
import com.roadoffice.app.meeting.LlmNoteAssistant
import com.roadoffice.app.meeting.Voice
import com.roadoffice.app.vehicle.NaviEvent
import com.roadoffice.app.vehicle.VehicleSignals
import com.roadoffice.app.workmile.ActiveTrip
import com.roadoffice.app.workmile.Classifier
import com.roadoffice.app.workmile.ClaimMode
import com.roadoffice.app.workmile.ClientPlace
import com.roadoffice.app.workmile.DayClose
import com.roadoffice.app.workmile.DayCloseInput
import com.roadoffice.app.workmile.EnergyKind
import com.roadoffice.app.workmile.EnergyWatcher
import com.roadoffice.app.workmile.ExportRefill
import com.roadoffice.app.workmile.ExportProfile
import com.roadoffice.app.workmile.ExportTrip
import com.roadoffice.app.workmile.Gear
import com.roadoffice.app.workmile.LatLng
import com.roadoffice.app.workmile.Purpose
import com.roadoffice.app.workmile.Refill
import com.roadoffice.app.workmile.ReportWriter
import com.roadoffice.app.workmile.TripDraft
import com.roadoffice.app.workmile.TripRecorder
import com.roadoffice.app.workmile.distanceMeters
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
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

/** 주유·충전 판단에 들어가는 신호 — 한 줄로 세워 순서대로 처리한다 (동시 처리 시 기록 중복 방지). */
private sealed interface EnergyEvent {
    data class GearChanged(val gear: Gear, val now: Long, val soc: Int?, val fuel: Int?) : EnergyEvent
    data class Level(val kind: EnergyKind, val pct: Int?, val now: Long) : EnergyEvent
}

/** 회고 대기 상태 — 미팅을 마치고 차에 다시 타면 "미팅 어땠어요?" 를 묻는다. */
data class PendingDebrief(val tripId: Long, val clientId: Long?, val clientName: String)

/**
 * 앱 전역 객체 — 신호·기록기·DB·음성을 연결한다. 앱 프로세스가 살아 있는 동안 백그라운드에서도 동작.
 *
 * 영업사원의 하루 흐름:
 *  출발 → (워크마일) 운행 기록 → 거래처 1km 앞 (브리핑) 지난 상담·할 일 음성 → 도착·주차 → 운행 저장·업무 분류
 *  → 미팅 → 차에 다시 탐(P단·운전석 착석) (회고) "○○ 미팅 어땠어요?" → 말한 내용을 상담 일지·할 일로 정리
 *  → 주차 중 충전·주유 (자동 포착) 금액만 넣으면 정산에 반영 → 마지막 주차 (하루 마감) 남은 정리를 30초에
 *
 * 동승자 보호: 운전석 말고 다른 자리에 사람이 있으면 거래처·상담 내용을 소리 내 읽지 않는다.
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
    private val _lastBrief = MutableStateFlow<String?>(null)
    /** 방금 읽은 브리핑 — 주행 화면에 15초간 한 줄로 (소리로 놓쳐도 다시 볼 수 있게) */
    val lastBrief: StateFlow<String?> = _lastBrief
    private val _listening = MutableStateFlow(false)
    val listening: StateFlow<Boolean> = _listening
    private val briefedThisTrip = mutableSetOf<Long>()
    /** 이만큼 차를 떠나 있었으면 미팅으로 본다 (디버그 빌드는 시연용으로 짧게) */
    private val minMeetingMs = if (BuildConfig.DEBUG) 10_000L else MIN_MEETING_MS
    private val debriefTrigger = DebriefTrigger(minMeetingMs)
    private val energy = mapOf(EnergyKind.EV_CHARGE to EnergyWatcher(EnergyKind.EV_CHARGE), EnergyKind.FUEL to EnergyWatcher(EnergyKind.FUEL))
    private val energyEvents = Channel<EnergyEvent>(Channel.UNLIMITED)
    /** 동승자 때문에 소리 내지 못한 회고 — 동승자가 내리면 다시 묻는다 */
    @Volatile private var debriefHeld = false
    /** 동승자 탑승으로 보류한 브리핑 로그를 운행당 한 번만 남기기 위함 */
    private val heldThisTrip = mutableSetOf<Long>()
    private val _dayClose = MutableStateFlow(false)
    /** 하루 마감 화면을 띄울지 */
    val dayClose: StateFlow<Boolean> = _dayClose

    var perKmRate: Int
        get() = settings.getInt("per_km_rate", 300)
        set(v) = settings.edit().putInt("per_km_rate", v).apply()
    var claimMode: ClaimMode
        get() = runCatching { ClaimMode.valueOf(settings.getString("claim_mode", null) ?: "") }.getOrDefault(ClaimMode.KM_RATE)
        set(v) = settings.edit().putString("claim_mode", v.name).apply()
    private val _privacy = MutableStateFlow(settings.getBoolean("privacy_on", true))
    /** 동승자 보호 (기본 켬) */
    val privacy: StateFlow<Boolean> = _privacy
    fun setPrivacy(on: Boolean) { _privacy.value = on; settings.edit().putBoolean("privacy_on", on).apply() }
    /** 이 시각 이후 운행이 끝나면 하루 마감을 권한다 */
    var dayEndHour: Int
        get() = settings.getInt("day_end_hour", 17)
        set(v) = settings.edit().putInt("day_end_hour", v).apply()
    /** 지금 업무 내용을 소리·화면으로 내면 안 되는가 */
    val guestOnBoard: Boolean get() = _privacy.value && signals.cabin.value.guestOnBoard
    private val secure = SecurePrefs(context)
    var userName: String
        get() = secure.getString("user_name") ?: ""
        set(v) = secure.putString("user_name", v)
    var department: String
        get() = secure.getString("department") ?: ""
        set(v) = secure.putString("department", v)

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
                val now = System.currentTimeMillis()
                lock.withLock {
                    recorder.onGear(g, now, signals.odometerKm.value, signals.location.value)
                    _activeTrip.value = recorder.active
                }
                if (g != Gear.UNKNOWN) signals.log("기어: ${g.name}")
                energyEvents.trySend(EnergyEvent.GearChanged(g, now, signals.socPct.value, signals.fuelPct.value))
                // 회고는 출발 전(착석)이 기본, 그때 못 물었으면(센서 없음·짧은 정차·앱 재시작) 출발 순간에 한 번
                if (prev == Gear.PARK && g != Gear.PARK && g != Gear.UNKNOWN) askDebrief(delayMs = 4_000)
                if (g == Gear.PARK) { briefedThisTrip.clear(); heldThisTrip.clear() }
                prev = g
            }
        }
        // 미팅 후 차에 다시 탐(P단·운전석 착석) → 회고
        scope.launch {
            var wasGuest = false
            signals.cabin.collect { c ->
                if (!c.known) return@collect
                val now = System.currentTimeMillis()
                val guest = guestOnBoard
                if (guest && !wasGuest) voice.stop() // 읽던 업무 내용이 있으면 동승자가 앉는 순간 끊는다
                // 동승자가 내렸고 보류한 회고가 있으면 이제 묻는다
                if (!guest && wasGuest && debriefHeld && signals.gear.value == Gear.PARK) speakDebrief()
                wasGuest = guest
                if (debriefTrigger.onSeat(c.driverSeated, signals.gear.value, now)) askDebrief(delayMs = 1_500)
            }
        }
        // 실차: 차에서 내리면 헤드유닛이 꺼져 "자리 비움"을 못 본다 → 앱이 P단·착석 상태로 시작되면 직전 주차를 미팅으로 본다
        scope.launch {
            val (c, g) = withTimeoutOrNull(30_000) {
                combine(signals.cabin, signals.gear) { c, g -> c to g }.first { it.first.known && it.second != Gear.UNKNOWN }
            } ?: return@launch
            if (c.driverSeated && g == Gear.PARK) askDebrief(delayMs = 3_000, minSinceParkMs = minMeetingMs)
        }
        // 주유·충전: 신호를 한 줄로 받아 순서대로 판단·저장
        restoreEnergy()
        scope.launch { signals.socPct.collect { energyEvents.trySend(EnergyEvent.Level(EnergyKind.EV_CHARGE, it, System.currentTimeMillis())) } }
        scope.launch { signals.fuelPct.collect { energyEvents.trySend(EnergyEvent.Level(EnergyKind.FUEL, it, System.currentTimeMillis())) } }
        scope.launch { for (e in energyEvents) handleEnergy(e) }
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
        maybeOfferDayClose(trip.endAt)
    }

    // ── 회고 (미팅 후) ─────────────────────────────────────────────

    /**
     * 직전 운행이 업무 방문이고 아직 묻지 않았고 메모도 없으면 회고 요청 (운행당 한 번).
     * 미팅 후 차에 다시 탄 순간(출발 전)이 기본이고, 그때 못 물었으면 출발 순간에 불린다.
     * 동승자가 있으면 소리 내 묻지 않고 화면 배너만 남겼다가, 동승자가 내리면 묻는다.
     */
    private fun askDebrief(delayMs: Long, minSinceParkMs: Long = 0) = scope.launch {
        val last = db.trips().latest() ?: return@launch
        val name = last.destinationName ?: return@launch
        val now = System.currentTimeMillis()
        if (last.purpose != Purpose.BUSINESS.name) return@launch
        if (now - last.endAt > DEBRIEF_WINDOW_MS || now - last.endAt < minSinceParkMs) return@launch
        if (settings.getLong("debrief_asked_trip", -1) == last.id) return@launch
        if (db.notes().countForTrip(last.id) > 0) return@launch
        settings.edit().putLong("debrief_asked_trip", last.id).apply()
        _pendingDebrief.value = PendingDebrief(last.id, last.clientId, name)
        delay(delayMs)
        speakDebrief()
    }

    private fun speakDebrief() {
        val p = _pendingDebrief.value ?: return
        if (guestOnBoard) { debriefHeld = true; signals.log("동승자 탑승 — 회고 질문은 화면에만 표시"); return }
        debriefHeld = false
        voice.speak("${p.clientName} 미팅 어땠어요? 말씀하시면 상담 일지로 정리할게요.") { listenDebrief() }
    }

    /** 회고 듣기 (질문 뒤 자동, 또는 화면의 "말하기" 버튼). */
    fun listenDebrief() {
        if (guestOnBoard) return
        _listening.value = true
        voice.listen(
            onResult = { text -> _listening.value = false; addNote(text) },
            onError = { e -> _listening.value = false; signals.log("회고 음성 입력 실패($e) — 화면에서 입력할 수 있어요") },
        )
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
        debriefHeld = false
        signals.log("상담 일지 저장: ${clientName ?: "거래처 미지정"} · 할 일 ${s.tasks.size}개")
        voice.speak(if (s.tasks.isEmpty() || guestOnBoard) "상담 일지에 저장했어요." else "저장했어요. 할 일 ${s.tasks.size}개, ${s.tasks.first()}.")
    }

    fun dismissDebrief() { _pendingDebrief.value = null; debriefHeld = false }

    // ── 브리핑 (미팅 전) ───────────────────────────────────────────

    private suspend fun briefIfClientNamed(destName: String) {
        val c = db.clients().all().firstOrNull { destName.replace(" ", "").contains(it.name.replace(" ", ""), true) } ?: return
        brief(c)
    }

    /** 거래처 브리핑 문장을 만들어 읽는다. 같은 운행에서 같은 거래처는 1번만. */
    suspend fun brief(c: ClientEntity, force: Boolean = false): String {
        if (!force && guestOnBoard) { if (heldThisTrip.add(c.id)) signals.log("동승자 탑승 — 브리핑 보류(거래처 정보 보호)"); return "" }
        if (!force && !briefedThisTrip.add(c.id)) return ""
        val notes = db.notes().forClient(c.id)
        val open = notes.filter { !it.tasksDone }.flatMap { it.tasks.lines() }.filter { it.isNotBlank() }
        val text = Briefing.text(c.name, notes.firstOrNull()?.summary, open, db.trips().visits(c.id), arriving = !force)
        voice.speak(text)
        val line = Briefing.short(c.name, open)
        _lastBrief.value = line
        scope.launch { delay(15_000); if (_lastBrief.value == line) _lastBrief.value = null }
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
        val refills = db.refills().between(from, to).map {
            ExportRefill(it.startedAt, EnergyKind.valueOf(it.kind), it.fromPct, it.toPct, it.amount, Purpose.valueOf(it.purpose), it.place)
        }
        val r = reports.write(trips, refills, ExportProfile(userName.ifBlank { "-" }, department.ifBlank { "-" }, perKmRate, claimMode), from)
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

    // ── 주유·충전 포착 ─────────────────────────────────────────────

    /** 앱이 다시 시작됐을 때 저장해 둔 주차 상태를 되돌린다 (실차는 주차 중 헤드유닛이 꺼진다). */
    private fun restoreEnergy() {
        energy.forEach { (kind, w) ->
            val at = settings.getLong("energy_parked_at_${kind.name}", 0)
            val low = settings.getInt("energy_low_${kind.name}", -1)
            if (at > 0 && low >= 0 && System.currentTimeMillis() - at < ENERGY_RESTORE_MS) w.restore(EnergyWatcher.Snapshot(at, low))
        }
    }

    private fun saveEnergy(kind: EnergyKind, w: EnergyWatcher) {
        val snap = w.snapshot()
        settings.edit().apply {
            if (snap == null) { remove("energy_parked_at_${kind.name}"); remove("energy_low_${kind.name}"); remove("refill_id_${kind.name}") }
            else { putLong("energy_parked_at_${kind.name}", snap.parkedAt); putInt("energy_low_${kind.name}", snap.low) }
        }.apply()
    }

    private suspend fun handleEnergy(e: EnergyEvent) {
        when (e) {
            is EnergyEvent.GearChanged -> energy.forEach { (kind, w) ->
                // 주차 중 오른 배터리·연료 → 출발할 때 최종 값으로 확정
                w.onGear(e.gear, e.now, if (kind == EnergyKind.EV_CHARGE) e.soc else e.fuel)?.let { onRefill(it, final = true) }
                saveEnergy(kind, w)
            }
            is EnergyEvent.Level -> {
                val w = energy.getValue(e.kind)
                w.onLevel(e.pct, e.now)?.let { onRefill(it, final = false) }
                saveEnergy(e.kind, w)
            }
        }
    }

    /**
     * 주차 중 배터리·연료 상승 → 기록을 만들거나(첫 포착) 최신 값으로 고친다. 금액은 사용자가 넣는다.
     * [handleEnergy] 에서만 불려 순서대로 처리된다. 기록 id 는 앱이 다시 시작돼도 이어지게 저장한다.
     */
    private suspend fun onRefill(r: Refill, final: Boolean) {
        val key = "refill_id_${r.kind.name}"
        val id = settings.getLong(key, -1)
        if (id >= 0) {
            db.refills().setTo(id, r.toPct)
            if (final) settings.edit().remove(key).apply()
            return
        }
        val last = db.trips().latest()
        // 이번 주차의 운행(주차 직전에 끝난 것)일 때만 장소·업무 여부를 이어받는다
        val sameStop = last != null && last.endAt >= r.startedAt - 60_000
        val purpose = if (sameStop && last?.purpose == Purpose.BUSINESS.name) Purpose.BUSINESS else Purpose.UNKNOWN
        val here = signals.location.value
        val newId = db.refills().insert(
            RefillEntity(kind = r.kind.name, startedAt = r.startedAt, fromPct = r.fromPct, toPct = r.toPct, lat = here?.lat, lon = here?.lon, place = if (sameStop) last?.destinationName else null, purpose = purpose.name),
        )
        if (!final) settings.edit().putLong(key, newId).apply()
        signals.log("${r.kind.label} 포착: ${r.fromPct}% → ${r.toPct}% (${purpose.name})")
        if (!final && (signals.cabin.value.driverSeated || !signals.cabin.value.known)) {
            val what = if (r.kind == EnergyKind.EV_CHARGE) "충전" else "주유"
            voice.speak(if (claimMode == ClaimMode.ACTUAL) "$what 기록을 남겼어요. 업무용이면 금액을 넣어 주세요." else "$what 기록을 남겼어요.")
        }
    }

    fun setRefillAmount(id: Long, amount: Long?) = scope.launch { db.refills().setAmount(id, amount) }
    fun setRefillPurpose(id: Long, purpose: Purpose) = scope.launch { db.refills().setPurpose(id, purpose.name) }

    // ── 하루 마감 ─────────────────────────────────────────────────

    private fun today(now: Long = System.currentTimeMillis()): Pair<Long, Long> {
        val c = java.util.Calendar.getInstance().apply { timeInMillis = now; set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0); set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0) }
        val from = c.timeInMillis
        return from to from + DAY_MS
    }

    /** 이번 달 1일 0시 */
    fun monthStart(now: Long = System.currentTimeMillis()): Long = java.util.Calendar.getInstance().apply {
        timeInMillis = today(now).first; set(java.util.Calendar.DAY_OF_MONTH, 1)
    }.timeInMillis

    suspend fun dayCloseInput(): DayCloseInput {
        val (from, to) = today()
        val trips = db.trips().between(from, to)
        val notes = db.notes().all().filter { !it.tasksDone }
        // 미분류·금액 미입력은 오늘 것만이 아니라 이번 달에 남은 것 전부 (어제 미룬 것도 다시 보이게)
        val monthTrips = db.trips().between(monthStart(), to)
        return DayCloseInput(
            trips = trips.size,
            businessKm = trips.filter { it.purpose == Purpose.BUSINESS.name }.sumOf { it.distanceKm },
            unknownTrips = monthTrips.count { it.purpose == Purpose.UNKNOWN.name },
            refillsWithoutAmount = if (claimMode == ClaimMode.ACTUAL) db.refills().between(monthStart(), to).count { it.amount == null && it.purpose != Purpose.PERSONAL.name } else 0,
            openTasks = notes.flatMap { it.tasks.lines() }.filter { it.isNotBlank() },
            tomorrowClients = notes.filter { (it.followUpAt ?: 0) in to until to + DAY_MS }.mapNotNull { it.clientName }.distinct(),
        )
    }

    /** 퇴근 시각 이후 운행이 끝났고 오늘 아직 마감하지 않았으면 하루 마감을 권한다 (하루 1번). */
    private suspend fun maybeOfferDayClose(endAt: Long) {
        val hour = java.util.Calendar.getInstance().apply { timeInMillis = endAt }.get(java.util.Calendar.HOUR_OF_DAY)
        val key = today(endAt).first
        if (hour < dayEndHour || settings.getLong("day_close_offered", 0) == key || settings.getLong("day_closed", 0) == key) return
        settings.edit().putLong("day_close_offered", key).apply()
        openDayClose()
    }

    /** 하루 마감 화면을 열고 요약을 읽어 준다 (화면의 "오늘 마감" 버튼도 여기로). */
    fun openDayClose() = scope.launch {
        _dayClose.value = true
        val input = dayCloseInput()
        if (signals.cabin.value.driverSeated || !signals.cabin.value.known) voice.speak(DayClose.speech(input, guestOnBoard))
    }

    fun cancelDayClose() { _dayClose.value = false }

    fun finishDayClose() {
        _dayClose.value = false
        settings.edit().putLong("day_closed", today().first).apply()
        voice.speak("오늘 마감했어요.")
        signals.log("하루 마감 완료")
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
        /** 이만큼 차를 떠나 있었으면 미팅을 한 것으로 본다 (짐 꺼내기 등 잠깐 내림은 제외) */
        const val MIN_MEETING_MS = 3 * 60 * 1000L
        const val DAY_MS = 24 * 60 * 60 * 1000L
        /** 이보다 오래된 주차 상태는 되돌리지 않는다 */
        const val ENERGY_RESTORE_MS = 24 * 60 * 60 * 1000L
    }
}

fun ClientEntity.location(): LatLng? = if (lat != null && lon != null) LatLng(lat, lon) else null
