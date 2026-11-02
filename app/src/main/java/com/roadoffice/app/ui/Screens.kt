package com.roadoffice.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.text.TextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.roadoffice.app.AppGraph
import com.roadoffice.app.data.ClientEntity
import com.roadoffice.app.data.MeetingNoteEntity
import com.roadoffice.app.data.TripEntity
import com.roadoffice.app.workmile.ClaimMode
import com.roadoffice.app.workmile.Gear
import com.roadoffice.app.workmile.HeaderStyle
import com.roadoffice.app.workmile.LatLng
import com.roadoffice.app.workmile.Purpose
import com.roadoffice.app.workmile.WorkMileSummary
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

// 현대 브랜드 색
internal val HyundaiBlue = Color(0xFF002C5F)
internal val Card = Color(0xFF0A3D7A)
internal val Active = Color(0xFF00AAD2)
internal val Sub = Color(0xFFA9BCD6)
internal val Warn = Color(0xFFF2A900)

/** 주행 중이면 한 줄 화면, 정차 중이면 탭 화면 (운전자 주의 분산 최소화). */
@Composable
fun RoadOfficeScreen(graph: AppGraph) {
    val gear by graph.signals.gear.collectAsState()
    val speed by graph.signals.speedKmh.collectAsState()
    val driving = (gear != Gear.PARK && gear != Gear.UNKNOWN) || (speed ?: 0f) > 3f
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Active, onPrimary = HyundaiBlue, surface = HyundaiBlue, onSurface = Color.White,
            onSurfaceVariant = Sub, outline = Sub, background = HyundaiBlue, onBackground = Color.White,
        ),
        typography = Typography(bodyLarge = TextStyle(fontSize = 24.sp), bodySmall = TextStyle(fontSize = 18.sp)),
    ) {
        Box(Modifier.fillMaxSize().background(HyundaiBlue)) {
            if (driving) DrivingView(graph) else ParkedView(graph)
        }
    }
}

