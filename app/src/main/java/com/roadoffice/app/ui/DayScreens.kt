package com.roadoffice.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
import com.roadoffice.app.data.RefillEntity
import com.roadoffice.app.workmile.EnergyKind
import com.roadoffice.app.workmile.Purpose
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val BannerBlue = Color(0xFF00597A)
private val hm = SimpleDateFormat("HH:mm", Locale.KOREA)
private val mdhm = SimpleDateFormat("M/d HH:mm", Locale.KOREA)

/** 동승자 보호가 켜져 있고 운전석 외 좌석에 사람이 있는가 (좌석 센서 기준). */
@Composable
internal fun rememberGuest(graph: AppGraph): Boolean {
    val privacy by graph.privacy.collectAsState()
    val cabin by graph.signals.cabin.collectAsState()
    return privacy && cabin.guestOnBoard
}

@Composable
internal fun Chip(label: String, on: Boolean, onClick: () -> Unit) {
    Text(
        label,
        color = if (on) HyundaiBlue else Color.White,
        fontSize = 22.sp,
        modifier = Modifier
            .padding(end = 8.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(if (on) Active else Card)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

/** 상단 우측 — 동승자 보호 켬/끔과 지금 상태. */
@Composable
internal fun PrivacyChip(graph: AppGraph) {
    val privacy by graph.privacy.collectAsState()
    val cabin by graph.signals.cabin.collectAsState()
    val text = when {
        !privacy -> "동승자 보호 꺼짐"
        !cabin.known -> "동승자 보호 · 좌석 센서 없음"
        cabin.guestOnBoard -> "동승자 탑승 · 보호 중"
        else -> "동승자 보호 켬"
    }
    Text(
        text,
        color = if (privacy && cabin.guestOnBoard) HyundaiBlue else Color.White,
        fontSize = 22.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(if (privacy && cabin.guestOnBoard) Warn else Card)
            .clickable { graph.setPrivacy(!privacy) }
            .padding(horizontal = 18.dp, vertical = 10.dp),
    )
}

/** 동승자가 있으면 거래처·상담 화면을 가리고, 운전자가 "잠깐 보기"를 누를 때만 연다. 열어도 되면 true. */
@Composable
internal fun PrivacyGate(graph: AppGraph, what: String): Boolean {
    val cabin by graph.signals.cabin.collectAsState()
    val guest = rememberGuest(graph)
    var revealed by remember(cabin.occupied) { mutableStateOf(false) }
    if (!guest || revealed) return true
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Card).padding(32.dp)) {
        Text("동승자가 있어 $what 화면을 가렸어요", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text("좌석 센서로 옆·뒷자리 탑승을 확인했어요. 고객 정보가 다른 고객에게 보이지 않도록 브리핑·상담 내용은 소리로도 읽지 않아요.", color = Sub, fontSize = 22.sp)
        Spacer(Modifier.height(16.dp))
        Button(onClick = { revealed = true }, colors = ButtonDefaults.buttonColors(containerColor = Active, contentColor = HyundaiBlue)) { Text("잠깐 보기", fontSize = 22.sp) }
    }
    return false
}

/** 금액 입력칸 + 저장. 금액은 영수증·결제 앱을 보고 사용자가 넣는다. */
@Composable
private fun AmountEditor(graph: AppGraph, r: RefillEntity) {
    var text by remember(r.id, r.amount) { mutableStateOf(r.amount?.toString() ?: "") }
    OutlinedTextField(
        value = text,
        onValueChange = { v -> text = v.filter { it.isDigit() }.take(7) },
        singleLine = true,
        placeholder = { Text("금액(원)") },
        modifier = Modifier.width(200.dp),
    )
    Spacer(Modifier.width(8.dp))
    Button(onClick = { graph.setRefillAmount(r.id, text.toLongOrNull()) }, colors = ButtonDefaults.buttonColors(containerColor = Active, contentColor = HyundaiBlue)) {
        Text("저장", fontSize = 22.sp)
    }
}

private fun RefillEntity.title() = "${EnergyKind.valueOf(kind).label} ${fromPct}% → ${toPct}%"

@Composable
internal fun RefillRow(graph: AppGraph, r: RefillEntity) {
    Row(
        Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(12.dp)).background(Card).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("${mdhm.format(Date(r.startedAt))}  ·  ${r.title()}", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Text((r.place ?: "장소 미상") + "  ·  " + (r.amount?.let { won(it) } ?: "금액 미입력"), color = if (r.amount == null) Warn else Sub, fontSize = 20.sp)
        }
        listOf(Purpose.BUSINESS, Purpose.PERSONAL).forEach { p -> Chip(p.label(), r.purpose == p.name) { graph.setRefillPurpose(r.id, p) } }
        Spacer(Modifier.width(8.dp))
        AmountEditor(graph, r)
    }
}

/** 주차 중 충전·주유가 포착되면 바로 뜨는 배너 — 금액만 넣으면 정산에 들어간다. */
@Composable
internal fun RefillBanner(graph: AppGraph) {
    val (from, to) = remember { dayRange() }
    val refills by graph.db.refills().observeBetween(from, to).collectAsState(initial = emptyList())
    var hidden by remember { mutableStateOf(setOf<Long>()) }
    val r = refills.firstOrNull { it.amount == null && it.id !in hidden } ?: return
    Row(
        Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(12.dp)).background(BannerBlue).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("${r.title()} 포착", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Text("${hm.format(Date(r.startedAt))} 주차 중 · 차량 ${if (r.kind == EnergyKind.EV_CHARGE.name) "배터리" else "연료"} 신호 기준 · 금액만 넣으면 정산 끝", color = Sub, fontSize = 20.sp)
        }
        listOf(Purpose.BUSINESS, Purpose.PERSONAL).forEach { p -> Chip(p.label(), r.purpose == p.name) { graph.setRefillPurpose(r.id, p) } }
        Spacer(Modifier.width(8.dp))
        AmountEditor(graph, r)
        Text("나중에", color = Color.White, fontSize = 22.sp, modifier = Modifier.clickable { hidden = hidden + r.id }.padding(12.dp))
    }
}

