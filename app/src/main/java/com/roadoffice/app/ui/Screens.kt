package com.roadoffice.app.ui

import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
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


/** 주행 중이면 한 장 카드, 정차 중이면 왼쪽 메뉴 + 화면 (운전자 주의 분산 최소화). 낮엔 라이트, 밤엔 다크. */
@Composable
fun RoadOfficeScreen(graph: AppGraph) {
    val gear by graph.signals.gear.collectAsState()
    val speed by graph.signals.speedKmh.collectAsState()
    val driving = (gear != Gear.PARK && gear != Gear.UNKNOWN) || (speed ?: 0f) > 3f
    val mode by graph.themeMode.collectAsState()
    val dark = when (mode) { "DAY" -> false; "NIGHT" -> true; else -> isSystemInDarkTheme() }
    val ro = if (dark) DarkRo else LightRo
    val scheme = if (dark) darkColorScheme(
        primary = ro.accent, onPrimary = ro.onAccent, surface = ro.surface, onSurface = ro.ink, surfaceContainerHigh = ro.surface,
        onSurfaceVariant = ro.sub, outline = ro.line, background = ro.bg, onBackground = ro.ink,
    ) else lightColorScheme(
        primary = ro.accent, onPrimary = ro.onAccent, surface = ro.surface, onSurface = ro.ink, surfaceContainerHigh = ro.surface,
        onSurfaceVariant = ro.sub, outline = ro.line, background = ro.bg, onBackground = ro.ink,
    )
    CompositionLocalProvider(LocalRo provides ro) {
        MaterialTheme(colorScheme = scheme, typography = roTypography(), shapes = androidx.compose.material3.Shapes(extraSmall = RoundedCornerShape(18.dp))) {
            Box(Modifier.fillMaxSize().background(ro.bg)) {
                if (driving) DrivingView(graph) else ParkedView(graph)
            }
        }
    }
}