@Composable
private fun DrivingView(graph: AppGraph) {
    val trip by graph.activeTrip.collectAsState()
    val odo by graph.signals.odometerKm.collectAsState()
    val km = trip?.startOdometerKm?.let { start -> ((odo ?: start) - start).coerceAtLeast(0.0) } ?: 0.0
    Column(Modifier.fillMaxSize().padding(48.dp), verticalArrangement = Arrangement.Center) {
        Text("워크마일 · 운행 기록 중", color = Active, fontSize = 32.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        Text("%.1f km".format(km), color = Color.White, fontSize = 96.sp, fontWeight = FontWeight.Bold)
        val guest = rememberGuest(graph)
        trip?.destinationName?.let { Text(if (guest) "→ 업무 방문지" else "→ $it", color = Color.White, fontSize = 36.sp) }
        val listening by graph.listening.collectAsState()
        val debrief by graph.pendingDebrief.collectAsState()
        if (listening) Text("🎙 듣고 있어요… ${if (guest) "" else debrief?.clientName ?: ""} 미팅 이야기를 말씀해 주세요", color = Active, fontSize = 30.sp)
        val brief by graph.lastBrief.collectAsState()
        if (!guest) brief?.let {
            Spacer(Modifier.height(20.dp))
            Text("🔊 $it", color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                modifier = Modifier.clip(RoundedCornerShape(14.dp)).background(Card).padding(horizontal = 24.dp, vertical = 16.dp))
        }
        if (guest) {
            Spacer(Modifier.height(16.dp))
            Text("동승자 탑승 중 · 거래처·상담 내용은 소리 내 읽지 않아요", color = Warn, fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(24.dp))
        Text("주행 중에는 조작할 수 없어요. 정차하면 운행 목록이 열립니다.", color = Sub, fontSize = 24.sp)
    }
}

private enum class Tab(val label: String) { TODAY("오늘 운행"), NOTES("상담 일지"), WORKMILE("워크마일"), CLIENTS("거래처"), SIGNALS("신호") }

@Composable
private fun ParkedView(graph: AppGraph) {
    var tab by remember { mutableStateOf(Tab.TODAY) }
    val closing by graph.dayClose.collectAsState()
    Column(Modifier.fillMaxSize().padding(32.dp)) {
        // 하루 마감 중에는 탭을 숨긴다 (눌러도 반응 없는 탭이 남지 않게)
        if (closing) { DayClosePanel(graph); return@Column }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("로드오피스", color = Color.White, fontSize = 36.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(32.dp))
            Tab.entries.forEach { t ->
                val on = t == tab
                Text(
                    t.label,
                    color = if (on) HyundaiBlue else Color.White,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .padding(end = 12.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(if (on) Active else Card)
                        .clickable { tab = t }
                        .padding(horizontal = 22.dp, vertical = 12.dp),
                )
            }
            Spacer(Modifier.weight(1f))
            PrivacyChip(graph)
        }
        Spacer(Modifier.height(16.dp))
        // 배너는 한 번에 하나 — 회고가 먼저, 주유·충전은 한 줄로 접는다
        val debrief by graph.pendingDebrief.collectAsState()
        DebriefBanner(graph)
        RefillBanner(graph, collapsed = debrief != null)
        Spacer(Modifier.height(8.dp))
        when (tab) {
            Tab.TODAY -> TodayTab(graph)
            Tab.NOTES -> NotesTab(graph)
            Tab.WORKMILE -> WorkMileTab(graph)
            Tab.CLIENTS -> ClientsTab(graph)
            Tab.SIGNALS -> SignalsTab(graph)
        }
    }
}

internal fun dayRange(): Pair<Long, Long> {
    val c = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
    val from = c.timeInMillis
    c.add(Calendar.DAY_OF_MONTH, 1)
    return from to c.timeInMillis
}

internal fun monthRange(): Pair<Long, Long> {
    val c = Calendar.getInstance().apply { set(Calendar.DAY_OF_MONTH, 1); set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
    val from = c.timeInMillis
    c.add(Calendar.MONTH, 1)
    return from to c.timeInMillis
}

private val hm = SimpleDateFormat("HH:mm", Locale.KOREA)

@Composable
private fun TodayTab(graph: AppGraph) {
    val (from, to) = remember { dayRange() }
    val trips by graph.db.trips().observeBetween(from, to).collectAsState(initial = emptyList())
    val business = trips.filter { it.purpose == Purpose.BUSINESS.name }.sumOf { it.distanceKm }
    val guest = rememberGuest(graph)
    // 퇴근 시각 전에는 마감 버튼을 눈에 덜 띄게
    val evening = Calendar.getInstance().get(Calendar.HOUR_OF_DAY) >= graph.dayEndHour
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Stat("오늘 운행", "${trips.size}건")
        Stat("업무 거리", "%.1f km".format(business))
        Stat("미분류", "${trips.count { it.purpose == Purpose.UNKNOWN.name }}건", if (trips.any { it.purpose == Purpose.UNKNOWN.name }) Warn else Color.White)
        if (evening) Button(
            onClick = { graph.openDayClose() },
            colors = ButtonDefaults.buttonColors(containerColor = Active, contentColor = HyundaiBlue),
            modifier = Modifier.height(96.dp),
        ) { Text("오늘 마감 30초", fontSize = 26.sp, fontWeight = FontWeight.Bold) }
        else OutlinedButton(onClick = { graph.openDayClose() }, modifier = Modifier.height(72.dp)) { Text("오늘 마감", fontSize = 24.sp, color = Color.White) }
    }
    Spacer(Modifier.height(20.dp))
    if (trips.isEmpty()) {
        Text("아직 오늘 기록된 운행이 없어요. 출발하면 자동으로 기록됩니다.", color = Sub, fontSize = 24.sp)
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items(trips, key = { it.id }) { t -> TripRow(t, guest) { p -> graph.setPurpose(t.id, p) } }
    }
}

@Composable
internal fun TripRow(t: TripEntity, guest: Boolean = false, onPurpose: (Purpose) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Card).padding(18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("${hm.format(Date(t.startAt))} – ${hm.format(Date(t.endAt))}  ·  %.1f km".format(t.distanceKm), color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Text(
                listOfNotNull(if (guest && t.destinationName != null) "업무 방문 (동승자 탑승 중 가림)" else t.destinationName ?: "목적지 미상", t.tollFareEstimate?.takeIf { it > 0 }?.let { "통행료 약 ${won(it.toLong())}" }).joinToString("  ·  "),
                color = Sub, fontSize = 22.sp,
            )
        }
        Purpose.entries.forEach { p ->
            val on = t.purpose == p.name
            Text(
                p.label(),
                color = if (on) HyundaiBlue else Color.White,
                fontSize = 22.sp,
                modifier = Modifier
                    .padding(start = 8.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(if (on) (if (p == Purpose.UNKNOWN) Warn else Active) else HyundaiBlue)
                    .clickable { onPurpose(p) }
                    .padding(horizontal = 18.dp, vertical = 16.dp),
            )
        }
    }
}

@Composable
private fun WorkMileTab(graph: AppGraph) {
    val (from, to) = remember { monthRange() }
    val trips by graph.db.trips().observeBetween(from, to).collectAsState(initial = emptyList())
    val refills by graph.db.refills().observeBetween(from, to).collectAsState(initial = emptyList())
    var rate by remember { mutableStateOf(graph.perKmRate.toString()) }
    var mode by remember { mutableStateOf(graph.claimMode) }
    val s = WorkMileSummary.of(
        trips.map { it.distanceKm to Purpose.valueOf(it.purpose) },
        trips.filter { it.purpose == Purpose.BUSINESS.name }.mapNotNull { it.tollFareEstimate },
        rate.toIntOrNull() ?: 0,
        mode,
        refills.filter { it.purpose == Purpose.BUSINESS.name }.sumOf { it.amount ?: 0L },
    )
    Text("이번 달 워크마일", color = Active, fontSize = 28.sp, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(12.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Stat("업무 거리", "%.1f km".format(s.businessKm))
        Stat("업무 비율", "%.0f%%".format(s.businessRatio * 100))
        Stat("예상 청구액", won(s.claimAmount), Active)
        Stat("미분류", "%.1f km".format(s.unknownKm), if (s.unknownKm > 0) Warn else Color.White)
    }
    Spacer(Modifier.height(20.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("유류비 정산 방식", color = Color.White, fontSize = 24.sp)
        Spacer(Modifier.width(16.dp))
        ClaimMode.entries.forEach { m ->
            Chip(m.label, m == mode) { mode = m; graph.claimMode = m }
        }
        Spacer(Modifier.width(32.dp))
        if (mode == ClaimMode.KM_RATE) Text("회사 km 단가(원)", color = Color.White, fontSize = 24.sp)
        Spacer(Modifier.width(16.dp))
        if (mode == ClaimMode.KM_RATE) OutlinedTextField(
            value = rate,
            onValueChange = { v -> rate = v.filter { it.isDigit() }.take(5); rate.toIntOrNull()?.let { graph.perKmRate = it } },
            singleLine = true,
            modifier = Modifier.width(180.dp),
        )
    }
    Spacer(Modifier.height(12.dp))
    Text(
        if (mode == ClaimMode.KM_RATE) "청구액 = 업무 km × 단가 + 업무 운행 예상 통행료. 거리는 차량 주행거리계 기준 · 주유·충전 기록은 참고로만 붙어요(이중 청구 방지)."
        else "청구액 = 업무 주유·충전 실비 + 업무 운행 예상 통행료. 주유·충전은 차량 배터리·연료 상승으로 자동 포착돼요.",
        color = Sub, fontSize = 22.sp,
    )
    if (refills.isNotEmpty()) {
        Spacer(Modifier.height(12.dp))
        Text("이번 달 주유·충전 ${refills.size}건", color = Active, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        refills.take(3).forEach { r -> RefillRow(graph, r) }
    }
    Spacer(Modifier.height(20.dp))
    var name by remember { mutableStateOf(graph.userName) }
    var dept by remember { mutableStateOf(graph.department) }
    var result by remember { mutableStateOf<String?>(null) }
    var header by remember { mutableStateOf(graph.exportHeader) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("상담 활동 CSV 열 이름", color = Color.White, fontSize = 22.sp)
        Spacer(Modifier.width(16.dp))
        HeaderStyle.entries.forEach { h -> Chip(h.label, h == header) { header = h; graph.exportHeader = h } }
        Spacer(Modifier.width(12.dp))
        Text("회사 SFA·CRM 가져오기에 그대로 — 상담 원문은 넣지 않아요", color = Sub, fontSize = 20.sp)
    }
    Spacer(Modifier.height(12.dp))
    val scope = rememberCoroutineScope()
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(value = dept, onValueChange = { dept = it.take(30); graph.department = dept }, singleLine = true, label = { Text("부서") }, modifier = Modifier.width(260.dp))
        Spacer(Modifier.width(12.dp))
        OutlinedTextField(value = name, onValueChange = { name = it.take(20); graph.userName = name }, singleLine = true, label = { Text("성명") }, modifier = Modifier.width(220.dp))
        Spacer(Modifier.width(16.dp))
        Button(
            onClick = { scope.launch { result = graph.exportMonth(from, to).files.joinToString("\n") { "· " + it.name } } },
            colors = ButtonDefaults.buttonColors(containerColor = Active, contentColor = HyundaiBlue),
        ) { Text("이번 달 정산 내보내기", fontSize = 24.sp) }
    }
    result?.let {
        Spacer(Modifier.height(10.dp))
        Text("만들어진 파일 (운행기록부·유류비 청구서·상담 활동 CSV, 정산 PDF):\n$it", color = Color.White, fontSize = 22.sp)
    }
}

@Composable
private fun DebriefBanner(graph: AppGraph) {
    val pending by graph.pendingDebrief.collectAsState()
    val p = pending ?: return
    var text by remember(p.tripId) { mutableStateOf("") }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color(0xFF00597A)).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val guest = rememberGuest(graph)
        Text(if (guest) "방금 미팅 어땠어요?" else "${p.clientName} 미팅 어땠어요?", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(16.dp))
        OutlinedTextField(
            value = text, onValueChange = { text = it.take(2000) }, singleLine = true,
            placeholder = { Text(if (guest) "입력해 두거나, 혼자 탔을 때 말로 남겨요" else "예: 견적 마음에 들어함, 다음 주 화요일 연락") },
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(12.dp))
        if (!guest) {
            Button(onClick = { graph.listenDebrief() }, colors = ButtonDefaults.buttonColors(containerColor = Active, contentColor = HyundaiBlue), modifier = Modifier.height(64.dp)) { Text("🎙 말하기", fontSize = 22.sp) }
            Spacer(Modifier.width(8.dp))
        }
        Button(onClick = { graph.addNote(text) }, colors = ButtonDefaults.buttonColors(containerColor = Active, contentColor = HyundaiBlue), modifier = Modifier.height(64.dp)) { Text("저장", fontSize = 22.sp) }
        Spacer(Modifier.width(8.dp))
        OutlinedButton(onClick = { graph.dismissDebrief() }, modifier = Modifier.height(64.dp)) { Text("나중에", fontSize = 22.sp, color = Color.White) }
    }
}

private val md = SimpleDateFormat("M/d(E) HH:mm", Locale.KOREA)
private val dayOnly = SimpleDateFormat("M/d(E)", Locale.KOREA)

@Composable
private fun NotesTab(graph: AppGraph) {
    if (!PrivacyGate(graph, "상담 일지")) return
    val notes by graph.db.notes().observeAll().collectAsState(initial = emptyList())
    val openTasks = notes.filter { !it.tasksDone }.sumOf { n -> n.tasks.lines().count { it.isNotBlank() } }
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Stat("상담 일지", "${notes.size}건")
        Stat("남은 할 일", "${openTasks}개", if (openTasks > 0) Warn else Color.White)
        Stat("후속 일정", "${notes.count { it.followUpAt != null && !it.tasksDone }}건")
    }
    Spacer(Modifier.height(16.dp))
    if (notes.isEmpty()) Text("거래처 방문 후 차에 다시 타면 \"미팅 어땠어요?\" 하고 물어요. 말한 내용이 여기에 정리됩니다.", color = Sub, fontSize = 24.sp)
    // 새 일지가 맨 위에 들어오면 목록도 맨 위로 (방금 말한 내용이 바로 보이게)
    val listState = rememberLazyListState()
    LaunchedEffect(notes.firstOrNull()?.id) { if (notes.isNotEmpty()) listState.scrollToItem(0) }
    LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items(notes, key = { it.id }) { n -> NoteRow(n, graph) { graph.setTasksDone(n.id, !n.tasksDone) } }
    }
}