/** 하루 마감 30초 — 미분류 운행, 금액 없는 주유·충전, 남은 할 일, 내일 일정을 한 화면에서 끝낸다. */
@Composable
internal fun DayClosePanel(graph: AppGraph) {
    val (from, to) = remember { dayRange() }
    val trips by graph.db.trips().observeBetween(from, to).collectAsState(initial = emptyList())
    val refills by graph.db.refills().observeBetween(from, to).collectAsState(initial = emptyList())
    val notes by graph.db.notes().observeAll().collectAsState(initial = emptyList())
    val guest = rememberGuest(graph)
    val unknown = trips.filter { it.purpose == Purpose.UNKNOWN.name }
    val missing = refills.filter { it.amount == null }
    val open = notes.filter { n -> !n.tasksDone && n.tasks.isNotBlank() }
    val openCount = open.sumOf { n -> n.tasks.lines().count { it.isNotBlank() } }
    val tomorrow = notes.filter { !it.tasksDone && (it.followUpAt ?: 0) in to until to + 24 * 60 * 60 * 1000L }
    val left = unknown.size + missing.size + openCount

    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("오늘 마감 30초", color = Active, fontSize = 34.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(20.dp))
            Text("차에서 내리기 전에 오늘 정리를 끝내요", color = Sub, fontSize = 22.sp, modifier = Modifier.weight(1f))
            Text("나중에", color = Color.White, fontSize = 22.sp, modifier = Modifier.clickable { graph.cancelDayClose() }.padding(12.dp))
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Stat("오늘 운행", "${trips.size}건")
            Stat("업무 거리", "%.1f km".format(trips.filter { it.purpose == Purpose.BUSINESS.name }.sumOf { it.distanceKm }))
            Stat("남은 정리", if (left == 0) "없음" else "${left}건", if (left > 0) Warn else Active)
        }
        Spacer(Modifier.height(12.dp))
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (unknown.isNotEmpty()) {
                item { Section("미분류 운행 — 업무·개인을 골라 주세요") }
                items(unknown, key = { "t${it.id}" }) { t -> TripRow(t) { p -> graph.setPurpose(t.id, p) } }
            }
            if (missing.isNotEmpty()) {
                item { Section("금액을 안 넣은 주유·충전") }
                items(missing, key = { "r${it.id}" }) { r -> RefillRow(graph, r) }
            }
            if (open.isNotEmpty()) {
                item { Section("남은 할 일 — 끝낸 건 눌러서 체크") }
                if (guest) item { Text("할 일 ${openCount}개 · 동승자 탑승 중이라 내용은 가렸어요", color = Sub, fontSize = 22.sp) }
                else items(open, key = { "n${it.id}" }) { n ->
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Card).clickable { graph.setTasksDone(n.id, true) }.padding(14.dp)) {
                        Text(n.clientName ?: "거래처 미지정", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                        n.tasks.lines().filter { it.isNotBlank() }.forEach { Text("☐ $it", color = Warn, fontSize = 22.sp) }
                    }
                }
            }
            if (tomorrow.isNotEmpty()) {
                item { Section("내일 후속 일정") }
                item {
                    Text(
                        if (guest) "${tomorrow.size}건 · 동승자 탑승 중이라 거래처명은 가렸어요" else tomorrow.mapNotNull { it.clientName }.distinct().joinToString("  ·  "),
                        color = Color.White, fontSize = 24.sp,
                    )
                }
            }
            if (left == 0) item { Text("정리할 게 없어요. 오늘 기록은 월말 정산에 그대로 들어가요.", color = Sub, fontSize = 24.sp) }
        }
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = { graph.finishDayClose() },
            colors = ButtonDefaults.buttonColors(containerColor = Active, contentColor = HyundaiBlue),
            modifier = Modifier.fillMaxWidth().height(72.dp),
        ) { Text(if (left == 0) "마감 완료" else "이대로 마감 (남은 ${left}건은 내일 다시)", fontSize = 26.sp, fontWeight = FontWeight.Bold) }
    }
}

@Composable
private fun Section(title: String) {
    Text(title, color = Active, fontSize = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
}