/** 주행 중 — 한눈(2초)에 읽히는 카드 하나. 조작 요소 없음. */
@Composable
private fun DrivingView(graph: AppGraph) {
    val trip by graph.activeTrip.collectAsState()
    val odo by graph.signals.odometerKm.collectAsState()
    val km = trip?.startOdometerKm?.let { start -> ((odo ?: start) - start).coerceAtLeast(0.0) } ?: 0.0
    val guest = rememberGuest(graph)
    val listening by graph.listening.collectAsState()
    val debrief by graph.pendingDebrief.collectAsState()
    val brief by graph.lastBrief.collectAsState()
    Box(Modifier.fillMaxSize().padding(48.dp)) {
        Column(Modifier.align(Alignment.Center).widthIn(max = 1180.dp).fillMaxWidth().roCard(40).padding(horizontal = 64.dp, vertical = 56.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(RoIcons.Car, null, tint = AccentText, modifier = Modifier.size(40.dp))
                Spacer(Modifier.width(14.dp))
                Text("워크마일 · 운행 기록 중", color = AccentText, fontSize = 30.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                if (guest) Badge("동승자 보호 중", RoIcons.Shield)
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text("%.1f".format(km), color = Ink, fontSize = 144.sp, fontWeight = FontWeight.ExtraBold, style = Tnum)
                Text("km", color = Sub, fontSize = 48.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 12.dp, bottom = 26.dp))
            }
            trip?.destinationName?.let {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(RoIcons.Pin, null, tint = Sub, modifier = Modifier.size(38.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(if (guest) "업무 방문지" else it, color = Ink, fontSize = 38.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            if (listening) {
                Spacer(Modifier.height(28.dp))
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Banner).padding(horizontal = 28.dp, vertical = 22.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(64.dp).clip(CircleShape).background(Active), contentAlignment = Alignment.Center) {
                        Icon(RoIcons.Mic, null, tint = OnAccent, modifier = Modifier.size(38.dp))
                    }
                    Spacer(Modifier.width(20.dp))
                    Text("듣고 있어요 · ${if (guest) "" else debrief?.clientName?.let { "$it " } ?: ""}미팅 이야기를 말씀해 주세요", color = Ink, fontSize = 32.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            if (!guest) brief?.let {
                Spacer(Modifier.height(28.dp))
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Field).padding(horizontal = 28.dp, vertical = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(RoIcons.Speaker, null, tint = AccentText, modifier = Modifier.size(44.dp))
                    Spacer(Modifier.width(20.dp))
                    Text(it, color = Ink, fontSize = 36.sp, fontWeight = FontWeight.SemiBold, maxLines = 2)
                }
            }
            if (guest) {
                Spacer(Modifier.height(24.dp))
                Text("거래처·상담 내용은 소리 내 읽지 않아요", color = WarnText, fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        Text("주행 중에는 조작할 수 없어요. 정차하면 운행 목록이 열립니다.", color = Sub, fontSize = 24.sp, modifier = Modifier.align(Alignment.BottomCenter))
    }
}

/** 작은 상태 배지 (주의색). */
@Composable
internal fun Badge(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector? = null) {
    Row(Modifier.clip(RoundedCornerShape(20.dp)).background(Warn).padding(horizontal = 18.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        icon?.let { Icon(it, null, tint = OnWarn, modifier = Modifier.size(26.dp)); Spacer(Modifier.width(8.dp)) }
        Text(text, color = OnWarn, fontSize = 22.sp, fontWeight = FontWeight.Bold)
    }
}

private enum class Tab(val label: String, val title: String) {
    TODAY("오늘 운행", "오늘"), NOTES("상담 일지", "미팅 후 말로 남긴 기록"), WORKMILE("워크마일", "운행 기록 · 월말 정산"),
    CLIENTS("거래처", "거래처 · 용어 사전"), SIGNALS("신호", "차량 신호 · 점검")
}

private fun Tab.icon() = when (this) {
    Tab.TODAY -> RoIcons.Car
    Tab.NOTES -> RoIcons.Notes
    Tab.WORKMILE -> RoIcons.Receipt
    Tab.CLIENTS -> RoIcons.Store
    Tab.SIGNALS -> RoIcons.Tune
}

private val todayFmt = SimpleDateFormat("M월 d일 EEEE", Locale.KOREA)

@Composable
private fun ParkedView(graph: AppGraph) {
    var tab by remember { mutableStateOf(Tab.TODAY) }
    val closing by graph.dayClose.collectAsState()
    if (closing) { Column(Modifier.fillMaxSize().padding(32.dp)) { DayClosePanel(graph) }; return } // 마감 중엔 메뉴를 숨긴다
    Row(Modifier.fillMaxSize()) {
        NavRail(tab) { tab = it }
        Column(Modifier.weight(1f).fillMaxHeight().padding(horizontal = 36.dp, vertical = 28.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (tab == Tab.TODAY) "${tab.title} · ${todayFmt.format(Date())}" else tab.title, color = Ink, fontSize = 34.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                PrivacyChip(graph)
            }
            Spacer(Modifier.height(18.dp))
            // 알림 카드는 한 번에 하나 — 회고가 먼저, 주유·충전은 한 줄로 접는다
            val debrief by graph.pendingDebrief.collectAsState()
            DebriefBanner(graph)
            RefillBanner(graph, collapsed = debrief != null)
            Spacer(Modifier.height(12.dp))
            when (tab) {
                Tab.TODAY -> TodayTab(graph)
                Tab.NOTES -> NotesTab(graph)
                Tab.WORKMILE -> WorkMileTab(graph)
                Tab.CLIENTS -> ClientsTab(graph)
                Tab.SIGNALS -> SignalsTab(graph)
            }
        }
    }
}

/** 왼쪽 세로 메뉴 — 운전석(왼쪽)에서 팔을 덜 뻗게, 큰 아이콘 + 이름. 점검용 '신호'는 맨 아래. */
@Composable
private fun NavRail(tab: Tab, onTab: (Tab) -> Unit) {
    Row(Modifier.fillMaxHeight()) {
        Column(Modifier.width(168.dp).fillMaxHeight().background(Rail).padding(vertical = 28.dp, horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("로드오피스", color = AccentText, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold)
            Text("RoadOffice", color = Sub, fontSize = 15.sp)
            Spacer(Modifier.height(28.dp))
            Tab.entries.filter { it != Tab.SIGNALS }.forEach { RailItem(it, it == tab) { onTab(it) } }
            Spacer(Modifier.weight(1f))
            RailItem(Tab.SIGNALS, tab == Tab.SIGNALS, small = true) { onTab(Tab.SIGNALS) }
        }
    }
}

@Composable
private fun RailItem(t: Tab, on: Boolean, small: Boolean = false, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 6.dp).clip(RoundedCornerShape(22.dp))
            .background(if (on) Strong else Color.Transparent).clickable(onClick = onClick)
            .padding(vertical = if (small) 12.dp else 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(t.icon(), null, tint = if (on) OnStrong else Sub, modifier = Modifier.size(if (small) 30.dp else 40.dp))
        Spacer(Modifier.height(6.dp))
        Text(t.label, color = if (on) OnStrong else Ink, fontSize = if (small) 18.sp else 22.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.SemiBold)
    }
}

internal fun dayRange(): Pair<Long, Long> {
    val c = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
    val from = c.timeInMillis
    c.add(Calendar.DAY_OF_MONTH, 1)
    return from to c.timeInMillis
}

/** offset 0 = 이번 달, -1 = 지난달 */
internal fun monthRange(offset: Int = 0): Pair<Long, Long> {
    val c = Calendar.getInstance().apply { set(Calendar.DAY_OF_MONTH, 1); set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0); add(Calendar.MONTH, offset) }
    val from = c.timeInMillis
    c.add(Calendar.MONTH, 1)
    return from to c.timeInMillis
}

private val hm = SimpleDateFormat("HH:mm", Locale.KOREA)

@Composable
private fun TodayTab(graph: AppGraph) {
    val (from, to) = remember { dayRange() }
    // 처음 읽기 전에는 빈 목록·"0건"을 보여 주지 않는다 (불러오는 동안 0건이 깜빡이는 문제)
    val tripsFlow = remember(from, to) { graph.db.trips().observeBetween(from, to) }
    val loadedTrips by tripsFlow.collectAsState(initial = null as List<TripEntity>?)
    val trips = loadedTrips ?: return
    val business = trips.filter { it.purpose == Purpose.BUSINESS.name }.sumOf { it.distanceKm }
    val guest = rememberGuest(graph)
    // 퇴근 시각 전에는 마감 버튼을 눈에 덜 띄게
    val evening = Calendar.getInstance().get(Calendar.HOUR_OF_DAY) >= graph.dayEndHour
    Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        Column(Modifier.weight(1.7f)) {
            // 히어로 카드 하나 — 오늘 업무 거리를 크게, 나머지는 한 줄로
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(32.dp))
                    .background(Brush.linearGradient(listOf(Banner, Card)))
                    .padding(horizontal = 36.dp, vertical = 30.dp),
            ) {
                Text("오늘 업무 거리", color = Sub, fontSize = 22.sp, fontWeight = FontWeight.Medium)
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("%.1f".format(business), color = Ink, fontSize = 76.sp, fontWeight = FontWeight.ExtraBold, style = Tnum)
                    Text("km", color = Sub, fontSize = 32.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 10.dp, bottom = 14.dp))
                    Spacer(Modifier.weight(1f))
                    MiniStat("오늘 운행", "${trips.size}건")
                    Spacer(Modifier.width(36.dp))
                    MiniStat("미분류", "${trips.count { it.purpose == Purpose.UNKNOWN.name }}건", if (trips.count { it.purpose == Purpose.UNKNOWN.name } > 0) WarnText else Ink)
                }
            }
            Spacer(Modifier.height(24.dp))
            SectionTitle("운행 기록", "업무·개인을 누르면 월말 정산에 바로 반영돼요")
            if (trips.isEmpty()) Empty("아직 오늘 기록된 운행이 없어요. 출발하면 자동으로 기록됩니다.")
            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(trips, key = { it.id }) { t -> TripRow(t, guest) { p -> graph.setPurpose(t.id, p) } }
            }
        }
        Column(Modifier.weight(1f)) {
            TodoPanel(graph, guest)
            Spacer(Modifier.height(16.dp))
            if (evening) Button(
                onClick = { graph.openDayClose() },
                colors = ButtonDefaults.buttonColors(containerColor = Active, contentColor = OnAccent),
                modifier = Modifier.fillMaxWidth().height(96.dp),
            ) { Text("오늘 마감 30초", fontSize = 28.sp, fontWeight = FontWeight.Bold) }
            else SoftButton(onClick = { graph.openDayClose() }, modifier = Modifier.fillMaxWidth().height(80.dp)) {
                Text("오늘 마감", fontSize = 24.sp, color = Ink, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/** 오늘 화면 오른쪽 — 남은 할 일과 가까운 후속 일정. 동승자가 있으면 개수만. */
@Composable
private fun TodoPanel(graph: AppGraph, guest: Boolean) {
    val notes by graph.db.notes().observeAll().collectAsState(initial = emptyList())
    val open = notes.filter { !it.tasksDone && it.tasks.isNotBlank() }
    val tasks = open.flatMap { n -> n.tasks.lines().filter { it.isNotBlank() }.map { n to it } }
    Column(Modifier.fillMaxWidth().roCard().padding(24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(RoIcons.Check, null, tint = AccentText, modifier = Modifier.size(30.dp))
            Spacer(Modifier.width(10.dp))
            Text("남은 할 일", color = Ink, fontSize = 26.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text("${tasks.size}개", color = if (tasks.isEmpty()) Sub else WarnText, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(12.dp))
        when {
            tasks.isEmpty() -> Text("남은 할 일이 없어요.", color = Sub, fontSize = 22.sp)
            guest -> Text("동승자 탑승 중이라 내용은 가렸어요", color = Sub, fontSize = 22.sp)
            else -> tasks.take(4).forEach { (n, t) ->
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp).clip(RoundedCornerShape(14.dp)).background(Field).clickable { graph.setTasksDone(n.id, true) }.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(RoIcons.Box, null, tint = Sub, modifier = Modifier.size(28.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(t, color = Ink, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                        Text(listOfNotNull(n.clientName, n.followUpAt?.let { "후속 " + dayOnly.format(Date(it)) }).joinToString(" · "), color = Sub, fontSize = 18.sp, maxLines = 1)
                    }
                }
            }
        }
        if (!guest && tasks.size > 4) Text("외 ${tasks.size - 4}개 · 상담 일지에서 볼 수 있어요", color = Sub, fontSize = 18.sp, modifier = Modifier.padding(top = 6.dp))
    }
}

@Composable
private fun MiniStat(label: String, value: String, color: Color = Ink) {
    Column(Modifier.padding(bottom = 12.dp), horizontalAlignment = Alignment.End) {
        Text(label, color = Sub, fontSize = 20.sp, fontWeight = FontWeight.Medium)
        Text(value, color = color, fontSize = 36.sp, fontWeight = FontWeight.Bold, style = Tnum)
    }
}

@Composable
internal fun SectionTitle(title: String, hint: String? = null) {
    Row(Modifier.padding(bottom = 12.dp), verticalAlignment = Alignment.Bottom) {
        Text(title, color = Ink, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        hint?.let { Spacer(Modifier.width(14.dp)); Text(it, color = Sub, fontSize = 19.sp) }
    }
}

@Composable
internal fun Empty(text: String) {
    Text(text, color = Sub, fontSize = 23.sp, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Field).padding(28.dp))
}

@Composable
internal fun TripRow(t: TripEntity, guest: Boolean = false, onPurpose: (Purpose) -> Unit) {
    Row(
        Modifier.fillMaxWidth().roCard().padding(horizontal = 24.dp, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("${hm.format(Date(t.startAt))} – ${hm.format(Date(t.endAt))}  ·  %.1f km".format(t.distanceKm), color = Ink, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Text(
                listOfNotNull(if (guest && t.destinationName != null) "업무 방문 (동승자 탑승 중 가림)" else t.destinationName ?: "목적지 미상", t.tollFareEstimate?.takeIf { it > 0 }?.let { "통행료 약 ${won(it.toLong())}" }).joinToString("  ·  "),
                color = Sub, fontSize = 21.sp,
            )
        }
        // 업무·개인·미분류 — 붙어 있는 세그먼트 버튼
        Row(Modifier.clip(CircleShape).background(Field).padding(5.dp)) {
            Purpose.entries.forEach { p ->
                val on = t.purpose == p.name
                val unknown = p == Purpose.UNKNOWN
                Text(
                    p.label(),
                    color = if (on) (if (unknown) OnWarn else OnStrong) else Sub,
                    fontSize = 22.sp,
                    fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(if (on) (if (unknown) Warn else Strong) else Color.Transparent)
                        .clickable { onPurpose(p) }
                        .padding(horizontal = 22.dp, vertical = 16.dp),
                )
            }
        }
    }
}

@Composable
private fun WorkMileTab(graph: AppGraph) {
    // 월 정산은 보통 다음 달 초에 한다 — 첫 주에는 지난달이 기본
    var offset by remember { mutableStateOf(if (Calendar.getInstance().get(Calendar.DAY_OF_MONTH) <= 7) -1 else 0) }
    val (from, to) = remember(offset) { monthRange(offset) }
    // 불러오기 전 0km·0원이 깜빡이지 않게 (첫 값이 올 때까지 그리지 않음)
    val tripsFlow = remember(from, to) { graph.db.trips().observeBetween(from, to) }
    val refillsFlow = remember(from, to) { graph.db.refills().observeBetween(from, to) }
    val loadedTrips by tripsFlow.collectAsState(initial = null as List<TripEntity>?)
    val refills by refillsFlow.collectAsState(initial = emptyList())
    val trips = loadedTrips ?: return
    var rate by remember { mutableStateOf(graph.perKmRate.toString()) }
    var mode by remember { mutableStateOf(graph.claimMode) }
    val s = WorkMileSummary.of(
        trips.map { it.distanceKm to Purpose.valueOf(it.purpose) },
        trips.filter { it.purpose == Purpose.BUSINESS.name }.mapNotNull { it.tollFareEstimate },
        rate.toIntOrNull() ?: 0,
        mode,
        refills.filter { it.purpose == Purpose.BUSINESS.name }.sumOf { it.amount ?: 0L },
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("${SimpleDateFormat("M월", Locale.KOREA).format(Date(from))} 워크마일", color = AccentText, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(20.dp))
        Chip("지난달", offset == -1) { offset = -1 }
        Chip("이번 달", offset == 0) { offset = 0 }
    }
    Spacer(Modifier.height(12.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Stat("업무 거리", "%.1f km".format(s.businessKm), modifier = Modifier.weight(1f))
        Stat("업무 비율", "%.0f%%".format(s.businessRatio * 100), modifier = Modifier.weight(1f))
        Stat("예상 청구액", won(s.claimAmount), AccentText, modifier = Modifier.weight(1f))
        Stat("미분류", "%.1f km".format(s.unknownKm), if (s.unknownKm > 0) WarnText else Ink, modifier = Modifier.weight(1f))
    }
    Spacer(Modifier.height(20.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        Column(Modifier.weight(1f).roCard().padding(26.dp)) {
            SectionTitle("정산 설정")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("유류비 정산 방식", color = Ink, fontSize = 24.sp)
                Spacer(Modifier.width(16.dp))
                ClaimMode.entries.forEach { m ->
                    Chip(m.label, m == mode) { mode = m; graph.claimMode = m }
                }
            }
            if (mode == ClaimMode.KM_RATE) Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("회사 km 단가(원)", color = Ink, fontSize = 24.sp)
                Spacer(Modifier.width(16.dp))
                OutlinedTextField(
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
                color = Sub, fontSize = 20.sp,
            )
        }
        Column(Modifier.weight(1f).roCard().padding(26.dp)) {
            SectionTitle("정산 파일 만들기", "운행기록부·유류비 청구서·상담 활동 CSV·정산 PDF")
            var name by remember { mutableStateOf(graph.userName) }
            var dept by remember { mutableStateOf(graph.department) }
            var result by remember { mutableStateOf<String?>(null) }
            var header by remember { mutableStateOf(graph.exportHeader) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("상담 활동 CSV 열 이름", color = Ink, fontSize = 22.sp)
                Spacer(Modifier.width(16.dp))
                HeaderStyle.entries.forEach { h -> Chip(h.label, h == header) { header = h; graph.exportHeader = h } }
            }
            Text("회사 SFA·CRM 가져오기에 그대로 — 상담 원문은 넣지 않아요", color = Sub, fontSize = 19.sp, modifier = Modifier.padding(top = 6.dp))
            Spacer(Modifier.height(12.dp))
            val scope = rememberCoroutineScope()
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(value = dept, onValueChange = { dept = it.take(30); graph.department = dept }, singleLine = true, label = { Text("부서") }, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(12.dp))
                OutlinedTextField(value = name, onValueChange = { name = it.take(20); graph.userName = name }, singleLine = true, label = { Text("성명") }, modifier = Modifier.weight(1f))
            }
            Spacer(Modifier.height(16.dp))
            run {
                Button(
                    modifier = Modifier.fillMaxWidth().height(76.dp),
                    onClick = { scope.launch { result = graph.exportMonth(from, to).files.joinToString("\n") { "· " + it.name } } },
                    colors = ButtonDefaults.buttonColors(containerColor = Active, contentColor = OnAccent),
                ) { Text("정산 파일 내보내기", fontSize = 26.sp, fontWeight = FontWeight.Bold) }
            }
            result?.let {
                Spacer(Modifier.height(10.dp))
                Text("만들어진 파일 (운행기록부·유류비 청구서·상담 활동 CSV, 정산 PDF):\n$it", color = Ink, fontSize = 22.sp)
            }
        }
    }
    // 주유·충전 행은 금액 입력칸까지 있어 전체 폭으로
    if (refills.isNotEmpty()) {
        Spacer(Modifier.height(20.dp))
        SectionTitle("주유·충전 ${refills.size}건", "차량 배터리·연료 상승으로 자동 포착")
        refills.take(3).forEach { r -> RefillRow(graph, r) }
    }
}

@Composable
private fun DebriefBanner(graph: AppGraph) {
    val pending by graph.pendingDebrief.collectAsState()
    val p = pending ?: return
    var text by remember(p.tripId) { mutableStateOf("") }
    val guest = rememberGuest(graph)
    // 음성이 먼저 — 큰 마이크 버튼, 글자 입력은 보조
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Banner).padding(horizontal = 24.dp, vertical = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (!guest) {
            Button(
                onClick = { graph.listenDebrief() }, colors = ButtonDefaults.buttonColors(containerColor = Active, contentColor = OnAccent),
                modifier = Modifier.height(88.dp),
            ) {
                Icon(RoIcons.Mic, null, modifier = Modifier.size(36.dp)); Spacer(Modifier.width(8.dp))
                Text("말하기", fontSize = 26.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(20.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(if (guest) "방금 미팅 어땠어요?" else "${p.clientName} 미팅 어땠어요?", color = Ink, fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = text, onValueChange = { text = it.take(2000) }, singleLine = true,
                placeholder = { Text(if (guest) "입력해 두거나, 혼자 탔을 때 말로 남겨요" else "예: 견적 마음에 들어함, 다음 주 화요일 연락") },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(Modifier.width(16.dp))
        SoftButton(onClick = { graph.addNote(text) }, modifier = Modifier.height(72.dp)) { Text("저장", fontSize = 24.sp, fontWeight = FontWeight.SemiBold) }
        Spacer(Modifier.width(10.dp))
        SoftButton(onClick = { graph.dismissDebrief() }, modifier = Modifier.height(72.dp)) { Text("나중에", fontSize = 24.sp, color = Ink) }
    }
}

internal val md = SimpleDateFormat("M/d(E) HH:mm", Locale.KOREA)
internal val dayOnly = SimpleDateFormat("M/d(E)", Locale.KOREA)

@Composable
private fun NotesTab(graph: AppGraph) {
    if (!PrivacyGate(graph, "상담 일지")) return
    val notesFlow = remember { graph.db.notes().observeAll() }
    val loadedNotes by notesFlow.collectAsState(initial = null as List<MeetingNoteEntity>?)
    val notes = loadedNotes ?: return // 불러오는 동안 "0건"·빈 안내를 깜빡이지 않게
    val openTasks = notes.filter { !it.tasksDone }.sumOf { n -> n.tasks.lines().count { it.isNotBlank() } }
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Stat("상담 일지", "${notes.size}건", modifier = Modifier.weight(1f))
        Stat("남은 할 일", "${openTasks}개", if (openTasks > 0) WarnText else Ink, modifier = Modifier.weight(1f))
        Stat("후속 일정", "${notes.count { it.followUpAt != null && !it.tasksDone }}건", modifier = Modifier.weight(1f))
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
    Column(Modifier.fillMaxWidth().roCard().padding(horizontal = 26.dp, vertical = 20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(n.clientName ?: "거래처 미지정", color = Ink, fontSize = 26.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            if (n.needsReview && !editing) {
                // 음성·Gleo 로 정리된 일지 — 정차 중에 맞는지 확인 (음성 인식 오류 대비)
                Text("확인 필요 · 음성 정리", color = OnWarn, fontSize = 20.sp, fontWeight = FontWeight.SemiBold,
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
                Button(onClick = { graph.reviewNote(n.id, summary, tasks.lines()); editing = false }, colors = ButtonDefaults.buttonColors(containerColor = Active, contentColor = OnAccent), modifier = Modifier.height(64.dp)) { Text("저장", fontSize = 22.sp) }
                Spacer(Modifier.width(8.dp))
                SoftButton(onClick = { editing = false }, modifier = Modifier.height(64.dp)) { Text("취소", fontSize = 22.sp, color = Ink) }
            }
            return@Column
        }
        Spacer(Modifier.height(4.dp))
        Text(n.summary, color = Ink, fontSize = 24.sp)
        val tasks = n.tasks.lines().filter { it.isNotBlank() }
        if (tasks.isNotEmpty() || n.followUpAt != null) Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            tasks.forEach { t ->
                Row(
                    Modifier.padding(end = 10.dp).clip(RoundedCornerShape(14.dp)).background(Field).clickable { onToggle() }.padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(if (n.tasksDone) RoIcons.Check else RoIcons.Box, null, tint = if (n.tasksDone) Sub else WarnText, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(t, color = if (n.tasksDone) Sub else Ink, fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            n.followUpAt?.let {
                Row(Modifier.clip(RoundedCornerShape(14.dp)).padding(horizontal = 6.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(RoIcons.Calendar, null, tint = AccentText, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("후속 일정 ${dayOnly.format(Date(it))}", color = AccentText, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        Text("원문 · ${n.rawText}", color = Sub, fontSize = 18.sp, maxLines = 1, modifier = Modifier.padding(top = 10.dp))
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
            colors = ButtonDefaults.buttonColors(containerColor = Active, contentColor = OnAccent),
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
    Row(Modifier.fillMaxWidth().roCard().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(c.name, color = Ink, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Text(if (c.lat != null) "반경 ${c.radiusM.toInt()}m 안에 도착하면 업무 운행" else "위치 없음 — 내비 목적지 이름으로 인식", color = Sub, fontSize = 20.sp)
        }
        Text("삭제", color = Ink, fontSize = 22.sp, modifier = Modifier.clickable { onDelete() }.padding(12.dp))
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
        Stat("주행거리", odo?.let { "%.1f km".format(it) } ?: "—", modifier = Modifier.weight(1f))
        Stat("기어", gear.name, modifier = Modifier.weight(1f))
        Stat("속도", speed?.let { "%.0f km/h".format(it) } ?: "—", modifier = Modifier.weight(1f))
        Stat("내비", if (navi) "연결됨" else "대기", modifier = Modifier.weight(1f))
        Stat("위치", loc?.let { "%.3f, %.3f".format(it.lat, it.lon) } ?: "—", modifier = Modifier.weight(1f))
    }
    Spacer(Modifier.height(12.dp))
    var showLicense by remember { mutableStateOf(false) }
    Row {
        Button(onClick = { graph.signals.requestRoute("현대차 강남대로사옥", "서울 강남구 강남대로 350", LatLng(37.4959, 127.0293)) }) {
            Text("시험 경로 시작", fontSize = 22.sp)
        }
        Spacer(Modifier.width(12.dp))
        val mode by graph.themeMode.collectAsState()
        Text("화면 색", color = Ink, fontSize = 22.sp, modifier = Modifier.align(Alignment.CenterVertically).padding(end = 12.dp))
        listOf("AUTO" to "자동(차량 주·야간)", "DAY" to "주간", "NIGHT" to "야간").forEach { (k, l) -> Chip(l, mode == k) { graph.setThemeMode(k) } }
        Spacer(Modifier.width(12.dp))
        SoftButton(onClick = { showLicense = true }, modifier = Modifier.height(56.dp)) { Text("오픈소스 라이선스", fontSize = 22.sp, color = Ink) }
    }
    if (showLicense) LicenseDialog { showLicense = false }
    LazyColumn { items(logs) { Text(it, color = Ink, fontSize = 20.sp, modifier = Modifier.padding(vertical = 3.dp)) } }
}

@Composable
internal fun Stat(label: String, value: String, valueColor: Color = Ink, modifier: Modifier = Modifier.width(280.dp)) {
    Column(modifier.roCard(24).padding(horizontal = 26.dp, vertical = 22.dp)) {
        Text(label, color = Sub, fontSize = 20.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(6.dp))
        Text(value, color = valueColor, fontSize = 42.sp, fontWeight = FontWeight.ExtraBold, style = Tnum)
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
            colors = ButtonDefaults.buttonColors(containerColor = Active, contentColor = OnAccent), modifier = Modifier.height(64.dp),
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