@Composable
private fun NoteRow(n: MeetingNoteEntity, graph: AppGraph, onToggle: () -> Unit) {
    var editing by remember(n.id) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Card).padding(18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(n.clientName ?: "거래처 미지정", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            if (n.needsReview && !editing) {
                // 음성·Gleo 로 정리된 일지 — 정차 중에 맞는지 확인 (음성 인식 오류 대비)
                Text("확인 필요 · 음성 정리", color = HyundaiBlue, fontSize = 20.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(end = 12.dp).clip(RoundedCornerShape(12.dp)).background(Warn).padding(horizontal = 12.dp, vertical = 4.dp))
                Chip("맞아요", false) { graph.reviewNote(n.id, n.summary, n.tasks.lines()) }
                Chip("고치기", false) { editing = true }
                Spacer(Modifier.width(12.dp))
            }
            Text(md.format(Date(n.createdAt)), color = Sub, fontSize = 20.sp)
        }
        if (editing) {
            var summary by remember(n.id) { mutableStateOf(n.summary) }
            var tasks by remember(n.id) { mutableStateOf(n.tasks) }
            OutlinedTextField(value = summary, onValueChange = { summary = it.take(200) }, label = { Text("요약") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = tasks, onValueChange = { tasks = it.take(1000) }, label = { Text("할 일 (한 줄에 하나)") }, modifier = Modifier.fillMaxWidth())
            Row(Modifier.padding(top = 8.dp)) {
                Button(onClick = { graph.reviewNote(n.id, summary, tasks.lines()); editing = false }, colors = ButtonDefaults.buttonColors(containerColor = Active, contentColor = HyundaiBlue), modifier = Modifier.height(64.dp)) { Text("저장", fontSize = 22.sp) }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = { editing = false }, modifier = Modifier.height(64.dp)) { Text("취소", fontSize = 22.sp, color = Color.White) }
            }
            return@Column
        }
        Text(n.summary, color = Color.White, fontSize = 23.sp)
        n.tasks.lines().filter { it.isNotBlank() }.forEach { t ->
            Text((if (n.tasksDone) "☑ " else "☐ ") + t, color = if (n.tasksDone) Sub else Warn, fontSize = 22.sp, modifier = Modifier.clickable { onToggle() }.padding(top = 4.dp))
        }
        n.followUpAt?.let { Text("후속 일정: ${dayOnly.format(Date(it))}", color = Active, fontSize = 21.sp, modifier = Modifier.padding(top = 4.dp)) }
        Text("원문: ${n.rawText}", color = Sub, fontSize = 19.sp, modifier = Modifier.padding(top = 6.dp))
    }
}

@Composable
private fun ClientsTab(graph: AppGraph) {
    if (!PrivacyGate(graph, "거래처 목록")) return
    val clients by graph.db.clients().observeAll().collectAsState(initial = emptyList())
    val here by graph.signals.location.collectAsState()
    var name by remember { mutableStateOf("") }
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(value = name, onValueChange = { name = it.take(40) }, singleLine = true, label = { Text("거래처 이름") }, modifier = Modifier.width(420.dp))
        Spacer(Modifier.width(16.dp))
        Button(
            onClick = { if (name.isNotBlank()) { graph.addClientHere(name.trim()); name = "" } },
            colors = ButtonDefaults.buttonColors(containerColor = Active, contentColor = HyundaiBlue),
        ) { Text("현재 위치로 등록", fontSize = 24.sp) }
    }
    Text(if (here == null) "현재 위치를 받지 못했어요 — 이름으로만 인식합니다." else "현재 위치 %.5f, %.5f".format(here!!.lat, here!!.lon), color = Sub, fontSize = 20.sp)
    Spacer(Modifier.height(12.dp))
    TermsEditor(graph)
    Spacer(Modifier.height(16.dp))
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(clients, key = { it.id }) { c -> ClientRow(c) { graph.deleteClient(c.id) } }
    }
}

@Composable
private fun ClientRow(c: ClientEntity, onDelete: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Card).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(c.name, color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Text(if (c.lat != null) "반경 ${c.radiusM.toInt()}m 안에 도착하면 업무 운행" else "위치 없음 — 내비 목적지 이름으로 인식", color = Sub, fontSize = 20.sp)
        }
        Text("삭제", color = Color.White, fontSize = 22.sp, modifier = Modifier.clickable { onDelete() }.padding(12.dp))
    }
}

@Composable
private fun SignalsTab(graph: AppGraph) {
    if (!PrivacyGate(graph, "신호·기록 로그")) return // 로그에 거래처 이름이 남는다
    val odo by graph.signals.odometerKm.collectAsState()
    val gear by graph.signals.gear.collectAsState()
    val speed by graph.signals.speedKmh.collectAsState()
    val navi by graph.signals.naviConnected.collectAsState()
    val loc by graph.signals.location.collectAsState()
    val logs = remember { mutableStateListOf<String>() }
    LaunchedEffect(Unit) { graph.signals.log.collect { logs.add(0, "${SimpleDateFormat("HH:mm:ss", Locale.KOREA).format(Date())}  $it") } }
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Stat("주행거리", odo?.let { "%.1f km".format(it) } ?: "—")
        Stat("기어", gear.name)
        Stat("속도", speed?.let { "%.0f km/h".format(it) } ?: "—")
        Stat("내비", if (navi) "연결됨" else "대기")
        Stat("위치", loc?.let { "%.3f, %.3f".format(it.lat, it.lon) } ?: "—")
    }
    Spacer(Modifier.height(12.dp))
    var showLicense by remember { mutableStateOf(false) }
    Row {
        Button(onClick = { graph.signals.requestRoute("현대차 강남대로사옥", "서울 강남구 강남대로 350", LatLng(37.4959, 127.0293)) }) {
            Text("시험 경로 시작", fontSize = 22.sp)
        }
        Spacer(Modifier.width(12.dp))
        OutlinedButton(onClick = { showLicense = true }, modifier = Modifier.height(56.dp)) { Text("오픈소스 라이선스", fontSize = 22.sp, color = Color.White) }
    }
    if (showLicense) LicenseDialog { showLicense = false }
    LazyColumn { items(logs) { Text(it, color = Color.White, fontSize = 20.sp, modifier = Modifier.padding(vertical = 3.dp)) } }
}

@Composable
internal fun Stat(label: String, value: String, valueColor: Color = Color.White) {
    Column(Modifier.width(280.dp).clip(RoundedCornerShape(14.dp)).background(Card).padding(20.dp)) {
        Text(label, color = Sub, fontSize = 20.sp)
        Text(value, color = valueColor, fontSize = 34.sp, fontWeight = FontWeight.Bold)
    }
}

internal fun Purpose.label() = when (this) {
    Purpose.BUSINESS -> "업무"
    Purpose.PERSONAL -> "개인"
    Purpose.UNKNOWN -> "미분류"
}

internal fun won(v: Long) = NumberFormat.getInstance(Locale.KOREA).format(v) + "원"

/** 제품·용어 사전 — 음성 인식이 틀리기 쉬운 이름을 등록 (거래처 이름은 자동 포함). 누르면 삭제. */
@Composable
private fun TermsEditor(graph: AppGraph) {
    var terms by remember { mutableStateOf(graph.terms) }
    var input by remember { mutableStateOf("") }
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(value = input, onValueChange = { input = it.take(30) }, singleLine = true, label = { Text("제품·용어 사전") }, modifier = Modifier.width(360.dp))
        Spacer(Modifier.width(12.dp))
        Button(
            onClick = { if (input.isNotBlank()) { terms = (terms + input.trim()).distinct(); graph.terms = terms; input = "" } },
            colors = ButtonDefaults.buttonColors(containerColor = Active, contentColor = HyundaiBlue), modifier = Modifier.height(64.dp),
        ) { Text("추가", fontSize = 22.sp) }
        Spacer(Modifier.width(16.dp))
        Text("음성 정리 때 거래처·제품 이름을 이 사전대로 고쳐요", color = Sub, fontSize = 20.sp)
    }
    if (terms.isNotEmpty()) Row(Modifier.padding(top = 8.dp)) {
        terms.take(8).forEach { t -> Chip("$t ✕", false) { terms = terms - t; graph.terms = terms } }
    }
}

/** 오픈소스 라이선스 고지 (SQLCipher 등 고지 의무) — assets/licenses/NOTICE.txt */
@Composable
private fun LicenseDialog(onClose: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val text = remember { runCatching { ctx.assets.open("licenses/NOTICE.txt").bufferedReader().readText() }.getOrDefault("라이선스 파일을 열 수 없습니다.") }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onClose,
        title = { Text("오픈소스 라이선스", fontSize = 28.sp) },
        text = {
            LazyColumn(Modifier.height(520.dp)) { item { Text(text, fontSize = 18.sp) } }
        },
        confirmButton = { androidx.compose.material3.TextButton(onClick = onClose) { Text("닫기", fontSize = 22.sp) } },
    )
}
